package org.krost.unidrive.onedrive

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The browser sign-in callback listens on a loopback port that the authorize request's redirect URI
 * must point at. 8080 stays the default; `UNIDRIVE_ONEDRIVE_OAUTH_PORT` moves it when another
 * service already holds 8080.
 */
class OneDriveOAuthPortTest {
    @Test
    fun `the default port stays 8080 and the default redirect URI is unchanged`() {
        assertEquals(8080, OneDriveConfig.DEFAULT_OAUTH_CALLBACK_PORT)
        assertEquals("http://localhost:8080/callback", OneDriveConfig.DEFAULT_REDIRECT_URI)
        assertEquals(OneDriveConfig.DEFAULT_REDIRECT_URI, OneDriveConfig(oauthCallbackPort = 8080).redirectUri)
    }

    @Test
    fun `a configured port is used and the redirect URI follows it`() {
        val config = OneDriveConfig(oauthCallbackPort = 9090)
        assertEquals(9090, config.oauthCallbackPort)
        assertEquals("http://localhost:9090/callback", config.redirectUri)
    }

    @Test
    fun `an explicit redirect URI still wins`() {
        val config = OneDriveConfig(oauthCallbackPort = 9090, redirectUri = "http://localhost:7000/custom")
        assertEquals("http://localhost:7000/custom", config.redirectUri)
    }

    @Test
    fun `the environment value is parsed as a port`() {
        assertEquals(9090, OneDriveConfig.resolveOAuthCallbackPort("9090"))
        assertEquals(9090, OneDriveConfig.resolveOAuthCallbackPort(" 9090 "))
        assertEquals(1, OneDriveConfig.resolveOAuthCallbackPort("1"))
        assertEquals(65535, OneDriveConfig.resolveOAuthCallbackPort("65535"))
    }

    @Test
    fun `an absent or invalid environment value falls back to the default port`() {
        for (raw in listOf(null, "", "  ", "abc", "80x", "0", "-1", "65536", "99999999999")) {
            assertEquals(
                OneDriveConfig.DEFAULT_OAUTH_CALLBACK_PORT,
                OneDriveConfig.resolveOAuthCallbackPort(raw),
                "raw=$raw",
            )
        }
    }
}
