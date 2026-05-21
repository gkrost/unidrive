package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanHeartbeatTest {
    /**
     * UD-352: helper that lets a test drive `clock()` deterministically.
     * Mirrors the LocalScanner heartbeat test pattern but without the disk walk.
     */
    private class FakeClock(
        initialMs: Long = 0L,
    ) {
        var nowMs: Long = initialMs

        fun read(): Long = nowMs

        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    @Test
    fun `UD-352 fires when item-count threshold crossed`() {
        val clock = FakeClock()
        val fires = mutableListOf<Int>()
        val hb =
            ScanHeartbeat(
                onFire = { fires += it },
                intervalItems = 5_000,
                intervalMs = 10_000L,
                clock = clock::read,
            )
        // Below threshold — no fire.
        hb.tick(100)
        hb.tick(4_999)
        assertTrue(fires.isEmpty(), "no fire below 5k items, got: $fires")
        // Cross the threshold — fire.
        hb.tick(5_000)
        assertEquals(listOf(5_000), fires)
    }

    @Test
    fun `UD-352 fires when wall-clock threshold elapsed`() {
        val clock = FakeClock()
        val fires = mutableListOf<Int>()
        val hb =
            ScanHeartbeat(
                onFire = { fires += it },
                intervalItems = 5_000,
                intervalMs = 10_000L,
                clock = clock::read,
            )
        hb.tick(1)
        // Advance below the time threshold — no fire even though count grew.
        clock.advance(9_999L)
        hb.tick(50)
        assertTrue(fires.isEmpty(), "no fire below 10s elapsed, got: $fires")
        // Cross the wall-clock threshold — fire.
        clock.advance(2L)
        hb.tick(50)
        assertEquals(listOf(50), fires)
    }

    @Test
    fun `UD-352 does not fire when neither threshold crossed`() {
        val clock = FakeClock()
        val fires = mutableListOf<Int>()
        val hb =
            ScanHeartbeat(
                onFire = { fires += it },
                intervalItems = 5_000,
                intervalMs = 10_000L,
                clock = clock::read,
            )
        // Many ticks, all below both thresholds — never fire.
        for (i in 1..100) {
            clock.advance(50L)
            hb.tick(i * 10) // up to 1000 items, 5s elapsed → both below thresholds
        }
        assertTrue(fires.isEmpty(), "no fire when neither threshold met, got: $fires")
    }

    @Test
    fun `UD-352 both thresholds met simultaneously yields a single fire`() {
        val clock = FakeClock()
        val fires = mutableListOf<Int>()
        val hb =
            ScanHeartbeat(
                onFire = { fires += it },
                intervalItems = 5_000,
                intervalMs = 10_000L,
                clock = clock::read,
            )
        // Same tick: both 5k items AND 10s elapsed → still exactly one fire.
        clock.advance(10_000L)
        hb.tick(5_000)
        assertEquals(listOf(5_000), fires)
    }

    @Test
    fun `UD-352 thresholds reset after each fire`() {
        val clock = FakeClock()
        val fires = mutableListOf<Int>()
        val hb =
            ScanHeartbeat(
                onFire = { fires += it },
                intervalItems = 5_000,
                intervalMs = 10_000L,
                clock = clock::read,
            )
        // First fire by item-count.
        hb.tick(5_000)
        assertEquals(listOf(5_000), fires)
        // Need another 5k items past the LAST FIRE to fire again — at 9_999 we shouldn't.
        hb.tick(9_999)
        assertEquals(listOf(5_000), fires, "second fire only after another 5k items past last fire, got: $fires")
        // Cross again.
        hb.tick(10_000)
        assertEquals(listOf(5_000, 10_000), fires)
    }

    @Test
    fun `UD-352 wall-clock counter resets after each fire`() {
        val clock = FakeClock()
        val fires = mutableListOf<Int>()
        val hb =
            ScanHeartbeat(
                onFire = { fires += it },
                intervalItems = 5_000,
                intervalMs = 10_000L,
                clock = clock::read,
            )
        // First fire by wall-clock.
        clock.advance(10_000L)
        hb.tick(1)
        assertEquals(listOf(1), fires)
        // Advance only 5s after the fire; should NOT fire even though the
        // total elapsed since construction is now 15s.
        clock.advance(5_000L)
        hb.tick(2)
        assertEquals(listOf(1), fires, "second fire only after another 10s past last fire, got: $fires")
        // Total 10s past last fire — fires.
        clock.advance(5_000L)
        hb.tick(3)
        assertEquals(listOf(1, 3), fires)
    }
}
