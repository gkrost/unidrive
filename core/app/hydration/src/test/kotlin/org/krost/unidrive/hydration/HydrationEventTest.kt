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
            is HydrationEvent.Queued         -> "qu"
            is HydrationEvent.Skipped        -> "sk"
            is HydrationEvent.Uploading      -> "up"
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
    fun `view_invalidated omits moved when there are no renames`() {
        // #595: the hint is additive — consumers written before it must parse the line.
        val e = HydrationEvent.ViewInvalidated(paths = listOf("/a"))
        assertEquals("""{"event":"view.invalidated","paths":["/a"]}""", serialiseHydrationEvent(e))
    }

    @Test
    fun `view_invalidated carries the rename hint as from-to pairs`() {
        val e =
            HydrationEvent.ViewInvalidated(
                paths = listOf("/before.txt", "/after.txt"),
                moved = listOf(HydrationEvent.ViewInvalidated.Moved("/before.txt", "/after.txt")),
            )
        assertEquals(
            """{"event":"view.invalidated","paths":["/before.txt","/after.txt"],"moved":[{"from":"/before.txt","to":"/after.txt"}]}""",
            serialiseHydrationEvent(e),
        )
    }

    @Test
    fun `view_invalidated with exactly 256 paths stays under cap and emits paths array`() {
        val paths = (1..HydrationEvent.VIEW_INVALIDATED_PATH_CAP).map { "/p$it" }
        val e = HydrationEvent.ViewInvalidated(paths = paths)
        val json = serialiseHydrationEvent(e)
        assertTrue(json.startsWith("""{"event":"view.invalidated","paths":["""))
    }

    // The progress event is what a mounted client sees most often during a copy: its object
    // must close exactly once, at the end. A stray brace after handle_id made every line
    // unparseable and reset the client's event stream on each progress report.
    @Test
    fun `uploading serialises as one well-formed object`() {
        val e = HydrationEvent.Uploading("/a/save.doc", "h1", bytesDone = 4096, bytesTotal = 65536)
        assertEquals(
            """{"event":"uploading","path":"/a/save.doc","handle_id":"h1","bytes_done":4096,"bytes_total":65536}""",
            serialiseHydrationEvent(e),
        )
    }

    @Test
    fun `every event kind serialises with balanced braces and brackets`() {
        val events = listOf(
            HydrationEvent.Hydrating("/a"),
            HydrationEvent.Hydrated("/a", 1),
            HydrationEvent.Dehydrated("/a"),
            HydrationEvent.Skipped("/a"),
            HydrationEvent.Queued("/a"),
            HydrationEvent.Uploading("/a", "h", 1, 2),
            HydrationEvent.Failed("/a", HydrationError.Generic("x")),
            HydrationEvent.Completed("/a", "h", HydrationEvent.Completed.Direction.UPLOAD, ok = true),
            HydrationEvent.Completed("/a", "h", HydrationEvent.Completed.Direction.UPLOAD, ok = false, error = HydrationError.NotFound),
            HydrationEvent.ViewInvalidated(paths = listOf("/a")),
            HydrationEvent.ViewInvalidated(paths = emptyList(), full = true),
        )
        for (e in events) {
            val json = serialiseHydrationEvent(e)
            var depth = 0
            var inString = false
            var escaped = false
            for ((i, c) in json.withIndex()) {
                if (inString) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        assertTrue(depth >= 0, "closed more than opened at index $i: $json")
                        assertTrue(depth > 0 || i == json.length - 1, "the top-level object closes before the end at index $i: $json")
                    }
                }
            }
            assertEquals(0, depth, "unbalanced: $json")
        }
    }
}
