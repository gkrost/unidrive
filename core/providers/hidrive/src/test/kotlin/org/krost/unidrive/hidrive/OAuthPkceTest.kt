package org.krost.unidrive.hidrive

import kotlin.test.*

class OAuthPkceTest {
    @Test
    fun `getAuthorizationUrl includes PKCE parameters`() {
        val config = HiDriveConfig(clientId = "test-client", clientSecret = "test-secret")
        val oauth = OAuthService(config)
        val (url, verifier) = oauth.getAuthorizationUrl("test-state")

        assertTrue(url.contains("code_challenge="), "Missing code_challenge")
        assertTrue(url.contains("code_challenge_method=S256"), "Missing S256 method")
        assertTrue(verifier.length >= 43, "Verifier too short")
        assertFalse(verifier.contains("+"), "Verifier should be URL-safe base64")
        assertFalse(verifier.contains("/"), "Verifier should be URL-safe base64")
        assertFalse(verifier.contains("="), "Verifier should have no padding")
    }

    @Test
    fun `PKCE challenge matches verifier`() {
        val config = HiDriveConfig(clientId = "test", clientSecret = "test")
        val oauth = OAuthService(config)
        val (url, verifier) = oauth.getAuthorizationUrl("state")

        // Extract challenge from URL
        val challenge = Regex("code_challenge=([^&]+)").find(url)!!.groupValues[1]

        // Verify: challenge == BASE64URL(SHA256(verifier))
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(verifier.toByteArray())
        val expected =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(digest)

        assertEquals(expected, challenge, "Challenge doesn't match SHA-256 of verifier")
    }
}
