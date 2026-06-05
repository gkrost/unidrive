package org.krost.unidrive.tracking

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaCursorExpiredException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.Reconciler
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * Orchestrator for the tracking-set engine.
 *
 * Per-pass flow:
 *   1. Walk local tree under [syncRoot] → map<path, LocalObservation>.
 *   2. Enumerate remote → map<path, RemoteObservation>.
 *   3. Union (local ∪ remote ∪ trackingSet.paths()) — this is what makes
 *      "remote vanished AND local already deleted" still observable.
 *   4. For each path, call [TrackingReconciler.reconcile] and collect
 *      actions. Adoption is decided in the same loop.
 *   5. [BatchGuard.inspect] the full action list. If it trips, drop ALL
 *      delete actions; keep uploads/downloads/collisions.
 *   6. Apply non-NoOp actions: persist `Pending*` row → do IO →
 *      persist final state. The Pending row is what makes the engine
 *      crash-safe: a kill mid-action leaves a row the next pass can
 *      reason about without producing a delete.
 *
 * The engine is deliberately simple. There's no concurrency control
 * here — runs a single pass single-threaded. Real-world parallelism is
 * a follow-up and is the obvious place for the next round of
 * regressions, so keeping it single-threaded keeps the structural-
 * safety story unambiguous.
 *
 * Adoption policy (spec Amendment 2): on first scan with a non-empty
 * `sync_root`, adopt only when local and remote contents match exactly.
 * Anything else surfaces as `ReportCollision` — the user must
 * `unidrive ts claim <path>` to assign a winner.
 */
