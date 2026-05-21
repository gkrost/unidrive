package org.krost.unidrive.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * UD-351: pin the PKCE helper output shape against RFC 7636.
 */
class PkceTest {
    @Test
    fun `generateVerifier produces approximately 43 char URL-Base64 string`() {
        val v = Pkce.generateVerifier()
        // 32 bytes * 4 / 3 = 42.67, padded to 43 (no padding character).
        assertEquals(43, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "URL-Base64 alphabet only; got: $v")
        assertTrue(!v.endsWith("="), "no padding; got: $v")
    }

    @Test
    fun `each generateVerifier returns a different value`() {
        // Cryptographically random; collision probability is negligible.
        val a = Pkce.generateVerifier()
        val b = Pkce.generateVerifier()
        assertNotEquals(a, b)
    }

    @Test
    fun `generateChallenge produces 43 char URL-Base64 string`() {
        // SHA-256 = 32 bytes, same Base64 length as the verifier.
        val v = Pkce.generateVerifier()
        val c = Pkce.generateChallenge(v)
        assertEquals(43, c.length)
        assertTrue(c.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(!c.endsWith("="))
    }

    @Test
    fun `generateChallenge - RFC 7636 Appendix B test vector`() {
        // RFC 7636 Appendix B: verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        // challenge SHA-256 → URL-Base64-no-padding = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, Pkce.generateChallenge(verifier))
    }

    @Test
    fun `generateChallenge is deterministic for the same verifier`() {
        val v = "test-verifier-stable"
        assertEquals(Pkce.generateChallenge(v), Pkce.generateChallenge(v))
    }
}
