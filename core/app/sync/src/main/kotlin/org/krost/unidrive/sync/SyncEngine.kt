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
) {
    private val log = LoggerFactory.getLogger(SyncEngine::class.java)
    private val effectiveExcludePatterns =
        validateExcludePatterns(
            excludePatterns + listOf("/.unidrive-trash/**", "/.unidrive-versions/**"),
        )
    private val scanner = LocalScanner(syncRoot, db, effectiveExcludePatterns)
    private val reconciler = Reconciler(db, syncRoot, conflictPolicy, conflictOverrides, effectiveExcludePatterns)

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
        val allActions = reconciler.reconcile(remoteChanges, localChanges, reporter, syncPath)

        // Persist remote metadata for reuse in subsequent runs (UD-260)
        db.batch {
            updateRemoteEntries(allRemoteChanges)
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

        reporter.onActionCount(actions.size)

        if (actions.isEmpty()) {
            // Promote pending cursor to delta_cursor so subsequent scans reuse remote state (UD-260)
            val pendingCursor = db.getSyncState("pending_cursor")
            if (pendingCursor != null) {
                db.setSyncState("delta_cursor", pendingCursor)
                db.setSyncState("last_full_scan", Instant.now().toString())
            }
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(0, 0, 0, duration)
            return
        }

        // Phase 2b: Deletion safeguard. UD-298: also evaluate in dry-run —
        // emit reporter.onWarning instead of throwing so the user still sees
        // the planned actions but cannot miss the warning. Non-dry-run
        // throws (current behaviour preserved).
        if (!forceDelete && maxDeletePercentage in 1..99) {
            val deleteCount = actions.count { it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal }
            val totalEntries = db.getEntryCount()
            if (totalEntries > 0 && deleteCount > 10) {
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
            // Promote pending cursor to delta_cursor so subsequent scans reuse remote state (UD-260)
            val pendingCursor = db.getSyncState("pending_cursor")
            if (pendingCursor != null) {
                db.setSyncState("delta_cursor", pendingCursor)
                db.setSyncState("last_full_scan", Instant.now().toString())
            }
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
                        is SyncAction.RemoveEntry -> db.deleteEntry(action.path)
                        else -> {}
                    }
                    consecutiveFailures = 0
                } catch (e: AuthenticationException) {
                    // UD-253: include exception class + full stack for auth failures.
                    log.error("Authentication failed, stopping sync: {}: {}", e.javaClass.simpleName, e.message, e)
                    throw e
                } catch (e: Exception) {
                    consecutiveFailures++
                    passOneFailures.incrementAndGet()
                    // UD-253: class name + throwable (SLF4J renders stack trace when the
                    // last arg is a Throwable) so WARNs are self-diagnosing in the log.
                    log.warn(
                        "Action failed for {} ({} consecutive): {}: {}",
                        action.path,
                        consecutiveFailures,
                        e.javaClass.simpleName,
                        e.message,
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
        val cursor = db.getSyncState("pending_cursor")
        if (cursor != null && transferFailures.get() == 0) {
            db.setSyncState("delta_cursor", cursor)
            db.setSyncState("last_full_scan", Instant.now().toString())
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

        suspend fun nextPage(c: String?): DeltaPage {
            val page =
                if (useShared) {
                    when (val r = provider.deltaWithShared(c)) {
                        is CapabilityResult.Success -> r.value
                        is CapabilityResult.Unsupported -> provider.delta(c, onPageProgress)
                    }
                } else {
                    provider.delta(c, onPageProgress)
                }
            // UD-751: single canonical "Delta: N items, hasMore=X" line, lifted out
            // of the five providers that used to emit the same data per-page.
            log.debug("Delta: {} items, hasMore={}", page.items.size, page.hasMore)
            if (!page.complete) {
                allComplete = false
            }
            return page
        }

        var page = nextPage(cursor)
        for (item in page.items) {
            val resolved = resolveItemPath(item) ?: continue
            changes[resolved.path] = resolved
        }
        db.setSyncState("pending_cursor", page.cursor)
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
            db.setSyncState("pending_cursor", page.cursor)
            reporter.onScanProgress("remote", changes.size)
        }

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
        if (isFolder) {
            db.renamePrefix(action.fromPath, action.path)
        }
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
        db.upsertEntry(entryFromCloudItem(action.remoteItem, action.path, oldEntry?.isHydrated ?: false))
        if (isFolder) {
            db.renamePrefix(action.fromPath, action.path)
        }
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
        db.deleteEntry(action.path)
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
        db.deleteEntry(action.path)
        auditLog?.emit(
            action = "Delete",
            path = action.path,
            size = priorEntry?.remoteSize,
            oldHash = priorEntry?.remoteHash,
            result = auditResult,
        )
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
    }
}
