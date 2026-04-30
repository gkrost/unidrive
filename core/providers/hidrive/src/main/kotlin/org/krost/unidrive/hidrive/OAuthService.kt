package org.krost.unidrive.hidrive

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.auth.Pkce
import org.krost.unidrive.hidrive.model.HiDriveToken
import org.krost.unidrive.hidrive.model.HiDriveTokenResponse
import org.krost.unidrive.io.setPosixPermissionsIfSupported
import java.net.URLEncoder
import java.nio.file.Files

class OAuthService(
    private val config: HiDriveConfig,
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(OAuthService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient()

    companion object {
        private const val TOKEN_FILE = "token.json"
    }

    suspend fun loadToken(): HiDriveToken? {
        val tokenFile = config.tokenPath.resolve(TOKEN_FILE)
        if (!Files.exists(tokenFile)) return null

        return try {
            val content = Files.readString(tokenFile)
            json.decodeFromString<HiDriveToken>(content)
        } catch (e: Exception) {
            log.warn("Failed to load token from {}: {}", tokenFile, e.message)
            null
        }
    }

    suspend fun saveToken(token: HiDriveToken) {
        Files.createDirectories(config.tokenPath)
        setPosixPermissionsIfSupported(config.tokenPath, ownerRwx = true)
        val tokenFile = config.tokenPath.resolve(TOKEN_FILE)
        Files.writeString(tokenFile, json.encodeToString(HiDriveToken.serializer(), token))
        setPosixPermissionsIfSupported(tokenFile, ownerRwx = false)
    }

    fun getAuthorizationUrl(state: String): Pair<String, String> {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val scope = URLEncoder.encode(HiDriveConfig.SCOPE, "UTF-8")
        val redirectUri = URLEncoder.encode(HiDriveConfig.REDIRECT_URI, "UTF-8")
        val url =
            "${HiDriveConfig.AUTH_ENDPOINT}?" +
                "client_id=${config.clientId}" +
                "&response_type=code" +
                "&redirect_uri=$redirectUri" +
                "&scope=$scope" +
                "&state=$state" +
                "&prompt=select_account" +
                "&code_challenge=$challenge" +
                "&code_challenge_method=S256"
        return Pair(url, verifier)
    }

    // UD-351: PKCE helpers lifted to org.krost.unidrive.auth.Pkce.
    // Note: HiDrive's pre-lift challenge used `verifier.toByteArray()`
    // (default UTF-8); the shared helper uses US-ASCII per RFC 7636 §4.2.
    // The verifier is always Base64 (URL alphabet, ASCII subset) so the
    // resulting bytes are identical; no behaviour change for any
    // valid call site.
    private fun generateCodeVerifier(): String = Pkce.generateVerifier()

    private fun generateCodeChallenge(verifier: String): String = Pkce.generateChallenge(verifier)

    suspend fun exchangeCodeForToken(
        code: String,
        codeVerifier: String,
    ): HiDriveToken {
        val response =
            httpClient.post(HiDriveConfig.TOKEN_ENDPOINT) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("client_id", config.clientId)
                            append("client_secret", config.clientSecret)
                            append("code", code)
                            append("redirect_uri", HiDriveConfig.REDIRECT_URI)
                            append("grant_type", "authorization_code")
                            append("code_verifier", codeVerifier)
                        }.formUrlEncode(),
                )
            }

        if (!response.status.isSuccess()) {
            throw AuthenticationException("Failed to exchange code for token: ${response.bodyAsText()}")
        }

        val tokenResponse = json.decodeFromString<HiDriveTokenResponse>(response.bodyAsText())
        return tokenResponse.toToken()
    }

    suspend fun refreshToken(refreshToken: String): HiDriveToken {
        val response =
            httpClient.post(HiDriveConfig.TOKEN_ENDPOINT) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("client_id", config.clientId)
                            append("client_secret", config.clientSecret)
                            append("refresh_token", refreshToken)
                            append("grant_type", "refresh_token")
                        }.formUrlEncode(),
                )
            }

        if (!response.status.isSuccess()) {
            throw AuthenticationException("Failed to refresh token: ${response.bodyAsText()}")
        }

        val tokenResponse = json.decodeFromString<HiDriveTokenResponse>(response.bodyAsText())
        return tokenResponse.toToken()
    }

    suspend fun revokeToken(accessToken: String) {
        try {
            httpClient.post(HiDriveConfig.REVOKE_ENDPOINT) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("token", accessToken)
                        }.formUrlEncode(),
                )
            }
        } catch (_: Exception) {
            // Best-effort revocation
        }
    }

    override fun close() {
        httpClient.close()
    }

    private fun HiDriveTokenResponse.toToken(): HiDriveToken =
        HiDriveToken(
            accessToken = accessToken,
            tokenType = tokenType,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            refreshToken = refreshToken,
            scope = scope,
        )
}

// UD-347: setPosixPermissionsIfSupported lifted to org.krost.unidrive.io.
