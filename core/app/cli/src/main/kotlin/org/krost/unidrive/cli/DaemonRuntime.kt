package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.hydration.MountEngine
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-profile daemon lifecycle, separated from picocli wiring for testability.
 *
 * Per spec docs/dev/specs/unidrive-daemon-design.md §3.3: the picocli class
 * `DaemonRunCommand` is a thin shell that reads its dependencies off
 * `parent: Main` and constructs this runtime. Tests construct DaemonRuntime
 * directly with their own paths + provider factory — no JVM spawn required.
 *
 * Lifecycle (spec §3.2):
 *   1. Acquire ProcessLock(DAEMON). On contention: render holder info, exit 1.
 *   2. Warn on stale FUSE mounts (best-effort). Never abort.
 *   3. Open StateDatabase. On failure: release lock (in cleanup), rethrow.
 *   4. Call provider.authenticateAndLog(). On failure: release lock, rethrow.
 *      NO socket is bound until this succeeds.
 *   5. Register handlers, then bind IpcServer LAST — a client that can
 *      connect is guaranteed a reply for every documented verb; binding
 *      earlier accepted requests into the unregistered window and hung
 *      their senders. On bind failure: release lock, rethrow.
 *   6. SERVE until close() is called.
 *   7. On close: graceful shutdown bounded by SHUTDOWN_DEADLINE_MS (spec I7).
 *
 * Phase 2 scope: hydration.* verbs + sync.subscribe wired. refresh.run and
 * daemon.status come in Phase 3.
 */
