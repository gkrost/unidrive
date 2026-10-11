package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DaemonSocketAwaitTest {
    private fun pipe(): Pipe = Pipe.open().also { it.source().configureBlocking(false) }

    private fun Pipe.send(text: String) {
        sink().write(ByteBuffer.wrap(text.toByteArray()))
    }

    @Test
    fun `a stalled wait names its step, the time spent and what arrived`() {
        val p = pipe()
        p.send("""{"event":"enumerate.done"}""" + "\n")
        val failure =
            assertFailsWith<IllegalStateException> {
                runBlocking { awaitIpcLine(p.source(), "refresh.done terminal event", budgetMs = 150) { it.contains("refresh.done") } }
            }
        val message = failure.message.orEmpty()
        assertTrue("refresh.done terminal event" in message, "names the step: $message")
        assertTrue("150 ms budget" in message, "names the budget: $message")
        assertTrue(Regex("""waited [0-9]+ ms""").containsMatchIn(message), "names the elapsed time: $message")
        assertTrue("enumerate.done" in message, "lists what did arrive: $message")
    }

    @Test
    fun `a reply and its event are both found whichever arrives first`() {
        val p = pipe()
        // The event overtakes the reply, as it can when a run finishes before its reply line is written.
        p.send("""{"event":"refresh.done","ok":true}""" + "\n" + """{"ok":true,"job_id":"j"}""" + "\n")
        val (reply, event) =
            runBlocking {
                awaitIpcLines(
                    p.source(),
                    "reply and event",
                    1_000,
                    { !it.contains("\"event\"") },
                    { it.contains("\"event\":\"refresh.done\"") },
                )
            }
        assertEquals("""{"ok":true,"job_id":"j"}""", reply)
        assertEquals("""{"event":"refresh.done","ok":true}""", event)
    }

    @Test
    fun `a line split across reads is reassembled`() {
        val p = pipe()
        p.send("""{"ok":tr""")
        val writer = Thread { Thread.sleep(100); p.send("""ue}""" + "\n") }.also { it.start() }
        val line = runBlocking { awaitIpcLine(p.source(), "split line", 5_000) { true } }
        writer.join()
        assertEquals("""{"ok":true}""", line)
    }

    @Test
    fun `a closed peer fails at once with its step`() {
        val p = pipe()
        p.sink().close()
        val failure =
            assertFailsWith<IllegalStateException> {
                runBlocking { awaitIpcLine(p.source(), "peer closes", budgetMs = 5_000) { true } }
            }
        assertTrue("peer closes" in failure.message.orEmpty(), failure.message)
        assertTrue("closed" in failure.message.orEmpty(), failure.message)
    }
}
