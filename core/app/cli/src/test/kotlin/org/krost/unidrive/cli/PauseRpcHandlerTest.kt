package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.PauseGate
import org.krost.unidrive.hydration.TransferPausedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #778: the `daemon.pause` and `daemon.resume` verbs. A refused request answers the engine's structured
 * `{"ok":false,"error":"<token>"}` and changes nothing.
 */
class PauseRpcHandlerTest {
    private var now = 1_000L
    private val gate = PauseGate(clockMs = { now })
    private val events = mutableListOf<HydrationEvent>()
    private val handler = PauseRpcHandler(gate) { events += it }

    private fun reply(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `pause accepts a reason and auto, answers the state and tells subscribers`() {
        val reply = reply(handler.pause("""{"verb":"daemon.pause","reason":"metered","auto":true}"""))

        assertEquals("true", reply.getValue("ok").jsonPrimitive.content)
        val paused = reply.getValue("paused").jsonObject
        assertEquals(setOf("reason", "since_ms", "auto"), paused.keys)
        assertEquals("metered", paused.getValue("reason").jsonPrimitive.content)
        assertEquals("1000", paused.getValue("since_ms").jsonPrimitive.content)
        assertEquals("true", paused.getValue("auto").jsonPrimitive.content)
        assertEquals(PauseGate.State("metered", 1_000L, true), gate.current)
        assertEquals(listOf<HydrationEvent>(HydrationEvent.Paused("metered", 1_000L, true)), events)
    }

    @Test
    fun `auto is optional and defaults to a pause by hand`() {
        handler.pause("""{"verb":"daemon.pause","reason":"user"}""")

        assertEquals(PauseGate.State("user", 1_000L, false), gate.current)
    }

    @Test
    fun `a missing reason is refused with a structured error and changes nothing`() {
        assertEquals("""{"ok":false,"error":"missing_reason"}""", handler.pause("""{"verb":"daemon.pause","auto":true}"""))
        assertEquals("""{"ok":false,"error":"missing_reason"}""", handler.pause("""{"verb":"daemon.pause"}"""))
        assertEquals("""{"ok":false,"error":"missing_reason"}""", handler.pause("not json"))
        assertFalse(gate.isPaused)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a reason that is not a short machine word is refused`() {
        val garbage = listOf(
            "\"\"",
            """"has space"""",
            """"UPPER"""",
            """"metered!"""",
            """"a/b"""",
            """"line\nbreak"""",
            """"${"x".repeat(33)}"""",
            """"é"""",
            "5",
            "true",
            "null",
            "[]",
            "{}",
        )
        for (reason in garbage) {
            assertEquals(
                """{"ok":false,"error":"invalid_reason"}""",
                handler.pause("""{"verb":"daemon.pause","reason":$reason,"auto":false}"""),
                "reason $reason",
            )
        }
        assertFalse(gate.isPaused, "no garbage reason pauses the daemon")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a reason up to 32 lowercase letters, digits and underscores is accepted`() {
        for (reason in listOf("user", "metered", "power_saver", "x", "a1_b2", "x".repeat(32))) {
            assertEquals("true", reply(handler.pause("""{"verb":"daemon.pause","reason":"$reason"}""")).getValue("ok").jsonPrimitive.content, reason)
        }
    }

    @Test
    fun `an auto that is not a boolean is refused`() {
        for (auto in listOf(""""yes"""", "1", "null", "[]")) {
            assertEquals(
                """{"ok":false,"error":"invalid_auto"}""",
                handler.pause("""{"verb":"daemon.pause","reason":"user","auto":$auto}"""),
                "auto $auto",
            )
        }
        assertFalse(gate.isPaused)
    }

    @Test
    fun `pause while paused keeps the first since, takes the new reason and auto and tells subscribers once per change`() {
        handler.pause("""{"verb":"daemon.pause","reason":"user","auto":false}""")
        now = 9_000L

        val same = reply(handler.pause("""{"verb":"daemon.pause","reason":"user","auto":false}"""))
        assertEquals("1000", same.getValue("paused").jsonObject.getValue("since_ms").jsonPrimitive.content)
        assertEquals(1, events.size, "pausing with the state it already has is not news")

        val changed = reply(handler.pause("""{"verb":"daemon.pause","reason":"metered","auto":true}"""))
        val paused = changed.getValue("paused").jsonObject
        assertEquals("metered", paused.getValue("reason").jsonPrimitive.content)
        assertEquals("true", paused.getValue("auto").jsonPrimitive.content)
        assertEquals("1000", paused.getValue("since_ms").jsonPrimitive.content, "the pause began at the first request")
        assertEquals(
            listOf<HydrationEvent>(HydrationEvent.Paused("user", 1_000L, false), HydrationEvent.Paused("metered", 1_000L, true)),
            events,
        )
    }

    @Test
    fun `resume ends the pause and tells subscribers`() {
        handler.pause("""{"verb":"daemon.pause","reason":"user"}""")
        events.clear()

        assertEquals("""{"ok":true}""", handler.resume())

        assertFalse(gate.isPaused)
        assertEquals(listOf<HydrationEvent>(HydrationEvent.Resumed), events)
    }

    @Test
    fun `resume while running is a no-op success and says nothing`() {
        assertEquals("""{"ok":true}""", handler.resume())
        assertEquals("""{"ok":true}""", handler.resume())

        assertNull(gate.current)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `starting a pause aborts the transfers that need ten seconds or more`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val transfer = async { runCatching { gate.track("/a") { _ -> started.complete(Unit); CompletableDeferred<Unit>().await() } } }
        started.await()

        handler.pause("""{"verb":"daemon.pause","reason":"metered","auto":true}""")

        try {
            assertFailsWith<TransferPausedException> { withTimeout(5_000) { transfer.await() }.getOrThrow() }
        } finally {
            transfer.cancel()
        }
    }
}
