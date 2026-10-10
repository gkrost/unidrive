package org.krost.unidrive.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-207: lock the canonical HTTP retry-policy matrix as a contract on
 * `HttpRetryBudget`. Each test name reads as a sentence asserting one row of
 * the matrix in `HttpRetryBudget`'s class KDoc.
 *
 * Scope: `HttpRetryBudget` is the *coordination* layer (token bucket, circuit
 * breaker, IOException classifier). The per-status (4xx/5xx/408/429) decision
 * lives in each provider's `withRetry` / `authenticatedRequest` loop. Tests
 * here exercise only what the budget itself decides; the per-status rows
 * (4xx, 408, 5xx, the unknown-exception cap) are not the budget's job and
 * belong in the provider tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpRetryBudgetMatrixTest {
    private class FakeClock(
        var now: Long = 0L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    // -- Row: Throttle (429) — storm threshold + largest-Retry-After gating ---

    // UD-811 audit: renamed from `429 honors Retry-After capped by maxRetryAfter`.
    // The original name promised a `maxRetryAfter` cap that does not exist in
    // HttpRetryBudget — only in the KDoc matrix's prescriptive cell. The body
    // never asserted any cap. The actual invariants the body pins are
    // (a) a single 429 below the storm threshold does not open the circuit,
    // and (b) once the storm trips, the largest Retry-After in the window
    // gates resumeAfterEpochMs. Renamed accordingly; if/when a maxRetryAfter
    // cap is actually implemented in HttpRetryBudget, a new test should pin
    // that contract separately.
    @Test
    @Suppress("ktlint:standard:function-naming")
    fun `429 storm opens circuit honoring largest Retry-After observed`() =
        runTest {
            val clock = FakeClock(now = 1_000)
            val budget =
                HttpRetryBudget(
                    maxConcurrency = 8,
                    stormThreshold = 4,
                    clock = clock,
                )
            // Single 429 below the storm threshold — circuit stays closed.
            budget.recordThrottle(retryAfterMs = 5_000)
            assertEquals(0L, budget.resumeAfterEpochMs(), "single 429 must not open the circuit")
            assertEquals(8, budget.currentConcurrency(), "single 429 must not shrink concurrency")
            // Trip the storm — the cumulative max(retryAfter) gates the resumeAt.
            repeat(3) {
                budget.recordThrottle(retryAfterMs = 5_000)
                clock.now += 100
            }
            assertTrue(
                budget.resumeAfterEpochMs() >= clock.now + 5_000,
                "circuit resumeAt must honor the largest Retry-After observed in the storm window",
            )
        }

    // -- Row: Network error (no status) — retriability classification --------

    // UD-811 audit: renamed from `network IOException retries with exponential
    // backoff`. The previous name promised backoff verification, but the body
    // exercises only `HttpRetryBudget.isRetriableIoException`'s classifier —
    // it asserts which IOException subclasses are transient (retry-worthy)
    // versus misconfig (fail fast). The actual exponential-backoff behaviour
    // is tested at the provider layer (GraphApiService
    // uploadChunkWithRetries), not here. Renamed to match
    // the body's actual scope.
    @Test
    @Suppress("ktlint:standard:function-naming")
    fun `isRetriableIoException distinguishes transient TCP failures from misconfig`() {
        // The budget classifies which IOExceptions are worth retrying via
        // isRetriableIoException on its companion. Transient TCP failures must be
        // retriable; misconfig classes (DNS, SSL) must NOT be.
        assertTrue(
            HttpRetryBudget.isRetriableIoException(java.net.SocketTimeoutException("read timed out")),
            "SocketTimeoutException is a transient TCP failure — must retry",
        )
        assertTrue(
            HttpRetryBudget.isRetriableIoException(java.net.SocketException("Connection reset")),
            "SocketException 'Connection reset' is transient — must retry",
        )
        assertTrue(
            HttpRetryBudget.isRetriableIoException(java.net.SocketException("Broken pipe")),
            "SocketException 'Broken pipe' is transient — must retry",
        )
        assertTrue(
            HttpRetryBudget.isRetriableIoException(java.io.IOException("premature end of stream")),
            "premature EOF mid-body is transient — must retry",
        )
        // Misconfig classes — explicit non-retriable.
        assertFalse(
            HttpRetryBudget.isRetriableIoException(java.net.UnknownHostException("nope.invalid")),
            "UnknownHostException is DNS misconfig — must NOT retry",
        )
        assertFalse(
            HttpRetryBudget.isRetriableIoException(javax.net.ssl.SSLHandshakeException("bad cert")),
            "SSLHandshakeException is protocol/cert misconfig — must NOT retry",
        )
        assertFalse(
            HttpRetryBudget.isRetriableIoException(javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch")),
            "SSLPeerUnverifiedException is cert mismatch — must NOT retry",
        )
    }
}
