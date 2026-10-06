package org.krost.unidrive.engine

import kotlinx.coroutines.withContext
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaCursorExpiredException
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.http.Priority
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncScope
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.time.Instant

/**
 * The remote gather of one profile (#560 U2b): provider delta paging into a map of
 * changes, the per-page staging in `scan_staging` and the pending/delta cursor in
 * `sync_state`, the collision rule for same-named remote items (#401), the
 * absence sweep of a full listing, the tracked-scope transition, and the commit of
 * gathered items into `sync_entries`.
 *
 * Both paths use it: the mirror engine's sync pass (`SyncEngine.syncOnce`, buffered
 * gather; its streaming gather reuses the admission, path resolution, collision
 * report and absence sweep) and the mount's enumeration ([RemoteEnumeration]). The
 * code moved from `SyncEngine` unchanged; what it read from the engine's private
 * fields now comes in through the constructor.
 *
 * State it carries across passes, for the lifetime of the engine: the paths the
 * engine uploaded recently ([markRecentlyUploaded]), the collided paths of the last
 * gather ([isCollided]) and whether the last first-sync adopted the remote cursor
 * without listing ([fastBootstrapActive]).
 */
class RemoteGather(
    private val provider: CloudProvider,
    private val db: StateDatabase,
    private val guard: RemoteOperationGuard,
    private val options: Options,
    private val listener: Listener,
    // #115: canonical top-level remote name -> the locale-aliased local folder that
    // stands for it. The alias rule reads the sync root and the XDG user dirs, so
    // the mirror engine supplies it; no alias means identity paths.
    private val localTopAliases: (remoteChanges: Map<String, CloudItem>) -> Map<String, String> = { emptyMap() },
    // The engine's logger, so the moved log lines keep their logger name.
    private val log: Logger,
) {
    /** What the gather is asked to do, fixed per engine. */
    data class Options(
        // The provider type, for the log lines only.
        val providerId: String = "",
        // UD-223: on a first sync, adopt the remote's current cursor without listing.
        val fastBootstrap: Boolean = false,
        // Ask the provider for shared items too, where it supports that.
        val includeShared: Boolean = false,
    )

    /**
     * Where the gather reports to. The mirror engine forwards to its progress reporter,
     * its skipped-ops log and its view-invalidation sink; every method defaults to a no-op.
     */
    interface Listener {
        /** The remote phase's running item count (the reporter's "remote" heartbeat). */
        fun onScanProgress(count: Int) {}

        /** An operator-facing warning. */
        fun onWarning(message: String) {}

        /** A skipped-ops.jsonl record; the listener drops it on a dry-run. */
        fun onSkippedOp(
            label: String,
            path: String,
            reason: String,
            dryRun: Boolean,
        ) {}

        /**
         * Rows changed in state.db: [changedPaths], or the whole view when [full].
         * [moved] carries the renames the merge detected (#595) — a consumer can move
         * a placeholder instead of deleting and recreating it; consumers that only
         * read [changedPaths] see both the old and the new path there regardless.
         */
        fun onViewInvalidated(
            changedPaths: Set<String>,
            full: Boolean,
            moved: List<RemoteMerge.Move> = emptyList(),
        ) {}
    }

    // #116: set true when a gather actually took the fast-bootstrap branch (cursor
    // adopted with zero enumeration). The mirror engine reads it in its apply pass
    // (adopt-on-name-match for top-level folders) and resets it at the start of
    // every sync pass.
    @Volatile
    var fastBootstrapActive = false

    /** True when state.db tracks [remotePath] (see [RemoteOperationGuard.isTracked]). */
    fun isTracked(remotePath: String): Boolean = guard.isTracked(remotePath)

    /** Whether every delta of the provider is a full listing. */
    val deltaIsFullListing: Boolean get() = provider.deltaIsFullListing

    /** #115: see the constructor's `localTopAliases`. */
    fun canonicalToLocalTop(remoteChanges: Map<String, CloudItem>): Map<String, String> = localTopAliases(remoteChanges)

    /** Tell the view consumer which rows changed. */
    fun invalidateView(
        changedPaths: Set<String>,
        full: Boolean,
        moved: List<RemoteMerge.Move> = emptyList(),
    ) = listener.onViewInvalidated(changedPaths, full, moved)

    /** #401: true when the last gather found two live remote items at [path]. */
    fun isCollided(path: String): Boolean = path in collidedPaths

    // Paths the engine itself uploaded, mapped to the upload instant. Consulted by
    // detectMissingAfterFullSync to defer the absence-implies-deletion verdict for a
    // path whose remote write the eventually-consistent delta feed hasn't reflected
    // yet — the re-upload churn class. Daemon-scoped in-memory state (same lifetime as
    // RemoteEnumeration.deferredMissing); pruned past RECENT_UPLOAD_REAP_GRACE so a genuinely-deleted
    // path is reaped once the window lapses and the map can't grow unbounded.
    private val recentlyUploaded = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    fun markRecentlyUploaded(path: String) {
        recentlyUploaded[path] = Instant.now()
    }

    private fun pruneRecentlyUploaded() {
        val cutoff = Instant.now().minus(RECENT_UPLOAD_REAP_GRACE)
        recentlyUploaded.entries.removeIf { it.value.isBefore(cutoff) }
    }

    // #401: paths the last gather flagged as collided — two live remote items resolving
    // to one path key. A path-addressed delete/move on one of these could hit either
    // twin, so apply refuses them unless the action carries a real remote id (#402).
    // Rebuilt by every gather: the collision exists for as long as the twins do.
    private val collidedPaths: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // #401/#309: the collision key folds case when the local filesystem is
    // case-insensitive, so case-only twins collide (and get reported) instead of
    // writing two rows that map to one local file. NFC is already applied by
    // resolveItemPath. Windows check mirrors SyncEngine.sameSyncRoot.
    private val foldGatherKeys: Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    private fun gatherKey(path: String): String = if (foldGatherKeys) path.lowercase() else path

    // #401: one collision path's winner and the twins it displaced.
    class CollisionRecord(
        val winner: CloudItem,
    ) {
        val losers = mutableListOf<CloudItem>()
    }

    /**
     * #401: deterministic admission of remote delta items into the gather map.
     *
     * Named justification (structural-safety property): reconcile keys are injective
     * and nothing the provider emits is dropped unreported. Replaces the gather's
     * `changes[path] = item` sites, where last-one-wins let the provider's emit
     * order decide which same-named item survived — the other was dropped silently.
     *
     * Where a key is already taken, the winner is folder > file > the id state.db
     * already tracks at that path > smallest id — a pure function of the two items
     * plus the DB, independent of emission order. The loser is recorded (reported
     * after the gather via [reportCollisions]) and dropped.
     *
     * @return the item now holding the key (the winner), so callers can tell whether
     *   [item] survived.
     */
    fun admit(
        changes: MutableMap<String, CloudItem>,
        keyToPath: MutableMap<String, String>,
        collisions: MutableMap<String, CollisionRecord>,
        item: CloudItem,
    ): CloudItem {
        val key = gatherKey(item.path)
        val incumbentPath = keyToPath[key]
        if (incumbentPath == null) {
            changes[item.path] = item
            keyToPath[key] = item.path
            return item
        }
        val incumbent = changes.getValue(incumbentPath)
        if (incumbent.id == item.id) {
            // Same remote item re-reported on a later page — a mid-gather remote edit
            // surfacing a newer version. Fresh data wins (pre-#401 last-wins), and a
            // version refresh is not a twin collision.
            changes[incumbentPath] = item
            keyToPath[key] = item.path
            return item
        }
        val winner = collisionWinner(incumbent, item)
        val loser = if (winner === incumbent) item else incumbent
        if (winner !== incumbent) {
            changes.remove(incumbentPath)
            changes[winner.path] = winner
            keyToPath[key] = winner.path
        }
        // A tombstone losing to the live replacement at its own path is a normal
        // create-after-delete sequence, not a twin — drop it without reporting.
        if (!loser.deleted) {
            collisions.getOrPut(winner.path) { CollisionRecord(winner) }.losers.add(loser)
        }
        return winner
    }

    // #401 winner rule: a live item beats a tombstone (the replacement at the same
    // path is the current truth); then folder beats file (the folder owns the path
    // space); then the id state.db already tracks at this path (stability across
    // runs — the engine keeps syncing the twin it has been syncing); then the
    // smallest id, so two never-tracked twins resolve identically under any emit
    // order.
    private fun collisionWinner(
        a: CloudItem,
        b: CloudItem,
    ): CloudItem =
        when {
            a.deleted != b.deleted -> if (b.deleted) a else b
            a.isFolder != b.isFolder -> if (a.isFolder) a else b
            else -> {
                val tracked = runCatching { db.getEntryByRemotePath(a.path)?.remoteId }.getOrNull()
                when {
                    tracked != null && a.id == tracked -> a
                    tracked != null && b.id == tracked -> b
                    a.id <= b.id -> a
                    else -> b
                }
            }
        }

    /**
     * #401: report what the gather suppressed. Per collision: WARN with both ids,
     * sizes and mtimes, `listener.onWarning`, a skipped-ops.jsonl entry; then the
     * run-level `sync_state` counters `status` renders. The loser ids come back so
     * the absence sweep (detectMissingAfterFullSync) keeps treating them as seen —
     * a suppressed twin must never be reaped via a path-addressed delete.
     * Deliberately does NOT mark the gather incomplete: that would suspend reaping
     * and new uploads for the whole profile for as long as one twin exists.
     */
    fun reportCollisions(
        collisions: Map<String, CollisionRecord>,
        dryRun: Boolean,
    ): Set<String> {
        collidedPaths.clear()
        collidedPaths.addAll(collisions.keys)
        if (collisions.isEmpty()) {
            db.setSyncState(REMOTE_COLLISIONS_KEY, "0")
            // clearSyncState is private to StateDatabase; an empty value reads the
            // same as absent ("0 collisions", no paths) through getSyncState.
            db.setSyncState(REMOTE_COLLISION_PATHS_KEY, "")
            return emptySet()
        }
        val losers = mutableSetOf<String>()
        for ((path, record) in collisions) {
            val w = record.winner
            for (l in record.losers) {
                losers.add(l.id)
                val msg =
                    "Remote path collision at $path: keeping id=${w.id} (size=${w.size}, mtime=${w.modified}); " +
                        "suppressing id=${l.id} (size=${l.size}, mtime=${l.modified}). The twin stays cloud-only — " +
                        "resolve or remove the duplicate in the cloud to sync it."
                log.warn(msg)
                listener.onWarning(msg)
                listener.onSkippedOp("remote-collision", path, "duplicate remote item; winner=${w.id} loser=${l.id}", dryRun)
            }
        }
        db.setSyncState(REMOTE_COLLISIONS_KEY, collisions.size.toString())
        db.setSyncState(REMOTE_COLLISION_PATHS_KEY, collisions.keys.take(COLLISION_PATHS_STATUS_LIMIT).joinToString("\t"))
        return losers
    }

    // [readOnly]: a dry-run preview, told to the provider through ScanContext.readOnly so it persists nothing.
    // [progress]: the tracker an enumeration reports to, fed by the page callbacks and by the provider.
    suspend fun gather(
        readOnly: Boolean = false,
        progress: EnumerationTracker? = null,
    ): Map<String, CloudItem> = withContext(Priority.Background) {
        val storedCursor = db.getSyncState("delta_cursor")
        val cursor = storedCursor?.ifEmpty { null }
        var isFullSync = cursor == null || provider.deltaIsFullListing
        var changes = mutableMapOf<String, CloudItem>()
        // #401: every `changes[path] = item` site goes through admit() so same-named
        // remote items cannot silently drop a twin (see the admit doc).
        val keyToPath = HashMap<String, String>()
        val collisions = HashMap<String, CollisionRecord>()
        fun admitChange(item: CloudItem) {
            admit(changes, keyToPath, collisions, item)
        }
        // After every page the gather holds: the reporter's heartbeat and the enumeration's item count.
        fun reportGathered() {
            listener.onScanProgress(changes.size)
            progress?.onItems(changes.size)
        }
        val providerProgress: ((org.krost.unidrive.ScanProgress) -> Unit)? = progress?.let { it::onProgress }

        // UD-223 fast-bootstrap: on first-sync only, adopt the remote's current
        // cursor without enumerating. Provider must declare FastBootstrap; otherwise
        // log a warning and fall through. On success, the subsequent full-sync
        // deletion sweep (detectMissingAfterFullSync) is skipped — no enumeration
        // means no authoritative item set to diff against, so we must NOT treat
        // absence as deletion.
        if (options.fastBootstrap && cursor == null) {
            if (Capability.FastBootstrap in provider.capabilities()) {
                // #532: the preview's bootstrap carries readOnly, so a dry-run --fast-bootstrap
                // stamps nothing in the provider's own storage (OneDrive's delta_last_seen).
                when (
                    val result =
                        provider.deltaFromLatest(
                            org.krost.unidrive.ScanContext(
                                resumeMarker = null,
                                resumedItems = emptyList(),
                                persistPage = { _, _ -> },
                                readOnly = readOnly,
                            ),
                        )
                ) {
                    is CapabilityResult.Success -> {
                        val page = result.value
                        for (item in page.items) {
                            val resolved = resolveItemPath(item) ?: continue
                            admitChange(resolved)
                        }
                        reportCollisions(collisions, readOnly)
                        // UD-223: promote the cursor directly. Bootstrap guarantees no transfers
                        // fire on this run (the action list is empty by construction), so the
                        // usual "pending → delta after zero failures" dance is skipped —
                        // otherwise syncOnce's `if (actions.isEmpty()) return` short-circuits
                        // the promotion and the next run re-bootstraps forever.
                        db.setSyncState("delta_cursor", page.cursor)
                        db.setSyncState("last_full_scan", Instant.now().toString())
                        // Invalidate any in-progress scan checkpoint. The offsets persisted
                        // in `scan_in_progress_marker` correspond to the previous gather's
                        // cursor (cursor=null full enum, or an earlier delta cursor); they
                        // index into a different result set than what the new cursor=now
                        // delta will return. Resuming with the old offsets would silently
                        // page past items modified in the seam. Clearing the staging slice
                        // is cheap (no live `sync_entries` rows touched).
                        db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)?.let { staleScanId ->
                            log.info(
                                "UD-223 fast-bootstrap: invalidating stale scan checkpoint scan={} marker={}",
                                staleScanId,
                                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER),
                            )
                            db.completeScan(staleScanId)
                        }
                        val stamp = Instant.now().toString()
                        val msg =
                            "UD-223 fast-bootstrap: adopted remote cursor as of $stamp. " +
                                "Items that already exist on the remote will stay invisible until they next mutate. " +
                                "Upload-direction sync is unaffected."
                        // The log line only — reporter.onWarning duplicated it on every CLI
                        // one-shot run (console appender + progress reporter, #532); the
                        // daemon runs with the Silent reporter either way.
                        log.warn(msg)
                        // #116: arm adopt-on-name-match for this run's apply pass.
                        fastBootstrapActive = true
                        return@withContext changes
                    }
                    is CapabilityResult.Unsupported -> {
                        log.warn(
                            "UD-223 fast-bootstrap requested but provider '{}' does not support it ({}). " +
                                "Falling back to full first-sync enumeration.",
                            options.providerId,
                            result.reason,
                        )
                    }
                }
            } else {
                log.warn(
                    "UD-223 fast-bootstrap requested but provider '{}' does not declare the capability. " +
                        "Falling back to full first-sync enumeration.",
                    options.providerId,
                )
            }
        }

        // If includeShared is requested but the provider doesn't actually support
        // delta-with-shared, silently fall back to plain delta — this is the
        // long-standing behaviour preserved across the UD-301 refactor.
        val useShared =
            options.includeShared &&
                Capability.DeltaShared in provider.capabilities()

        // UD-352: forward per-page progress to reporter.onScanProgress("remote", N).
        // The provider's delta() invokes this callback on each accumulated page
        // (where supported); the engine just fans it out to the reporter so the
        // heartbeat fires inside the gather loop instead of only at start/end.
        // Snapshot/all-at-once providers (HiDrive, rclone) leave this unused —
        // the engine still emits a final tick once gather returns. Note that
        // [provider.deltaWithShared] does not yet accept the callback (its
        // pagination path is OneDrive-only and ALREADY emits progress via the
        // outer loop in this method); only the plain [provider.delta] path
        // forwards it.
        val onPageProgress: (Int) -> Unit = { itemsSoFar ->
            listener.onScanProgress(itemsSoFar)
            progress?.onItems(itemsSoFar)
        }

        // UD-360: providers signal partial gathers via DeltaPage.complete=false.
        // We track that across pages and skip the absence-implies-deletion sweep
        // (detectMissingAfterFullSync) when any page was incomplete — otherwise
        // a transient subtree error on the provider side would synthesize bogus
        // DeleteLocal actions for every file under that subtree.
        var allComplete = true

        // Resumable-scan lifecycle: a non-null activeScanId means this gather
        // pass either resumes a prior daemon's interrupted scan or has just
        // started a fresh one. The scanContext threads the engine's staging
        // hooks into provider.delta(); on a successful return the staging
        // slice is cleared. A throw mid-scan leaves both the slice and the
        // checkpoint intact so the next launch can pick up from there.
        val activeScan = db.getActiveScan(staleThreshold = java.time.Duration.ofHours(SCAN_CHECKPOINT_STALE_HOURS))
        val resumedItems: List<CloudItem> =
            if (activeScan != null) db.loadStagedItems(activeScan.scanId) else emptyList()
        val scanId =
            activeScan?.scanId
                ?: db.beginScan(initialMarker = null)
        if (activeScan != null) {
            log.info(
                "Resuming Internxt-style scan id={} marker={} ({} previously-staged items)",
                scanId,
                activeScan.marker,
                resumedItems.size,
            )
        }
        val scanContext =
            org.krost.unidrive.ScanContext(
                resumeMarker = activeScan?.marker,
                resumedItems = resumedItems,
                persistPage = { items, marker -> db.persistScanPage(scanId, items, marker) },
                scopeRoots = guard.trackScope,
                readOnly = readOnly,
                onProgress = providerProgress,
            )

        suspend fun nextPage(c: String?): DeltaPage {
            val page =
                if (useShared) {
                    when (val r = provider.deltaWithShared(c)) {
                        is CapabilityResult.Success -> r.value
                        is CapabilityResult.Unsupported -> provider.delta(c, onPageProgress, scanContext)
                    }
                } else {
                    provider.delta(c, onPageProgress, scanContext)
                }
            // UD-751: single canonical "Delta: N items, hasMore=X" line, lifted out
            // of the five providers that used to emit the same data per-page.
            log.debug("Delta: {} items, hasMore={}", page.items.size, page.hasMore)
            if (!page.complete) {
                allComplete = false
            }
            return page
        }

        // Persist the running completeness flag alongside the cursor.
        // Promotion now happens unconditionally (see promotePendingCursor):
        // pinning the cursor at its prior value on an incomplete sweep
        // forced a full re-scan every launch on a hot account whenever any
        // subtree returned 500/503. The flag is still recorded so the doctor
        // surface and warnings can tell the user the last scan was incomplete
        // and recommend `--reset` if the skipped subtree matters.
        //
        // The monotonicity floor (cursor never regresses below the prior
        // value) lives inside each provider's delta() — only providers know
        // whether their cursor is timestamp-comparable (Internxt) or opaque
        // (OneDrive's @odata.nextLink URLs), and only providers can do the
        // comparison correctly.
        fun persistPendingCursor(cursor: String) {
            db.setSyncState("pending_cursor", cursor)
            db.setSyncState("pending_cursor_complete", if (allComplete) "true" else "false")
        }

        // #110: a persisted delta cursor can age out (OneDrive Graph 410 Gone).
        // Catch DeltaCursorExpiredException specifically — NOT the generic
        // ProviderException — so non-410 failures keep their existing behaviour.
        // On 410: clear the cursor, discard partial results, and re-run a full
        // enumeration from cursor=null so genuine deletes in the stale window
        // are reaped and unchanged paths are spared. If the recovery pass itself
        // throws ProviderException the exception propagates to the caller which
        // treats it as an enumeration failure (deletes suppressed) — no infinite
        // loop, no recursive recovery.
        try {
            var page = nextPage(cursor)
            for (item in page.items) {
                val resolved = resolveItemPath(item) ?: continue
                admitChange(resolved)
            }
            persistPendingCursor(page.cursor)
            // UD-742: heartbeat after each remote page. Internxt paginates
            // LISTING_PAGE_SIZE = 999 per page (measured + source-verified, #392
            // 2026-09-29), so a 113k-item drive emits ~113 update events — cheap,
            // and the reporter is responsible for throttling display
            // (CliProgressReporter overwrites the same line via printInline).
            reportGathered()

            while (page.hasMore) {
                page = nextPage(page.cursor)
                for (item in page.items) {
                    val resolved = resolveItemPath(item) ?: continue
                    admitChange(resolved)
                }
                persistPendingCursor(page.cursor)
                reportGathered()
            }
        } catch (e: DeltaCursorExpiredException) {
            // The resumed cursor aged out / the drive re-keyed (Graph 410). Clear the
            // persisted cursor and re-enumerate the FULL inventory from a null cursor
            // (incremental = false), so genuine deletes during the stale window are
            // reaped and unchanged paths are not.
            log.warn(
                "#110: delta cursor expired ({}); clearing it and re-enumerating the full inventory.",
                e.message,
            )
            db.setSyncState("delta_cursor", "")
            // Abandon the stale scan context and start a fresh one for the full re-enum.
            db.completeScan(scanId)
            val recoveryScanId = db.beginScan(initialMarker = null)
            val recoveryScanContext =
                org.krost.unidrive.ScanContext(
                    resumeMarker = null,
                    resumedItems = emptyList(),
                    persistPage = { items, marker -> db.persistScanPage(recoveryScanId, items, marker) },
                    scopeRoots = guard.trackScope,
                    readOnly = readOnly,
                    onProgress = providerProgress,
                )
            suspend fun nextPageRecovery(c: String?): DeltaPage {
                val p =
                    if (useShared) {
                        when (val r = provider.deltaWithShared(c)) {
                            is CapabilityResult.Success -> r.value
                            is CapabilityResult.Unsupported -> provider.delta(c, onPageProgress, recoveryScanContext)
                        }
                    } else {
                        provider.delta(c, onPageProgress, recoveryScanContext)
                    }
                log.debug("Delta (recovery): {} items, hasMore={}", p.items.size, p.hasMore)
                if (!p.complete) allComplete = false
                return p
            }
            // Reset all mutable accumulation state for the recovery pass.
            changes = mutableMapOf()
            keyToPath.clear()
            collisions.clear()
            allComplete = true
            isFullSync = true
            // Recovery: full enumeration from null cursor. Any ProviderException here
            // propagates to the caller (treated as an enumeration failure, deletes
            // suppressed) — never recover recursively.
            var rPage = nextPageRecovery(null)
            for (item in rPage.items) {
                val resolved = resolveItemPath(item) ?: continue
                admitChange(resolved)
            }
            persistPendingCursor(rPage.cursor)
            reportGathered()
            while (rPage.hasMore) {
                rPage = nextPageRecovery(rPage.cursor)
                for (item in rPage.items) {
                    val resolved = resolveItemPath(item) ?: continue
                    admitChange(resolved)
                }
                persistPendingCursor(rPage.cursor)
                reportGathered()
            }
            if (allComplete) {
                db.completeScan(recoveryScanId)
            }
        }

        // Staged inventory has been consumed by this gather pass — the live
        // sync_entries write happens downstream via updateRemoteEntries — so
        // the per-page durability slice can be cleared. A daemon crash now,
        // or on any subsequent step, will retry from the freshly-persisted
        // delta_cursor (or pending_cursor if no transfers ran), not from the
        // staged offsets.
        //
        // Cross-session resume: when the gather returned `complete=false`
        // (e.g. Internxt's 503 subtree skip or a partial ancestor-uuid drop),
        // the marker + staged rows are deliberately preserved so the NEXT
        // daemon launch can pick up at the same offset rather than restarting
        // at 0. Pairs with the best-effort `delta_cursor` advance in
        // `promotePendingCursor` — the cursor moves forward by `max(updatedAt)`
        // over completed pages while the offset doesn't regress, so a
        // throttle-cliff'd account makes monotonic progress across restarts
        // even if no individual run reaches `complete=true`. The stale-
        // threshold check inside `getActiveScan` is the safety net against
        // an indefinitely-preserved marker drifting past Internxt's
        // change-detection window.
        if (allComplete) {
            db.completeScan(scanId)
        }

        // #401: report suppressed twins BEFORE the absence sweep so their ids are
        // excluded from reaping.
        val collisionLoserIds = reportCollisions(collisions, readOnly)

        if (isFullSync && allComplete) {
            detectMissingAfterFullSync(changes, collisionLoserIds)
        } else if (isFullSync) {
            val msg =
                "UD-360: at least one delta page returned complete=false; " +
                    "skipping detectMissingAfterFullSync to avoid spurious del-local actions. " +
                    "The missing inventory will be picked up on the next sync run."
            log.warn(msg)
            listener.onWarning(msg)
        }
        // Record the gather's ACTUAL full-enumeration verdict (a 410 recovery above
        // can upgrade an incremental pass to full) so the sync-path remote-shrink
        // guard judges the real mode, not just the pre-gather cursor state.
        db.setSyncState("last_gather_full", isFullSync.toString())
        changes
    }

    private fun loadTrackedScope(): List<String> =
        db.getSyncState("tracked_scope").orEmpty().split("\t").filter { it.isNotEmpty() }

    // Reconcile what state.db tracks with the standing scope. Narrowing drops rows
    // outside the new scope without planning any delete: the local files stay and
    // the remote is untouched. Widening clears the delta cursor so the next gather
    // enumerates the newly in-scope subtrees; rows outside the old scope were never
    // tracked, so an incremental delta could not find them. A dry-run runs this on its disposable copy,
    // so the preview shows exactly what the real run will do.
    fun applyScopeTransition() {
        val prior = loadTrackedScope()
        if (prior == guard.trackScope) return
        val narrowed =
            if (prior.isEmpty()) {
                guard.trackScope.isNotEmpty()
            } else {
                guard.trackScope.isNotEmpty() && prior.any { !SyncScope.contains(it, guard.trackScope) }
            }
        val widened =
            guard.trackScope.isEmpty() || (prior.isNotEmpty() && guard.trackScope.any { !SyncScope.contains(it, prior) })
        db.batch {
            if (narrowed) {
                var untracked = 0
                for (entry in db.getAllEntries()) {
                    if (guard.isTracked(entry.remotePath ?: entry.path)) continue
                    db.deleteEntry(entry.path)
                    untracked++
                }
                // Silent when nothing was untracked: the first run of a new profile
                // narrows from "whole drive" to its scope over an empty db, and a
                // "stopped tracking 0 row(s)" line reads as if state were lost (#395).
                if (untracked > 0) {
                    log.info("Sync scope narrowed to {}: stopped tracking {} row(s); local files left in place", guard.trackScope, untracked)
                }
            }
            if (widened) {
                db.setSyncState("delta_cursor", "")
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)?.let { db.completeScan(it) }
                val msg = "Sync scope widened to ${guard.trackScope.ifEmpty { listOf("whole drive") }}: re-enumerating the drive."
                log.warn(msg)
                listener.onWarning(msg)
            }
            db.setSyncState("tracked_scope", guard.trackScope.joinToString("\t"))
        }
        // The tracked set just reshaped: narrowing untracked rows the mount may
        // still hold in cache (no reap event fires for them — they were deleted,
        // not reaped), and widening re-enumerates from scratch. Neither is
        // expressible as a per-path delta, so invalidate the whole view.
        listener.onViewInvalidated(emptySet(), true)
    }

    fun promotePendingCursor() {
        val pendingCursor = db.getSyncState("pending_cursor") ?: return
        db.setSyncState("delta_cursor", pendingCursor)
        val complete = db.getSyncState("pending_cursor_complete") ?: "true"
        if (complete == "true") {
            db.setSyncState("last_full_scan", Instant.now().toString())
        }
    }

    fun resolveItemPath(item: CloudItem): CloudItem? {
        // #171: canonicalize the remote path to NFC so it matches the NFC local key
        // in the reconciler (copy only when the form actually changes).
        if (item.path != "/" && item.path.isNotEmpty()) {
            val n = PathNormalizer.nfc(item.path)
            return if (n == item.path) item else item.copy(path = n)
        }
        // #183: access-revoked tombstone — Graph `@microsoft.graph.removed` state="removed",
        // no parentReference, so path resolved to "/". The item still physically exists on the
        // remote; the local file MUST be kept. Retire the DB row via TRASHED (removed from the
        // alive view, no longer an unreapable orphan) and return null so the item doesn't flow
        // into the normal reconciler path.
        if (!item.deleted && item.accessRevoked) {
            val retired = db.setStatusTrashed(item.id)
            if (retired) {
                log.info(
                    "#183: access-revoked tombstone id={}: DB row retired (TRASHED), local file preserved",
                    item.id,
                )
            } else {
                log.debug(
                    "#183: access-revoked tombstone id={}: no alive row found (already retired or never tracked)",
                    item.id,
                )
            }
            return null
        }
        if (!item.deleted) return null // non-deleted root item without a known path, skip
        val entry = db.getEntryByRemoteId(item.id) ?: return null
        log.debug("Resolved deleted item id={} to path={}", item.id, entry.path)
        return item.copy(path = entry.path, name = entry.path.substringAfterLast("/"))
    }

    fun detectMissingAfterFullSync(
        remoteChanges: MutableMap<String, CloudItem>,
        admittedLoserIds: Set<String> = emptySet(),
    ) {
        val seenRemoteIds = remoteChanges.values.mapTo(mutableSetOf()) { it.id }
        // #401: a twin suppressed by the gather collision winner-rule keeps its DB row
        // alive but holds no key in remoteChanges; without this it would look absent
        // and the sweep would synthesize a path-addressed DeleteRemote for it — the
        // wrong-twin delete #402 closed. Suppressed ≠ deleted.
        seenRemoteIds.addAll(admittedLoserIds)
        pruneRecentlyUploaded()

        for (entry in db.getAllEntries()) {
            if (entry.remoteId == null) continue
            if (entry.remoteId in seenRemoteIds) continue
            // #115: the delta and the recently-uploaded marks live in the REMOTE
            // namespace, so test against the row's effective remote path
            // (`remotePath ?: path`). For a non-aliased row this is just
            // entry.path — byte-identical to pre-#115 behaviour.
            val effectiveRemote = entry.remotePath ?: entry.path
            if (!guard.isTracked(effectiveRemote)) continue
            if (effectiveRemote in remoteChanges) continue

            val uploadedAt = recentlyUploaded[effectiveRemote]
            if (uploadedAt != null) {
                log.debug(
                    "Full sync: DB entry {} (remoteId={}) not in delta but uploaded {}s ago; " +
                        "within the recent-upload grace window, deferring the deletion verdict",
                    entry.path,
                    entry.remoteId,
                    java.time.Duration.between(uploadedAt, Instant.now()).seconds,
                )
                continue
            }

            log.debug("Full sync: DB entry {} (remoteId={}) not in delta, marking deleted", entry.path, entry.remoteId)
            // Key the synthesized tombstone at the effective remote path so it
            // flows through the reconciler's canonical→real-local reverse map
            // alongside genuine deltas.
            remoteChanges[effectiveRemote] =
                CloudItem(
                    id = entry.remoteId,
                    name = effectiveRemote.substringAfterLast("/"),
                    path = effectiveRemote,
                    size = 0,
                    isFolder = entry.isFolder,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                    deleted = true,
                )
        }
    }

    /**
     * What one [updateRemoteEntries] pass changed, as the view consumers need it (#595):
     * [changedPaths] holds exactly the paths whose row is new or whose view-relevant
     * content (remote id, hash, size, modified time, path) changed — a delta that
     * re-delivers unchanged items (Internxt's rewound cursor) changes nothing and must
     * not invalidate the view. [moved] holds the renames the merge detected: an item
     * re-delivered under a new path replaces its own row (remote id is the primary
     * key), and without this the old path would vanish from the view unannounced.
     */
    class RemoteMerge(
        val changedPaths: Set<String>,
        val moved: List<Move>,
    ) {
        data class Move(
            val from: String,
            val to: String,
        )
    }

    fun updateRemoteEntries(remoteChanges: Map<String, CloudItem>): RemoteMerge {
        // #115: the remoteChanges keys are canonical remote paths. Build a
        // canonical→real-local reverse map so a newly-arrived aliased item is
        // persisted at its REAL-LOCAL path (with the canonical in remote_path),
        // not as a phantom canonical-keyed row that LocalScanner can never find
        // on disk. Existing rows are matched by effective remote path so the
        // merge preserves their real-local path + remote_path. No alias active
        // → both helpers are identity and this is byte-identical to pre-#115.
        val remoteToLocalTop = localTopAliases(remoteChanges)
        val changed = java.util.LinkedHashSet<String>()
        val moved = mutableListOf<RemoteMerge.Move>()
        for ((path, item) in remoteChanges) {
            if (item.deleted) continue // skip deleted items
            val realLocalPath = applyReverseTop(path, remoteToLocalTop)
            val isAliased = realLocalPath != path
            // Match an existing row by effective remote path (handles aliased
            // rows keyed at their real-local path).
            var existing = db.getEntryByRemotePath(path)
            var renamedFrom: String? = null
            if (existing == null) {
                // #595: a renamed item arrives under its new path and matches no row
                // by path — but its remote id still owns a row at the OLD path, and
                // upsertEntry would replace it silently (remote id is the primary
                // key), vanishing the old name from the view. Merge into that row
                // instead and report the move.
                val byId = db.getEntryByRemoteId(item.id)
                if (byId != null && byId.path != realLocalPath) {
                    existing = byId
                    renamedFrom = byId.path
                }
            }
            val merged =
                if (existing == null) {
                    SyncEntry(
                        // #115: key a newly-arrived aliased item at its real-local
                        // path; record the canonical in remotePath so LocalScanner
                        // finds the row and the next delta matches by effective
                        // remote path.
                        path = realLocalPath,
                        remotePath = if (isAliased) path else null,
                        remoteId = item.id,
                        remoteHash = item.hash,
                        remoteSize = item.size,
                        remoteModified = item.modified,
                        localMtime = null,
                        localSize = null,
                        isFolder = item.isFolder,
                        isPinned = false,
                        isHydrated = false,
                        lastSynced = Instant.now(),
                    )
                } else {
                    existing.copy(
                        remoteId = item.id,
                        remoteHash = item.hash,
                        remoteSize = item.size,
                        remoteModified = item.modified,
                        lastSynced = Instant.now(),
                        // A fresh delta event for a previously-quarantined row
                        // means the cloud is reporting it alive again — drop the
                        // quarantine and let the next reconcile re-emit the
                        // download. Belt-and-braces with
                        // StateDatabase.clearDownloadQuarantine below: that call
                        // wins on the canonical SQL UPDATE; this copy ensures
                        // any consumer reading the merged value inside this loop
                        // sees the cleared state too.
                        downloadQuarantined = false,
                        lastErrorAt = null,
                        // A rename moves the row to the new path (preserving
                        // localMtime, localSize, isHydrated and isPinned — the
                        // hydration cache and pins travel with it, so a client can
                        // move the placeholder instead of redownloading, #595). A
                        // path-matched merge keeps its path and remote_path: the
                        // row was found BY that effective remote path, and only a
                        // rename — never a re-delivery — may re-key it.
                        path = if (renamedFrom != null) realLocalPath else existing.path,
                        remotePath = if (renamedFrom != null) (if (isAliased) path else null) else existing.remotePath,
                    )
                }
            db.upsertEntry(merged)
            // Belt-and-braces (matches the .copy() above): explicitly clear
            // the quarantine flag on the canonical row in case a future
            // upsertEntry call path mutates the row without going through
            // the merged.copy() construction above.
            if (existing != null && existing.downloadQuarantined && existing.remoteId != null) {
                db.clearDownloadQuarantine(existing.remoteId)
            }
            // #595: the view only learns what actually changed. lastSynced moves on
            // every pass by design; a re-delivered unchanged item (Internxt's rewound
            // cursor re-names the same items every poll) must not invalidate it.
            val contentChanged =
                existing == null ||
                    existing.remoteId != item.id ||
                    existing.remoteHash != item.hash ||
                    existing.remoteSize != item.size ||
                    existing.remoteModified != item.modified
            if (contentChanged) changed += realLocalPath
            if (renamedFrom != null) {
                moved += RemoteMerge.Move(from = renamedFrom, to = realLocalPath)
                // Both ends of the move leave and enter the view, even when the
                // content fields read equal (a pure rename changes nothing else).
                changed += renamedFrom
                changed += realLocalPath
            }
        }
        return RemoteMerge(changedPaths = changed, moved = moved)
    }

    companion object {
        @JvmField
        val RECENT_UPLOAD_REAP_GRACE: java.time.Duration = java.time.Duration.ofSeconds(120)

        const val SCAN_CHECKPOINT_STALE_HOURS: Long = 6L

        // #401: sync_state keys backing the `status` collision surface. The paths
        // value is TAB-joined, capped so a drive-wide naming collision cannot bloat
        // either the row or the status output.
        const val REMOTE_COLLISIONS_KEY: String = "remote_collisions"
        const val REMOTE_COLLISION_PATHS_KEY: String = "remote_collisions_paths"
        const val COLLISION_PATHS_STATUS_LIMIT: Int = 50

        /** #115: [path] with its canonical top-level folder replaced by the local alias, if any. */
        fun applyReverseTop(
            path: String,
            canonicalToLocalTop: Map<String, String>,
        ): String {
            if (canonicalToLocalTop.isEmpty()) return path
            val noSlash = path.removePrefix("/")
            val slash = noSlash.indexOf('/')
            val top = if (slash < 0) noSlash else noSlash.substring(0, slash)
            val localTop = canonicalToLocalTop[top] ?: return path
            val rest = if (slash < 0) "" else noSlash.substring(slash)
            return "/$localTop$rest"
        }
    }
}
