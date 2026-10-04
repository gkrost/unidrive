package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
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
    // in-flight guard. 0 = off (strictly reactive, the daemon default).
    private val pollIntervalMs: Long = 0,
    // #450: hydration cache budget in bytes (profile key hydration_cache_max_bytes); 0 = unlimited.
    private val hydrationCacheMaxBytes: Long = HydrationImpl.DEFAULT_CACHE_MAX_BYTES,
    // #504: > 0 = rescan the sync root for files that arrived out of band (a pass at start, then every
    // this many ms), uploading them; 0 = off. Profile key sync_root_rescan_minutes (default 10 min).
    private val syncRootRescanIntervalMs: Long = 0,
) {
    private val log = LoggerFactory.getLogger(DaemonRuntime::class.java)

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

            Files.createDirectories(socketPath.parent)
            val server = IpcServer(socketPath)
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
                    viewInvalidationSink = { changedPaths, full ->
                        val cap = HydrationEvent.VIEW_INVALIDATED_PATH_CAP
                        val event = if (full || changedPaths.size > cap) {
                            HydrationEvent.ViewInvalidated(paths = emptyList(), full = true)
                        } else {
                            HydrationEvent.ViewInvalidated(paths = changedPaths.toList())
                        }
                        hydrationIpcRef?.dispatchEvent(event)
                    },
                )
                val hydration = HydrationImpl(engine, db!!, cacheMaxBytes = hydrationCacheMaxBytes)
                hydrationRef = hydration
                // #450: what a stopped daemon left in the hydration cache (staging temp files, the
                // copies of synced files read through the mount) is trimmed to the budget at start.
                serveScope.launch {
                    runCatching { hydration.sweepCache() }
                        .onFailure { log.warn("hydration cache sweep at start failed", it) }
                }
                val hydrationIpc = HydrationIpcHandler(hydration)
                hydrationIpcRef = hydrationIpc

                // sync.enumerate handler (mount-view-refresh-design.md §4.1): one-way
                // remote→state.db refresh for mount view consumers. Constructed before the
                // hydration verbs so the subscribe-triggered enumerate (below) can reuse its
                // shared in-flight guard.
                val enumerateHandler = EnumerateRpcHandler(engine, serveScope, server::emit)

                // Reactive remote-change detection: when the FUSE co-daemon issues
                // hydration.subscribe on mount, run ONE guarded enumerate after the reply.
                // The enumerate pulls remote changes into state.db and, when anything changed,
                // the engine's viewInvalidationSink pushes view.invalidated back over this same
                // subscribe stream — so a freshly-mounted view reflects remote renames/deletes
                // without a manual `refresh`. This stays strictly reactive (triggered by the
                // subscribe verb, daemon-design G3): no always-on poll loop. Serialised through
                // the same in-flight guard as sync.enumerate/the poller, so a concurrent
                // enumeration is skipped rather than overlapped.
                for (verb in HydrationIpcHandler.VERBS) {
                    server.registerHandler(verb) { connId, json ->
                        val reply = hydrationIpc.handle(connectionId = connId, jsonRequest = json)
                        if (verb == "hydration.subscribe" && reply.contains("\"ok\":true")) {
                            server.scheduleAfterReply(connId) {
                                runCatching { enumerateHandler.runGuarded(reset = false) }
                                    .onFailure { log.warn("enumerate-on-subscribe failed", it) }
                            }
                        }
                        reply
                    }
                }
                server.registerConnectionCloseListener { connId ->
                    hydration.onConnectionClosed(connId)
                }
                hydrationIpc.start(serveScope, server::writeToConnection)
                server.registerConnectionCloseListener { connId ->
                    hydrationIpc.onSubscriberDisconnect(connId)
                }
                serveScope.launch { hydration.events.collect { hydrationIpc.dispatchEvent(it) } }

                // Engine-side upload recovery: re-enqueue rows written through the
                // mount whose upload never landed (still queued or failed when the
                // daemon last stopped). Runs before any client connects, so a
                // restart alone drains the backlog; the uploads go through the
                // same per-path serialization and daemon-wide transfer budget as
                // client-submitted ones. The co-daemon's recovery-<n> scanner
                // stays as the client-side complement for cache files the row
                // scan cannot see.
                serveScope.launch {
                    runCatching { hydration.replayPendingUploads() }
                        .onSuccess { if (it > 0) log.info("replayed {} pending upload(s) from state.db", it) }
                        .onFailure { log.warn("pending-upload replay failed", it) }
                    // #504: the sync root is nobody's inbox but the engine's: files that reach it
                    // other than through the mount (copied in, dropped while the daemon was down,
                    // restored from a backup) are uploaded by an upload-only rescan, once now (after
                    // the replay above, so a mount write's own queued upload goes first) and then on
                    // a timer. Never downloads or deletes; see SyncEngine.rescanSyncRootForUpload.
                    SyncRootRescanner(syncRootRescanIntervalMs) {
                        val r = engine.rescanSyncRootForUpload()
                        if (r.uploaded > 0 || r.foldersCreated > 0) {
                            log.info("sync root rescan: {} file(s) uploaded, {} folder(s) created", r.uploaded, r.foldersCreated)
                        }
                    }.run()
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
                // the F9 `reset` parameter can call db.resetAll() before
                // re-enumeration.
                // Route refresh to the one-way enumerate path when a mount client (the FUSE
                // co-daemon) is serving this profile's view — that profile's sync_root is
                // empty/unset, so the legacy reconcile would (correctly) abort on the deletion
                // guards. Detect the mount via a live connection that has issued a hydration verb;
                // hasSubscribers() is always false today because the co-daemon doesn't yet
                // subscribe on mount (that's the Phase-3 follow-up — design §4/§6).
                val refreshHandler =
                    RefreshRpcHandler(
                        server,
                        engine,
                        db!!,
                        serveScope,
                        mountClientConnected = { hydrationIpc.hasActiveMountConnection() },
                    )
                server.registerHandler("refresh.run") { connId, json ->
                    refreshHandler.handle(connId, json)
                }

                // sync.enumerate verb (mount-view-refresh-design.md §4.1). The handler is
                // constructed above (shared with the subscribe-triggered enumerate); here we
                // expose it as an explicit verb whose terminal events fan out to sync.subscribe
                // listeners via server.emit.
                server.registerHandler("sync.enumerate") { connId, json ->
                    enumerateHandler.handle(connId, json)
                }

                // Optional auto-poll (mount-view-refresh-design.md §5). Polls
                // unconditionally when enabled — the enumerate path is cheap on a
                // no-change incremental delta and a profile served by the daemon is
                // assumed to back a mount view. Shares the sync.enumerate in-flight
                // guard (never overlaps a manual refresh/enumerate). Launched on
                // serveScope so it cancels with the daemon at shutdown. The backoff
                // starts escalated when the previous run ended in enumerate
                // failures (#517 R3): a restart into a known-bad remote doesn't
                // re-run the doomed cycle at full cadence.
                EnumeratePoller(
                    enumerateHandler,
                    pollIntervalMs,
                    serveScope,
                    onNextAttempt = engine.enumerationTracker::nextAttemptAt,
                    consecutiveFailuresAtStart =
                        db!!.getSyncState(SyncEngine.ENUMERATE_FAILURE_STREAK_KEY)?.toIntOrNull() ?: 0,
                ).start()

                // daemon.status verb (spec §4.3). protocol_version is the
                // additive cross-repo handshake field (IPC_PROTOCOL_VERSION):
                // co-clients warn/refuse on mismatch instead of failing on a
                // missing field mid-operation. provider/authenticated are the
                // account/auth state a status UI needs; the SPI carries no account
                // identity, so the provider type + name is what we can report
                // truthfully, and `authenticated` only says that credentials are
                // loaded (not that they are valid). enumeration is the progress of
                // the remote enumeration (additive, object always present).
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
                    val enumerationJson = engine.enumerationStatus().toJson().toString()
                    // engine_version (#554): the build a co-client is talking to — behaviour fixes do
                    // not move IPC_PROTOCOL_VERSION, so this is the age signal a client gates its
                    // engine minimum on (additive, read-only).
                    """{"ok":true,"protocol_version":$IPC_PROTOCOL_VERSION,"engine_version":"${BuildInfo.versionString()}","uptime_ms":$uptimeMs,"clients_connected":$clientCount,"refresh_in_flight":$refreshInFlight,"refresh_job_id":$jobIdJson,"sync_paths":$syncPathsJson,"provider":$providerJson,"provider_name":$providerNameJson,"authenticated":${provider.isAuthenticated},"enumeration":$enumerationJson}"""
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
                // Cancel all serve-scope children (hydration.events collector,
                // IpcServer accept loop, HydrationIpcHandler subscriber writers,
                // etc.) so this method can return. State.db + lock cleanup is
                // handled by the outer finally calling cleanup().
                serveJob.cancel()
            }
        } catch (e: Exception) {
            log.error("daemon: lifecycle error", e)
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
        val holder = lock.readHolderInfo()
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
        System.exit(1)
    }

    private fun cleanup() {
        runCatching { ipcServer?.close() }
        ipcServer = null
        runCatching { db?.close() }
        db = null
        runCatching { lock?.unlock() }
        lock = null
    }

    companion object {
        const val SHUTDOWN_DEADLINE_MS: Long = 10_000

        // Cross-repo IPC wire-protocol version, surfaced as the additive
        // protocol_version field in the daemon.status reply so co-clients
        // (Rust FUSE crate, C# CfAPI client) can warn/refuse on mismatch
        // instead of failing on a missing field mid-operation. Bump ONLY on
        // a breaking wire change; additive fields do not bump it. The golden
        // corpus under src/test/resources/ipc-contract/ pins the current
        // shape (IpcContractCorpusTest).
        const val IPC_PROTOCOL_VERSION: Int = 1
    }
}
