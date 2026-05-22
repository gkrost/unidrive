package org.krost.unidrive.hydration

import kotlin.test.Test
import kotlin.test.assertEquals

class HydrationEventTest {
    @Test
    fun `hydrating carries path only`() {
        val e = HydrationEvent.Hydrating("/foo/bar.txt")
        assertEquals("/foo/bar.txt", e.path)
    }

    @Test
    fun `hydrated carries path and bytes`() {
        val e = HydrationEvent.Hydrated("/foo/bar.txt", 4096)
        assertEquals("/foo/bar.txt", e.path)
        assertEquals(4096L, e.bytes)
    }

    @Test
    fun `dehydrated carries path`() {
        val e = HydrationEvent.Dehydrated("/foo/bar.txt")
        assertEquals("/foo/bar.txt", e.path)
    }

    @Test
    fun `failed carries path and structured error`() {
        val e = HydrationEvent.Failed("/foo/bar.txt", HydrationError.Generic("nope"))
        assertEquals("/foo/bar.txt", e.path)
        assertEquals("nope", e.error.message)
    }

    @Test
    fun `when over sealed class is exhaustive`() {
        val e: HydrationEvent = HydrationEvent.Hydrating("/x")
        val s: String = when (e) {
            is HydrationEvent.Hydrating  -> "ing"
            is HydrationEvent.Hydrated   -> "ed"
            is HydrationEvent.Dehydrated -> "dh"
            is HydrationEvent.Failed     -> "fa"
        }
        assertEquals("ing", s)
    }
}
