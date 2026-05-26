package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplyOrderTest {
    private fun mkdir(path: String) = SyncAction.CreateRemoteFolder(path)

    private fun move(
        from: String,
        to: String,
    ) = SyncAction.MoveRemote(path = to, fromPath = from, remoteId = "id-$from")

    /** Index of [path]'s CreateRemoteFolder in the ordered result. */
    private fun List<SyncAction>.mkdirIdx(path: String) =
        indexOfFirst { it is SyncAction.CreateRemoteFolder && it.path == path }

    private fun List<SyncAction>.moveIdx(to: String) =
        indexOfFirst { it is SyncAction.MoveRemote && it.path == to }

    @Test
    fun `parent folders are created before their children regardless of input order`() {
        val out = topologicalApplyOrder(listOf(mkdir("/A/B/C"), mkdir("/A"), mkdir("/A/B")))
        assertTrue(out.mkdirIdx("/A") < out.mkdirIdx("/A/B"), "/A before /A/B")
        assertTrue(out.mkdirIdx("/A/B") < out.mkdirIdx("/A/B/C"), "/A/B before /A/B/C")
    }

    @Test
    fun `a move that produces a folder runs before a child mkdir that needs it`() {
        // Live evidence: mkdir /Pictures/Scans emitted before move /19notte78/Pictures -> /Pictures.
        val out = topologicalApplyOrder(listOf(mkdir("/Pictures/Scans"), move("/19notte78/Pictures", "/Pictures")))
        assertTrue(
            out.moveIdx("/Pictures") < out.mkdirIdx("/Pictures/Scans"),
            "move producing /Pictures must precede mkdir /Pictures/Scans; got $out",
        )
    }

    @Test
    fun `a mkdir that produces a move's source runs before the move consumes it`() {
        val out = topologicalApplyOrder(listOf(move("/staging/job", "/job"), mkdir("/staging/job")))
        assertTrue(
            out.mkdirIdx("/staging/job") < out.moveIdx("/job"),
            "mkdir of the move source must precede the move; got $out",
        )
    }

    @Test
    fun `non-structural actions keep their absolute positions`() {
        val del = SyncAction.DeleteRemote("/z")
        val delLocal = SyncAction.DeleteLocal("/y")
        val out = topologicalApplyOrder(listOf(del, mkdir("/A/B"), delLocal, mkdir("/A")))
        // Structural slots were positions 1 and 3; non-structural stay at 0 and 2.
        assertEquals(del, out[0], "DeleteRemote stays at position 0")
        assertEquals(delLocal, out[2], "DeleteLocal stays at position 2")
        // The two mkdirs fill the structural slots (1,3) in topo order: /A before /A/B.
        assertTrue(out.mkdirIdx("/A") < out.mkdirIdx("/A/B"), "/A before /A/B in their slots")
    }

    @Test
    fun `a move that relocates a subtree runs before a deep child mkdir under the moved tree`() {
        // move /old/A -> /A brings the whole /A subtree (incl. /A/B) with it; mkdir /A/B/C's
        // immediate parent /A/B is produced only by that move (no direct mkdir /A/B). The dep
        // must resolve through the moved ANCESTOR /A, not just the immediate parent.
        val out = topologicalApplyOrder(listOf(mkdir("/A/B/C"), move("/old/A", "/A")))
        assertTrue(
            out.moveIdx("/A") < out.mkdirIdx("/A/B/C"),
            "move relocating /A must precede mkdir /A/B/C (parent /A/B arrives via the move); got $out",
        )
    }

    @Test
    fun `a stable list with no dependencies is returned unchanged`() {
        val input = listOf(mkdir("/a"), mkdir("/b"), mkdir("/c"))
        assertEquals(input, topologicalApplyOrder(input), "independent mkdirs keep input order")
    }
}
