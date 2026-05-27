package org.krost.unidrive.onedrive

import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.auth.RefreshableTokenLatch
import org.krost.unidrive.auth.awaitOAuthCallback
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.io.openBrowser
import org.krost.unidrive.onedrive.model.Token
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

class TokenManager(
    private val config: OneDriveConfig,
    private val oauthService: OAuthService,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(TokenManager::class.java)
    private var token: Token? = null

    // UD-338: shared mutex + NonCancellable wrap lifted to :app:core/auth.
    // Provider-specific force-refresh (UD-310) and RefreshFailure recording
    // (UD-111) stay below in `getValidToken`.
    private val refreshLatch = RefreshableTokenLatch()

    @Volatile
    var lastRefreshFailure: RefreshFailure? = null
        private set

    data class RefreshFailure(
        val timestamp: java.time.Instant,
        val errorClass: String,
        val message: String?,
    )

    val isAuthenticated: Boolean get() = token != null

    suspend fun initialize() {
        token = oauthService.loadToken()
    }

    suspend fun authenticateWithBrowser(): Token {
        val state =
            java.util.UUID
                .randomUUID()
                .toString()
        val (authUrl, pkceVerifier) = oauthService.getAuthorizationUrl(state)

        println("Opening browser for authentication...")
        println("URL: $authUrl")
        println("Waiting up to 5 minutes for authentication...")

        openBrowser(authUrl)

        val code =
            awaitOAuthCallback(
                port = 8080,
                expectedState = state,
                providerLabel = "Azure",
                timeout = 5.minutes,
            )

        token = oauthService.exchangeCodeForToken(code, pkceVerifier)
        oauthService.saveToken(token!!)

        return token!!
    }

    suspend fun authenticateWithDeviceCode(): Token {
        val deviceCode = oauthService.getDeviceCode()

        println(deviceCode.message)
        println()
        println("Waiting for authentication...")

        token = oauthService.pollForToken(deviceCode.deviceCode, deviceCode.interval)
        oauthService.saveToken(token!!)

        println("Authentication successful!")

        return token!!
    }

    suspend fun getValidToken(forceRefresh: Boolean = false): Token {
        val currentToken = token ?: throw AuthenticationException("Not authenticated")

        if (!forceRefresh && !currentToken.isNearExpiry()) {
            return currentToken
        }

        // UD-338: serialisation + NonCancellable wrap delegated to
        // RefreshableTokenLatch. The skip-if-already-fresh predicate
        // captures both OneDrive nuances:
        //  - normal callers (no forceRefresh) accept any fresh token,
        //  - forceRefresh callers accept a fresh token IFF it's a
        //    different object than the one we observed pre-lock (i.e.
        //    another coroutine already did the force-refresh).
        // RefreshFailure recording (UD-111) and the throw still live
        // here because they're provider-specific UX, not lock-mechanics.
        return refreshLatch.withRefresh(
            isAlreadyFresh = {
                val freshToken = token ?: throw AuthenticationException("Not authenticated")
                when {
                    !forceRefresh && !freshToken.isNearExpiry() -> freshToken
                    forceRefresh && !freshToken.isNearExpiry() && freshToken !== currentToken -> freshToken
                    else -> null
                }
            },
            body = {
                val freshToken = token ?: throw AuthenticationException("Not authenticated")
                val refreshToken = freshToken.refreshToken
                if (refreshToken != null) {
                    try {
                        val refreshed = oauthService.refreshToken(refreshToken)
                        token = refreshed
                        oauthService.saveToken(refreshed)
                        lastRefreshFailure = null
                        return@withRefresh refreshed
                    } catch (e: Exception) {
                        val failure =
                            RefreshFailure(
                                timestamp = java.time.Instant.now(),
                                errorClass = e.javaClass.simpleName,
                                message = e.message,
                            )
                        lastRefreshFailure = failure
                        // A transient network blip (DNS unresolved, connection
                        // reset, read/connect timeout) during refresh is NOT proof
                        // the token expired — a later live call can still succeed.
                        // Surface it as retryable so the session is not latched as
                        // "expired → re-authenticate."
                        if (isTransientNetworkFailure(e)) {
                            log.warn(
                                "OAuth refresh hit a transient network failure: {}: {}. Treating as retryable, not expired.",
                                failure.errorClass,
                                failure.message,
                                e,
                            )
                            throw TransientNetworkException(
                                "Transient network failure during token refresh: ${e.message}",
                                cause = e,
                            )
                        }
                        log.warn(
                            "OAuth refresh failed: {}: {}. User will be prompted to re-authenticate.",
                            failure.errorClass,
                            failure.message,
                            e,
                        )
                    }
                }
                throw AuthenticationException("Authentication expired. Please re-authenticate.")
            },
        )
    }

    suspend fun logout() {
        token = null
        val tokenFile = config.tokenPath.resolve("token.json")
        Files.deleteIfExists(tokenFile)
    }

    // Classify a refresh-time throwable as a transient network failure (retryable)
    // versus a real auth failure (expired refresh token / invalid_grant). The auth
    // failure surfaces from refreshToken as an AuthenticationException on a non-2xx
    // body; anything network-flavoured (DNS unresolved, connection reset, timeout)
    // must be treated as retryable so a blip can't latch the session as expired.
    private fun isTransientNetworkFailure(e: Throwable): Boolean {
        // A non-2xx refresh response (e.g. 400 invalid_grant) is a real auth
        // failure, not transient — refreshToken raises AuthenticationException.
        if (e is AuthenticationException) return false
        if (e is UnresolvedAddressException) return true // DNS blip — transient.
        if (e is io.ktor.client.plugins.HttpRequestTimeoutException) return true
        if (e is java.io.IOException && HttpRetryBudget.isRetriableIoException(e)) return true
        // Ktor wraps the underlying socket failure; walk one cause level for the
        // common DNS-unresolved and retryable-IO shapes.
        val cause = e.cause ?: return false
        if (cause is UnresolvedAddressException) return true
        if (cause is io.ktor.client.plugins.HttpRequestTimeoutException) return true
        return cause is java.io.IOException && HttpRetryBudget.isRetriableIoException(cause)
    }
}
