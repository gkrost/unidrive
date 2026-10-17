package org.krost.unidrive.engine

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.engine.RemoteGather.Companion.applyReverseTop
import org.krost.unidrive.engine.RemoteGather.RemoteMerge
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.EnumerationStatus
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The one-way remote -> state.db refresh a mount serves its view from (#560 U2b), moved
 * from `SyncEngine.enumerateRemoteIntoState` unchanged: one attempt at a time, its
 * progress and status on [tracker], the failure streak the daemon's poller backs off
 * from, and the reap rules — a remote deletion flips a row only on a complete
 * enumeration, a bulk disappearance waits for a second complete one, and a path whose
 * hydration cache may hold the only copy of an edit is never reaped.
 *
 * The gather itself, the cursor and the state commit belong to [gather]; the hydration
 * layer's view of its own uploads comes in through [reapGuards].
 */
class RemoteEnumeration(
    private val gather: RemoteGather,
    private val db: StateDatabase,
    // What a client may ask about the enumeration (see status). The daemon's poller
    // records on the same tracker when it tries again after a failure.
    val tracker: EnumerationTracker,
    private val reapGuards: ReapGuards,
    // The engine's logger, so the moved log lines keep their logger name.
    private val log: Logger,
    // #147: how many remote rows one merge transaction upserts. Tests drive chunk
    // boundaries with a handful of items; production uses the companion default.
    private val mergeChunkRows: Int = MERGE_CHUNK_ROWS,
) {
    /**
     * What the reap asks the hydration layer before it evicts a cache file. Both are
     * late-bound in the CLI wiring (the hydration layer is built after the engine).
     */
    class ReapGuards(
        // #301: whether a background hydration upload of the path is queued or in
        // flight (an open_write returned Ok but its upload has not landed yet). A
        // queued edit's only copy is never deleted out from under its upload.
        val uploadInFlight: (path: String) -> Boolean = { false },
        // The hydration-cache file of a logical path.
        val cachePathOf: (path: String) -> Path,
    ) {
        /**
         * Whether the row at [path] ([row] is its alive row, if any) may hold the only copy of an edit that has
         * not reached the cloud, so that nothing may drop the row or its cache copy (#301):
         * - an upload of the path is queued or in flight ([uploadInFlight]);
         * - the row never reached the cloud (`SyncEntry.isPendingUpload`);
         * - the hydration cache holds a copy newer than the row's last-synced watermark (an edit whose upload
         *   crashed or failed; the co-daemon's recovery scanner replays exactly this watermark).
         * A cache path that cannot be resolved or read counts as no copy. The enumeration's reap and
         * `refresh.run`'s reset ask the same question here.
         */
        fun holdsUnsyncedEdit(
            path: String,
            row: SyncEntry?,
        ): Boolean {
            if (uploadInFlight(path)) return true
            if (row == null) return false
            if (row.isPendingUpload) return true
            // A folder has no bytes to lose: its cache entry is a directory whose time moves with whatever is put into it
            // or taken out of it (the cache copy of a row that moved, say), and those files are guarded by rows of their
            // own. Read as an edit it would hold the reap of a folder for good, the trash root of an emptied trash included.
            if (row.isFolder) return false
            return runCatching {
                val cachePath = cachePathOf(path)
                Files.exists(cachePath) && Files.getLastModifiedTime(cachePath).toMillis() > row.lastSynced.toEpochMilli()
            }.getOrDefault(false)
        }
    }

    // Single-flight guard across ALL callers: the --poll-interval poller, the
    // sync.enumerate verb (EnumerateRpcHandler), and mount-routed refresh.run
    // (RefreshRpcHandler calls the engine directly). Per-handler guards don't
    // serialize across handlers, so two passes could otherwise race delta_cursor
    // promotion and deferredMissing corroboration state. A caller that loses the
    // CAS is a no-op (skipped=true).
    private val enumerateInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    // Paths a prior COMPLETE enumeration flagged missing-but-deferred under the
    // bulk-disappearance corroboration guard (mount-view-refresh-design.md §3.2).
    // Carried in-memory across enumerations on a long-lived daemon engine; a path
    // is reaped only once a second consecutive complete enumeration still shows it
    // missing. Resets on restart (conservative: re-defers).
    private var deferredMissing: Set<String> = emptySet()

    // #301: paths whose reap was deferred and already warned about (see the reap).
    private val deferredReapWarned: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()


    /**
     * One-way remote→state.db refresh for view consumers (the FUSE mount). Reuses the remote
     * gather + state.db upsert, but NEVER scans sync_root, NEVER plans/executes a local→remote
     * delete, and NEVER evaluates the empty-sync_root / max_delete_* guards. Remote-observed
     * deletions flip state.db rows only on a COMPLETE enumeration (see reaping below). See
     * docs/dev/specs/mount-view-refresh-design.md.
     */
    suspend fun enumerate(reset: Boolean): EnumerateResult {
        // Single-flight: a caller that loses the CAS returns immediately as a no-op so
        // it can never run a concurrent pass against the same engine/DB state (cursor
        // promotion, corroboration). The winning pass is already refreshing the view.
        if (!enumerateInFlight.compareAndSet(false, true)) {
            return EnumerateResult(ok = true, skipped = true)
        }
        return try {
            enumerateRemoteIntoStateLocked(reset)
        } finally {
            enumerateInFlight.set(false)
        }
    }

    // Records the attempt for enumerationStatus: its start, its end, and a failure that escapes. The
    // provider failures that end as an EnumerateResult are recorded where they are caught.
    //
    // #517 R3: the failure side of FAILURE_STREAK_KEY — one write per
    // failed gather, read by the daemon's enumerate poller at start.
    // #586: the provider refused the credentials (raised only after its own token refresh failed).
    private fun isCredentialRejection(e: Throwable): Boolean = e is AuthenticationException

    /**
     * #878/#900: the machine-readable cause of a failed attempt for `last_error_code`. An API error carries its
     * HTTP status (`http_408`), a rejected authentication is `auth_rejected`, a transport failure names its
     * class (`timeout`, `dns`, `tls`, `connect`, `reset`, `io`) through the attempt classifier, an answer that
     * arrived but cannot be read is `decode`, and anything else is `internal`. So `io` only means transport I/O.
     */
    private fun errorCodeOf(e: Exception): String {
        if (e is AuthenticationException) return "auth_rejected"
        if (e is ProviderException && e.httpStatus != null) return "http_" + e.httpStatus
        val transport = AttemptOutcome.ofFailure(e)
        if (transport != AttemptOutcome.IO) return transport.wire
        val chain = generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }.take(CAUSE_DEPTH).toList()
        val responseStatus = chain.asSequence().drop(1).filterIsInstance<ProviderException>().mapNotNull { it.httpStatus }.firstOrNull()
        return when {
            chain.any { it is java.io.IOException } -> transport.wire
            chain.any { it.isDecodeFailure() } -> "decode"
            responseStatus != null -> "http_$responseStatus"
            else -> "internal"
        }
    }

    // kotlinx.serialization is not on this module's classpath: its SerializationException (and the JSON
    // decoder's subclasses of it) are recognised by name along the superclass chain.
    private fun Throwable.isDecodeFailure(): Boolean = generateSequence<Class<*>>(javaClass) { it.superclass }.any { it.simpleName == "SerializationException" }

    private fun recordEnumerateFailureStreak() {
        val streak = (db.getSyncState(FAILURE_STREAK_KEY)?.toIntOrNull() ?: 0) + 1
        db.setSyncState(FAILURE_STREAK_KEY, streak.toString())
    }

    private suspend fun enumerateRemoteIntoStateLocked(reset: Boolean): EnumerateResult {
        tracker.begin()
        try {
            val result = runEnumeration(reset)
            if (result.ok) tracker.succeeded()
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracker.failed(e.message ?: e.javaClass.simpleName, authRejected = isCredentialRejection(e), code = errorCodeOf(e))
            throw e
        } finally {
            // A no-op once the attempt succeeded or failed; a cancelled one is neither.
            tracker.aborted()
        }
    }

    /**
     * What a client may be told about the enumeration right now. A running attempt is answered from
     * memory alone: state.db is held by the batch that saves the result, and a status request must
     * not wait for it. Otherwise the cursor decides whether the view is still incomplete, and the
     * last completed scan, when this process has not run one, comes from state.db.
     */
    fun status(): EnumerationStatus {
        val status = tracker.snapshot()
        if (status.state == EnumerationStatus.State.RUNNING) return status
        return runCatching {
            status.copy(
                first = db.getSyncState("delta_cursor").isNullOrEmpty(),
                lastScanComplete = db.getSyncState("pending_cursor_complete")?.toBooleanStrictOrNull(),
                unreachableItems = db.getSyncState(RemoteGather.UNREACHABLE_ITEMS_KEY)?.toIntOrNull()?.takeIf { it > 0 },
                droppedItems = db.getSyncState(RemoteGather.DROPPED_ITEMS_KEY)?.toIntOrNull()?.takeIf { it > 0 },
                lastSuccessAtMs =
                    status.lastSuccessAtMs
                        ?: db.getSyncState("last_full_scan")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }.getOrDefault(status)
    }

    // What the last complete full enumeration found, as the next full one's estimate.
    private fun previousFullEnumeration(): EnumerationTracker.Expected? {
        val items = db.getSyncState(FULL_ENUMERATION_ITEMS_KEY)?.toIntOrNull() ?: return null
        return EnumerationTracker.Expected(
            items = items,
            folders = db.getSyncState(FULL_ENUMERATION_FOLDERS_KEY)?.toIntOrNull(),
            durationMs = db.getSyncState(FULL_ENUMERATION_MS_KEY)?.toLongOrNull(),
        )
    }

    private fun recordFullEnumeration(
        remoteChanges: Map<String, CloudItem>,
        listingMs: Long,
    ) {
        val live = remoteChanges.values.filter { !it.deleted }
        db.setSyncState(FULL_ENUMERATION_ITEMS_KEY, live.size.toString())
        db.setSyncState(FULL_ENUMERATION_FOLDERS_KEY, live.count { it.isFolder }.toString())
        db.setSyncState(FULL_ENUMERATION_MS_KEY, listingMs.toString())
    }

    private suspend fun runEnumeration(reset: Boolean): EnumerateResult {
        // reset clears only delta_cursor (NOT db.resetAll) so a gather that then fails never
        // leaves the mount serving an empty view. A reset forces a full re-enumeration whose
        // complete-reap below sweeps stale rows (mark-and-sweep), with no empty-view window.
        gather.applyScopeTransition()
        if (reset) db.setSyncState("delta_cursor", "")
        // Only a full listing is measured against a previous one; a delta of a few items must not
        // inherit the total of the whole drive.
        val cursorEmpty = db.getSyncState("delta_cursor").isNullOrEmpty()
        // The recovery latch as the pass starts: while it is set, this pass lists the whole drive because the last scan was incomplete.
        val recoveryPass = db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)?.equals("true", ignoreCase = true) == true
        tracker.baseline(
            first = cursorEmpty,
            expected = if (cursorEmpty || gather.deltaIsFullListing) previousFullEnumeration() else null,
            recovery = recoveryPass,
        )
        val gatherStartedAt = Instant.now()
        // The tracked rows before the gather: what a full listing is measured against for the recovery latch (see below).
        val preGatherTrackedRows = db.getEntryCount()
        val remoteChanges: Map<String, CloudItem> =
            try {
                gather.gather(progress = tracker).filterKeys { gather.isTracked(it) }
            } catch (e: ProviderException) {
                tracker.failed(e.message ?: e.javaClass.simpleName, authRejected = isCredentialRejection(e), code = errorCodeOf(e))
                recordEnumerateFailureStreak()
                return EnumerateResult(ok = false, error = e.message)
            }
        val listingMs = tracker.saving(remoteChanges.count { !it.value.deleted })
        db.setSyncState(FAILURE_STREAK_KEY, "0")
        // Completeness is recorded in sync_state by the gather, not on its return value.
        val complete = db.getSyncState("pending_cursor_complete")?.equals("true", ignoreCase = true) ?: true
        val canonicalToLocalTop = gather.canonicalToLocalTop(remoteChanges)
        // Bulk-disappearance corroboration guard (spec §3.2). A complete enumeration that
        // would flip more than max(50, 20% of tracked rows) to deleted is suspicious (e.g.
        // Internxt /files lag); reap only paths a PRIOR complete enumeration also saw
        // missing, defer the rest, and carry the candidate set to the next enumeration.
        val trackedRows = db.getEntryCount()
        val bulkThreshold = maxOf(BULK_REAP_ABSOLUTE, (trackedRows * BULK_REAP_FRACTION).toInt())
        var reaped = 0
        val reapedViewPaths = mutableSetOf<String>()
        var nextDeferred = emptySet<String>()
        // Whether the corroboration guard held back a reap this pass (see the recovery latch below).
        var reapsDeferred = false
        // #149: cache files are evicted AFTER the batch commits — a filesystem
        // delete is a non-transactional side effect and must not lengthen the
        // SQLite lock window.
        val cacheEvictions = mutableListOf<Triple<String, Path, Long?>>()
        // #595: only the rows the merge actually changed (new, or a different remote id,
        // hash, size or modified time) invalidate the view — the Internxt delta rewinds
        // its cursor on purpose and re-delivers the same items every poll, which used to
        // re-invalidate unchanged paths forever. The merge also reports renames (an item
        // re-delivered under a new path used to replace its own row silently, leaving
        // the old path in the view unannounced). The merge's paths are engine-local;
        // map them through the alias reverse map like the reap paths.
        var upsertedViewPaths: Set<String> = emptySet()
        var movedView: List<RemoteMerge.Move> = emptyList()
        // #147: the merge runs in chunks of separate transactions. One batch over a full
        // enumeration held StateDatabase's monitor for the whole merge — a reset over
        // hundreds of thousands of rows stalled every concurrent mount read for the
        // run's length. Each chunk's commit releases the monitor, so a reader waits at
        // most one chunk. The merge is row-at-a-time and the gather has already
        // persisted the cursor before this point, so a chunk boundary adds no new crash
        // exposure: untouched rows keep their previous truth and the next enumeration
        // re-derives them. Entries sort ancestors-first so the #601 parent guard's DB
        // fallback reads a parent row this pass already updated, and the alias map
        // derives once from the full change set (see the RemoteGather overload) — a
        // chunk on its own may not contain the top-level folder an aliased path needs.
        val merges =
            remoteChanges.entries
                .sortedBy { it.key }
                .chunked(mergeChunkRows)
                .map { chunk ->
                    val chunkMap = buildMap<String, CloudItem>(chunk.size) { chunk.forEach { put(it.key, it.value) } }
                    db.batch { gather.updateRemoteEntries(chunkMap, canonicalToLocalTop, remoteChanges) }
                }
        upsertedViewPaths = buildSet { merges.forEach { addAll(it.changedPaths) } }
        movedView =
            merges.flatMap { merge ->
                merge.moved.map {
                    RemoteMerge.Move(applyReverseTop(it.from, canonicalToLocalTop), applyReverseTop(it.to, canonicalToLocalTop))
                }
            }
        // A row that moved takes its cache copy along. The row keeps its hydration state at the new path, and the copy
        // is found by path: left behind it would be an orphan, and an edit in it (a file moved into the trash
        // namespace while it held unsent changes) would no longer belong to any row.
        for (merge in merges) for (move in merge.moved) carryCacheCopy(move.from, move.to)
        // The reap needs the merge committed and runs as its own transaction; a crash
        // between the chunks and this batch leaves the reap to the next enumeration.
        db.batch {
            if (complete) {
                // Reap ONLY on a complete enumeration (spec §3.1). The deleted items are
                // already present in remoteChanges: RemoteGather.gather runs
                // detectMissingAfterFullSync on a full (cursor-null) gather, injecting
                // deleted=true CloudItems for DB rows absent from the live set; incremental
                // gathers carry provider tombstones the same way. updateRemoteEntries skips
                // them, so we flip state.db here directly — never via provider.delete.
                val missingNow = remoteChanges.filterValues { it.deleted }.keys
                val bulk = missingNow.size > bulkThreshold
                val toReap =
                    if (bulk) missingNow.intersect(deferredMissing) else missingNow
                for (path in toReap) {
                    // #301: refuse to reap a path whose hydration cache may hold the
                    // only copy of user bytes. A queued or in-flight upload means an
                    // edit written through the mount has not landed yet; a cache file
                    // newer than the row's last-synced watermark means the same for an
                    // edit whose upload crashed or failed (the co-daemon's recovery
                    // scanner replays exactly this watermark). Deleting the cache in
                    // that window destroyed the bytes everywhere — the upload then hit
                    // a missing cache path, and the remote copy (if any) was stale.
                    // Defer the whole reap: the row stays alive and the next complete
                    // enumeration re-evaluates once the upload has landed (or failed).
                    val row = db.getEntry(path)
                    // A row whose path does not resolve inside the cache has no cache copy to
                    // protect or evict: its cache side is skipped (logged once), the row is reaped.
                    val cachePath = CachePaths.forRow(path, log, reapGuards.cachePathOf)
                    if (reapGuards.holdsUnsyncedEdit(path, row)) {
                        // The daemon enumerates every poll interval: warn once per path, not once per poll.
                        val msg = "enumerate: deferring reap of {} — its hydration cache may hold the only copy of an un-uploaded edit"
                        if (deferredReapWarned.add(path)) log.warn(msg, path) else log.debug(msg, path)
                        continue
                    }
                    deferredReapWarned.remove(path)
                    db.markDeleted(path)
                    if (cachePath != null) {
                        cacheEvictions.add(
                            Triple(path, cachePath, runCatching { Files.getLastModifiedTime(cachePath).toMillis() }.getOrNull()),
                        )
                    }
                    reapedViewPaths.add(applyReverseTop(path, canonicalToLocalTop))
                    reaped++
                }
                if (bulk) {
                    val deferred = missingNow - toReap
                    nextDeferred = missingNow
                    if (deferred.isNotEmpty()) {
                        reapsDeferred = true
                        log.warn(
                            "enumerate: bulk disappearance ({} paths > threshold {}); " +
                                "deferring {} uncorroborated path(s) to the next complete enumeration",
                            missingNow.size,
                            bulkThreshold,
                            deferred.size,
                        )
                    }
                }
            }
        }
        // #149: same per-file behaviour as before (errors swallowed — the row
        // flip is the truth and the cache copy is a disk-space concern), just
        // outside the transaction now.
        // The lock window is gone, so a hydration write may have recreated the path
        // in between (#301 class): skip the delete when an upload is queued for it or
        // the cache file is no longer the one the reap decided on.
        for ((path, cachePath, mtimeAtReap) in cacheEvictions) {
            runCatching {
                val unchanged = runCatching { Files.getLastModifiedTime(cachePath).toMillis() }.getOrNull() == mtimeAtReap
                if (!reapGuards.uploadInFlight(path) && unchanged) Files.deleteIfExists(cachePath)
            }
        }
        // A bulk disappearance must be corroborated by CONSECUTIVE complete enumerations.
        // On a complete pass, carry this pass's candidate set forward. On an INCOMPLETE
        // pass, RESET the deferred set: an incomplete pass means we had no clean run, so
        // a later complete pass must re-defer rather than reap against stale corroboration
        // state (conservative — favors not reaping, since reaping evicts cache + marks
        // deleted).
        deferredMissing = if (complete) nextDeferred else emptySet()
        // The reap may have removed the trash root's row (nothing is in the trash any more).
        gather.refreshTrash()
        gather.promotePendingCursor()
        // Last, after the merge, the reap and the cursor: a crash before this point leaves the latch set, and the next
        // pass lists the drive again.
        settleRecoveryLatch(remoteChanges, preGatherTrackedRows, reapsDeferred)
        // Two full listings in a row that end incomplete with the same tracked items missing: accept them, do not list again.
        gather.boundIncompleteRecovery(remoteChanges, gatherStartedAt)
        // A complete full listing becomes the estimate for the next one (the gather recorded whether it was full).
        if (complete && db.getSyncState("last_gather_full") == "true") recordFullEnumeration(remoteChanges, listingMs)
        // Notify the view-invalidation sink once, with all paths that changed in state.db
        // during this pass. Only fires when something actually changed so quiescent polls
        // do not produce spurious cache-invalidation traffic (#595: a re-delivering delta
        // no longer counts as "something"). The sink is a plain lambda so
        // app:hydration can wire HydrationEvent.ViewInvalidated without creating a circular
        // import (app:hydration depends on app:sync, not vice versa).
        val upserted = upsertedViewPaths.size
        if (upserted > 0 || reaped > 0) {
            val changedPaths: Set<String> = upsertedViewPaths + reapedViewPaths
            gather.invalidateView(changedPaths, false, movedView)
        }
        val recoveryPending = db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)?.equals("true", ignoreCase = true) == true
        return EnumerateResult(ok = true, upserted = upserted, reaped = reaped, complete = complete, recoveryPending = recoveryPending)
    }

    // The recovery latch (RemoteGather.INCOMPLETE_ENUMERATION_KEY). A gather that ends incomplete sets it, and while it is
    // set every gather lists the whole drive from zero. Only the sync pass cleared it, and a mount daemon never runs that
    // pass, so one incomplete scan made every later poll a full relisting for ever, complete or not. The enumeration now
    // asks the same question as the sync pass (RemoteGather.settleRecoveryLatch): a complete, full, unscoped listing that
    // the remote-shrink guard accepts ends the recovery.
    //
    // Scope: the enumeration has no scope of its own per run. A standing scope is the tracked scope, and a complete
    // listing of it is the complete inventory of everything the profile tracks, which is what the sweep judged.
    //
    // A deferred reap keeps the latch (reapsDeferred): the bulk-disappearance guard held back deletions it could not
    // corroborate, so this pass is no trusted inventory yet, and the corroboration is a SECOND complete FULL listing:
    // an incremental pass would not report those paths missing again, and the candidates would be forgotten. A reap
    // deferred for a hydration-cache edit (holdsUnsyncedEdit) does not keep it: that waits for a local upload, not for the
    // remote's inventory, and holding the latch for it would relist the drive for as long as the upload is stuck.
    private fun settleRecoveryLatch(
        remoteChanges: Map<String, CloudItem>,
        preGatherTrackedRows: Int,
        reapsDeferred: Boolean,
    ) {
        val fullListing = db.getSyncState("last_gather_full") == "true"
        val observedAlive = remoteChanges.values.count { !it.deleted }
        val shrinkWarning = if (fullListing) RemoteGather.remoteShrinkWarningOrNull(observedAlive, preGatherTrackedRows) else null
        if (shrinkWarning != null && db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)?.equals("true", ignoreCase = true) == true) {
            log.warn(
                "enumerate: the full listing observed only {} live item(s) against {} tracked rows; keeping the recovery latch",
                observedAlive,
                preGatherTrackedRows,
            )
        }
        gather.settleRecoveryLatch(scoped = false, shrinkWarning = shrinkWarning, reapsDeferred = reapsDeferred)
    }

    // The cache copy of the row that moved from [from] to [to]. A copy already at the destination is never replaced, a
    // move that fails is logged and leaves the copy where it is: neither loses bytes.
    private fun carryCacheCopy(
        from: String,
        to: String,
    ) {
        val source = CachePaths.forRow(from, log, reapGuards.cachePathOf) ?: return
        val target = CachePaths.forRow(to, log, reapGuards.cachePathOf) ?: return
        try {
            if (!Files.exists(source) || Files.exists(target)) return
            Files.createDirectories(target.parent)
            Files.move(source, target)
        } catch (e: java.io.IOException) {
            log.warn("enumerate: the cache copy of {} could not follow the row to {}: {}", from, to, e.message)
        }
    }

    companion object {
        private const val CAUSE_DEPTH = 8
        // #517 R3: sync_state key counting consecutive enumerations that ended in a
        // provider failure. The daemon's enumerate poller seeds its backoff from it
        // at start, so a restart into a known-bad remote doesn't re-run the doomed
        // cycle at full cadence. Reset to "0" by any gather that comes back.
        const val FAILURE_STREAK_KEY: String = "enumerate_failure_streak"

        // Bulk-disappearance corroboration guard (mount-view-refresh-design.md §3.2):
        // a complete enumeration flipping more than max(absolute, fraction × tracked
        // rows) to deleted defers reaping until a second complete enumeration corroborates.
        const val BULK_REAP_ABSOLUTE: Int = 50
        const val BULK_REAP_FRACTION: Double = 0.20

        // #147: rows per merge transaction. Bounded so a concurrent mount read waits at
        // most one chunk's commit rather than a whole-enumeration batch.
        const val MERGE_CHUNK_ROWS: Int = 5_000

        // sync_state keys: what the last complete full enumeration found (live items, live folders)
        // and how long its listing took, the estimate behind the next one's ETA. Not the
        // last_scan_*_remote hints: a sync pass writes those for deltas too, so they are no total.
        const val FULL_ENUMERATION_ITEMS_KEY: String = "last_full_enumeration_items"
        const val FULL_ENUMERATION_FOLDERS_KEY: String = "last_full_enumeration_folders"
        const val FULL_ENUMERATION_MS_KEY: String = "last_full_enumeration_ms"
    }
}
