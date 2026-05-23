package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the per-subscriber NDJSON push pipeline in HydrationIpcHandler.
 *
 * Each test wires the handler against a fake writer that records lines per
 * connectionId; the writer can be gated to simulate a slow subscriber. Events
 * are pushed via dispatchEvent() — the same entry point the daemon calls from
 * its single hydration.events collector — so these tests exercise the production
 * code path end-to-end except for the UDS socket itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationSubscribeTest {

    /** Captures lines written per connectionId, with optional per-connection gate. */
    private class FakeWriter {
        val lines: ConcurrentHashMap<String, MutableList<String>> = ConcurrentHashMap()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var rejectAll: Boolean = false

        suspend fun write(connectionId: String, line: String): Boolean {
            if (rejectAll) return false
            gate?.await()
            lines.computeIfAbsent(connectionId) { java.util.Collections.synchronizedList(mutableListOf()) }
                .add(line)
            return true
        }

        fun linesFor(connectionId: String): List<String> =
            lines[connectionId]?.toList() ?: emptyList()
    }

    private fun newHandler(): HydrationIpcHandler {
        // We pass a no-op Hydration because these tests exercise the event-push
        // pipeline only; no Hydration verbs are invoked. A real HydrationImpl is
        // covered separately by HydrationImplTest.
        val noop = object : Hydration {
            override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult =
                OpenResult.Failed(HydrationError.Generic("unused"))
            override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: java.nio.file.Path): OpenResult =
                OpenResult.Failed(HydrationError.Generic("unused"))
            override suspend fun closeHandle(connectionId: String, handleId: String) {}
            override suspend fun hydrate(path: String): HydrateResult = HydrateResult.Failed(HydrationError.Generic("unused"))
            override suspend fun dehydrate(path: String): DehydrateResult = DehydrateResult.Failed(HydrationError.Generic("unused"))
            override suspend fun lastSynced(path: String): LastSyncedResult = LastSyncedResult.Unknown("unused")
            override val events = kotlinx.coroutines.flow.MutableSharedFlow<HydrationEvent>()
            override fun onConnectionClosed(connectionId: String) {}
        }
        return HydrationIpcHandler(noop)
    }

    @Test
    fun `single subscriber receives serialised NDJSON for each dispatched event in order`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }

        val reply = handler.handle("conn1", """{"verb":"hydration.subscribe"}""")
        assertEquals("""{"ok":true}""", reply.trim())

        handler.dispatchEvent(HydrationEvent.Hydrating("/foo.txt"))
        handler.dispatchEvent(HydrationEvent.Hydrated("/foo.txt", bytes = 42))
        advanceUntilIdle()

        val out = writer.linesFor("conn1")
        assertEquals(2, out.size)
        assertEquals("""{"event":"hydrating","path":"/foo.txt"}""", out[0])
        assertEquals("""{"event":"hydrated","path":"/foo.txt","bytes":42}""", out[1])

        handler.onSubscriberDisconnect("conn1")
    }

    @Test
    fun `two subscribers each receive every dispatched event`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }

        handler.handle("conn1", """{"verb":"hydration.subscribe"}""")
        handler.handle("conn2", """{"verb":"hydration.subscribe"}""")

        handler.dispatchEvent(HydrationEvent.Dehydrated("/bar.txt"))
        advanceUntilIdle()

        val expected = """{"event":"dehydrated","path":"/bar.txt"}"""
        assertEquals(listOf(expected), writer.linesFor("conn1"))
        assertEquals(listOf(expected), writer.linesFor("conn2"))

        handler.onSubscriberDisconnect("conn1")
        handler.onSubscriberDisconnect("conn2")
    }

    @Test
    fun `slow subscriber drops oldest events and emits one lost sentinel before the next deliverable line`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        val gate = CompletableDeferred<Unit>()
        writer.gate = gate
        handler.start(this) { id, line -> writer.write(id, line) }

        handler.handle("conn1", """{"verb":"hydration.subscribe"}""")
        // Step the writer onto the gate: dispatch ONE event, advance, so the
        // writer pulls it and suspends inside write(). Now the queue is empty
        // and the writer is stuck — every following dispatch fills the queue
        // until it overflows.
        handler.dispatchEvent(HydrationEvent.Hydrating("/p0"))
        advanceUntilIdle()

        val capacity = HydrationIpcHandler.SUBSCRIBER_QUEUE_CAPACITY
        // Fire `capacity + 35` more events: the first `capacity` (p1..p64) fill
        // the queue, the next 35 (p65..p99) trigger drop-oldest. Total dispatched
        // = 100. Net writer output = e0 + sentinel(since_last=35) + the last
        // `capacity` events that survived (p35..p99).
        val overflow = 35
        val totalAfterP0 = capacity + overflow
        for (i in 1..totalAfterP0) {
            handler.dispatchEvent(HydrationEvent.Hydrating("/p$i"))
        }
        // State at this point: writer is suspended mid-write on p0; queue holds
        // the most recent `capacity` events; dropped == overflow. Release the
        // gate so the writer drains: finishes p0, then sees dropped > 0 and
        // emits the sentinel, then writes the surviving events in order.
        gate.complete(Unit)
        advanceUntilIdle()

        val out = writer.linesFor("conn1")
        // Line 0: p0 (the first event the writer was suspended-on-gate for).
        // Line 1: sentinel emitted before the next deliverable, since_last == dropped count.
        // Lines 2+: surviving events — the LAST `capacity` of the queue-filling batch.
        assertEquals("""{"event":"hydrating","path":"/p0"}""", out[0])
        assertEquals("""{"event":"lost","since_last":$overflow}""", out[1])
        // Surviving range: p[overflow+1] .. p[totalAfterP0] inclusive == `capacity` events.
        // size before any post-dispatch follow-up == 2 + capacity.
        val survivingFirst = overflow + 1   // = 36 for overflow=35
        for (k in 0 until capacity) {
            val expectedIdx = survivingFirst + k
            assertEquals("""{"event":"hydrating","path":"/p$expectedIdx"}""", out[2 + k])
        }
        // After the sentinel fires the counter must reset; a fresh dispatch
        // after the queue drains should NOT carry a stale sentinel.
        handler.dispatchEvent(HydrationEvent.Dehydrated("/after.txt"))
        advanceUntilIdle()
        val finalLines = writer.linesFor("conn1")
        assertEquals(2 + capacity + 1, finalLines.size)
        assertEquals("""{"event":"dehydrated","path":"/after.txt"}""", finalLines.last())

        handler.onSubscriberDisconnect("conn1")
    }

    @Test
    fun `connection close unregisters subscriber and no further events are written to that connection`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }

        handler.handle("conn1", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(HydrationEvent.Hydrating("/before.txt"))
        advanceUntilIdle()
        assertEquals(1, writer.linesFor("conn1").size)

        handler.onSubscriberDisconnect("conn1")
        advanceUntilIdle()

        // Subsequent dispatches must NOT write to conn1.
        handler.dispatchEvent(HydrationEvent.Hydrated("/after.txt", bytes = 1))
        handler.dispatchEvent(HydrationEvent.Dehydrated("/after.txt"))
        advanceUntilIdle()

        // Still just the one pre-disconnect line.
        assertEquals(1, writer.linesFor("conn1").size)

        // And a fresh dispatch after disconnect doesn't crash the fan-out site —
        // dispatchEvent on no subscribers is a quiet no-op.
        // Also assert nothing got written to a never-existed connection.
        assertNull(writer.lines["never-existed"])
        // Sanity: total dispatched calls into dispatchEvent didn't surface
        // any spurious writer invocations after disconnect.
        assertTrue(writer.linesFor("conn1").none { it.contains("after.txt") })
    }
}
