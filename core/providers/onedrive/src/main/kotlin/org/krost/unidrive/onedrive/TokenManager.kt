package org.krost.unidrive.onedrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.onedrive.model.Token
import java.net.URLDecoder
import java.nio.file.Files

class TokenManager(
    private val config: OneDriveConfig,
    private val oauthService: OAuthService,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(TokenManager::class.java)
    private var token: Token? = null
    private val refreshMutex = Mutex()

    /**
     * UD-111: structured record of the most recent OAuth refresh failure,
     * exposed for `unidrive status --audit` / MCP `unidrive_status` so a
     * user-facing client can surface "please re-auth" at the right moment.
     * Cleared on the next successful refresh.
     */
    @Volatile
    var lastRefreshFailure: RefreshFailure? = null
        private set

    data class RefreshFailure(
        val timestamp: java.time.Instant,
        val errorClass: String,
        val message: String?,
    )

    val isAuthenticated: Boolean get() = token != null

    companion object {
        private const val AUTH_TIMEOUT_MS = 300_000
    }

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

        val code = waitForCallback(state)

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

    // UD-310: [forceRefresh] = true skips the !isExpired fast path and always issues a refresh.
    // Callers set this on a 401 retry where the access token may still be nominally non-expired
    // (clock skew, server-side rotation) but Graph has rejected it. Direction B: we also refresh
    // proactively when the token is within 5 min of expiry, cutting the 401 race window down from
    // "minutes" to "seconds" during long syncs.
    suspend fun getValidToken(forceRefresh: Boolean = false): Token {
        val currentToken = token ?: throw AuthenticationException("Not authenticated")

        if (!forceRefresh && !currentToken.isNearExpiry()) {
            return currentToken
        }

        return refreshMutex.withLock {
            val freshToken = token ?: throw AuthenticationException("Not authenticated")
            // Re-check under the lock — another coroutine may have refreshed while we waited.
            if (!forceRefresh && !freshToken.isNearExpiry()) {
                return freshToken
            }
            // If another caller already forced a refresh after we took our turn in the queue, its
            // result is fresh enough for us too.
            if (forceRefresh && !freshToken.isNearExpiry() && freshToken !== currentToken) {
                return freshToken
            }

            val refreshToken = freshToken.refreshToken
            if (refreshToken != null) {
                try {
                    // UD-310: wrap in NonCancellable so an in-flight refresh survives parent
                    // coroutine cancellation (Pass 2 scope cancel on a sibling's 401, for
                    // example). Without this, the refresh was logging "ScopeCoroutine was
                    // cancelled" and falling through to the re-auth error, even though the
                    // refresh_token itself was perfectly valid.
                    val refreshed =
                        withContext(NonCancellable) {
                            oauthService.refreshToken(refreshToken)
                        }
                    token = refreshed
                    oauthService.saveToken(refreshed)
                    // UD-111: clear the failure record on success so status
                    // shows "auth-healthy" rather than a stale stale message.
                    lastRefreshFailure = null
                    return refreshed
                } catch (e: Exception) {
                    // UD-111: replaced the bare `println` with a structured
                    // log.warn + stored RefreshFailure record. Pre-fix the
                    // failure was visible only in stdout (often invisible to
                    // daemon logs / tray) so users hit "please re-auth"
                    // without warning.
                    val failure =
                        RefreshFailure(
                            timestamp = java.time.Instant.now(),
                            errorClass = e.javaClass.simpleName,
                            message = e.message,
                        )
                    lastRefreshFailure = failure
                    log.warn(
                        "OAuth refresh failed: {}: {}. User will be prompted to re-authenticate.",
                        failure.errorClass,
                        failure.message,
                        e,
                    )
                }
            }

            throw AuthenticationException("Authentication expired. Please re-authenticate.")
        }
    }

    suspend fun logout() {
        token = null
        val tokenFile = config.tokenPath.resolve("token.json")
        Files.deleteIfExists(tokenFile)
    }

    private suspend fun waitForCallback(expectedState: String): String =
        withContext(Dispatchers.IO) {
            val server = java.net.ServerSocket(8080, 0, java.net.InetAddress.getByName("127.0.0.1"))
            server.soTimeout = AUTH_TIMEOUT_MS
            try {
                val client = server.accept()
                client.soTimeout = 10_000
                val requestLine = client.getInputStream().bufferedReader().readLine() ?: ""

                try {
                    val code = parseAndValidateCallback(requestLine, expectedState)
                    val html =
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
                            "<html><body><h1>Authentication complete!</h1><p>You can close this window.</p></body></html>"
                    client.getOutputStream().write(html.toByteArray())
                    client.close()
                    code
                } catch (e: AuthenticationException) {
                    val html =
                        "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\n\r\n" +
                            "<html><body><h1>Authentication failed</h1><p>${e.message}</p></body></html>"
                    try {
                        client.getOutputStream().write(html.toByteArray())
                        client.close()
                    } catch (_: Exception) {
                        // best-effort; caller still gets the exception
                    }
                    throw e
                }
            } catch (e: java.net.SocketTimeoutException) {
                throw AuthenticationException("Authentication timed out after 5 minutes. Please try again.")
            } finally {
                server.close()
            }
        }
}

/**
 * Parse an HTTP callback request line and validate the OAuth state parameter.
 *
 * Order matters and is load-bearing:
 *   1. provider error (surfaces `access_denied` etc. as themselves, not as CSRF)
 *   2. state parameter (rejects CSRF before we ever look at the code)
 *   3. authorization code (happy path only after CSRF is cleared)
 *
 * @throws AuthenticationException on provider error, missing/mismatched state, or missing code.
 */
internal fun parseAndValidateCallback(
    requestLine: String,
    expectedState: String,
): String {
    val errorMatch = Regex("[?&]error=([^&\\s]+)").find(requestLine)
    if (errorMatch != null) {
        val error = errorMatch.groupValues[1]
        val descMatch = Regex("[?&]error_description=([^&\\s]+)").find(requestLine)
        val desc = descMatch?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        throw AuthenticationException("Azure returned error: $error $desc")
    }

    val stateMatch = Regex("[?&]state=([^&\\s]+)").find(requestLine)
    val receivedState =
        stateMatch?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            ?: throw AuthenticationException("Missing OAuth state parameter — possible CSRF attack")
    if (receivedState != expectedState) {
        throw AuthenticationException("OAuth state mismatch — possible CSRF attack")
    }

    val codeMatch = Regex("[?&]code=([^&\\s]+)").find(requestLine)
    return codeMatch?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
        ?: throw AuthenticationException("No authorization code in callback: $requestLine")
}

/**
 * Open [url] in the default system browser.
 * Uses java.awt.Desktop on Windows/macOS; falls back to xdg-open on Linux
 * when Desktop.browse is unsupported.
 */
internal fun openBrowser(url: String) {
    val os = System.getProperty("os.name", "").lowercase()
    try {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            desktop.browse(java.net.URI(url))
            return
        }
    } catch (_: Exception) {
    }
    // Fallback: platform-specific command
    when {
        os.contains("win") -> Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", url))
        os.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", url))
        else -> Runtime.getRuntime().exec(arrayOf("xdg-open", url))
    }
}
