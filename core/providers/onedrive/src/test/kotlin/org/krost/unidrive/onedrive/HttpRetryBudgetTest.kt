package org.krost.unidrive.onedrive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.HttpRetryBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HttpRetryBudgetTest {
    private class FakeClock(
        var now: Long = 0L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `circuit stays closed under the storm threshold`() =
        runTest {
            val clock = FakeClock()
            val b = HttpRetryBudget(maxConcurrency = 8, stormThreshold = 4, clock = clock)
            // 3 throttles is below the threshold (default 4).
            repeat(3) { b.recordThrottle(retryAfterMs = 5_000) }
            assertEquals(0, b.resumeAfterEpochMs(), "Circuit should not open on 3 throttles")
            assertEquals(8, b.currentConcurrency(), "Concurrency should be unchanged")
        }

    @Test
    fun `four throttles inside the window open the circuit and halve concurrency`() {
        // Invariant: once the storm threshold trips, resume must be pushed past the
        // largest observed retryAfter and concurrency must shrink. The exact backoff
        // factor (currently 1.2×) and the exact shrink ratio are implementation
        // details; assert direction-and-floor only. UD-812.
        val clock = FakeClock()
        val b = HttpRetryBudget(maxConcurrency = 8, stormThreshold = 4, clock = clock)
        clock.now = 1_000
        repeat(4) {
            b.recordThrottle(retryAfterMs = 10_000)
            clock.now += 500 // 4 events within 2s, well inside the 20s window
        }
        assertTrue(b.resumeAfterEpochMs() > clock.now, "Circuit should be open")
        assertTrue(
            b.resumeAfterEpochMs() > clock.now + 10_000,
            "Resume must be at least max(retryAfter) into the future; exact scale is an implementation detail",
        )
        assertTrue(
            b.currentConcurrency() in 1 until 8,
            "Concurrency must shrink from 8 but stay ≥ 1 after a storm",
        )
    }

    @Test
    fun `throttles older than the window are pruned`() {
        val clock = FakeClock()
        val b = HttpRetryBudget(maxConcurrency = 8, stormThreshold = 4, stormWindowMs = 20_000, clock = clock)
        clock.now = 0
        // 3 throttles at t=0
        repeat(3) { b.recordThrottle(5_000) }
        // Advance past the window
        clock.now = 25_000
        // One more throttle at t=25000 — the 3 old entries should prune; total in window = 1
        b.recordThrottle(5_000)
        assertEquals(0, b.resumeAfterEpochMs(), "Pruned window should not trigger storm")
        assertEquals(8, b.currentConcurrency())
    }

    @Test
    fun `repeated storms shrink concurrency each time with floor 1`() {
        // Invariant: each sustained storm must push concurrency strictly lower than the
        // previous level, but never below 1. The exact shrink ratio (currently /2) is an
        // implementation detail — a different strategy (linear, /3, etc.) that still
        // converges to 1 must also pass. UD-812.
        val clock = FakeClock()
        val b = HttpRetryBudget(maxConcurrency = 8, stormThreshold = 4, clock = clock)
        // Storm 1
        repeat(4) {
            b.recordThrottle(5_000)
            clock.now += 100
        }
        val c1 = b.currentConcurrency()
        assertTrue(c1 in 1 until 8, "concurrency shrinks on first storm (got $c1)")
        // Advance past window so old throttles don't count
        clock.now += 30_000
        // Storm 2
        repeat(4) {
            b.recordThrottle(5_000)
            clock.now += 100
        }
        val c2 = b.currentConcurrency()
        assertTrue(c2 in 1 until c1, "further shrink on second storm, never below 1 (got $c2 after $c1)")
        // Keep storming until we hit the floor
        repeat(8) {
            clock.now += 30_000
            repeat(4) {
                b.recordThrottle(5_000)
                clock.now += 100
            }
        }
        assertEquals(1, b.currentConcurrency(), "floor at 1 after enough storms")
        // One more storm past the floor — stays at 1.
        clock.now += 30_000
        repeat(4) {
            b.recordThrottle(5_000)
            clock.now += 100
        }
        assertEquals(1, b.currentConcurrency(), "Concurrency should never drop below 1")
    }

    @Test
    fun `awaitSlot does not delay when circuit is closed and no prior token was claimed`() =
        runTest {
            // Align HttpRetryBudget's clock with the test's virtual time so delay() inside
            // awaitSlot matches what currentTime observes.
            val b = HttpRetryBudget(maxConcurrency = 8, clock = { currentTime }, minSpacingMs = 200)
            val before = currentTime
            b.awaitSlot()
            val elapsed = currentTime - before
            // First call: no circuit wait, token bucket nextAllowed starts at 0 so no spacing wait either.
            assertTrue(elapsed < 50, "First awaitSlot should be near-instant, was ${elapsed}ms")
        }

    @Test
    fun `token bucket enforces minimum spacing between slot claims after a recent throttle`() =
        runTest {
            // UD-200: spacing is adaptive. With a throttle inside the last noThrottleWindowMs
            // (default 60s) but no storm, awaitSlot should enforce the configured minSpacingMs.
            val b = HttpRetryBudget(maxConcurrency = 8, clock = { currentTime }, minSpacingMs = 200)
            b.recordThrottle(retryAfterMs = 1_000) // single 429 → "recent throttle, no storm" band
            val t0 = currentTime
            b.awaitSlot()
            b.awaitSlot()
            b.awaitSlot()
            // With 200ms spacing enforced by the token bucket, 3 slots = 2 × 200ms waits = 400ms.
            val elapsed = currentTime - t0
            assertTrue(
                elapsed >= 400,
                "3 back-to-back slots should take ≥400ms with 200ms spacing, was ${elapsed}ms",
            )
        }

    @Test
    fun `UD-200 awaitSlot has no spacing in the steady-state no-throttle fast path`() =
        runTest {
            // UD-200 fast path: with no throttle ever observed, awaitSlot() must not introduce
            // a 200 ms floor. Five back-to-back claims complete in ~0 ms of virtual time.
            val b = HttpRetryBudget(maxConcurrency = 8, clock = { currentTime }, minSpacingMs = 200)
            val t0 = currentTime
            repeat(5) { b.awaitSlot() }
            val elapsed = currentTime - t0
            assertEquals(0L, elapsed, "5 awaitSlots in the no-throttle steady state must not delay")
        }

    @Test
    fun `UD-200 awaitSlot drops spacing to zero once the no-throttle window elapses`() {
        // Advance the clock past noThrottleWindowMs since the last 429: spacing must relax
        // back to 0 so the steady-state fast path re-engages.
        val clock = FakeClock(now = 1_000)
        val b =
            HttpRetryBudget(
                maxConcurrency = 8,
                clock = clock,
                minSpacingMs = 200,
                noThrottleWindowMs = 60_000,
            )
        b.recordThrottle(retryAfterMs = 1_000)
        assertEquals(200L, b.currentSpacingMs(), "recent throttle → 200ms spacing")
        clock.now += 59_000 // still inside the window
        assertEquals(200L, b.currentSpacingMs(), "still inside no-throttle window → 200ms")
        clock.now += 2_000 // now > 60s since the last throttle
        assertEquals(0L, b.currentSpacingMs(), "outside no-throttle window → 0ms fast path")
    }

    @Test
    fun `UD-200 spacing ramps to stormSpacingMs while circuit is open and relaxes after 30s`() {
        // Under an active storm, spacing must firm up to stormSpacingMs (500ms default) and
        // stay there for postStormRecoveryMs after the circuit closes, then relax back to
        // the normal minSpacingMs band.
        val clock = FakeClock(now = 1_000)
        val b =
            HttpRetryBudget(
                maxConcurrency = 8,
                stormThreshold = 4,
                clock = clock,
                minSpacingMs = 200,
                stormSpacingMs = 500,
                postStormRecoveryMs = 30_000,
            )
        // Trigger a storm → circuit opens.
        repeat(4) {
            b.recordThrottle(retryAfterMs = 5_000)
            clock.now += 100
        }
        assertTrue(b.resumeAfterEpochMs() > clock.now, "Circuit should be open after storm")
        assertEquals(500L, b.currentSpacingMs(), "Storm spacing while circuit is open")

        // Advance to exactly the resumeAt instant (circuit just closed).
        clock.now = b.resumeAfterEpochMs()
        assertEquals(500L, b.currentSpacingMs(), "Storm spacing at t=resumeAfter (0s after close)")

        // 29s after close: still in the recovery band.
        clock.now += 29_000
        assertEquals(500L, b.currentSpacingMs(), "Storm spacing 29s after circuit close")

        // 31s after close: recovery band expires, back to normal single-throttle spacing.
        clock.now += 2_000
        assertEquals(
            200L,
            b.currentSpacingMs(),
            "Spacing relaxes to minSpacingMs after postStormRecoveryMs",
        )
    }

    @Test
    fun `recordSuccess is a no-op before any storm`() {
        val clock = FakeClock()
        val b = HttpRetryBudget(maxConcurrency = 8, clock = clock)
        b.recordSuccess()
        assertEquals(8, b.currentConcurrency(), "Pre-storm recordSuccess should not change concurrency")
    }

    @Test
    fun `recordSuccess restores one permit after the recovery interval`() {
        val clock = FakeClock()
        val b =
            HttpRetryBudget(
                maxConcurrency = 8,
                stormThreshold = 4,
                recoveryCleanIntervalMs = 60_000,
                clock = clock,
            )
        clock.now = 1_000
        // Trigger a storm → 8 → 4
        repeat(4) {
            b.recordThrottle(5_000)
            clock.now += 100
        }
        assertEquals(4, b.currentConcurrency())

        // recordSuccess too early: no restore
        clock.now += 10_000
        b.recordSuccess()
        assertEquals(4, b.currentConcurrency())

        // Advance past recovery interval: one permit restored
        clock.now += 70_000
        b.recordSuccess()
        assertEquals(5, b.currentConcurrency())

        // Another interval later: +1
        clock.now += 70_000
        b.recordSuccess()
        assertEquals(6, b.currentConcurrency())
    }

    @Test
    fun `recordSuccess does not exceed maxConcurrency`() {
        val clock = FakeClock()
        val b =
            HttpRetryBudget(
                maxConcurrency = 3,
                stormThreshold = 4,
                recoveryCleanIntervalMs = 1_000,
                clock = clock,
            )
        clock.now = 1_000
        // Force a storm
        repeat(4) {
            b.recordThrottle(1_000)
            clock.now += 10
        }
        assertEquals(1, b.currentConcurrency(), "3 / 2 = 1 (integer floor)")
        // Advance enough to ramp back multiple times
        repeat(10) {
            clock.now += 5_000
            b.recordSuccess()
        }
        assertEquals(3, b.currentConcurrency(), "Concurrency should cap at maxConcurrency")
    }

    // -- UD-278: IOException retry classification + counter --------------------
    //
    // The taxonomy mirrors WebDav's UD-288 isRetriableIoException helper.
    // Lifting it to HttpRetryBudget.companion lets every provider adopt the
    // same shape (UD-330 follow-up). Tests pin both the include (transient
    // TCP failures) and exclude (DNS / SSL misconfig) cases so a future
    // taxonomy edit can't silently flip a class.

    @Test
    fun `UD-278 - isRetriableIoException - transient TCP failures retry`() {
        // SocketTimeoutException — read or connect timer fired.
        assertTrue(HttpRetryBudget.isRetriableIoException(java.net.SocketTimeoutException("read timed out")))
        // SocketException — covers connection reset, broken pipe.
        assertTrue(HttpRetryBudget.isRetriableIoException(java.net.SocketException("Connection reset")))
        assertTrue(HttpRetryBudget.isRetriableIoException(java.net.SocketException("Broken pipe")))
    }

    @Test
    fun `UD-278 - isRetriableIoException - localised Windows TCP RST messages match`() {
        // Plain English "aborted" — Windows WSAECONNABORTED surface.
        assertTrue(HttpRetryBudget.isRetriableIoException(java.io.IOException("Connection aborted")))
        // German Windows: "Eine bestehende Verbindung wurde softwaregesteuert..."
        assertTrue(
            HttpRetryBudget.isRetriableIoException(
                java.io.IOException(
                    "Eine bestehende Verbindung wurde softwaregesteuert durch den Hostcomputer abgebrochen",
                ),
            ),
        )
        // Premature EOF mid-body.
        assertTrue(HttpRetryBudget.isRetriableIoException(java.io.IOException("premature end of stream")))
    }

    @Test
    fun `UD-278 - isRetriableIoException - misconfig classes do NOT retry`() {
        // DNS misconfig — retrying won't fix it.
        assertEquals(false, HttpRetryBudget.isRetriableIoException(java.net.UnknownHostException("nope.invalid")))
        // SSL handshake — protocol/cert mismatch, retry won't fix.
        assertEquals(false, HttpRetryBudget.isRetriableIoException(javax.net.ssl.SSLHandshakeException("bad cert")))
        assertEquals(
            false,
            HttpRetryBudget.isRetriableIoException(javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch")),
        )
        // Generic IOException with no clue — conservative default is don't retry.
        assertEquals(false, HttpRetryBudget.isRetriableIoException(java.io.IOException("???")))
    }

    @Test
    fun `UD-278 - recordIoRetry counter accumulates`() {
        val b = HttpRetryBudget(maxConcurrency = 4)
        assertEquals(0, b.ioRetryCount())
        b.recordIoRetry()
        b.recordIoRetry()
        b.recordIoRetry()
        assertEquals(3, b.ioRetryCount())
    }
}
