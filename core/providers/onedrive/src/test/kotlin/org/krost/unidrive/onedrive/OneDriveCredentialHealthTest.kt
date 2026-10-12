package org.krost.unidrive.onedrive

import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderRegistry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun tokenJson(
        refreshToken: String?,
        expiresInMs: Long = 90 * 60 * 1000,
    ): String {
        val refresh = if (refreshToken == null) "" else ",\"refreshToken\":\"$refreshToken\""
        // Default: expiresAt ~90 min from now: not expired, under the 24 h mark.
        return """{"accessToken":"${"a".repeat(40)}","tokenType":"Bearer",""" +
            """"expiresAt":${System.currentTimeMillis() + expiresInMs}$refresh}"""
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
        val left = (health as CredentialHealth.ExpiresIn).remainingMs
        assertTrue(
            left != null && left in 80 * 60_000L..90 * 60_000L,
            "the exact time left (about 90 min) tells expiring from expired, got: $left",
        )
    }

    // #586: an access token past its life with no refresh token can never be renewed. It is expired (not a
    // free-form warning), so that daemon.status can say "sign in again" for it.
    @Test
    fun `an expired access token without a refresh token is expired, with the re-auth text`() {
        val health = health(tokenJson(refreshToken = null, expiresInMs = -60_000))

        assertTrue(health is CredentialHealth.ExpiresIn, "expected ExpiresIn, got: $health")
        assertEquals(0L, health.hours)
        assertEquals(0L, health.remainingMs, "the exact time left is zero: expired, not merely expiring")
        assertEquals("Access token expired, no refresh token — re-auth required", health.message)
    }

    @Test
    fun `an expired access token with a refresh token stays Ok`() {
        assertTrue(health(tokenJson(refreshToken = "refresh-token", expiresInMs = -60_000)) is CredentialHealth.Ok)
    }
}
