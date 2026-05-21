package org.krost.unidrive.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// UD-348: shared OAuth loopback callback server for authorization-code
// flows.
//
// Pre-UD-348 OneDrive and HiDrive `TokenManager` each shipped a 35-line
// word-for-word duplicate `waitForCallback` (single-shot loopback
// `ServerSocket`, blocking `accept`, hard-coded HTML success / error
// page) plus a 25-line duplicate `parseAndValidateCallback` with the
// same load-bearing comment about provider-error-vs-state-vs-code regex
// ordering. Lifted here so any future authorization-code provider
// inherits the callback handler without copy-paste.
//
// The two pre-lift copies differed only in:
//   - the success-page H1 text ("Authentication complete!" vs.
//     "HiDrive authentication complete!"), and
//   - the provider-error prefix ("Azure returned error: …" vs.
//     "HiDrive returned error: …").
// Both are now driven by the `providerLabel` parameter.

/** Default OAuth callback timeout (5 minutes) — matches the pre-lift OneDrive + HiDrive constant. */
public val DEFAULT_OAUTH_CALLBACK_TIMEOUT: Duration = 5.minutes

/**
 * Listen on `127.0.0.1:[port]` for the OAuth provider's redirect, parse
 * the authorization code out of the GET request line, and write a small
 * HTML success / error page back to the user's browser.
 *
 * @param port               loopback port the provider's `redirect_uri` points at
 * @param expectedState      `state` parameter we generated at authorize time;
 *                           the callback's `state` MUST match (CSRF guard)
 * @param providerLabel      human-readable provider name. Drives the success-page
 *                           H1 text ("$providerLabel authentication complete!")
 *                           and the provider-error prefix
 *                           ("$providerLabel returned error: …").
 * @param timeout            how long to block on `accept` before throwing
 *                           [AuthenticationException]. Default 5 minutes.
 * @return the authorization code from the callback URL.
 *
 * @throws AuthenticationException on provider error param, missing /
 *   mismatched `state` (CSRF), missing `code`, or the timeout firing.
 */
public suspend fun awaitOAuthCallback(
    port: Int,
    expectedState: String,
    providerLabel: String,
    timeout: Duration = DEFAULT_OAUTH_CALLBACK_TIMEOUT,
): String =
    withContext(Dispatchers.IO) {
        val server = ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = timeout.inWholeMilliseconds.toInt()
        try {
            val client = server.accept()
            client.soTimeout = 10_000
            val requestLine = client.getInputStream().bufferedReader().readLine() ?: ""

            try {
                val code = parseAndValidateCallback(requestLine, expectedState, providerLabel)
                val html =
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
                        "<html><body><h1>$providerLabel authentication complete!</h1>" +
                        "<p>You can close this window.</p></body></html>"
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
        } catch (_: SocketTimeoutException) {
            throw AuthenticationException(
                "Authentication timed out after ${timeout.inWholeMinutes} minutes. Please try again.",
            )
        } finally {
            server.close()
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
 * @param requestLine    raw HTTP request line, e.g. `GET /?code=…&state=… HTTP/1.1`
 * @param expectedState  the `state` value we generated at authorize time
 * @param providerLabel  human-readable provider name; used as the prefix
 *                       in the provider-error message
 * @throws AuthenticationException on provider error, missing/mismatched state, or missing code.
 */
public fun parseAndValidateCallback(
    requestLine: String,
    expectedState: String,
    providerLabel: String,
): String {
    val errorMatch = Regex("[?&]error=([^&\\s]+)").find(requestLine)
    if (errorMatch != null) {
        val error = errorMatch.groupValues[1]
        val descMatch = Regex("[?&]error_description=([^&\\s]+)").find(requestLine)
        val desc = descMatch?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        throw AuthenticationException("$providerLabel returned error: $error $desc")
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
