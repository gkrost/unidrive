package org.krost.unidrive.dropbox

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.Pkce
import org.krost.unidrive.auth.RefreshableTokenLatch
import org.krost.unidrive.auth.awaitOAuthCallback
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.http.truncateErrorBody
import org.krost.unidrive.io.openBrowser
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration.Companion.minutes

/**
 * Dropbox sign-in and the token it yields: OAuth 2 authorization code with PKCE (a public client, no secret) and
 * `token_access_type=offline`, so the grant carries a refresh token next to the short-lived access token. The
 * browser comes back to a loopback callback ([DropboxConfig.redirectUri]). Dropbox has no device-code flow.
 */
class DropboxAuth(
    private val config: DropboxConfig,
    private val httpClient: HttpClient,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(DropboxAuth::class.java)
    private val json = org.krost.unidrive.UnidriveJson
    private val store =
        CredentialStore(config.tokenPath, TOKEN_FILE, DropboxToken.serializer(), validate = { it.hasPlausibleShape() })
    private val refreshLatch = RefreshableTokenLatch()

    @Volatile
    private var token: DropboxToken? = null

    val isAuthenticated: Boolean get() = token != null

    fun load() {
        token = store.load()
    }

    /** Signs in through the browser and keeps the grant. */
    suspend fun signInWithBrowser() {
        val state = java.util.UUID.randomUUID().toString()
        val verifier = Pkce.generateVerifier()
        val url = authorizationUrl(state, Pkce.generateChallenge(verifier))
        println("Opening browser for Dropbox sign-in...")
        println("URL: $url")
        println("Waiting up to 5 minutes for the sign-in...")
        openBrowser(url)
        val code =
            awaitOAuthCallback(
                port = config.oauthCallbackPort,
                expectedState = state,
                providerLabel = "Dropbox",
                timeout = 5.minutes,
                bindFailureHint = "Set ${DropboxConfig.OAUTH_PORT_ENV} to a free port (and register that redirect URI with the Dropbox app).",
            )
        val fresh = exchangeCode(code, verifier)
        store.save(fresh)
        token = fresh
    }

    internal fun authorizationUrl(
        state: String,
        challenge: String,
    ): String =
        URLBuilder(config.authorizeUrl)
            .apply {
                parameters.append("client_id", config.appKey)
                parameters.append("response_type", "code")
                parameters.append("redirect_uri", config.redirectUri)
                parameters.append("token_access_type", "offline")
                parameters.append("code_challenge", challenge)
                parameters.append("code_challenge_method", "S256")
                parameters.append("state", state)
            }.buildString()

    internal suspend fun exchangeCode(
        code: String,
        verifier: String,
    ): DropboxToken =
        requestToken(
            Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("client_id", config.appKey)
                append("redirect_uri", config.redirectUri)
                append("code_verifier", verifier)
            },
            previousRefreshToken = null,
        )

    /**
     * A usable access token: the stored one, or a refreshed one when it is near its expiry or [forceRefresh] is set
     * (the API refused it). One refresh at a time; a caller that waited for another's refresh takes its result.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        val seen = signedIn()
        if (!forceRefresh && !seen.isNearExpiry()) return seen.accessToken
        return refreshLatch
            .withRefresh(
                isAlreadyFresh = {
                    val now = signedIn()
                    if (!now.isNearExpiry() && (!forceRefresh || now !== seen)) now else null
                },
                body = { refresh() },
            ).accessToken
    }

    private fun signedIn(): DropboxToken = token ?: throw AuthenticationException("Not signed in to Dropbox. Run 'unidrive auth'.")

    private suspend fun refresh(): DropboxToken {
        val refreshToken = signedIn().refreshToken ?: throw AuthenticationException("The Dropbox sign-in expired. Run 'unidrive auth' again.")
        val fresh =
            transientAware {
                requestToken(
                    Parameters.build {
                        append("grant_type", "refresh_token")
                        append("refresh_token", refreshToken)
                        append("client_id", config.appKey)
                    },
                    previousRefreshToken = refreshToken,
                )
            }
        store.save(fresh)
        token = fresh
        return fresh
    }

    // A network blip during the refresh says nothing about the grant: it surfaces as retryable, never as "signed out".
    @Suppress("TooGenericExceptionCaught") // classified, then rethrown (as itself or as the transient failure it is)
    private suspend fun <T> transientAware(block: suspend () -> T): T =
        try {
            block()
        } catch (e: AuthenticationException) {
            throw e
        } catch (e: Exception) {
            throw classified(e)
        }

    private fun classified(e: Exception): Exception {
        if (!isTransient(e)) return e
        log.warn("Dropbox token refresh hit a transient network failure ({}); retryable", e.javaClass.simpleName)
        return TransientNetworkException("Transient network failure during the Dropbox token refresh", e)
    }

    suspend fun logout() {
        token = null
        store.delete()
    }

    private suspend fun requestToken(
        form: Parameters,
        previousRefreshToken: String?,
    ): DropboxToken {
        val response = httpClient.submitForm(config.tokenUrl, form)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            // 400 invalid_grant: the refresh token was revoked or the code is spent; a new sign-in is the only way.
            throw AuthenticationException("Dropbox refused the sign-in (${response.status.value}): ${truncateErrorBody(body)}")
        }
        val parsed = json.decodeFromString(DropboxTokenResponse.serializer(), body)
        val fresh =
            DropboxToken(
                accessToken = parsed.accessToken,
                expiresAt = System.currentTimeMillis() + parsed.expiresIn * 1000L,
                refreshToken = parsed.refreshToken ?: previousRefreshToken,
                accountId = parsed.accountId,
            )
        if (!fresh.hasPlausibleShape()) {
            throw AuthenticationException("Dropbox's token endpoint answered with an implausibly short access token")
        }
        return fresh
    }

    private fun isTransient(e: Throwable): Boolean {
        var cause: Throwable? = e
        repeat(2) {
            when (val c = cause) {
                null -> return false
                is UnresolvedAddressException, is io.ktor.client.plugins.HttpRequestTimeoutException -> return true
                is java.io.IOException -> if (HttpRetryBudget.isRetriableIoException(c)) return true
            }
            cause = cause?.cause
        }
        return false
    }

    companion object {
        const val TOKEN_FILE = "token.json"
    }
}
