package org.krost.unidrive.sync

fun computePollInterval(
    idleCycles: Int,
    min: Int,
    normal: Int,
    max: Int,
): Int =
    when {
        idleCycles < 3 -> min
        idleCycles < 8 -> normal
        else -> max
    }

/**
 * WS-aware variant of [computePollInterval]. When a server-pushed change
 * feed (Internxt's `NOTIFICATIONS_URL`) is healthy the poll loop only needs
 * to fire at a stale-heartbeat cadence — the WS will wake it on every real
 * mutation. We collapse to the existing [max] interval so operators can tune
 * the heartbeat via `[general] max_poll_interval` without a new knob.
 *
 * On WS disconnect (or before the first wake hint ever arrives) the caller
 * passes [wsHealthy] = false and gets the original adaptive ladder.
 */
fun computePollIntervalWithWs(
    idleCycles: Int,
    min: Int,
    normal: Int,
    max: Int,
    wsHealthy: Boolean,
): Int = if (wsHealthy) max else computePollInterval(idleCycles, min, normal, max)

fun pollStateName(idleCycles: Int): String =
    when {
        idleCycles < 3 -> "ACTIVE"
        idleCycles < 8 -> "NORMAL"
        else -> "IDLE"
    }
