package org.krost.unidrive.webdav

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * UD-288: regression tests for the WebDAV retry helper. Pre-fix, WebDAV
 * had no retry logic anywhere — one transient failure (UD-285 timeout,
 * UD-287 WSAECONNABORTED, 5xx server burp) was a permanently failed file
 * for the rest of the relocate run. The withRetry helper mirrors the
 * pattern from OneDrive's GraphApiService (UD-227 / UD-232) but with
 * WebDAV's narrower error vocabulary.
 *
 * These tests pin the contract directly via the internal withRetry
 * function — no HTTP server needed. backoffMs is overridden to 1 ms so
 * the suite stays fast.
 */
class WebDavApiServiceRetryTest {
    private fun service() =
        WebDavApiService(
            WebDavConfig(
                baseUrl = "https://dav.example.com/webdav",
                username = "alice",
                password = "secret",
                tokenPath = Files.createTempDirectory("webdav-retry-test"),
            ),
        )

    private val fastBackoff: (Int) -> Long = { 1L }

    // ── Success / no-retry paths ──────────────────────────────────────────

    @Test
    fun `block called exactly once when it succeeds on first attempt`() =
        runTest {
            var calls = 0
            val result =
                service().withRetry("test", "/", backoffMs = fastBackoff) {
                    calls++
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(1, calls)
        }

    // ── Retriable IOException paths ────────────────────────────────────────

    @Test
    fun `retries on retriable IOException then succeeds`() =
        runTest {
            var calls = 0
            val result =
                service().withRetry("upload", "/file.bin", backoffMs = fastBackoff) {
                    calls++
                    if (calls < 3) {
                        throw IOException("Connection reset by peer")
                    }
                    "success-after-$calls-attempts"
                }
            assertEquals("success-after-3-attempts", result)
            assertEquals(3, calls)
        }

    @Test
    fun `retries on Windows German WSAECONNABORTED message`() =
        runTest {
            var calls = 0
            service().withRetry("upload", "/file.bin", backoffMs = fastBackoff) {
                calls++
                if (calls < 2) {
                    throw IOException(
                        "Eine bestehende Verbindung wurde softwaregesteuert durch den Hostcomputer abgebrochen",
                    )
                }
                Unit
            }
            assertEquals(2, calls, "should retry on the German WSAECONNABORTED text")
        }

    @Test
    fun `does NOT retry UnknownHostException - signals misconfig not transient`() =
        runTest {
            var calls = 0
            assertFailsWith<UnknownHostException> {
                service().withRetry("upload", "/x", backoffMs = fastBackoff) {
                    calls++
                    throw UnknownHostException("dav.no-such-host.example")
                }
            }
            assertEquals(1, calls, "UnknownHostException must be terminal — not transient")
        }

    @Test
    fun `does NOT retry SSLHandshakeException - signals misconfig`() =
        runTest {
            var calls = 0
            assertFailsWith<SSLHandshakeException> {
                service().withRetry("download", "/x", backoffMs = fastBackoff) {
                    calls++
                    throw SSLHandshakeException("PKIX path validation failed")
                }
            }
            assertEquals(1, calls)
        }

    // ── Timeout-shaped IOException ────────────────────────────────────────
    //
    // Ktor's HttpRequestTimeoutException takes an HttpRequestData arg that's
    // not constructible outside the engine pipeline; use SocketTimeoutException
    // directly. withRetry treats it as retriable via isRetriableIoException.

    @Test
    fun `retries on SocketTimeoutException then succeeds`() =
        runTest {
            var calls = 0
            service().withRetry("upload", "/big.mp4", backoffMs = fastBackoff) {
                calls++
                if (calls < 2) {
                    throw java.net.SocketTimeoutException("read timed out after 600s")
                }
                Unit
            }
            assertEquals(2, calls)
        }

    // ── CancellationException always propagates ────────────────────────────

    @Test
    fun `CancellationException propagates immediately - no retry`() =
        runTest {
            var calls = 0
            assertFailsWith<CancellationException> {
                service().withRetry("upload", "/x", backoffMs = fastBackoff) {
                    calls++
                    throw CancellationException("user cancelled relocate")
                }
            }
            assertEquals(1, calls, "Cancellation must short-circuit the retry loop (UD-300)")
        }

    // ── Retry budget exhaustion ────────────────────────────────────────────

    @Test
    fun `exhausts retry budget then throws last captured exception`() =
        runTest {
            var calls = 0
            val ex =
                assertFailsWith<IOException> {
                    service().withRetry(
                        "upload",
                        "/x",
                        maxAttempts = 3,
                        backoffMs = fastBackoff,
                    ) {
                        calls++
                        throw IOException("connection reset attempt $calls")
                    }
                }
            assertEquals(3, calls, "should attempt exactly maxAttempts times")
            assertTrue(
                ex.message!!.contains("attempt 3"),
                "thrown exception should be the LAST captured one; was: ${ex.message}",
            )
        }

    // ── isRetriableStatus ─────────────────────────────────────────────────

    @Test
    fun `isRetriableStatus returns true for 408 425 429 5xx`() {
        val s = service()
        assertTrue(s.isRetriableStatus(HttpStatusCode.RequestTimeout))
        assertTrue(s.isRetriableStatus(HttpStatusCode.TooEarly))
        assertTrue(s.isRetriableStatus(HttpStatusCode.TooManyRequests))
        assertTrue(s.isRetriableStatus(HttpStatusCode.InternalServerError))
        assertTrue(s.isRetriableStatus(HttpStatusCode.BadGateway))
        assertTrue(s.isRetriableStatus(HttpStatusCode.ServiceUnavailable))
        assertTrue(s.isRetriableStatus(HttpStatusCode.GatewayTimeout))
    }

    @Test
    fun `isRetriableStatus returns false for 4xx terminal codes`() {
        val s = service()
        assertEquals(false, s.isRetriableStatus(HttpStatusCode.BadRequest))
        assertEquals(false, s.isRetriableStatus(HttpStatusCode.Unauthorized))
        assertEquals(false, s.isRetriableStatus(HttpStatusCode.Forbidden))
        assertEquals(false, s.isRetriableStatus(HttpStatusCode.NotFound))
        assertEquals(false, s.isRetriableStatus(HttpStatusCode.Conflict))
        assertEquals(false, s.isRetriableStatus(HttpStatusCode.Gone))
        assertEquals(false, s.isRetriableStatus(HttpStatusCode.MethodNotAllowed))
        assertEquals(false, s.isRetriableStatus(HttpStatusCode.OK))
    }

    // ── isRetriableIoException ────────────────────────────────────────────

    @Test
    fun `isRetriableIoException - SocketTimeoutException is retriable`() {
        assertTrue(service().isRetriableIoException(java.net.SocketTimeoutException("read timed out")))
    }

    @Test
    fun `isRetriableIoException - SocketException is retriable`() {
        assertTrue(service().isRetriableIoException(java.net.SocketException("Connection reset")))
    }

    @Test
    fun `isRetriableIoException - UnknownHostException is NOT retriable`() {
        assertEquals(false, service().isRetriableIoException(UnknownHostException("dav.example.com")))
    }

    @Test
    fun `isRetriableIoException - SSLHandshakeException is NOT retriable`() {
        assertEquals(false, service().isRetriableIoException(SSLHandshakeException("bad cert")))
    }

    @Test
    fun `isRetriableIoException - message containing aborted is retriable`() {
        assertTrue(service().isRetriableIoException(IOException("Connection aborted")))
    }
}
