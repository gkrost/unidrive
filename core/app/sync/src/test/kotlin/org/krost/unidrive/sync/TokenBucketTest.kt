package org.krost.unidrive.sync

import kotlin.test.*

/**
 * UD-106: token-bucket rate limiter unit tests.
 *
 * Uses an injected clock so timing is deterministic — no sleeps or flakes.
 */
class TokenBucketTest {
    private class FakeClock(
        var nanos: Long = 0L,
    ) : () -> Long {
        override fun invoke(): Long = nanos

        fun advanceMillis(ms: Long) {
            nanos += ms * 1_000_000L
        }
    }

    @Test
    fun `initial burst equals capacity`() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 5, refillPerSecond = 10, clock = clock)
        repeat(5) { assertTrue(bucket.tryTake(), "take $it should succeed") }
        assertFalse(bucket.tryTake(), "6th take should fail — bucket drained")
    }

    @Test
    fun `refill restores tokens proportionally to elapsed time`() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 10, refillPerSecond = 10, clock = clock)
        // Drain.
        repeat(10) { bucket.tryTake() }
        assertFalse(bucket.tryTake())

        // 100 ms → 1 token at 10/sec.
        clock.advanceMillis(100)
        assertTrue(bucket.tryTake(), "1 token should be available after 100 ms")
        assertFalse(bucket.tryTake(), "no second token yet")
    }

    @Test
    fun `refill never exceeds capacity`() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 3, refillPerSecond = 1000, clock = clock)
        repeat(3) { bucket.tryTake() }
        clock.advanceMillis(10_000) // 10s × 1000 = 10,000 tokens, but capacity = 3
        repeat(3) { assertTrue(bucket.tryTake(), "burst up to capacity") }
        assertFalse(bucket.tryTake(), "should cap at capacity despite huge elapsed time")
    }

    @Test
    fun `zero refill rate means strictly one burst then done`() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 2, refillPerSecond = 0, clock = clock)
        assertTrue(bucket.tryTake())
        assertTrue(bucket.tryTake())
        clock.advanceMillis(60_000)
        assertFalse(bucket.tryTake(), "no refill should ever happen")
    }

    @Test
    fun `fractional refill accumulates across small advances`() {
        val clock = FakeClock()
        val bucket = TokenBucket(capacity = 1, refillPerSecond = 10, clock = clock)
        bucket.tryTake() // drain
        assertFalse(bucket.tryTake())
        // Advance 50ms → 0.5 tokens; not enough yet.
        clock.advanceMillis(50)
        assertFalse(bucket.tryTake(), "0.5 tokens insufficient for a take")
        // Another 50ms → total 1.0 tokens.
        clock.advanceMillis(50)
        assertTrue(bucket.tryTake(), "accumulated fractional refill should yield a whole token")
    }

    @Test
    fun `rejects invalid construction parameters`() {
        assertFailsWith<IllegalArgumentException> { TokenBucket(capacity = 0, refillPerSecond = 10) }
        assertFailsWith<IllegalArgumentException> { TokenBucket(capacity = -1, refillPerSecond = 10) }
        assertFailsWith<IllegalArgumentException> { TokenBucket(capacity = 10, refillPerSecond = -1) }
    }
}
