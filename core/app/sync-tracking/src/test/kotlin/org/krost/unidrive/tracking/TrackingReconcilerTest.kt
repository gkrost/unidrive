package org.krost.unidrive.tracking

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests over the case table from the spec pseudocode.
 *
 * The integration test (TrackingEngineIntegrationTest) covers the
 * end-to-end behaviour with persistence + IO; this one nails the
 * pure decision function so a future refactor of the case-by-case
 * branches can't drift silently.
 */
class TrackingReconcilerTest {
    private val rec = TrackingReconciler()

    private fun local(
        exists: Boolean,
        hash: String? = "lh",
    ) = LocalObservation(exists, hash, if (exists) 10L else null, if (exists) Instant.EPOCH else null, null)

    private fun remote(
        exists: Boolean,
        etag: String? = "re",
        hash: String? = "rh",
    ) = RemoteObservation(exists, if (exists) "rid" else null, etag, if (exists) 10L else null, hash, if (exists) Instant.EPOCH else null)

    private fun track(
        localHash: String? = "lh",
        remoteEtag: String? = "re",
    ) = TrackingRecord(
        path = "/p",
        providerId = "fake",
        remoteFileId = "rid",
        state = TrackState.TrackedSynced,
        localHash = localHash,
        localSize = 10L,
        remoteEtag = remoteEtag,
        remoteSize = 10L,
        lastSynced = Instant.EPOCH,
    )

    @Test
    fun `untracked + both absent → no-op`() {
        assertTrue(rec.reconcile("/p", local(false), remote(false), null) is ReconcileAction.NoOp)
    }

    @Test
    fun `untracked + pure local → no-op (NOT an upload)`() {
        // Critical: dropping a file into sync_root must NOT auto-upload.
        val a = rec.reconcile("/p", local(true), remote(false), null)
        assertTrue(a is ReconcileAction.NoOp, "expected NoOp for pure-local untracked, got $a")
    }

    @Test
    fun `untracked + pure remote → download`() {
        val a = rec.reconcile("/p", local(false), remote(true), null)
        assertTrue(a is ReconcileAction.DownloadRemote)
    }

    @Test
    fun `untracked + both present + content match → no-op (adopt is engine-level)`() {
        val a = rec.reconcile("/p", local(true, hash = "same"), remote(true, hash = "same"), null)
        assertTrue(a is ReconcileAction.NoOp)
        assertTrue(rec.shouldAdopt(local(true, hash = "same"), remote(true, hash = "same"), null))
    }

    @Test
    fun `untracked + both present + content mismatch → collision`() {
        val a = rec.reconcile("/p", local(true, hash = "L"), remote(true, hash = "R"), null)
        assertTrue(a is ReconcileAction.ReportCollision)
    }

    /**
     * Safety invariant (#104): when BOTH hashes are null (Internxt first-scan),
     * equal size must NOT produce a silent adopt. Content identity is unprovable
     * → surface ReportCollision (loud) rather than silently treating divergent
     * files as identical.
     *
     * This test FAILS before the fix (pre-fix: size fallback returns NoOp).
     */
    @Test
    fun `null_hash_same_size_different_content_emits_collision_not_silent_adopt`() {
        val sameSize = 42L
        val l = LocalObservation(exists = true, hash = null, size = sameSize, mtime = null, inode = null)
        val r = RemoteObservation(exists = true, remoteFileId = "rid", etag = "etag", size = sameSize, hash = null, serverMtime = null)
        val action = rec.reconcile("/p", l, r, track = null)
        assertTrue(
            action is ReconcileAction.ReportCollision,
            "expected ReportCollision when both hashes are null (Internxt), got $action",
        )
        val shouldAdopt = rec.shouldAdopt(l, r, track = null)
        assertTrue(
            !shouldAdopt,
            "shouldAdopt must be false when hashes are null — size-only match is unprovable",
        )
    }

    /**
     * Regression guard (#104): a genuine hash match (OneDrive — both hashes
     * present and equal) must still adopt cleanly; no collision introduced.
     */
    @Test
    fun `hash_match_both_present_still_adopts`() {
        val l = LocalObservation(exists = true, hash = "abc123", size = 10L, mtime = null, inode = null)
        val r = RemoteObservation(exists = true, remoteFileId = "rid", etag = "etag", size = 10L, hash = "abc123", serverMtime = null)
        val action = rec.reconcile("/p", l, r, track = null)
        assertTrue(action is ReconcileAction.NoOp, "hash-equal both-sides must produce NoOp (adopt-signal), got $action")
        assertTrue(rec.shouldAdopt(l, r, track = null), "shouldAdopt must be true for a proven hash match")
    }

