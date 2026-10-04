package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.EnumerateResult
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnumerateRpcHandlerTest {
    @Test
    fun enumerate_emits_terminal_done_event_with_counts(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true, upserted = 3, reaped = 1, complete = true))
        val emitted = mutableListOf<String>()
        val handler = EnumerateRpcHandler(engine, scope, emit = { emitted.add(it) })

        val reply = handler.handle("conn-1", """{"verb":"sync.enumerate","reset":false}""")
        assertTrue(reply.contains("\"ok\":true"), "sync reply must accept the request: $reply")
        handler.awaitInFlight()

        assertEquals(1, emitted.size)
        val ev = emitted.single()
        assertTrue(ev.contains("\"event\":\"enumerate.done\""), ev)
        assertTrue(ev.contains("\"ok\":true"), ev)
        assertTrue(ev.contains("\"upserted\":3"), ev)
        assertTrue(ev.contains("\"reaped\":1"), ev)
        assertTrue(ev.contains("\"complete\":true"), ev)
    }

    @Test
    fun enumerate_reports_provider_error_on_failed_result(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "boom \"quoted\""))
        val emitted = mutableListOf<String>()
        val handler = EnumerateRpcHandler(engine, scope, emit = { emitted.add(it) })

        handler.handle("conn-1", """{"verb":"sync.enumerate","reset":false}""")
        handler.awaitInFlight()

        val ev = emitted.single()
        assertTrue(ev.contains("\"ok\":false"), ev)
        assertTrue(ev.contains("\"error\":\"provider_error\""), ev)
        // Encoder must escape the embedded quotes (raw string-concat would corrupt the JSON).
        assertTrue(ev.contains("""boom \"quoted\""""), ev)
    }

    @Test
    fun second_concurrent_enumerate_returns_busy(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true), gate = gate)
        val handler = EnumerateRpcHandler(engine, scope, emit = {})

        val first = handler.handle("conn-1", """{"verb":"sync.enumerate","reset":true}""")
        assertTrue(first.contains("\"ok\":true"), first)

        // Yield until the launched job has actually entered enumerate (gated open).
        repeat(200) {
            if (engine.enumerateCalled) return@repeat
            kotlinx.coroutines.yield()
        }
        assertTrue(engine.enumerateCalled, "first enumerate must be in flight before the busy check")
        val second = handler.handle("conn-2", """{"verb":"sync.enumerate"}""")
        assertTrue(second.contains("\"ok\":false"), second)
        assertTrue(second.contains("\"error\":\"busy\""), second)

        gate.complete(Unit)
        handler.awaitInFlight()
        assertEquals(true, engine.lastReset)
        assertNotNull(handler)
    }

    @Test
    fun enumerate_emits_shutdown_on_daemon_close_and_ends_cancelled(): Unit = runBlocking {
        // Cancelling the daemon scope while an enumeration is in flight must emit
        // the shutdown terminal event (the view consumer needs closure), release
        // the in-flight slot, and NOT swallow the CancellationException into a
        // normal completion.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true), gate = gate)
        val emitted = mutableListOf<String>()
        val handler = EnumerateRpcHandler(engine, scope, emit = { emitted.add(it) })

        handler.handle("conn-1", """{"verb":"sync.enumerate"}""")

        // Wait until the enumeration has actually suspended inside the engine.
        repeat(200) {
            if (engine.enumerateCalled) return@repeat
            kotlinx.coroutines.yield()
        }
        assertTrue(engine.enumerateCalled, "enumerate must be in flight before the close")

        // Simulate daemon close while the enumeration is suspended; the gate is
        // never completed, so the coroutine resumes with CancellationException.
        scope.cancel()
        repeat(200) {
            if (emitted.isNotEmpty()) return@repeat
            kotlinx.coroutines.yield()
        }

        val ev = emitted.single()
        assertTrue(ev.contains("\"event\":\"enumerate.done\""), ev)
        assertTrue(ev.contains("\"ok\":false"), ev)
        assertTrue(ev.contains("\"error\":\"shutdown\""), "scope cancellation must emit shutdown, not provider_error: $ev")
        assertEquals(null, handler.inFlightJobId(), "the in-flight slot must be released on shutdown")
    }
}
