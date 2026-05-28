package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingReconcileBufferTest {
    private fun item(path: String) =
        CloudItem(
            id = "id$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = 1,
            isFolder = false,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
        )

    @Test
    fun `classify splits additive safe-now from deletion-bearing deferred`() {
        val buf = StreamingReconcileBuffer()
        val safe = buf.classify(listOf(SyncAction.Upload("/a"), SyncAction.DeleteLocal("/b")))
        assertEquals(listOf(SyncAction.Upload("/a")), safe, "additive action fires now")
        assertEquals(listOf(SyncAction.DeleteLocal("/b")), buf.drainDeferred(), "deletion is deferred")
    }

    @Test
    fun `Conflict is deferred only when one side is DELETED`() {
        val buf = StreamingReconcileBuffer()
        val liveConflict =
            SyncAction.Conflict("/m", ChangeState.MODIFIED, ChangeState.MODIFIED, null, ConflictPolicy.KEEP_BOTH)
        val deleteConflict =
            SyncAction.Conflict("/d", ChangeState.DELETED, ChangeState.MODIFIED, null, ConflictPolicy.KEEP_BOTH)
        val safe = buf.classify(listOf(liveConflict, deleteConflict))
        assertEquals(listOf(liveConflict), safe)
        assertEquals(listOf(deleteConflict), buf.drainDeferred())
    }

    // C6 invariant: a delete/recreate split across delta pages must keep the recreated
    // file. Page 1 tombstones the old id (DeleteLocal, deferred); a later page re-creates
    // the file at the same path (DownloadContent, safe-now, already written). The drain
    // must drop the stale DeleteLocal.
    // If this test is removed or loosened, the streaming delete-then-recreate data-loss
    // path silently re-opens.
    @Test
    fun `deferred delete is suppressed when remote content lands at the same path`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/p"))) // earlier page: tombstone(old id) -> deferred
        buf.classify(listOf(SyncAction.DownloadContent("/p", item("/p")))) // later page: recreate -> safe-now
        assertTrue(
            buf.drainDeferred().none { it.path == "/p" },
            "a deferred delete must be dropped once remote content lands at that path",
        )
    }

    // Bot P1 (the complement): a LOCAL Upload must NOT suppress a deferred delete. The
    // localChanges map is replayed every page, so a MODIFIED-local path produces a
    // per-page safe-now Upload; the page carrying the remote tombstone emits a
    // DELETED-bearing Conflict. That conflict is a genuine modify-vs-remote-delete and
    // must survive the drain — only remote-content landing supersedes a delete.
    @Test
    fun `local Upload does not suppress a deferred delete-modify conflict`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.Upload("/p"))) // no-remote page: MODIFIED-local replayed
        val conflict =
            SyncAction.Conflict("/p", ChangeState.MODIFIED, ChangeState.DELETED, null, ConflictPolicy.KEEP_BOTH)
        buf.classify(listOf(conflict)) // tombstone page: real delete-vs-modify conflict
        assertEquals(
            listOf(conflict),
            buf.drainDeferred(),
            "a local Upload must not drop a genuine remote-delete conflict",
        )
    }

    @Test
    fun `deferred delete survives when no safe-now action touched the path`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/gone")))
        assertEquals(
            listOf(SyncAction.DeleteLocal("/gone")),
            buf.drainDeferred(),
            "a genuine remote deletion with no competing remote-content action must still drain",
        )
    }
}
