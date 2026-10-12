package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #778: the pause state and the in-flight rule. A transfer whose estimated time remaining is under ten
 * seconds is left to finish; any other is aborted (and its owner re-queues it).
 */
class PauseGateTest {
    private class Clocks {
        var ms = 1_000L
        var ns = 0L

        fun gate() = PauseGate(clockMs = { ms }, nanoTime = { ns })
    }

    private val seconds = 1_000_000_000L

    @Test
    fun `a fresh gate is running`() {
        val gate = PauseGate()

        assertFalse(gate.isPaused)
        assertNull(gate.current)
    }

    @Test
    fun `pause records the reason, the time it began and whether it was automatic`() {
        val clocks = Clocks()
        val gate = clocks.gate()

        assertEquals(PauseGate.Change.STARTED, gate.pause("metered", auto = true))

        assertTrue(gate.isPaused)
        assertEquals(PauseGate.State(reason = "metered", sinceMs = 1_000L, auto = true), gate.current)
    }

    @Test
    fun `pause while paused keeps the first since and takes the new reason and auto`() {
        val clocks = Clocks()
        val gate = clocks.gate()
        gate.pause("user", auto = false)
        clocks.ms = 9_000L

        assertEquals(PauseGate.Change.UPDATED, gate.pause("metered", auto = true))

        assertEquals(PauseGate.State(reason = "metered", sinceMs = 1_000L, auto = true), gate.current)
    }

    @Test
    fun `pause with the state it already has changes nothing`() {
        val clocks = Clocks()
        val gate = clocks.gate()
        gate.pause("user", auto = false)
        clocks.ms = 9_000L

        assertEquals(PauseGate.Change.UNCHANGED, gate.pause("user", auto = false))

        assertEquals(PauseGate.State(reason = "user", sinceMs = 1_000L, auto = false), gate.current)
    }

    @Test
    fun `resume clears the state and reports that it did, a second resume is a no-op`() {
        val gate = PauseGate()
        gate.pause("user", auto = false)

        assertTrue(gate.resume())
        assertFalse(gate.isPaused)
        assertNull(gate.current)
        assertFalse(gate.resume(), "resume while running changes nothing")
    }

    @Test
    fun `a pause after a resume starts a new since`() {
        val clocks = Clocks()
        val gate = clocks.gate()
        gate.pause("user", auto = false)
        gate.resume()
        clocks.ms = 5_000L

        assertEquals(PauseGate.Change.STARTED, gate.pause("user", auto = false))

        assertEquals(5_000L, gate.current?.sinceMs)
    }

    @Test
    fun `awaitResumed returns at once when running and waits while paused`() = runBlocking {
        val gate = PauseGate()
        gate.awaitResumed()

        gate.pause("user", auto = false)
        val waiter = async { gate.awaitResumed() }
        kotlinx.coroutines.delay(50)
        assertFalse(waiter.isCompleted, "a paused gate holds its waiters")

        gate.resume()
        withTimeout(5_000) { waiter.await() }
    }

    // ── the in-flight rule ──────────────────────────────────────────────────────────────────────

