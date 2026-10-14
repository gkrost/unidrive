package org.krost.unidrive.dropbox

import io.ktor.http.Url
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The sign-in request (PKCE, offline access, the loopback redirect) and what the factory asks of a profile. */
class DropboxAuthTest {
    private val dir = Files.createTempDirectory("dropbox-auth")

    @Test
    fun `the authorization url asks for offline access with a PKCE challenge`() {
        val auth = DropboxAuth(DropboxConfig(appKey = "key", tokenPath = dir, oauthCallbackPort = 8123), FakeDropbox().let { io.ktor.client.HttpClient(it.engine) })

        val url = Url(auth.authorizationUrl(state = "st", challenge = "ch"))

        assertEquals("www.dropbox.com", url.host)
        assertEquals("key", url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("offline", url.parameters["token_access_type"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals("ch", url.parameters["code_challenge"])
        assertEquals("st", url.parameters["state"])
        assertEquals("http://localhost:8123/callback", url.parameters["redirect_uri"])
    }

    @Test
    fun `a profile without an app key is refused with how to get one`() {
        if (System.getenv(DropboxConfig.CLIENT_ID_ENV) != null) return
        val e = assertFailsWith<ConfigurationException> { DropboxProviderFactory().create(emptyMap(), dir) }
        assertTrue(e.message.contains("client_id"))
        assertIs<CredentialHealth.Missing>(DropboxProviderFactory().checkCredentialHealth(emptyMap(), dir))
    }

    @Test
    fun `a stored refresh token makes the credential healthy`() {
        Files.writeString(
            dir.resolve(DropboxAuth.TOKEN_FILE),
            org.krost.unidrive.UnidriveJson.encodeToString(DropboxToken.serializer(), DropboxToken("a".repeat(40), 0, "r")),
        )
        val factory = DropboxProviderFactory()

        assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(mapOf("client_id" to "k"), dir))
        assertTrue(factory.isAuthenticated(mapOf("client_id" to "k"), dir))
    }
}
