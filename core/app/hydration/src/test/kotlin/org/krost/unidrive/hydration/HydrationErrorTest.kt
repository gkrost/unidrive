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

    @Test
    fun `sealed interface allows future variants without breaking exhaustiveness`() {
        val e: HydrationError = HydrationError.Generic("x")
        val rendered = when (e) {
            is HydrationError.Generic -> "generic:${e.message}"
            HydrationError.NotFound -> "not_found"
            HydrationError.UnknownPath -> "unknown_path"
            HydrationError.Conflict -> "conflict"
        }
        assertTrue(rendered.startsWith("generic:"))
    }
}
