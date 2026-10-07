package org.krost.unidrive.io

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The advapi32 calls behind [OwnerOnly] on Windows: read a path's owner and DACL, replace the DACL
 * with a protected one given in SDDL, and identify the current user (token user SID, membership of
 * Administrators). `AclFileAttributeView` is not enough: it names SYSTEM and Administrators only
 * through localized account names, and it does not re-derive the entries of the files already in a
 * folder; `SetNamedSecurityInfoW` does.
 *
 * Only FFM members whose signatures are the same in JDK 21 (compile toolchain) and JDK 22+ (the
 * packaged runtime) are used; wide strings are therefore copied char by char.
 */
internal object WindowsSecurity {
    const val ADMINISTRATORS_SID = "S-1-5-32-544"

    private const val ERROR_FILE_NOT_FOUND = 2
    private const val ERROR_PATH_NOT_FOUND = 3
    private const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
    private const val IO_REPARSE_TAG_AF_UNIX = 0x80000023.toInt()
    private const val WIN32_FIND_DATA_SIZE = 592L
    private const val REPARSE_TAG_OFFSET = 36L
    private const val SE_FILE_OBJECT = 1
    private const val OWNER_SECURITY_INFORMATION = 0x1
    private const val DACL_SECURITY_INFORMATION = 0x4
    private const val PROTECTED_DACL_SECURITY_INFORMATION = 0x80000000.toInt()
    private const val SDDL_REVISION_1 = 1
    private const val TOKEN_QUERY = 0x8
    private const val TOKEN_USER = 1
    private const val TOKEN_USER_BUFFER = 256L

    private val INT = ValueLayout.JAVA_INT
    private val PTR = ValueLayout.ADDRESS

    private val linker by lazy { Linker.nativeLinker() }
    private val advapi32 by lazy { SymbolLookup.libraryLookup("advapi32", Arena.global()) }
    private val kernel32 by lazy { SymbolLookup.libraryLookup("kernel32", Arena.global()) }
    private val callState by lazy { Linker.Option.captureStateLayout() }
    private val lastErrorOffset by lazy { callState.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError")) }

    /** A BOOL-returning function whose first argument receives the thread's last error. */
    private fun withLastError(
        lib: SymbolLookup,
        name: String,
        vararg args: MemoryLayout,
    ): MethodHandle =
        linker.downcallHandle(
            lib.find(name).orElseThrow { IOException("$name not found") },
            FunctionDescriptor.of(INT, *args),
            Linker.Option.captureCallState("GetLastError"),
        )

    private fun plain(
        lib: SymbolLookup,
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle = linker.downcallHandle(lib.find(name).orElseThrow { IOException("$name not found") }, descriptor)

    private val findFirstFile by lazy {
        linker.downcallHandle(
            kernel32.find("FindFirstFileW").orElseThrow { IOException("FindFirstFileW not found") },
            FunctionDescriptor.of(PTR, PTR, PTR),
            Linker.Option.captureCallState("GetLastError"),
        )
    }
    private val findClose by lazy { plain(kernel32, "FindClose", FunctionDescriptor.of(INT, PTR)) }
    private val getCurrentProcess by lazy { plain(kernel32, "GetCurrentProcess", FunctionDescriptor.of(PTR)) }
    private val closeHandle by lazy { plain(kernel32, "CloseHandle", FunctionDescriptor.of(INT, PTR)) }
    private val localFree by lazy { plain(kernel32, "LocalFree", FunctionDescriptor.of(PTR, PTR)) }
    private val openProcessToken by lazy { withLastError(advapi32, "OpenProcessToken", PTR, INT, PTR) }
    private val getTokenInformation by lazy { withLastError(advapi32, "GetTokenInformation", PTR, INT, PTR, INT, PTR) }
    private val convertSidToStringSid by lazy { withLastError(advapi32, "ConvertSidToStringSidW", PTR, PTR) }
    private val convertStringSidToSid by lazy { withLastError(advapi32, "ConvertStringSidToSidW", PTR, PTR) }
    private val checkTokenMembership by lazy { withLastError(advapi32, "CheckTokenMembership", PTR, PTR, PTR) }
    private val stringToSecurityDescriptor by lazy {
        withLastError(advapi32, "ConvertStringSecurityDescriptorToSecurityDescriptorW", PTR, INT, PTR, PTR)
    }
    private val securityDescriptorToString by lazy {
        withLastError(advapi32, "ConvertSecurityDescriptorToStringSecurityDescriptorW", PTR, INT, INT, PTR, PTR)
    }
    private val getSecurityDescriptorDacl by lazy { withLastError(advapi32, "GetSecurityDescriptorDacl", PTR, PTR, PTR, PTR) }

    // These two return the error code instead of setting the last error.
    private val getNamedSecurityInfo by lazy {
        plain(advapi32, "GetNamedSecurityInfoW", FunctionDescriptor.of(INT, PTR, INT, INT, PTR, PTR, PTR, PTR, PTR))
    }
    private val setNamedSecurityInfo by lazy {
        plain(advapi32, "SetNamedSecurityInfoW", FunctionDescriptor.of(INT, PTR, INT, INT, PTR, PTR, PTR, PTR))
    }

    /** The owner SID (`S-1-...`) and the DACL (SDDL, `D:...`) of a path. */
    data class Security(
        val ownerSid: String,
        val dacl: String,
    )

    /** SID of the user this process runs as (the token user, not a file owner). */
    val currentUserSid: String by lazy { tokenUserSid() }

    /** True when this process's token is a member of Administrators (elevated; CheckTokenMembership semantics). */
    val processIsAdministrator: Boolean by lazy { isAdministratorsMember() }

    fun isUnixSocket(path: Path): Boolean =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(callState)
            // WIN32_FIND_DATAW: DWORD attributes, three FILETIMEs, size fields, then dwReserved0.
            val data = arena.allocate(WIN32_FIND_DATA_SIZE, 4)
            val handle = findFirstFile.invoke(state, arena.wide(path.toAbsolutePath().toString()), data) as MemorySegment
            if (handle.address() == -1L) throw failure("FindFirstFileW", path, lastError(state))
            try {
                data.get(INT, 0) and FILE_ATTRIBUTE_REPARSE_POINT != 0 &&
                    data.get(INT, REPARSE_TAG_OFFSET) == IO_REPARSE_TAG_AF_UNIX
            } finally {
                findClose.invoke(handle)
            }
        }

    fun read(path: Path): Security =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(callState)
            val ownerOut = arena.allocate(PTR)
            val sdOut = arena.allocate(PTR)
            val rc =
                getNamedSecurityInfo.invoke(
                    arena.wide(path.toAbsolutePath().toString()),
                    SE_FILE_OBJECT,
                    OWNER_SECURITY_INFORMATION or DACL_SECURITY_INFORMATION,
                    ownerOut,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    sdOut,
                ) as Int
            if (rc != 0) throw failure("GetNamedSecurityInfoW", path, rc)
            val sd = sdOut.get(PTR, 0)
            try {
                val owner = sidToString(arena, state, ownerOut.get(PTR, 0))
                Security(owner, securityDescriptorDacl(arena, state, sd))
            } finally {
                localFree.invoke(sd)
            }
        }

