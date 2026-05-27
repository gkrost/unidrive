package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.EnumerateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumeratePollerTest {
    // advanceTimeBy runs tasks in [now, now+delta); a delay at exactly `intervalMs`
    // needs +1ms to fire. Step past one interval deterministically (jitter is
    // injected as identity so the sleep is exactly intervalMs).
    private val intervalMs = 60_000L
    private fun stepOneInterval(): Long = intervalMs + 1

    @Test
    fun `poll loop fires enumerate once per interval`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller = EnumeratePoller(handler = handler, intervalMs = intervalMs, scope = scope, jitter = { it })
        poller.start()

        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "one tick after the first interval")

        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(2, engine.enumerateCount.get(), "a second tick after the second interval")
        scope.cancel()
    }

    @Test
    fun `tick during an in-flight enumerate does not double-run`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true), gate = gate)
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller = EnumeratePoller(handler = handler, intervalMs = intervalMs, scope = scope, jitter = { it })

        // A manual sync.enumerate occupies the shared in-flight guard (gated open).
        val reply = handler.handle("conn-manual", """{"verb":"sync.enumerate"}""")
        assertTrue(reply.contains("\"ok\":true"), reply)
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "manual enumerate is in flight")

        poller.start()
        advanceTimeBy(stepOneInterval()) // poll tick fires while manual enumerate still in flight
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "a poll tick must NOT double-run while an enumerate is in flight (shared guard)")

        gate.complete(Unit) // release the manual enumerate
        runCurrent()

        // Next interval after the guard is free → the poll tick runs.
        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(2, engine.enumerateCount.get(), "after the guard frees, the next interval's tick runs")
        scope.cancel()
    }

    @Test
    fun `poll backs off after a failed enumeration`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "provider boom"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller = EnumeratePoller(
            handler = handler,
            intervalMs = intervalMs,
            scope = scope,
            jitter = { it },
            backoffMultiplier = 3,
        )
        poller.start()

        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "first tick fires at the base interval")

        // After ok=false the next sleep is base × backoffMultiplier = 180s.
        // At +1 more interval (total ~120s) the tick must NOT have fired yet.
        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "after a failed enumeration the next interval is extended (backoff)")

        advanceTimeBy(2 * intervalMs) // reach the 180s backed-off interval
        runCurrent()
        assertEquals(2, engine.enumerateCount.get(), "the backed-off interval eventually fires")
        scope.cancel()
    }

    @Test
    fun `parseIntervalMs accepts bare seconds suffixed units and zero`() {
        assertEquals(0L, EnumeratePoller.parseIntervalMs("0"))
        assertEquals(0L, EnumeratePoller.parseIntervalMs("0s"))
        assertEquals(60_000L, EnumeratePoller.parseIntervalMs("60"))
        assertEquals(60_000L, EnumeratePoller.parseIntervalMs("60s"))
        assertEquals(30_000L, EnumeratePoller.parseIntervalMs("30s"))
        assertEquals(300_000L, EnumeratePoller.parseIntervalMs("5m"))
        assertEquals(3_600_000L, EnumeratePoller.parseIntervalMs("1h"))
        assertEquals(500L, EnumeratePoller.parseIntervalMs("500ms"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { EnumeratePoller.parseIntervalMs("nope") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { EnumeratePoller.parseIntervalMs("60x") }
    }

    @Test
    fun `interval 0 starts no loop`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller = EnumeratePoller(handler = handler, intervalMs = 0, scope = scope, jitter = { it })
        poller.start()

        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(0, engine.enumerateCount.get(), "--poll-interval 0 → no poll loop")
        scope.cancel()
    }
}
