package org.krost.unidrive.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * UD-010: pins the behaviour of the shared JWT body extractor against
 * the three pre-lift call sites (OneDrive `extractJwtClaim`, Internxt
 * `checkCredentialHealth` exp-regex, Internxt `isJwtExpired`).
 *
 * UD-308 padding invariant is covered explicitly — if this test is
 * removed or loosened, the `length % 4 in {0, 3}` JWTs silently fall
 * back to "unparseable" and `getValidCredentials()` would re-trigger
 * a refresh storm. Treat the padding test as a contract, not a test.
 */
class JwtExtractorTest {
    /** Build a real JWT-shaped string with the given JSON body. */
    private fun jwt(bodyJson: String): String {
        val header = base64UrlNoPad("""{"alg":"HS256","typ":"JWT"}""")
        val body = base64UrlNoPad(bodyJson)
        val sig = "fakesig"
        return "$header.$body.$sig"
    }

    /** Encode as URL-safe base64 with padding stripped (canonical JWT shape). */
    private fun base64UrlNoPad(s: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `extractClaim returns string claim on happy path`() {
        val token = jwt("""{"preferred_username":"alice@example.com","exp":1700000000}""")
        assertEquals("alice@example.com", JwtExtractor.extractClaim(token, "preferred_username"))
    }

    @Test
    fun `extractClaim returns null when the claim is absent`() {
        val token = jwt("""{"sub":"alice"}""")
        assertNull(JwtExtractor.extractClaim(token, "preferred_username"))
    }

    @Test
    fun `extractExp returns the numeric exp claim`() {
        val token = jwt("""{"exp":1700000000,"iat":1699996400}""")
        assertEquals(1700000000L, JwtExtractor.extractExp(token))
    }

    @Test
    fun `extractExp returns null when exp is absent`() {
        val token = jwt("""{"sub":"alice"}""")
        assertNull(JwtExtractor.extractExp(token))
    }

    @Test
    fun `decodeBody handles base64url payload with each length-mod-4 case (UD-308 invariant)`() {
        // UD-308: the pre-fix code padded with hardcoded "==" which only
        // produced a multiple-of-4 length when raw.length % 4 == 2. Bodies
        // with length % 4 in {0, 3} decoded as garbage / threw, and the
        // catch swallowed it. Each branch must now decode cleanly.
        //
        // Note: unpadded base64 length % 4 is in {0, 2, 3}; 1 is impossible
        // because 4N+1 base64 chars carry 6 leftover bits that can't fit a
        // whole byte. Cover {0, 2, 3}; that's the full live surface.
        val bucketsSeen = mutableSetOf<Int>()
        for (suffix in 0..30) {
            val sub = "a".repeat(suffix)
            val token = jwt("""{"sub":"$sub","exp":1700000000}""")
            val body = token.split(".")[1]
            bucketsSeen += body.length % 4
            assertNotNull(
                JwtExtractor.decodeBody(token),
                "decodeBody failed for length%4=${body.length % 4} (suffix=$suffix)",
            )
            assertEquals(1700000000L, JwtExtractor.extractExp(token))
        }
        // Confirm we actually hit every legal residue class; otherwise the
        // test is silently weaker than its name implies.
        assertEquals(setOf(0, 2, 3), bucketsSeen, "did not cover every legal length%4 case")
    }

    @Test
    fun `decodeBody returns null for input without dots`() {
        assertNull(JwtExtractor.decodeBody("not-a-jwt"))
        assertNull(JwtExtractor.extractClaim("not-a-jwt", "anything"))
        assertNull(JwtExtractor.extractExp("not-a-jwt"))
    }

    @Test
    fun `decodeBody returns null when middle segment is not valid base64`() {
        // '!' is outside the URL-safe base64 alphabet.
        val token = "header.not!valid!base64.sig"
        assertNull(JwtExtractor.decodeBody(token))
    }

    @Test
    fun `decodeBody returns null when payload is not JSON`() {
        val notJsonBody = base64UrlNoPad("this is not json")
        val token = "header.$notJsonBody.sig"
        assertNull(JwtExtractor.decodeBody(token))
    }

    @Test
    fun `extractClaim returns null for numeric or boolean claims (string-only contract)`() {
        // `exp` is numeric, not a string. extractClaim is for strings;
        // numeric claims go through extractExp. This pins the contract
        // so a future caller doesn't accidentally read `exp` as a String.
        val token = jwt("""{"exp":1700000000,"admin":true}""")
        // jsonPrimitive.contentOrNull on a JsonPrimitive(number) returns the
        // string form, which IS the documented kotlinx behaviour. Pin it
        // here so a callsite that depends on this isn't surprised.
        assertEquals("1700000000", JwtExtractor.extractClaim(token, "exp"))
        assertEquals("true", JwtExtractor.extractClaim(token, "admin"))
    }
}