    /** Runs a tracked transfer that reports [done] of [total] after [elapsedNs] and then waits to be released. */
    private suspend fun kotlinx.coroutines.CoroutineScope.runningTransfer(
        clocks: Clocks,
        gate: PauseGate,
        elapsedNs: Long,
        done: Long,
        total: Long,
        release: CompletableDeferred<Unit>,
    ): Pair<CompletableDeferred<Unit>, kotlinx.coroutines.Deferred<Result<String>>> {
        val started = CompletableDeferred<Unit>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                gate.track("/a") { transfer ->
                    transfer.begin()
                    clocks.ns = elapsedNs
                    transfer.report(done, total)
                    started.complete(Unit)
                    release.await()
                    "finished"
                }
            }
        }
        started.await()
        return started to result
    }

    @Test
    fun `a transfer that needs less than ten seconds is left to finish`() = runBlocking {
        val clocks = Clocks()
        val gate = clocks.gate()
        val release = CompletableDeferred<Unit>()
        // 501 of 1000 bytes in 10 s: 499 bytes left at 50.1 B/s is 9.96 s.
        val (_, result) = runningTransfer(clocks, gate, 10 * seconds, 501, 1_000, release)

        gate.pause("metered", auto = true)
        assertEquals(0, gate.abortSlowTransfers())
        release.complete(Unit)

        assertEquals("finished", result.await().getOrThrow())
    }

    @Test
    fun `a transfer that needs ten seconds or more is aborted`() = runBlocking {
        val clocks = Clocks()
        val gate = clocks.gate()
        val release = CompletableDeferred<Unit>()
        // 500 of 1000 bytes in 10 s: 500 bytes left at 50 B/s is exactly 10 s, which is not under ten.
        val (_, result) = runningTransfer(clocks, gate, 10 * seconds, 500, 1_000, release)

        gate.pause("metered", auto = true)
        assertEquals(1, gate.abortSlowTransfers())

        assertFailsWith<TransferPausedException> { result.await().getOrThrow() }
        assertEquals(0, gate.inFlightCount(), "an aborted transfer is no longer tracked")
    }

    @Test
    fun `a transfer with no progress reading has no estimate and is aborted`(): Unit = runBlocking {
        val clocks = Clocks()
        val gate = clocks.gate()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val result = async {
            runCatching { gate.track("/a") { _ -> started.complete(Unit); release.await() } }
        }
        started.await()

        gate.pause("user", auto = false)
        assertEquals(1, gate.abortSlowTransfers())

        assertFailsWith<TransferPausedException> { result.await().getOrThrow() }
    }

    @Test
    fun `a transfer measured by a probe is judged on the probe's bytes`() = runBlocking {
        val clocks = Clocks()
        val gate = clocks.gate()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                gate.track("/a") { transfer ->
                    transfer.begin()
                    clocks.ns = 10 * seconds
                    transfer.measuredBy(total = 1_000) { 999L }
                    started.complete(Unit)
                    release.await()
                    "finished"
                }
            }
        }
        started.await()

        gate.pause("user", auto = false)
        assertEquals(0, gate.abortSlowTransfers())
        release.complete(Unit)

        assertEquals("finished", result.await().getOrThrow())
    }

    @Test
    fun `the rule takes the threshold as an argument`(): Unit = runBlocking {
        val clocks = Clocks()
        val gate = clocks.gate()
        val release = CompletableDeferred<Unit>()
        // 9.96 s left: under the default ten seconds, not under two.
        val (_, result) = runningTransfer(clocks, gate, 10 * seconds, 501, 1_000, release)

        gate.pause("user", auto = false)
        assertEquals(1, gate.abortSlowTransfers(finishUnderMs = 2_000))

        assertFailsWith<TransferPausedException> { result.await().getOrThrow() }
    }

    @Test
    fun `a transfer that starts while paused is refused before its body runs`() = runBlocking {
        val gate = PauseGate()
        gate.pause("user", auto = false)
        var ran = false

        assertFailsWith<TransferPausedException> { gate.track("/a") { _ -> ran = true } }

        assertFalse(ran)
        assertEquals(0, gate.inFlightCount())
    }

    @Test
    fun `a cancellation that did not come from a pause stays a cancellation`() = runBlocking {
        val gate = PauseGate()
        val started = CompletableDeferred<Unit>()
        var sawPause = false
        val job = launch {
            try {
                gate.track("/a") { _ ->
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (e: TransferPausedException) {
                sawPause = true
                throw e
            }
        }
        started.await()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(sawPause, "the user's cancel is not a pause")
        assertEquals(0, gate.inFlightCount())
    }

    @Test
    fun `a failing transfer rethrows its own failure and is no longer tracked`() = runBlocking {
        val gate = PauseGate()

        val e = assertFailsWith<IllegalStateException> { gate.track("/a") { _ -> error("provider boom") } }

        assertEquals("provider boom", e.message)
        assertEquals(0, gate.inFlightCount())
    }

    @Test
    fun `a transfer that finishes is no longer tracked and returns its value`() = runBlocking {
        val gate = PauseGate()

        assertEquals(7, gate.track("/a") { _ -> 7 })

        assertEquals(0, gate.inFlightCount())
        assertEquals(0, gate.abortSlowTransfers())
    }
}
