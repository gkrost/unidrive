package org.krost.unidrive.internxt

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** #940: the recorded shard hash is parsed by its shape, and checked with the algorithm that shape names. */
class ShardHashExpectationTest {
    private val body = "0123456789A".toByteArray()
    private val bodySha256 = MessageDigest.getInstance("SHA-256").digest(body)
    private val bodySha256Hex = bodySha256.joinToString("") { "%02x".format(it) }

    // RIPEMD-160(SHA-256("0123456789A")), computed independently with Node's crypto module.
    private val bodyChainHex = "539cef25668d4b91379ec682f19cd7a1169f2701"

    @Test
    fun `64 hex characters are a SHA-256 of the ciphertext`() {
        val expectation = ShardHashExpectation.parse(bodySha256Hex)

        assertIs<ShardHashExpectation.Sha256>(expectation)
        assertEquals("SHA-256", expectation.algorithm)
        assertTrue(expectation.matches(bodySha256))
        assertFalse(expectation.matches(MessageDigest.getInstance("SHA-256").digest("other".toByteArray())))
    }

    @Test
    fun `40 hex characters are the RIPEMD-160 of the SHA-256 of the ciphertext`() {
        val expectation = ShardHashExpectation.parse(bodyChainHex)

        assertIs<ShardHashExpectation.Ripemd160OfSha256>(expectation)
        assertEquals("RIPEMD-160(SHA-256)", expectation.algorithm)
        assertTrue(expectation.matches(bodySha256), "the known chain of this body verifies")
        assertFalse(
            expectation.matches(MessageDigest.getInstance("SHA-256").digest("other".toByteArray())),
            "another ciphertext does not",
        )
    }

    @Test
    fun `upper case hex is accepted in both shapes`() {
        assertTrue(ShardHashExpectation.parse(bodyChainHex.uppercase()).let { it is ShardHashExpectation.Checkable && it.matches(bodySha256) })
        assertTrue(ShardHashExpectation.parse(bodySha256Hex.uppercase()).let { it is ShardHashExpectation.Checkable && it.matches(bodySha256) })
    }

    @Test
    fun `a 40 character value is not checked as a SHA-256 and a 64 character value not as a RIPEMD-160`() {
        // The 40 hex of a body's chain must not verify a SHA-256 comparison, and vice versa.
        val forty = ShardHashExpectation.parse(bodyChainHex) as ShardHashExpectation.Checkable
        val sixtyFour = ShardHashExpectation.parse(bodySha256Hex) as ShardHashExpectation.Checkable
        assertFalse(forty.matches(ByteArray(32)))
        assertFalse(sixtyFour.matches(ByteArray(32)))
    }

    @Test
    fun `other lengths of hex are unrecognised`() {
        for (length in listOf(1, 32, 39, 41, 63, 65, 128)) {
            val expectation = ShardHashExpectation.parse("ab".repeat(length).take(length))
            assertIs<ShardHashExpectation.Unrecognised>(expectation, "length $length")
            assertEquals(length, expectation.length)
            assertEquals("hex", expectation.characterClass)
            assertEquals("length $length, hex", expectation.description)
        }
    }

    @Test
    fun `an empty or blank value is unrecognised and says so`() {
        val empty = assertIs<ShardHashExpectation.Unrecognised>(ShardHashExpectation.parse(""))
        assertEquals("length 0, empty", empty.description)
        val blank = assertIs<ShardHashExpectation.Unrecognised>(ShardHashExpectation.parse(" ".repeat(40)))
        assertEquals("length 40, other", blank.description)
    }

    @Test
    fun `a value of a known length that is not hex is unrecognised, with its character class`() {
        val letters = assertIs<ShardHashExpectation.Unrecognised>(ShardHashExpectation.parse("g".repeat(40)))
        assertEquals("length 40, base64", letters.description)
        val symbols = assertIs<ShardHashExpectation.Unrecognised>(ShardHashExpectation.parse("!".repeat(64)))
        assertEquals("length 64, other", symbols.description)
        // Digits of other scripts are not hex digits here (Character.digit would accept them).
        val arabicIndic = assertIs<ShardHashExpectation.Unrecognised>(ShardHashExpectation.parse("٠".repeat(40)))
        assertEquals("length 40, other", arabicIndic.description)
    }

    @Test
    fun `a base64 digest is unrecognised`() {
        val base64 = java.util.Base64.getEncoder().encodeToString(bodySha256)
        val expectation = assertIs<ShardHashExpectation.Unrecognised>(ShardHashExpectation.parse(base64))
        assertEquals("length 44, base64", expectation.description)
    }

    @Test
    fun `no description carries the recorded value`() {
        for (value in listOf(bodySha256Hex, bodyChainHex, "secretvalue".repeat(4), "")) {
            val description = ShardHashExpectation.parse(value).description
            assertFalse(value.isNotEmpty() && description.contains(value), "description must not echo the value: $description")
        }
    }
}
