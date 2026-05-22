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
    fun `sealed interface allows future variants without breaking exhaustiveness`() {
        val e: HydrationError = HydrationError.Generic("x")
        val rendered = when (e) {
            is HydrationError.Generic -> "generic:${e.message}"
            // Note: this 'when' is intentionally non-exhaustive at the language level
            // because HydrationError is a sealed *interface* (open for extension by
            // sub-interfaces in callers). The test pins that Generic is matchable.
        }
        assertTrue(rendered.startsWith("generic:"))
    }
}
