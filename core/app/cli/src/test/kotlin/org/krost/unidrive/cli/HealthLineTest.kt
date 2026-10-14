package org.krost.unidrive.cli

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #891: the daemon's health line fires exactly once per interval on the serve scope's virtual time, logs
 * nothing at start, and stops with the scope; the rendered line carries the daemon's numbers in key=value
 * form, grep-able by scripts/dev/log-watch.sh.
 */
class HealthLineTest {
    @Test
    fun `one health line per interval and none after shutdown`() =
        runTest {
            val lines = mutableListOf<String>()
            val job: Job =
                DaemonHealthLine(
                    intervalMs = 1_000,
                    scope = backgroundScope,
                    enumeration = { "running" to 5 },
                    uploadQueueDepth = { 2 },
                    logLine = { lines += it },
                ).start()
            assertEquals(0, lines.size, "nothing is logged at start")
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, lines.size, "one line per interval")
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, lines.size, "one line per interval")
            job.cancelAndJoin()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, lines.size, "nothing after shutdown")
        }

    @Test
    fun `the line carries the heap, gc, threads, enumeration and upload queue in key=value form`() =
        runTest {
            val scope = kotlinx.coroutines.CoroutineScope(Job())
            val rendered = DaemonHealthLine(intervalMs = 60_000, scope = scope).render()
        for (key in listOf("heap_used_mib=", "heap_committed_mib=", "heap_max_mib=", "gc_count=", "gc_time_ms=", "threads=")) {
            assertTrue(key in rendered, "the JVM fields are always present: $rendered")
        }
        assertTrue("enumeration=" !in rendered && "items=" !in rendered && "upload_queue=" !in rendered, "absent suppliers leave their fields out: $rendered")

        val full =
            DaemonHealthLine(
                intervalMs = 60_000,
                scope = scope,
                enumeration = { "running" to 141_000 },
                uploadQueueDepth = { 3 },
            ).render()
        assertTrue(" enumeration=running" in full && " items=141000" in full, full)
        assertTrue(" upload_queue=3" in full, full)
        assertTrue(rendered.startsWith("health "), "the line is named and key=value: $rendered")
        }
}
