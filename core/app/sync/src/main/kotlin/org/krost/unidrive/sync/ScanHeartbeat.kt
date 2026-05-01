package org.krost.unidrive.sync

/**
 * Heartbeat helper for long-running scans.
 *
 * Fires [onFire] when EITHER:
 *   - the running count has advanced by [intervalItems] since the last fire, OR
 *   - [intervalMs] wall-clock have elapsed since the last fire.
 *
 * Whichever threshold trips first wins, and BOTH counters reset on every fire.
 * That way a fast walk emits by item-count and a slow walk still emits by
 * wall-clock — neither path silently overshoots the other's threshold.
 *
 * The defaults (5_000 items / 10_000 ms) mirror the original `LocalScanner`
 * heartbeat math from UD-742, lifted here as a single source of truth so the
 * cross-provider remote-scan heartbeat (UD-352) and the local-scan heartbeat
 * stay in sync. Tests can inject [clock] for deterministic time-threshold
 * assertions.
 *
 * Not thread-safe: each scan should own its own instance.
 */
class ScanHeartbeat(
    private val onFire: (count: Int) -> Unit,
    private val intervalItems: Int = DEFAULT_INTERVAL_ITEMS,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastFireItems: Int = 0
    private var lastFireMs: Long = clock()

    /**
     * Fire [onFire] if either threshold has been crossed since the last fire.
     * Idempotent at the same count: re-calling with an unchanged count and
     * before [intervalMs] elapses is a no-op.
     */
    fun tick(currentCount: Int) {
        val now = clock()
        if (currentCount - lastFireItems >= intervalItems || now - lastFireMs >= intervalMs) {
            onFire(currentCount)
            lastFireItems = currentCount
            lastFireMs = now
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_ITEMS: Int = 5_000
        const val DEFAULT_INTERVAL_MS: Long = 10_000L
    }
}
