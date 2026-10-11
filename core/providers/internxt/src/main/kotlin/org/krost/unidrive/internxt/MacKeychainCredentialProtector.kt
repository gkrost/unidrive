package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Stores Internxt credentials in the current macOS user's login Keychain. */
class MacKeychainCredentialProtector : CredentialProtector {
    override fun protect(id: String, plaintext: String): String {
        requireMacOS()
        val revision = UUID.randomUUID().toString()
        val account = keyFor(id)
        keychain.add(account, revision, plaintext)
        try {
            if (keychain.lookup(account, revision) != plaintext) {
                throw CredentialProtectionException("macOS Keychain did not verify stored credentials")
            }
        } catch (e: CredentialProtectionException) {
            runCatching { keychain.delete(account, revision) }
            throw e
        }
        return "$STORED_PREFIX$revision"
    }

    override fun decode(id: String, stored: String): CredentialData {
        if (!stored.startsWith(STORED_PREFIX)) return CredentialData(stored, false)
        val revision = stored.removePrefix(STORED_PREFIX)
        if (runCatching { UUID.fromString(revision) }.isFailure) {
            throw CredentialProtectionException("Internxt credential reference is invalid; refusing plaintext fallback")
        }
        requireMacOS()
        return CredentialData(keychain.lookup(keyFor(id), revision), true)
    }

    override fun delete(id: String) {
        requireMacOS()
        keychain.delete(keyFor(id), revision = null)
    }

    override fun discard(id: String, reference: String) {
        if (!reference.startsWith(STORED_PREFIX)) return
        val revision = reference.removePrefix(STORED_PREFIX)
        if (runCatching { UUID.fromString(revision) }.isFailure) return
        requireMacOS()
        keychain.delete(keyFor(id), revision)
    }

    private fun requireMacOS() {
        if (!System.getProperty("os.name", "").contains("mac", ignoreCase = true)) {
            throw CredentialProtectionException("macOS Keychain is only available on macOS")
        }
    }

    private fun keyFor(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val STORED_PREFIX = "unidrive-keychain-v1:"
    }
}

