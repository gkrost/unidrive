package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/**
 * Verifies file integrity after transfer using provider-specific hash algorithms.
 * Supports QuickXorHash (OneDrive), MD5/ETag (S3), and returns true for providers without hashes.
 */
object HashVerifier {
    /**
     * Verify that the downloaded file matches the remote hash.
     *
     * Returns true if hash matches, OR if the algorithm is null
     * (provider has no verifiable hash), OR if the remoteHash is
     * null/empty. Returns false only on actual mismatch.
     */
    fun verify(
        localPath: java.nio.file.Path,
        remoteHash: String?,
        algorithm: org.krost.unidrive.HashAlgorithm?,
    ): Boolean {
        if (remoteHash.isNullOrEmpty()) return true
        if (algorithm == null) return true

        return when (algorithm) {
            org.krost.unidrive.HashAlgorithm.QuickXor -> verifyQuickXorHash(localPath, remoteHash)
            org.krost.unidrive.HashAlgorithm.Md5Hex -> verifyS3ETag(localPath, remoteHash)
            org.krost.unidrive.HashAlgorithm.Sha256Hex -> verifySha256Hex(localPath, remoteHash)
        }
    }

    private fun verifySha256Hex(
        localPath: java.nio.file.Path,
        expectedHex: String,
    ): Boolean {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.nio.file.Files.newInputStream(localPath).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        val computed = md.digest().joinToString("") { "%02x".format(it) }
        return computed.equals(expectedHex, ignoreCase = true)
    }

    private fun verifyQuickXorHash(
        localPath: Path,
        expectedBase64: String,
    ): Boolean {
        val computed = computeQuickXorHash(localPath)
        return computed == expectedBase64
    }

    private fun verifyS3ETag(
        localPath: Path,
        expectedETag: String,
    ): Boolean {
        // Multipart ETags contain "-" (e.g. "abc123-3"), skip verification
        if (expectedETag.contains("-")) return true

        val computed = computeMd5Hex(localPath)
        return computed.equals(expectedETag, ignoreCase = true)
    }

    internal fun computeMd5Hex(path: Path): String {
        val md = MessageDigest.getInstance("MD5")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun computeQuickXorHash(path: Path): String {
        val qxh = QuickXorHash()
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                qxh.update(buffer, 0, read)
            }
        }
        return Base64.getEncoder().encodeToString(qxh.digest())
    }

    /**
     * QuickXorHash implementation matching Microsoft OneDrive spec.
     * 160-bit rolling XOR with shift-by-11, processes data in 64-byte blocks.
     */
    private class QuickXorHash {
        private val widthInBits = 160
        private val widthInBytes = widthInBits / 8
        private val shift = 11

        private val data = ByteArray(widthInBytes)
        private var shiftSoFar = 0
        private var lengthSoFar = 0L

        fun update(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            var currentOffset = offset
            var currentLength = length

            while (currentLength > 0) {
                val unchecked = widthInBytes - (shiftSoFar % widthInBytes)
                val amount = minOf(unchecked, currentLength)

                for (i in 0 until amount) {
                    val dataIndex = (shiftSoFar + i) % widthInBytes
                    data[dataIndex] = (data[dataIndex].toInt() xor buffer[currentOffset + i].toInt()).toByte()
                }

                currentOffset += amount
                currentLength -= amount
                shiftSoFar += shift * amount
                lengthSoFar += amount.toLong()
            }
        }

        fun digest(): ByteArray {
            // XOR length in little-endian into first 8 bytes
            var temp = lengthSoFar
            for (i in 0 until 8) {
                data[widthInBytes - 8 + i] = (data[widthInBytes - 8 + i].toInt() xor (temp and 0xFF).toInt()).toByte()
                temp = temp shr 8
            }

            // Rotate to normalize shift
            val result = ByteArray(widthInBytes)
            val normalizationShift = (widthInBits - (shiftSoFar % widthInBits)) % widthInBits

            for (i in 0 until widthInBytes) {
                val sourceIndex = (i + normalizationShift / 8) % widthInBytes
                val bitShift = normalizationShift % 8
                result[i] = ((data[sourceIndex].toInt() and 0xFF) shr bitShift).toByte()
                if (bitShift > 0) {
                    val nextIndex = (sourceIndex + 1) % widthInBytes
                    result[i] = (result[i].toInt() or ((data[nextIndex].toInt() and 0xFF) shl (8 - bitShift))).toByte()
                }
            }

            return result
        }
    }
}
