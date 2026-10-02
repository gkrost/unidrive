package org.krost.unidrive.hydration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            is HydrationEvent.Hydrating      -> "ing"
            is HydrationEvent.Hydrated       -> "ed"
            is HydrationEvent.Dehydrated     -> "dh"
            is HydrationEvent.Failed         -> "fa"
            is HydrationEvent.Skipped        -> "sk"
            is HydrationEvent.Completed      -> "co"
            is HydrationEvent.ViewInvalidated -> "vi"
        }
        assertEquals("ing", s)
    }

    @Test
    fun `completed success carries handle id, direction and ok`() {
        val e = HydrationEvent.Completed("/a.txt", "h1", HydrationEvent.Completed.Direction.UPLOAD, ok = true)
        val json = serialiseHydrationEvent(e)
        assertEquals(
            """{"event":"completed","path":"/a.txt","handle_id":"h1","direction":"upload","ok":true}""",
            json,
        )
    }

    @Test
    fun `completed failure carries the error token and no success-only fields`() {
        val e = HydrationEvent.Completed(
            "/a.txt", "h1", HydrationEvent.Completed.Direction.DOWNLOAD, ok = false, error = HydrationError.NotFound,
        )
        val json = serialiseHydrationEvent(e)
        assertEquals(
            """{"event":"completed","path":"/a.txt","handle_id":"h1","direction":"download","ok":false,"error":"not_found"}""",
            json,
        )
    }

    // A failed upload carries the provider's message, which routinely spans lines (an HTTP
    // error body). One raw newline would split the NDJSON line, and a raw control character
    // is not valid inside a JSON string: the reader's parser throws and its stream resets.
    @Test
    fun `completed failure escapes control characters in the error message`() {
        val bell = 1.toChar()
        val e = HydrationEvent.Completed(
            "/a.txt", "h1", HydrationEvent.Completed.Direction.UPLOAD, ok = false,
            error = HydrationError.Generic("HTTP 500\n{\"detail\":\"boom\"}\r\tend" + bell),
        )
        val json = serialiseHydrationEvent(e)
        assertTrue(json.none { it < ' ' }, "no raw control character may reach the wire: $json")
        val backslash = '\\'.toString()
        assertEquals(
            """{"event":"completed","path":"/a.txt","handle_id":"h1","direction":"upload","ok":false,"error":"HTTP 500""" +
                backslash + "n{" + backslash + "\"detail" + backslash + "\":" + backslash + "\"boom" + backslash + "\"}" +
                backslash + "r" + backslash + "tend" + backslash + "u0001" + "\"}",
            json,
        )
    }

    @Test
    fun `view_invalidated serialises as paths array when under the cap`() {
        val e = HydrationEvent.ViewInvalidated(paths = listOf("/a", "/b/c"))
        val json = serialiseHydrationEvent(e)
        assertEquals("""{"event":"view.invalidated","paths":["/a","/b/c"]}""", json)
    }

    @Test
    fun `view_invalidated serialises as full_true when over the cap`() {
        val e = HydrationEvent.ViewInvalidated(paths = emptyList(), full = true)
        val json = serialiseHydrationEvent(e)
        assertEquals("""{"event":"view.invalidated","full":true}""", json)
    }

    @Test
    fun `view_invalidated with exactly 256 paths stays under cap and emits paths array`() {
        val paths = (1..HydrationEvent.VIEW_INVALIDATED_PATH_CAP).map { "/p$it" }
        val e = HydrationEvent.ViewInvalidated(paths = paths)
        val json = serialiseHydrationEvent(e)
        assertTrue(json.startsWith("""{"event":"view.invalidated","paths":["""))
    }
}