    /**
     * Regression guard (#104): a clear size mismatch (both hashes null) must
     * also emit a collision — not silently adopt. Behaviour unchanged from
     * before the fix for this sub-case (size fallback also rejected mismatched
     * sizes), but explicitly asserted so a future refactor can't regress it.
     */
    @Test
    fun `null_hash_size_mismatch_emits_collision`() {
        val l = LocalObservation(exists = true, hash = null, size = 100L, mtime = null, inode = null)
        val r = RemoteObservation(exists = true, remoteFileId = "rid", etag = "etag", size = 200L, hash = null, serverMtime = null)
        val action = rec.reconcile("/p", l, r, track = null)
        assertTrue(action is ReconcileAction.ReportCollision, "size-mismatch with null hashes must produce ReportCollision, got $action")
    }

    // ── auto-match unit tests ──

    /**
     * auto-match=size: null-hash, same-size, same-path → adopt (NoOp + shouldAdopt=true).
     * This test would FAIL before the auto-match change (default collides).
     */
    @Test
    fun `auto_match_size_null_hash_equal_size_adopts`() {
        val size = 42L
        val l = LocalObservation(exists = true, hash = null, size = size, mtime = null, inode = null)
        val r = RemoteObservation(exists = true, remoteFileId = "rid", etag = "etag", size = size, hash = null, serverMtime = null)
        val recSize = TrackingReconciler(AutoMatchMode.SIZE)
        val action = recSize.reconcile("/p", l, r, track = null)
        assertTrue(
            action is ReconcileAction.NoOp,
            "auto-match=size, null hashes, equal size must produce NoOp (adopt-signal), got $action",
        )
        assertTrue(
            recSize.shouldAdopt(l, r, track = null),
            "auto-match=size, null hashes, equal size: shouldAdopt must be true",
        )
    }

    /**
     * auto-match=size: null-hash, SIZE MISMATCH → still collision (size-match required).
     */
    @Test
    fun `auto_match_size_null_hash_size_mismatch_still_collides`() {
        val l = LocalObservation(exists = true, hash = null, size = 10L, mtime = null, inode = null)
        val r = RemoteObservation(exists = true, remoteFileId = "rid", etag = "etag", size = 20L, hash = null, serverMtime = null)
        val recSize = TrackingReconciler(AutoMatchMode.SIZE)
        val action = recSize.reconcile("/p", l, r, track = null)
        assertTrue(
            action is ReconcileAction.ReportCollision,
            "auto-match=size with size mismatch must still produce ReportCollision, got $action",
        )
    }

    /**
     * auto-match=name: null-hash, same-path → adopt regardless of size.
     */
    @Test
    fun `auto_match_name_null_hash_same_path_adopts`() {
        val l = LocalObservation(exists = true, hash = null, size = 10L, mtime = null, inode = null)
        val r = RemoteObservation(exists = true, remoteFileId = "rid", etag = "etag", size = 99L, hash = null, serverMtime = null)
        val recName = TrackingReconciler(AutoMatchMode.NAME)
        val action = recName.reconcile("/p", l, r, track = null)
        assertTrue(
            action is ReconcileAction.NoOp,
            "auto-match=name, null hashes, same-path must produce NoOp (adopt-signal), got $action",
        )
        assertTrue(
            recName.shouldAdopt(l, r, track = null),
            "auto-match=name, null hashes: shouldAdopt must be true",
        )
    }

    /**
     * DEFAULT (no auto-match): null-hash same-size → ReportCollision — #104 preserved.
     * Belt-and-braces assertion on the reconciler directly (complements the integration test).
     */
    @Test
    fun `default_off_null_hash_same_size_still_collides_preserving_104`() {
        val size = 42L
        val l = LocalObservation(exists = true, hash = null, size = size, mtime = null, inode = null)
        val r = RemoteObservation(exists = true, remoteFileId = "rid", etag = "etag", size = size, hash = null, serverMtime = null)
        val recOff = TrackingReconciler(AutoMatchMode.OFF)
        val action = recOff.reconcile("/p", l, r, track = null)
        assertTrue(
            action is ReconcileAction.ReportCollision,
            "default (off) auto-match must still produce ReportCollision for null-hash same-size, got $action",
        )
    }

