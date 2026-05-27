package org.krost.unidrive.tracking

/**
 * Pure per-path reconciliation. Verbatim transliteration of the
 * spec pseudocode, with three deliberate departures:
 *
 *  1. We sequence the untracked branches the way the spec does — adopt
 *     on exact-content match, ignore pure-local untracked, download
 *     pure-remote untracked. The spec's `ReportCollision` for
 *     untracked content-mismatch is preserved verbatim.
 *
 *  2. We treat a `Pending*` tracking row as "the previous run started
 *     to touch this path but didn't finish". On resumption we re-derive
 *     the action from the current observations and the snapshot, NOT
 *     from the pending state's intent. This is how a crashed first-sync
 *     produces zero deletes on resume: the pending-download rows
 *     remain, but on the second pass we see local-absent + remote-present
 *     and emit another DownloadRemote, never a PropagateRemoteDelete.
 *
 *  3. The spec's `(_, _, true, true)` "both gone" path returns NoOp and
 *     asks the caller to `trackingSet.remove(path)`. We expose the
 *     cleanup as a follow-on engine responsibility, signalled by a NoOp
 *     plus a side-channel: a sequence of "should clean up" paths.
 *
 * The reconciler is intentionally side-effect free. The engine owns IO
 * and persistence; this function only decides.
 */
class TrackingReconciler {
    /**
     * Return the action to take for [path] given the live observations
     * and the current tracking record (null = untracked).
     */
    fun reconcile(
        path: String,
        local: LocalObservation,
        remote: RemoteObservation,
        track: TrackingRecord?,
    ): ReconcileAction {
        // -------- Untracked branch --------
        // INVARIANT: untracked files NEVER produce a delete on either side.
        if (track == null) {
            // Both sides exist — adopt iff content matches, else loud collision.
            // No silent auto-rename: spec Amendment 2.
            if (local.exists && remote.exists) {
                if (contentMatches(local, remote)) {
                    // Adoption is the engine's job; reconciler just signals NoOp.
                    // Engine pattern: if (track==null && both-exist && match) trackingSet.adopt(...).
                    // We return NoOp here so the action stream is clean; the engine
                    // separately consults the same (track,local,remote) tuple to
                    // decide adoption. Reconciler emits actions; adoption is state-only.
                    return ReconcileAction.NoOp(path)
                }
                return ReconcileAction.ReportCollision(
                    path,
                    "untracked path exists on both sides with different content (hash mismatch)",
                )
            }

            // Pure-local untracked: invisible to deletion logic. Engine will not
            // upload it until the user `ts claim`s it. That keeps drop-a-file-into-
            // sync_root from auto-uploading on next sync — explicit-only.
            if (local.exists && !remote.exists) return ReconcileAction.NoOp(path)

            // Pure-remote untracked: download. This is how first-sync from a
            // populated remote works — every remote path is untracked, every one
            // becomes a DownloadRemote. The .safe/* crash scenario starts here.
            if (!local.exists && remote.exists) return ReconcileAction.DownloadRemote(path)

            // Neither exists, no tracking row: nothing to do.
            return ReconcileAction.NoOp(path)
        }

        // -------- Tracked branch --------
        // Pending* states (crash-recovery): re-derive from live observations.
        // The action chosen by the case analysis below is correct regardless
        // of the lingering Pending* intent, which is exactly why this engine
        // is structurally crash-safe — see the .safe/* regression test.

        // The four primitive predicates. Per spec.
        //
        // Subtlety: "Gone" must mean "we used to have it AND now we don't".
        // A Pending* row's snapshot may carry null for the side it never
        // finished establishing (PendingDownload → localHash == null;
        // PendingUpload → remoteEtag == null). Reading null-side-absent as
        // "Gone" is precisely the bug class .safe/ hit on the legacy
        // engine. We gate Gone on `snapshot side was non-null` so a
        // crashed-mid-pending row recovers to its original intent (down/
        // upload), not to a delete.
        val localChanged = local.exists && local.hash != track.localHash
        val remoteChanged = remote.exists && (remote.etag != track.remoteEtag)
        val localGone = !local.exists && track.localHash != null
        val remoteGone = !remote.exists && track.remoteEtag != null

        // Pending* recovery: a Pending* row implies "the previous pass
        // started but did not finish". We re-derive the action from live
        // observations:
        //  - PendingDownload + local absent + remote present → DownloadRemote
        //  - PendingUpload   + local present + remote absent → UploadLocal
        // These don't show up under the localChanged/remoteChanged signals
        // (the changed sides match their nullable snapshots) so they have
        // their own short-circuit.
        if (track.state == TrackState.PendingDownload && !local.exists && remote.exists) {
            return ReconcileAction.DownloadRemote(path)
        }
        if (track.state == TrackState.PendingUpload && local.exists && !remote.exists) {
            return ReconcileAction.UploadLocal(path)
        }

        return when {
            // Both sides match snapshot → no-op.
            !localChanged && !remoteChanged && !localGone && !remoteGone ->
                ReconcileAction.NoOp(path)

            // Both gone — caller will trackingSet.remove(path).
            localGone && remoteGone ->
                ReconcileAction.NoOp(path)

            // Local-gone is the sensitive case. Subject to BatchGuard
            // upstream, but the action signal here is "intent to propagate".
            localGone ->
                ReconcileAction.PropagateLocalDelete(path)

            remoteGone ->
                ReconcileAction.PropagateRemoteDelete(path)

            // Both diverged — conflict. No silent winner.
            localChanged && remoteChanged ->
                ReconcileAction.ReportCollision(
                    path,
                    "tracked path diverged on both sides since last sync",
                )

            localChanged -> ReconcileAction.UploadLocal(path)
            remoteChanged -> ReconcileAction.DownloadRemote(path)

            // Defensive: should be unreachable.
            else -> ReconcileAction.NoOp(path)
        }
    }

    /**
     * Adoption helper: should the engine adopt [path] at first-scan time?
     * (Untracked + both-sides-exist + content matches.) Pulled into its
     * own predicate so the engine can decide adoption without
     * re-deriving the conditions.
     */
    fun shouldAdopt(
        local: LocalObservation,
        remote: RemoteObservation,
        track: TrackingRecord?,
    ): Boolean = track == null && local.exists && remote.exists && contentMatches(local, remote)

    private fun contentMatches(
        local: LocalObservation,
        remote: RemoteObservation,
    ): Boolean {
        // Prefer hash-equality when both sides have a hash. If either side's
        // hash is missing (provider with no hashAlgorithm, local not yet
        // hashed), fall back to size-equality which is the loosest "looks
        // the same" we can offer without reading bytes. Loose-match here is
        // safe because the alternative is ReportCollision, which is the
        // correct safe fallback.
        val lh = local.hash
        val rh = remote.hash
        if (lh != null && rh != null) return lh == rh
        return local.size != null && remote.size != null && local.size == remote.size
    }
}
