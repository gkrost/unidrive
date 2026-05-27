package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * Optional in-process auto-poll for `unidrive daemon run --poll-interval`
 * (mount-view-refresh-design.md §5). When [intervalMs] > 0 the daemon launches
 * ONE periodic coroutine on its serve scope that fires the enumerate path
 * (engine.enumerateRemoteIntoState(reset=false)) every interval — the same
 * operation `sync.enumerate` runs — serialised by the shared in-flight guard so
 * a tick never overlaps a manual refresh/enumerate or another tick. On a
 * provider failure / 429 it extends the next interval (back-off) rather than
 * hammering. Cancelled cleanly when the serve scope is cancelled at shutdown.
 */
class EnumeratePoller(
    private val handler: EnumerateRpcHandler,
    private val intervalMs: Long,
    private val scope: CoroutineScope,
    // ±10% jitter on each sleep (thundering-herd avoidance across profiles).
    // Injectable for deterministic virtual-time tests.
    private val jitter: (Long) -> Long = { base ->
        val span = (base / 10).coerceAtLeast(1)
        base - span + Random.nextLong(2 * span + 1)
    },
    private val backoffMultiplier: Long = DEFAULT_BACKOFF_MULTIPLIER,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
) {
    private val log = LoggerFactory.getLogger(EnumeratePoller::class.java)

    fun start() {
        if (intervalMs <= 0) return
        log.info("auto-poll enabled: enumerate every ${intervalMs}ms (±10% jitter)")
        scope.launch {
            var nextMs = intervalMs
            while (true) {
                try {
                    delay(jitter(nextMs))
                    val result = handler.runGuarded(reset = false)
                    nextMs =
                        when {
                            result == null -> intervalMs // busy: another enumerate held the guard, skip
                            result.ok -> intervalMs
                            else -> {
                                val backed = (intervalMs * backoffMultiplier).coerceAtMost(maxBackoffMs)
                                log.warn("auto-poll: enumerate failed (${result.error}); backing off to ${backed}ms")
                                backed
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("auto-poll: tick error; backing off", e)
                    nextMs = (intervalMs * backoffMultiplier).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_BACKOFF_MULTIPLIER: Long = 4
        const val DEFAULT_MAX_BACKOFF_MS: Long = 600_000

        private val DURATION_REGEX = Regex("^(\\d+)(ms|s|m|h)?$")

        /**
         * Parse a `--poll-interval` value to milliseconds. Accepts a bare number
         * (seconds), or a number suffixed `ms`/`s`/`m`/`h` (e.g. `60s`, `5m`).
         * `0` / `0s` means OFF. Throws IllegalArgumentException on a malformed value.
         */
        fun parseIntervalMs(raw: String): Long {
            val m = DURATION_REGEX.matchEntire(raw.trim())
                ?: throw IllegalArgumentException("invalid --poll-interval '$raw' (use e.g. 0, 60s, 5m)")
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2]) {
                "ms" -> n
                "", "s" -> n * 1_000
                "m" -> n * 60_000
                "h" -> n * 3_600_000
                else -> throw IllegalArgumentException("invalid --poll-interval unit in '$raw'")
            }
        }
    }
}