class TrackingEngine(
    private val provider: CloudProvider,
    private val trackingSet: TrackingSet,
    private val syncRoot: Path,
    reconciler: TrackingReconciler? = null,
    private val batchGuard: BatchGuard = BatchGuard(),
    private val dryRun: Boolean = false,
    private val excludePatterns: List<String> = emptyList(),
    autoMatch: AutoMatchMode = AutoMatchMode.OFF,
) {
    private val reconciler: TrackingReconciler = reconciler ?: TrackingReconciler(autoMatch)
    private val log = LoggerFactory.getLogger(TrackingEngine::class.java)

    /**
     * Single sync pass. Returns the plan + apply result so the CLI can
     * render `ts status`-style summaries without re-running anything.
     */
    fun syncOnce(): PassReport =
        runBlocking {
            val localObs = scanLocal()
            val remoteEnum = enumerateRemote()
            val remoteObs = remoteEnum.observations
            val tracked = trackingSet.paths()
            val universe = (localObs.keys + remoteObs.keys + tracked).toSortedSet()

            val plan = mutableListOf<ReconcileAction>()
            val collisions = mutableListOf<ReconcileAction.ReportCollision>()
            val adopted = mutableListOf<String>()
            val cleanedUp = mutableListOf<String>()

            for (path in universe) {
                // Default-ignore-list (keep-local): desktop/OS junk is never uploaded, adopted,
                // downloaded, or tombstoned. Skipping the whole path — not just its local
                // observation — is what guarantees "don't tombstone": an already-tracked path
                // that becomes excluded is left inert rather than read as locally-gone.
                if (isExcluded(path)) continue
                val l = localObs[path] ?: LocalObservation(exists = false, hash = null, size = null, mtime = null, inode = null)
                val t = trackingSet.lookup(path)
                val r =
                    remoteObs[path]
                        ?: absentRemoteObservation(remoteEnum.incremental, t)

                // Adopt before reconcile so adopt-on-content-match doesn't
                // emit a spurious DownloadRemote on the same pass.
                if (reconciler.shouldAdopt(l, r, t)) {
                    if (!dryRun) {
                        trackingSet.adopt(path, provider.id, l, r)
                    }
                    adopted += path
                    continue
                }

                val action = reconciler.reconcile(path, l, r, t)
                if (action is ReconcileAction.NoOp) {
                    // Cleanup branch: tracked row with no observations on
                    // either side. The reconciler emits NoOp here and
                    // expects the engine to remove the row (spec
                    // "both gone" case). Skip in dry-run.
                    if (t != null && !l.exists && !r.exists) {
                        if (!dryRun) trackingSet.remove(path)
                        cleanedUp += path
                    }
                    continue
                }
                if (action is ReconcileAction.ReportCollision) collisions += action
                plan += action
            }

            val verdict = batchGuard.inspect(plan, trackedTotal = tracked.size)
            val effectivePlan =
                when {
                    !remoteEnum.complete -> {
                        // Remote enumeration didn't see the full inventory
                        // (ProviderException mid-loop, a DeltaPage with
                        // complete=false, or no Delta capability at all).
                        // Tracked paths we didn't see look remote-absent to
                        // the reconciler and would otherwise emit
                        // PropagateRemoteDelete. Suppress all delete actions
                        // unconditionally to avoid propagating spurious
                        // deletes from a transient enumeration failure.
                        log.warn(
                            "Remote enumeration incomplete; suppressing delete actions for this pass " +
                                "(tracked rows that we didn't see are kept, not deleted).",
                        )
                        plan.filterNot {
                            it is ReconcileAction.PropagateLocalDelete ||
                                it is ReconcileAction.PropagateRemoteDelete
                        }
                    }
                    verdict is BatchGuard.Verdict.Deny -> {
                        log.warn("{}", verdict.describe())
                        plan.filterNot {
                            it is ReconcileAction.PropagateLocalDelete ||
                                it is ReconcileAction.PropagateRemoteDelete
                        }
                    }
                    else -> plan
                }

            val applied = if (dryRun) emptyList() else applyActions(effectivePlan)

            // After file deletes land, reap any remote directory they emptied (and
            // retry any directory whose reap was deferred on an earlier pass). The
            // engine tracks files only (folders are skipped in enumeration), so a
            // locally-deleted folder otherwise leaves an empty remote-directory
            // shell behind. Apply-only: dry-run deletes nothing and retries
            // nothing.
            val reapedDirs =
                if (dryRun) {
                    emptyList()
                } else {
                    // PropagateLocalDelete renders as "del-remote": the local file
                    // vanished, so its remote counterpart was deleted via
                    // provider.delete. Those are the deletions that can empty a
                    // remote directory.
                    val deletedFilePaths =
                        applied
                            .filter {
                                it.outcome == ApplyOutcome.SUCCESS &&
                                    it.action is ReconcileAction.PropagateLocalDelete
                            }
                            .map { it.action.path }
                    reapEmptyDirs(deletedFilePaths)
                }

            PassReport(
                plan = plan,
                effectivePlan = effectivePlan,
                applied = applied,
                adopted = adopted,
                collisions = collisions,
                guardVerdict = verdict,
                remoteEnumerationComplete = remoteEnum.complete,
                cleanedUp = cleanedUp,
                reapedDirs = reapedDirs,
            )
        }

    /**
     * Manual claim: mark [path] as adopted using current observations.
     * Used to resolve a [ReconcileAction.ReportCollision] (spec Amendment 2)
     * by declaring the local copy authoritative. Re-emits as an upload
     * on the next pass if the local diverges from the remote snapshot
     * we capture here.
     */
    fun claim(path: String) {
        runBlocking {
            val l = observeLocal(path)
            val r = observeRemote(path)
            trackingSet.adopt(path, provider.id, l, r)
        }
    }

    /** Remove [path] from the tracking set. */
    fun unclaim(path: String) {
        trackingSet.remove(path)
    }

    private suspend fun applyActions(plan: List<ReconcileAction>): List<AppliedAction> {
        val out = mutableListOf<AppliedAction>()
        for (action in plan) {
            val applied =
                try {
                    when (action) {
                        is ReconcileAction.NoOp -> AppliedAction(action, ApplyOutcome.SKIPPED, null)
                        is ReconcileAction.ReportCollision -> AppliedAction(action, ApplyOutcome.SKIPPED, action.reason)
                        is ReconcileAction.DownloadRemote -> applyDownload(action)
                        is ReconcileAction.UploadLocal -> applyUpload(action)
                        is ReconcileAction.PropagateLocalDelete -> applyPropagateLocalDelete(action)
                        is ReconcileAction.PropagateRemoteDelete -> applyPropagateRemoteDelete(action)
                    }
                } catch (e: Exception) {
                    log.warn("Apply failed for ${action.path}: ${e.message}")
                    AppliedAction(action, ApplyOutcome.FAILED, e.message)
                }
            out += applied
        }
        return out
    }

    private suspend fun applyDownload(action: ReconcileAction.DownloadRemote): AppliedAction {
        val path = action.path
        val dest = syncRoot.resolve(stripLeadingSlash(path))
        // Step 1: persist PendingDownload BEFORE network call. This is what makes
        // crash-recovery work: even if we die here, the next pass will see local
        // absent + remote present + PendingDownload row, which still reconciles to
        // DownloadRemote (not a delete).
        val existing = trackingSet.lookup(path)
        val pending =
            TrackingRecord(
                path = path,
                providerId = provider.id,
                remoteFileId = existing?.remoteFileId,
                state = TrackState.PendingDownload,
                localHash = existing?.localHash,
                localSize = existing?.localSize,
                remoteEtag = existing?.remoteEtag,
                remoteSize = existing?.remoteSize,
                lastSynced = Instant.now(),
            )
        trackingSet.upsert(pending)

        Files.createDirectories(dest.parent ?: syncRoot)
        provider.download(path, dest)

        val l = observeLocal(path)
        val r = observeRemote(path)
        trackingSet.adopt(path, provider.id, l, r)
        return AppliedAction(action, ApplyOutcome.SUCCESS, null)
    }

    private suspend fun applyUpload(action: ReconcileAction.UploadLocal): AppliedAction {
        val path = action.path
        val src = syncRoot.resolve(stripLeadingSlash(path))
        val existing = trackingSet.lookup(path)
        val pending =
            TrackingRecord(
                path = path,
                providerId = provider.id,
                remoteFileId = existing?.remoteFileId,
                state = TrackState.PendingUpload,
                localHash = existing?.localHash,
                localSize = existing?.localSize,
                remoteEtag = existing?.remoteEtag,
                remoteSize = existing?.remoteSize,
                lastSynced = Instant.now(),
            )
        trackingSet.upsert(pending)

        // Ensure the remote parent directories exist before uploading a new file
        // into them — a first upload of /a/b/c.txt must create /a, then /a/b.
        // Create them ONE AT A TIME shallowest-first: a child folder can only be
        // created once its parent exists, so a single batch call (which may try
        // to create /a/b before /a commits) is not enough. createFolder tolerates
        // an already-existing folder, so this is idempotent.
        if (existing?.remoteFileId == null) {
            for (dir in ancestorDirsToRoot(path).reversed()) { // shallowest-first
                try {
                    provider.createFolder(dir)
                } catch (e: Exception) {
                    log.debug("createFolder {} raised (likely already exists): {}", dir, e.message)
                }
            }
        }

        val uploaded = provider.upload(src, path, existingRemoteId = existing?.remoteFileId)
        val l = observeLocal(path)
        val r = observeRemote(path).copy(remoteFileId = uploaded.id, etag = uploaded.hash, hash = uploaded.hash, size = uploaded.size)
        trackingSet.adopt(path, provider.id, l, r)
        return AppliedAction(action, ApplyOutcome.SUCCESS, null)
    }

    /**
     * Ancestor directories of [filePath], immediate parent up to — but not
     * including — the sync root. `/a/b/c.txt` → `["/a/b", "/a"]`; a root-level
     * file → `[]`.
     */
    private fun ancestorDirsToRoot(filePath: String): List<String> {
        val out = mutableListOf<String>()
        var current = filePath
        while (true) {
            val slash = current.lastIndexOf('/')
            if (slash <= 0) break
            current = current.substring(0, slash)
            out += current
        }
        return out
    }

    private suspend fun applyPropagateLocalDelete(action: ReconcileAction.PropagateLocalDelete): AppliedAction {
        val path = action.path
        val existing = trackingSet.lookup(path) ?: return AppliedAction(action, ApplyOutcome.SKIPPED, "untracked")
        val pending = existing.copy(state = TrackState.PendingDeleteRemote, lastSynced = Instant.now())
        trackingSet.upsert(pending)
        provider.delete(path)
        trackingSet.remove(path)
        return AppliedAction(action, ApplyOutcome.SUCCESS, null)
    }

    private suspend fun applyPropagateRemoteDelete(action: ReconcileAction.PropagateRemoteDelete): AppliedAction {
        val path = action.path
        val existing = trackingSet.lookup(path) ?: return AppliedAction(action, ApplyOutcome.SKIPPED, "untracked")
        val pending = existing.copy(state = TrackState.PendingDeleteLocal, lastSynced = Instant.now())
        trackingSet.upsert(pending)
        val local = syncRoot.resolve(stripLeadingSlash(path))
        Files.deleteIfExists(local)
        trackingSet.remove(path)
        return AppliedAction(action, ApplyOutcome.SUCCESS, null)
    }

    /**
     * Reap remote directories emptied by file deletions.
     *
     * The engine tracks files only, so a locally-deleted folder propagates its
     * file deletions but leaves the empty remote directory behind. This deletes
     * those now-empty directories.
     *
     * Candidate set = the ancestor directories of every file deleted THIS pass
     * (`deletedFilePaths`) UNION the directories whose reap was deferred on an
     * earlier pass ([TrackingSet.pendingReaps]). The persisted set is what makes
     * reaping converge: the tracking row for a deleted file is removed the moment
     * its delete succeeds, so a directory skipped on the pass that emptied it
     * would otherwise never re-appear as a candidate. Persisting the deferral and
     * replaying it each pass gives an eventually-consistent provider time to
     * settle.
     *
     * Per candidate, after listing its children:
     *  - **empty** → delete it. On success, clear it from the pending set; on a
     *    provider error, persist it for retry on a later pass.
     *  - **non-empty** → it has a surviving child. If a TRACKED file still lives
     *    under it, the directory is legitimately populated and is retired from
     *    the pending set (not ours to reap). If no tracked descendant remains,
     *    the listing is treated as not-yet-consistent (a just-trashed file still
     *    shown) and the directory is persisted for a retry.
     *
     * Safety:
     *  - **Empty-verify before delete.** Some providers (Internxt) trash a folder
     *    by CASCADING its whole subtree — so a delete on a non-empty directory
     *    would take untracked children with it. We never rely on the provider to
     *    refuse a non-empty directory; the [CloudProvider.listChildren] gate above
     *    is the primary guard. A wrong deletion is never issued.
     *  - **Deepest-first**, so a nested empty tree collapses bottom-up: reaping
     *    `/a/b/c` first lets `/a/b` then become empty and be reaped in the same pass.
     *  - **Sync root is never a candidate** (ancestor walk is root-exclusive).
     *  - **Best-effort.** A provider error on one directory is logged, persisted
     *    for retry, and skipped; it never aborts the pass.
     */
    private suspend fun reapEmptyDirs(deletedFilePaths: List<String>): List<String> {
        val thisPassCandidates = deletedFilePaths.flatMap { ancestorDirsToRoot(it) }
        val candidates =
            (thisPassCandidates + trackingSet.pendingReaps())
                .distinct()
                // Deepest paths first so children are reaped before their parents.
                .sortedByDescending { it.count { ch -> ch == '/' } }
        if (candidates.isEmpty()) return emptyList()

        val trackedPaths = trackingSet.paths()
        val reaped = mutableListOf<String>()
        for (dir in candidates) {
            try {
                val children = provider.listChildren(dir)
                val stillPopulated = children.any { !it.deleted }
                if (stillPopulated) {
                    // A tracked file under this dir means it is legitimately
                    // populated → retire it. Otherwise the listing is treated as
                    // lagging → persist for a retry.
                    val prefix = "$dir/"
                    val hasTrackedChild = trackedPaths.any { it.startsWith(prefix) }
                    if (hasTrackedChild) {
                        trackingSet.removePendingReap(dir)
                    } else {
                        trackingSet.addPendingReap(dir)
                    }
                    continue
                }
                provider.delete(dir)
                trackingSet.removePendingReap(dir)
                reaped += dir
            } catch (e: org.krost.unidrive.FolderNotEmptyException) {
                // Provider refused a non-empty directory — leave it but retry
                // later. Not all providers raise this (Internxt cascades), which
                // is why the listChildren gate above is the primary guard.
                log.debug("Skipped reaping non-empty directory {}: {}", dir, e.message)
                trackingSet.addPendingReap(dir)
            } catch (e: Exception) {
                log.warn("Failed to reap empty directory {}: {}", dir, e.message)
                trackingSet.addPendingReap(dir)
            }
        }
        return reaped
    }

    // ---- observation helpers ----

    /**
     * What a path's [RemoteObservation] should be when it is ABSENT from
     * this pass's `remoteObs` map.
     *
     * On a FULL enumeration (`incremental == false`) absence is
     * authoritative: the remote genuinely has no such item, so we return
     * `exists = false` — the reconciler may then legitimately
     * `PropagateRemoteDelete` for a tracked path that vanished.
     *
     * On an INCREMENTAL pass (resumed from a persisted cursor) the delta
     * only carries items that *changed* (or were explicitly deleted).
     * A tracked path missing from the delta is UNCHANGED-PRESENT, not
     * gone — so we synthesize its observation from the tracking record
     * (`exists = true`, etag/size from the snapshot) and the reconciler
     * yields NoOp. Explicit deletions still arrive as a `deleted`-flagged
     * delta item, which [enumerateRemote] maps to `exists = false` in
     * `remoteObs`, so genuine removals still propagate. An untracked path
     * (`track == null`) can't be in the delta as a no-change item; treat
     * its absence as `exists = false`, same as the full case.
     *
     * EXCEPTION — `PendingDeleteLocal`: the remote already vanished and the
     * engine crashed before finishing the local delete (and removing the
     * row). The remote's deletion tombstone was already consumed by an
     * earlier delta page, so this resumed page OMITS the path — and
     * synthesizing `exists = true` would mask `remoteGone`, leaving the
     * stale local file un-reaped (and resurrected on the next local edit).
     * Preserve remote-absence for these rows so the reconciler re-derives
     * `PropagateRemoteDelete` (retry the local delete) or both-gone cleanup.
     * This is asymmetric on purpose: a `PendingDeleteRemote` row is driven
     * by `localGone` (unaffected by this synthesis) and self-heals — forcing
     * its absence instead could conclude both-gone and remove the row
     * WITHOUT deleting a remote that may not have been deleted yet.
     */
    private fun absentRemoteObservation(
        incremental: Boolean,
        track: TrackingRecord?,
    ): RemoteObservation =
        if (incremental && track != null && track.state != TrackState.PendingDeleteLocal) {
            RemoteObservation(
                exists = true,
                remoteFileId = track.remoteFileId,
                etag = track.remoteEtag,
                size = track.remoteSize,
                hash = track.remoteEtag,
                serverMtime = null,
            )
        } else {
            RemoteObservation(
                exists = false,
                remoteFileId = null,
                etag = null,
                size = null,
                hash = null,
                serverMtime = null,
            )
        }

    private fun scanLocal(): Map<String, LocalObservation> {
        if (!Files.isDirectory(syncRoot)) return emptyMap()
        val out = mutableMapOf<String, LocalObservation>()
        Files.walk(syncRoot).use { stream ->
            for (p in stream) {
                if (p == syncRoot) continue
                if (Files.isDirectory(p)) continue
                val rel = "/" + syncRoot.relativize(p).toString().replace('\\', '/')
                // Keep-local: don't even hash excluded junk. The syncOnce universe loop also
                // skips these paths, so this is a perf guard; the correctness guard is there.
                if (isExcluded(rel)) continue
                out[rel] = makeLocalObs(p)
            }
        }
        return out
    }

    /** True when [path] matches any configured exclude glob (shared default-ignore-list semantics). */
    private fun isExcluded(path: String): Boolean = excludePatterns.any { Reconciler.matchesGlob(path, it) }

    private fun makeLocalObs(p: Path): LocalObservation {
        val attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java)
        val size = attrs.size()
        val hash = hashFile(p)
        return LocalObservation(
            exists = true,
            hash = hash,
            size = size,
            mtime = attrs.lastModifiedTime().toInstant(),
            inode = attrs.fileKey()?.toString(),
        )
    }

    private fun observeLocal(path: String): LocalObservation {
        val p = syncRoot.resolve(stripLeadingSlash(path))
        return if (Files.exists(p) && !Files.isDirectory(p)) {
            makeLocalObs(p)
        } else {
            LocalObservation(exists = false, hash = null, size = null, mtime = null, inode = null)
        }
    }

    private suspend fun enumerateRemote(): RemoteEnumResult {
        if (!provider.capabilities().contains(Capability.Delta)) {
            log.warn(
                "Provider {} does not support Delta; tracking-set engine will see " +
                    "an empty remote view. EXPERIMENTAL — Delta integration has not " +
                    "been verified end-to-end against real providers.",
                provider.id,
            )
            // No remote view at all is treated as incomplete so the caller
            // doesn't interpret zero items seen as a universal-delete signal.
            return RemoteEnumResult(emptyMap(), complete = false, incremental = false)
        }
        // Resume from the cursor the last completed pass ended on (opaque to
        // the engine; the provider interprets it). Null on the first pass or
        // after a pass that never completed. A non-null cursor means this is
        // an INCREMENTAL delta: the provider returns only changed + explicitly-
        // deleted items, so a tracked path absent from the page is UNCHANGED-
        // PRESENT, not gone (see absentRemoteObservation).
        val resumeCursor: String? = trackingSet.loadDeltaCursor()
        val incremental = resumeCursor != null
        return try {
            walkDelta(resumeCursor, incremental)
        } catch (e: DeltaCursorExpiredException) {
            // The resumed cursor aged out / the drive re-keyed (Graph 410). We
            // lost delta continuity — arbitrary changes INCLUDING deletions
            // happened in the gap and will never arrive incrementally. Recover
            // by clearing the cursor and re-enumerating the FULL inventory from
            // a null cursor (incremental = false), so genuine deletes during the
            // stale window are reaped and unchanged paths are not. A full pass
            // that itself fails falls back to complete = false (deletes
            // suppressed) — never throw out of enumerateRemote.
            log.warn(
                "Delta cursor expired ({}); clearing it and re-enumerating the full inventory.",
                e.message,
            )
            trackingSet.saveDeltaCursor(null)
            try {
                walkDelta(cursor = null, incremental = false)
            } catch (e2: ProviderException) {
                log.warn("Full re-enumeration after cursor expiry failed; marking pass incomplete: ${e2.message}")
                RemoteEnumResult(emptyMap(), complete = false, incremental = false)
            }
        }
    }

    /**
     * Walk the provider delta from [cursor], accumulating remote observations.
     * Persists the cursor the walk ends on iff the walk saw the full inventory
     * (`complete == true`). A mid-loop [ProviderException] marks the pass
     * incomplete (deletes suppressed upstream) and leaves the prior persisted
     * cursor untouched. A [DeltaCursorExpiredException] is NOT swallowed here —
     * it propagates to [enumerateRemote] which drives the full-resync recovery.
     */
    private suspend fun walkDelta(
        cursor: String?,
        incremental: Boolean,
    ): RemoteEnumResult {
        val out = mutableMapOf<String, RemoteObservation>()
        var nextCursor: String? = cursor
        var complete = true
        try {
            do {
                val page = provider.delta(nextCursor)
                if (!page.complete) {
                    complete = false
                }
                for (item in page.items) {
                    if (item.isFolder) continue
                    out[item.path] =
                        if (item.deleted) {
                            // Explicit deletion signal (OneDrive maps
                            // @microsoft.graph.removed / deleted facets onto
                            // CloudItem.deleted). Record absence so a tracked
                            // path still produces PropagateRemoteDelete. On a
                            // full pass this is equivalent to omitting it.
                            RemoteObservation(
                                exists = false,
                                remoteFileId = item.id,
                                etag = null,
                                size = null,
                                hash = null,
                                serverMtime = null,
                            )
                        } else {
                            RemoteObservation(
                                exists = true,
                                remoteFileId = item.id,
                                etag = item.hash,
                                size = item.size,
                                hash = item.hash,
                                serverMtime = item.modified,
                            )
                        }
                }
                nextCursor = page.cursor
            } while (page.hasMore)
        } catch (e: DeltaCursorExpiredException) {
            throw e // recovery is enumerateRemote's responsibility, not a generic incomplete
        } catch (e: ProviderException) {
            log.warn("Remote enumeration failed mid-loop; marking pass incomplete: ${e.message}")
            complete = false
        }
        // Only advance the persisted cursor on a pass that saw the full
        // inventory. An incomplete pass leaves the prior cursor in place so
        // the next pass re-enumerates safely (preserves the delete-suppression
        // backup path's safety contract).
        if (complete) {
            trackingSet.saveDeltaCursor(nextCursor)
        }
        return RemoteEnumResult(out, complete, incremental)
    }

    private suspend fun observeRemote(path: String): RemoteObservation =
        try {
            val item = provider.getMetadata(path)
            RemoteObservation(
                exists = !item.deleted,
                remoteFileId = item.id,
                etag = item.hash,
                size = item.size,
                hash = item.hash,
                serverMtime = item.modified,
            )
        } catch (_: Exception) {
            RemoteObservation(
                exists = false,
                remoteFileId = null,
                etag = null,
                size = null,
                hash = null,
                serverMtime = null,
            )
        }

    private fun hashFile(p: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stripLeadingSlash(p: String): String = if (p.startsWith("/")) p.substring(1) else p
}

