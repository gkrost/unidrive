package org.krost.unidrive.internxt

import java.security.MessageDigest

/**
 * The hash the network recorded for a stored shard (`shards[].hash` of the bridge info), parsed by its shape, and
 * how to check the downloaded ciphertext against it (#940).
 *
 * Two shapes are known:
 *  - 40 hex characters: RIPEMD-160(SHA-256(ciphertext)). Every official client sends this in `files/finish`
 *    (inxt-js `HashStream.getHash`, drive-web `getRipemd160FromHex`, drive-mobile, the Swift and Android SDKs), so it
 *    is the shape of every object those clients stored.
 *  - 64 hex characters: SHA-256(ciphertext). What unidrive's own uploads record.
 * Anything else (blank, another length, not hex) cannot be checked here and is [Unrecognised].
 *
 * Nothing here ever holds or prints the recorded value in a message: [description] says its length and character
 * class only.
 */
internal sealed interface ShardHashExpectation {
    /** What a log line or message may say about the recorded value: its shape, never the value. */
    val description: String

    /** A shape this module can check the ciphertext against. */
    sealed interface Checkable : ShardHashExpectation {
        /** The algorithm the downloaded ciphertext is hashed with to compare, for messages. */
        val algorithm: String

        /** True when [ciphertextSha256] (the SHA-256 digest of the whole downloaded ciphertext) matches the recorded hash. */
        fun matches(ciphertextSha256: ByteArray): Boolean
    }

    class Sha256(
        private val expected: ByteArray,
    ) : Checkable {
        override val algorithm: String = "SHA-256"
        override val description: String = "length 64, hex"

        override fun matches(ciphertextSha256: ByteArray): Boolean = MessageDigest.isEqual(expected, ciphertextSha256)
    }

    class Ripemd160OfSha256(
        private val expected: ByteArray,
    ) : Checkable {
        override val algorithm: String = "RIPEMD-160(SHA-256)"
        override val description: String = "length 40, hex"

        override fun matches(ciphertextSha256: ByteArray): Boolean = MessageDigest.isEqual(expected, Ripemd160.digest(ciphertextSha256))
    }

    /** A recorded value in no shape this module knows. Carries only its length and character class. */
    class Unrecognised(
        val length: Int,
        val characterClass: String,
    ) : ShardHashExpectation {
        override val description: String = "length $length, $characterClass"
    }

    companion object {
        private const val SHA256_HEX_LENGTH = 64
        private const val RIPEMD160_HEX_LENGTH = 40
        private const val ASCII_LIMIT = 128

        fun parse(recorded: String): ShardHashExpectation =
            when {
                recorded.isHex() && recorded.length == SHA256_HEX_LENGTH -> Sha256(InternxtCrypto.hexToBytes(recorded))
                recorded.isHex() && recorded.length == RIPEMD160_HEX_LENGTH -> Ripemd160OfSha256(InternxtCrypto.hexToBytes(recorded))
                else -> Unrecognised(recorded.length, characterClassOf(recorded))
            }

        // Character.digit also accepts digits of other scripts: only ASCII counts as hex here.
        private fun String.isHex(): Boolean = isNotEmpty() && all { it.code < ASCII_LIMIT && Character.digit(it, 16) >= 0 }

        private fun characterClassOf(value: String): String =
            when {
                value.isEmpty() -> "empty"
                value.isHex() -> "hex"
                value.all { it.isBase64Character() } -> "base64"
                else -> "other"
            }

        private fun Char.isBase64Character(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "+/=-_"
    }
}
