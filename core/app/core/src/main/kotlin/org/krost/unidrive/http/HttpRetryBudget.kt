package org.krost.unidrive.http

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * UD-232: shared throttle coordinator for a single `GraphApiService`.
 *
 * Per-request retry (UD-207, UD-227) handles 429/503 for one call at a time. It does not
 * brake global request rate while other coroutines keep firing. On the UD-712 live sync
 * this let 6 medium-download coroutines all hit 429 at once, each wait its own timer, then
 * all resume simultaneously → next storm → 459 permanent failures (2.9 % of files).
 *
 * HttpRetryBudget adds three cross-request levers:
 *
 *  1. **Circuit breaker.** When ≥ [stormThreshold] throttles land inside [stormWindowMs],
 *     [awaitSlot] blocks all new request starts until `max(retryAfter) × 1.2` has elapsed.
 *     Coroutines already past [awaitSlot] run to completion — we don't cancel in-flight work.
 *  2. **Concurrency halving.** Each storm halves [currentConcurrency] (floor = 1). Callers
 *     observe the reduced value to shrink their own semaphore permits on the next pass.
 *  3. **Adaptive token bucket.** [awaitSlot] enforces a minimum gap between successive
 *     request starts so concurrent wake-ups don't re-burst immediately. UD-200: the gap is
 *     read from recent-throttle state at each call so the fast path has no artificial cap:
 *     no throttle in [noThrottleWindowMs] → 0 ms; at least one recent throttle → [minSpacingMs];
 *     circuit open or within [postStormRecoveryMs] of closing → [stormSpacingMs].
 *
 * Recovery: every [recoveryCleanIntervalMs] of no new throttle events, concurrency is
 * restored by +1 permit, up to [maxConcurrency].
 *
 * Clock is injectable so tests stay deterministic.
 */
