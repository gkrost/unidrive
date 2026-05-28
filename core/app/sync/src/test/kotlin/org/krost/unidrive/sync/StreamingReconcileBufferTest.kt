package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingReconcileBufferTest {
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

    // C6 invariant: a path that fired a safe-now (additive) action this gather must
    // NOT then be destroyed by a deferred delete buffered on another page. The live
    // case is delete-then-recreate split across delta pages: page 1 tombstones the old
    // id (DeleteLocal, deferred), a later page re-creates the file (DownloadContent,
    // safe-now). Here Upload stands in for any additive verdict — the buffer's safePaths
    // mechanism is agnostic to which one fired.
    // If this test is removed or loosened, the streaming delete-then-recreate data-loss
    // path silently re-opens.
    @Test
    fun `deferred delete is suppressed when the same path fired safe-now`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/p"))) // earlier page: tombstone -> deferred
        buf.classify(listOf(SyncAction.Upload("/p"))) // later page: recreate -> safe-now
        assertTrue(
            buf.drainDeferred().none { it.path == "/p" },
            "a deferred delete for a path that also fired safe-now must be dropped at drain",
        )
    }

    @Test
    fun `deferred delete survives when no safe-now action touched the path`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/gone")))
        assertEquals(
            listOf(SyncAction.DeleteLocal("/gone")),
            buf.drainDeferred(),
            "a genuine remote deletion with no competing safe-now action must still drain",
        )
    }
}
