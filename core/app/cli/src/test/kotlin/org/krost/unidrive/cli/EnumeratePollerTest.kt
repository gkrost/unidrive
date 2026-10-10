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
        val poller = EnumeratePoller(handler = handler, intervalMs = intervalMs, scope = scope, jitter = { it }, firstPollDelayMs = intervalMs)
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
        val poller = EnumeratePoller(handler = handler, intervalMs = intervalMs, scope = scope, jitter = { it }, firstPollDelayMs = intervalMs)

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
            firstPollDelayMs = intervalMs,
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
            firstPollDelayMs = intervalMs,
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
                firstPollDelayMs = intervalMs,
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
            firstPollDelayMs = intervalMs,
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
            firstPollDelayMs = intervalMs,
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
            firstPollDelayMs = intervalMs,
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

    // ---- #463: discovery for a daemon that serves a mount ---------------------------------------------------------

    // A loop left running after a failed assertion makes runTest advance its virtual clock forever: the build would
    // hang instead of failing. These tests cancel the loop in a finally.
    private fun pollTest(body: suspend kotlinx.coroutines.test.TestScope.(CoroutineScope) -> Unit) =
        runTest {
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            try {
                body(scope)
            } finally {
                scope.cancel()
            }
        }

    // A daemon that starts (a boot, a restart after an outage, a redeploy) catches up with what changed in the
    // cloud while it was down at once, not one interval later.
    @Test
    fun `the first poll runs promptly after start`() = pollTest { scope ->
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller = EnumeratePoller(handler = handler, intervalMs = intervalMs, scope = scope, jitter = { it })
        poller.start()

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "the catch-up poll runs at start")

        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(2, engine.enumerateCount.get(), "then one per interval")
    }

    // A refresh (the legacy reconcile or the mount's enumerate) works on the same state database; a poll that
    // starts while one runs is skipped, like one that meets an enumerate holding the guard.
    @Test
    fun `no poll starts while a refresh is in flight`() = pollTest { scope ->
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val refreshRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val poller =
            EnumeratePoller(
                handler = handler,
                intervalMs = intervalMs,
                scope = scope,
                jitter = { it },
                isBusy = { refreshRunning.get() },
            )
        poller.start()

        advanceTimeBy(1)
        runCurrent()
        assertEquals(0, engine.enumerateCount.get(), "the poll at start meets the refresh and is skipped")

        refreshRunning.set(false)
        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "the next interval's poll runs once the refresh is done")
    }

    // Polls never overlap each other: a poll that takes longer than the interval delays the next one.
    @Test
    fun `a slow poll is never overlapped by the next one`() = pollTest { scope ->
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true), gate = gate)
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller = EnumeratePoller(handler = handler, intervalMs = intervalMs, scope = scope, jitter = { it })
        poller.start()

        advanceTimeBy(5 * intervalMs)
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "the first poll is still running five intervals later; no second one")

        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(2, engine.enumerateCount.get(), "the next poll follows one interval after the slow one ended")
    }

    // After a failure the poller backs off. A sign that the provider answers again (a download or upload through
    // the daemon succeeded) ends the backoff: the next poll runs at the plain cadence, not up to ten minutes later.
    @Test
    fun `a sign that the provider is reachable again cuts a backoff short`() = pollTest { scope ->
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "provider boom"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val reported = mutableListOf<Long?>()
        val poller =
            EnumeratePoller(
                handler = handler,
                intervalMs = intervalMs,
                scope = scope,
                jitter = { it },
                clock = { testScheduler.currentTime },
                onNextAttempt = { reported += it },
            )
        poller.start()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "the poll at start fails; the next is 240 s away")

        engine.enumerateResult = EnumerateResult(ok = true)
        advanceTimeBy(100_000)
        poller.providerReachable()
        runCurrent()
        assertEquals(2, engine.enumerateCount.get(), "100 s after the failure, more than one interval: the poll runs now")
        assertEquals(listOf(null, 240_000L, null), reported, "the backed-off attempt was reported, then cleared by the run")

        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(3, engine.enumerateCount.get(), "and the cadence is the plain interval again")
    }

    @Test
    fun `a reachable sign right after a failure waits for the plain interval, not for the backoff`() = pollTest { scope ->
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "provider boom"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val reported = mutableListOf<Long?>()
        val poller =
            EnumeratePoller(
                handler = handler,
                intervalMs = intervalMs,
                scope = scope,
                jitter = { it },
                clock = { testScheduler.currentTime },
                onNextAttempt = { reported += it },
            )
        poller.start()
        advanceTimeBy(1)
        runCurrent()

        advanceTimeBy(10_000)
        poller.providerReachable()
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "10 s after a failed poll is too early for the next one")
        assertEquals(listOf(null, 240_000L, 60_000L), reported, "the next attempt moved from the backoff to the plain interval")

        advanceTimeBy(50_001)
        runCurrent()
        assertEquals(2, engine.enumerateCount.get(), "one plain interval after the failure, the poll runs")
    }

    @Test
    fun `a reachable sign outside a backoff changes nothing`() = pollTest { scope ->
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        val poller =
            EnumeratePoller(
                handler = handler,
                intervalMs = intervalMs,
                scope = scope,
                jitter = { it },
                clock = { testScheduler.currentTime },
            )
        poller.start()
        advanceTimeBy(1)
        runCurrent()

        repeat(5) {
            advanceTimeBy(5_000)
            poller.providerReachable()
            runCurrent()
        }
        assertEquals(1, engine.enumerateCount.get(), "every download is such a sign; none of them adds a poll")

        advanceTimeBy(intervalMs - 25_000 + 1)
        runCurrent()
        assertEquals(2, engine.enumerateCount.get(), "the regular poll comes on time")
    }

    @Test
    fun `the daemon polls by default and the command line overrides the profile`() {
        assertEquals(60_000L, EnumeratePoller.effectiveIntervalMs(cliValue = null, configSeconds = 60), "the default profile value")
        assertEquals(300_000L, EnumeratePoller.effectiveIntervalMs(cliValue = null, configSeconds = 300))
        assertEquals(0L, EnumeratePoller.effectiveIntervalMs(cliValue = null, configSeconds = 0), "0 in the profile turns it off")
        assertEquals(0L, EnumeratePoller.effectiveIntervalMs(cliValue = "0", configSeconds = 60), "--poll-interval 0 turns it off")
        assertEquals(30_000L, EnumeratePoller.effectiveIntervalMs(cliValue = "30s", configSeconds = 60))
        // The option has no default of its own: absent, the profile decides.
        val run = DaemonRunCommand()
        picocli.CommandLine(run).parseArgs()
        assertEquals(null, run.pollInterval)
        picocli.CommandLine(run).parseArgs("--poll-interval", "5m")
        assertEquals("5m", run.pollInterval)
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

    // #658: the last time something proved that the provider answers; a failed enumeration does not move it.
    @Test
    fun `providerReachable stamps the last contact and a later failure leaves it`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "provider boom"))
        val handler = EnumerateRpcHandler(engine, scope, emit = {})
        var now = 5_000L
        val poller = EnumeratePoller(handler = handler, intervalMs = intervalMs, scope = scope, jitter = { it }, clock = { now }, firstPollDelayMs = intervalMs)
        assertEquals(null, poller.lastReachableAtMs, "nothing proved yet: unknown")

        poller.providerReachable()
        assertEquals(5_000L, poller.lastReachableAtMs)

        poller.start()
        now = 9_000L
        advanceTimeBy(stepOneInterval())
        runCurrent()
        assertEquals(1, engine.enumerateCount.get(), "the outage is being polled")
        assertEquals(5_000L, poller.lastReachableAtMs, "a failure is not a contact")
        scope.cancel()
    }

    @Test
    fun `the last provider contact is the later of the enumeration and the transfer, and unknown only when both are`() {
        assertEquals(null, lastProviderContactMs(null, null))
        assertEquals(10L, lastProviderContactMs(10L, null))
        assertEquals(20L, lastProviderContactMs(null, 20L))
        assertEquals(30L, lastProviderContactMs(30L, 20L))
        assertEquals(30L, lastProviderContactMs(10L, 30L))
    }
}