/** Thin Security.framework binding; keeping it private avoids exposing FFM details to callers. */
@Suppress("TooGenericExceptionCaught")
private class MacKeychain {
    fun add(account: String, revision: String, secret: String) = withArena { operation ->
        val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)
        try {
            val attributes = itemAttributes(operation, account, revision, includeRevision = true, noPrompt = true) +
                (constant(security, "kSecValueData") to data(operation, secretBytes))
            val status = secItemAdd.invokeWithArguments(dictionary(operation, attributes), MemorySegment.NULL) as Int
            checkStatus(status, "store credentials")
        } finally {
            secretBytes.fill(0)
        }
    }

    fun lookup(account: String, revision: String): String = withArena { operation ->
        val attributes = itemAttributes(operation, account, revision, includeRevision = true, noPrompt = true) +
            (constant(security, "kSecReturnData") to constant(coreFoundation, "kCFBooleanTrue")) +
            (constant(security, "kSecMatchLimit") to constant(security, "kSecMatchLimitOne"))
        val query = dictionary(operation, attributes)
        val result = operation.arena.allocate(ValueLayout.ADDRESS).also { it.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL) }
        val status = secItemCopyMatching.invokeWithArguments(query, result) as Int
        checkStatus(status, "read credentials")
        val item = result.get(ValueLayout.ADDRESS, 0)
        if (item == MemorySegment.NULL) throw CredentialProtectionException("macOS Keychain returned no credential data")
        try {
            val length = cfDataGetLength.invokeWithArguments(item) as Long
            if (length < 0 || length > Int.MAX_VALUE) {
                throw CredentialProtectionException("macOS Keychain credential data has an invalid length")
            }
            val bytes = ByteArray(length.toInt())
            if (bytes.isNotEmpty()) {
                val source = cfDataGetBytePtr.invokeWithArguments(item) as MemorySegment
                MemorySegment.copy(
                    source.reinterpret(bytes.size.toLong()),
                    ValueLayout.JAVA_BYTE,
                    0,
                    bytes,
                    0,
                    bytes.size,
                )
            }
            try {
                String(bytes, StandardCharsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        } finally {
            cfRelease.invokeWithArguments(item)
        }
    }

    fun delete(account: String, revision: String?) = withArena { operation ->
        val query = dictionary(
            operation,
            itemAttributes(operation, account, revision, includeRevision = revision != null, noPrompt = true),
        )
        val status = secItemDelete.invokeWithArguments(query) as Int
        if (status != ERR_SEC_ITEM_NOT_FOUND) checkStatus(status, "remove credentials")
    }

    private fun itemAttributes(
        operation: Operation,
        account: String,
        revision: String?,
        includeRevision: Boolean,
        noPrompt: Boolean,
    ): List<Pair<MemorySegment, MemorySegment>> = buildList {
        add(constant(security, "kSecClass") to constant(security, "kSecClassGenericPassword"))
        add(constant(security, "kSecAttrService") to cfString(operation, SERVICE))
        if (includeRevision && revision != null) {
            // Account is unique per version so a failed file transaction can discard only its
            // new item. A stable label lets logout remove every version for this profile.
            add(constant(security, "kSecAttrAccount") to cfString(operation, "$account:$revision"))
        }
        add(constant(security, "kSecAttrLabel") to cfString(operation, account))
        if (noPrompt) add(constant(security, "kSecUseAuthenticationUI") to constant(security, "kSecUseAuthenticationUIFail"))
    }

    private fun dictionary(
        operation: Operation,
        entries: List<Pair<MemorySegment, MemorySegment>>,
    ): MemorySegment {
        val arena = operation.arena
        val keys = arena.allocate(ValueLayout.ADDRESS.byteSize() * entries.size, ValueLayout.ADDRESS.byteAlignment())
        val values = arena.allocate(ValueLayout.ADDRESS.byteSize() * entries.size, ValueLayout.ADDRESS.byteAlignment())
        entries.forEachIndexed { index, (key, value) ->
            keys.setAtIndex(ValueLayout.ADDRESS, index.toLong(), key)
            values.setAtIndex(ValueLayout.ADDRESS, index.toLong(), value)
        }
        val callbacks = symbol(coreFoundation, "kCFTypeDictionaryKeyCallBacks")
        val valueCallbacks = symbol(coreFoundation, "kCFTypeDictionaryValueCallBacks")
        val result = cfDictionaryCreate.invokeWithArguments(
            MemorySegment.NULL,
            keys,
            values,
            entries.size.toLong(),
            callbacks,
            valueCallbacks,
        ) as MemorySegment
        requireNativeObject(result, "create Keychain query")
        operation.owned.add(result)
        return result
    }

    private fun cfString(operation: Operation, value: String): MemorySegment {
        val bytes = value.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
        val cString = operation.arena.allocate(bytes.size.toLong(), 1).also { MemorySegment.copy(bytes, 0, it, ValueLayout.JAVA_BYTE, 0, bytes.size) }
        val result = cfStringCreateWithCString.invokeWithArguments(MemorySegment.NULL, cString, UTF8_ENCODING) as MemorySegment
        requireNativeObject(result, "create Keychain attribute")
        operation.owned.add(result)
        return result
    }

    private fun data(operation: Operation, bytes: ByteArray): MemorySegment {
        val source = operation.arena.allocate(maxOf(1, bytes.size).toLong(), 1)
        if (bytes.isNotEmpty()) MemorySegment.copy(bytes, 0, source, ValueLayout.JAVA_BYTE, 0, bytes.size)
        try {
            val value = cfDataCreate.invokeWithArguments(MemorySegment.NULL, source, bytes.size.toLong()) as MemorySegment
            requireNativeObject(value, "create Keychain credential data")
            operation.owned.add(value)
            return value
        } finally {
            source.fill(0)
        }
    }

    private fun constant(library: SymbolLookup, name: String): MemorySegment =
        symbol(library, name).reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0)

    private fun symbol(library: SymbolLookup, name: String): MemorySegment =
        library.find(name).orElseThrow { IllegalStateException("macOS system symbol $name is unavailable") }

    private fun checkStatus(status: Int, operation: String) {
        if (status != ERR_SEC_SUCCESS) {
            throw CredentialProtectionException("macOS Keychain could not $operation (status $status)")
        }
    }

    private fun requireNativeObject(value: MemorySegment, operation: String): MemorySegment {
        if (value == MemorySegment.NULL) throw CredentialProtectionException("macOS Keychain could not $operation")
        return value
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount", "InstanceOfCheckForException")
    private inline fun <T> withArena(block: (Operation) -> T): T {
        requireMacOS()
        val operation = Operation(Arena.ofConfined())
        return try {
            block(operation)
        } catch (e: RuntimeException) {
            throw if (e is CredentialProtectionException) e else CredentialProtectionException("macOS Keychain operation failed", e)
        } catch (e: LinkageError) {
            throw CredentialProtectionException("macOS Keychain operation failed", e)
        } finally {
            operation.owned.asReversed().forEach { runCatching { cfRelease.invokeWithArguments(it) } }
            operation.arena.close()
        }
    }

    private data class Operation(val arena: Arena, val owned: MutableList<MemorySegment> = mutableListOf())

    private fun requireMacOS() {
        if (!System.getProperty("os.name", "").contains("mac", ignoreCase = true)) {
            throw CredentialProtectionException("macOS Keychain is only available on macOS")
        }
    }

    private fun loadLibrary(path: String, arena: Arena): SymbolLookup =
        SymbolLookup.libraryLookup(path, arena)

    private val libraryArena = Arena.ofShared()
    private val coreFoundation: SymbolLookup
    private val security: SymbolLookup
    private val cfStringCreateWithCString: MethodHandle
    private val cfDataCreate: MethodHandle
    private val cfDataGetLength: MethodHandle
    private val cfDataGetBytePtr: MethodHandle
    private val cfDictionaryCreate: MethodHandle
    private val cfRelease: MethodHandle
    private val secItemAdd: MethodHandle
    private val secItemCopyMatching: MethodHandle
    private val secItemDelete: MethodHandle

    init {
        try {
            coreFoundation = loadLibrary(CORE_FOUNDATION_PATH, libraryArena)
            security = loadLibrary(SECURITY_PATH, libraryArena)
            val linker = Linker.nativeLinker()
            cfStringCreateWithCString = linker.downcallHandle(
                symbol(coreFoundation, "CFStringCreateWithCString"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            )
            cfDataCreate = linker.downcallHandle(
                symbol(coreFoundation, "CFDataCreate"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            )
            cfDataGetLength = linker.downcallHandle(
                symbol(coreFoundation, "CFDataGetLength"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
            )
            cfDataGetBytePtr = linker.downcallHandle(
                symbol(coreFoundation, "CFDataGetBytePtr"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )
            cfDictionaryCreate = linker.downcallHandle(
                symbol(coreFoundation, "CFDictionaryCreate"),
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                ),
            )
            cfRelease = linker.downcallHandle(
                symbol(coreFoundation, "CFRelease"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            )
            secItemAdd = linker.downcallHandle(
                symbol(security, "SecItemAdd"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )
            secItemCopyMatching = linker.downcallHandle(
                symbol(security, "SecItemCopyMatching"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )
            secItemDelete = linker.downcallHandle(
                symbol(security, "SecItemDelete"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            )
        } catch (e: RuntimeException) {
            throw CredentialProtectionException("macOS Keychain is unavailable", e)
        } catch (e: LinkageError) {
            throw CredentialProtectionException("macOS Keychain is unavailable", e)
        }
    }

    private companion object {
        const val SERVICE = "org.krost.unidrive.provider-credentials"
        const val UTF8_ENCODING = 0x08000100
        const val ERR_SEC_SUCCESS = 0
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
        const val CORE_FOUNDATION_PATH = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        const val SECURITY_PATH = "/System/Library/Frameworks/Security.framework/Security"
    }
}

private val keychain: MacKeychain by lazy { MacKeychain() }
