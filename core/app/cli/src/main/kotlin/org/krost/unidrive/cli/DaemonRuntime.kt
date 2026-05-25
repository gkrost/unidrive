package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.authenticateAndLog
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
 *   5. Bind IpcServer + register handlers. On bind failure: release lock, rethrow.
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
    private val socketPath: Path,
    private val providerFactory: () -> CloudProvider,
) {
    private val log = LoggerFactory.getLogger(DaemonRuntime::class.java)

    private var lock: ProcessLock? = null
    private var db: StateDatabase? = null
    private var ipcServer: IpcServer? = null
    private val closeSignal = CompletableDeferred<Unit>()

    suspend fun start() {
        // 1. Acquire lock (Mode.DAEMON).
        Files.createDirectories(lockFile.parent)
        val acquiredLock = ProcessLock(lockFile)
        if (!acquiredLock.tryLock(ProcessLock.Mode.DAEMON)) {
            renderLockContentionAndExit(acquiredLock)
            return
        }
        lock = acquiredLock

        try {
            // 2. Stale-mount warn (spec §3.3) — best-effort, never aborts.
            val staleMounts = StaleMountDetector.detectStaleFuseUnidriveMounts()
            if (staleMounts.isNotEmpty()) {
                System.err.println(
                    "WARNING: detected ${staleMounts.size} stale unidrive FUSE mount(s) " +
                        "(likely from a kill -9'd `unidrive mount` parent or prior daemon): " +
                        staleMounts.joinToString(),
                )
                System.err.println(
                    "These mounts no longer serve data. Clean up with " +
                        "`fusermount3 -u <path>` for each.",
                )
            }

            // 3. Open StateDatabase.
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

            // 5. Bind IpcServer + register handlers.
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
                server.start(serveScope)

                val engine = SyncEngine(provider, db!!, syncRoot = dbPath.parent)
                val hydration = HydrationImpl(engine, db!!)
                val hydrationIpc = HydrationIpcHandler(hydration)
                for (verb in HydrationIpcHandler.VERBS) {
                    server.registerHandler(verb) { connId, json ->
                        hydrationIpc.handle(connectionId = connId, jsonRequest = json)
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
                // jobs are cancelled when the daemon shuts down.
                val refreshHandler = RefreshRpcHandler(server, engine, serveScope)
                server.registerHandler("refresh.run") { connId, json ->
                    refreshHandler.handle(connId, json)
                }

                System.err.println(
                    "daemon ready, pid ${ProcessHandle.current().pid()}, socket $socketPath",
                )

                // 6. SERVE until close().
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
            // 7. Graceful cleanup.
            cleanup()
        }
    }

    fun close() {
        closeSignal.complete(Unit)
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
    }
}
