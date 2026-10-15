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

    // #778: a download refused while the daemon is paused; the front-end asks the user, then resumes and retries.
    @Test
    fun `paused carries the stable paused wire token`() {
        assertEquals("paused", HydrationError.Paused.message)
        assertEquals("paused", HydrationError.PAUSED_TOKEN)
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
    fun `missing remote object carries a stable token and useful status`() {
        assertEquals(
            "remote_object_missing: drive item exists, but its stored cloud object is missing",
            HydrationError.RemoteObjectMissing.message,
        )
        assertEquals("remote_object_missing", HydrationError.REMOTE_OBJECT_MISSING_TOKEN)
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
            HydrationError.RemoteObjectMissing -> "remote_object_missing"
            HydrationError.InvalidPath -> "invalid_path"
            is HydrationError.NameRefused -> "name_refused"
            is HydrationError.BlockedByParent -> "blocked_by_parent"
            HydrationError.Paused -> "paused"
            HydrationError.ReadOnly -> "read_only"
        }
        assertTrue(rendered.startsWith("generic:"))
    }

    // The trash namespace is read-only: every verb that would write answers this, and list says it beforehand.
    @Test
    fun `read only carries the stable read_only wire token`() {
        assertEquals("read_only", HydrationError.ReadOnly.message)
        assertEquals("read_only", HydrationError.READ_ONLY_TOKEN)
    }

    @Test
    fun `invalid path carries the stable invalid_path wire token`() {
        assertEquals("invalid_path", HydrationError.InvalidPath.message)
        assertEquals("invalid_path", HydrationError.INVALID_PATH_TOKEN)
    }

    // The drive refuses an entry by its name: the stable token, then the sibling when it is known. A client matches the
    // prefix up to ": ".
    @Test
    fun `name refused carries the stable similar_name token and the sibling when known`() {
        assertEquals("similar_name", HydrationError.NameRefused("similar_name", null).message)
        assertEquals("similar_name: sibling.txt", HydrationError.NameRefused("similar_name", "sibling.txt").message)
        assertEquals("blocked_by_parent: /a/b", HydrationError.BlockedByParent("/a/b").message)
        assertEquals("blocked_by_parent", HydrationError.BLOCKED_BY_PARENT_TOKEN)
    }
}