class DaemonRuntime(
    private val profileName: String,
    private val lockFile: Path,
    private val dbPath: Path,
    private val syncRoot: Path,
    private val socketPath: Path,
    private val providerFactory: () -> CloudProvider,
    private val syncPaths: List<String> = emptyList(),
    private val excludePatterns: List<String> = emptyList(),
    // > 0 enables the in-process auto-poll (mount-view-refresh-design.md §5):
    // one periodic enumerate on serveScope, serialised by the sync.enumerate
    // in-flight guard. 0 = off. `daemon run` passes the profile's daemon_poll_seconds
    // (default 60 s, #463) or --poll-interval; 0 stays the default here so tests that
    // construct a runtime do not poll.
    private val pollIntervalMs: Long = 0,
    // #450: hydration cache budget in bytes (profile key hydration_cache_max_bytes); 0 = unlimited.
    private val hydrationCacheMaxBytes: Long = HydrationImpl.DEFAULT_CACHE_MAX_BYTES,
    // #603 (U4): the profile's hosting mode, resolved and gated by `daemon run` before this runtime is
    // constructed. A mount daemon serves the hydration verbs, sync.enumerate and the poller, and routes
    // refresh.run to the always-enumerate path; a mirror daemon refuses the mount verbs (wrong_mode) and
    // keeps refresh.run's legacy reconcile. Reported in daemon.status (mode, capabilities) and the banner.
    private val profileMode: ProfileMode,
    // The profile's config folder (it holds credentials.json and the lock): the IPC token files are
    // written here at every start, before the socket listens (docs/dev/specs/ipc-authentication.md).
    private val ipcTokenDir: Path = lockFile.parent,
    // #655: TTL of the quota snapshot in daemon.status (`quota`), in ms; 0 = the quota field
    // renders from the cache only and never refreshes. `daemon run` passes the profile's
    // quota_refresh_minutes (default 15 min).
    private val quotaRefreshMs: Long = 15 * 60_000L,
    // #655: whether the provider's quota() reports the account's storage plan (ProviderMetadata
    // hasQuota). False — e.g. localfs — keeps the `quota` field ABSENT from daemon.status.
    private val providerHasQuota: Boolean = true,
    // How a start that loses the profile lock ends the process (a seam for tests, which cannot exit).
    private val exitProcess: (Int) -> Unit = { code -> System.exit(code) },
    // #678: where the startup replay and the cache sweep run their blocking per-record walks.
    // Dispatchers.IO in production; a seam for tests, which pin that a stop does not wait for them.
    private val startupIoDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val afterProfileLockAcquired: () -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(DaemonRuntime::class.java)

    /** #655: the last quota snapshot served in daemon.status (`quota`); null = nothing fetched yet. */
    internal data class QuotaSnapshot(
        val usedBytes: Long? = null,
        val totalBytes: Long? = null,
        val remainingBytes: Long? = null,
        val fetchedAtMs: Long? = null,
        val stale: Boolean = true,
        val error: String? = null,
    )

    @Volatile
    private var quotaSnapshot: QuotaSnapshot? = null

    private val quotaRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)


    private var lock: ProcessLock? = null
    private var db: StateDatabase? = null
    private var ipcServer: IpcServer? = null
    private val closeSignal = CompletableDeferred<Unit>()

    // Counted down once cleanup() has run. Only armed after the lock is held: a start() that lost
    // the lock race (or never ran) has nothing to wait for, and a shutdown hook must not stall the
    // System.exit that path triggers.
    private val cleanupDone = java.util.concurrent.CountDownLatch(1)

    @Volatile private var lifecycleActive = false
    private var startedAtMs: Long = 0

    suspend fun start() {
        Files.createDirectories(lockFile.parent)
        val acquiredLock = ProcessLock(lockFile)
        if (!acquiredLock.tryLock(ProcessLock.Mode.DAEMON)) {
            renderLockContentionAndExit(acquiredLock)
            return
        }
        lock = acquiredLock
        lifecycleActive = true

        try {
            afterProfileLockAcquired()

            // 2. Stale-mount warn (spec §3.3) — best-effort, never aborts.
            val staleMounts = StaleMountDetector.detectStaleFuseUnidriveMounts()
            if (staleMounts.isNotEmpty()) {
                System.err.println(
                    "WARNING: detected ${staleMounts.size} stale unidrive FUSE mount(s) " +
                        "(likely from a kill -9'd `unidrive mount` parent or prior daemon): " +
                        staleMounts.joinToString(),
                )
                // Emit one ready-to-run command per detected mount (real path,
                // not a `<path>` placeholder) so the operator can copy-paste directly.
                System.err.println("These mounts no longer serve data. Clean up with:")
                staleMounts.forEach { System.err.println("  fusermount3 -u $it") }
            }

            db = StateDatabase(dbPath).also { it.initialize() }

            // #655: seed the quota snapshot from the sync_state cache (the same tuple
            // `quota` persists), so the very first status already shows the last known
            // values flagged stale instead of nulls.
            quotaSnapshot = readCachedQuotaSnapshot(db!!)

            // 4. Authenticate. NO socket is bound until this succeeds.
            val provider = providerFactory()
            try {
                provider.authenticateAndLog()
            } catch (e: Exception) {
                System.err.println(
                    "unidrive daemon: authentication failed for profile " +
                        "'$profileName': ${e.message}",
                )
                throw e
            }

            // New IPC tokens for this start, written before the socket exists (only the lock holder
            // gets here, so a refused second start never rotates a running daemon's tokens). A refusal
            // has printed its one line; start() rethrows it and `daemon run` exits 78.
            val ipcAuth = IpcAuth.issueOrReport(ipcTokenDir, profileName, BuildInfo.versionString())

            Files.createDirectories(socketPath.parent)
            val server = IpcServer(socketPath, auth = ipcAuth)
            ipcServer = server

            // Use supervisorScope-with-explicit-cancel so the SERVE block returns
            // promptly on close(). A plain coroutineScope would wait for the
            // hydration.events.collect launch (infinite flow) and any handler
            // children spawned by IpcServer/HydrationIpcHandler to complete —
            // they don't. Solution: launch the long-lived children under a
            // dedicated SupervisorJob that we cancel before the scope exits.
            val serveJob = kotlinx.coroutines.SupervisorJob()
            val serveScope = kotlinx.coroutines.CoroutineScope(
                kotlin.coroutines.coroutineContext + serveJob,
            )
            var hydrationForShutdown: HydrationImpl? = null
            // Coroutine-debug probes (gkrost/unidrive#613 ask 1): with UNIDRIVE_COROUTINE_DEBUG=1 the
            // daemon captures suspension stacks at start and dumps every live coroutine when a
            // coroutine-dump.trigger file appears in the profile folder — a hung upload becomes one
            // stack trace instead of a silent wait. Off by default; see CoroutineDebug.
            if (CoroutineDebug.enabled()) {
                CoroutineDebug.install()
                CoroutineDebug.watchForDumpRequests(ipcTokenDir, serveScope)
            }
            try {
                // cacheKey = profileName keeps the daemon's hydration cache
                // subtree per-account and consistent with MountCommand's
                // co-daemon --cache root (also profile.name), so the FUSE
                // backing store, eviction, and crash-recovery scanner all
                // address the same files.
                // viewInvalidationSink is a late-binding lambda: hydrationIpc is not yet
                // constructed when engine is built, but the lambda captures the `var` slot
                // and is only invoked after enumerateRemoteIntoState returns (by which time
                // hydrationIpc is fully wired). dispatchEvent fans the event to all subscribers
                // without going through the HydrationImpl flow, which is intentional: the
                // invalidation event originates outside the per-path hydration lifecycle.
                var hydrationIpcRef: HydrationIpcHandler? = null
                // #301: same late-binding shape as hydrationIpcRef — the engine's
                // enumerate-reap asks the hydration layer whether an upload of a path
                // is queued or in flight before it evicts that path's cache file.
                var hydrationRef: HydrationImpl? = null
                val engine = SyncEngine(
                    provider, db!!, syncRoot = syncRoot, cacheKey = profileName, syncPaths = syncPaths,
                    standingScope = syncPaths, excludePatterns = excludePatterns,
                    uploadInFlight = { path -> hydrationRef?.hasUploadSlot(path) ?: false },
                    viewInvalidationSink = { changedPaths, full, moved ->
                        val cap = HydrationEvent.VIEW_INVALIDATED_PATH_CAP
                        val event = if (full || changedPaths.size > cap) {
                            HydrationEvent.ViewInvalidated(paths = emptyList(), full = true)
                        } else {
                            // #595: the renames ride along as an additive hint; a consumer
                            // that ignores them still sees both ends in `paths`.
                            HydrationEvent.ViewInvalidated(
                                paths = changedPaths.toList(),
                                moved = moved.map { HydrationEvent.ViewInvalidated.Moved(it.from, it.to) },
                            )
                        }
                        hydrationIpcRef?.dispatchEvent(event)
                    },
                )
                // #560 U3: the mount operations (hydration, uploads, remote folder/delete/rename and
                // the enumeration) run on MountEngine, over this engine's shared core (guard, gather,
                // enumeration: one of each per daemon). The engine itself stays for the refresh.run
                // fallback (RefreshRpcHandler, U4). See MountEngine for who owns what.
                val mount = MountEngine.over(engine)
                val wrongModeReply = """{"ok":false,"error":"wrong_mode"}"""
                val mountMode = profileMode == ProfileMode.MOUNT
                // #560 U6 (cutover): the hydration runtime exists only on a mount profile. A mirror
                // daemon constructs no hydration layer and keeps no cache — its local bytes live in
                // the sync root its reconcile owns, and every hydration verb gets wrong_mode below.
                var hydration: HydrationImpl? = null
                var pollerRef: EnumeratePoller? = null
                if (mountMode) {
                    // Upload and replay jobs outlive IPC handlers but not this daemon. Keep their
                    // scope separate so shutdown can cancel/join it without cancelling the serve scope.
                    val hydrationUploadScope = kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
                    )
                    val hydrationImpl = HydrationImpl(
                        mount,
                        db!!,
                        recoveryUploadScope = hydrationUploadScope,
                        cacheMaxBytes = hydrationCacheMaxBytes,
                    )
                    hydration = hydrationImpl
                    hydrationForShutdown = hydrationImpl
                    hydrationRef = hydrationImpl
                    // #450: what a stopped daemon left in the hydration cache (staging temp files, the
                    // copies of synced files read through the mount) is trimmed to the budget at start.
                    serveScope.launch {
                        // #678: blocking walk; off the event loop so it cannot delay a stop.
                        withContext(startupIoDispatcher) {
                            try {
                                hydrationImpl.sweepCache()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("hydration cache sweep at start failed", e)
                            }
                        }
                    }
                    val hydrationIpcHandler = HydrationIpcHandler(hydrationImpl)
                    hydrationIpcRef = hydrationIpcHandler

                    // #463: a hydration or upload that succeeded is (nearly always) a provider round trip
                    // that worked, so it cuts the poller's back-off short. Even when it was not, the poller
                    // polls at most once per plain interval. Late-bound: the poller is built further down.
                    serveScope.launch {
                        hydrationImpl.events.collect {
                            hydrationIpcHandler.dispatchEvent(it)
                            if (it is HydrationEvent.Hydrated || (it is HydrationEvent.Completed && it.ok)) pollerRef?.providerReachable()
                        }
                    }
                }

                // sync.enumerate handler (mount-view-refresh-design.md §4.1): one-way
                // remote→state.db refresh for mount view consumers. Constructed before the
                // hydration verbs so the subscribe-triggered enumerate (below) can reuse its
                // shared in-flight guard.
                val enumerateHandler = EnumerateRpcHandler(mount, serveScope, server::emit)

                // Reactive remote-change detection: when the FUSE co-daemon issues
                // hydration.subscribe on mount, run ONE guarded enumerate after the reply.
                // The enumerate pulls remote changes into state.db and, when anything changed,
                // the engine's viewInvalidationSink pushes view.invalidated back over this same
                // subscribe stream — so a freshly-mounted view reflects remote renames/deletes
                // without a manual `refresh`, without waiting for the next poll (#463). Serialised through
                // the same in-flight guard as sync.enumerate/the poller, so a concurrent
                // enumeration is skipped rather than overlapped.
                // #603 (U4): the mount verbs exist only on a mount profile. A mirror daemon answers every
                // hydration verb and sync.enumerate with wrong_mode — the client's command gate refuses
                // before this, but nothing here may serve mount semantics on a mirror profile either.
                // The mount wiring below (handlers, subscribe-hook, upload replay, poller)
                // is mount-only; a mirror daemon keeps sync.subscribe, refresh.run (legacy reconcile)
                // and daemon.status.
                for (verb in HydrationIpcHandler.VERBS) {
                    if (!mountMode) {
                        server.registerHandler(verb) { _, _ -> wrongModeReply }
                        continue
                    }
                    // The hydration layer is built above whenever mountMode holds.
                    val ipc = requireNotNull(hydrationIpcRef) { "mount mode without a hydration layer" }
                    server.registerHandler(verb) { connId, json ->
                        val reply = ipc.handle(connectionId = connId, jsonRequest = json)
                        // A READ-scope subscribe (an observer such as a status UI) never starts the enumerate:
                        // observing must not scan the remote. #657
                        if (verb == "hydration.subscribe" && reply.contains("\"ok\":true") &&
                            subscribeEnumerates(server.authEnabled, server.scopeOf(connId))
                        ) {
                            server.scheduleAfterReply(connId) {
                                runCatching { enumerateHandler.runGuarded(reset = false) }
                                    .onFailure { log.warn("enumerate-on-subscribe failed", it) }
                            }
                        }
                        reply
                    }
                }
                if (!mountMode) {
                    server.registerHandler("sync.enumerate") { _, _ -> wrongModeReply }
                }
                if (mountMode) {
                    server.registerConnectionCloseListener { connId ->
                        hydration?.onConnectionClosed(connId)
                    }
                    // A mount client's connections are never closed for being idle: its open handles and
                    // its event subscription live on them, and refresh.run routing counts them.
                    server.registerIdleExemptVerbs(HydrationIpcHandler.VERBS)
                    hydrationIpcRef?.start(serveScope, server::writeToConnection)
                    server.registerConnectionCloseListener { connId ->
                        hydrationIpcRef?.onSubscriberDisconnect(connId)
                    }
                }

                // Engine-side upload recovery: re-enqueue rows written through the
                // mount whose upload never landed (still queued or failed when the
                // daemon last stopped). Runs before any client connects, so a
                // restart alone drains the backlog; the uploads go through the
                // same per-path serialization and daemon-wide transfer budget as
                // client-submitted ones. The co-daemon's recovery-<n> scanner
                // stays as the client-side complement for cache files the row
                // scan cannot see.
                //
                // #603 (U4): mount-only. A mirror daemon serves no hydration uploads to replay.
                if (mountMode) {
                    // The hydration layer is built above whenever mountMode holds.
                    val h = requireNotNull(hydration) { "mount mode without a hydration layer" }
                    serveScope.launch {
                        // #678: the replay walks every record with blocking file-system work and no
                        // suspension point; on the event loop it starved closeSignal.await(), so a
                        // stop waited for the whole backlog. Off the loop, and with the walk checking
                        // cancellation between records, a stop lands within one record.
                        withContext(startupIoDispatcher) {
                            try {
                                val n = h.replayPendingUploads()
                                if (n > 0) log.info("replayed {} pending upload(s) from state.db", n)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("pending-upload replay failed", e)
                            }
                        }
                    }
                }

                // sync.subscribe — symmetric to SyncCommand's wiring.
                server.registerHandler("sync.subscribe") { connId, _ ->
                    server.scheduleAfterReply(connId) {
                        server.flushStateDumpTo(connId)
                        server.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }
                server.registerConnectionCloseListener { connId ->
                    server.unregisterSyncSubscriber(connId)
                }

                // refresh.run verb (spec §4.2). Pass serveScope so refresh
                // jobs are cancelled when the daemon shuts down. Pass db so
                // the F9 `reset` parameter can clear it (keeping the rows that
                // still await upload) before re-enumeration.
                // #603 (U4): a mount profile's refresh.run ALWAYS takes the one-way enumerate path —
                // with or without a connected client. The enumerate is remote→state.db only: a reset
                // clears the delta cursor (the enumeration state), pending local intent stays, the last
                // good rows survive a failed or incomplete gather, and nothing is published as a
                // deletion before an authoritative completion. A mirror profile keeps the legacy
                // reconcile (its mounted flag stays false; no hydration subscriber exists there).
                val refreshHandler =
                    RefreshRpcHandler(
                        server,
                        engine,
                        db!!,
                        serveScope,
                        mountClientConnected = { profileMode == ProfileMode.MOUNT },
                        // A reset keeps the rows of uploads still under way (same hook the enumeration's reap uses).
                        uploadInFlight = { path -> hydration?.hasUploadSlot(path) ?: false },
                    )
                server.registerHandler("refresh.run") { connId, json ->
                    refreshHandler.handle(connId, json)
                }

                // sync.enumerate verb (mount-view-refresh-design.md §4.1), mount-only (#603: a mirror
                // daemon registered its wrong_mode refusal above). The handler is constructed above
                // (shared with the subscribe-triggered enumerate); here we expose it as an explicit verb
                // whose terminal events fan out to sync.subscribe listeners via server.emit.
                if (mountMode) {
                    server.registerHandler("sync.enumerate") { connId, json ->
                        enumerateHandler.handle(connId, json)
                    }
                }

                // Remote-change discovery (mount-view-refresh-design.md §5, #463), on by default in
                // `daemon run`: the notification channel is not reliable, and a mount profile has no
                // other way to learn what changed in the cloud. The enumerate path is cheap on a
                // no-change incremental delta. The first poll runs at start (catch-up after a stop or
                // an outage). Shares the sync.enumerate in-flight guard and skips while a refresh runs,
                // so it never overlaps either. Launched on serveScope so it cancels with the daemon at
                // shutdown. The backoff starts escalated when the previous run ended in enumerate
                // failures (#517 R3): a restart into a known-bad remote doesn't re-run the doomed
                // cycle at full cadence. Mount-only (#603): a mirror profile's discovery is `sync`'s
                // reconcile, not the mount-view poll.
                if (mountMode) {
                    pollerRef =
                        EnumeratePoller(
                            enumerateHandler,
                            pollIntervalMs,
                            serveScope,
                            onNextAttempt = mount.enumerationTracker::nextAttemptAt,
                            consecutiveFailuresAtStart =
                                db!!.getSyncState(SyncEngine.ENUMERATE_FAILURE_STREAK_KEY)?.toIntOrNull() ?: 0,
                            isBusy = { refreshHandler.isInFlight() },
                        ).also { it.start() }
                }

                // daemon.status verb (spec §4.3). protocol_version is the
                // additive cross-repo handshake field (IPC_PROTOCOL_VERSION):
                // co-clients warn/refuse on mismatch instead of failing on a
                // missing field mid-operation. provider/authenticated are the
                // account/auth state a status UI needs; the SPI carries no account
                // identity, so the provider type + name is what we can report
                // truthfully, and `authenticated` only says that credentials are
                // loaded (not that they are valid). enumeration is the progress of
                // the remote enumeration (additive, object always present). Before a
                // connection authenticates, IpcAuth answers daemon.status itself with
                // the minimal reply; this handler serves authenticated connections.
                server.registerHandler("daemon.status") { _, _ ->
                    val uptimeMs = System.currentTimeMillis() - startedAtMs
                    val clientCount = server.clientCount
                    val refreshInFlight = refreshHandler.isInFlight()
                    val refreshJobId = refreshHandler.inFlightJobId()
                    val jobIdJson = if (refreshJobId != null) "\"$refreshJobId\"" else "null"
                    // sync_paths: the effective scope (sync_path entries the daemon was started
                    // with; empty = whole drive). Additive field, read-only over IPC.
                    val syncPathsJson = kotlinx.serialization.json.JsonArray(
                        syncPaths.map { kotlinx.serialization.json.JsonPrimitive(it) },
                    ).toString()
                    val providerJson = kotlinx.serialization.json.JsonPrimitive(provider.id).toString()
                    val providerNameJson = kotlinx.serialization.json.JsonPrimitive(provider.displayName).toString()
                    val enumerationStatus = mount.enumerationStatus()
                    val enumerationJson = enumerationStatus.toJson().toString()
                    val engineVersionJson = kotlinx.serialization.json.JsonPrimitive(BuildInfo.versionString()).toString()
                    // uploads / cache / provider_health (#658): the engine's own account of whether everything
                    // is uploaded, how much the hydration cache holds and when the provider last answered.
                    // uploads and cache exist only where a hydration layer does (a mount profile): a mirror
                    // daemon leaves them out, and so does an upload queue that has never been read, because a
                    // front-end must never read an unknown as "nothing pending". Neither read blocks this reply
                    // behind state.db or the cache directory: the previous reading is served (uploadHealthSnapshot),
                    // and the cache walk runs off this request (bytes null until the first).
                    val healthJson = hydration?.let { h ->
                        h.requestCacheMeasurement()
                        val uploads = h.uploadHealthSnapshot()
                        (uploads?.let { ",\"uploads\":${it.toJson()}" } ?: "") + ",\"cache\":${h.cacheHealth().toJson()}"
                    } ?: ""
                    val providerHealth = providerHealthJson(
                        lastProviderContactMs(enumerationStatus.lastSuccessAtMs, mount.lastProviderContactAtMs),
                    )
                    // mode + capabilities (#603 U4): what this daemon serves, so a client can check the
                    // hosting contract before it sends a verb the profile refuses (additive, read-only).
                    val modeJson = kotlinx.serialization.json.JsonPrimitive(profileMode.wireName).toString()
                    val capabilitiesJson = kotlinx.serialization.json.JsonArray(
                        profileMode.capabilities.map { kotlinx.serialization.json.JsonPrimitive(it) },
                    ).toString()
                    // quota (#655): the account's storage plan snapshot. Absent entirely for
                    // providers without an account quota (ProviderMetadata hasQuota=false —
                    // localfs probes machine storage). A client-connected status request is
                    // also the refresh trigger: a fetch runs single-flight when the TTL has
                    // expired; no client asking means no provider call. Failure keeps the
                    // last value with stale=true and the error named; unknowns are null —
                    // never zero, never a division by a missing total.
                    if (providerHasQuota) maybeTriggerQuotaRefresh(clientCount, serveScope)
                    val quotaJson =
                        if (!providerHasQuota) {
                            null
                        } else {
                            val snap = quotaSnapshot ?: QuotaSnapshot()
                            val errorJson = snap.error?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "null"
                            """{"used_bytes":${snap.usedBytes ?: "null"},""" +
                                """"total_bytes":${snap.totalBytes ?: "null"},""" +
                                """"fetched_at_ms":${snap.fetchedAtMs ?: "null"},"stale":${snap.stale},"error":$errorJson}"""
                        }
                    // poll_interval_ms (#463): the effective interval of the remote poll (0 = off). The
                    // next attempt after a failure is enumeration.next_attempt_at_ms (additive, read-only).
                    // engine_version (#554): the build a co-client is talking to — behaviour fixes do
                    // not move IPC_PROTOCOL_VERSION, so this is the age signal a client gates its
                    // engine minimum on (additive, read-only). #574: a dev build carries the commit id
                    // as semver build metadata (VERSION+COMMIT[.dirty]), so the release part orders.
                    """{"ok":true,"protocol_version":$IPC_PROTOCOL_VERSION,"engine_version":$engineVersionJson,"mode":$modeJson,"capabilities":$capabilitiesJson,"uptime_ms":$uptimeMs,"clients_connected":$clientCount,"refresh_in_flight":$refreshInFlight,"refresh_job_id":$jobIdJson,"sync_paths":$syncPathsJson,"provider":$providerJson,"provider_name":$providerNameJson,"authenticated":${provider.isAuthenticated},"enumeration":$enumerationJson,"poll_interval_ms":$pollIntervalMs""" +
                        (quotaJson?.let { ",\"quota\":$it" } ?: "") +
                        healthJson +
                        ",\"provider_health\":$providerHealth" +
                        "}"
                }

                // daemon.shutdown verb: graceful stop over IPC, signal-free and identical on every
                // OS (Windows has no SIGTERM; Process.destroy() there is TerminateProcess, which
                // skips the shutdown path entirely). Acks first, then runs the same path as a
                // clean exit: the serve scope is cancelled, then cleanup() closes the IPC server,
                // the state database and the process lock. The ack means "accepted"; completion
                // is observable as the connection closing and, finally, the process exiting.
                server.registerHandler("daemon.shutdown") { connId, _ ->
                    server.scheduleAfterReply(connId) { close() }
                    """{"ok":true}"""
                }

                // Bind LAST, after every handler above is registered. The socket
                // file appearing is what clients poll for before sending their
                // first verb — starting the server any earlier opens a window in
                // which a request is accepted and then silently dropped ("no
                // handler"), hanging the client that sent it. With the bind here,
                // a connect that succeeds is guaranteed a reply for every
                // documented verb; authentication, engine construction and handler
                // registration all complete before the socket exists.
                server.start(serveScope)
                startedAtMs = System.currentTimeMillis()

                System.err.println(
                    "daemon ready, pid ${ProcessHandle.current().pid()}, socket $socketPath",
                )

                closeSignal.await()
                log.info("daemon: shutting down")
            } finally {
                // Stop accepting hydration verbs and cancel in-flight request handlers first. Upload
                // workers have their own scope, so join them explicitly before cleanup closes state.db.
                server.close()
                ipcServer = null
                serveJob.cancelAndJoin()
                hydrationForShutdown?.shutdownUploads()
            }
        } catch (e: Exception) {
            // An IPC startup refusal is already reported as its one line.
            if (e !is IpcAuth.StartupRefused) log.error("daemon: lifecycle error", e)
            throw e
        } finally {
            cleanup()
            cleanupDone.countDown()
        }
    }

    fun close() {
        closeSignal.complete(Unit)
    }

    /**
     * Request shutdown and block until [start]'s cleanup (IpcServer close, StateDatabase close,
     * lock release) has finished, bounded by [timeoutMs] (spec I7). For the JVM shutdown hook:
     * the JVM halts as soon as every hook returns, so a hook that only called [close] raced the
     * main thread's cleanup and could leave a stale `.lock.pid` and socket behind.
     *
     * Returns true when cleanup completed (or there was nothing to clean up), false on timeout.
     */
    fun shutdownAndWait(timeoutMs: Long = SHUTDOWN_DEADLINE_MS): Boolean {
        close()
        if (!lifecycleActive) return true
        return try {
            cleanupDone.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun renderLockContentionAndExit(lock: ProcessLock) {
        val holder = lock.readLiveHolderInfo()
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profileName'"
            holder?.mode == ProcessLock.Mode.DAEMON ->
                "Another `unidrive daemon` already serves profile '$profileName'"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
        exitProcess(1)
    }

    private fun cleanup() {
        runCatching { ipcServer?.close() }
        ipcServer = null
        runCatching { db?.close() }
        db = null
        runCatching { lock?.unlock() }
        lock = null
    }

    // ── #655: the quota snapshot ─────────────────────────────────────────────

    /**
     * Triggers a single-flight quota fetch when a client is asking (status request), the TTL
     * has expired (or nothing was fetched yet), and the facility is on. Never blocks the
     * reply: the fetch runs on serveScope and the NEXT status serves the fresh snapshot.
     * The reply's own `stale` flag is recomputed from the TTL, so a client sees staleness
     * immediately even before the fetch lands.
     */
    private fun maybeTriggerQuotaRefresh(
        clientCount: Int,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        if (quotaRefreshMs <= 0 || clientCount <= 0) return
        val snap = quotaSnapshot
        val expired = snap == null || snap.fetchedAtMs == null ||
            System.currentTimeMillis() - snap.fetchedAtMs >= quotaRefreshMs
        if (snap != null && !expired && !snap.stale) return
        if (snap != null && !snap.stale && expired && !quotaRefreshInFlight.get()) {
            // Mark the served snapshot stale at once: a client must not read a TTL-expired
            // value as fresh while the refetch is in flight.
            quotaSnapshot = snap.copy(stale = true)
        }
        if (!quotaRefreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val provider = providerFactory()
                try {
                    runCatching {
                        provider.authenticateAndLog()
                        val quota = provider.quota()
                        val now = System.currentTimeMillis()
                        quotaSnapshot =
                            QuotaSnapshot(
                                usedBytes = quota.used,
                                totalBytes = quota.total,
                                remainingBytes = quota.remaining,
                                fetchedAtMs = now,
                                stale = false,
                                error = null,
                            )
                        persistQuotaSnapshot(quotaSnapshot!!)
                    }.onFailure { e ->
                        // Keep the last values; flag them stale and name the failure.
                        quotaSnapshot = (quotaSnapshot ?: QuotaSnapshot()).copy(
                            stale = true,
                            error = "${e.javaClass.simpleName}: ${e.message ?: "quota fetch failed"}",
                        )
                        log.warn("quota refresh failed; serving the last snapshot flagged stale: {}", e.message)
                    }
                } finally {
                    provider.close()
                }
            } catch (e: Exception) {
                log.warn("quota refresh crashed: {}", e.message)
            } finally {
                quotaRefreshInFlight.set(false)
            }
        }
    }

    /** Reads the cached quota tuple `quota` persists (same keys, one shared cache). */
    private fun readCachedQuotaSnapshot(db: StateDatabase): QuotaSnapshot? {
        val used = db.getSyncState("quota_used")?.toLongOrNull() ?: return null
        val total = db.getSyncState("quota_total")?.toLongOrNull() ?: return null
        val remaining = db.getSyncState("quota_remaining")?.toLongOrNull()
        val fetchedAt = db.getSyncState("quota_fetched_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        val age = System.currentTimeMillis() - fetchedAt.toEpochMilli()
        return QuotaSnapshot(
            usedBytes = used,
            totalBytes = total,
            remainingBytes = remaining,
            fetchedAtMs = fetchedAt.toEpochMilli(),
            stale = quotaRefreshMs <= 0 || age >= quotaRefreshMs,
            error = null,
        )
    }

    private fun persistQuotaSnapshot(snap: QuotaSnapshot) {
        runCatching {
            val db = StateDatabase(dbPath)
            try {
                db.initialize()
                db.setSyncState("quota_used", snap.usedBytes?.toString() ?: return)
                db.setSyncState("quota_total", snap.totalBytes?.toString() ?: return)
                snap.remainingBytes?.let { db.setSyncState("quota_remaining", it.toString()) }
                db.setSyncState("quota_fetched_at", Instant.ofEpochMilli(snap.fetchedAtMs ?: return).toString())
            } finally {
                db.close()
            }
        }
    }
    companion object {
        const val SHUTDOWN_DEADLINE_MS: Long = 10_000

        // Cross-repo IPC wire-protocol version, surfaced as the additive
        // protocol_version field in the daemon.status reply so co-clients
        // (Rust FUSE crate, C# CfAPI client) can warn/refuse on mismatch
        // instead of failing on a missing field mid-operation. Bump ONLY on
        // a breaking wire change; additive fields do not bump it. The golden
        // corpus under src/test/resources/ipc-contract/ pins the current
        // shape (IpcContractCorpusTest). Version 2 authenticates every
        // connection (IpcAuth, docs/dev/specs/ipc-authentication.md).
        const val IPC_PROTOCOL_VERSION: Int = IpcAuth.PROTOCOL_VERSION

        /**
         * Whether a successful `hydration.subscribe` is followed by the one guarded enumerate: only for a
         * full-scope connection, or when the server does not authenticate at all (the protocol-1 fallback,
         * until the handshake is mandatory everywhere). With authentication on, anything but `full`, an
         * unknown scope included, does not enumerate.
         */
        internal fun subscribeEnumerates(
            authEnabled: Boolean,
            scope: String?,
        ): Boolean = !authEnabled || scope == IpcAuth.Scope.FULL.wire
    }
}
