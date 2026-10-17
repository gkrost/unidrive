package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryAfterTest {
    // 2026-10-21 07:28:00 GMT, a Wednesday.
    private val now = 1_792_567_680_000L
    private val dateAhead5s = "Wed, 21 Oct 2026 07:28:05 GMT"

    @Test
    fun `delta-seconds become milliseconds, surrounding blanks ignored`() {
        assertEquals(7_000L, parseRetryAfterHeader("7", now))
        assertEquals(7_000L, parseRetryAfterHeader(" 7 ", now))
        assertEquals(0L, parseRetryAfterHeader("0", now))
    }

    @Test
    fun `an HTTP date gives the wait until that date`() {
        assertEquals(5_000L, parseRetryAfterHeader(dateAhead5s, now))
        assertEquals(5_000L, parseRetryAfterHeader("  $dateAhead5s ", now))
    }

    @Test
    fun `a date that has passed leaves lower precedence retry hints available`() {
        assertNull(parseRetryAfterHeader("Wed, 21 Oct 2026 07:27:00 GMT", now))
        assertNull(parseRetryAfterHeader("Wed, 21 Oct 2026 07:28:00 GMT", now))
    }

    @Test
    fun `a wait far in the future is clamped`() {
        assertEquals(MAX_RETRY_AFTER_MS, parseRetryAfterHeader("Fri, 21 Oct 2101 07:28:00 GMT", now))
        assertEquals(MAX_RETRY_AFTER_MS, parseRetryAfterHeader("86400", now))
        assertEquals(MAX_RETRY_AFTER_MS, parseRetryAfterHeader("99999999999999999999999", now))
    }

    @Test
    fun `negative, garbage, empty and absent give null`() {
        assertNull(parseRetryAfterHeader("-1", now))
        assertNull(parseRetryAfterHeader("soon", now))
        assertNull(parseRetryAfterHeader("1.5", now))
        assertNull(parseRetryAfterHeader("Thu, 21 Oct 2026 07:28:00 GMT", now)) // 21 Oct 2026 is no Thursday
        assertNull(parseRetryAfterHeader("", now))
        assertNull(parseRetryAfterHeader("   ", now))
        assertNull(parseRetryAfterHeader(null, now))
    }
}
