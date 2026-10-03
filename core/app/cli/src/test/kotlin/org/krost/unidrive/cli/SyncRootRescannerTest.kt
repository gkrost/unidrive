package org.krost.unidrive.cli

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #504: schedule of the daemon's sync root rescan: a pass at start, then one per interval; 0 = off. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRootRescannerTest {
    @Test
    fun `runs one pass at start and then one per interval`() =
        runTest {
            var passes = 0
            val job = launch { SyncRootRescanner(1_000) { passes++ }.run() }
            runCurrent()
            assertEquals(1, passes, "the start pass runs at once")
            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, passes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, passes)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(4, passes)
            job.cancel()
        }

    @Test
    fun `an interval of zero disables the rescan entirely, the start pass included`() =
        runTest {
            var passes = 0
            launch { SyncRootRescanner(0) { passes++ }.run() }
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(0, passes)
        }

    @Test
    fun `a failing pass does not end the loop`() =
        runTest {
            var passes = 0
            val job =
                launch {
                    SyncRootRescanner(1_000) {
                        passes++
                        if (passes == 1) error("offline")
                    }.run()
                }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, passes)
            job.cancel()
        }
}
