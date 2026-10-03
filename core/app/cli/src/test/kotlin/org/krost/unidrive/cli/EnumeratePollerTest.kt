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

    // The status a client polls says when the poller tries again after a failure: the sleep it really takes
    // (with its jitter), recorded when it begins, and gone as soon as the next run starts.
    @Test
    fun `the poller reports when it tries again after a failure and clears it when the next run starts`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "provider boom"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val reported = mutableListOf<Long?>()
        val poller = EnumeratePoller(
            handler = handler,
            intervalMs = intervalMs,
            scope = scope,
            jitter = { it },
            backoffMultiplier = 3,
            clock = { testScheduler.currentTime },
            onNextAttempt = { reported += it },
        )
        poller.start()

        advanceTimeBy(stepOneInterval()) // the first run starts at 60 s and fails
        runCurrent()
        assertEquals(listOf(null, 240_000L), reported, "cleared at the start, then 60 s + the backed-off 180 s")

        // The retry starts at 240 s and fails too; the backoff escalates (180 s x 3 = 540 s,
        // not a flat 180 s again, #517 R3), so the reported next attempt is 240 s + 540 s.
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(listOf(null, 240_000L, null, 780_000L), reported)
        scope.cancel()
    }

    // #517 R3: a flat first step repeated forever re-ran a doomed enumerate every ~7 min
    // for hours on the live account. The injected jitter records every sleep before it
    // is taken, so the recorded values ARE the schedule.

    @Test
    fun `backoff escalates with consecutive failures to the cap`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val sleeps = mutableListOf<Long>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "provider boom"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller =
            EnumeratePoller(
                handler = handler,
                intervalMs = intervalMs,
                scope = scope,
                jitter = { base ->
                    sleeps.add(base)
                    base
                },
            )
        poller.start()

        // Ticks at 60s, 300s (60+240) and 900s (300+600): one flat step, then the cap.
        advanceTimeBy(900_001L)
        runCurrent()
        assertEquals(listOf(60_000L, 240_000L, 600_000L), sleeps.take(3), "240s once, then the cap — not 240s forever")
        scope.cancel()
    }

    @Test
    fun `the poller records the jittered sleep, not the nominal one`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "provider boom"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val reported = mutableListOf<Long?>()
        val poller = EnumeratePoller(
            handler = handler,
            intervalMs = intervalMs,
            scope = scope,
            jitter = { it + 7_000 },
            backoffMultiplier = 3,
            clock = { testScheduler.currentTime },
            onNextAttempt = { reported += it },
        )
        poller.start()

        advanceTimeBy(intervalMs + 7_001)
        runCurrent()

        assertEquals(listOf(null, (intervalMs + 7_000) + (3 * intervalMs + 7_000)), reported)
        scope.cancel()
    }

    @Test
    fun `backoff starts escalated from the prior run's failure streak`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val sleeps = mutableListOf<Long>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "provider boom"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller =
            EnumeratePoller(
                handler = handler,
                intervalMs = intervalMs,
                scope = scope,
                jitter = { base ->
                    sleeps.add(base)
                    base
                },
                consecutiveFailuresAtStart = 2,
            )
        poller.start()

        // Seeded 60k → 240k → 600k cap; the first tick waits 600s, failures keep it there.
        advanceTimeBy(1_200_001L)
        runCurrent()
        assertEquals(2, engine.enumerateCount.get())
        assertEquals(listOf(600_000L, 600_000L), sleeps.take(2), "a streak of 2 starts the schedule at the cap")
        scope.cancel()
    }

    @Test
    fun `a tick that throws also reports its next attempt`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val engine = RecordingEngine(enumerateFailure = IllegalStateException("db is gone"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val reported = mutableListOf<Long?>()
        val poller = EnumeratePoller(
            handler = handler,
            intervalMs = intervalMs,
            scope = scope,
            jitter = { it },
            backoffMultiplier = 3,
            clock = { testScheduler.currentTime },
            onNextAttempt = { reported += it },
        )
        poller.start()

        advanceTimeBy(stepOneInterval())
        runCurrent()

        assertEquals(listOf(null, 240_000L), reported)
        scope.cancel()
    }

    @Test
    fun `a successful run schedules no next attempt`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val reported = mutableListOf<Long?>()
        val poller = EnumeratePoller(
            handler = handler,
            intervalMs = intervalMs,
            scope = scope,
            jitter = { it },
            clock = { testScheduler.currentTime },
            onNextAttempt = { reported += it },
        )
        poller.start()

        advanceTimeBy(stepOneInterval())
        runCurrent()
        advanceTimeBy(intervalMs)
        runCurrent()

        assertEquals(listOf<Long?>(null, null), reported, "each run start clears; a success leaves nothing scheduled")
        scope.cancel()
    }

    @Test
    fun `a success returns an escalated schedule to the plain interval`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val sleeps = mutableListOf<Long>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller =
            EnumeratePoller(
                handler = handler,
                intervalMs = intervalMs,
                scope = scope,
                jitter = { base ->
                    sleeps.add(base)
                    base
                },
                consecutiveFailuresAtStart = 2,
            )
        poller.start()

        // First tick at the seeded 600s succeeds; the next sleep is the plain interval.
        advanceTimeBy(600_000L + 60_000L + 1)
        runCurrent()
        assertEquals(2, engine.enumerateCount.get())
        assertEquals(listOf(600_000L, 60_000L), sleeps.take(2), "recovery drops the escalation")
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
