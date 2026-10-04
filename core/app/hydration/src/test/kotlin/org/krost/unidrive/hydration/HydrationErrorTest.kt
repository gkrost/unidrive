package org.krost.unidrive.hydration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrationErrorTest {
    @Test
    fun `generic carries the message verbatim`() {
        val e = HydrationError.Generic("disk full")
        assertEquals("disk full", e.message)
    }

    @Test
    fun `unknown path carries the stable unknown_path wire token`() {
        assertEquals("unknown_path", HydrationError.UnknownPath.message)
        assertEquals("unknown_path", HydrationError.UNKNOWN_PATH_TOKEN)
    }

    @Test
    fun `conflict carries the stable conflict wire token`() {
        assertEquals("conflict", HydrationError.Conflict.message)
        assertEquals("conflict", HydrationError.CONFLICT_TOKEN)
    }

    // #536: the token plus the numbers, one string — a client parses the prefix, the mount crate
    // falls to its EIO catch-all.
    @Test
    fun `remote incomplete carries the stable token with the numbers`() {
        assertEquals(
            "remote_incomplete: got 298844160 of 360951317 bytes",
            HydrationError.RemoteIncomplete(storedBytes = 298_844_160L, declaredBytes = 360_951_317L).message,
        )
        assertTrue(HydrationError.RemoteIncomplete(1, 2).message.startsWith(HydrationError.REMOTE_INCOMPLETE_TOKEN))
    }

    @Test
    fun `sealed interface allows future variants without breaking exhaustiveness`() {
        val e: HydrationError = HydrationError.Generic("x")
        val rendered = when (e) {
            is HydrationError.Generic -> "generic:${e.message}"
            HydrationError.NotFound -> "not_found"
            HydrationError.UnknownPath -> "unknown_path"
            HydrationError.Conflict -> "conflict"
            HydrationError.OutOfScope -> "outside_scope"
            HydrationError.Excluded -> "excluded"
            HydrationError.Cancelled -> "cancelled"
            is HydrationError.RemoteIncomplete -> "remote_incomplete"
        }
        assertTrue(rendered.startsWith("generic:"))
    }
}
