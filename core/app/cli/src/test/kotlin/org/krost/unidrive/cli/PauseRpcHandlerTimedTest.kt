package org.krost.unidrive.cli

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.PauseGate
import org.krost.unidrive.hydration.serialiseHydrationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `daemon.pause` with `until_ms`: the daemon resumes by itself, on a fake clock and scheduler so nothing sleeps.
 * The wall clock is virtual time plus [skewMs], which a test moves to play a clock that was set or a machine that slept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PauseRpcHandlerTimedTest {
    private val base = 1_700_000_000_000L
    private var skewMs = 0L

    private inner class Fixture(
        testScope: TestScope,
    ) {
        private val scheduler = testScope
        val now: Long get() = base + skewMs + scheduler.currentTime
        val gate = PauseGate(clockMs = { now })
        val events = mutableListOf<HydrationEvent>()
        val handler = PauseRpcHandler(gate, testScope.backgroundScope) { events += it }

        fun pause(
            reason: String = "user",
            until: Long? = null,
            auto: Boolean = false,
        ): String {
            val untilMember = if (until == null) "" else ",\"until_ms\":$until"
            return handler.pause("""{"verb":"daemon.pause","reason":"$reason","auto":$auto$untilMember}""")
        }
    }

    private fun reply(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a timed pause answers, reports and announces its end instant`() = runTest {
        with(Fixture(this)) {
            val until = now + 3_600_000

            val paused = reply(pause(until = until)).getValue("paused").jsonObject

            assertEquals(setOf("reason", "since_ms", "auto", "until_ms"), paused.keys)
            assertEquals(until.toString(), paused.getValue("until_ms").jsonPrimitive.content)
            assertEquals(PauseGate.State("user", base, false, until), gate.current)
            assertEquals(until.toString(), reply(gate.current!!.toJson().toString()).getValue("until_ms").jsonPrimitive.content)
            assertEquals(listOf<HydrationEvent>(HydrationEvent.Paused("user", base, false, until)), events)
            assertEquals(
                """{"event":"paused","reason":"user","since_ms":$base,"auto":false,"until_ms":$until}""",
                serialiseHydrationEvent(events.single()),
            )
        }
    }

    @Test
    fun `the daemon resumes by itself at the instant and announces it as a timeout`() = runTest {
        with(Fixture(this)) {
            val until = now + 3_600_000
            pause(until = until)

            advanceTimeBy(3_600_000 - 1)
            runCurrent()
            assertTrue(gate.isPaused, "one millisecond early")
            assertEquals(1, events.size)

            advanceTimeBy(1)
            runCurrent()
            assertFalse(gate.isPaused)
            assertEquals(HydrationEvent.Resumed(HydrationEvent.Resumed.TIMEOUT), events.last())
            assertEquals(2, events.size)
            assertEquals("""{"event":"resumed","reason":"timeout"}""", serialiseHydrationEvent(events.last()))
        }
    }

    @Test
    fun `an untimed pause stays until daemon resume and its objects carry no until`() = runTest {
        with(Fixture(this)) {
            val paused = reply(pause()).getValue("paused").jsonObject

            assertEquals(setOf("reason", "since_ms", "auto"), paused.keys)
            advanceTimeBy(10L * 365 * 24 * 3_600_000)
            runCurrent()
            assertTrue(gate.isPaused)
            assertNull(gate.current!!.untilMs)
            assertEquals(listOf<HydrationEvent>(HydrationEvent.Paused("user", base, false)), events)
            assertEquals("""{"event":"paused","reason":"user","since_ms":$base,"auto":false}""", serialiseHydrationEvent(events.single()))
            assertEquals("""{"event":"resumed"}""", serialiseHydrationEvent(HydrationEvent.Resumed()))
        }
    }

    @Test
    fun `pausing again with a new until replaces the timer`() = runTest {
        with(Fixture(this)) {
            pause(until = now + 1_000_000)
            advanceTimeBy(500_000)
            val replacedAt = now
            pause(until = now + 2_000_000)
            assertEquals(base, gate.current!!.sinceMs, "the pause began at the first request")

            advanceTimeBy(1_000_000)
            runCurrent()
            assertTrue(gate.isPaused, "the first instant no longer counts")

            advanceTimeBy(1_000_000)
            runCurrent()
            assertFalse(gate.isPaused)
            assertEquals(
                listOf<HydrationEvent>(
                    HydrationEvent.Paused("user", base, false, base + 1_000_000),
                    HydrationEvent.Paused("user", base, false, replacedAt + 2_000_000),
                    HydrationEvent.Resumed(HydrationEvent.Resumed.TIMEOUT),
                ),
                events,
            )
        }
    }

    @Test
    fun `an earlier until replaces a later one`() = runTest {
        with(Fixture(this)) {
            pause(until = now + 5_000_000)
            pause(until = now + 1_000_000)

            advanceTimeBy(1_000_000)
            runCurrent()
            assertFalse(gate.isPaused)
            assertEquals(3, events.size)
        }
    }

    @Test
    fun `pausing again without until makes the pause untimed`() = runTest {
        with(Fixture(this)) {
            pause(until = now + 1_000_000)
            pause()

            advanceTimeBy(5_000_000)
            runCurrent()
            assertTrue(gate.isPaused)
            assertNull(gate.current!!.untilMs)
            assertEquals(2, events.size, "the pause that lost its end is announced once")
        }
    }

    @Test
    fun `pausing again with the same until is not news and keeps one timer`() = runTest {
        with(Fixture(this)) {
            val until = now + 1_000_000
            pause(until = until)
            pause(until = until)
            assertEquals(1, events.size)

            advanceTimeBy(1_000_000)
            runCurrent()
            assertFalse(gate.isPaused)
            assertEquals(2, events.size)
        }
    }

    @Test
    fun `an explicit resume cancels the timer`() = runTest {
        with(Fixture(this)) {
            pause(until = now + 1_000_000)
            assertEquals("""{"ok":true}""", handler.resume())
            assertEquals(
                listOf<HydrationEvent>(HydrationEvent.Paused("user", base, false, base + 1_000_000), HydrationEvent.Resumed()),
                events,
            )

            // A new untimed pause begins before the old instant: the cancelled timer must not end it.
            advanceTimeBy(500_000)
            pause(reason = "metered", auto = true)
            advanceTimeBy(5_000_000)
            runCurrent()

            assertTrue(gate.isPaused)
            assertEquals(3, events.size)
        }
    }

    @Test
    fun `a refused until changes nothing`() = runTest {
        with(Fixture(this)) {
            val invalid = listOf("\"soon\"", "\"1700000100000\"", "1.5", "1e12", "true", "null", "[]", "{}")
            for (until in invalid) {
                assertEquals(
                    """{"ok":false,"error":"invalid_until"}""",
                    handler.pause("""{"verb":"daemon.pause","reason":"user","until_ms":$until}"""),
                    "until $until",
                )
            }
            for (until in listOf(now - 1, now, 0L, -5L)) {
                assertEquals(
                    """{"ok":false,"error":"until_in_past"}""",
                    handler.pause("""{"verb":"daemon.pause","reason":"user","until_ms":$until}"""),
                    "until $until",
                )
            }
            assertFalse(gate.isPaused)
            assertTrue(events.isEmpty())

            // Refused while paused: the pause in force, timer included, is untouched.
            val until = now + 1_000_000
            pause(until = until)
            assertEquals("""{"ok":false,"error":"until_in_past"}""", pause(reason = "metered", until = now - 1))
            assertEquals(PauseGate.State("user", base, false, until), gate.current)
            advanceTimeBy(1_000_000)
            runCurrent()
            assertFalse(gate.isPaused)
        }
    }

    @Test
    fun `a clock set forward resumes within one recheck interval`() = runTest {
        with(Fixture(this)) {
            pause(until = now + 7_200_000)

            skewMs = 7_200_000
            advanceTimeBy(PauseRpcHandler.CLOCK_RECHECK_MS)
            runCurrent()

            assertFalse(gate.isPaused)
            assertEquals(HydrationEvent.Resumed(HydrationEvent.Resumed.TIMEOUT), events.last())
        }
    }

    @Test
    fun `a clock set back keeps the pause until the instant on the new clock`() = runTest {
        with(Fixture(this)) {
            pause(until = now + 100_000)

            skewMs = -3_600_000
            advanceTimeBy(100_000)
            runCurrent()
            assertTrue(gate.isPaused, "the instant is on the wall clock, which is an hour away now")

            skewMs = 0
            advanceTimeBy(PauseRpcHandler.CLOCK_RECHECK_MS)
            runCurrent()
            assertFalse(gate.isPaused)
        }
    }
}