    /** The DACL of [sddl] as Windows writes it back (aliases and order applied). */
    fun canonicalDacl(sddl: String): String =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(callState)
            val sd = parseSecurityDescriptor(arena, state, sddl)
            try {
                securityDescriptorDacl(arena, state, sd)
            } finally {
                localFree.invoke(sd)
            }
        }

    /**
     * Replace the DACL of [path] with the one in [sddl], marked protected (no entries inherited from
     * the parent). On a folder, Windows re-derives the inherited entries of everything already in it.
     */
    fun setProtectedDacl(
        path: Path,
        sddl: String,
    ) {
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(callState)
            val sd = parseSecurityDescriptor(arena, state, sddl)
            try {
                val present = arena.allocate(INT)
                val defaulted = arena.allocate(INT)
                val daclOut = arena.allocate(PTR)
                if (getSecurityDescriptorDacl.invoke(state, sd, present, daclOut, defaulted) as Int == 0) {
                    throw IOException("GetSecurityDescriptorDacl failed with error ${lastError(state)}")
                }
                val rc =
                    setNamedSecurityInfo.invoke(
                        arena.wide(path.toAbsolutePath().toString()),
                        SE_FILE_OBJECT,
                        DACL_SECURITY_INFORMATION or PROTECTED_DACL_SECURITY_INFORMATION,
                        MemorySegment.NULL,
                        MemorySegment.NULL,
                        daclOut.get(PTR, 0),
                        MemorySegment.NULL,
                    ) as Int
                if (rc != 0) throw failure("SetNamedSecurityInfoW", path, rc)
            } finally {
                localFree.invoke(sd)
            }
        }
    }

    private fun tokenUserSid(): String =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(callState)
            val tokenOut = arena.allocate(PTR)
            val process = getCurrentProcess.invoke() as MemorySegment
            if (openProcessToken.invoke(state, process, TOKEN_QUERY, tokenOut) as Int == 0) {
                throw IOException("OpenProcessToken failed with error ${lastError(state)}")
            }
            val token = tokenOut.get(PTR, 0)
            try {
                val buffer = arena.allocate(TOKEN_USER_BUFFER, 8)
                val length = arena.allocate(INT)
                if (getTokenInformation.invoke(state, token, TOKEN_USER, buffer, TOKEN_USER_BUFFER.toInt(), length) as Int == 0) {
                    throw IOException("GetTokenInformation failed with error ${lastError(state)}")
                }
                // TOKEN_USER starts with SID_AND_ATTRIBUTES, whose first field is the SID pointer.
                sidToString(arena, state, buffer.get(PTR, 0))
            } finally {
                closeHandle.invoke(token)
            }
        }

    private fun isAdministratorsMember(): Boolean =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(callState)
            val sidOut = arena.allocate(PTR)
            if (convertStringSidToSid.invoke(state, arena.wide(ADMINISTRATORS_SID), sidOut) as Int == 0) {
                throw IOException("ConvertStringSidToSidW failed with error ${lastError(state)}")
            }
            val sid = sidOut.get(PTR, 0)
            try {
                val member = arena.allocate(INT)
                if (checkTokenMembership.invoke(state, MemorySegment.NULL, sid, member) as Int == 0) {
                    throw IOException("CheckTokenMembership failed with error ${lastError(state)}")
                }
                member.get(INT, 0) != 0
            } finally {
                localFree.invoke(sid)
            }
        }

    private fun parseSecurityDescriptor(
        arena: Arena,
        state: MemorySegment,
        sddl: String,
    ): MemorySegment {
        val sdOut = arena.allocate(PTR)
        if (stringToSecurityDescriptor.invoke(state, arena.wide(sddl), SDDL_REVISION_1, sdOut, MemorySegment.NULL) as Int == 0) {
            throw IOException("ConvertStringSecurityDescriptorToSecurityDescriptorW failed with error ${lastError(state)}")
        }
        return sdOut.get(PTR, 0)
    }

    private fun securityDescriptorDacl(
        arena: Arena,
        state: MemorySegment,
        sd: MemorySegment,
    ): String {
        val textOut = arena.allocate(PTR)
        val ok =
            securityDescriptorToString.invoke(state, sd, SDDL_REVISION_1, DACL_SECURITY_INFORMATION, textOut, MemorySegment.NULL) as Int
        if (ok == 0) throw IOException("ConvertSecurityDescriptorToStringSecurityDescriptorW failed with error ${lastError(state)}")
        val text = textOut.get(PTR, 0)
        try {
            return readWide(text)
        } finally {
            localFree.invoke(text)
        }
    }

    private fun sidToString(
        arena: Arena,
        state: MemorySegment,
        sid: MemorySegment,
    ): String {
        val textOut = arena.allocate(PTR)
        if (convertSidToStringSid.invoke(state, sid, textOut) as Int == 0) {
            throw IOException("ConvertSidToStringSidW failed with error ${lastError(state)}")
        }
        val text = textOut.get(PTR, 0)
        try {
            return readWide(text)
        } finally {
            localFree.invoke(text)
        }
    }

    private fun lastError(state: MemorySegment): Int = state.get(INT, lastErrorOffset)

    /**
     * The exception for [call] on [path] failing with Windows error [code]: [NoSuchFileException] when
     * the path or a folder on it does not exist (any more), so callers can tell that case apart.
     */
    private fun failure(
        call: String,
        path: Path,
        code: Int,
    ): IOException =
        if (code == ERROR_FILE_NOT_FOUND || code == ERROR_PATH_NOT_FOUND) {
            NoSuchFileException(path.toString(), null, "$call failed with error $code")
        } else {
            IOException("$call($path) failed with error $code")
        }

    /** A NUL-terminated UTF-16 copy of [s]. */
    private fun Arena.wide(s: String): MemorySegment {
        val segment = allocate((s.length + 1) * 2L, 2)
        for (i in s.indices) segment.set(ValueLayout.JAVA_CHAR, i * 2L, s[i])
        segment.set(ValueLayout.JAVA_CHAR, s.length * 2L, Char(0))
        return segment
    }

    /** The NUL-terminated UTF-16 string at [pointer] (memory owned by Windows). */
    private fun readWide(pointer: MemorySegment): String {
        val segment = pointer.reinterpret(Int.MAX_VALUE.toLong())
        val text = StringBuilder()
        var offset = 0L
        while (true) {
            val c = segment.get(ValueLayout.JAVA_CHAR, offset)
            if (c == Char(0)) return text.toString()
            text.append(c)
            offset += 2
        }
    }
}
