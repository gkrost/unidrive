package org.krost.unidrive.onedrive

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.Pkce
import org.krost.unidrive.onedrive.model.*
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class OAuthService(
    private val config: OneDriveConfig,
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(OAuthService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient()

    companion object {
        val SCOPES =
            listOf(
                "openid",
                "offline_access",
                "https://graph.microsoft.com/Files.ReadWrite.All",
            ).joinToString(" ")

        private const val TOKEN_FILE = "token.json"
    }

       private val credentialStore: CredentialStore<Token> =
        CredentialStore(
            dir = config.tokenPath,
            fileName = TOKEN_FILE,
            serializer = Token.serializer(),
            validate = { it.hasPlausibleAccessTokenShape() },
        )

    suspend fun loadToken(): Token? = credentialStore.load()

    suspend fun saveToken(token: Token) {
        credentialStore.save(token)
    }

    /** Returns (authorizationUrl, pkceVerifier). Caller must pass verifier to exchangeCodeForToken(). */
    fun getAuthorizationUrl(state: String): Pair<String, String> {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val scopes = URLEncoder.encode(SCOPES, "UTF-8")
        val url =
            "${config.authEndpoint}/authorize?" +
                "client_id=${config.applicationId}" +
                "&response_type=code" +
                "&redirect_uri=${config.redirectUri}" +
                "&scope=$scopes" +
                "&state=$state" +
                "&code_challenge=$challenge" +
                "&code_challenge_method=S256" +
                "&prompt=select_account"
        return Pair(url, verifier)
    }

    private fun generateCodeVerifier(): String = Pkce.generateVerifier()

    private fun generateCodeChallenge(verifier: String): String = Pkce.generateChallenge(verifier)

    suspend fun exchangeCodeForToken(
        code: String,
        pkceVerifier: String,
    ): Token {
        // UD-311: route through the retry helper so a TLS handshake blip during
        // the browser-callback code-exchange doesn't cost the user a re-auth.
        val response =
            postWithFlakeRetry("exchangeCodeForToken") {
                Parameters
                    .build {
                        append("client_id", config.applicationId)
                        append("code", code)
                        append("redirect_uri", config.redirectUri)
                        append("grant_type", "authorization_code")
                        append("code_verifier", pkceVerifier)
                    }.formUrlEncode()
            }

        if (!response.status.isSuccess()) {
            val error = response.bodyAsText()
            throw AuthenticationException("Failed to exchange code for token: $error")
        }

        val tokenResponse = json.decodeFromString<TokenResponse>(response.bodyAsText())
        val token = tokenResponse.toToken()
        if (!token.hasPlausibleAccessTokenShape()) {
            throw AuthenticationException(
                "Code-exchange endpoint returned 2xx with a suspiciously short access_token " +
                    "(length=${token.accessToken.length}). UD-312 guard — retry.",
            )
        }
        return token
    }

    suspend fun getDeviceCode(): DeviceCodeResponse {
        val response =
            httpClient.post("${config.authEndpoint}/devicecode") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("client_id", config.applicationId)
                            append("scope", SCOPES)
                        }.formUrlEncode(),
                )
            }

        if (!response.status.isSuccess()) {
            throw AuthenticationException("Failed to get device code: ${response.bodyAsText()}")
        }

        val body = response.bodyAsText()
        val parsed = json.decodeFromString<kotlinx.serialization.json.JsonObject>(body)

        return DeviceCodeResponse(
            deviceCode =
                parsed["device_code"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    ?: throw AuthenticationException("Missing device_code"),
            userCode =
                parsed["user_code"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    ?: throw AuthenticationException("Missing user_code"),
            verificationUri =
                parsed["verification_uri"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    ?: throw AuthenticationException("Missing verification_uri"),
            expiresIn = parsed["expires_in"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toLong() } ?: 0,
            interval = parsed["interval"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content.toLong() } ?: 5,
            message =
                parsed["message"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                    ?: "Please visit the verification URI and enter the user code",
        )
    }

    sealed class DevicePollOutcome {
        data class Success(
            val token: Token,
        ) : DevicePollOutcome()

        data class Pending(
            val retryAfterSeconds: Long,
        ) : DevicePollOutcome()

        data class Failed(
            val message: String,
        ) : DevicePollOutcome()
    }

    suspend fun pollOnceForToken(deviceCode: String): DevicePollOutcome {
        val response =
            try {
                postWithFlakeRetry("pollOnceForToken") {
                    Parameters
                        .build {
                            append("client_id", config.applicationId)
                            append("code", deviceCode)
                            append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        }.formUrlEncode()
                }
            } catch (e: Exception) {
                return DevicePollOutcome.Failed("Network error polling device-code token endpoint: ${e.message}")
            }

        val body = response.bodyAsText()

        if (response.status.isSuccess()) {
            val tokenResponse = json.decodeFromString<TokenResponse>(body)
            val token = tokenResponse.toToken()
            if (!token.hasPlausibleAccessTokenShape()) {
                return DevicePollOutcome.Failed(
                    "Device-code poll returned 2xx with a suspiciously short access_token " +
                        "(length=${token.accessToken.length}). TODO retry.",
                )
            }
            return DevicePollOutcome.Success(token)
        }

        val parsed =
            try {
                json.decodeFromString<kotlinx.serialization.json.JsonObject>(body)
            } catch (_: Exception) {
                return DevicePollOutcome.Failed("Device-code poll failed: $body")
            }
        val error = parsed["error"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }

        // Azure may advertise a bumped interval on slow_down; default to 5s.
        val fallbackInterval = 5L
        return when (error) {
            "authorization_pending" -> DevicePollOutcome.Pending(fallbackInterval)
            "slow_down" -> DevicePollOutcome.Pending(fallbackInterval * 2)
            "expired_token" -> DevicePollOutcome.Failed("Device code expired. Please try again.")
            else -> DevicePollOutcome.Failed("Device code error: $error - $body")
        }
    }

    suspend fun pollForToken(
        deviceCode: String,
        interval: Long,
    ): Token {
        while (true) {
            val response =
                postWithFlakeRetry("pollForToken") {
                    Parameters
                        .build {
                            append("client_id", config.applicationId)
                            append("code", deviceCode)
                            append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        }.formUrlEncode()
                }

            val body = response.bodyAsText()

            if (response.status.isSuccess()) {
                val tokenResponse = json.decodeFromString<TokenResponse>(body)
                val token = tokenResponse.toToken()
                if (!token.hasPlausibleAccessTokenShape()) {
                    throw AuthenticationException(
                        "Device-code poll returned 2xx with a suspiciously short access_token " +
                            "(length=${token.accessToken.length}). UD-312 guard — retry.",
                    )
                }
                return token
            }

            val parsed = json.decodeFromString<kotlinx.serialization.json.JsonObject>(body)
            val error = parsed["error"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }

            when (error) {
                "authorization_pending" -> delay(TimeUnit.SECONDS.toMillis(interval))
                "slow_down" -> delay(TimeUnit.SECONDS.toMillis(interval * 2))
                "expired_token" -> throw AuthenticationException("Device code expired. Please try again.")
                else -> throw AuthenticationException("Device code error: $error - $body")
            }
        }
    }

    suspend fun refreshToken(refreshToken: String): Token {
        val response =
            postWithFlakeRetry("refreshToken") {
                Parameters
                    .build {
                        append("client_id", config.applicationId)
                        append("refresh_token", refreshToken)
                        append("grant_type", "refresh_token")
                    }.formUrlEncode()
            }

        if (!response.status.isSuccess()) {
            throw AuthenticationException("Failed to refresh token: ${response.bodyAsText()}")
        }

        val tokenResponse = json.decodeFromString<TokenResponse>(response.bodyAsText())
        val token = tokenResponse.toToken()
        if (!token.hasPlausibleAccessTokenShape()) {
            throw AuthenticationException(
                "Refresh endpoint returned 2xx with a suspiciously short access_token " +
                    "(length=${token.accessToken.length}). UD-312 guard — retry or re-authenticate.",
            )
        }
        return token
    }

    private fun TokenResponse.toToken(): Token =
        Token(
            accessToken = accessToken,
            tokenType = tokenType,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            refreshToken = refreshToken,
            scope = scope,
        )

    override fun close() {
        httpClient.close()
    }

    private suspend fun postWithFlakeRetry(
        label: String,
        bodyProvider: () -> String,
    ): HttpResponse {
        var attempt = 0
        while (true) {
            try {
                return httpClient.post("${config.authEndpoint}/token") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(bodyProvider())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // UD-300: don't retry on cancellation; propagate cleanly.
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= MAX_AUTH_FLAKE_ATTEMPTS) {
                    log.warn("$label: auth POST failed after {} attempts: {}", attempt, e.message)
                    throw e
                }
                val backoffMs = 2_000L * (1L shl (attempt - 1)) // 2s, 4s
                log.warn(
                    "$label: auth POST failed (attempt {}/{}), retrying in {}ms: {}",
                    attempt,
                    MAX_AUTH_FLAKE_ATTEMPTS,
                    backoffMs,
                    e.message,
                )
                delay(backoffMs)
            }
        }
    }
}

private const val MAX_AUTH_FLAKE_ATTEMPTS = 3