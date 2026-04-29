package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object XtraHeader {
    private val MAGIC = "XTRA".toByteArray()
    private const val VERSION: Byte = 0x01

    sealed class ReadResult {
        data class Valid(
            val algorithmId: Byte,
            val wrappedKey: ByteArray,
            val iv: ByteArray,
        ) : ReadResult()

        data object NotEncrypted : ReadResult()

        data class Corrupted(
            val reason: String,
        ) : ReadResult()
    }

    fun write(
        output: OutputStream,
        algorithmId: Byte,
        wrappedKey: ByteArray,
        iv: ByteArray,
    ) {
        output.write(MAGIC)
        output.write(VERSION.toInt())
        output.write(algorithmId.toInt())
        output.write(ByteBuffer.allocate(2).putShort(wrappedKey.size.toShort()).array())
        output.write(wrappedKey)
        output.write(ByteBuffer.allocate(2).putShort(iv.size.toShort()).array())
        output.write(iv)
    }

    fun read(input: InputStream): ReadResult {
        val magic = input.readNBytes(4)
        if (magic.size < 4 || !magic.contentEquals(MAGIC)) return ReadResult.NotEncrypted
        val va = input.readNBytes(2)
        if (va.size < 2) return ReadResult.Corrupted("Truncated after magic")
        if (va[0] != VERSION) return ReadResult.Corrupted("Unknown version: ${va[0]}")
        val algorithmId = va[1]
        val wkLenBytes = input.readNBytes(2)
        if (wkLenBytes.size < 2) return ReadResult.Corrupted("Missing wrapped key length")
        val wkLen = ByteBuffer.wrap(wkLenBytes).short.toInt() and 0xFFFF
        val wrappedKey = input.readNBytes(wkLen)
        if (wrappedKey.size < wkLen) return ReadResult.Corrupted("Wrapped key truncated")
        val ivLenBytes = input.readNBytes(2)
        if (ivLenBytes.size < 2) return ReadResult.Corrupted("Missing IV length")
        val ivLen = ByteBuffer.wrap(ivLenBytes).short.toInt() and 0xFFFF
        val iv = input.readNBytes(ivLen)
        if (iv.size < ivLen) return ReadResult.Corrupted("IV truncated")
        return ReadResult.Valid(algorithmId, wrappedKey, iv)
    }
}
