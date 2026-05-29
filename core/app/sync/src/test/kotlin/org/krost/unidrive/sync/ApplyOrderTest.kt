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

    // #123: createFolderBatches feeds the bounded-concurrency create-folder run.
    // Every batch may run concurrently; the apply loop drains batch N before
    // starting batch N+1. These tests pin the parent-before-child invariant that
    // barrier relies on.

    @Test
    fun `create-folder batches place every parent in an earlier batch than its child`() {
        // Topologically ordered input (parent before child within the flat list).
        val ordered = topologicalApplyOrder(listOf(mkdir("/A/B/C"), mkdir("/A"), mkdir("/A/B")))
            .filterIsInstance<SyncAction.CreateRemoteFolder>()
        val batches = createFolderBatches(ordered)
        // Map each path to the index of the batch that creates it.
        val batchOf = HashMap<String, Int>()
        batches.forEachIndexed { i, b -> b.forEach { batchOf[it.path] = i } }
        assertTrue(batchOf["/A"]!! < batchOf["/A/B"]!!, "/A batch precedes /A/B batch")
        assertTrue(batchOf["/A/B"]!! < batchOf["/A/B/C"]!!, "/A/B batch precedes /A/B/C batch")
    }

    @Test
    fun `sibling folders at the same depth share one batch so they run concurrently`() {
        val batches = createFolderBatches(listOf(mkdir("/A/x"), mkdir("/A/y"), mkdir("/A/z")))
        assertEquals(1, batches.size, "three depth-2 siblings collapse into one concurrent batch")
        assertEquals(setOf("/A/x", "/A/y", "/A/z"), batches[0].map { it.path }.toSet())
    }

    @Test
    fun `no batch contains a folder and one of its own ancestors`() {
        // The barrier between batches is the only thing serialising parent→child;
        // if a parent and child ever landed in the same batch they could race.
        val batches =
            createFolderBatches(
                listOf(mkdir("/A"), mkdir("/A/B"), mkdir("/A/B/C"), mkdir("/D"), mkdir("/D/E")),
            )
        for (batch in batches) {
            val paths = batch.map { it.path }
            for (p in paths) {
                val ancestorInSameBatch = paths.any { it != p && p.startsWith("$it/") }
                assertTrue(!ancestorInSameBatch, "batch $paths must not contain an ancestor of $p")
            }
        }
    }
}
