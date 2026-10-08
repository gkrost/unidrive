package org.krost.unidrive.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.http.Priority
import org.krost.unidrive.engine.AuditSink
import org.krost.unidrive.engine.EnumerationEntryPoint
import org.krost.unidrive.engine.MountHost
import org.krost.unidrive.engine.MountWiring
import org.krost.unidrive.engine.RemoteEnumeration
import org.krost.unidrive.engine.RemoteGather.RemoteMerge
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.engine.localNameIssue
import org.krost.unidrive.engine.Transfers
import org.krost.unidrive.sync.model.*
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException

open class SyncEngine(
    private val provider: CloudProvider,
    private val db: StateDatabase,
    private val syncRoot: Path,
    private val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    private val conflictOverrides: Map<String, ConflictPolicy> = emptyMap(),
    private val excludePatterns: List<String> = emptyList(),
    private val reporter: ProgressReporter = ProgressReporter.Silent,
    private val failureLogPath: Path? = null,
    private val conflictLog: ConflictLog? = null,
    private val syncPaths: List<String> = emptyList(),
    // The profile's standing scope (config sync_path). It decides which remote rows
    // state.db tracks; [syncPaths] only decides which actions this run plans. Empty =
    // track the whole drive, so a one-off --sync-path never narrows tracking.
    private val standingScope: List<String> = emptyList(),
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
    // Streaming reconciliation: when true, the engine runs the local scan
    // first, then drives provider.delta() page-by-page, reconciling each
    // page against the full local map via Reconciler.resolveSlice. Safe-now
    // actions (DownloadContent, Upload, Create*, explicit Move) accumulate
    // into the action list as each page lands; deletion-bearing actions
    // (DeleteLocal, DeleteRemote, RemoveEntry, Conflict×DELETED) buffer
    // until scan-end so detectMissingAfterFullSync still gates them. The
    // executor pipeline (Pass 1 / Pass 2) remains the existing single-list
    // shape — the channel + executor coroutine wiring lands separately.
    // Default false; flipped by the CLI flag + TOML key.
    private val streamingReconciliation: Boolean = false,
    // Root directory for the hydration cache used by the mount front-end (MountEngine
    // ensureHydrated / uploadFromCache, #560 U3) and the Reconciler's #459 guard. Null means resolve via XDG_CACHE_HOME (or ~/.cache).
    // Injected in tests so the cache stays inside the temp directory.
    private val cacheRoot: Path? = null,
    // Per-account namespace for the hydration cache subtree. MUST be unique
    // per profile (use profile.name, not profile.type) so two accounts of the
    // same provider type (e.g. two `onedrive` profiles) don't collide on
    // identical remote paths under one shared cache dir. Distinct from
    // [providerId], which is the provider TYPE used for ProviderRegistry
    // metadata lookups (concurrency cap, capability flags) and must stay the
    // type. Defaults to [providerId] so callers that don't set it keep the
    // pre-existing layout; the CLI sync/daemon paths pass profile.name.
    private val cacheKey: String = providerId,
    // Called once after enumerateRemoteIntoState mutates state.db with the set of
    // changed paths (upserted + reaped; full=false), and once after
    // applyScopeTransition reshapes the tracked set (full=true — narrowing untracks
    // rows a mount may still cache and widening re-enumerates from scratch, so the
    // shrink/growth is not expressible as a per-path delta and the whole view must
    // be invalidated). No-op default keeps callers that don't need the signal
    // unaffected. app:cli is the wiring site; keeping this as a plain lambda keeps
    // :app:sync free of :app:hydration (HydrationEvent lives there; checkModuleEdges
    // forbids the edge either way, #560 U3). The mount front-end's rescan reports
    // through the same sink (RemoteGather.invalidateView).
    // #595: [moved] carries the renames the merge detected (old path -> new path);
    // consumers that only need the paths can ignore it — changedPaths always holds
    // both ends of every move regardless.
    private val viewInvalidationSink: (changedPaths: Set<String>, full: Boolean, moved: List<RemoteMerge.Move>) -> Unit = { _, _, _ -> },
    // #301: whether a background hydration upload of [path] is queued or in flight
    // (an open_write returned Ok but its upload has not landed yet). The
    // enumerate-reap consults it before evicting a hydration-cache file, so a
    // queued edit's only copy is never deleted out from under its upload. Wired by
    // app:cli to HydrationImpl.hasUploadSlot (late-bound — the hydration layer is
    // constructed after the engine); the no-op default keeps engine-only callers
    // and tests unaffected.
    private val uploadInFlight: (path: String) -> Boolean = { false },
    // What a client may ask about the enumeration (see enumerationStatus). The daemon's poller
    // records on the same tracker when it tries again after a failure.
    val enumerationTracker: EnumerationTracker = EnumerationTracker(),
    xdgUserDirsOverridesForTest: Map<String, String>? = null,
) : MountHost,
    EnumerationEntryPoint {
    private val log = LoggerFactory.getLogger(SyncEngine::class.java)
    private val effectiveExcludePatterns =
        validateExcludePatterns(
            (SyncConfig.DEFAULT_EXCLUDE_PATTERNS + excludePatterns).distinct(),
        )
    private val scanner = LocalScanner(syncRoot, db, effectiveExcludePatterns, provider.hashAlgorithm(), syncPaths)

    // #560 U2: scope, excludes and the transfer budget live in :app:engine-core's
    // RemoteOperationGuard, shared by the sync pass and the mount front-end (MountEngine,
    // through [mountWiring], #560 U3).
    //
    // UD-263: per-provider transfer concurrency cap, computed once at
    // construction. Audit values flow from docs/providers/<id>-robustness.md
    // §5 → ProviderMetadata → here. One daemon-wide semaphore shared by the
    // sync pass (Pass 2 + streaming-gather executor) and the hydration upload
    // path, so an Explorer copy burst through the mount can never exceed the
    // provider's cap (Internxt allows 2) no matter which path the transfers
    // come from. Memory-pressure protection on big files is delegated to the
    // provider's HttpRetryBudget (UD-232).
    private val guard =
        org.krost.unidrive.engine.RemoteOperationGuard(
            standingScope = standingScope,
            syncPaths = syncPaths,
            excludePatterns = effectiveExcludePatterns,
            maxConcurrentTransfers =
                org.krost.unidrive.ProviderRegistry
                    .getMetadata(providerId)
                    ?.maxConcurrentTransfers ?: 4,
            matchesGlob = { path, pattern -> Reconciler.matchesGlob(path, pattern) },
        )

    // Remote paths state.db tracks: the standing scope plus any per-run --sync-path,
    // and the folders leading to them. Empty = the whole drive.
    private val trackScope: List<String> get() = guard.trackScope

    private fun isTracked(remotePath: String): Boolean = guard.isTracked(remotePath)

    private val perProviderConcurrency: Int get() = guard.maxConcurrentTransfers
    private val transferBudget: kotlinx.coroutines.sync.Semaphore get() = guard.transferBudget

    // #115: read once at construction — a locale change requires a daemon
    // restart. Shared by the reconciler (alias detection) and updateRemoteEntries
    // (canonical→real-local reverse map for newly-arrived aliased rows).
    private val xdgUserDirsOverrides: Map<String, String> =
        xdgUserDirsOverridesForTest ?: parseUserDirsFile(
            Paths.get(
                System.getenv("HOME") ?: System.getProperty("user.home"),
                ".config", "user-dirs.dirs",
            ),
        )

    private val reconciler = Reconciler(
        db, syncRoot, conflictPolicy, conflictOverrides, effectiveExcludePatterns,
        // #115: wire real user-dirs.dirs content so the reconciler can map locale-
        // aliased local folder names to their cloud-canonical equivalents.
        xdgUserDirsOverrides = xdgUserDirsOverrides,
        // A row whose path does not resolve inside the cache answers true: it is kept out of the
        // delete plan rather than read as a local delete.
        isHydrationCachePresent = { path ->
            org.krost.unidrive.engine.CachePaths.forRow(path, log) { resolveCachePath(it) }
                ?.let { Files.isRegularFile(it) } ?: true
        },
        // #532: identical edits on both sides are convergence, not a conflict — compare the
        // local file against the remote item with the provider's own content hash. Strict
        // (matches, not verify): a provider without a hash keeps the conflict.
        sameContent = { localFile, item -> HashVerifier.matches(localFile, item.hash, provider.hashAlgorithm()) },
    )

    // Debounce state for remote-change wake hints (Internxt notifications WS).
    // The provider may emit many frames per second during a folder-tree
    // mutation; we coalesce them into one wake by cancelling-and-restarting
    // a single delay job per hint and firing the listener only after the
    // quiet window elapses.
    @Volatile
    private var remoteWakeDebounceJob: kotlinx.coroutines.Job? = null

    // #116: set true for the duration of a sync run that actually took the
    // fast-bootstrap branch (cursor adopted with zero enumeration). When true,
    // applyCreateRemoteFolder adopts-on-name-match for a top-level folder whose
    // name already exists on the remote instead of issuing a CreateRemoteFolder
    // that would 409 — the pre-existing cloud tree is invisible to the planner
    // under fast-bootstrap, so the local scanner emits a spurious mkdir for every
    // top-level folder that matches one. Reset at the start of every doSyncOnce.
    // The gather sets it, so the flag lives in RemoteGather (#560 U2b).
    private var fastBootstrapActive: Boolean
        get() = remoteGather.fastBootstrapActive
        set(value) {
            remoteGather.fastBootstrapActive = value
        }

    // #116: per-run cache of the remote root's direct children indexed by name,
    // populated lazily on the first top-level CreateRemoteFolder while
    // fastBootstrapActive. Null = not yet fetched this run; an empty map = fetched
    // and the root has no folders (or the listing failed — see populateRemoteTopLevel).
    // The mutex serialises the lazy fetch so the concurrent create-folder run
    // (which launches the depth-0 batch in parallel) issues the root listing once.
    private var remoteTopLevelByName: Map<String, CloudItem>? = null
    private val remoteTopLevelMutex = kotlinx.coroutines.sync.Mutex()

    // #560 U2b: the remote gather — delta paging, the scan staging and the cursor, the
    // #401 collision rule, the absence sweep, the tracked-scope transition and the
    // commit into sync_entries — lives in :app:engine-core's RemoteGather, and the
    // mount's enumeration in RemoteEnumeration. Both take what they used to read from
    // this engine's fields explicitly; the private wrappers below keep the sync pass's
    // call sites as they were.
    private val remoteGather =
        RemoteGather(
            provider = provider,
            db = db,
            guard = guard,
            options =
                RemoteGather.Options(
                    providerId = providerId,
                    fastBootstrap = fastBootstrap,
                    includeShared = includeShared,
                ),
            listener =
                object : RemoteGather.Listener {
                    override fun onScanProgress(count: Int) = reporter.onScanProgress("remote", count)

                    override fun onWarning(message: String) = reporter.onWarning(message)

                    override fun onSkippedOp(
                        label: String,
                        path: String,
                        reason: String,
                        dryRun: Boolean,
                    ) = logSkippedOp(label, path, reason, dryRun)

                    override fun onViewInvalidated(
                        changedPaths: Set<String>,
                        full: Boolean,
                        moved: List<RemoteMerge.Move>,
                    ) = viewInvalidationSink(changedPaths, full, moved)
                },
            localTopAliases = { buildCanonicalToLocalTopMap(it) },
            log = log,
        )

    private val enumeration =
        RemoteEnumeration(
            gather = remoteGather,
            db = db,
            tracker = enumerationTracker,
            reapGuards =
                RemoteEnumeration.ReapGuards(
                    uploadInFlight = uploadInFlight,
                    cachePathOf = { resolveCachePath(it) },
                ),
            log = log,
        )

    /**
     * #560 U3: what the mount front-end (`MountEngine`, :app:hydration) runs on: this engine's guard,
     * gather and enumeration (shared, not copied), its cache layout, its audit log, progress reporter,
     * logger and the late-bound [uploadInFlight]. #560 U6: the mount touches no sync root — the
     * `SyncRootBridge` of the coordinated model is gone, and the sync root, its name rules, its scanner
     * and the mirror's executors stay mirror-only here. Built once per engine. The view invalidation
     * reaches [viewInvalidationSink] through the gather's listener, as for the enumeration and the
     * scope transition.
     */
    override val mountWiring: MountWiring by lazy {
        MountWiring(
            provider = provider,
            db = db,
            guard = guard,
            gather = remoteGather,
            enumeration = enumeration,
            cachePathOf = { resolveCachePath(it) },
            options = MountWiring.Options(syncPaths = syncPaths, verifyIntegrity = verifyIntegrity),
            auditLog =
                auditLog?.let { audit ->
                    object : AuditSink {
                        override fun emit(
                            action: String,
                            path: String,
                            result: String,
                            size: Long?,
                            oldHash: String?,
                            newHash: String?,
                        ) = audit.emit(action = action, path = path, result = result, size = size, oldHash = oldHash, newHash = newHash)
                    }
                },
            onTransferProgress = { path, transferred, total -> reporter.onTransferProgress(path, transferred, total) },
            uploadInFlight = uploadInFlight,
            log = log,
        )
    }

    private fun markRecentlyUploaded(path: String) = remoteGather.markRecentlyUploaded(path)

    private fun admit(
        changes: MutableMap<String, CloudItem>,
        keyToPath: MutableMap<String, String>,
        collisions: MutableMap<String, RemoteGather.CollisionRecord>,
        item: CloudItem,
    ): CloudItem = remoteGather.admit(changes, keyToPath, collisions, item)

    private fun reportGatheredCollisions(
        collisions: Map<String, RemoteGather.CollisionRecord>,
        dryRun: Boolean,
    ): Set<String> = remoteGather.reportCollisions(collisions, dryRun)

    private suspend fun gatherRemoteChanges(readOnly: Boolean = false): Map<String, CloudItem> = remoteGather.gather(readOnly = readOnly)

    private fun applyScopeTransition() = remoteGather.applyScopeTransition()

    private fun promotePendingCursor() = remoteGather.promotePendingCursor()

    private fun resolveItemPath(item: CloudItem): CloudItem? = remoteGather.resolveItemPath(item)

    private fun detectMissingAfterFullSync(
        remoteChanges: MutableMap<String, CloudItem>,
        admittedLoserIds: Set<String> = emptySet(),
    ) = remoteGather.detectMissingAfterFullSync(remoteChanges, admittedLoserIds)

    // #401: a path-addressed mutation on a collided path could hit either twin and
    // the action carries no remote id to aim it with. Skip; the action re-plans
    // once the duplicate is resolved in the cloud. Internal for the wrong-twin test.
    internal fun refuseCollidedPath(action: SyncAction): Boolean {
        if (!remoteGather.isCollided(action.path)) return false
        val msg =
            "Not applying ${actionLabel(action)} for ${action.path}: two remote items share this path and " +
                "the action has no remote id to aim at. Resolve the duplicate in the cloud first."
        log.warn(msg)
        reporter.onWarning("Skipped: $msg")
        logSkippedOp(action, "collided_path_no_remote_id", dryRun = false)
        return true
    }

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

    /**
     * Resolves the cache file path for a given path within the hydration cache.
     * The mount front-end resolves through it ([MountWiring.cachePathOf], #560 U3), as do the
     * Reconciler's #459 guard, the enumeration's reap and test fixtures.
     *
     * The result is normalised and always lies inside the profile's cache folder
     * (`<cacheRoot>/unidrive/hydration/<cacheKey>`), the way [safeResolveLocal] keeps local files
     * inside the sync root: a path that would resolve outside it throws [SecurityException]
     * ([org.krost.unidrive.engine.CachePaths.resolveInside]). The verbs answer it with
     * `invalid_path`; the passes over rows skip the row's cache side.
     */
    fun resolveCachePath(path: String): Path =
        org.krost.unidrive.engine.CachePaths.resolveInside(
            hydrationCacheRoot(cacheRoot ?: defaultHydrationCacheRoot(), cacheKey),
            path,
        )

    // #560 U2: the classification lives in :app:engine-core (RemoteErrors), shared with the mount.
    // See [org.krost.unidrive.engine.RemoteErrors.isAlreadyGone] for the two shapes it accepts.
    private fun isAlreadyGone(e: Throwable): Boolean = org.krost.unidrive.engine.RemoteErrors.isAlreadyGone(e)

    // The remote item at [path], or null only when the provider proves it ABSENT; transient failures
    // propagate, never read as absence. The mirror's copy of MountEngine.remoteItemOrNull (#560 U3),
    // for the #421 move-source probe.
    private suspend fun remoteItemIfPresent(path: String): CloudItem? =
        try {
            provider.getMetadata(path)
        } catch (e: Exception) {
            if (isAlreadyGone(e) || statusCodeOf(e) == 404 || (e.cause?.let { statusCodeOf(it) } == 404)) {
                null
            } else {
                throw e
            }
        }

    // Reflectively read a `getStatusCode(): Int` off a provider exception without a
    // provider-module classpath dependency (mirrors the hydration SPI helper).
    private fun statusCodeOf(e: Throwable): Int? =
        runCatching {
            val getter = e.javaClass.methods.firstOrNull { it.name == "getStatusCode" && it.parameterCount == 0 }
            getter?.invoke(e) as? Int
        }.getOrNull()

    /**
     * One-way remote→state.db refresh for view consumers (the FUSE mount). Reuses the remote
     * gather + state.db upsert, but NEVER scans sync_root, NEVER plans/executes a local→remote
     * delete, and NEVER evaluates the empty-sync_root / max_delete_* guards. Remote-observed
     * deletions flip state.db rows only on a COMPLETE enumeration. Single-flight: an overlapping
     * call returns `skipped = true`. See [RemoteEnumeration] (#560 U2b) and
     * docs/dev/specs/mount-view-refresh-design.md.
     *
     * #560 U3: the mount front-end (MountEngine) runs the same [RemoteEnumeration]; this entry
     * point stays for the mount-routed `refresh.run` (RefreshRpcHandler, reworked in U4).
     */
    override suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult = enumeration.enumerate(reset)

    open suspend fun syncOnce(
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
        // A dry-run must never change real state. The engine writes to its database in many places (row
        // upserts, cursor bookkeeping, scan checkpoints, scanner pre-writes), so purity is guaranteed by
        // construction: the caller hands a dry-run a disposable database (StateDatabase.snapshotOf).
        require(!dryRun || db.isDisposable) {
            "A dry-run needs a disposable StateDatabase (StateDatabase.snapshotOf) so it cannot change the real state."
        }
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
        reason: SyncReason,
        startTime: Long,
        skipTransfers: Boolean = false,
        skipRemoteGather: Boolean = false,
    ) {
        // #116: reset the per-run fast-bootstrap adopt state. gatherRemoteChanges
        // flips fastBootstrapActive on only when the bootstrap branch is actually
        // taken; the apply loop reads it to adopt-on-name-match top-level folders.
        fastBootstrapActive = false
        remoteTopLevelByName = null
        val downloaded = AtomicInteger(0)
        val uploaded = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        // Lifted up from Pass 2 so the streaming-reconciliation gather can
        // dispatch transfers concurrently with the scan and share the same
        // failure counter + auth-failure latch. On the non-streaming path
        // only Pass 2 increments them, so the move is behaviour-preserving.
        val transferFailures = AtomicInteger(0)
        val authFailure =
            java.util.concurrent.atomic
                .AtomicReference<AuthenticationException?>(null)
        // Paths the streaming-gather executor already dispatched. Pass 2
        // skips them so a Download/Upload doesn't fire twice (waste of an
        // API round-trip + potential file-locked-by-prior-write race on
        // Windows). Concurrent set: producer is the streaming executor,
        // consumer is Pass 2's launch loop.
        val executedPaths =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()
        // UD-263: the per-provider transfer budget is a construction-time field
        // ([transferBudget]) shared with the hydration upload path via
        // [withTransferPermit] — one daemon-wide cap, not one per sync pass.
        // The per-provider audit values (docs/providers/<id>-robustness.md §5
        // → ProviderMetadata) still drive the cap.
        val transferSemaphore = transferBudget

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
        //  - If `syncPaths` is non-empty this run: UNION them into the persisted scope
        //    (whether the scope was previously empty or not). The run proceeds
        //    with the runtime scope filter as before.
        //  - If `syncPaths` is empty and the persisted scope is non-empty and the
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
        // #532: a refresh (skipTransfers) of a profile that has a persisted scope but no
        // runtime syncPaths continues WITHIN the persisted scope instead of being refused
        // below: refresh plans the boundary the profile has been operating on, so
        // out-of-scope paths stay invisible to the reconciler exactly as under
        // --sync-path. A refresh never widens or clears the persisted scope — it only
        // reads it. A plain sync keeps the refusal (its un-scoped bidirectional apply is
        // the catastrophe pattern the guard exists for).
        val runScope = if (syncPaths.isEmpty() && skipTransfers) priorScope else syncPaths
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
        } else if (syncPaths.isNotEmpty()) {
            val unioned = (priorScope + syncPaths).distinct()
            if (unioned.size != priorScope.size && !dryRun) {
                log.info(
                    "UD-256: persisting effective_scope += {} (now {} entry/entries)",
                    syncPaths,
                    unioned.size,
                )
                db.setSyncState("effective_scope", unioned.joinToString("\t"))
            } else if (unioned.size != priorScope.size && dryRun) {
                log.info(
                    "UD-256: --dry-run with new --sync-path {} — would extend effective_scope to {} entries (not persisted)",
                    syncPaths,
                    unioned.size,
                )
            }
        } else if (priorScope.isNotEmpty() && syncDirection == SyncDirection.BIDIRECTIONAL && !skipTransfers) {
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

        if (dryRun) {
            trashManager?.expiredCount(trashRetentionDays)?.takeIf { it > 0 }?.let {
                reporter.onWarning("Dry-run: a real run would purge $it expired trash item(s)")
            }
            versionManager?.expiredCount(versionRetentionDays)?.takeIf { it > 0 }?.let {
                reporter.onWarning("Dry-run: a real run would prune $it expired file version(s)")
            }
        } else {
            trashManager?.purge(trashRetentionDays)
            versionManager?.pruneByAge(versionRetentionDays)
        }

        // UD-297: empty-local + populated-DB sanity check. Catches the
        // wrong-sync_root case (user pointed at an empty directory while
        // the state DB knows about thousands of remote entries) before
        // the reconciler turns it into a wall of del-remote actions.
        // Fires in dry-run too — that's where the user is most likely
        // to notice and the system has the least to lose by being loud.
        //
        // Gate on the HYDRATED entry count, not the total. A state.db
        // populated by previous failed gather passes (Internxt "dance"
        // pattern: many delta walks, no successful downloads) is full of
        // unhydrated rows that represent cloud-side items the user still
        // needs to download — refusing to run there blocks the UD-225
        // recovery loop from doing its job. The original concern (mass
        // DeleteRemote when sync_root is mis-pointed) only applies when
        // hydrated entries are missing locally; the Reconciler rewrites
        // unhydrated-FILE DELETED localChanges into DownloadContent via
        // its UD-225a recovery downgrade, and drops DeleteRemote actions
        // for unhydrated-FOLDER rows in a post-detectMoves filter, so
        // the only way DeleteRemote reaches the apply phase is via
        // hydrated rows.
        // Live repro 2026-05-20: 171 386 file rows all is_hydrated=0,
        // sync_root empty, old guard refused with a misleading
        // "--force-delete" hint that would have catastrophically wiped
        // the cloud side.
        //
        // #137: --download-only is exempt. In download-only mode the
        // destructive local→remote-delete direction is already gated by
        // the direction filter, so the mis-pointed-sync_root mass-delete
        // risk does NOT apply. A download-only user who intentionally
        // wiped local and wants the cloud copy back (a legitimate
        // rehydrate) must not be blocked here.
        val hydratedEntryCount = db.getHydratedEntryCount()
        val localDeletesAllowed = !forceDelete && syncDirection != SyncDirection.DOWNLOAD
        if (localDeletesAllowed && hydratedEntryCount > 10 && isSyncRootEffectivelyEmpty()) {
            val msg =
                "Local sync_root '$syncRoot' is empty, but state DB knows " +
                    "$hydratedEntryCount previously-hydrated entries (of " +
                    "${db.getEntryCount()} total). sync_root probably points at " +
                    "the wrong directory. To rehydrate from cloud, re-run with " +
                    "--download-only. To proceed with a bidirectional sync after " +
                    "an intentional local wipe, re-run with --force-delete."
            if (dryRun) {
                reporter.onWarning(msg)
            } else {
                throw IllegalStateException(msg)
            }
        }

        // #137: create sync_root only after the guard — an aborted (guard-fired)
        // run must not leave an empty sync_root dir behind.
        if (dryRun) {
            if (!Files.exists(syncRoot)) {
                reporter.onWarning("Dry-run: sync_root '$syncRoot' does not exist; a real run would create it")
            }
        } else {
            java.nio.file.Files.createDirectories(syncRoot)
        }

        applyScopeTransition()

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
        // Surface the cursor THIS scan will filter on, plus whether the prior
        // gather that produced it ran clean. delta_cursor is the active filter
        // (null on first sync / `--reset`); pending_cursor_complete=false means
        // a prior incomplete pass left the cursor pinned at its older value.
        reporter.onScanCursorHint(
            phase = "remote",
            cursor = db.getSyncState("delta_cursor")?.ifBlank { null },
            complete = db.getSyncState("pending_cursor_complete")?.toBooleanStrictOrNull() ?: true,
        )
        // Remote-shrink guard inputs (sync path only): snapshot the pre-gather
        // tracked-row baseline and whether this pass is expected to be a full
        // enumeration (delta_cursor unset, matching gatherRemoteChanges). The
        // actual verdict — which a 410 cursor-expiry recovery can upgrade inside
        // the gather — is read back after the gather below.
        val preGatherTrackedRows = db.getEntryCount()
        val fullEnumerationExpected = db.getSyncState("delta_cursor").isNullOrEmpty() || provider.deltaIsFullListing
        // A full enumeration against an established baseline must run NON-streaming
        // so the remote-shrink guard can abort before any transfer is dispatched —
        // the streaming gather dispatches safe-now uploads/downloads mid-scan, which
        // would otherwise execute before a partial listing is detected.
        val shrinkGateMayApply = fullEnumerationExpected && preGatherTrackedRows >= ENUM_TRUST_MIN_BASELINE
        val remotePhaseStart = System.currentTimeMillis()
        reporter.onScanProgress("remote", 0)
        // Streaming reconciliation reorders the phases: local scan first,
        // then per-page remote gather reconciles each page against the
        // full local map (spec §1 decision 1 — "Local scan completes
        // before streaming remote loop starts"). The non-streaming path
        // keeps the historical remote-then-local order so a flag flip
        // doesn't change the timing for tests + telemetry baselines.
        val localChangesForStreaming: Map<String, ChangeState>?
        val streamingActions: List<SyncAction>?
        val allRemoteChanges: Map<String, CloudItem>
        // A dry-run always takes the accumulate-then-reconcile path: streaming dispatches transfers from
        // inside the gather, which a preview must never do.
        val streamingViable = streamingReconciliation && !skipRemoteGather && !shrinkGateMayApply
        if (streamingViable && !dryRun) {
            db.getSyncState("last_scan_secs_local")?.toLongOrNull()?.let {
                reporter.onScanHistoricalHint("local", it)
            }
            db.getSyncState("last_scan_count_local")?.toIntOrNull()?.let {
                reporter.onScanCountHint("local", it)
            }
            val localPhaseStart = System.currentTimeMillis()
            reporter.onScanProgress("local", 0)
            val allLocalChangesPre =
                scanner.scan { count ->
                    reporter.onScanProgress("local", count)
                }
            val localChangesPre =
                if (runScope.isNotEmpty()) {
                    val ancestors = SyncScope.ancestors(runScope)
                    allLocalChangesPre.filterKeys {
                        SyncScope.contains(it, runScope) || it in ancestors
                    }
                } else {
                    allLocalChangesPre
                }
            reporter.onScanProgress("local", localChangesPre.size)
            val localScanSecs = (System.currentTimeMillis() - localPhaseStart) / 1000
            db.setSyncState("last_scan_secs_local", localScanSecs.toString())
            db.setSyncState("last_scan_count_local", localChangesPre.size.toString())
            localChangesForStreaming = localChangesPre

            val (remoteMap, actions) =
                gatherStreamingChanges(
                    localChanges = localChangesPre,
                    scope = runScope,
                    downloaded = downloaded,
                    uploaded = uploaded,
                    transferFailures = transferFailures,
                    authFailure = authFailure,
                    executedPaths = executedPaths,
                    transferSemaphore = transferSemaphore,
                )
            allRemoteChanges = remoteMap
            streamingActions = actions
        } else {
            localChangesForStreaming = null
            streamingActions = null
            // UD-236: skipRemoteGather (apply mode) bypasses provider.delta() entirely.
            // The recovery loops in Reconciler.reconcile pick up any pending UD-225/UD-901
            // rows from a prior refresh and emit DownloadContent / Upload actions for them.
            allRemoteChanges =
                if (skipRemoteGather) {
                    log.info("Apply mode: skipping remote gather; recovery loops will surface pending entries")
                    emptyMap()
                } else {
                    gatherRemoteChanges(readOnly = dryRun)
                }
        }

        val remoteChanges =
            if (runScope.isNotEmpty()) {
                allRemoteChanges.filterKeys { SyncScope.contains(it, runScope) }
            } else {
                allRemoteChanges
            }
        reporter.onScanProgress("remote", remoteChanges.size)
        // UD-747 / UD-748: persist the wall-clock + final count for next
        // run's ETA computation.
        val remoteScanSecs = (System.currentTimeMillis() - remotePhaseStart) / 1000
        db.setSyncState("last_scan_secs_remote", remoteScanSecs.toString())
        db.setSyncState("last_scan_count_remote", remoteChanges.size.toString())

        // Remote-shrink guard: abort a full, unscoped enumeration that observed far
        // fewer live remote items than the pre-gather baseline — a partial listing
        // (flaky provider, swallowed transient errors) that would otherwise plan
        // spurious mass uploads/deletes (and --force-delete would apply them).
        // Uses the gather's ACTUAL full-enumeration verdict (a 410 cursor-expiry
        // recovery upgrades an incremental pass to a full re-enumeration inside the
        // gather), falling back to the pre-gather expectation. allRemoteChanges is
        // the unscoped gather result; remoteChanges may be syncPath-filtered.
        // skipRemoteGather (apply mode) has no fresh listing to judge.
        val actualFullEnumeration =
            db.getSyncState("last_gather_full")?.toBooleanStrictOrNull() ?: fullEnumerationExpected
        if (actualFullEnumeration && runScope.isEmpty() && !skipRemoteGather) {
            val observedAlive = allRemoteChanges.values.count { !it.deleted }
            remoteShrinkWarningOrNull(observedAlive, preGatherTrackedRows)?.let { msg ->
                if (dryRun) {
                    reporter.onWarning(msg)
                } else {
                    throw IllegalStateException(msg)
                }
            }
        }

        // Streaming-reconciliation auto-flip: after the first successful
        // streaming scan, persist the sentinel so subsequent runs default to
        // streaming without re-opting via CLI/TOML. "Successful" here means
        // the streaming path ran AND the gather reported all pages complete
        // (pending_cursor_complete=true, written by the just-completed gather).
        // CLI/TOML override always wins on the next launch — the sentinel
        // sits at the lowest tier of [SyncConfig.resolveStreamingReconciliation].
        if (streamingReconciliation && !skipRemoteGather &&
            db.getSyncState("pending_cursor_complete")?.equals("true", ignoreCase = true) == true
        ) {
            db.setSyncState(SyncConfig.STREAMING_RECONCILIATION_SENTINEL_KEY, "true")
        }

        // Local-changes resolution: streaming captured these above so the
        // per-page reconcile could see them; non-streaming runs the scan
        // here on the historical order.
        val localChanges: Map<String, ChangeState>
        if (localChangesForStreaming != null) {
            localChanges = localChangesForStreaming
        } else {
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
            localChanges =
                if (runScope.isNotEmpty()) {
                    val ancestors = SyncScope.ancestors(runScope)
                    allLocalChanges.filterKeys { SyncScope.contains(it, runScope) || it in ancestors }
                } else {
                    allLocalChanges
                }
            reporter.onScanProgress("local", localChanges.size)
            val localScanSecs = (System.currentTimeMillis() - localPhaseStart) / 1000
            db.setSyncState("last_scan_secs_local", localScanSecs.toString())
            db.setSyncState("last_scan_count_local", localChanges.size.toString())
        }

        // UD-240g: pass reporter so the phase emits a
        // heartbeat instead of going silent for many seconds on big first-syncs;
        // UD-901a: pass syncPath so the recovery loops respect scope and don't
        // resurrect orphans outside the user's requested subtree).
        //
        // Streaming reconciliation already ran resolveSlice per page and
        // accumulated through StreamingReconcileBuffer; finalize against
        // the union of streamed safe-now + deferred-drained actions here
        // so the recovery loops, case-collision detection, move detection,
        // and final sort run exactly once against the full action set.
        // Upload-direction completeness gate: when the just-completed gather is
        // INCOMPLETE, a local-present/remote-absent path may be an un-enumerated
        // subtree rather than a genuinely-new file, so the reconciler must defer
        // new-local creates (mirror of the delete-side "reap only on complete").
        // apply mode (skipRemoteGather) has no fresh listing, so it is never gated.
        val enumerationComplete =
            skipRemoteGather || (db.getSyncState("pending_cursor_complete")?.toBooleanStrictOrNull() ?: true)
        val reconciledActionsAll =
            if (streamingActions != null) {
                reconciler.finalizeStreaming(streamingActions, remoteChanges, localChanges, runScope,
                    downloadOnly = syncDirection == SyncDirection.DOWNLOAD,
                    enumerationComplete = enumerationComplete)
            } else {
                reconciler.reconcile(remoteChanges, localChanges, reporter, runScope,
                    downloadOnly = syncDirection == SyncDirection.DOWNLOAD,
                    enumerationComplete = enumerationComplete)
            }
        // #503 review: a name the local scan refused as an NFC clash (#491) is out of sync in BOTH directions. The scan
        // reports no change for it, so without this a remote edit of the cloud copy would be planned as a download that
        // overwrites the local member with that name (and a local edit of it would be lost). Nothing is planned for it.
        val reconciledActions =
            if (scanner.nfcCollisionKeys.isEmpty()) {
                reconciledActionsAll
            } else {
                reconciledActionsAll.filterNot { a ->
                    scanner.isUnderNfcCollision(a.path) ||
                        (a is SyncAction.MoveRemote && scanner.isUnderNfcCollision(a.fromPath)) ||
                        (a is SyncAction.MoveLocal && scanner.isUnderNfcCollision(a.fromPath))
                }.also { kept -> if (kept.size < reconciledActionsAll.size) log.warn("#503: {} planned action(s) under NFC-clashing local names skipped", reconciledActionsAll.size - kept.size) }
            }
        logUnhydratedFolderSkips(dryRun)

        db.batch {
            updateRemoteEntries(allRemoteChanges.filterKeys { isTracked(it) })
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
                applyTopLevelHydrationGuard(reconciledActions, dryRun)
            }

        var actions =
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
            // UD-260: promote pending cursor (see promotePendingCursor for the
            // best-effort cursor-advance semantic on incomplete gathers).
            promotePendingCursor()
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(0, 0, 0, duration)
            return
        }

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
        //
        // #108: under a long-running daemon (`sync --watch`), a tripped safeguard
        // must NOT abort the whole cycle. The throw is caught by the watch loop,
        // which then retries the same failing cycle forever (exponential backoff)
        // and never makes progress on uploads/downloads either — the daemon
        // "stays failed". So in daemon mode (BOOT/WATCH_POLL) we DEFER the deletes
        // (drop them this cycle, below) + warn + continue; one-shot `unidrive sync`
        // (MANUAL) keeps the hard throw so an operator sees it and can re-run with
        // --force-delete. A transient cause (e.g. an incomplete Internxt
        // enumeration) clears on a later cycle; a genuine bulk delete simply waits
        // for explicit operator confirmation.
        var deferDeletes = false
        if (!forceDelete) {
            val deleteActions = actions.filter { it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal }
            val deleteCount = deleteActions.size
            val totalEntries = db.getEntryCount()
            val daemonMode = reason == SyncReason.WATCH_POLL || reason == SyncReason.BOOT

            // UD-298 legacy whole-inventory percentage axis.
            if (maxDeletePercentage in 1..99 && totalEntries > 0 && deleteCount > 10) {
                val pct = deleteCount * 100 / totalEntries
                if (pct > maxDeletePercentage) {
                    val msg =
                        "Deletion safeguard: $deleteCount of $totalEntries files ($pct%) would be deleted, " +
                            "exceeding max_delete_percentage=$maxDeletePercentage%. " +
                            "sync_root='$syncRoot'. Use --force-delete to override."
                    when {
                        dryRun -> reporter.onWarning(msg)
                        daemonMode -> {
                            deferDeletes = true
                            reporter.onWarning("$msg Deferring deletes this cycle (daemon mode).")
                        }
                        else -> throw IllegalStateException(msg)
                    }
                }
            }

            // UD-265 axis 1: absolute count cap.
            if (maxDeleteAbsolute > 0 && deleteCount > maxDeleteAbsolute) {
                val msg =
                    "UD-265 Deletion safeguard: $deleteCount deletes planned, " +
                        "exceeding max_delete_absolute=$maxDeleteAbsolute. " +
                        "sync_root='$syncRoot'. Use --force-delete to override."
                when {
                    dryRun -> reporter.onWarning(msg)
                    daemonMode -> {
                        deferDeletes = true
                        reporter.onWarning("$msg Deferring deletes this cycle (daemon mode).")
                    }
                    else -> throw IllegalStateException(msg)
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
                        when {
                            dryRun -> reporter.onWarning(msg)
                            daemonMode -> {
                                deferDeletes = true
                                reporter.onWarning("$msg Deferring deletes this cycle (daemon mode).")
                            }
                            else -> throw IllegalStateException(msg)
                        }
                    }
                }
            }
        }

        // #108: drop the planned deletes for this cycle when a daemon-mode
        // safeguard tripped. The apply pass derives sequentialActions from
        // `actions`, so removing them here keeps uploads/downloads/creates
        // flowing; the deletes are re-evaluated on the next sync.
        if (deferDeletes) {
            val before = actions.size
            actions = actions.filterNot { it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal }
            log.warn(
                "#108: deferred {} delete action(s) this cycle (daemon-mode safeguard); re-evaluated next sync",
                before - actions.size,
            )
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
            // UD-260: promote pending cursor (see promotePendingCursor for the
            // best-effort cursor-advance semantic on incomplete gathers).
            promotePendingCursor()
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(downloaded.get(), uploaded.get(), conflicts.get(), duration, counts)
            return
        }

        // perProviderConcurrency + transferBudget are declared at
        // construction so the streaming-gather executor, Pass 2 and the
        // hydration upload path all share one concurrency budget. The
        // per-provider audit values (docs/providers/<id>-robustness.md §5
        // → ProviderMetadata) still drive the cap.
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
        // #419: paths this pass has already deleted on the remote, so the empty-directory
        // reaper below leaves them alone instead of re-probing a folder it just trashed.
        val remoteDeletedPaths = mutableSetOf<String>()
        // #421: source paths of remote moves that failed this pass. The reconciler orders every
        // folder delete after the moves out of that folder; a remote delete must also not run when
        // such a move failed, or it would trash the folder together with the file the move left in it.
        val failedMoveSources = mutableListOf<String>()

        // Batched into one SQLite transaction — avoids one fsync per action.
        // Wrap in Priority.Foreground so the provider's throttle coordinator
        // gates the corresponding Drive REST calls in the foreground lane and
        // any concurrent background scan traffic yields.
        val sequentialActions =
            topologicalApplyOrder(
                actions.filter {
                    it !is SyncAction.DownloadContent && it !is SyncAction.Upload
                },
            )
        db.beginBatch()
        try {
            withContext(Priority.Foreground) {
                // #123: process a maximal contiguous run of CreateRemoteFolder
                // actions with bounded concurrency instead of one blocking
                // round-trip each. topologicalApplyOrder has already placed any
                // MoveRemote a create depends on before the run, so a run's
                // external deps are satisfied; parent→child WITHIN the run is
                // preserved by createFolderBatch's depth-barrier semantics.
                var idx = 0
                while (idx < sequentialActions.size) {
                    val head = sequentialActions[idx]
                    if (head is SyncAction.CreateRemoteFolder) {
                        var end = idx
                        while (end < sequentialActions.size &&
                            sequentialActions[end] is SyncAction.CreateRemoteFolder
                        ) {
                            end++
                        }
                        @Suppress("UNCHECKED_CAST")
                        val run = sequentialActions.subList(idx, end) as List<SyncAction.CreateRemoteFolder>
                        val failures =
                            applyCreateRemoteFolderRun(
                                run = run,
                                concurrency = perProviderConcurrency,
                                passOneFailures = passOneFailures,
                                completedActions = completedActions,
                                totalActions = actions.size,
                            )
                        // Fold the run outcome into consecutiveFailures: a wholly
                        // failed run signals an outage (trips the hard cap below),
                        // any success resets the streak. Mirrors the single-action
                        // path's reset-on-success / increment-on-failure semantic.
                        if (failures >= run.size) {
                            consecutiveFailures += failures
                            if (consecutiveFailures >= CONSECUTIVE_SYNC_FAILURE_HARD_CAP) {
                                log.error(
                                    "Stopping sync pass: {} consecutive action failures " +
                                        "(last run: {} create-folder failures) — treating as upstream outage",
                                    consecutiveFailures,
                                    failures,
                                )
                                throw ProviderException(
                                    "Stopping sync after $consecutiveFailures consecutive failures",
                                )
                            }
                            if (failures > 0) delay(minOf(2_000L * consecutiveFailures, 10_000L))
                        } else {
                            consecutiveFailures = 0
                        }
                        idx = end
                        continue
                    }
                    val action = head
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
                            is SyncAction.DeleteRemote ->
                                if (!deleteBlockedByFailedMove(action, failedMoveSources)) {
                                    applyDeleteRemote(action)
                                    remoteDeletedPaths += action.path
                                }
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
                        if (action is SyncAction.MoveRemote && moveSourceStillRemote(action.fromPath)) {
                            failedMoveSources.add(action.fromPath)
                        }
                        // #530: one WARN line with the item, the class and the message (the
                        // console appender's threshold is WARN — a throwable here printed the
                        // full JVM stack trace for every expected per-file failure); the
                        // stack trace lives on a DEBUG event, which the file appender takes
                        // and the console drops. UD-203: requestIdSuffix(e) renders
                        // ` requestId=<id>` when the caught exception is a ProviderException
                        // with a non-null id, empty string otherwise.
                        log.warn(
                            "Action failed for {} ({} consecutive): {}: {}{}",
                            action.path,
                            consecutiveFailures,
                            e.javaClass.simpleName,
                            e.message,
                            org.krost.unidrive.requestIdSuffix(e),
                        )
                        log.debug(
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
                    idx++
                }
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

        // UD-222: Pass 2 now carries all hydration for new/modified remote files. Failures are
        // tracked so we (a) rethrow AuthenticationException after the scope exits cleanly, and
        // (b) skip cursor promotion when any transfer failed — otherwise Graph's delta would
        // advance past the failed items and they'd never retry.
        val transferActions =
            actions.filter {
                it is SyncAction.DownloadContent || it is SyncAction.Upload
            }
        // transferFailures + authFailure are now declared at the top of
        // doSyncOnce so the streaming-gather executor (gatherStreamingChanges)
        // can share them. On the non-streaming path nothing else mutates
        // them before this point, so the move is behaviour-preserving.
        try {
            coroutineScope {
                for (action in transferActions) {
                    // Streaming-gather executor may have already dispatched
                    // this transfer mid-scan; skip the second dispatch so we
                    // don't redo the API round-trip or hit a Windows
                    // file-locked-by-prior-write race.
                    if (action.path in executedPaths) continue
                    when (action) {
                        is SyncAction.DownloadContent -> {
                            launch {
                                withContext(Priority.Foreground) {
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
                                        } catch (e: PermanentDownloadFailureException) {
                                            handlePermanentDownloadFailure(action, e)
                                            transferFailures.incrementAndGet()
                                        } catch (e: Exception) {
                                            // #530: one WARN line, the stack trace at DEBUG.
                                            log.warn(
                                                "Download failed for {}: {}: {}",
                                                action.path,
                                                e.javaClass.simpleName,
                                                e.message,
                                            )
                                            log.debug(
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
                        }
                        is SyncAction.Upload -> {
                            launch {
                                withContext(Priority.Foreground) {
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
                                            // #530: one WARN line, the stack trace at DEBUG.
                                            log.warn(
                                                "Upload failed for {}: {}: {}",
                                                action.path,
                                                e.javaClass.simpleName,
                                                e.message,
                                            )
                                            log.debug(
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

        // Promote unconditionally. The original gate was `transferFailures == 0`,
        // which protected against "cursor advances past a failed download and the
        // item is never re-seen by future deltas". That concern is now handled by
        // the UD-225 / UD-901 recovery loops in Reconciler.reconcile, which scan
        // db.getAllEntries() every pass and synthesise DownloadContent for any
        // isHydrated=false row and Upload for any remoteId=null row that no live
        // action already covers.
        //
        // Holding the cursor on transfer failures looked safe in isolation but
        // produced an inescapable first-sync loop on busy drives: a 200 k-item
        // gather is essentially guaranteed to have *some* transient transfer
        // failure (Internxt 503s, network blips, partial uploads), so every run
        // ended with the cursor pinned at null → next run re-enumerated from
        // scratch → same outcome. Live repro 2026-05-20 def535f1: 14.8 h scan,
        // 218 k items, pending_cursor written, delta_cursor never set; the
        // following run did the full enum again.
        //
        // #108/P1 (PR #241 review): when a daemon-mode safeguard trip deferred the planned
        // deletes this cycle, do NOT advance the cursor. A DeleteLocal driven by a remote
        // tombstone is consumed from the delta exactly once; if the cursor moved past it, an
        // incremental delta would not resend it and the unchanged local+DB row would not
        // re-plan it — silently stranding the local copy and making the "wait for operator
        // --force-delete" path impossible. Holding the cursor lets the tombstones replay
        // until the divergence clears or drops back under the cap. (DeleteRemote re-derives
        // from the local scan every cycle regardless, so it is unaffected either way.)
        if (deferDeletes) {
            log.warn("#108: holding delta cursor this cycle (deletes deferred); tombstones replay next sync")
        } else {
            promotePendingCursor()
        }

        // Reap remote directories emptied by this pass's remote file deletions.
        // The reconciler's folder-delete is gated by the never-hydrated-folder
        // guard, so an emptied remote directory is otherwise left behind. This
        // deletes only directories verified empty via listChildren, so it never
        // removes content the guard exists to protect.
        reapEmptyRemoteDirs(actions, remoteDeletedPaths)

        val duration = System.currentTimeMillis() - startTime
        reporter.onSyncComplete(
            downloaded.get(),
            uploaded.get(),
            conflicts.get(),
            duration,
            failed = passOneFailures.get() + transferFailures.get(),
        )
    }

    /**
     * Delete remote directories emptied by this pass's remote file deletions.
     *
     * The reconciler skips DeleteRemote for never-hydrated folder rows (a guard
     * against the accidental mass-deletion of remote folders the user never saw
     * locally). A side effect: when the FILES inside a remote folder are deleted,
     * the now-empty folder is left behind as a shell. This reaps those shells.
     *
     * Safety:
     *  - **Empty-verify before delete.** A directory is deleted only if a live
     *    `listChildren` shows no surviving child. We never trust the provider to
     *    refuse a non-empty directory (some providers cascade a folder delete
     *    over the whole subtree), so the listing is the guard. This also keeps
     *    the never-hydrated guard's intent intact: an EMPTY directory has no
     *    content to lose, so reaping it does not delete anything the user never
     *    saw.
     *  - **Scoped to this pass.** Only directories that were ancestors of a file
     *    deleted on this pass are candidates — never an arbitrary sweep of
     *    remote directories.
     *  - **Deepest-first**, so a nested empty tree collapses bottom-up.
     *  - **Best-effort.** A provider error on one directory is logged and
     *    skipped; it never fails the pass.
     *  - **Already gone is not a failure (#419).** A directory this pass's own
     *    DeleteRemote already removed ([alreadyDeleted]) is not a candidate, and
     *    a not-found from the listing or the delete (trashed meanwhile, e.g. by
     *    an ancestor's delete) means the goal is met — debug, not WARN.
     */
    private suspend fun reapEmptyRemoteDirs(
        actions: List<SyncAction>,
        alreadyDeleted: Set<String>,
    ) {
        val deletedRemoteFiles =
            actions.filterIsInstance<SyncAction.DeleteRemote>().map { it.path }
        if (deletedRemoteFiles.isEmpty()) return
        val candidates =
            deletedRemoteFiles
                .flatMap { ancestorDirsToSyncRoot(it) }
                .distinct()
                // A scoped pass may delete a file at the scope root, but its
                // ancestors belong to the wider tree. Reaping one can cascade-delete
                // siblings this pass did not enumerate (#525).
                .filter { SyncScope.contains(it, syncPaths) }
                .filterNot { it in alreadyDeleted }
                .sortedByDescending { it.count { ch -> ch == '/' } }
        for (dir in candidates) {
            try {
                val children = provider.listChildren(dir).filterNot { it.deleted }
                if (children.isNotEmpty()) continue
                provider.delete(dir)
                db.getEntry(dir)?.let { db.deleteEntry(dir) }
                log.debug("Reaped empty remote directory: {}", dir)
            } catch (e: Exception) {
                if (isRemoteNotFound(e)) {
                    log.debug("Remote directory already gone, nothing to reap: {}", dir)
                } else {
                    log.warn("Failed to reap empty remote directory {}: {}", dir, e.message)
                }
            }
        }
    }

    // #419: the provider says the path does not exist — the typed Internxt "not found"
    // shapes, or an HTTP 404 carried on the exception (same probe as remoteItemOrNull).
    private fun isRemoteNotFound(e: Throwable): Boolean =
        isAlreadyGone(e) || statusCodeOf(e) == 404 || (e.cause?.let { statusCodeOf(it) } == 404)

    /**
     * Ancestor directories of [filePath], immediate parent up to — but not
     * including — the sync root. `/a/b/c.txt` → `["/a/b", "/a"]`; a root-level
     * file → `[]`, so the sync root is never a reap candidate.
     */
    private fun ancestorDirsToSyncRoot(filePath: String): List<String> {
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

    private suspend fun dispatchStreamingDownload(
        action: SyncAction.DownloadContent,
        downloaded: AtomicInteger,
        transferFailures: AtomicInteger,
        authFailure: java.util.concurrent.atomic.AtomicReference<AuthenticationException?>,
        executedPaths: MutableSet<String>,
        gatherScope: kotlinx.coroutines.CoroutineScope,
        transferSemaphore: kotlinx.coroutines.sync.Semaphore,
    ) {
        withContext(Priority.Foreground) {
            transferSemaphore.withPermit {
                try {
                    applyDownload(action)
                    downloaded.incrementAndGet()
                    executedPaths.add(action.path)
                } catch (e: AuthenticationException) {
                    log.error(
                        "Authentication failed during streaming download of {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    restoreToPlaceholder(action.path, action.remoteItem)
                    transferFailures.incrementAndGet()
                    executedPaths.add(action.path)
                    authFailure.compareAndSet(null, e)
                    gatherScope.cancel()
                } catch (e: CancellationException) {
                    restoreToPlaceholder(action.path, action.remoteItem)
                    throw e
                } catch (e: PermanentDownloadFailureException) {
                    handlePermanentDownloadFailure(action, e)
                    transferFailures.incrementAndGet()
                    executedPaths.add(action.path)
                } catch (e: Exception) {
                    // #530: one WARN line, the stack trace at DEBUG.
                    log.warn(
                        "Streaming download failed for {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    log.debug(
                        "Streaming download failed for {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    reporter.onWarning("Failed: ${action.path} - ${e.message}")
                    logFailure(action, e)
                    restoreToPlaceholder(action.path, action.remoteItem)
                    transferFailures.incrementAndGet()
                    // UD-225 recovery picks failed downloads up on the next
                    // pass; mark executed so Pass 2 of *this* run doesn't
                    // double-dispatch.
                    executedPaths.add(action.path)
                }
            }
        }
    }

    private suspend fun dispatchStreamingUpload(
        action: SyncAction.Upload,
        uploaded: AtomicInteger,
        transferFailures: AtomicInteger,
        authFailure: java.util.concurrent.atomic.AtomicReference<AuthenticationException?>,
        executedPaths: MutableSet<String>,
        gatherScope: kotlinx.coroutines.CoroutineScope,
        transferSemaphore: kotlinx.coroutines.sync.Semaphore,
    ) {
        withContext(Priority.Foreground) {
            transferSemaphore.withPermit {
                try {
                    applyUpload(action)
                    uploaded.incrementAndGet()
                    executedPaths.add(action.path)
                } catch (e: AuthenticationException) {
                    log.error(
                        "Authentication failed during streaming upload of {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    transferFailures.incrementAndGet()
                    executedPaths.add(action.path)
                    authFailure.compareAndSet(null, e)
                    gatherScope.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // #530: one WARN line, the stack trace at DEBUG.
                    log.warn(
                        "Streaming upload failed for {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                    )
                    log.debug(
                        "Streaming upload failed for {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    reporter.onWarning("Failed: ${action.path} - ${e.message}")
                    logFailure(action, e)
                    transferFailures.incrementAndGet()
                    // UD-901 recovery picks failed uploads up on the next
                    // pass; mark executed so Pass 2 of *this* run doesn't
                    // double-dispatch.
                    executedPaths.add(action.path)
                }
            }
        }
    }

    private suspend fun gatherStreamingChanges(
        localChanges: Map<String, ChangeState>,
        scope: List<String>,
        downloaded: AtomicInteger,
        uploaded: AtomicInteger,
        transferFailures: AtomicInteger,
        authFailure: java.util.concurrent.atomic.AtomicReference<AuthenticationException?>,
        executedPaths: MutableSet<String>,
        transferSemaphore: kotlinx.coroutines.sync.Semaphore,
    ): Pair<Map<String, CloudItem>, List<SyncAction>> = withContext(Priority.Background) {
        val storedCursor = db.getSyncState("delta_cursor")
        val cursor = storedCursor?.ifEmpty { null }
        val isFullSync = cursor == null || provider.deltaIsFullListing
        val changes = mutableMapOf<String, CloudItem>()
        // #401: the streaming path admits through the same collision winner-rule as
        // the buffered gather (see gatherRemoteChanges).
        val keyToPath = HashMap<String, String>()
        val collisions = HashMap<String, RemoteGather.CollisionRecord>()
        val buffer = StreamingReconcileBuffer()
        val safeAccumulator = mutableListOf<SyncAction>()
        // resolveSlice processes the full localChanges map on every delta page,
        // so a MODIFIED-local file absent from a given page's remote delta
        // re-emits its Upload (and likewise a DownloadContent can recur across
        // pages). Without a precheck the executor would launch one dispatch per
        // recurrence — K concurrent applyUpload/applyDownload of the same path
        // across K pages. executedPaths only fills in post-completion, so it
        // can't dedup the in-flight sends. Claim the path here, before the send,
        // so two pages can never both forward the same transfer. Lives for the
        // gather only; Pass 2 dedups separately via executedPaths.
        val sentToExecutor =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()

        // UD-223 fast-bootstrap mirror: bootstrap adopts the cursor with
        // zero enumeration, so there's nothing to stream — fall through
        // to the same map-only path as the non-streaming gather.
        if (fastBootstrap && cursor == null && Capability.FastBootstrap in provider.capabilities()) {
            when (val result = provider.deltaFromLatest()) {
                is CapabilityResult.Success -> {
                    val page = result.value
                    for (item in page.items) {
                        val resolved = resolveItemPath(item) ?: continue
                        changes[resolved.path] = resolved
                    }
                    db.setSyncState("delta_cursor", page.cursor)
                    db.setSyncState("last_full_scan", Instant.now().toString())
                    // See the non-streaming `gatherRemoteChanges` fast-bootstrap path
                    // for rationale. Stale `scan_in_progress_*` offsets index into a
                    // different result set than the new cursor=now delta returns.
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
                    return@withContext changes to emptyList()
                }
                is CapabilityResult.Unsupported -> {
                    log.warn(
                        "UD-223 fast-bootstrap requested but provider '{}' does not support it ({}). " +
                            "Falling back to streaming first-sync enumeration.",
                        providerId,
                        result.reason,
                    )
                }
            }
        }

        val useShared =
            includeShared &&
                Capability.DeltaShared in provider.capabilities()

        val onPageProgress: (Int) -> Unit = { itemsSoFar ->
            reporter.onScanProgress("remote", itemsSoFar)
        }

        var allComplete = true
        val activeScan = db.getActiveScan(staleThreshold = java.time.Duration.ofHours(SCAN_CHECKPOINT_STALE_HOURS))
        val resumedItems: List<CloudItem> =
            if (activeScan != null) db.loadStagedItems(activeScan.scanId) else emptyList()
        val scanId =
            activeScan?.scanId
                ?: db.beginScan(initialMarker = null)
        if (activeScan != null) {
            log.info(
                "Resuming streaming-reconciliation scan id={} marker={} ({} previously-staged items)",
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
                scopeRoots = trackScope,
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
            log.debug("Delta (streaming): {} items, hasMore={}", page.items.size, page.hasMore)
            if (!page.complete) {
                allComplete = false
            }
            return page
        }

        fun persistPendingCursor(cursor: String) {
            db.setSyncState("pending_cursor", cursor)
            db.setSyncState("pending_cursor_complete", if (allComplete) "true" else "false")
        }

        // #115 streaming fix (page-order-independent): obtain the complete set of
        // remote top-level folder names with ONE cheap listing of the root's direct
        // children BEFORE the streaming loop starts.  XDG aliasing only concerns
        // top-level folders, so this single call is sufficient and does not defeat
        // streaming (we are NOT fetching the full tree — just root/children).
        //
        // This replaces the previous 1-page-lookahead accumulator
        // (accumulatedTopLevelNames), which failed when the canonical folder
        // (e.g. /Pictures) arrived more than one page after the slice carrying the
        // aliasable children (e.g. /Bilder/...) — remote delta/enumeration order
        // is NOT guaranteed to be parent-first.  With a stable pre-fetched set,
        // every resolveSlice() call receives the same complete set regardless of
        // which page the canonical folder's delta entry falls on.
        val stableRemoteTopLevelNames: Set<String> =
            try {
                provider
                    .listChildren("/")
                    .filter { it.isFolder && !it.deleted }
                    .mapTo(mutableSetOf()) { it.name }
            } catch (e: Exception) {
                // Non-fatal: if the preliminary listing fails (e.g. network glitch,
                // provider doesn't support it), fall back to an empty set so
                // resolveSlice() derives alias context from each page's own items
                // (pre-fix behaviour — still better than crashing the sync).
                log.warn(
                    "#115: preliminary root-children listing failed ({}); " +
                        "XDG aliasing may not fire for streaming pages whose canonical " +
                        "folder entry falls on a later page.",
                    e.message,
                )
                emptySet()
            }

        // Per-page reconciliation with 1-page rename-coalescing lookahead
        // (spec §3) on a bounded backpressure channel (spec §4).
        //
        // Producer coroutine: drives nextPage() in a loop, runs the
        // lookahead merge, and sends ready-to-reconcile page slices into
        // the channel. When the consumer falls behind, the send() suspends
        // — which suspends nextPage() — which back-pressures the
        // provider's delta() and its internal speculativeFetchPages
        // through coroutineScope semantics.
        //
        // Consumer coroutine: receives page slices, runs resolveSlice
        // against the full local map, classifies through
        // StreamingReconcileBuffer, accumulates safe-now actions, and
        // flips the staged-row state-machine on scan_staging.
        //
        // Channel capacity (STREAMING_RECONCILE_CHANNEL_CAPACITY = 4)
        // matches the per-provider transfer concurrency floor per spec
        // §4 decision 2. Backpressure pauses the scan instead of buffering
        // unboundedly: peak memory is bounded by 4 × page-size irrespective
        // of total drive size.
        data class HeldItem(val resolved: CloudItem, val originalId: String)
        data class PageSlice(
            val slice: Map<String, CloudItem>,
            val ids: List<String>,
            // The complete set of remote top-level folder names, pre-fetched
            // before the streaming loop (page-order-independent).  Passed to
            // resolveSlice() so XDG-alias context is built from the FULL stable
            // set regardless of which delta page the canonical folder entry
            // (e.g. /Pictures) falls on.
            val stableRemoteTopLevelNames: Set<String>,
        )

        val pageChannel =
            kotlinx.coroutines.channels
                .Channel<PageSlice>(capacity = STREAMING_RECONCILE_CHANNEL_CAPACITY)

        // Per-page streaming dispatch: as each page reconciles, its safe-now
        // DownloadContent and Upload actions are forwarded to an executor
        // coroutine that dispatches them concurrently with the still-running
        // gather. Time-to-first-byte goes from "full enum then transfers"
        // to "first transfer fires ~30 s after start, the rest stream in
        // as pages reconcile." The executor uses the same applyDownload /
        // applyUpload paths as Pass 2 and bumps the shared counters, so
        // the final onSyncComplete totals reflect the streamed transfers.
        // Pass 2 checks `executedPaths` to skip re-dispatch on the same
        // path. Capacity matches the page-channel: bounded memory under
        // backpressure.
        val executorChannel =
            kotlinx.coroutines.channels
                .Channel<SyncAction>(capacity = STREAMING_RECONCILE_CHANNEL_CAPACITY)

        coroutineScope {
            // Reference to this outer scope. The executor launches dispatch
            // jobs into it (rather than into a child scope) so an auth-
            // failure cancel here propagates to consumer + producer too,
            // mirroring Pass 2's `this@coroutineScope.cancel()` shape.
            val gatherScope = this

            // Consumer: reconcile slices as they arrive. Forward safe-now
            // transfer actions to the executor; the rest land in
            // safeAccumulator for the engine's final Pass 1 / Pass 2 sweep.
            val consumerJob =
                launch {
                    for (pageSlice in pageChannel) {
                        if (pageSlice.slice.isEmpty()) continue
                        val pageActions =
                            reconciler.resolveSlice(
                                pageSlice.slice,
                                localChanges,
                                scope,
                                pageSlice.stableRemoteTopLevelNames,
                                downloadOnly = syncDirection == SyncDirection.DOWNLOAD,
                            )
                        val safeNow = buffer.classify(pageActions)
                        safeAccumulator.addAll(safeNow)
                        for (action in safeNow) {
                            if (action is SyncAction.DownloadContent || action is SyncAction.Upload) {
                                // #200(b): a NEW-local upload must NOT dispatch mid-gather —
                                // enumeration completeness isn't known until scan-end. It stays in
                                // safeAccumulator, so finalizeStreaming's gate either keeps it
                                // (complete → uploaded in Pass 2) or defers it (incomplete),
                                // never duplicating an un-enumerated remote file. Downloads and
                                // MODIFIED/replace uploads still stream concurrently.
                                if (action is SyncAction.Upload && localChanges[action.path] == ChangeState.NEW) continue
                                // Claim before forwarding so a transfer re-emitted on a later
                                // page is sent to the executor exactly once. add() returns
                                // false when already claimed; concurrent set so two page-slices
                                // can't both win the claim.
                                //
                                // An Upload's local content is fixed for the gather
                                // (localChanges is computed once), so a MODIFIED upload that
                                // recurs across pages is a true duplicate — claim by path.
                                // A DownloadContent, however, carries this page's remote
                                // metadata: if the remote was edited again on a later page the
                                // later DownloadContent is a genuinely-newer transfer, so key
                                // it on (path, hash, modified) — only an identical re-emission
                                // is dropped, never a fresher remote version.
                                val claimKey =
                                    when (action) {
                                        is SyncAction.DownloadContent ->
                                            "${action.path}|${action.remoteItem.hash}|${action.remoteItem.modified}"
                                        else -> action.path
                                    }
                                if (!sentToExecutor.add(claimKey)) continue
                                executorChannel.send(action)
                            }
                        }
                        if (pageSlice.ids.isNotEmpty()) {
                            db.markStagedReconciled(scanId, pageSlice.ids)
                        }
                    }
                    // Producer is done and we drained the page channel; the
                    // executor can stop accepting new work and finish its
                    // in-flight items.
                    executorChannel.close()
                }

            // Executor: dispatch safe-now transfers concurrently with the
            // gather. Mirrors the Pass 2 dispatch shape (foreground priority,
            // shared semaphore, restore-to-placeholder on download failure)
            // so a streamed transfer is indistinguishable from a Pass 2
            // dispatch in terms of side effects. Launches into `gatherScope`
            // (not into a child scope) so an auth failure cancels consumer
            // + producer too, matching Pass 2's behaviour.
            val executorJob =
                launch {
                    // Serialize transfers that target the SAME path, in channel (page)
                    // order. A path can surface a second transfer on a later page — a
                    // remote edited again mid-enumeration yields a newer DownloadContent
                    // with different metadata, which the content-aware claim deliberately
                    // lets through. If both ran concurrently to the same destination + DB
                    // row, a slower earlier-page transfer could finish last and overwrite
                    // the fresher bytes/metadata. Chaining each path's job onto the prior
                    // one (join, which waits without rethrowing) makes the later page win.
                    // Distinct paths get a null prior, so they still run concurrently. The
                    // loop is single-threaded, so this map needs no synchronisation.
                    val lastJobPerPath = HashMap<String, kotlinx.coroutines.Job>()
                    for (action in executorChannel) {
                        val prior = lastJobPerPath[action.path]
                        when (action) {
                            is SyncAction.DownloadContent ->
                                lastJobPerPath[action.path] =
                                    gatherScope.launch {
                                        prior?.join()
                                        dispatchStreamingDownload(
                                            action = action,
                                            downloaded = downloaded,
                                            transferFailures = transferFailures,
                                            authFailure = authFailure,
                                            executedPaths = executedPaths,
                                            gatherScope = gatherScope,
                                            transferSemaphore = transferSemaphore,
                                        )
                                    }
                            is SyncAction.Upload ->
                                lastJobPerPath[action.path] =
                                    gatherScope.launch {
                                        prior?.join()
                                        dispatchStreamingUpload(
                                            action = action,
                                            uploaded = uploaded,
                                            transferFailures = transferFailures,
                                            authFailure = authFailure,
                                            executedPaths = executedPaths,
                                            gatherScope = gatherScope,
                                            transferSemaphore = transferSemaphore,
                                        )
                                    }
                            else -> {
                                // Defensive: classify() only surfaces
                                // Download/Upload to us. Ignore the rest so
                                // a future SyncAction variant doesn't crash.
                            }
                        }
                    }
                }

            // Producer: page-fetch loop with 1-page rename-coalescing lookahead.
            launch {
                var heldItems: MutableMap<String, HeldItem>? = null

                suspend fun flushHeld() {
                    val held = heldItems ?: return
                    heldItems = null
                    if (held.isEmpty()) return
                    val slice = LinkedHashMap<String, CloudItem>()
                    val ids = mutableListOf<String>()
                    for ((_, item) in held) {
                        ids.add(item.originalId)
                        // #401: single admit into the gather map. The page slice carries
                        // the key's winner only when THIS page's item is it — a twin that
                        // lost to an earlier page's winner was already reconciled on that
                        // page, and the collision is recorded by the admit itself.
                        val winner = admit(changes, keyToPath, collisions, item.resolved)
                        if (winner.id == item.resolved.id) {
                            slice[winner.path] = winner
                        }
                    }
                    reporter.onScanProgress("remote", changes.size)
                    // Pass the pre-fetched complete top-level set (page-order-independent).
                    pageChannel.send(PageSlice(slice, ids, stableRemoteTopLevelNames))
                }

                suspend fun ingestPage(page: DeltaPage) {
                    persistPendingCursor(page.cursor)

                    // Resolve and stage the new page's items keyed by remote id.
                    // resolveItemPath may collapse to null for unreachable deletes;
                    // those entries are dropped from the lookahead (the next sync
                    // will surface them once their context is in the local DB).
                    val newItems = LinkedHashMap<String, HeldItem>()
                    for (item in page.items) {
                        val resolved = resolveItemPath(item) ?: continue
                        newItems[resolved.id] = HeldItem(resolved, resolved.id)
                    }

                    // Merge step: any id in newItems that also lives in the held
                    // page gets its held entry's path overridden with the new
                    // page's path (later page wins).
                    val held = heldItems
                    if (held != null) {
                        for ((id, item) in newItems) {
                            val priorHeld = held[id] ?: continue
                            held[id] =
                                HeldItem(
                                    resolved =
                                        priorHeld.resolved.copy(
                                            path = item.resolved.path,
                                            name = item.resolved.name,
                                        ),
                                    originalId = priorHeld.originalId,
                                )
                        }
                    }

                    // Release the held page for reconciliation (now with any
                    // merged paths applied) and replace it with the new page.
                    flushHeld()
                    heldItems = newItems
                }

                try {
                    var page = nextPage(cursor)
                    ingestPage(page)
                    while (page.hasMore) {
                        page = nextPage(page.cursor)
                        ingestPage(page)
                    }
                    // Final flush of whatever's still held after the last page.
                    flushHeld()
                } finally {
                    pageChannel.close()
                }
            }

            consumerJob.join()
            // executorJob.join() is implicit at coroutineScope exit, but be
            // explicit: scope-exit will wait for it anyway. The consumer
            // closed executorChannel above so the executor's `for` loop
            // exits cleanly once it drains in-flight items.
            executorJob.join()
        }
        // AuthenticationException latched by the executor — surface to the
        // caller exactly like Pass 2 does so token refresh / re-auth flows
        // trigger instead of a silent "0 downloaded, many failed" result.
        // Pass 2 does the same rethrow after its own scope, but doing it
        // here too means a streaming-only auth failure (no Pass 2 actions
        // left to dispatch) still propagates.
        authFailure.get()?.let { throw it }

        if (allComplete) {
            db.completeScan(scanId)
        }

        // #401: report suppressed twins BEFORE the absence sweep so their ids are
        // excluded from reaping (same ordering as the buffered gather).
        val collisionLoserIds = reportGatheredCollisions(collisions, dryRun = false)

        if (isFullSync && allComplete) {
            detectMissingAfterFullSync(changes, collisionLoserIds)
        } else if (isFullSync) {
            val msg =
                "UD-360: at least one delta page returned complete=false; " +
                    "skipping detectMissingAfterFullSync to avoid spurious del-local actions. " +
                    "The missing inventory will be picked up on the next sync run."
            log.warn(msg)
            reporter.onWarning(msg)
        }
        // Record the gather's full-enumeration verdict (see gatherRemoteChanges).
        db.setSyncState("last_gather_full", isFullSync.toString())

        val deferred = buffer.drainDeferred()
        changes to (safeAccumulator + deferred)
    }

    private suspend fun applyCreatePlaceholder(action: SyncAction.CreatePlaceholder) {
        // #230 (PR #242 review): applyDownload guards the file-download path, but folders and
        // "both new" adopt placeholders reach createFolder/createPlaceholder here. A name that
        // cannot exist on the local filesystem (Windows reserved / all-dots / trailing-dot, etc.)
        // would fail the mkdir every poll cycle. Quarantine the row instead — the same flag
        // handlePermanentDownloadFailure sets, so the reconciler's recovery loop skips it
        // thereafter (Reconciler.kt) — rather than re-attempting an impossible create forever.
        localNameIssue(action.path)?.let { issue ->
            log.warn("Cannot represent '{}' on the local filesystem ({}) — quarantining row", action.path, issue)
            reporter.onWarning("Permanent failure: ${action.path} - cannot represent on the local filesystem: $issue")
            val remoteId = action.remoteItem.id
            if (remoteId.isNotEmpty()) {
                db.setDownloadQuarantine(remoteId, Instant.now())
            }
            return
        }
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
                // #115: the REMOTE source is item.path (canonical); the local
                // destination is localPath (resolved from the real-local action.path).
                downloadByIdOrPath(item, item.path, localPath)
                placeholder.restoreMtime(action.path, item.modified)
            }
        }

        val isHydrated = hasRealContent || item.size == 0L || shouldDownload
        val entry = entryFromCloudItem(item, action.path, isHydrated)
        // #396: only bytes the engine just wrote get a local hash; an adopted file is not re-read.
        db.upsertEntry(if (shouldDownload) withLocalHash(entry) else entry)
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
                    // #115: remote source = item.path (canonical); dest = real-local.
                    downloadByIdOrPath(item, item.path, placeholder.resolveLocal(action.path))
                    placeholder.restoreMtime(action.path, item.modified)
                } catch (e: Exception) {
                    restoreToPlaceholder(action.path, item)
                    throw e
                }
            } else if (!item.isFolder) {
                placeholder.updatePlaceholderMetadata(action.path, item.size, item.modified)
            }
        }

        val entry = entryFromCloudItem(item, action.path, action.wasHydrated)
        // #396: the hydrated branch above re-downloaded real bytes; metadata-only updates did not.
        db.upsertEntry(if (action.wasHydrated && !item.isFolder) withLocalHash(entry) else entry)
    }

    internal suspend fun downloadByIdOrPath(
        item: CloudItem,
        remotePath: String,
        destination: java.nio.file.Path,
    ): Long = Transfers.downloadByIdOrPath(provider, item, remotePath, destination)

    private suspend fun applyDownload(action: SyncAction.DownloadContent) {
        log.debug("Download: {} ({} bytes)", action.path, action.remoteItem.size)
        // #230: a name that cannot exist on the local filesystem (Windows
        // all-dots / trailing-dot / reserved device name, etc.) can never be
        // downloaded here. Surface it as a permanent failure so the row is
        // quarantined (handlePermanentDownloadFailure) instead of re-attempted
        // every poll cycle.
        localNameIssue(action.path)?.let { issue ->
            throw PermanentDownloadFailureException(
                "cannot represent '${action.path}' on the local filesystem: $issue",
            )
        }
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
                // #115: remote source = remoteItem.path (canonical); local
                // destination = localPath (resolved from real-local action.path).
                downloadByIdOrPath(action.remoteItem, action.remoteItem.path, localPath)
                placeholder.restoreMtime(action.path, action.remoteItem.modified)
            }

            if (verifyIntegrity) {
                val verified = HashVerifier.verify(localPath, action.remoteItem.hash, algorithm = provider.hashAlgorithm())
                if (!verified) {
                    reporter.onWarning("Integrity check failed: ${action.path}")
                }
            }

            db.upsertEntry(withLocalHash(entryFromCloudItem(action.remoteItem, action.path, isHydrated = true)))
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
        // #115: LOCAL read uses action.path (the real local path); the REMOTE
        // upload target is action.remoteTarget ?: action.path. For a non-aliased
        // upload remoteTarget is null, so remotePath == action.path and this is
        // byte-identical to pre-#115 behaviour.
        val localPath = placeholder.resolveLocal(action.path)
        val remotePath = action.remoteTarget ?: action.path
        val sizeForLog = if (Files.isRegularFile(localPath)) Files.size(localPath) else -1L
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Upload: {} -> {} ({} bytes)", action.path, remotePath, sizeForLog)
        // UD-113: capture pre-action remote hash so the audit entry's oldHash reflects
        // the version we replaced (or null for a fresh upload).
        val prevHash = db.getEntry(action.path)?.remoteHash
        val sent = statBeforeUpload(localPath)
        val result =
            try {
                // UD-366: action.remoteId carries the existing remote UUID for MODIFIED uploads;
                // null for NEW. Internxt routes through PUT /files/{uuid} when non-null.
                provider.upload(localPath, remotePath, existingRemoteId = action.remoteId) { transferred, total ->
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
        // The eventually-consistent delta feed may not list this just-written remote
        // item on the next full enumeration; mark it so the absence sweep defers its
        // deletion verdict instead of reaping + re-uploading in a loop. Marked by
        // the REMOTE path because the absence sweep reasons in the remote namespace.
        markRecentlyUploaded(remotePath)
        // #337/#148: the row records the file's stats as they were BEFORE the
        // transfer. A write that lands while the upload is in flight must stay
        // visible to the next scan (which re-uploads it); recording the
        // post-upload mtime of a changed file would absorb the edit into the
        // baseline and it would never be sent.
        val afterUpload = statBeforeUpload(localPath)
        if (afterUpload != null && sent != null && afterUpload != sent) {
            log.debug(
                "Upload: {} changed while it was being uploaded; keeping the pre-upload watermark so the next scan re-plans it",
                action.path,
            )
        }
        val mtime = sent?.first ?: afterUpload?.first ?: Files.getLastModifiedTime(localPath).toMillis()
        val size = sent?.second ?: afterUpload?.second ?: Files.size(localPath)
        db.upsertEntry(
            SyncEntry(
                path = action.path,
                // #115: persist the canonical remote path so the next sync's
                // delta (keyed at the canonical) matches this row by effective
                // remote path — no re-upload, no spurious MoveLocal.
                remotePath = action.remoteTarget,
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
            ).let { withSentHash(it, localPath, sent) }, // #396: hashless providers get the SHA-256 of the bytes just sent
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
        // #115: action.fromPath / action.path are REAL-LOCAL (DB + resolveLocal
        // ops below key on them). The REMOTE move runs in the canonical
        // namespace: source = source row's `remotePath ?: fromPath`, dest =
        // action.remoteTarget ?: action.path. Non-aliased rows have remotePath
        // null and remoteTarget null → identical to pre-#115 behaviour.
        val oldEntry = db.getEntry(action.fromPath)
        val remoteFrom = oldEntry?.remotePath ?: action.fromPath
        val remoteTo = action.remoteTarget ?: action.path
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Move: {} -> {} (remote {} -> {})", action.fromPath, action.path, remoteFrom, remoteTo)
        val isFolder = oldEntry?.isFolder ?: false
        val result =
            try {
                if (!action.remoteId.startsWith("local:")) {
                    // #402: aim the move at the twin the row tracks, not at whichever
                    // same-named sibling getMetadata's path lookup finds first.
                    provider.moveById(action.remoteId, remoteFrom, remoteTo)
                } else {
                    if (refuseCollidedPath(action)) return
                    provider.move(remoteFrom, remoteTo)
                }
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
                // #115: persist the canonical remote destination so the next
                // sync's delta (canonical-keyed) matches this row by effective
                // remote path.
                remotePath = action.remoteTarget,
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
                // #396: a rename does not touch the bytes, so the recorded local hash stays valid.
                localHash = oldEntry?.localHash,
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
        db.upsertEntry(
            entryFromCloudItem(action.remoteItem, action.path, oldEntry?.isHydrated ?: false)
                .copy(localHash = oldEntry?.localHash),
        )
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

    /**
     * #421: true while the remote still has [fromPath], so a delete of a folder a move failed to
     * take something out of stays guarded. A move that failed AFTER the provider applied it (a
     * multi-leg move, the state.db writes) leaves the source gone: re-planning that move then
     * fails with "Item not found" every pass, and guarding the delete on it would keep the
     * folder undeletable forever — the exact non-convergence this guard was added to prevent.
     * With the source gone the move is effectively applied: the delete runs (itself a no-op
     * against the live remote), the rows tombstone, and the reconciler adopts the moved items
     * by id on the next pass.
     */
    private suspend fun moveSourceStillRemote(fromPath: String): Boolean =
        try {
            remoteItemIfPresent(fromPath) != null
        } catch (e: Exception) {
            log.debug(
                "#421: probing the move source {} failed ({}); guarding the delete anyway",
                fromPath,
                e.message ?: e.javaClass.simpleName,
            )
            true
        }

    /**
     * #421: true when [action] deletes a folder that a remote move failed to take something out
     * of earlier in this pass. Deleting it now would trash whatever the move left behind (the
     * provider's delete takes the folder's whole subtree with it); the delete and the failed move
     * both re-plan on the next sync instead.
     */
    private fun deleteBlockedByFailedMove(
        action: SyncAction.DeleteRemote,
        failedMoveSources: List<String>,
    ): Boolean {
        val blockedBy =
            failedMoveSources.firstOrNull { it == action.path || it.startsWith(action.path + "/") }
                ?: return false
        log.warn("Not deleting {}: the move of {} out of it failed; both are retried on the next sync", action.path, blockedBy)
        reporter.onWarning("Skipped delete of ${action.path}: moving $blockedBy out of it failed")
        return true
    }

    private suspend fun applyDeleteRemote(action: SyncAction.DeleteRemote) {
        // UD-113: capture pre-delete row for the audit entry.
        val priorEntry = db.getEntry(action.path)
        // #115: DeleteRemote.path is real-local (DB lookup above keys on it).
        // The REMOTE delete target is the row's effective remote path
        // (`remotePath ?: path`). Non-aliased rows have remotePath null →
        // delete at action.path, byte-identical to pre-#115.
        val remotePath = priorEntry?.remotePath ?: action.path
        val remoteId = priorEntry?.remoteId
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Delete: {} (remote {})", action.path, remotePath)
        var auditResult = "success"
        try {
            if (remoteId != null && !remoteId.startsWith("local:")) {
                // #402: the row's real cloud uuid addresses the twin the engine
                // actually tracks; path resolution would pick the first of any
                // same-named siblings and could trash the wrong one.
                provider.deleteById(remoteId, remotePath)
            } else {
                if (refuseCollidedPath(action)) return
                provider.delete(remotePath)
            }
        } catch (e: ProviderException) {
            // Only a typed already-gone signal (Folder/Item not found) is a
            // safe no-op delete that may fall through and tombstone the row.
            // Every other ProviderException (transient 5xx/429, connection
            // reset, auth, throttle) must re-throw so the row is left untouched
            // and DeleteRemote re-emits on the next sync — otherwise a tombstoned
            // row never re-emits and the remote file is orphaned forever.
            if (!isAlreadyGone(e)) {
                auditLog?.emit(
                    action = "Delete",
                    path = action.path,
                    size = priorEntry?.remoteSize,
                    oldHash = priorEntry?.remoteHash,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
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

    private fun applyRemoveEntry(action: SyncAction.RemoveEntry) {
        val entry = db.getEntry(action.path)
        val remoteId = entry?.remoteId
        if (remoteId != null) {
            db.setStatusTrashed(remoteId)
        } else {
            db.deleteEntry(action.path)
        }
    }

    /**
     * #116: fast-bootstrap adopt-on-name-match for a TOP-LEVEL folder. Returns true
     * when the folder's name already exists at the remote root and the state.db row
     * was adopted (capturing the existing remoteId) — the caller then SKIPS the
     * CreateRemoteFolder, avoiding the 409 the mkdir would otherwise hit.
     *
     * Scope is deliberately narrow: only single-segment paths (drive-root children),
     * which is exactly the spurious-mkdir set the issue's evidence shows. Nested
     * folders fall through to the normal create — their parent was either adopted
     * (so the create lands under it) or created this run.
     */
    private suspend fun adoptExistingTopLevelFolder(
        action: SyncAction.CreateRemoteFolder,
        remotePath: String,
    ): Boolean {
        val name = topLevelFolderName(remotePath) ?: return false
        val existing = populateRemoteTopLevel()[name] ?: return false
        db.upsertEntry(
            SyncEntry(
                path = action.path,
                remotePath = action.remoteTarget,
                remoteId = existing.id,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = existing.modified,
                localMtime = Files.getLastModifiedTime(placeholder.resolveLocal(action.path)).toMillis(),
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
                parentUuid = existing.parentId,
            ),
        )
        log.info(
            "#116 fast-bootstrap: adopted existing remote folder {} (remoteId={}) instead of mkdir",
            action.path,
            existing.id,
        )
        auditLog?.emit(
            action = "CreateRemoteFolder",
            path = action.path,
            result = "adopted",
        )
        return true
    }

    // #116: the single path segment of a drive-root child, or null when the path
    // is the root itself or nested deeper than one level (adopt-on-match is
    // top-level-only — see adoptExistingTopLevelFolder).
    private fun topLevelFolderName(remotePath: String): String? {
        val trimmed = remotePath.trim('/')
        if (trimmed.isEmpty() || trimmed.contains('/')) return null
        return trimmed
    }

    // #116: lazily list the remote root's direct child folders once per run and
    // index them by name. A failed listing caches an empty map so we don't retry
    // per folder — falling back to the pre-fix mkdir (which 409s, but is no worse
    // than before the fix).
    private suspend fun populateRemoteTopLevel(): Map<String, CloudItem> {
        remoteTopLevelByName?.let { return it }
        return remoteTopLevelMutex.withLock {
            remoteTopLevelByName?.let { return@withLock it }
            val byName =
                try {
                    provider
                        .listChildren("/")
                        .filter { it.isFolder && !it.deleted }
                        .associateBy { it.name }
                } catch (e: CancellationException) {
                    // Never swallow cancellation as a listing failure: doing so would
                    // cache an empty map and let a shutdown/cancel pass fall through to
                    // issuing mkdirs (remote writes during teardown, the 409s this avoids).
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "#116 fast-bootstrap: root-children listing failed ({}); " +
                            "matching top-level folders will fall back to mkdir (may 409).",
                        e.message,
                    )
                    emptyMap()
                }
            remoteTopLevelByName = byName
            byName
        }
    }

    private suspend fun applyCreateRemoteFolder(action: SyncAction.CreateRemoteFolder) {
        // #115: REMOTE create target = remoteTarget ?: path; LOCAL mtime read
        // uses action.path. Non-aliased: remoteTarget null → identical to today.
        val remotePath = action.remoteTarget ?: action.path
        // #116: under fast-bootstrap the pre-existing cloud tree is invisible to
        // the planner, so the local scanner emits a CreateRemoteFolder for every
        // top-level local folder — including ones whose name already exists on the
        // remote, each of which then 409s. Adopt-on-name-match: if the matching
        // remote folder already exists, register its remoteId and skip the mkdir.
        if (fastBootstrapActive && adoptExistingTopLevelFolder(action, remotePath)) {
            return
        }
        val result =
            try {
                provider.createFolder(remotePath)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // #531: under a fast-bootstrap-adopted cursor the pre-existing cloud tree is
                // invisible to state.db, so the planner emits mkdir-remote for folders that exist
                // remotely (a second device, a lost row) and each one fails with 409
                // nameAlreadyExists — the same failure, run after run. Resolve it here: when the
                // create fails because the folder is already there, adopt the existing folder's
                // remote identity instead of failing the run, at any depth and on any run.
                // Anything else rethrows.
                val existing =
                    try {
                        provider.getMetadata(remotePath).takeIf { it.isFolder }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        null
                    }
                if (existing != null) {
                    log.info(
                        "#531: adopted existing remote folder {} (remoteId={}) instead of mkdir",
                        action.path,
                        existing.id,
                    )
                    auditLog?.emit(
                        action = "CreateRemoteFolder",
                        path = action.path,
                        result = "adopted-existing",
                    )
                    db.upsertEntry(
                        SyncEntry(
                            path = action.path,
                            remotePath = action.remoteTarget,
                            remoteId = existing.id,
                            remoteHash = null,
                            remoteSize = 0,
                            remoteModified = existing.modified,
                            localMtime = Files.getLastModifiedTime(placeholder.resolveLocal(action.path)).toMillis(),
                            localSize = 0,
                            isFolder = true,
                            isPinned = false,
                            isHydrated = true,
                            lastSynced = Instant.now(),
                            parentUuid = existing.parentId,
                        ),
                    )
                    return
                }
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
                remotePath = action.remoteTarget,
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

    /**
     * #123: applies a contiguous run of CreateRemoteFolder actions with bounded concurrency.
     *
     * The run is split into depth-ordered batches (see [createFolderBatches]); each batch runs
     * concurrently under a [concurrency]-permit semaphore, and the next batch starts only after
     * the current one fully drains — that barrier preserves the parent-before-child invariant
     * (a child folder is always one level deeper than its parent, so its parent's create is in an
     * earlier batch and has completed).
     *
     * Partial-failure tolerance (mirrors the per-action sequential path): a single createFolder
     * failure is logged + recorded and the rest of the plan continues. A folder whose parent's
     * create failed in an earlier batch is skipped (its create cannot succeed without the parent)
     * and counted as a failure too, so the count surfaced to the caller reflects the real damage.
     *
     * @return the number of folders in this run that failed (or were skipped due to a failed parent).
     */
    private suspend fun applyCreateRemoteFolderRun(
        run: List<SyncAction.CreateRemoteFolder>,
        concurrency: Int,
        passOneFailures: AtomicInteger,
        completedActions: AtomicInteger,
        totalActions: Int,
    ): Int {
        val failedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val failures = AtomicInteger(0)
        val semaphore = kotlinx.coroutines.sync.Semaphore(concurrency.coerceAtLeast(1))
        for (batch in createFolderBatches(run)) {
            coroutineScope {
                for (action in batch) {
                    launch {
                        // Skip when a parent create failed earlier in this run: the
                        // provider would reject the child with "parent not found".
                        val parent = parentOfTracked(action.path, failedPaths)
                        if (parent != null) {
                            log.warn(
                                "Skipping create-folder {} — parent create failed ({})",
                                action.path,
                                parent,
                            )
                            failedPaths.add(normalizeTrackedPath(action.path))
                            failures.incrementAndGet()
                            passOneFailures.incrementAndGet()
                            reporter.onActionProgress(
                                completedActions.incrementAndGet(),
                                totalActions,
                                actionLabel(action),
                                displayPath(action),
                            )
                            return@launch
                        }
                        try {
                            semaphore.withPermit { applyCreateRemoteFolder(action) }
                        } catch (e: AuthenticationException) {
                            log.error(
                                "Authentication failed, stopping sync: {}: {}{}",
                                e.javaClass.simpleName,
                                e.message,
                                org.krost.unidrive.requestIdSuffix(e),
                                e,
                            )
                            throw e
                        } catch (e: Exception) {
                            failedPaths.add(normalizeTrackedPath(action.path))
                            failures.incrementAndGet()
                            passOneFailures.incrementAndGet()
                            // #530: one WARN line, the stack trace at DEBUG.
                            log.warn(
                                "Action failed for {}: {}: {}{}",
                                action.path,
                                e.javaClass.simpleName,
                                e.message,
                                org.krost.unidrive.requestIdSuffix(e),
                            )
                            log.debug(
                                "Action failed for {}: {}: {}{}",
                                action.path,
                                e.javaClass.simpleName,
                                e.message,
                                org.krost.unidrive.requestIdSuffix(e),
                                e,
                            )
                            reporter.onWarning("Failed: ${action.path} - ${e.message}")
                            logFailure(action, e)
                        } finally {
                            reporter.onActionProgress(
                                completedActions.incrementAndGet(),
                                totalActions,
                                actionLabel(action),
                                displayPath(action),
                            )
                        }
                    }
                }
            }
        }
        return failures.get()
    }

    private fun normalizeTrackedPath(p: String): String {
        val s = if (p.startsWith("/")) p else "/$p"
        return if (s.length > 1) s.trimEnd('/') else s
    }

    // Returns the nearest ancestor path that already failed its create in this run, or null.
    private fun parentOfTracked(
        path: String,
        failedPaths: Set<String>,
    ): String? {
        var anc = normalizeTrackedPath(path)
        val cut = anc.lastIndexOf('/')
        anc = if (cut <= 0) "" else anc.substring(0, cut)
        while (anc.isNotEmpty()) {
            if (anc in failedPaths) return anc
            val i = anc.lastIndexOf('/')
            anc = if (i <= 0) "" else anc.substring(0, i)
        }
        return null
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
        // The side copy holds the user's OWN edit, so it is named "conflict-local".
        // Keeping the user's edit on a side path (rather than the canonical path)
        // means a later delete of the canonical path by another actor reaps the
        // remote-derived canonical — NOT the user's surviving edit. The canonical
        // path becomes the remote version (the tracked entity).
        val conflictSuffix =
            if (ext.isNotEmpty()) {
                ".conflict-local-$timestamp.$ext"
            } else {
                ".conflict-local-$timestamp"
            }

        if (action.remoteItem != null && !action.remoteItem.deleted) {
            // Both sides diverged: preserve the local edit as a side copy, then
            // let the remote take the canonical path.
            if ((action.localState == ChangeState.NEW || action.localState == ChangeState.MODIFIED) &&
                Files.exists(localPath)
            ) {
                val conflictPath = "$base$conflictSuffix"
                val conflictLocal = placeholder.resolveLocal(conflictPath)
                // Move the user's edit aside under the conflict-local name. It is a
                // local-only file (untracked, never uploaded) — the user's recoverable
                // copy of their own work.
                withEchoSuppression(conflictPath) {
                    Files.move(localPath, conflictLocal, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            // The remote version takes the canonical path and becomes the tracked
            // entity (real bytes, not a NUL stub).
            applyCreatePlaceholder(
                SyncAction.CreatePlaceholder(action.path, action.remoteItem, shouldHydrate = !action.remoteItem.isFolder),
            )
        } else if (action.localState == ChangeState.DELETED && action.remoteItem != null) {
            // UD-222: remote wins the conflict — download real bytes, not a NUL stub.
            applyCreatePlaceholder(
                SyncAction.CreatePlaceholder(action.path, action.remoteItem, shouldHydrate = !action.remoteItem.isFolder),
            )
        } else if (action.remoteState == ChangeState.DELETED && Files.exists(localPath)) {
            val sent = statBeforeUpload(localPath)
            val result =
                provider.upload(localPath, action.path) { transferred, total ->
                    reporter.onTransferProgress(action.path, transferred, total)
                }
            // #337/#148: record the pre-upload stat (same rule as applyUpload) so a
            // write landing during this upload is re-planned by the next scan.
            val afterUpload = statBeforeUpload(localPath)
            if (afterUpload != null && sent != null && afterUpload != sent) {
                log.debug(
                    "Conflict keep-both: {} changed while it was being uploaded; keeping the pre-upload watermark",
                    action.path,
                )
            }
            val mtime = sent?.first ?: afterUpload?.first ?: Files.getLastModifiedTime(localPath).toMillis()
            val size = sent?.second ?: afterUpload?.second ?: Files.size(localPath)
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
                ).let { withSentHash(it, localPath, sent) },
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
        // #115: when the action's local [path] differs from the remote item's
        // canonical path, this is a locale-aliased row — persist the canonical
        // as remotePath so the next delta matches by effective remote path.
        // Equal paths (the universal case) leave remotePath null → byte-identical.
        remotePath = if (item.path != path) item.path else null,
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

    // #396: for a provider with no remote content hash (Internxt), record the SHA-256 of the
    // bytes the engine itself just wrote (download) or sent (upload). LocalScanner uses it to
    // recognise a file whose mtime was bumped by something else (shell handler, indexer,
    // antivirus) as unchanged instead of planning a redundant re-upload. Hash-capable
    // providers are left alone: the scanner already compares against the remote hash there.
    //
    // The hash is kept only if the file still has the mtime and size the row records, so a
    // write that lands while we are hashing cannot turn the new bytes into the tracked
    // baseline. Any read failure just leaves the hash null (today's mtime+size behaviour).
    private fun withLocalHash(entry: SyncEntry): SyncEntry {
        if (provider.hashAlgorithm() != null || entry.isFolder) return entry
        val mtime = entry.localMtime ?: return entry
        val size = entry.localSize ?: return entry
        return withLocalHash(entry, placeholder.resolveLocal(entry.path), mtime, size)
    }

    // Same guard for a copy of the bytes that is not the sync-root file — the cache copy a
    // hydration downloaded or a write-back was uploaded from. The row records that copy's
    // stats, so the hash must describe that copy and be dropped if it changes mid-hash.
    // #560 U3: shared with the mount front-end, see [Transfers.withLocalHash].
    internal fun withLocalHash(
        entry: SyncEntry,
        local: Path,
        mtime: Long,
        size: Long,
    ): SyncEntry = Transfers.withLocalHash(entry, local, mtime, size, provider.hashAlgorithm(), log)

    // mtime and size of a file about to be uploaded, or null when it cannot be read.
    private fun statBeforeUpload(local: Path): Pair<Long, Long>? = Transfers.statBeforeUpload(local)

    // See [Transfers.withSentHash] (#337/#148: hash only the bytes that were sent).
    private fun withSentHash(
        entry: SyncEntry,
        local: Path,
        sent: Pair<Long, Long>?,
    ): SyncEntry = Transfers.withSentHash(entry, local, sent, provider.hashAlgorithm(), log)

    private fun buildCanonicalToLocalTopMap(remoteChanges: Map<String, CloudItem>): Map<String, String> {
        val topLevelNames = remoteChanges.keys
            .filter { it.count { c -> c == '/' } == 1 && !remoteChanges[it]!!.deleted }
            .map { it.removePrefix("/") }
            .toSet()
        val aliases = XdgLocaleDirAliases.build(
            remoteTopLevelNames = topLevelNames,
            userDirsOverrides = xdgUserDirsOverrides,
        )
        if (aliases.isEmpty) return emptyMap()
        val rev = HashMap<String, String>()
        for ((localTop, canonical) in aliases.localToCanonicalMap()) {
            if (canonical in rev.values) continue
            if (Files.isDirectory(syncRoot.resolve(localTop))) rev[canonical] = localTop
        }
        return rev
    }

    private fun updateRemoteEntries(remoteChanges: Map<String, CloudItem>) = remoteGather.updateRemoteEntries(remoteChanges)

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
    private fun applyTopLevelHydrationGuard(
        actions: List<SyncAction>,
        dryRun: Boolean,
    ): List<SyncAction> {
        if (actions.none { it is SyncAction.DeleteRemote }) return actions
        val hydrationCache = mutableMapOf<String, Boolean>()
        val kept = mutableListOf<SyncAction>()
        var skipped = 0
        var audited = 0
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
                logSkippedOp(action, "top_level_never_hydrated", dryRun)
                audited++
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
        if (dryRun && audited > 0) {
            reporter.onWarning(
                "Dry-run: $audited del-remote action(s) hit the never-hydrated top-level guard " +
                    "(a real run records them in skipped-ops.jsonl)",
            )
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

    private fun handlePermanentDownloadFailure(
        action: SyncAction.DownloadContent,
        e: PermanentDownloadFailureException,
    ) {
        log.warn(
            "Permanent download failure for {}: {}: {} — quarantining row",
            action.path,
            e.javaClass.simpleName,
            e.message,
        )
        reporter.onWarning("Permanent failure: ${action.path} - ${e.message}")
        logFailure(action, e)
        restoreToPlaceholder(action.path, action.remoteItem)
        val remoteId = action.remoteItem.id
        if (remoteId.isNotEmpty()) {
            db.setDownloadQuarantine(remoteId, Instant.now())
        }
    }

    private fun logUnhydratedFolderSkips(dryRun: Boolean) {
        val skipped = reconciler.lastUnhydratedFolderDeletes
        if (skipped.isEmpty()) return
        for (path in skipped) {
            logSkippedOp(SyncAction.DeleteRemote(path), "unhydrated_folder", dryRun)
        }
        if (dryRun) {
            reporter.onWarning(
                "Dry-run: ${skipped.size} del-remote action(s) for unhydrated folder rows would be skipped " +
                    "(a real run records them in skipped-ops.jsonl)",
            )
        }
        log.warn(
            "skipped {} del-remote action(s) for unhydrated folder rows (see skipped-ops.jsonl)",
            skipped.size,
        )
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
        dryRun: Boolean,
    ) {
        logSkippedOp(actionLabel(action), action.path, reason, dryRun)
    }

    // #401 overload: gather-collision records have no SyncAction — the label and
    // path stand in. Same JSONL shape as the action-based entry.
    private fun logSkippedOp(
        label: String,
        path: String,
        reason: String,
        dryRun: Boolean,
    ) {
        if (dryRun) return // a preview records nothing; the callers report a warning instead
        val logPath = skippedOpsLogPath ?: return
        val line = formatSkippedOpJson(label, path, reason, Instant.now())
        Files.createDirectories(logPath.parent)
        Files.writeString(logPath, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
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
        const val CONSECUTIVE_SYNC_FAILURE_HARD_CAP: Int = 20

        // #560 U2b: the enumeration's and the gather's constants live in
        // RemoteEnumeration and RemoteGather (see there); these names stay for callers.
        const val ENUMERATE_FAILURE_STREAK_KEY: String = RemoteEnumeration.FAILURE_STREAK_KEY
        const val BULK_REAP_ABSOLUTE: Int = RemoteEnumeration.BULK_REAP_ABSOLUTE
        const val BULK_REAP_FRACTION: Double = RemoteEnumeration.BULK_REAP_FRACTION

        // Remote-shrink trust gate. A *full* enumeration is trusted as complete
        // only if it observed at least ENUM_TRUST_MIN_FRACTION of the tracked
        // baseline; a silently-partial provider walk (transient errors swallowed
        // upstream, short pages) can report complete=true while having dropped
        // whole subtrees, and trusting it would let detectMissingAfterFullSync
        // synthesize a mass-delete cascade for every unobserved row. Accounts
        // below ENUM_TRUST_MIN_BASELINE rows are exempt so a legitimately small or
        // near-empty drive is never pinned incomplete by the gate.
        const val ENUM_TRUST_MIN_FRACTION: Double = 0.5
        const val ENUM_TRUST_MIN_BASELINE: Int = 50

        /**
         * Remote-shrink trust gate. Returns an operator-facing warning when a FULL
         * enumeration observed implausibly few live remote items ([observedAlive])
         * versus the tracked [baseline] — the signature of a silently-partial walk
         * masquerading as complete — otherwise null. Accounts below
         * [ENUM_TRUST_MIN_BASELINE] rows are exempt so a legitimately small or
         * near-empty drive is never blocked. Pure function of the two counts so the
         * threshold is unit-testable without a live gather.
         */
        internal fun remoteShrinkWarningOrNull(observedAlive: Int, baseline: Int): String? {
            if (baseline < ENUM_TRUST_MIN_BASELINE) return null
            if (observedAlive >= baseline * ENUM_TRUST_MIN_FRACTION) return null
            return "Remote enumeration looks partial: a full scan observed only $observedAlive " +
                "live remote item(s) vs $baseline previously-tracked " +
                "(< ${(ENUM_TRUST_MIN_FRACTION * 100).toInt()}%). Refusing to reconcile to avoid " +
                "spurious mass uploads/deletes from an incomplete listing. Do NOT use " +
                "--force-delete (it would apply the bad plan). Re-run when the connection is " +
                "healthy; if you really removed most remote files, rebuild with --reset."
        }

        @JvmField
        val RECENT_UPLOAD_REAP_GRACE: java.time.Duration = RemoteGather.RECENT_UPLOAD_REAP_GRACE

        const val SCAN_CHECKPOINT_STALE_HOURS: Long = RemoteGather.SCAN_CHECKPOINT_STALE_HOURS

        const val REMOTE_WAKE_DEBOUNCE_MS: Long = 5_000L

        const val STREAMING_RECONCILE_CHANNEL_CAPACITY: Int = 4

        // #401: the status collision surface (see RemoteGather).
        const val REMOTE_COLLISIONS_KEY: String = RemoteGather.REMOTE_COLLISIONS_KEY
        const val REMOTE_COLLISION_PATHS_KEY: String = RemoteGather.REMOTE_COLLISION_PATHS_KEY
        const val COLLISION_PATHS_STATUS_LIMIT: Int = RemoteGather.COLLISION_PATHS_STATUS_LIMIT

        // The estimate behind the next full enumeration's ETA (see RemoteEnumeration).
        const val FULL_ENUMERATION_ITEMS_KEY: String = RemoteEnumeration.FULL_ENUMERATION_ITEMS_KEY
        const val FULL_ENUMERATION_FOLDERS_KEY: String = RemoteEnumeration.FULL_ENUMERATION_FOLDERS_KEY
        const val FULL_ENUMERATION_MS_KEY: String = RemoteEnumeration.FULL_ENUMERATION_MS_KEY

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

        fun defaultHydrationCacheRoot(): Path =
            System.getenv("XDG_CACHE_HOME")?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".cache")

        fun hydrationCacheRoot(cacheRoot: Path, cacheKey: String): Path =
            cacheRoot
                .resolve("unidrive/hydration")
                .resolve(cacheKey.ifBlank { "default" })
    }
}
