package org.krost.unidrive.onedrive

import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderRegistry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The credential health of a profile is read by `status --check-auth`: a healthy
 * profile must not warn. A non-expired access token whose profile holds a refresh
 * token is fine — the ~1 h life of an access token is routine and the provider
 * refreshes it — while a token without a refresh token really is expiring.
 */
class OneDriveCredentialHealthTest {
    private fun factory() = ProviderRegistry.get("onedrive")!!

    private fun health(tokenJson: String): CredentialHealth {
        val dir = Files.createTempDirectory("od-health")
        try {
            Files.writeString(dir.resolve("token.json"), tokenJson)
            return factory().checkCredentialHealth(emptyMap(), dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun tokenJson(refreshToken: String?): String {
        val refresh = if (refreshToken == null) "" else ",\"refreshToken\":\"$refreshToken\""
        // expiresAt ~90 min from now: not expired, under the 24 h mark.
        return """{"accessToken":"${"a".repeat(40)}","tokenType":"Bearer",""" +
            """"expiresAt":${System.currentTimeMillis() + 90 * 60 * 1000}$refresh}"""
    }

    @Test
    fun `a fresh access token with a refresh token is Ok`() {
        val health = health(tokenJson(refreshToken = "refresh-token"))
        assertTrue(
            health is CredentialHealth.Ok,
            "a refreshable credential is healthy, got: $health",
        )
    }

    @Test
    fun `a fresh access token without a refresh token keeps its expiry warning`() {
        val health = health(tokenJson(refreshToken = null))
        assertTrue(
            health is CredentialHealth.ExpiresIn,
            "without a refresh token the credential really expires, got: $health",
        )
    }
}