/** Outcome of a single applied action. */
enum class ApplyOutcome { SUCCESS, FAILED, SKIPPED }

data class AppliedAction(
    val action: ReconcileAction,
    val outcome: ApplyOutcome,
    val note: String?,
)

/** Per-pass summary suitable for `ts sync` / `ts status` rendering. */
data class PassReport(
    /** The full action list before [BatchGuard] / incomplete-enumeration suppression. */
    val plan: List<ReconcileAction>,
    /** What the engine actually attempted (deletes removed if guard tripped OR enumeration was incomplete). */
    val effectivePlan: List<ReconcileAction>,
    /** Per-action outcomes (empty in dry-run). */
    val applied: List<AppliedAction>,
    /** Paths adopted on this pass (both-sides-existed-with-matching-content). */
    val adopted: List<String>,
    /** Collisions surfaced for `ts claim` follow-up. */
    val collisions: List<ReconcileAction.ReportCollision>,
    /** The BatchGuard verdict; useful for rendering operator-facing warnings. */
    val guardVerdict: BatchGuard.Verdict,
    /**
     * False if remote enumeration didn't see the full inventory
     * (`ProviderException` mid-loop, a `DeltaPage.complete=false`, or no Delta
     * capability at all). When false, the engine suppresses delete actions to
     * avoid propagating spurious deletes from a transient failure.
     */
    val remoteEnumerationComplete: Boolean = true,
    /** Tracked paths removed because both local and remote vanished (spec "both gone" cleanup). */
    val cleanedUp: List<String> = emptyList(),
    /**
     * Remote directories deleted because this pass's file deletions emptied them.
     * Apply-only (empty in dry-run); each was verified empty via `listChildren`
     * before deletion. Deepest-first.
     */
    val reapedDirs: List<String> = emptyList(),
) {
    fun deleteCount(): Int =
        plan.count {
            it is ReconcileAction.PropagateLocalDelete || it is ReconcileAction.PropagateRemoteDelete
        }
}

/** Internal result type for [TrackingEngine.enumerateRemote]. */
private data class RemoteEnumResult(
    val observations: Map<String, RemoteObservation>,
    val complete: Boolean,
    /**
     * True iff this pass resumed from a persisted (non-null) delta cursor —
     * i.e. the provider returned an INCREMENTAL delta (only changed +
     * explicitly-deleted items). False on the first pass / post-reset, when
     * the delta is a FULL enumeration. SEPARATE from [complete]: a pass can
     * be incremental yet incomplete, or full yet complete.
     */
    val incremental: Boolean,
)
