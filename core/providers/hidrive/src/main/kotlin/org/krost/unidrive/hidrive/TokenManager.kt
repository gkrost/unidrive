package org.krost.unidrive.hidrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.hidrive.model.HiDriveToken
import java.net.URLDecoder
import java.nio.file.Files

class TokenManager(
    private val config: HiDriveConfig,
    private val oauthService: OAuthService,
) {
    private var token: HiDriveToken? = null
    private val refreshMutex = Mutex()

    val isAuthenticated: Boolean get() = token != null

    companion object {
        private const val AUTH_TIMEOUT_MS = 300_000
    }

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

        val code = waitForCallback(state)

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

    private suspend fun waitForCallback(expectedState: String): String =
        withContext(Dispatchers.IO) {
            val server = java.net.ServerSocket(HiDriveConfig.CALLBACK_PORT, 0, java.net.InetAddress.getByName("127.0.0.1"))
            server.soTimeout = AUTH_TIMEOUT_MS
            try {
                val client = server.accept()
                client.soTimeout = 10_000
                val requestLine = client.getInputStream().bufferedReader().readLine() ?: ""

                try {
                    val code = parseAndValidateCallback(requestLine, expectedState)
                    val html =
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
                            "<html><body><h1>HiDrive authentication complete!</h1><p>You can close this window.</p></body></html>"
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
        throw AuthenticationException("HiDrive returned error: $error $desc")
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
    when {
        os.contains("win") -> Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", url))
        os.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", url))
        else -> Runtime.getRuntime().exec(arrayOf("xdg-open", url))
    }
}
