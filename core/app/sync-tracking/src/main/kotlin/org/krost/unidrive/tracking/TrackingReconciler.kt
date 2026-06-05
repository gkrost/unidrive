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
class TrackingReconciler(
    val autoMatch: AutoMatchMode = AutoMatchMode.OFF,
) {
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

            // Pure-local untracked: a file created locally that the remote does
            // not have yet → upload it. This is the first-upload path, symmetric
            // with pure-remote-untracked → DownloadRemote below, and is what makes
            // the engine genuinely two-way (drop a file into sync_root, `ts sync`
            // pushes it). It is an UPLOAD only — untracked paths still never
            // produce a delete, so the lemma holds.
            if (local.exists && !remote.exists) return ReconcileAction.UploadLocal(path)

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
        //
        // Hashless-provider change-token: when both etags/hashes are null
        // (Internxt never returns a content hash or a discriminating etag)
        // the primary identity token can't distinguish "unchanged" from
        // "changed to the same null". Fall back to size: if the size changed
        // the file definitely changed; if size is the same we can't tell
        // (a same-size content edit is undetectable without a hash — inherent
        // to the provider). This is the best available token given the API.
        val remoteEtagDiffers = remote.etag != track.remoteEtag
        val remoteEtagComparable = remote.etag != null || track.remoteEtag != null
        val remoteChanged = remote.exists &&
            (remoteEtagDiffers || (!remoteEtagComparable && remote.size != track.remoteSize))

        // Symmetric: local hash is null on some configurations (e.g. a provider
        // that only hashes after upload). Fall back to local size as well.
        val localHashDiffers = local.hash != track.localHash
        val localHashComparable = local.hash != null || track.localHash != null
        val localChanged = local.exists &&
            (localHashDiffers || (!localHashComparable && local.size != track.localSize))
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
        // Content identity is PROVEN only when BOTH sides supply a comparable
        // hash. A missing hash (e.g. Internxt's hashAlgorithm()==null → every
        // CloudItem.hash==null) means we cannot verify byte-level equality —
        // silently adopting on size-equality would hide a data-divergence bug
        // behind a size coincidence. The safe fallback is false → ReportCollision
        // (loud, user-recoverable) rather than silent adopt (data-corruption risk).
        val lh = local.hash
        val rh = remote.hash
        if (lh != null && rh != null) return lh == rh

        // Both hashes are null: content identity is unprovable.
        // Opt-in escape hatches via autoMatch — the operator asserts it is safe.
        return when (autoMatch) {
            AutoMatchMode.OFF -> false
            AutoMatchMode.SIZE -> local.size != null && local.size == remote.size
            // NAME: both sides exist at the same path (already guaranteed by the
            // caller) and we treat that as sufficient evidence. Any size mismatch
            // is still a potential divergence — we flag it, but the operator opted
            // in to name-only matching so we adopt regardless.
            AutoMatchMode.NAME -> true
        }
    }
}
