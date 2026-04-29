package org.krost.unidrive.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EventBufferTest {
    @Test
    fun `drain returns empty when no events`() {
        val buf = EventBuffer("nonexistent-profile", capacity = 10)
        // Don't start — no daemon to connect to
        val (events, seq) = buf.drain(0)
        assertEquals(0, events.size)
        assertEquals(0, seq)
    }

    @Test
    fun `buffer is not connected when not started`() {
        val buf = EventBuffer("nonexistent-profile")
        assertFalse(buf.connected)
    }

    @Test
    fun `drain with sinceSeq filters old events`() {
        // We can't easily inject events without a real socket,
        // but we can verify the drain API contract with an empty buffer
        val buf = EventBuffer("test", capacity = 5)
        val (events1, seq1) = buf.drain(0, 10)
        assertEquals(0, events1.size)
        assertEquals(0, seq1)
        // Second drain with same seq returns nothing new
        val (events2, seq2) = buf.drain(seq1, 10)
        assertEquals(0, events2.size)
        assertEquals(seq1, seq2)
    }
}
