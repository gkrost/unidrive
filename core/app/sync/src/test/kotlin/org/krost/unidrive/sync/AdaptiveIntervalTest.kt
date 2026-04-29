package org.krost.unidrive.sync

import kotlin.test.*

class AdaptiveIntervalTest {
    @Test
    fun `ACTIVE state for idleCycles 0-2`() {
        assertEquals(10, computePollInterval(idleCycles = 0, min = 10, normal = 60, max = 300))
        assertEquals(10, computePollInterval(idleCycles = 1, min = 10, normal = 60, max = 300))
        assertEquals(10, computePollInterval(idleCycles = 2, min = 10, normal = 60, max = 300))
    }

    @Test
    fun `NORMAL state for idleCycles 3-7`() {
        assertEquals(60, computePollInterval(idleCycles = 3, min = 10, normal = 60, max = 300))
        assertEquals(60, computePollInterval(idleCycles = 7, min = 10, normal = 60, max = 300))
    }

    @Test
    fun `IDLE state for idleCycles 8+`() {
        assertEquals(300, computePollInterval(idleCycles = 8, min = 10, normal = 60, max = 300))
        assertEquals(300, computePollInterval(idleCycles = 100, min = 10, normal = 60, max = 300))
    }

    @Test
    fun `pollStateName returns correct label`() {
        assertEquals("ACTIVE", pollStateName(idleCycles = 0))
        assertEquals("NORMAL", pollStateName(idleCycles = 5))
        assertEquals("IDLE", pollStateName(idleCycles = 10))
    }

    @Test
    fun `negative idleCycles treated as ACTIVE`() {
        assertEquals(10, computePollInterval(idleCycles = -1, min = 10, normal = 60, max = 300))
    }

    @Test
    fun `very large idleCycles stays IDLE`() {
        assertEquals(300, computePollInterval(idleCycles = Int.MAX_VALUE, min = 10, normal = 60, max = 300))
    }

    @Test
    fun `pollStateName boundary at 3`() {
        assertEquals("ACTIVE", pollStateName(idleCycles = 2))
        assertEquals("NORMAL", pollStateName(idleCycles = 3))
    }

    @Test
    fun `pollStateName boundary at 8`() {
        assertEquals("NORMAL", pollStateName(idleCycles = 7))
        assertEquals("IDLE", pollStateName(idleCycles = 8))
    }
}
