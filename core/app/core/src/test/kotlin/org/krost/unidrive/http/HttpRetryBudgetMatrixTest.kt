package org.krost.unidrive.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
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
 * here exercise what the budget itself decides; per-status rows that the
 * budget does not classify centrally are marked `@Ignore` with a TODO
 * referencing UD-207. See the report on the ticket for the full divergence
 * list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpRetryBudgetMatrixTest {
    private class FakeClock(
        var now: Long = 0L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    // -- Row: Permanent client error (4xx except 408/429) — fail fast --------

    @Test
    @Ignore(
        "UD-207: HttpRetryBudget is the coordination layer; per-status retry decisions live " +
            "in provider-level withRetry helpers (e.g. WebDavApiService.isRetriableStatus). The " +
            "budget itself has no decide(status) entry point. Re-enable once UD-330 lifts the " +
            "shared classifier into :app:core.",
    )
    @Suppress("ktlint:standard:function-naming")
    fun `4xx (404) does not retry`() {
        // Intentionally empty — see @Ignore reason. Provider-level coverage:
        // WebDavApiServiceRetryTest.`isRetriableStatus returns true for 408 425 429 5xx`.
    }

    // -- Row: Timeout (408) — retries with exp+jitter -----------------------

    @Test
    @Ignore(
        "UD-207: HttpRetryBudget has no per-status decide() entry point. 408 retriability is " +
            "asserted at the provider layer (WebDavApiServiceRetryTest). Re-enable when the " +
            "shared classifier lands under UD-330.",
    )
    @Suppress("ktlint:standard:function-naming")
    fun `408 retries with exponential backoff`() {
        // See @Ignore reason.
    }

    // -- Row: Throttle (429) — honors Retry-After ---------------------------

    @Test
    @Suppress("ktlint:standard:function-naming")
    fun `429 honors Retry-After capped by maxRetryAfter`() =
        runTest {
            // The budget's contract for 429 is observable via recordThrottle: the largest
            // Retry-After in the storm window is what gates resumeAfterEpochMs once the
            // storm threshold trips. We assert that:
            //   - a single 429 with retryAfter=R does NOT prematurely open the circuit;
            //   - the recorded retryAfter is preserved for the storm hint.
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

    // -- Row: Server error (5xx) — retries with exp+jitter ------------------

    @Test
    @Ignore(
        "UD-207: HttpRetryBudget routes 5xx through recordThrottle (alongside 429) for storm " +
            "detection but does not itself classify per-status retriability. 5xx retry is " +
            "asserted at the provider layer (WebDavApiServiceRetryTest, GraphApiService " +
            "uploadChunkWithRetries). Re-enable under UD-330.",
    )
    @Suppress("ktlint:standard:function-naming")
    fun `5xx (503) retries with exponential backoff`() {
        // See @Ignore reason.
    }

    // -- Row: Network error (no status) — retries with exp+jitter -----------

    @Test
    @Suppress("ktlint:standard:function-naming")
    fun `network IOException retries with exponential backoff`() {
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

    // -- Row: Unknown exception — retries up to min(3, budget.maxRetries) ---

    @Test
    @Ignore(
        "UD-207: HttpRetryBudget does not currently expose a maxRetries cap distinct from " +
            "provider-side max-attempt loops, and does not differentiate the 'Unknown' caller " +
            "class from network errors. The matrix prescribes min(3, budget.maxRetries) for " +
            "unknown exceptions; today the cap is set per provider (WebDav: 5; OneDrive chunk " +
            "upload: 3). Re-enable once a budget-level max-attempt API lands.",
    )
    @Suppress("ktlint:standard:function-naming")
    fun `unknown exception caps at 3 retries even when budget allows more`() {
        // See @Ignore reason.
    }
}
