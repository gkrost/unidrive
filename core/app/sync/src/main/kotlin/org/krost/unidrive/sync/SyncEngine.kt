package org.krost.unidrive.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.model.*
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException

class SyncEngine(
    private val provider: CloudProvider,
    private val db: StateDatabase,
    private val syncRoot: Path,
    private val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    private val conflictOverrides: Map<String, ConflictPolicy> = emptyMap(),
    private val excludePatterns: List<String> = emptyList(),
    private val reporter: ProgressReporter = ProgressReporter.Silent,
    private val failureLogPath: Path? = null,
    private val conflictLog: ConflictLog? = null,
    private val syncPath: String? = null,
    private val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
    // UD-737: --upload-only is push-additive by default — local deletes do NOT
    // propagate to remote. Set to true to opt back in to legacy "local is
    // source of truth" semantics. No effect outside SyncDirection.UPLOAD.
    private val propagateDeletes: Boolean = false,
    private val maxDeletePercentage: Int = 50,
    // UD-265: two new axes on the deletion safeguard. maxDeletePercentage stays
    // for back-compat (still trips); these add an absolute cap (catches
    // wide-blast on large drives where any sane percentage is still a
    // catastrophe) and a per-top-level-subtree percentage (catches
    // 100%-of-/Documents/Foo runs that are 0.1% of the whole drive). Either
    // axis tripping aborts apply / warns in dry-run; --force-delete bypasses
    // all three. 0 disables the corresponding check.
    private val maxDeleteAbsolute: Int = 50,
    private val maxDeletePerSubtreePercent: Int = 80,
    private val verifyIntegrity: Boolean = false,
    private val providerId: String = "",
    private val useTrash: Boolean = true,
    private val includeShared: Boolean = false,
    private val echoSuppress: ((String) -> Unit)? = null,
    private val echoUnsuppress: ((String) -> Unit)? = null,
    private val placeholder: PlaceholderManager = PlaceholderManager(syncRoot),
    private val trashManager: TrashManager? = null,
    private val trashRetentionDays: Int = 30,
    private val versionManager: VersionManager? = null,
    private val maxVersions: Int = 5,
    private val versionRetentionDays: Int = 90,
    private val fastBootstrap: Boolean = false,
    // UD-113: optional structured audit log of mutations (Download/Upload/Delete/Move/
    // CreateRemoteFolder). Wired from CLI/MCP startup; null in tests that don't need it.
    private val auditLog: org.krost.unidrive.sync.audit.AuditLog? = null,
    // UD-256: operator opt-in for full-tree bidirectional after the profile has been
    // used with --sync-path. When true, the persisted effective_scope is cleared and
    // this run reconciles against the entire cloud (which is what the user wants when
    // they're consciously taking the profile out of scoped mode). When false (the
    // default), a bare bidirectional apply on a profile with non-empty persisted
    // scope is refused with a guidance message — the 2026-05-16 405-cloud-delete
    // incident on inxt_gernot_krost_posteo would have been caught here.
    private val allowFullTreeReconciliation: Boolean = false,
    // UD-264: opt-out for the top-level-never-hydrated guard. When false (default)
    // the engine SKIPS any del-remote action whose top-level cloud folder has
    // never held a hydrated descendant locally — exactly the 2026-05-16 incident
    // shape on `inxt_gernot_krost_posteo`, where state.db indexed 280k entries
    // but no descendant of /Documents/CyberLink/ etc. ever had local_mtime or
    // is_hydrated=1 (the official Internxt client wrote them to cloud; the user
    // never downloaded them through unidrive). When true the guard still logs
    // skipped paths to skipped-ops.jsonl but does NOT drop the action — for the
    // rare case where the operator genuinely wants to purge a never-touched
    // top-level (e.g. final cleanup after rename).
    private val ignoreTopLevelGuard: Boolean = false,
    // UD-264: sibling of failureLogPath. When non-null and a delete is dropped
    // (or would have been dropped, if ignoreTopLevelGuard=true), append a JSON
    // line: {"ts":"...","action":"del-remote","path":"...","reason":"..."}.
    // Null in tests that don't care about audit output.
    private val skippedOpsLogPath: Path? = null,
) {
    private val log = LoggerFactory.getLogger(SyncEngine::class.java)
    private val effectiveExcludePatterns =
        validateExcludePatterns(
            excludePatterns + listOf("/.unidrive-trash/**", "/.unidrive-versions/**"),
        )
    private val scanner = LocalScanner(syncRoot, db, effectiveExcludePatterns)
    private val reconciler = Reconciler(db, syncRoot, conflictPolicy, conflictOverrides, effectiveExcludePatterns)

    // Debounce state for remote-change wake hints (Internxt notifications WS).
    // The provider may emit many frames per second during a folder-tree
    // mutation; we coalesce them into one wake by cancelling-and-restarting
    // a single delay job per hint and firing the listener only after the
    // quiet window elapses.
    @Volatile
    private var remoteWakeDebounceJob: kotlinx.coroutines.Job? = null

    /**
     * Wire the provider's server-pushed change feed (Internxt's socket.io
     * `NOTIFICATIONS_URL`) into the watch loop. The provider emits one
     * raw hint per observed remote mutation; this method debounces a
     * burst into a single [listener] invocation after a quiet window
     * ([REMOTE_WAKE_DEBOUNCE_MS]) so 50 frame arrivals from one folder-
     * tree change wake the poll loop ONCE instead of 50 times.
     *
     * [scope] owns the debounce coroutine. When [scope] is cancelled
     * (daemon shutdown) any pending debounced fire is dropped.
     *
     * Providers without a push channel inherit the [CloudProvider.onRemoteChangeHint]
     * default no-op; this call is then a registration into a black hole,
     * which is fine.
     */
    fun registerRemoteWakeListener(
        scope: kotlinx.coroutines.CoroutineScope,
        listener: () -> Unit,
    ) {
        provider.onRemoteChangeHint {
            // Cancel any pending debounce and restart. The current hint
            // resets the quiet-window clock; only when the quiet window
            // elapses without a fresh hint do we fire the listener.
            remoteWakeDebounceJob?.cancel()
            remoteWakeDebounceJob =
                scope.launch {
                    kotlinx.coroutines.delay(REMOTE_WAKE_DEBOUNCE_MS)
                    try {
                        listener()
                    } catch (e: Exception) {
                        log.warn("Remote-wake listener threw", e)
                    }
                }
        }
    }

    /** Suppress watcher events for [path] during [block], then unsuppress. */
    private inline fun <T> withEchoSuppression(
        path: String,
        block: () -> T,
    ): T {
        echoSuppress?.invoke(path)
        try {
            return block()
        } finally {
            echoUnsuppress?.invoke(path)
        }
    }

    suspend fun syncOnce(
        dryRun: Boolean = false,
        forceDelete: Boolean = false,
        // UD-254: classifies WHY a sync pass started so post-incident log review
        // can separate a normal watch poll from e.g. a rescan-after-retry burst.
        reason: SyncReason = SyncReason.MANUAL,
        // UD-236: refresh-mode cut. Run Gather + Reconcile + Pass 1 (placeholder
        // ops, deletes, moves, mkdirs — all metadata) and SKIP Pass 2 (the actual
        // byte transfers). Pending transfers persist as remoteId=null (upload
        // pending) or isHydrated=false (download pending) DB rows that the next
        // sync — or a follow-up `unidrive apply` — picks up via the UD-225/UD-901
        // recovery loops in Reconciler.reconcile.
        //
        // pending_cursor is NOT promoted to delta_cursor when transfers are
        // skipped — apply will do the promotion when the bytes actually move.
        skipTransfers: Boolean = false,
        // UD-236: apply-mode cut. SKIP the remote Gather phase (no provider.delta()
        // call). Local scan still runs (cheap; catches local edits made since the
        // previous refresh). The recovery loops in Reconciler emit actions for
        // any unhydrated / no-remoteId DB rows from the prior refresh — apply's
        // reason for being is to drain those.
        skipRemoteGather: Boolean = false,
    ) {
        // UD-254: short random scan id pushed into MDC so every DEBUG/WARN line
        // emitted inside this pass inherits it (e.g. InternxtProvider's
        // "Scanning files: N"). A single grep "scan=<id>" gives the slice
        // belonging to one sync pass.
        val scanId =
            java.util.UUID
                .randomUUID()
                .toString()
                .substring(0, 8)
        val priorScanMdc = org.slf4j.MDC.get("scan")
        org.slf4j.MDC.put("scan", scanId)
        val startTime = System.currentTimeMillis()
        log.info("Scan started scan={} reason={} dryRun={}", scanId, reason, dryRun)
        try {
            doSyncOnce(dryRun, forceDelete, scanId, reason, startTime, skipTransfers, skipRemoteGather)
        } finally {
            val duration = System.currentTimeMillis() - startTime
            log.info("Scan ended scan={} reason={} duration={}ms", scanId, reason, duration)
            if (priorScanMdc == null) {
                org.slf4j.MDC.remove("scan")
            } else {
                org.slf4j.MDC.put("scan", priorScanMdc)
            }
        }
    }

    private suspend fun doSyncOnce(
        dryRun: Boolean,
        forceDelete: Boolean,
        @Suppress("UNUSED_PARAMETER") scanId: String,
        @Suppress("UNUSED_PARAMETER") reason: SyncReason,
        startTime: Long,
        skipTransfers: Boolean = false,
        skipRemoteGather: Boolean = false,
    ) {
        val downloaded = AtomicInteger(0)
        val uploaded = AtomicInteger(0)
        val conflicts = AtomicInteger(0)

        // UD-299: detect sync_root drift between runs. state.db is per-profile
        // (not per-(profile, sync_root)), so editing sync_root in config.toml
        // leaves the DB indexing the old tree. Every absent old-root path then
        // becomes a DeleteRemote in the next plan. Refuse to run when the
        // stored sync_root differs from the current one — `--reset` is the
        // bypass (it wipes state.db, so the next run records the new root).
        val currentRoot =
            syncRoot
                .toAbsolutePath()
                .normalize()
                .toString()
        val storedRoot = db.getSyncState("sync_root")
        if (storedRoot.isNullOrEmpty()) {
            db.setSyncState("sync_root", currentRoot)
        } else if (!sameSyncRoot(storedRoot, currentRoot)) {
            throw IllegalStateException(
                "sync_root changed from '$storedRoot' to '$currentRoot'. " +
                    "The state DB still indexes the old tree and would produce " +
                    "spurious del-remote actions. Run with --reset to wipe state " +
                    "and re-sync from scratch, or revert sync_root in config.toml.",
            )
        }

        // UD-256: scope-persistence guard against the 2026-05-16
        // delete-the-cloud-by-bidirectional-on-partial-local pattern.
        //
        // A profile that has ever been operated with `--sync-path` accumulates
        // a persisted `effective_scope` (sync_state key, TAB-separated list of
        // normalised paths). On every run:
        //  - If `syncPath` is set this run: UNION it into the persisted scope
        //    (whether the scope was previously empty or not). The run proceeds
        //    with the runtime scope filter as before.
        //  - If `syncPath` is NULL and the persisted scope is non-empty and the
        //    run is bidirectional-apply: REFUSE unless --full-tree was passed.
        //    The reconciler would otherwise treat every cloud path outside the
        //    persisted scope as "user-deleted-locally" and propagate DELETE.
        //  - If `--full-tree` was passed: clear the persisted scope (the user
        //    is consciously taking the profile out of scoped mode) and proceed.
        //  - Dry-run is allowed-with-warning, not refused, so the operator can
        //    inspect what a `--full-tree` would do without an unrecoverable
        //    commitment.
        //  - Upload-only and download-only are not refused — UD-737 already
        //    blocks delete propagation in those modes, so the catastrophe
        //    pattern can't trigger from the directions alone.
        //
        // UD-256 / PR #45 review (Codex P1): **never mutate effective_scope
        // in dry-run.** Dry-run is contractually side-effect-free (see
        // UD-738's in-memory-shadow handling of `--reset --dry-run`). Earlier
        // versions of this block wrote to sync_state unconditionally — so a
        // `--full-tree --dry-run` preview would permanently clear the guard,
        // and a subsequent bare bidirectional apply on the same partial local
        // tree would no longer be refused. Same hazard applied to
        // `--sync-path X --dry-run`: previewing a scope addition silently
        // committed it. Both writes are now gated on `!dryRun`. The refusal
        // / warning branches are pure reads and stay structured as before.
        val priorScope = loadEffectiveScope()
        if (allowFullTreeReconciliation) {
            if (priorScope.isNotEmpty() && !dryRun) {
                log.info(
                    "UD-256: --full-tree clears persisted effective_scope ({} entries)",
                    priorScope.size,
                )
                db.setSyncState("effective_scope", "")
            } else if (priorScope.isNotEmpty() && dryRun) {
                log.info(
                    "UD-256: --full-tree --dry-run previewing whole-cloud reconciliation; persisted effective_scope ({} entries) left untouched",
                    priorScope.size,
                )
            }
        } else if (syncPath != null) {
            val unioned = (priorScope + syncPath).distinct()
            if (unioned.size != priorScope.size && !dryRun) {
                log.info(
                    "UD-256: persisting effective_scope += '{}' (now {} entry/entries)",
                    syncPath,
                    unioned.size,
                )
                db.setSyncState("effective_scope", unioned.joinToString("\t"))
            } else if (unioned.size != priorScope.size && dryRun) {
                log.info(
                    "UD-256: --dry-run with new --sync-path '{}' — would extend effective_scope to {} entries (not persisted)",
                    syncPath,
                    unioned.size,
                )
            }
        } else if (priorScope.isNotEmpty() && syncDirection == SyncDirection.BIDIRECTIONAL) {
            val msg =
                "UD-256: this profile has been used with scoped operations " +
                    "(--sync-path) in the past. Persisted effective_scope: " +
                    priorScope.joinToString(", ") { "'$it'" } + ". " +
                    "Running un-scoped bidirectional reconciliation would treat " +
                    "every cloud path outside the persisted scope as a deletion " +
                    "candidate. Either: (a) pass --sync-path <one of the above> " +
                    "to operate within the existing scope, or (b) pass --full-tree " +
                    "to clear the persisted scope and re-enable whole-cloud " +
                    "reconciliation (DANGER — this is the path that produced the " +
                    "2026-05-16 405-folder-delete incident; only use when the " +
                    "local sync_root is known to mirror the entire cloud)."
            if (dryRun) {
                reporter.onWarning(msg)
            } else {
                throw IllegalStateException(msg)
            }
        }

        // Auto-purge expired trash entries
        trashManager?.purge(trashRetentionDays)
        versionManager?.pruneByAge(versionRetentionDays)

        // Phase 1: Gather changes
        // UD-747 (UD-744 slice): pass the previous run's wall-clock seconds
        // for each phase to the reporter so the heartbeat can render a
        // bucketed ETA. First-run / `--reset` scans simply have no key in
        // sync_state and the reporter falls back to throughput-only output.
        // UD-748: also pass the previous run's *final item count* for each
        // phase so the bucket helper can use progress-fraction extrapolation
        // when the current run is faster/slower than last time.
        db.getSyncState("last_scan_secs_remote")?.toLongOrNull()?.let {
            reporter.onScanHistoricalHint("remote", it)
        }
        db.getSyncState("last_scan_count_remote")?.toIntOrNull()?.let {
            reporter.onScanCountHint("remote", it)
        }
        val remotePhaseStart = System.currentTimeMillis()
        reporter.onScanProgress("remote", 0)
        // UD-236: skipRemoteGather (apply mode) bypasses provider.delta() entirely.
        // The recovery loops in Reconciler.reconcile pick up any pending UD-225/UD-901
        // rows from a prior refresh and emit DownloadContent / Upload actions for them.
        val allRemoteChanges =
            if (skipRemoteGather) {
                log.info("Apply mode: skipping remote gather; recovery loops will surface pending entries")
                emptyMap()
            } else {
                gatherRemoteChanges()
            }

        val remoteChanges =
            if (syncPath != null) {
                allRemoteChanges.filterKeys { it.startsWith(syncPath) || it == syncPath }
            } else {
                allRemoteChanges
            }
        reporter.onScanProgress("remote", remoteChanges.size)
        // UD-747 / UD-748: persist the wall-clock + final count for next
        // run's ETA computation.
        val remoteScanSecs = (System.currentTimeMillis() - remotePhaseStart) / 1000
        db.setSyncState("last_scan_secs_remote", remoteScanSecs.toString())
        db.setSyncState("last_scan_count_remote", remoteChanges.size.toString())

        db.getSyncState("last_scan_secs_local")?.toLongOrNull()?.let {
            reporter.onScanHistoricalHint("local", it)
        }
        db.getSyncState("last_scan_count_local")?.toIntOrNull()?.let {
            reporter.onScanCountHint("local", it)
        }
        val localPhaseStart = System.currentTimeMillis()
        reporter.onScanProgress("local", 0)
        val allLocalChanges =
            scanner.scan { count ->
                // UD-742: scanner emits this every 5k items / 10s during long walks.
                reporter.onScanProgress("local", count)
            }
        val localChanges =
            if (syncPath != null) {
                val ancestors = syncPathAncestors(syncPath)
                allLocalChanges.filterKeys { it.startsWith(syncPath) || it == syncPath || it in ancestors }
            } else {
                allLocalChanges
            }
        reporter.onScanProgress("local", localChanges.size)
        val localScanSecs = (System.currentTimeMillis() - localPhaseStart) / 1000
        db.setSyncState("last_scan_secs_local", localScanSecs.toString())
        db.setSyncState("last_scan_count_local", localChanges.size.toString())

        // UD-297: empty-local + populated-DB sanity check. Catches the
        // wrong-sync_root case (user pointed at an empty directory while
        // the state DB knows about thousands of remote entries) before
        // the reconciler turns it into a wall of del-remote actions.
        // Fires in dry-run too — that's where the user is most likely
        // to notice and the system has the least to lose by being loud.
        if (!forceDelete && db.getEntryCount() > 10 && isSyncRootEffectivelyEmpty()) {
            val msg =
                "Local sync_root '$syncRoot' is empty, but state DB knows " +
                    "${db.getEntryCount()} entries. sync_root probably points at the " +
                    "wrong directory. Re-run with --force-delete if the local data was " +
                    "intentionally wiped."
            if (dryRun) {
                reporter.onWarning(msg)
            } else {
                throw IllegalStateException(msg)
            }
        }

        // Phase 2: Reconcile (UD-240g: pass reporter so the phase emits a
        // heartbeat instead of going silent for many seconds on big first-syncs;
        // UD-901a: pass syncPath so the recovery loops respect scope and don't
        // resurrect orphans outside the user's requested subtree).
        val reconciledActions = reconciler.reconcile(remoteChanges, localChanges, reporter, syncPath)

        // Persist remote metadata for reuse in subsequent runs (UD-260)
        db.batch {
            updateRemoteEntries(allRemoteChanges)
        }

        // UD-264: top-level-never-hydrated guard. For every DeleteRemote action,
        // check whether *any* descendant under the action's top-level cloud
        // folder has ever been hydrated locally (is_hydrated=1 OR local_mtime
        // IS NOT NULL). If not, the top-level has never been touched by this
        // unidrive install — propagating deletes outward would mirror the
        // 2026-05-16 incident shape, where state.db indexed 280k entries via
        // delta but no descendant of /Documents/CyberLink/, /.userhome/win11/
        // etc. ever held a hydrated local row (the user used the official
        // Internxt client to write them to cloud; unidrive only ever saw them
        // through delta). forceDelete bypasses; --ignore-top-level-guard logs
        // but does not skip. Skipped paths are appended to skipped-ops.jsonl
        // for post-mortem visibility.
        val allActions =
            if (forceDelete) {
                reconciledActions
            } else {
                applyTopLevelHydrationGuard(reconciledActions)
            }

        // Phase 2a: Direction filter
        val actions =
            when (syncDirection) {
                SyncDirection.UPLOAD ->
                    allActions.filter {
                        // UD-737: DeleteRemote only flows in upload-direction
                        // when --propagate-deletes is explicitly set. The flag
                        // name `--upload-only` reads as a one-way uploader,
                        // and the default is push-additive: only Upload /
                        // CreateRemoteFolder / MoveRemote land on remote.
                        it is SyncAction.Upload ||
                            (propagateDeletes && it is SyncAction.DeleteRemote) ||
                            it is SyncAction.CreateRemoteFolder ||
                            it is SyncAction.MoveRemote ||
                            it is SyncAction.Conflict ||
                            it is SyncAction.RemoveEntry
                    }
                SyncDirection.DOWNLOAD ->
                    allActions.filter {
                        it is SyncAction.CreatePlaceholder ||
                            it is SyncAction.UpdatePlaceholder ||
                            it is SyncAction.DownloadContent ||
                            it is SyncAction.DeleteLocal ||
                            it is SyncAction.MoveLocal ||
                            it is SyncAction.Conflict ||
                            it is SyncAction.RemoveEntry
                    }
                SyncDirection.BIDIRECTIONAL -> allActions
            }

        // UD-201: pass both pre-filter (reconciler verdict) and post-filter
        // (executor input) counts so reporters can distinguish "reconciler
        // decided N actions" from "executor will run M after --upload-only
        // / --download-only filtering." Behaviour unchanged; only the
        // signal passed to the reporter is richer.
        val filterReason: String? =
            if (actions.size != allActions.size) {
                when (syncDirection) {
                    SyncDirection.UPLOAD -> "--upload-only"
                    SyncDirection.DOWNLOAD -> "--download-only"
                    SyncDirection.BIDIRECTIONAL -> null
                }
            } else {
                null
            }
        reporter.onActionCount(actions.size, allActions.size, filterReason)

        if (actions.isEmpty()) {
            // UD-260 / UD-901e: promote pending cursor only if the gather pass
            // that wrote it was complete (see promotePendingCursorIfComplete).
            promotePendingCursorIfComplete()
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(0, 0, 0, duration)
            return
        }

        // Phase 2b: Deletion safeguards.
        //
        // UD-298: legacy whole-inventory percentage check. Still in place for
        // back-compat — preserves the same threshold name and behaviour that
        // operators have come to rely on. Evaluates in dry-run as a warning
        // (UD-298 reframe) and throws otherwise.
        //
        // UD-265: two additional axes on top of the legacy check.
        //   1) maxDeleteAbsolute (default 50): trips on any run planning > N
        //      deletes, regardless of inventory size. Catches "wide blast"
        //      runs on large drives where 0.14 % of 280k entries (the
        //      2026-05-16 incident: 405 deletes) is still a catastrophe.
        //   2) maxDeletePerSubtreePercent (default 80): trips when any single
        //      top-level cloud folder affected by deletes has > N% of its
        //      tracked entries marked for deletion. Catches "delete 100 % of
        //      /Documents/CyberLink/" runs that are tiny fractions of the
        //      whole drive but catastrophic within their subtree.
        //
        // Any single axis tripping aborts apply / warns in dry-run.
        // forceDelete bypasses all three. 0 disables the corresponding axis.
        if (!forceDelete) {
            val deleteActions = actions.filter { it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal }
            val deleteCount = deleteActions.size
            val totalEntries = db.getEntryCount()

            // UD-298 legacy whole-inventory percentage axis.
            if (maxDeletePercentage in 1..99 && totalEntries > 0 && deleteCount > 10) {
                val pct = deleteCount * 100 / totalEntries
                if (pct > maxDeletePercentage) {
                    val msg =
                        "Deletion safeguard: $deleteCount of $totalEntries files ($pct%) would be deleted, " +
                            "exceeding max_delete_percentage=$maxDeletePercentage%. " +
                            "sync_root='$syncRoot'. Use --force-delete to override."
                    if (dryRun) {
                        reporter.onWarning(msg)
                    } else {
                        throw IllegalStateException(msg)
                    }
                }
            }

            // UD-265 axis 1: absolute count cap.
            if (maxDeleteAbsolute > 0 && deleteCount > maxDeleteAbsolute) {
                val msg =
                    "UD-265 Deletion safeguard: $deleteCount deletes planned, " +
                        "exceeding max_delete_absolute=$maxDeleteAbsolute. " +
                        "sync_root='$syncRoot'. Use --force-delete to override."
                if (dryRun) {
                    reporter.onWarning(msg)
                } else {
                    throw IllegalStateException(msg)
                }
            }

            // UD-265 axis 2: per-top-level-subtree percentage cap. Group
            // delete actions by their top-level segment, count the tracked
            // entries under that top-level in state.db, and compare. Skip
            // subtrees with fewer than 5 tracked entries — small folders
            // produce noisy 100% trips on legitimate cleanups.
            if (maxDeletePerSubtreePercent in 1..99 && deleteCount > 0) {
                val byTopLevel = deleteActions.groupBy { topLevelOf(it.path) }
                for ((top, group) in byTopLevel) {
                    if (top == null) continue
                    val tracked = db.countEntriesUnderTopLevel(top)
                    if (tracked < 5) continue
                    val pct = group.size * 100 / tracked
                    if (pct > maxDeletePerSubtreePercent) {
                        val msg =
                            "UD-265 Deletion safeguard: ${group.size} of $tracked entries " +
                                "under top-level '$top' ($pct%) would be deleted, exceeding " +
                                "max_delete_per_subtree_percent=$maxDeletePerSubtreePercent%. " +
                                "sync_root='$syncRoot'. Use --force-delete to override."
                        if (dryRun) {
                            reporter.onWarning(msg)
                        } else {
                            throw IllegalStateException(msg)
                        }
                    }
                }
            }
        }

        if (dryRun) {
            val counts = mutableMapOf<String, Int>()
            actions.forEachIndexed { index, action ->
                val label = actionLabel(action)
                reporter.onActionProgress(index + 1, actions.size, label, displayPath(action))
                counts[label] = (counts[label] ?: 0) + 1
                when (action) {
                    is SyncAction.DownloadContent -> downloaded.incrementAndGet()
                    is SyncAction.CreatePlaceholder -> if (action.shouldHydrate) downloaded.incrementAndGet()
                    is SyncAction.UpdatePlaceholder -> if (action.wasHydrated) downloaded.incrementAndGet()
                    is SyncAction.Upload -> uploaded.incrementAndGet()
                    is SyncAction.Conflict -> conflicts.incrementAndGet()
                    else -> {}
                }
            }
            // UD-260 / UD-901e: promote pending cursor only if the gather pass
            // that wrote it was complete (see promotePendingCursorIfComplete).
            promotePendingCursorIfComplete()
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(downloaded.get(), uploaded.get(), conflicts.get(), duration, counts)
            return
        }

        // Phase 3: Apply
        // UD-263: replaced the pre-fix 16/6/2 size-based split with a single
        // per-provider semaphore sized from ProviderRegistry metadata. The
        // size-based split was provider-agnostic and routinely overshot the
        // tighter providers (Synology DSM 500s above ~4, SharePoint heavy
        // throttling above 2). Per-provider audit values now flow from
        // docs/providers/<id>-robustness.md §5 → ProviderMetadata →
        // SyncEngine. Memory-pressure protection on big files is delegated
        // to the provider's HttpRetryBudget (UD-232) which serialises throttle
        // responses end-to-end.
        val perProviderConcurrency =
            org.krost.unidrive.ProviderRegistry
                .getMetadata(providerId)
                ?.maxConcurrentTransfers ?: 4
        val transferSemaphore = kotlinx.coroutines.sync.Semaphore(perProviderConcurrency)
        log.info(
            "Pass 2 transfer semaphore: provider={} maxConcurrentTransfers={}",
            providerId.ifBlank { "<unknown>" },
            perProviderConcurrency,
        )

        var consecutiveFailures = 0
        val completedActions = AtomicInteger(0)
        // UD-745: count of Pass 1 actions that hit a non-recoverable failure
        // (mkdir/move/delete/conflict). Combined with `transferFailures`
        // below for the headline `failed` count in onSyncComplete.
        val passOneFailures = AtomicInteger(0)

        // Pass 1: sequential actions (placeholder ops, deletes, moves, conflicts, remote folder creates)
        // Batched into one SQLite transaction — avoids one fsync per action.
        val sequentialActions =
            actions.filter {
                it !is SyncAction.DownloadContent && it !is SyncAction.Upload
            }
        db.beginBatch()
        try {
            for (action in sequentialActions) {
                try {
                    when (action) {
                        is SyncAction.CreatePlaceholder -> {
                            applyCreatePlaceholder(action)
                            if (action.shouldHydrate) downloaded.incrementAndGet()
                        }
                        is SyncAction.UpdatePlaceholder -> {
                            applyUpdatePlaceholder(action)
                            if (action.wasHydrated) downloaded.incrementAndGet()
                        }
                        is SyncAction.MoveRemote -> applyMoveRemote(action)
                        is SyncAction.MoveLocal -> applyMoveLocal(action)
                        is SyncAction.DeleteLocal -> applyDeleteLocal(action)
                        is SyncAction.DeleteRemote -> applyDeleteRemote(action)
                        is SyncAction.CreateRemoteFolder -> applyCreateRemoteFolder(action)
                        is SyncAction.Conflict -> {
                            applyConflict(action)
                            conflicts.incrementAndGet()
                        }
                        is SyncAction.RemoveEntry -> applyRemoveEntry(action)
                        else -> {}
                    }
                    consecutiveFailures = 0
                } catch (e: AuthenticationException) {
                    // UD-253: include exception class + full stack for auth failures.
                    // UD-203: append `requestId=<id>` when the provider's exception
                    // carries one, so the ERROR log line points at a Graph / S3 /
                    // Internxt support trace.
                    log.error(
                        "Authentication failed, stopping sync: {}: {}{}",
                        e.javaClass.simpleName,
                        e.message,
                        org.krost.unidrive.requestIdSuffix(e),
                        e,
                    )
                    throw e
                } catch (e: Exception) {
                    consecutiveFailures++
                    passOneFailures.incrementAndGet()
                    // UD-253: class name + throwable (SLF4J renders stack trace when the
                    // last arg is a Throwable) so WARNs are self-diagnosing in the log.
                    // UD-203: requestIdSuffix(e) renders ` requestId=<id>` when the
                    // caught exception is a ProviderException with a non-null id,
                    // empty string otherwise — same line shape as before for non-
                    // provider failures.
                    log.warn(
                        "Action failed for {} ({} consecutive): {}: {}{}",
                        action.path,
                        consecutiveFailures,
                        e.javaClass.simpleName,
                        e.message,
                        org.krost.unidrive.requestIdSuffix(e),
                        e,
                    )
                    reporter.onWarning("Failed: ${action.path} - ${e.message}")
                    logFailure(action, e)
                    // UD-248: previously, hitting 3 consecutive action failures
                    // threw ProviderException which tore down the whole pass and
                    // made the watch loop restart syncOnce (re-enumerating the
                    // entire remote tree — expensive for 22k-file profiles).
                    // Now we skip the failing action and continue with the rest.
                    // The watch loop's own cycle-failure backoff handles the
                    // truly-broken case (every action fails → next cycle delays
                    // longer). Catastrophic outage still trips at
                    // CONSECUTIVE_SYNC_FAILURE_HARD_CAP, far above the 3-in-a-row
                    // threshold that was firing on transient provider 500s.
                    if (consecutiveFailures >= CONSECUTIVE_SYNC_FAILURE_HARD_CAP) {
                        log.error(
                            "Stopping sync pass: {} consecutive action failures " +
                                "(last: {} on {}) — treating as upstream outage",
                            consecutiveFailures,
                            e.javaClass.simpleName,
                            action.path,
                        )
                        throw ProviderException(
                            "Stopping sync after $consecutiveFailures consecutive failures",
                            e,
                        )
                    }
                    // Exponential backoff capped at 10s so the pass doesn't
                    // stall indefinitely on a failure cluster.
                    delay(minOf(2_000L * consecutiveFailures, 10_000L))
                }
                reporter.onActionProgress(completedActions.incrementAndGet(), actions.size, actionLabel(action), displayPath(action))
            }
            db.commitBatch()
        } catch (e: Exception) {
            db.rollbackBatch()
            throw e
        }

        // UD-236: refresh-mode short-circuit. Pass 1 (metadata) is done; transfers
        // remain pending in the DB as remoteId=null / isHydrated=false rows. The
        // pending_cursor stays unpromoted so the next `apply` (or `sync`) finalises.
        if (skipTransfers) {
            val pendingTransfers = actions.count { it is SyncAction.DownloadContent || it is SyncAction.Upload }
            log.info(
                "Refresh mode: skipping Pass 2; {} pending transfer(s) deferred (downloads + uploads)",
                pendingTransfers,
            )
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(
                downloaded = 0,
                uploaded = 0,
                conflicts = conflicts.get(),
                durationMs = duration,
                actionCounts = actions.groupingBy { actionLabel(it) }.eachCount(),
                failed = passOneFailures.get(),
            )
            return
        }

        // Pass 2: concurrent transfers (downloads and uploads)
        // UD-222: Pass 2 now carries all hydration for new/modified remote files. Failures are
        // tracked so we (a) rethrow AuthenticationException after the scope exits cleanly, and
        // (b) skip cursor promotion when any transfer failed — otherwise Graph's delta would
        // advance past the failed items and they'd never retry.
        val transferActions =
            actions.filter {
                it is SyncAction.DownloadContent || it is SyncAction.Upload
            }
        val transferFailures = AtomicInteger(0)
        val authFailure =
            java.util.concurrent.atomic
                .AtomicReference<AuthenticationException?>(null)
        try {
            coroutineScope {
                for (action in transferActions) {
                    when (action) {
                        is SyncAction.DownloadContent -> {
                            launch {
                                transferSemaphore.withPermit {
                                    try {
                                        applyDownload(action)
                                        downloaded.incrementAndGet()
                                    } catch (e: AuthenticationException) {
                                        // UD-253: include exception class + full stack.
                                        log.error(
                                            "Authentication failed during download of {}: {}: {}",
                                            action.path,
                                            e.javaClass.simpleName,
                                            e.message,
                                            e,
                                        )
                                        restoreToPlaceholder(action.path, action.remoteItem)
                                        transferFailures.incrementAndGet()
                                        authFailure.compareAndSet(null, e)
                                        this@coroutineScope.cancel()
                                    } catch (e: CancellationException) {
                                        restoreToPlaceholder(action.path, action.remoteItem)
                                        throw e
                                    } catch (e: Exception) {
                                        // UD-253: class name + throwable (SLF4J stack trace).
                                        log.warn(
                                            "Download failed for {}: {}: {}",
                                            action.path,
                                            e.javaClass.simpleName,
                                            e.message,
                                            e,
                                        )
                                        reporter.onWarning("Failed: ${action.path} - ${e.message}")
                                        logFailure(action, e)
                                        restoreToPlaceholder(action.path, action.remoteItem)
                                        transferFailures.incrementAndGet()
                                    } finally {
                                        reporter.onActionProgress(
                                            completedActions.incrementAndGet(),
                                            actions.size,
                                            actionLabel(action),
                                            displayPath(action),
                                        )
                                    }
                                }
                            }
                        }
                        is SyncAction.Upload -> {
                            launch {
                                transferSemaphore.withPermit {
                                    try {
                                        applyUpload(action)
                                        uploaded.incrementAndGet()
                                    } catch (e: AuthenticationException) {
                                        // UD-253: include exception class + full stack.
                                        log.error(
                                            "Authentication failed during upload of {}: {}: {}",
                                            action.path,
                                            e.javaClass.simpleName,
                                            e.message,
                                            e,
                                        )
                                        transferFailures.incrementAndGet()
                                        authFailure.compareAndSet(null, e)
                                        this@coroutineScope.cancel()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // UD-253: class name + throwable (SLF4J stack trace).
                                        log.warn(
                                            "Upload failed for {}: {}: {}",
                                            action.path,
                                            e.javaClass.simpleName,
                                            e.message,
                                            e,
                                        )
                                        reporter.onWarning("Failed: ${action.path} - ${e.message}")
                                        logFailure(action, e)
                                        transferFailures.incrementAndGet()
                                    } finally {
                                        reporter.onActionProgress(
                                            completedActions.incrementAndGet(),
                                            actions.size,
                                            actionLabel(action),
                                            displayPath(action),
                                        )
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: CancellationException) {
            // Scope cancelled by an AuthenticationException in a child job — the rethrow happens
            // just below so callers see the auth failure, not the cancellation.
            if (authFailure.get() == null) throw e
        }

        // UD-222: AuthenticationException trumps everything — surface it to the caller so token
        // refresh / re-auth flows trigger instead of a silent "0 downloaded, many failed" result.
        authFailure.get()?.let { throw it }

        // Persist cursor only if every transfer succeeded. A single failed download would
        // otherwise be lost forever: the server-side delta cursor advances past it and future
        // syncs would not re-see it as "new". Failed items keep pending_cursor → next sync redoes
        // the delta from the previous promoted cursor.
        //
        // UD-901e additionally gates promotion on the gather pass being complete
        // (see promotePendingCursorIfComplete).
        if (transferFailures.get() == 0) {
            promotePendingCursorIfComplete()
        }

        val duration = System.currentTimeMillis() - startTime
        reporter.onSyncComplete(
            downloaded.get(),
            uploaded.get(),
            conflicts.get(),
            duration,
            failed = passOneFailures.get() + transferFailures.get(),
        )
    }

    private suspend fun gatherRemoteChanges(): Map<String, CloudItem> {
        val storedCursor = db.getSyncState("delta_cursor")
        val cursor = storedCursor?.ifEmpty { null }
        val isFullSync = cursor == null
        val changes = mutableMapOf<String, CloudItem>()

        // UD-223 fast-bootstrap: on first-sync only, adopt the remote's current
        // cursor without enumerating. Provider must declare FastBootstrap; otherwise
        // log a warning and fall through. On success, the subsequent full-sync
        // deletion sweep (detectMissingAfterFullSync) is skipped — no enumeration
        // means no authoritative item set to diff against, so we must NOT treat
        // absence as deletion.
        if (fastBootstrap && isFullSync) {
            if (Capability.FastBootstrap in provider.capabilities()) {
                when (val result = provider.deltaFromLatest()) {
                    is CapabilityResult.Success -> {
                        val page = result.value
                        for (item in page.items) {
                            val resolved = resolveItemPath(item) ?: continue
                            changes[resolved.path] = resolved
                        }
                        // UD-223: promote the cursor directly. Bootstrap guarantees no transfers
                        // fire on this run (the action list is empty by construction), so the
                        // usual "pending → delta after zero failures" dance is skipped —
                        // otherwise syncOnce's `if (actions.isEmpty()) return` short-circuits
                        // the promotion and the next run re-bootstraps forever.
                        db.setSyncState("delta_cursor", page.cursor)
                        db.setSyncState("last_full_scan", Instant.now().toString())
                        val stamp = Instant.now().toString()
                        val msg =
                            "UD-223 fast-bootstrap: adopted remote cursor as of $stamp. " +
                                "Items that already exist on the remote will stay invisible until they next mutate. " +
                                "Upload-direction sync is unaffected."
                        log.warn(msg)
                        reporter.onWarning(msg)
                        return changes
                    }
                    is CapabilityResult.Unsupported -> {
                        log.warn(
                            "UD-223 fast-bootstrap requested but provider '{}' does not support it ({}). " +
                                "Falling back to full first-sync enumeration.",
                            providerId,
                            result.reason,
                        )
                    }
                }
            } else {
                log.warn(
                    "UD-223 fast-bootstrap requested but provider '{}' does not declare the capability. " +
                        "Falling back to full first-sync enumeration.",
                    providerId,
                )
            }
        }

        // If includeShared is requested but the provider doesn't actually support
        // delta-with-shared, silently fall back to plain delta — this is the
        // long-standing behaviour preserved across the UD-301 refactor.
        val useShared =
            includeShared &&
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
            reporter.onScanProgress("remote", itemsSoFar)
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

        // UD-901e: persist the running completeness flag alongside the cursor.
        // The 3 cursor-promotion sites (apply / dry-run / empty-action) check
        // `pending_cursor_complete` and refuse to promote when any page in the
        // gather pass returned complete=false — otherwise a transient subtree
        // 500/503 would bake the incomplete sweep's cursor into delta_cursor,
        // and the next sync would resume from a point past items it never
        // enumerated. Stored as the string "true"/"false"; absent is treated
        // as "true" by the promotion guards for backwards-compat with state.db
        // files that predate this flag.
        fun persistPendingCursor(cursor: String) {
            db.setSyncState("pending_cursor", cursor)
            db.setSyncState("pending_cursor_complete", if (allComplete) "true" else "false")
        }

        var page = nextPage(cursor)
        for (item in page.items) {
            val resolved = resolveItemPath(item) ?: continue
            changes[resolved.path] = resolved
        }
        persistPendingCursor(page.cursor)
        // UD-742: heartbeat after each remote page. Internxt paginates 50/page,
        // so a 113k-item drive emits ~2260 update events — cheap, and the
        // reporter is responsible for throttling display (CliProgressReporter
        // overwrites the same line via printInline).
        reporter.onScanProgress("remote", changes.size)

        while (page.hasMore) {
            page = nextPage(page.cursor)
            for (item in page.items) {
                val resolved = resolveItemPath(item) ?: continue
                changes[resolved.path] = resolved
            }
            persistPendingCursor(page.cursor)
            reporter.onScanProgress("remote", changes.size)
        }

        // Staged inventory has been consumed by this gather pass — the live
        // sync_entries write happens downstream via updateRemoteEntries — so
        // the per-page durability slice can be cleared. A daemon crash now,
        // or on any subsequent step, will retry from the freshly-persisted
        // delta_cursor (or pending_cursor if no transfers ran), not from the
        // staged offsets.
        db.completeScan(scanId)

        if (isFullSync && allComplete) {
            detectMissingAfterFullSync(changes)
        } else if (isFullSync) {
            val msg =
                "UD-360: at least one delta page returned complete=false; " +
                    "skipping detectMissingAfterFullSync to avoid spurious del-local actions. " +
                    "The missing inventory will be picked up on the next sync run."
            log.warn(msg)
            reporter.onWarning(msg)
        }
        return changes
    }

    /**
     * UD-901e: promote `pending_cursor` to `delta_cursor` only when the gather
     * pass that produced it was complete. A `pending_cursor_complete` value
     * of "false" means at least one delta page returned `complete=false`
     * (currently: Internxt subtree 500/503 skip path), and promoting that
     * cursor would silently skip items in the failed subtree on the next run.
     * Absent flag is treated as complete=true so the gate is backwards-compat
     * with state.db files written before this flag landed.
     *
     * Returns true if promotion happened; false if it was withheld. Callers
     * use the return value only for `last_full_scan` bookkeeping.
     */
    private fun promotePendingCursorIfComplete(): Boolean {
        val pendingCursor = db.getSyncState("pending_cursor") ?: return false
        val complete = db.getSyncState("pending_cursor_complete") ?: "true"
        if (complete != "true") {
            log.warn(
                "UD-901e: withholding pending_cursor promotion — gather pass was incomplete. " +
                    "delta_cursor stays at its previous value so missed inventory is re-enumerated next run.",
            )
            return false
        }
        db.setSyncState("delta_cursor", pendingCursor)
        db.setSyncState("last_full_scan", Instant.now().toString())
        return true
    }

    /**
     * Deleted items from OneDrive personal have no name/parentReference.path,
     * so toCloudItem() produces path="/". Recover the real path from the DB by remoteId.
     * Returns null for items that should be skipped (drive root, unknown deleted items).
     */
    private fun resolveItemPath(item: CloudItem): CloudItem? {
        if (item.path != "/" && item.path.isNotEmpty()) return item
        if (!item.deleted) return null // non-deleted root item, skip
        val entry = db.getEntryByRemoteId(item.id) ?: return null
        log.debug("Resolved deleted item id={} to path={}", item.id, entry.path)
        return item.copy(path = entry.path, name = entry.path.substringAfterLast("/"))
    }

    /**
     * After a full delta (cursor was null), the response contains ALL items in the drive.
     * Any DB entry whose remoteId is NOT in the full set must have been deleted.
     * This is a pure set comparison — zero extra API calls.
     */
    private fun detectMissingAfterFullSync(remoteChanges: MutableMap<String, CloudItem>) {
        val seenRemoteIds = remoteChanges.values.mapTo(mutableSetOf()) { it.id }

        for (entry in db.getAllEntries()) {
            if (entry.remoteId == null) continue
            if (entry.remoteId in seenRemoteIds) continue
            if (entry.path in remoteChanges) continue

            log.debug("Full sync: DB entry {} (remoteId={}) not in delta, marking deleted", entry.path, entry.remoteId)
            remoteChanges[entry.path] =
                CloudItem(
                    id = entry.remoteId,
                    name = entry.path.substringAfterLast("/"),
                    path = entry.path,
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

    private suspend fun applyCreatePlaceholder(action: SyncAction.CreatePlaceholder) {
        // UD-222: under the new routing (Reconciler), CreatePlaceholder is only emitted for
        //   - folders (mkdir)
        //   - "both new" adopt (local file already matches remote size)
        //   - conflict-resolution internal invocations that want a downloaded file
        // Non-folder remote-new/remote-modified goes through DownloadContent in Pass 2. The
        // `shouldHydrate` field is vestigial — download now triggers whenever the local side
        // lacks real content, so that sparse leftovers from an interrupted sync get re-hydrated
        // instead of silently adopted as NUL bytes.
        val item = action.remoteItem
        val localPath = placeholder.resolveLocal(action.path)
        val sizeMatch = !item.isFolder && Files.isRegularFile(localPath) && Files.size(localPath) == item.size
        val hasRealContent = sizeMatch && !placeholder.isSparse(localPath, item.size)
        val shouldDownload = !item.isFolder && item.size > 0 && !hasRealContent

        withEchoSuppression(action.path) {
            if (item.isFolder) {
                placeholder.createFolder(action.path, item.modified)
            } else if (!sizeMatch) {
                placeholder.createPlaceholder(action.path, item.size, item.modified)
            }

            if (shouldDownload) {
                // UD-225b: prefer id-based dispatch — path-based download triggers
                // a per-segment folder traversal in resolveFolder which can fail
                // on transient 503s or sanitization edge cases (UD-317).
                downloadByIdOrPath(item, action.path, localPath)
                placeholder.restoreMtime(action.path, item.modified)
            }
        }

        val isHydrated = hasRealContent || item.size == 0L || shouldDownload
        db.upsertEntry(entryFromCloudItem(item, action.path, isHydrated))
    }

    private suspend fun applyUpdatePlaceholder(action: SyncAction.UpdatePlaceholder) {
        val item = action.remoteItem
        if (versionManager != null && action.wasHydrated && !item.isFolder) {
            val localPath = placeholder.resolveLocal(action.path)
            if (Files.isRegularFile(localPath) && Files.size(localPath) > 0) {
                versionManager.snapshot(action.path)
                versionManager.pruneByCount(action.path, maxVersions)
            }
        }
        withEchoSuppression(action.path) {
            if (action.wasHydrated && !item.isFolder) {
                try {
                    // UD-225b: id-based dispatch (see applyCreatePlaceholder).
                    downloadByIdOrPath(item, action.path, placeholder.resolveLocal(action.path))
                    placeholder.restoreMtime(action.path, item.modified)
                } catch (e: Exception) {
                    restoreToPlaceholder(action.path, item)
                    throw e
                }
            } else if (!item.isFolder) {
                placeholder.updatePlaceholderMetadata(action.path, item.size, item.modified)
            }
        }

        db.upsertEntry(entryFromCloudItem(item, action.path, action.wasHydrated))
    }

    /**
     * UD-225b: prefer id-based download dispatch when the action's CloudItem
     * carries a remoteId. Path-based `provider.download(remotePath, dest)`
     * forces providers like Internxt to walk the folder tree segment-by-
     * segment via `getFolderContents` (`InternxtProvider.resolveFolder`),
     * which is wasteful (one round-trip per path component) and fragile
     * (any segment that fails sanitization or hits a transient 503 fails
     * the whole download with `Folder not found: <segment>`). Live impact
     * 2026-05-03 on the UD-225a recovery sync: ~44 % of 1,426 files failed
     * with that error before this fix.
     *
     * The `CloudProvider.downloadById` default delegates to path-based
     * `download` for providers that don't override, so the change is safe
     * across the SPI: providers with a real id-based path (Internxt's
     * Bridge fileUuid lookup, OneDrive Graph item-id GET) take it; others
     * keep existing semantics.
     *
     * Empty `item.id` falls through to path-based download — defensive for
     * synthesized CloudItems where `entry.remoteId` was null.
     */
    private suspend fun downloadByIdOrPath(
        item: CloudItem,
        remotePath: String,
        destination: java.nio.file.Path,
    ): Long =
        if (item.id.isNotEmpty()) {
            provider.downloadById(item.id, remotePath, destination)
        } else {
            provider.download(remotePath, destination)
        }

    private suspend fun applyDownload(action: SyncAction.DownloadContent) {
        log.debug("Download: {} ({} bytes)", action.path, action.remoteItem.size)
        val localPath = placeholder.resolveLocal(action.path)
        if (versionManager != null && Files.isRegularFile(localPath) && Files.size(localPath) > 0) {
            versionManager.snapshot(action.path)
            versionManager.pruneByCount(action.path, maxVersions)
        }
        // UD-113: capture pre-action hash for the audit oldHash field.
        val prevHash = db.getEntry(action.path)?.remoteHash
        try {
            withEchoSuppression(action.path) {
                // UD-225b: id-based dispatch — see applyCreatePlaceholder for rationale.
                downloadByIdOrPath(action.remoteItem, action.path, localPath)
                placeholder.restoreMtime(action.path, action.remoteItem.modified)
            }

            if (verifyIntegrity) {
                val verified = HashVerifier.verify(localPath, action.remoteItem.hash, algorithm = provider.hashAlgorithm())
                if (!verified) {
                    reporter.onWarning("Integrity check failed: ${action.path}")
                }
            }

            db.upsertEntry(entryFromCloudItem(action.remoteItem, action.path, isHydrated = true))
            auditLog?.emit(
                action = "Download",
                path = action.path,
                size = action.remoteItem.size,
                oldHash = prevHash,
                newHash = action.remoteItem.hash,
                result = "success",
            )
        } catch (e: Exception) {
            auditLog?.emit(
                action = "Download",
                path = action.path,
                size = action.remoteItem.size,
                oldHash = prevHash,
                result = "failed:${e.javaClass.simpleName}: ${e.message}",
            )
            throw e
        }
    }

    private suspend fun applyUpload(action: SyncAction.Upload) {
        val localPath = placeholder.resolveLocal(action.path)
        val sizeForLog = if (Files.isRegularFile(localPath)) Files.size(localPath) else -1L
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Upload: {} ({} bytes)", action.path, sizeForLog)
        // UD-113: capture pre-action remote hash so the audit entry's oldHash reflects
        // the version we replaced (or null for a fresh upload).
        val prevHash = db.getEntry(action.path)?.remoteHash
        val result =
            try {
                // UD-366: action.remoteId carries the existing remote UUID for MODIFIED uploads;
                // null for NEW. Internxt routes through PUT /files/{uuid} when non-null.
                provider.upload(localPath, action.path, existingRemoteId = action.remoteId) { transferred, total ->
                    reporter.onTransferProgress(action.path, transferred, total)
                }
            } catch (e: Exception) {
                auditLog?.emit(
                    action = "Upload",
                    path = action.path,
                    size = if (sizeForLog >= 0) sizeForLog else null,
                    oldHash = prevHash,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
        val mtime = Files.getLastModifiedTime(localPath).toMillis()
        val size = Files.size(localPath)
        db.upsertEntry(
            SyncEntry(
                path = action.path,
                remoteId = result.id,
                remoteHash = result.hash,
                remoteSize = result.size,
                remoteModified = result.modified,
                localMtime = mtime,
                localSize = size,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        // UD-113: success path. Failure path emits inside the try/catch above.
        auditLog?.emit(
            action = "Upload",
            path = action.path,
            size = size,
            oldHash = prevHash,
            newHash = result.hash,
            result = "success",
        )
    }

    private suspend fun applyMoveRemote(action: SyncAction.MoveRemote) {
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Move: {} -> {}", action.fromPath, action.path)
        val oldEntry = db.getEntry(action.fromPath)
        val isFolder = oldEntry?.isFolder ?: false
        val result =
            try {
                provider.move(action.fromPath, action.path)
            } catch (e: Exception) {
                auditLog?.emit(
                    action = "Move",
                    path = action.path,
                    fromPath = action.fromPath,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
        val localPath = placeholder.resolveLocal(action.path)
        val mtime = if (Files.exists(localPath)) Files.getLastModifiedTime(localPath).toMillis() else 0L
        val size =
            if (isFolder) {
                0L
            } else if (Files.exists(localPath)) {
                Files.size(localPath)
            } else {
                oldEntry?.remoteSize ?: 0L
            }
        db.deleteEntry(action.fromPath)
        // UD-901d: renamePrefix() must run BEFORE the destination upsert.
        // renamePrefix's UD-901c cleanup phase deletes every row at the
        // new prefix (including `newPrefix.removeSuffix("/")` — the folder
        // root row itself) to clear out pre-existing LocalScanner UD-901
        // placeholder rows that would otherwise collide with the UPDATE.
        // If we upserted first and renamed second, the cleanup would wipe
        // the canonical destination row we just wrote, leaving the moved
        // folder with no remoteId/metadata in state.db — every subsequent
        // scan would treat the folder as untracked and try to re-create
        // it remotely. Upserting AFTER the rename keeps cleanup honest
        // and the canonical row intact.
        if (isFolder) {
            db.renamePrefix(action.fromPath, action.path)
        }
        db.upsertEntry(
            SyncEntry(
                path = action.path,
                remoteId = result.id,
                remoteHash = result.hash,
                remoteSize = oldEntry?.remoteSize ?: result.size,
                remoteModified = result.modified,
                localMtime = mtime,
                localSize = size,
                isFolder = isFolder,
                isPinned = false,
                isHydrated = oldEntry?.isHydrated ?: true,
                lastSynced = Instant.now(),
            ),
        )
        // UD-113: success path for the remote-side rename/move.
        auditLog?.emit(
            action = "Move",
            path = action.path,
            fromPath = action.fromPath,
            oldHash = oldEntry?.remoteHash,
            newHash = result.hash,
            result = "success",
        )
    }

    private fun applyMoveLocal(action: SyncAction.MoveLocal) {
        val oldLocal = placeholder.resolveLocal(action.fromPath)
        val newLocal = placeholder.resolveLocal(action.path)
        val oldEntry = db.getEntry(action.fromPath)
        val isFolder = action.remoteItem.isFolder

        // Suppress both old and new paths to avoid echo events from the move
        withEchoSuppression(action.fromPath) {
            withEchoSuppression(action.path) {
                Files.createDirectories(newLocal.parent)
                if (Files.exists(oldLocal)) {
                    Files.move(oldLocal, newLocal, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else if (!isFolder) {
                    placeholder.createPlaceholder(action.path, action.remoteItem.size, action.remoteItem.modified)
                } else {
                    Files.createDirectories(newLocal)
                }
            }
        }

        db.deleteEntry(action.fromPath)
        // UD-901d: renamePrefix() must run BEFORE the destination upsert —
        // see applyMoveRemote for the full rationale. Same bug class:
        // renamePrefix's UD-901c cleanup deletes the destination root row
        // it doesn't know was meant to be canonical.
        if (isFolder) {
            db.renamePrefix(action.fromPath, action.path)
        }
        db.upsertEntry(entryFromCloudItem(action.remoteItem, action.path, oldEntry?.isHydrated ?: false))
    }

    private fun applyDeleteLocal(action: SyncAction.DeleteLocal) {
        withEchoSuppression(action.path) {
            if (trashManager != null) {
                trashManager.trash(action.path)
            } else if (useTrash) {
                Trash.trash(placeholder.resolveLocal(action.path))
            } else {
                placeholder.deleteLocal(action.path)
            }
        }
        // The cloud cascaded a delete to us; flip the DB row to TRASHED rather
        // than hard-delete so recovery can answer "what was recently deleted?"
        // with one SELECT. Pre-redesign rows without a real remote_id fall
        // back to hard delete (nothing to tombstone).
        val priorEntry = db.getEntry(action.path)
        val remoteId = priorEntry?.remoteId
        if (remoteId != null) {
            db.setStatusTrashed(remoteId)
        } else {
            db.deleteEntry(action.path)
        }
    }

    private suspend fun applyDeleteRemote(action: SyncAction.DeleteRemote) {
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Delete: {}", action.path)
        // UD-113: capture pre-delete row for the audit entry.
        val priorEntry = db.getEntry(action.path)
        var auditResult = "success"
        try {
            provider.delete(action.path)
        } catch (e: ProviderException) {
            log.debug("DeleteRemote skipped for ${action.path}: ${e.message}")
            auditResult = "skipped:${e.message}"
        } catch (e: Exception) {
            auditLog?.emit(
                action = "Delete",
                path = action.path,
                size = priorEntry?.remoteSize,
                oldHash = priorEntry?.remoteHash,
                result = "failed:${e.javaClass.simpleName}: ${e.message}",
            )
            throw e
        }
        // Provider-initiated cloud delete — flip to TRASHED so the row survives
        // as a queryable tombstone. Internxt's `delete` routes through the
        // recycle bin (`POST /storage/trash/add`); a recovery SELECT on TRASHED
        // rows + a batched untrash PATCH is the documented restore path.
        val remoteId = priorEntry?.remoteId
        if (remoteId != null) {
            db.setStatusTrashed(remoteId)
        } else {
            // No remote_id on file (pre-redesign synthetic, or stale row that
            // never carried one). Nothing to tombstone — fall through to
            // hard delete so the path doesn't dangle.
            db.deleteEntry(action.path)
        }
        auditLog?.emit(
            action = "Delete",
            path = action.path,
            size = priorEntry?.remoteSize,
            oldHash = priorEntry?.remoteHash,
            result = auditResult,
        )
    }

    /**
     * Two flavours, distinguished by the alive row's `remoteId`:
     *  - pending-upload row that vanished before reaching the cloud
     *    (`remoteId == null`) → hard delete (nothing to tombstone).
     *  - real cloud row that's already gone on the remote side
     *    (`remoteId != null`, "both deleted" cascade) → flip to TRASHED.
     */
    private fun applyRemoveEntry(action: SyncAction.RemoveEntry) {
        val entry = db.getEntry(action.path)
        val remoteId = entry?.remoteId
        if (remoteId != null) {
            db.setStatusTrashed(remoteId)
        } else {
            db.deleteEntry(action.path)
        }
    }

    private suspend fun applyCreateRemoteFolder(action: SyncAction.CreateRemoteFolder) {
        val result =
            try {
                provider.createFolder(action.path)
            } catch (e: Exception) {
                auditLog?.emit(
                    action = "CreateRemoteFolder",
                    path = action.path,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
        db.upsertEntry(
            SyncEntry(
                path = action.path,
                remoteId = result.id,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = result.modified,
                localMtime = Files.getLastModifiedTime(placeholder.resolveLocal(action.path)).toMillis(),
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        // UD-113: success path.
        auditLog?.emit(
            action = "CreateRemoteFolder",
            path = action.path,
            result = "success",
        )
    }

    private suspend fun applyConflict(action: SyncAction.Conflict) {
        log.warn("Conflict on ${action.path}: local=${action.localState}, remote=${action.remoteState}")

        when (action.policy) {
            ConflictPolicy.KEEP_BOTH -> {
                applyKeepBoth(action)
                conflictLog?.record(
                    path = action.path,
                    localState = action.localState.name,
                    remoteState = action.remoteState.name,
                    policy = "KEEP_BOTH",
                    loserFile = null,
                )
            }
            ConflictPolicy.LAST_WRITER_WINS -> applyLastWriterWins(action)
        }
    }

    private suspend fun applyKeepBoth(action: SyncAction.Conflict) {
        val timestamp =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now())

        val localPath = placeholder.resolveLocal(action.path)
        val ext = action.path.substringAfterLast(".", "")
        val base = action.path.substringBeforeLast(".")
        val conflictSuffix =
            if (ext.isNotEmpty()) {
                ".conflict-remote-$timestamp.$ext"
            } else {
                ".conflict-remote-$timestamp"
            }

        if (action.remoteItem != null && !action.remoteItem.deleted) {
            val conflictPath = "$base$conflictSuffix"
            val conflictLocal = placeholder.resolveLocal(conflictPath)
            withEchoSuppression(conflictPath) {
                // UD-225b: id-based dispatch (see applyCreatePlaceholder).
                downloadByIdOrPath(action.remoteItem, action.remoteItem.path, conflictLocal)
            }
            if (action.localState == ChangeState.NEW || action.localState == ChangeState.MODIFIED) {
                if (Files.exists(localPath)) {
                    val result =
                        // UD-366: COPY-MERGE conflict — remote already has a UUID; overwrite
                        // it in place rather than POSTing a duplicate that 409s.
                        provider.upload(
                            localPath,
                            action.path,
                            existingRemoteId = action.remoteItem.id,
                        ) { transferred, total ->
                            reporter.onTransferProgress(action.path, transferred, total)
                        }
                    val mtime = Files.getLastModifiedTime(localPath).toMillis()
                    db.upsertEntry(
                        SyncEntry(
                            path = action.path,
                            remoteId = result.id,
                            remoteHash = result.hash,
                            remoteSize = result.size,
                            remoteModified = result.modified,
                            localMtime = mtime,
                            localSize = Files.size(localPath),
                            isFolder = false,
                            isPinned = false,
                            isHydrated = true,
                            lastSynced = Instant.now(),
                        ),
                    )
                }
            }
        } else if (action.localState == ChangeState.DELETED && action.remoteItem != null) {
            // UD-222: remote wins the conflict — download real bytes, not a NUL stub.
            applyCreatePlaceholder(
                SyncAction.CreatePlaceholder(action.path, action.remoteItem, shouldHydrate = !action.remoteItem.isFolder),
            )
        } else if (action.remoteState == ChangeState.DELETED && Files.exists(localPath)) {
            val result =
                provider.upload(localPath, action.path) { transferred, total ->
                    reporter.onTransferProgress(action.path, transferred, total)
                }
            val mtime = Files.getLastModifiedTime(localPath).toMillis()
            db.upsertEntry(
                SyncEntry(
                    path = action.path,
                    remoteId = result.id,
                    remoteHash = result.hash,
                    remoteSize = result.size,
                    remoteModified = result.modified,
                    localMtime = mtime,
                    localSize = Files.size(localPath),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
        }
    }

    private suspend fun applyLastWriterWins(action: SyncAction.Conflict) {
        val localPath = placeholder.resolveLocal(action.path)
        val localMtime = if (Files.exists(localPath)) Files.getLastModifiedTime(localPath).toInstant() else null
        val remoteMtime = action.remoteItem?.modified

        val remoteWins =
            when {
                localMtime == null -> true
                remoteMtime == null -> false
                else -> remoteMtime.isAfter(localMtime)
            }

        val loserFile = if (remoteWins && Files.exists(localPath)) localPath else null
        conflictLog?.record(
            path = action.path,
            localState = action.localState.name,
            remoteState = action.remoteState.name,
            policy = "LAST_WRITER_WINS",
            loserFile = loserFile,
        )

        if (remoteWins && action.remoteItem != null && !action.remoteItem.deleted) {
            applyCreatePlaceholder(SyncAction.CreatePlaceholder(action.path, action.remoteItem, shouldHydrate = true))
        } else if (!remoteWins && Files.exists(localPath)) {
            // UD-366: conflict-loser is the remote — overwrite it in place rather than
            // POSTing a duplicate. action.remoteItem is non-null on this branch because
            // remoteWins=false implies a remote modification was the conflict trigger.
            applyUpload(SyncAction.Upload(action.path, remoteId = action.remoteItem?.id))
        }
    }

    private fun entryFromCloudItem(
        item: CloudItem,
        path: String,
        isHydrated: Boolean,
    ) = SyncEntry(
        path = path,
        remoteId = item.id,
        remoteHash = item.hash,
        remoteSize = item.size,
        remoteModified = item.modified,
        localMtime = placeholder.localMtime(path),
        localSize = placeholder.localSize(path),
        isFolder = item.isFolder,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = Instant.now(),
    )

    private fun updateRemoteEntries(remoteChanges: Map<String, CloudItem>) {
        for ((path, item) in remoteChanges) {
            if (item.deleted) continue // skip deleted items
            val existing = db.getEntry(path)
            val merged =
                existing?.copy(
                    remoteId = item.id,
                    remoteHash = item.hash,
                    remoteSize = item.size,
                    remoteModified = item.modified,
                    lastSynced = Instant.now(),
                    // preserve localMtime, localSize, isHydrated, isFolder
                ) ?: SyncEntry(
                    path = path,
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
            db.upsertEntry(merged)
        }
    }

    private fun actionLabel(action: SyncAction): String =
        when (action) {
            is SyncAction.CreatePlaceholder ->
                when {
                    action.remoteItem.isFolder -> "mkdir"
                    Files.isRegularFile(placeholder.resolveLocal(action.path)) &&
                        Files.size(placeholder.resolveLocal(action.path)) == action.remoteItem.size -> "adopt"
                    else -> "placeholder"
                }
            is SyncAction.UpdatePlaceholder -> "update"
            is SyncAction.DownloadContent -> "down"
            is SyncAction.MoveRemote -> "move"
            is SyncAction.MoveLocal -> "move"
            is SyncAction.Upload -> "up"
            is SyncAction.DeleteLocal -> "del-local"
            is SyncAction.DeleteRemote -> "del-remote"
            is SyncAction.CreateRemoteFolder -> "mkdir-remote"
            is SyncAction.Conflict -> "CONFLICT"
            is SyncAction.RemoveEntry -> "cleanup"
        }

    // UD-740: display path used in the user-facing progress line. For move
    // actions, expand to `from -> to` so the operator can see what was
    // renamed where; for everything else it's just `action.path`. The
    // failures.jsonl JSON layer keeps `path` clean (destination) and adds
    // `from_path` when relevant — see logFailure.
    private fun displayPath(action: SyncAction): String =
        when (action) {
            is SyncAction.MoveRemote -> "${action.fromPath} -> ${action.path}"
            is SyncAction.MoveLocal -> "${action.fromPath} -> ${action.path}"
            else -> action.path
        }

    private fun restoreToPlaceholder(
        remotePath: String,
        item: CloudItem,
    ) {
        try {
            withEchoSuppression(remotePath) {
                placeholder.createPlaceholder(remotePath, item.size, item.modified)
            }
            // UD-222: if no prior DB entry existed (first-time download that failed), create one
            // marked non-hydrated so the next sync re-attempts. Without this, a failed first-time
            // download left zero trace in the DB and the cursor-promotion guard was the only
            // safety net.
            val entry = db.getEntry(remotePath)
            val next =
                entry?.copy(isHydrated = false, remoteSize = item.size, remoteModified = item.modified)
                    ?: entryFromCloudItem(item, remotePath, isHydrated = false)
            db.upsertEntry(next)
        } catch (e: Exception) {
            // UD-253: class name + throwable for diagnostics.
            log.warn(
                "Could not restore placeholder for {} after cancel: {}: {}",
                remotePath,
                e.javaClass.simpleName,
                e.message,
                e,
            )
        }
    }

    private fun syncPathAncestors(path: String): Set<String> {
        val parts = path.trimStart('/').split('/')
        val ancestors = mutableSetOf<String>()
        for (i in 1 until parts.size) {
            ancestors.add("/" + parts.subList(0, i).joinToString("/"))
        }
        return ancestors
    }

    // UD-256: read the persisted `effective_scope` list (TAB-separated, no entries
    // contain TAB — state.db inspection 2026-05-17 confirmed zero paths with
    // control characters). Empty string / missing key both mean "no scope ever
    // persisted" → return empty list (no constraint).
    private fun loadEffectiveScope(): List<String> {
        val raw = db.getSyncState("effective_scope") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split("\t").filter { it.isNotEmpty() }
    }

    // UD-264: extract the top-level cloud-path segment from an absolute path.
    // `/Documents/CyberLink/Foo` -> `/Documents`. Root-only paths (`/`, ``) or
    // single-segment paths under root (`/Foo`) return their own value as the
    // top-level. Returns null for paths that don't start with `/`, which never
    // happens in production but keeps callers honest.
    private fun topLevelOf(path: String): String? {
        if (!path.startsWith("/")) return null
        val trimmed = path.trimStart('/')
        if (trimmed.isEmpty()) return null
        val firstSlash = trimmed.indexOf('/')
        return "/" + if (firstSlash < 0) trimmed else trimmed.substring(0, firstSlash)
    }

    // UD-264: filter `del-remote` actions against the top-level-never-hydrated
    // guard. For each candidate, identify its top-level segment; if state.db
    // has zero rows under that top-level with is_hydrated=1 OR local_mtime
    // IS NOT NULL, the top-level has never been locally hydrated and the
    // delete is dropped (or, with --ignore-top-level-guard, kept but logged).
    // Cache per (top-level, run) so a wide-blast plan doesn't hammer the DB.
    // DeleteLocal is NOT covered here — the guard's purpose is to refuse
    // *cloud-side* deletes triggered by a partial-local-tree reconciliation.
    private fun applyTopLevelHydrationGuard(actions: List<SyncAction>): List<SyncAction> {
        if (actions.none { it is SyncAction.DeleteRemote }) return actions
        val hydrationCache = mutableMapOf<String, Boolean>()
        val kept = mutableListOf<SyncAction>()
        var skipped = 0
        for (action in actions) {
            if (action !is SyncAction.DeleteRemote) {
                kept.add(action)
                continue
            }
            val top = topLevelOf(action.path)
            if (top == null) {
                kept.add(action)
                continue
            }
            val everHydrated =
                hydrationCache.getOrPut(top) {
                    db.hasHydratedDescendant(top)
                }
            if (everHydrated) {
                kept.add(action)
            } else {
                // Log to skipped-ops.jsonl regardless of opt-out — operators
                // need the audit trail either way.
                logSkippedOp(action, "top_level_never_hydrated")
                if (ignoreTopLevelGuard) {
                    // Opt-out: keep the action in the plan (still logged).
                    kept.add(action)
                } else {
                    skipped++
                    log.warn(
                        "UD-264: skipping del-remote for {} — top-level '{}' has never had a hydrated descendant",
                        action.path,
                        top,
                    )
                }
            }
        }
        if (skipped > 0) {
            log.warn(
                "UD-264: skipped {} del-remote action(s) for never-hydrated top-level subtrees " +
                    "(see skipped-ops.jsonl). Pass --ignore-top-level-guard to override.",
                skipped,
            )
        }
        return kept
    }

    // UD-264: append a JSON line to skipped-ops.jsonl. Goes through
    // kotlinx.serialization.json so the output is always valid JSONL even
    // when an action's path contains JSON-special chars (`"`, `\`, newline).
    // PR #46 Codex P2 (2026-05-17): the prior hand-built triple-quoted
    // template broke parsing for valid Linux/cloud filenames — failures.jsonl
    // already had ~119 PARSE_ERR entries from the same bug class in
    // logFailure (separate ticket to follow). The audit-of-record observed a
    // path like `/\ninternxt-cli.desktop` in the wild, so this is not
    // hypothetical. No-op if no log path was wired (CLI sets it; tests that
    // don't care leave it null).
    //
    // The JSON-formatting is lifted into [formatSkippedOpJson] so a unit
    // test can exercise nasty paths without going through LocalScanner (which
    // would reject them at the OS-path layer on Windows).
    private fun logSkippedOp(
        action: SyncAction,
        reason: String,
    ) {
        val path = skippedOpsLogPath ?: return
        val line = formatSkippedOpJson(actionLabel(action), action.path, reason, Instant.now())
        Files.createDirectories(path.parent)
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    // UD-297: literally-empty syncRoot detector. Narrow on purpose — any
    // child entry (file, folder, hidden) makes this return false. The
    // broader "high deletion percentage" case is UD-298.
    private fun isSyncRootEffectivelyEmpty(): Boolean {
        if (!Files.exists(syncRoot)) return true
        if (!Files.isDirectory(syncRoot)) return true
        return Files.newDirectoryStream(syncRoot).use { !it.iterator().hasNext() }
    }

    // UD-299: case-insensitive compare on Windows (drive-letter case + NTFS
    // semantics), exact compare elsewhere. Both inputs are already
    // .toAbsolutePath().normalize() so separator and dot-segment normalisation
    // are taken care of.
    private fun sameSyncRoot(
        a: String,
        b: String,
    ): Boolean {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        return if (isWindows) a.equals(b, ignoreCase = true) else a == b
    }

    private fun logFailure(
        action: SyncAction,
        error: Exception,
    ) {
        val path = failureLogPath ?: return
        val kind = actionLabel(action)
        val msg = (error.message ?: error.javaClass.simpleName).replace("\"", "\\\"")
        // UD-740: include `from_path` for move actions so post-mortem readers
        // can see the rename source. `path` keeps its destination meaning.
        val fromSegment =
            when (action) {
                is SyncAction.MoveRemote -> ""","from_path":"${action.fromPath}""""
                is SyncAction.MoveLocal -> ""","from_path":"${action.fromPath}""""
                else -> ""
            }
        val line = """{"ts":"${Instant.now()}","action":"$kind","path":"${action.path}"$fromSegment,"error":"$msg"}"""
        Files.createDirectories(path.parent)
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun validateExcludePatterns(patterns: List<String>): List<String> {
        val valid = mutableListOf<String>()
        for (pattern in patterns) {
            try {
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
                valid.add(pattern)
            } catch (e: PatternSyntaxException) {
                log.warn("Invalid exclude pattern '{}', skipping: {}", pattern, e.message)
            }
        }
        return valid
    }

    companion object {
        /**
         * UD-248: catastrophic-failure threshold for the sequential-action
         * pass. Below this count, failing actions are skipped and the pass
         * continues (no more 3-in-a-row-equals-tear-down-and-rescan
         * behaviour). Above this count, we treat the run as an upstream
         * outage and stop the pass so the watch loop can back off.
         */
        const val CONSECUTIVE_SYNC_FAILURE_HARD_CAP: Int = 20

        /**
         * Age at which a resumable-scan checkpoint is considered stale and
         * discarded. Internxt's `updatedAt` cursor filter is a low-resolution
         * "what changed since X" query, so a six-hour gap is the rough
         * upstream change-detection window — past that, items that mutated
         * after the prior scan's cursor were captured then would silently
         * vanish from a resume. Default matches the BACKLOG entry; not
         * currently user-configurable.
         */
        const val SCAN_CHECKPOINT_STALE_HOURS: Long = 6L

        /**
         * Quiet-window for the remote-change wake debounce. A burst of 50
         * notification frames from a single folder-tree mutation should
         * trigger ONE delta walk, not 50. The window has to be long enough
         * to swallow a burst but short enough that the latency-vs-batch
         * trade-off still favours latency (the whole point of the WS feed
         * vs the multi-minute poll cadence). 5 s is the spec's lower
         * bound; pick it.
         */
        const val REMOTE_WAKE_DEBOUNCE_MS: Long = 5_000L

        /**
         * UD-264 / PR #46 Codex P2: format a `skipped-ops.jsonl` row using
         * kotlinx.serialization.json so the output is always valid JSONL
         * regardless of what the path / reason / action label contains.
         * Public for unit-testing with paths that the OS would reject at the
         * filesystem layer (`"`, `\`, newline — all banned on Windows but
         * legal on Linux/cloud).
         */
        internal fun formatSkippedOpJson(
            action: String,
            path: String,
            reason: String,
            ts: Instant,
        ): String =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("ts", kotlinx.serialization.json.JsonPrimitive(ts.toString()))
                    put("action", kotlinx.serialization.json.JsonPrimitive(action))
                    put("path", kotlinx.serialization.json.JsonPrimitive(path))
                    put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                }.toString()
    }
}