class HttpRetryBudget(
    private val maxConcurrency: Int,
    private val stormThreshold: Int = 4,
    private val stormWindowMs: Long = 20_000L,
    private val stormBackoffFactor: Double = 1.2,
    private val minSpacingMs: Long = 200L,
    private val stormSpacingMs: Long = 500L,
    private val noThrottleWindowMs: Long = 60_000L,
    private val postStormRecoveryMs: Long = 30_000L,
    private val recoveryCleanIntervalMs: Long = 5L * 60 * 1000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val log = LoggerFactory.getLogger(HttpRetryBudget::class.java)

    private val recentThrottles = ConcurrentLinkedDeque<Long>()
    private val lastRetryAfterInWindowMs = AtomicLong(0)
    private val globalResumeAfter = AtomicLong(0)
    private val concurrency = AtomicInteger(maxConcurrency)

    // Initialise well below any real epoch-ms clock so the first awaitSlot doesn't incur a
    // spurious minSpacingMs wait: `prev + minSpacingMs` stays below `clock()` on the first
    // call, so myStart = clock() and spacingWait = 0.
    private val nextAllowedStart = AtomicLong(Long.MIN_VALUE / 2)

    // Sentinel Long.MIN_VALUE (not 0) for "never observed" so an injected test clock that
    // starts at t=0 doesn't collide with a real event recorded at t=0. UD-200.
    private val lastThrottleEventMs = AtomicLong(Long.MIN_VALUE)
    private val lastRecoveryAtMs = AtomicLong(Long.MIN_VALUE)

    /** Current target concurrency. Callers can read this each time they acquire a permit
     *  and shrink their own semaphore to match. */
    fun currentConcurrency(): Int = concurrency.get()

    /** Timestamp (epoch ms) until which all new request starts should pause.
     *  Zero when the circuit is closed. Exposed for diagnostics / tests. */
    fun resumeAfterEpochMs(): Long = globalResumeAfter.get()

    /** Record a throttle response. [retryAfterMs] is the server-hinted wait (from
     *  Retry-After header or `retryAfterSeconds` JSON body). */
    fun recordThrottle(retryAfterMs: Long) {
        val now = clock()
        lastThrottleEventMs.set(now)
        recentThrottles.addLast(now)
        pruneOldThrottles(now)
        lastRetryAfterInWindowMs.updateAndGet { maxOf(it, retryAfterMs) }

        if (recentThrottles.size >= stormThreshold) {
            val hint = lastRetryAfterInWindowMs.get().coerceAtLeast(minSpacingMs)
            val backoffMs = (hint * stormBackoffFactor).toLong()
            val wasHalved =
                concurrency.updateAndGet { current -> maxOf(1, current / 2) }
            globalResumeAfter.updateAndGet { maxOf(it, now + backoffMs) }
            log.warn(
                "UD-232 throttle storm: {} × 429/503 in {}ms — pausing new requests {}ms, " +
                    "concurrency {}→{}",
                recentThrottles.size,
                stormWindowMs,
                backoffMs,
                maxConcurrency,
                wasHalved,
            )
        }
    }

    /** Optionally call on every clean (2xx) response so the budget can restore concurrency
     *  after a prolonged quiet period. Cheap — mostly a timestamp check. */
    fun recordSuccess() {
        val now = clock()
        val lastEvent = maxOf(lastThrottleEventMs.get(), lastRecoveryAtMs.get())
        if (lastEvent == Long.MIN_VALUE) return // no storm yet, nothing to recover
        if (now - lastEvent < recoveryCleanIntervalMs) return
        val current = concurrency.get()
        if (current >= maxConcurrency) return
        if (concurrency.compareAndSet(current, current + 1)) {
            lastRecoveryAtMs.set(now)
            log.info("UD-232 throttle recovery: concurrency {}→{}", current, current + 1)
        }
    }

    /** Blocks until the circuit is closed AND the token bucket admits this caller.
     *  Call this immediately before starting a request. */
    suspend fun awaitSlot() {
        // 1. Circuit breaker: if open, wait out the global pause.
        while (true) {
            val resumeAt = globalResumeAfter.get()
            val now = clock()
            val circuitWait = resumeAt - now
            if (circuitWait <= 0) break
            delay(circuitWait)
        }
        // 2. Adaptive token bucket: claim a slot at least currentSpacingMs() after the
        //    previous claim. UD-200: 0 in the steady state (no recent throttle), restoring
        //    the pre-UD-232 ~119 files/min throughput. Ramps to 200/500 ms on throttle.
        val spacing = currentSpacingMs()
        val myStart =
            nextAllowedStart.updateAndGet { prev ->
                maxOf(prev + spacing, clock())
            }
        val spacingWait = myStart - clock()
        if (spacingWait > 0) delay(spacingWait)
    }

    /** UD-200: adaptive inter-request spacing. Read at every [awaitSlot] from state we
     *  already track — [lastThrottleEventMs] and [globalResumeAfter] — so the no-throttle
     *  steady state pays zero spacing cost and a live storm ramps firmly. Exposed for
     *  tests and diagnostics; callers should go through [awaitSlot]. */
    fun currentSpacingMs(): Long {
        val now = clock()
        val resumeAt = globalResumeAfter.get()
        val lastThrottle = lastThrottleEventMs.get()

        // Storm band: circuit open right now, or we're inside postStormRecoveryMs after it
        // closed (whichever side of `resumeAt` `now` is on).
        val circuitEverOpened = resumeAt > 0L
        val timeSinceResume = now - resumeAt // negative while circuit is open
        if (circuitEverOpened && timeSinceResume < postStormRecoveryMs) {
            return stormSpacingMs
        }

        // Quiet steady state: nothing to space out. This is the UD-200 fast path.
        if (lastThrottle == Long.MIN_VALUE || now - lastThrottle > noThrottleWindowMs) {
            return 0L
        }

        // Single 429 within the last minute but no storm: keep modest spacing to dampen
        // concurrent wake-ups without capping bulk throughput.
        return minSpacingMs
    }

    private fun pruneOldThrottles(now: Long) {
        while (true) {
            val head = recentThrottles.peekFirst() ?: break
            if (now - head <= stormWindowMs) break
            recentThrottles.pollFirst()
        }
        if (recentThrottles.isEmpty()) {
            lastRetryAfterInWindowMs.set(0)
        }
    }
}