    @Test
    fun `tracked + no change → no-op`() {
        assertTrue(rec.reconcile("/p", local(true, "lh"), remote(true, "re", "rh"), track()) is ReconcileAction.NoOp)
    }

    @Test
    fun `tracked + local changed → upload`() {
        val a = rec.reconcile("/p", local(true, "NEW"), remote(true, "re", "rh"), track())
        assertTrue(a is ReconcileAction.UploadLocal)
    }

    @Test
    fun `tracked + remote changed → download`() {
        val a = rec.reconcile("/p", local(true, "lh"), remote(true, "NEW", "rh"), track())
        assertTrue(a is ReconcileAction.DownloadRemote)
    }

    @Test
    fun `tracked + both changed → collision`() {
        val a = rec.reconcile("/p", local(true, "NEWL"), remote(true, "NEWR", "rh"), track())
        assertTrue(a is ReconcileAction.ReportCollision)
    }

    @Test
    fun `tracked + local gone → propagate local delete`() {
        val a = rec.reconcile("/p", local(false), remote(true), track())
        assertTrue(a is ReconcileAction.PropagateLocalDelete)
    }

    @Test
    fun `tracked + remote gone → propagate remote delete`() {
        val a = rec.reconcile("/p", local(true), remote(false), track())
        assertTrue(a is ReconcileAction.PropagateRemoteDelete)
    }

    @Test
    fun `tracked + both gone → no-op (caller cleans up)`() {
        val a = rec.reconcile("/p", local(false), remote(false), track())
        assertTrue(a is ReconcileAction.NoOp)
    }

    /**
     * Pending-star states are crash-recovery markers. The reconciler re-derives
     * the action from live observations — independent of the pending intent.
     * This is what makes the .safe/ recovery safe.
     */
    @Test
    fun `pending-download + local absent + remote present → download (not delete)`() {
        val pending = track().copy(state = TrackState.PendingDownload, localHash = null)
        // local absent, remote present, snapshot says "we never had it locally"
        val a = rec.reconcile("/p", local(false), remote(true, "re", "rh"), pending)
        assertEquals(
            ReconcileAction.DownloadRemote("/p")::class,
            a::class,
            "post-crash pending-download must re-derive to DownloadRemote, got $a",
        )
    }
}

/** BatchGuard tests. */
class BatchGuardTest {
    private fun deletes(n: Int) = (0 until n).map { ReconcileAction.PropagateLocalDelete("/p-$it") }

    @Test
    fun `allow when no deletes regardless of total`() {
        val g = BatchGuard()
        val plan: List<ReconcileAction> = listOf(ReconcileAction.UploadLocal("/u"))
        assertTrue(g.inspect(plan, trackedTotal = 0) is BatchGuard.Verdict.Allow)
    }

    @Test
    fun `allow when ratio under threshold and under absolute cap`() {
        val g = BatchGuard(maxDeleteRatio = 0.5, maxDeleteAbsolute = 50)
        assertTrue(g.inspect(deletes(10), trackedTotal = 100) is BatchGuard.Verdict.Allow)
    }

    @Test
    fun `deny on ratio trip`() {
        val g = BatchGuard(maxDeleteRatio = 0.5, maxDeleteAbsolute = 1_000)
        val v = g.inspect(deletes(51), trackedTotal = 100)
        assertTrue(v is BatchGuard.Verdict.Deny)
        assertTrue(v.ratioTripped)
    }

    @Test
    fun `deny on absolute trip even with low ratio`() {
        val g = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = 5)
        val v = g.inspect(deletes(6), trackedTotal = 100_000)
        assertTrue(v is BatchGuard.Verdict.Deny)
        assertTrue(v.absoluteTripped)
    }

    @Test
    fun `deny when trackedTotal=0 but deletes proposed`() {
        // Edge case: tracking-set tells us nothing is tracked but somehow
        // a delete shows up. Ratio is 100% by convention; both axes can trip.
        val g = BatchGuard()
        val v = g.inspect(deletes(1), trackedTotal = 0)
        assertTrue(v is BatchGuard.Verdict.Deny, "delete with empty tracked set must trip the guard")
    }
}
