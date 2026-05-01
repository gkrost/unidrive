package org.krost.unidrive.hidrive

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.auth.awaitOAuthCallback
import org.krost.unidrive.hidrive.model.HiDriveToken
import org.krost.unidrive.io.openBrowser
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

class TokenManager(
    private val config: HiDriveConfig,
    private val oauthService: OAuthService,
) {
    private var token: HiDriveToken? = null
    private val refreshMutex = Mutex()

    val isAuthenticated: Boolean get() = token != null

    suspend fun initialize() {
        token = oauthService.loadToken()
    }

    suspend fun authenticateWithBrowser(): HiDriveToken {
        val state =
            java.util.UUID
                .randomUUID()
                .toString()
        val (authUrl, verifier) = oauthService.getAuthorizationUrl(state)

        println("Opening browser for HiDrive authentication...")
        println("URL: $authUrl")
        println("Waiting up to 5 minutes for authentication...")

        openBrowser(authUrl)

        val code =
            awaitOAuthCallback(
                port = HiDriveConfig.CALLBACK_PORT,
                expectedState = state,
                providerLabel = "HiDrive",
                timeout = 5.minutes,
            )

        token = oauthService.exchangeCodeForToken(code, verifier)
        oauthService.saveToken(token!!)

        return token!!
    }

    suspend fun getValidToken(): HiDriveToken {
        val currentToken = token ?: throw AuthenticationException("Not authenticated")

        if (!currentToken.isExpired) {
            return currentToken
        }

        return refreshMutex.withLock {
            val freshToken = token ?: throw AuthenticationException("Not authenticated")
            if (!freshToken.isExpired) {
                return freshToken
            }

            val refreshToken = freshToken.refreshToken
            if (refreshToken != null) {
                try {
                    // UD-331: mirror the UD-310 OneDrive fix — wrap the network
                    // call AND the saveToken in NonCancellable so a Pass-2 scope
                    // cancel on a sibling coroutine's 401 can't abort the
                    // refresh between "got new access_token" and "wrote it to
                    // disk." Without this, in-memory token disagrees with
                    // what's persisted across crashes / process restart.
                    val refreshed =
                        withContext(NonCancellable) {
                            val newToken = oauthService.refreshToken(refreshToken)
                            oauthService.saveToken(newToken)
                            newToken
                        }
                    token = refreshed
                    return refreshed
                } catch (e: Exception) {
                    println("Token refresh failed: ${e.message}")
                }
            }

            throw AuthenticationException("Authentication expired. Please re-authenticate.")
        }
    }

    suspend fun logout() {
        token?.let { oauthService.revokeToken(it.accessToken) }
        token = null
        val tokenFile = config.tokenPath.resolve("token.json")
        Files.deleteIfExists(tokenFile)
    }
}

// UD-348: waitForCallback / parseAndValidateCallback / openBrowser lifted
// to org.krost.unidrive.auth.OAuthCallbackServer + org.krost.unidrive.io.OpenBrowser.
