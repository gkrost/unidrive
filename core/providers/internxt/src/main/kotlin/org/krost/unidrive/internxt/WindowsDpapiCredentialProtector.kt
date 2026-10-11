package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Protects credential payloads with Windows DPAPI, scoped to the current user. */
class WindowsDpapiCredentialProtector : CredentialProtector {
    override fun protect(id: String, plaintext: String): String {
        val ciphertext = crypt(plaintext.toByteArray(StandardCharsets.UTF_8), entropy(id), protect = true)
        val reference = try {
            "$PREFIX${Base64.getEncoder().encodeToString(ciphertext)}"
        } finally {
            ciphertext.fill(0)
        }
        val roundTrip = decode(id, reference).plaintext
        val expected = plaintext.toByteArray(StandardCharsets.UTF_8)
        val actual = roundTrip.toByteArray(StandardCharsets.UTF_8)
        val verified = MessageDigest.isEqual(expected, actual)
        expected.fill(0)
        actual.fill(0)
        if (!verified) {
            throw CredentialProtectionException("Windows DPAPI did not verify stored credentials")
        }
        return reference
    }

    override fun decode(id: String, stored: String): CredentialData {
        if (!stored.startsWith(PREFIX)) return CredentialData(stored, false)
        val encoded = stored.removePrefix(PREFIX)
        val ciphertext =
            runCatching { Base64.getDecoder().decode(encoded) }
                .getOrElse { throw CredentialProtectionException("Windows credential reference is invalid; refusing plaintext fallback", it) }
        val plaintext = crypt(ciphertext, entropy(id), protect = false)
        return try {
            CredentialData(String(plaintext, StandardCharsets.UTF_8), true)
        } finally {
            plaintext.fill(0)
        }
    }

    // DPAPI ciphertext is self-contained in credentials.json, so there is no external item to discard.
    override fun delete(id: String) = Unit

    override fun discard(id: String, reference: String) = Unit

    private fun crypt(
        input: ByteArray,
        entropy: ByteArray,
        protect: Boolean,
    ): ByteArray {
        try {
            return runCatching { performCrypt(input, entropy, protect) }
                .fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { cause ->
                        Result.failure(
                            cause as? CredentialProtectionException
                                ?: CredentialProtectionException(
                                    "Windows DPAPI is unavailable; credentials remain protected and the operation failed",
                                    cause,
                                ),
                        )
                    },
                ).getOrThrow()
        } finally {
            input.fill(0)
            entropy.fill(0)
        }
    }

    private fun performCrypt(input: ByteArray, entropy: ByteArray, protect: Boolean): ByteArray = Arena.ofConfined().use { arena ->
        val inputBlob = blob(arena, input)
        val entropyBlob = blob(arena, entropy)
        val outputBlob = arena.allocate(DATA_BLOB)
        if (invokeCrypt(inputBlob, entropyBlob, outputBlob, protect) == 0) {
            throw CredentialProtectionException("Windows DPAPI failed; check the current user profile and retry")
        }
        copyAndFreeOutput(outputBlob)
    }

    private fun invokeCrypt(input: MemorySegment, entropy: MemorySegment, output: MemorySegment, protect: Boolean): Int =
        (if (protect) cryptProtect else cryptUnprotect).invoke(
            input,
            MemorySegment.NULL,
            entropy,
            MemorySegment.NULL,
            MemorySegment.NULL,
            UI_FORBIDDEN,
            output,
        ) as Int

    private fun copyAndFreeOutput(outputBlob: MemorySegment): ByteArray {
        val output = outputBlob.get(ADDRESS, DATA_BLOB.byteOffset(MemoryLayout.PathElement.groupElement("data")))
        try {
            val size = outputBlob.get(JAVA_INT, 0)
            if (output == MemorySegment.NULL || size < 0) {
                throw CredentialProtectionException("Windows DPAPI returned invalid credential data")
            }
            return ByteArray(size).also {
                MemorySegment.copy(output.reinterpret(size.toLong()), 0, MemorySegment.ofArray(it), 0, size.toLong())
            }
        } finally {
            if (output != MemorySegment.NULL) localFree.invoke(output)
        }
    }

    private fun blob(
        arena: Arena,
        bytes: ByteArray,
    ): MemorySegment {
        val data = arena.allocate(bytes.size.toLong())
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, data, 0, bytes.size.toLong())
        return arena.allocate(DATA_BLOB).also {
            it.set(JAVA_INT, 0, bytes.size)
            it.set(ADDRESS, DATA_BLOB.byteOffset(MemoryLayout.PathElement.groupElement("data")), data)
        }
    }

    private fun entropy(id: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val PREFIX = "unidrive-dpapi-v1:"
        const val UI_FORBIDDEN = 0x1
        val JAVA_INT = ValueLayout.JAVA_INT
        val ADDRESS = ValueLayout.ADDRESS
        val DATA_BLOB =
            MemoryLayout.structLayout(
                JAVA_INT.withName("size"),
                MemoryLayout.paddingLayout(ADDRESS.byteAlignment() - JAVA_INT.byteAlignment()),
                ADDRESS.withName("data"),
            )
        val linker by lazy { Linker.nativeLinker() }
        val crypt32 by lazy { SymbolLookup.libraryLookup("crypt32", Arena.global()) }
        val kernel32 by lazy { SymbolLookup.libraryLookup("kernel32", Arena.global()) }
        val cryptProtect by lazy { downcall(crypt32, "CryptProtectData") }
        val cryptUnprotect by lazy { downcall(crypt32, "CryptUnprotectData") }
        val localFree: MethodHandle by lazy {
            linker.downcallHandle(
                kernel32.find("LocalFree").orElseThrow { IllegalStateException("LocalFree is unavailable") },
                FunctionDescriptor.of(ADDRESS, ADDRESS),
            )
        }

        private fun downcall(
            library: SymbolLookup,
            name: String,
        ): MethodHandle =
            linker.downcallHandle(
                library.find(name).orElseThrow { IllegalStateException("$name is unavailable") },
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
            )
    }
}
