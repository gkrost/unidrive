package org.krost.unidrive.http

import java.time.DateTimeException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Longest wait [parseRetryAfterHeader] returns; callers still apply their own, lower caps. */
const val MAX_RETRY_AFTER_MS: Long = 3_600_000L

private val DELTA_SECONDS = Regex("""\d+""")

/**
 * The wait in milliseconds a `Retry-After` header asks for (RFC 9110 section 10.2.3), or null when the
 * header is absent, empty, negative or not understood. Both forms are read: delta-seconds, and an HTTP date
 * in the IMF-fixdate form (`Wed, 21 Oct 2026 07:28:00 GMT`). A date that has already passed gives 0, and a
 * wait longer than [MAX_RETRY_AFTER_MS] is clamped to it.
 */
fun parseRetryAfterHeader(
    header: String?,
    nowMs: Long = System.currentTimeMillis(),
): Long? {
    val raw = header?.trim()?.takeUnless { it.isEmpty() } ?: return null
    if (DELTA_SECONDS.matches(raw)) {
        val seconds = raw.toLongOrNull() ?: return MAX_RETRY_AFTER_MS
        return minOf(seconds, MAX_RETRY_AFTER_MS / 1000) * 1000
    }
    return try {
        val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (target - nowMs).coerceIn(0L, MAX_RETRY_AFTER_MS)
    } catch (_: DateTimeException) {
        null
    }
}
