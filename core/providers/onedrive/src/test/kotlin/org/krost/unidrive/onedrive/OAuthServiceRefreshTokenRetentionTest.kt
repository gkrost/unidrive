package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The refresh_token member of a refresh response is optional. When the
 * endpoint omits it, the refresh token used for the request stays valid and
 * must be kept; when it rotates, the new one wins.
 */
class OAuthServiceRefreshTokenRetentionTest {
    private fun serviceReplying(body: String): OAuthService {
        val engine =
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return OAuthService(OneDriveConfig(), httpClient = HttpClient(engine))
    }

    private val accessToken = "A".repeat(64)

    @Test
    fun `refresh response without refresh_token keeps the refresh token used for the request`() =
        runTest {
            val service =
                serviceReplying("""{"access_token":"$accessToken","token_type":"Bearer","expires_in":3600}""")
            service.use {
                val refreshed = it.refreshToken("old-refresh-token")
                assertEquals("old-refresh-token", refreshed.refreshToken)
            }
        }

    @Test
    fun `refresh response with a rotated refresh_token replaces the old one`() =
        runTest {
            val service =
                serviceReplying(
                    """{"access_token":"$accessToken","token_type":"Bearer","expires_in":3600,"refresh_token":"new-refresh-token"}""",
                )
            service.use {
                val refreshed = it.refreshToken("old-refresh-token")
                assertEquals("new-refresh-token", refreshed.refreshToken)
            }
        }
}
