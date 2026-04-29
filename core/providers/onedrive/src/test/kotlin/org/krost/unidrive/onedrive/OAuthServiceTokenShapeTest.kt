package org.krost.unidrive.onedrive

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UD-312: guards that prevent a corrupt or half-written `token.json` from
 * being handed to a caller that's about to send it to Graph.
 *
 * The matching fix targets three surface areas:
 *   - `Token.hasPlausibleAccessTokenShape()` — cheap shape predicate.
 *   - `OAuthService.loadToken()` — discards tokens that fail the shape
 *     check and returns null so the caller refreshes.
 *   - `OAuthService.saveToken()` — atomic write via tmp + ATOMIC_MOVE so
 *     concurrent readers never see half a file.
 *
 * These tests exercise the first two. The atomic-save invariant is not
 * directly testable without a concurrent reader thread racing a writer
 * on the same file — instead we assert the weaker invariant that a
 * normal save leaves no `.tmp` residue, which proves the Files.move ran.
 */
class OAuthServiceTokenShapeTest {
    private lateinit var tokenDir: Path
    private lateinit var oauthService: OAuthService

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("unidrive-oauth-token-shape-test")
        oauthService = OAuthService(OneDriveConfig(tokenPath = tokenDir))
    }

    @AfterTest
    fun tearDown() {
        oauthService.close()
        tokenDir.toFile().deleteRecursively()
    }

    @Test
    fun `hasPlausibleAccessTokenShape rejects empty`() {
        val t = Token(accessToken = "", tokenType = "Bearer", expiresAt = Long.MAX_VALUE)
        assertFalse(t.hasPlausibleAccessTokenShape())
    }

    @Test
    fun `hasPlausibleAccessTokenShape rejects short strings below the 32-char floor`() {
        val t = Token(accessToken = "abc.def.ghi", tokenType = "Bearer", expiresAt = Long.MAX_VALUE)
        assertFalse(t.hasPlausibleAccessTokenShape())
    }

    @Test
    fun `hasPlausibleAccessTokenShape accepts a personal-MSA-shaped opaque token`() {
        // Real MS consumer tokens look like `EwB…` and are ~1.5KB. 64 chars is a
        // conservative above-the-floor fixture; zero dots, just base64-ish.
        val t =
            Token(
                accessToken = "EwBIBMl6BAAU9Batlg" + "x".repeat(50),
                tokenType = "Bearer",
                expiresAt = Long.MAX_VALUE,
            )
        assertTrue(t.hasPlausibleAccessTokenShape())
    }

    @Test
    fun `hasPlausibleAccessTokenShape accepts a work-school JWS compact token`() {
        // Three-segment JWS; irrelevant to the check since it's length-based, but
        // pinning the invariant "we accept both formats, not just JWTs".
        val t =
            Token(
                accessToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMifQ.aGVsbG8",
                tokenType = "Bearer",
                expiresAt = Long.MAX_VALUE,
            )
        assertTrue(t.hasPlausibleAccessTokenShape())
    }

    @Test
    fun `loadToken discards a file whose access_token is empty`() =
        runTest {
            val tokenFile = tokenDir.resolve("token.json")
            Files.writeString(
                tokenFile,
                """{"accessToken":"","tokenType":"Bearer","expiresAt":${Long.MAX_VALUE}}""",
            )
            assertNull(oauthService.loadToken(), "empty access_token must not round-trip through loadToken")
        }

    @Test
    fun `loadToken discards a file whose access_token is too short`() =
        runTest {
            val tokenFile = tokenDir.resolve("token.json")
            Files.writeString(
                tokenFile,
                """{"accessToken":"short.token","tokenType":"Bearer","expiresAt":${Long.MAX_VALUE}}""",
            )
            assertNull(oauthService.loadToken())
        }

    @Test
    fun `loadToken returns null on corrupt JSON`() =
        runTest {
            val tokenFile = tokenDir.resolve("token.json")
            Files.writeString(tokenFile, "not json at all")
            assertNull(oauthService.loadToken())
        }

    @Test
    fun `loadToken round-trips a plausible token`() =
        runTest {
            val saved =
                Token(
                    accessToken = "E".repeat(200),
                    tokenType = "Bearer",
                    expiresAt = System.currentTimeMillis() + 3_600_000,
                    refreshToken = "r".repeat(80),
                    scope = "openid Files.Read",
                )
            oauthService.saveToken(saved)
            val loaded = oauthService.loadToken()
            assertNotNull(loaded)
            assertEquals(saved.accessToken, loaded.accessToken)
            assertEquals(saved.refreshToken, loaded.refreshToken)
            assertEquals(saved.scope, loaded.scope)
        }

    @Test
    fun `saveToken leaves no tmp residue after a successful atomic move`() =
        runTest {
            val saved =
                Token(
                    accessToken = "E".repeat(100),
                    tokenType = "Bearer",
                    expiresAt = Long.MAX_VALUE,
                )
            oauthService.saveToken(saved)
            val tmp = tokenDir.resolve("token.json.tmp")
            assertFalse(Files.exists(tmp), "token.json.tmp must not linger — atomic move should have consumed it")
            assertTrue(Files.exists(tokenDir.resolve("token.json")))
        }

    @Test
    fun `saveToken overwrites a prior good token without readers seeing half-state`() =
        runTest {
            // Concurrent-reader race is not testable here without a thread race
            // harness; what we can test is that the final on-disk content is
            // either the old or the new, never partial, because the write goes
            // through a tmp file. This asserts the post-condition by checking
            // that loadToken sees the new content after save().
            val first =
                Token(
                    accessToken = "A".repeat(100),
                    tokenType = "Bearer",
                    expiresAt = Long.MAX_VALUE,
                )
            val second =
                Token(
                    accessToken = "B".repeat(100),
                    tokenType = "Bearer",
                    expiresAt = Long.MAX_VALUE,
                )
            oauthService.saveToken(first)
            oauthService.saveToken(second)
            val loaded = oauthService.loadToken()
            assertNotNull(loaded)
            assertEquals(second.accessToken, loaded.accessToken)
            assertFalse(Files.exists(tokenDir.resolve("token.json.tmp")))
        }
}
