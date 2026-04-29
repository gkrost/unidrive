package org.krost.unidrive.onedrive

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.onedrive.model.*
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
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

    suspend fun loadToken(): Token? {
        val tokenFile = config.tokenPath.resolve(TOKEN_FILE)
        if (!Files.exists(tokenFile)) return null

        return try {
            val content = Files.readString(tokenFile)
            val parsed = json.decodeFromString<Token>(content)
            // UD-312: reject tokens whose access_token is empty/truncated on load.
            // Without this a half-written token.json (pre-atomic-save) or a Graph
            // 2xx-with-empty-access-token response that was persisted earlier would
            // be returned here, then sent to Graph, then 401'd with
            // "IDX14100: JWT is not well formed, there are no dots (.)". Returning
            // null forces the caller through a fresh refresh path instead.
            if (!parsed.hasPlausibleAccessTokenShape()) {
                log.warn(
                    "Token at {} has a suspiciously short access_token (length={}) — discarding so caller refreshes",
                    tokenFile,
                    parsed.accessToken.length,
                )
                return null
            }
            parsed
        } catch (e: Exception) {
            log.warn("Failed to load token from {}: {}", tokenFile, e.message)
            null
        }
    }

    suspend fun saveToken(token: Token) {
        Files.createDirectories(config.tokenPath)
        setPosixPermissionsIfSupported(config.tokenPath, ownerRwx = true)
        val tokenFile = config.tokenPath.resolve(TOKEN_FILE)
        // UD-312: atomic write. Non-atomic `Files.writeString` over an existing
        // file opens a window where a concurrent loadToken() (another coroutine
        // on the same JVM, or the MCP / tray reading the same file) sees the
        // truncated-and-partially-written state and parses a Token with an empty
        // access_token. Fix: write to a sibling .tmp then Files.move with
        // ATOMIC_MOVE so any reader sees either the old or the new, never half.
        // Fall back to non-atomic move on filesystems that reject ATOMIC_MOVE
        // (some network shares on Windows) — the race window shrinks but doesn't
        // fully close; that's documented limitation.
        val tmpFile = config.tokenPath.resolve("$TOKEN_FILE.tmp")
        Files.writeString(tmpFile, json.encodeToString(Token.serializer(), token))
        try {
            Files.move(
                tmpFile,
                tokenFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            log.warn("Filesystem rejected ATOMIC_MOVE at {} — falling back to non-atomic replace", tokenFile)
            Files.move(tmpFile, tokenFile, StandardCopyOption.REPLACE_EXISTING)
        }
        setPosixPermissionsIfSupported(tokenFile, ownerRwx = false)
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

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

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
        // UD-312: same shape guard as refreshToken() — catch empty / truncated
        // access_token at the issue site so the caller doesn't persist it.
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

    /**
     * UD-216: non-blocking device-code poll. Performs exactly one round-trip
     * against the token endpoint and classifies the result. Unlike
     * [pollForToken], this never loops — the caller decides whether to retry
     * (used by the MCP auth tools, which cannot block the server on a single
     * tool call).
     *
     * Callers should respect the provider's `retry_after_seconds` hint on a
     * [DevicePollOutcome.Pending] result; Azure explicitly slows us down with
     * `slow_down` if we ignore it.
     */
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
                        "(length=${token.accessToken.length}). UD-312 guard — retry.",
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
            // UD-311: same flake retry inside the poll loop. A TLS blip should
            // not collapse the device-code flow — one more retry + small delay
            // is cheaper than sending the user back to the verification URL.
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
                // UD-312: same shape guard as the other two grant endpoints.
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
        // UD-311: the token-refresh path was SSL-failing with zero retry during
        // long syncs. Observed 2026-04-19 13:05 after a 76-min 429 wait: token
        // had expired, refresh hit login.microsoftonline.com, TLS handshake was
        // terminated mid-stream, AuthenticationException propagated to
        // SyncEngine which logged "Download failed: Remote host terminated the
        // handshake" and gave up on the file. A single-shot handshake flake on
        // a transient network blip should not cost a file. Use the same
        // 3-attempt/2s-4s retry policy as downloadFile's flake loop.
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
        // UD-312: Azure occasionally emits a 2xx refresh response with an empty
        // or truncated access_token under load. Without this guard the caller
        // would persist and replay the empty token, Graph would reject with
        // IDX14100 (observed 2026-04-19 07:57). We surface the diagnostic here
        // instead of propagating the bad token. Matches the guard in
        // scripts/dev/oauth-mcp/graph_client.py so the CLI and the MCP agree
        // on what "valid enough to return" means.
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

    /**
     * UD-311: wrap a form-url-encoded POST to the auth endpoint in the same
     * 3-attempt / 2s / 4s flake retry as `GraphApiService.downloadFile`. SSL
     * handshake failures and `IOException` family (connection reset, timeout,
     * `Remote host terminated the handshake`) retry. HTTP-status failures
     * return the response so the caller's existing error-classification path
     * still runs — we don't want to retry a 400 invalid_grant for a minute.
     * [label] is used only in log messages for correlation.
     */
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

/**
 * Apply owner-only POSIX permissions to [path] when the underlying filesystem
 * supports the POSIX file attribute view (Linux, macOS).  On Windows/FAT/NTFS
 * this is a no-op; token security is provided by the directory ACL instead.
 *
 * [ownerRwx] = true  → rwx------  (used for the token directory)
 * [ownerRwx] = false → rw-------  (used for the token file)
 */
internal fun setPosixPermissionsIfSupported(
    path: java.nio.file.Path,
    ownerRwx: Boolean,
) {
    val view =
        Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
            ?: return
    val perms =
        if (ownerRwx) {
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            )
        }
    view.setPermissions(perms)
}
