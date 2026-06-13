package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.onedrive.model.Token
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the refresh-failure classification contract in
 * `TokenManager.getValidToken`. Live incident: a token refresh that hit a
 * transient `UnresolvedAddressException` (DNS/network blip) surfaced
 * "Authentication expired. Please re-authenticate." and latched the session
 * dead — even though a later live Graph `quota` call proved the token was
 * still valid. The refresh path must distinguish a transient network failure
 * (retryable, NOT latched) from a real auth failure (400 invalid_grant →
 * expired, re-authenticate).
 *
 * Two orthogonal invariants, one named test each:
 *  1. A transient network error during refresh surfaces a
 *     [TransientNetworkException] (retryable) — NOT an
 *     [AuthenticationException] — and does not latch the session as expired.
 *  2. A real 400 `invalid_grant` surfaces the existing expired / re-auth
 *     [AuthenticationException].
 */
class TokenManagerRefreshClassificationTest {
    private fun nearExpiryTokenWithRefresh(): Token =
        Token(
            // Above the 32-char plausibility floor so the refresh path is reached.
            accessToken = "A".repeat(64),
            tokenType = "Bearer",
            // Near-expiry so getValidToken() forces a refresh.
            expiresAt = System.currentTimeMillis() + 1_000,
            refreshToken = "r".repeat(80),
            scope = "openid Files.ReadWrite.All",
        )

    private fun tokenManagerWith(engine: MockEngine): TokenManager {
        val oauth = OAuthService(OneDriveConfig(), httpClient = HttpClient(engine))
        val manager = TokenManager(OneDriveConfig(), oauth)
        // Seed the in-memory token without going through a real auth flow.
        val tokenField = TokenManager::class.java.getDeclaredField("token")
        tokenField.isAccessible = true
        tokenField.set(manager, nearExpiryTokenWithRefresh())
        return manager
    }

    @Test
    fun `transient_network_error_during_refresh_is_not_latched_as_expired`() =
        runTest {
            // Every refresh POST raises a DNS-resolution failure — the same
            // transient class as the live incident.
            val engine = MockEngine { throw UnresolvedAddressException() }
            val manager = tokenManagerWith(engine)

            // assertFailsWith<TransientNetworkException> pins the classification: a
            // transient network blip must surface as TransientNetworkException, NOT the
            // AuthenticationException sibling that latches the session as auth-expired.
            assertFailsWith<TransientNetworkException> {
                manager.getValidToken()
            }
            // The session must not be latched: lastRefreshFailure may record the
            // attempt, but the error class proves it was classified transient,
            // not a permanent expiry.
            assertTrue(
                manager.lastRefreshFailure?.errorClass != "AuthenticationException",
                "a transient failure must not be recorded as an AuthenticationException latch",
            )
        }

    @Test
    fun `invalid_grant_is_latched_as_expired`() =
        runTest {
            // The refresh endpoint returns the real expired-refresh-token shape.
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":"invalid_grant","error_description":"AADSTS70008: refresh token expired"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val manager = tokenManagerWith(engine)

            val ex =
                assertFailsWith<AuthenticationException> {
                    manager.getValidToken()
                }
            // assertFailsWith<AuthenticationException> pins the classification: a real
            // invalid_grant must surface as AuthenticationException, NOT the
            // TransientNetworkException sibling.
            assertTrue(
                ex.message?.contains("re-authenticate", ignoreCase = true) == true,
                "a real invalid_grant must surface the expired / re-authenticate message; got: ${ex.message}",
            )
        }
}
