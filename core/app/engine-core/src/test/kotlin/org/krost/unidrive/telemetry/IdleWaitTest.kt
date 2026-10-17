package org.krost.unidrive.telemetry

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class IdleWaitTest {
    @Test
    fun `an idle writer waits until the nearest thing falls due, rounded up to a millisecond`() {
        assertEquals(600_000L, idleWaitMs(TimeUnit.MINUTES.toNanos(10)))
        assertEquals(3_000L, idleWaitMs(TimeUnit.SECONDS.toNanos(3)))
        assertEquals(4L, idleWaitMs(3_000_001L), "never short of the due time")
    }

    @Test
    fun `a due or overdue thing is not waited for`() {
        assertEquals(1L, idleWaitMs(0))
        assertEquals(1L, idleWaitMs(-5_000_000_000L))
        assertEquals(1L, idleWaitMs(1), "a timed poll of 0 would block for ever")
    }
}
