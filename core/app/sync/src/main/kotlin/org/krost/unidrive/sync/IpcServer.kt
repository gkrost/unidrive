package org.krost.unidrive.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.io.OwnerOnly
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class IpcServer(
    private val socketPath: Path,
    private val transportDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    private val handlerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    writeTimeoutMs: Long = readWriteTimeoutFromEnv(),
    // IPC authentication (docs/dev/specs/ipc-authentication.md). `daemon run` and `sync` always pass one;
    // null (no handshake) is for the unit tests of the bare server only.
    private val auth: IpcAuth? = null,
) {
    // Captured at construction so a later companion-object change can't accidentally
    // re-read the env var mid-flight. Multiply once into nanos so writeNonBlocking
    // does no per-call arithmetic.
    private val writeTimeoutNs: Long = writeTimeoutMs * 1_000_000L

    data class SyncState(
        val profile: String,
        val phase: String? = null,
        val scanCount: Int = 0,
        val actionTotal: Int = 0,
        val actionIndex: Int = 0,
        val lastAction: String? = null,
        val lastPath: String? = null,
        val isComplete: Boolean = false,
        val downloaded: Int = 0,
        val uploaded: Int = 0,
        val conflicts: Int = 0,
        val durationMs: Long = 0,
    )

    @Volatile
    var syncState: SyncState? = null
        private set

    /**
     * UD-214: number of currently-connected clients. Exposed so callers can
     * synchronize on "accept loop has registered a freshly-connected client"
     * without polling private state. The value is eventually-consistent —
     * `accept()` runs on `Dispatchers.IO` and there is a small window
     * between the client's TCP connect completing and `clients.add(...)`
     * running — but is monotonic for a given client until that client
     * closes. Primary consumer: `IpcProgressReporterTest` and any tool
     * that needs to gate emission on "at least one consumer is listening".
     */
    val clientCount: Int
        get() = clients.size

    private val log = LoggerFactory.getLogger(IpcServer::class.java)
    // Each connected client is tracked as a (SocketChannel, connectionId) pair.
    // connectionId is a random UUID assigned at accept-time; stable for the lifetime
    // of the connection regardless of how other clients join or leave.
    private data class ClientEntry(
        val channel: SocketChannel,
        val id: String,
        val writeMutex: Mutex = Mutex(),
    )
    private val clients = CopyOnWriteArrayList<ClientEntry>()
    private val channel = Channel<String>(capacity = 256)
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, suspend (String, String) -> String>()
    private val closeListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var serverChannel: ServerSocketChannel? = null

    // #419: set once start() has bound the socket, so close() removes only files this server owns.
    @Volatile
    private var ownsSocketFiles = false
    private var acceptJob: Job? = null
    private var broadcastJob: Job? = null
    private var ownedTransport: java.util.concurrent.ExecutorService? = null

    // Connections that issued `sync.subscribe`. Filtered by the broadcast loop
    // (§3.2.5) so request-reply-only clients (e.g. FUSE co-daemon) never
    // receive unsolicited sync-progress bytes. Cleanup is via the close-
    // listener registered externally by SyncCommand (§3.3 of the spec).
    private val syncSubscribers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // One-shot per-connection slot for an action that runs AFTER the current
    // request's reply is written to the socket. See scheduleAfterReply()
    // and the post-reply hook in dispatchRequest. Single in-flight request
    // per connection (the per-client reader processes lines sequentially),
    // so a per-connId slot is sufficient — no queue.
    private val pendingPostReply = java.util.concurrent.ConcurrentHashMap<String, suspend () -> Unit>()

    // IPC authentication: one handshake state per connection (connId), only when [auth] is set.
    private val authSessions = java.util.concurrent.ConcurrentHashMap<String, IpcAuth.Session>()

    /**
     * Register an inbound-verb handler. The handler receives the connection ID
     * (a per-client UUID, stable for the life of the connection) and the raw
     * JSON line (excluding the trailing newline), and returns the JSON reply
     * line (the server appends the newline). Verb dispatch keys on a top-level
     * "verb" field in the request JSON. Throws IllegalArgumentException on
     * duplicate registration (registration is one-shot per verb).
     */
    fun registerHandler(verb: String, handler: suspend (connectionId: String, json: String) -> String) {
        require(handlers.putIfAbsent(verb, handler) == null) {
            "Handler for verb '$verb' is already registered"
        }
    }

    /**
     * Register a listener invoked when a client disconnects (EOF or IOException).
     * Called with the same connectionId that was passed to registered handlers.
     */
    fun registerConnectionCloseListener(listener: (connectionId: String) -> Unit) {
        closeListeners.add(listener)
    }

    fun updateState(state: SyncState) {
        syncState = state
    }

    // NEW-2: count silently-dropped broadcast events so a full-channel drop
    // (slow/absent subscriber) is observable instead of invisible. Exposed
    // internally for the regression test; production drop semantics are
    // unchanged (we still drop — observability only).
    private val droppedBroadcastEvents = java.util.concurrent.atomic.AtomicLong(0)

    internal val droppedBroadcastEventsForTest: Long
        get() = droppedBroadcastEvents.get()

    fun emit(json: String) {
        // trySend on a closed channel reports "closed" instead of throwing, so no isClosedForSend
        // pre-check (a delicate API) is needed: a closed channel is neither sent to nor counted as a drop.
        val r = channel.trySend(json)
        if (r.isFailure && !r.isClosed) {
            droppedBroadcastEvents.incrementAndGet()
            log.warn("sync-progress broadcast event dropped (channel full, cap=256) — subscriber too slow")
        }
    }

    /**
     * Write a single JSON line to one specific connection (newline is appended
     * server-side). Used by the hydration subscriber pipeline to push events to
     * just the connections that ran `hydration.subscribe`, instead of fanning out
     * via the shared broadcast channel. Returns false when the connection is
     * unknown or the write failed (dead client is then removed and close-listeners
     * fire — same shape as the broadcast loop's dead-client cleanup).
     */
    suspend fun writeToConnection(connectionId: String, json: String): Boolean {
        val entry = clients.firstOrNull { it.id == connectionId } ?: return false
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        return try {
            entry.writeMutex.withLock {
                writeNonBlocking(entry.channel, ByteBuffer.wrap(bytes))
            }
            true
        } catch (e: IOException) {
            log.debug("IPC: writeToConnection failed for id={}: {}", entry.id, e.message)
            clients.remove(entry)
            runCatching { entry.channel.close() }
            closeListeners.forEach { it(entry.id) }
            false
        }
    }

    /**
     * Mark a connection as a sync-progress subscriber. After this call, the
     * connection receives sync-progress events from `emit(...)` until it
     * disconnects (cleanup via `unregisterSyncSubscriber`, called from a
     * connection-close listener registered externally by `SyncCommand` for
     * stylistic parity with the existing hydration close listeners).
     *
     * Symmetric with `HydrationIpcHandler.registerSubscriber` at the wire
     * level; the two subscriber sets are independent (a client may
     * subscribe to one, both, or neither).
     */
    fun registerSyncSubscriber(connectionId: String) {
        syncSubscribers.add(connectionId)
    }

    /**
     * Remove a connection from the sync-progress subscriber set. Idempotent;
     * called from the connection-close listener `SyncCommand` registers
     * and from the broadcast loop's dead-subscriber cleanup path.
     */
    fun unregisterSyncSubscriber(connectionId: String) {
        syncSubscribers.remove(connectionId)
    }

    /**
     * Schedule a one-shot action to run AFTER the current request's reply
     * is written to the socket. Used by `sync.subscribe` to push the state
     * dump and subscriber-set registration only after the {"ok":true} reply
     * is on the wire — so the subscriber's first observed line is always
     * the reply, never an event.
     *
     * Must be called from inside a verb handler running under
     * dispatchRequest's withContext(handlerDispatcher) block. Calling
     * outside a handler is a no-op (the action will never fire because
     * the next dispatchRequest call clears the slot at the top).
     *
     * The action fires only on the successful-reply path. If the handler
     * threw and dispatchRequest wrote an error envelope, the scheduled
     * action is discarded (R7 in the spec).
     */
    fun scheduleAfterReply(connectionId: String, action: suspend () -> Unit) {
        pendingPostReply[connectionId] = action
    }

    /**
     * Test-only accessor used by IpcSyncSubscriberSetTest to assert that
     * disconnected connections are removed from the subscriber set (T4 in
     * the spec). Same-module visibility is sufficient — `internal` works
     * for tests living in `:app:sync`.
     */
    internal val syncSubscribersSnapshot: Set<String>
        get() = syncSubscribers.toSet()

    fun start(scope: CoroutineScope) {
        reclaimStaleSocket()

        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        ownsSocketFiles = true
        // UD-100: defense-in-depth — the socket file is owner-only too (0600 on POSIX, an owner-only
        // ACL on Windows), for parity with its owner-only folder (defaultSocketPath). The folder is
        // what keeps others out, so a failure here is logged rather than fatal.
        val restricted = OwnerOnly.restrictFile(socketPath)
        if (!restricted.restricted) log.warn("IPC: could not restrict the permissions of {}: {}", socketPath, restricted)
        serverChannel = server

        val transport: kotlinx.coroutines.CoroutineDispatcher = transportDispatcher ?: run {
            val es = java.util.concurrent.Executors.newFixedThreadPool(
                TRANSPORT_POOL_SIZE,
                ipcIoThreadFactory(),
            )
            ownedTransport = es
            es.asCoroutineDispatcher()
        }
        log.info(
            "IPC: transport pool size={} write_timeout_ms={}",
            TRANSPORT_POOL_SIZE,
            writeTimeoutNs / 1_000_000L,
        )

        acceptJob =
            scope.launch(transport) {
                while (isActive) {
                    try {
                        val sc = server.accept()
                        if (clients.size >= MAX_CLIENTS) {
                            log.warn("IPC: max clients ({}) reached, rejecting connection", MAX_CLIENTS)
                            runCatching { sc.close() }
                            continue
                        }
                        sc.configureBlocking(false)
                        val connId = java.util.UUID.randomUUID().toString()
                        val entry = ClientEntry(sc, connId)
                        auth?.let { authSessions[connId] = it.newSession() }
                        clients.add(entry)
                        log.debug("IPC: client connected id={} (total={})", connId, clients.size)
                        scope.launch(transport) {
                            val buf = ByteBuffer.allocate(MAX_REQUEST_BYTES)
                            val pending = StringBuilder()
                            try {
                                while (isActive) {
                                    // IPC authentication: a connection still unauthenticated after the timeout is closed.
                                    if (authSessions[connId]?.timedOut() == true) {
                                        log.debug("IPC: closing unauthenticated client id={} after the handshake timeout", connId)
                                        break
                                    }
                                    buf.clear()
                                    val n = sc.read(buf)
                                    if (n < 0) break  // client closed
                                    if (n == 0) { delay(20); continue }
                                    buf.flip()
                                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                                    if (pending.length + bytes.size > MAX_REQUEST_BYTES) {
                                        log.warn("IPC: request too large (pending={} + read={}), closing client", pending.length, bytes.size)
                                        runCatching { sc.close() }
                                        break
                                    }
                                    pending.append(String(bytes, Charsets.UTF_8))
                                    // Split on \n; dispatch each complete line.
                                    var idx = pending.indexOf('\n')
                                    while (idx >= 0) {
                                        val line = pending.substring(0, idx)
                                        pending.delete(0, idx + 1)
                                        dispatchRequest(sc, connId, line)
                                        if (!sc.isOpen) break // closed by the request (failed handshakes)
                                        idx = pending.indexOf('\n')
                                    }
                                }
                            } catch (e: IOException) {
                                log.debug("IPC: client reader closed: {}", e.message)
                            } finally {
                                clients.remove(entry)
                                authSessions.remove(connId)
                                runCatching { sc.close() }
                                log.debug("IPC: reader exited, client removed id={} (total={})", connId, clients.size)
                                closeListeners.forEach { it(connId) }
                            }
                        }
                    } catch (_: java.nio.channels.AsynchronousCloseException) {
                        break
                    } catch (e: IOException) {
                        if (isActive) log.warn("IPC: accept error", e)
                    }
                }
            }

        broadcastJob =
            scope.launch(transport) {
                for (json in channel) {
                    val line = (json + "\n").toByteArray(Charsets.UTF_8)
                    val dead = mutableListOf<ClientEntry>()
                    for (entry in clients) {
                        if (entry.id !in syncSubscribers) continue
                        try {
                            entry.writeMutex.withLock {
                                writeNonBlocking(entry.channel, ByteBuffer.wrap(line))
                            }
                        } catch (e: IOException) {
                            log.debug("IPC: dropping dead sync-subscriber id={}: {}", entry.id, e.message)
                            dead.add(entry)
                        }
                    }
                    for (entry in dead) {
                        clients.remove(entry)
                        runCatching { entry.channel.close() }
                        closeListeners.forEach { it(entry.id) }
                    }
                }
            }
    }

    fun close() {
        channel.close()
        acceptJob?.cancel()
        broadcastJob?.cancel()
        runCatching { serverChannel?.close() }
        for (entry in clients) {
            runCatching { entry.channel.close() }
            closeListeners.forEach { it(entry.id) }
        }
        clients.clear()
        syncSubscribers.clear()
        pendingPostReply.clear()
        authSessions.clear()
        // #419: only remove the files of a socket THIS server bound. A start() that was refused
        // ("Another daemon is already listening") or failed to bind still gets close()d by its
        // caller's cleanup, and must not unlink the live daemon's socket. Cleared afterwards so a
        // second close() cannot delete a successor's socket.
        if (ownsSocketFiles) {
            ownsSocketFiles = false
            deleteSocketFiles(socketPath)
        }
        ownedTransport?.let { es ->
            es.shutdown()
            runCatching {
                es.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            ownedTransport = null
        }
    }

    private fun reclaimStaleSocket() {
        if (Files.exists(socketPath)) {
            try {
                SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).close()
                throw IllegalStateException("Another daemon is already listening on $socketPath")
            } catch (_: ConnectException) {
                Files.deleteIfExists(socketPath) // Stale socket — nobody listening
            } catch (_: java.net.SocketException) {
                Files.deleteIfExists(socketPath) // Stale socket — Windows "Invalid argument"
            }
            // Other IOExceptions propagate — never delete a socket we don't own
        }
    }

    /**
     * Replay the current SyncState as initial state-dump events to a
     * single subscriber connection. Called from the `sync.subscribe`
     * post-reply hook (see scheduleAfterReply) so that subscribers see
     * context for the in-progress sync, but non-subscribers (e.g. the
     * FUSE co-daemon, request-reply clients) never receive these
     * unsolicited bytes on their socket.
     *
     * If `syncState` is null (no sync in progress at subscribe time),
     * this is a no-op. If the connection has closed between subscribe
     * and the post-reply hook firing, logs a WARN and returns —
     * surfacing the rare timing window for future operator debugging.
     *
     * Public for the same module-boundary reason as `registerSyncSubscriber`
     * (called from `SyncCommand` in `:app:cli`; `IpcServer` is in `:app:sync`).
     *
     * On partial-write failure, the subscriber receives a truncated state
     * dump and then nothing further from this method. The connection's
     * subsequent dead-client cleanup (via the broadcast loop's dropping
     * path or the per-client reader's IOException path) handles the
     * unhealthy connection. Subscribers should validate they receive
     * expected event shapes rather than assume the dump completed.
     */
    suspend fun flushStateDumpTo(connectionId: String) {
        val state = syncState ?: return
        val entry = clients.firstOrNull { it.id == connectionId } ?: run {
            log.warn(
                "IPC: flushStateDumpTo called for unknown connection id={}; " +
                    "client likely closed between sync.subscribe parse and post-reply hook",
                connectionId,
            )
            return
        }
        val ts =
            java.time.Instant
                .now()
                .toString()
        val lines = mutableListOf<String>()
        lines.add(buildStateDumpLine("sync_started", state.profile, ts))
        if (state.phase != null) {
            lines.add(
                buildStateDumpLine(
                    "scan_progress",
                    state.profile,
                    ts,
                    """"phase":${escapeJson(state.phase)},"count":${state.scanCount}""",
                ),
            )
        }
        if (state.actionTotal > 0) {
            lines.add(
                buildStateDumpLine(
                    "action_count",
                    state.profile,
                    ts,
                    """"total":${state.actionTotal}""",
                ),
            )
        }
        if (state.actionIndex > 0 && state.lastAction != null) {
            lines.add(
                buildStateDumpLine(
                    "action_progress",
                    state.profile,
                    ts,
                    """"index":${state.actionIndex},"total":${state.actionTotal},"action":${escapeJson(state.lastAction)},"path":${escapeJson(state.lastPath ?: "")}""",
                ),
            )
        }
        for (line in lines) {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            try {
                entry.writeMutex.withLock {
                    writeNonBlocking(entry.channel, ByteBuffer.wrap(bytes))
                }
            } catch (e: IOException) {
                log.debug("IPC: failed to flush state dump to subscriber: {}", e.message)
                clients.remove(entry)
                runCatching { entry.channel.close() }
                syncSubscribers.remove(connectionId)
                return
            }
        }
    }

    private fun buildStateDumpLine(
        event: String,
        profile: String,
        timestamp: String,
        extra: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("""{"event":${escapeJson(event)},"profile":${escapeJson(profile)}""")
        if (extra != null) {
            sb.append(",")
            sb.append(extra)
        }
        sb.append(""","timestamp":${escapeJson(timestamp)}}""")
        return sb.toString()
    }

    private suspend fun dispatchRequest(client: SocketChannel, connId: String, line: String) {
        // R8: defensive clear at the top — any stale entry from a prior request
        // that errored on this connection is removed before this request runs.
        // No-op on the happy path (the entry was already consumed at the end
        // of the prior successful request).
        pendingPostReply.remove(connId)

        // Every well-framed request line gets a reply, including the unanswerable
        // ones. A dropped request (no verb field, or no handler for the verb) is
        // indistinguishable from a lost frame: the client waits on a reply line
        // that never arrives and hangs. This was the daemon-startup hang — a
        // client polling `daemon.status` while the daemon was still registering
        // handlers got silence and waited forever. Handlers are now registered
        // before start() binds the socket, so a live connection always finds a
        // handler for a documented verb; `unknown_verb` then only answers verbs
        // no version of this daemon knows, which the client can surface instead
        // of hanging on.
        val verb = parseVerb(line)
        // IPC authentication (IpcAuth): before the handler lookup, the connection's session answers the
        // handshake itself and refuses what the connection may not call (yet); null = go on as before.
        val gate = auth?.gate(authSessions[connId], verb, line)
        val handler = verb?.let { handlers[it] }
        var handlerThrew = false
        val reply = when {
            gate != null -> gate.json
            verb == null -> {
                log.warn("IPC: request without 'verb' field: {}", line.take(80))
                """{"ok":false,"error":"missing_verb"}"""
            }
            handler == null -> {
                log.warn("IPC: no handler for verb '{}'", verb)
                """{"ok":false,"error":"unknown_verb"}"""
            }
            else -> try {
                kotlinx.coroutines.withContext(handlerDispatcher) { handler(connId, line) }
            } catch (e: Exception) {
                handlerThrew = true
                log.error("IPC: handler '$verb' threw", e)
                """{"error":"handler_threw","verb":"$verb","message":${escapeJson(e.message ?: "")}}"""
            }
        }
        val entry = clients.firstOrNull { it.channel === client } ?: return
        runCatching {
            entry.writeMutex.withLock {
                writeNonBlocking(entry.channel, ByteBuffer.wrap((reply + "\n").toByteArray(Charsets.UTF_8)))
            }
        }
        // The third failed handshake closes the connection (its reader then cleans up).
        if (gate?.close == true) runCatching { client.close() }
        // R7: post-reply hook fires ONLY on the successful-reply path.
        // If the handler threw, the scheduled action (if any) is discarded.
        val pending = pendingPostReply.remove(connId)
        if (!handlerThrew && pending != null) {
            runCatching {
                kotlinx.coroutines.withContext(handlerDispatcher) { pending() }
            }.onFailure { e ->
                log.warn("IPC: post-reply hook for connId={} threw: {}", connId, e.message)
            }
        }
    }

    private fun parseVerb(line: String): String? {
        // Minimal JSON probe — looks for "verb"\s*:\s*"..." at top level. Avoids
        // pulling a full JSON parser into IpcServer for one field.
        // Top-level anchoring: the char before "verb" (skipping whitespace) must be { or ,.
        val key = "\"verb\""
        val k = line.indexOf(key)
        if (k < 0) return null
        // Walk left skipping whitespace to find the previous non-whitespace character.
        var prev = k - 1
        while (prev >= 0 && line[prev].isWhitespace()) prev--
        if (prev < 0 || (line[prev] != '{' && line[prev] != ',')) return null
        val colon = line.indexOf(':', k + key.length)
        if (colon < 0) return null
        val q1 = line.indexOf('"', colon)
        if (q1 < 0) return null
        val q2 = line.indexOf('"', q1 + 1)
        if (q2 < 0) return null
        return line.substring(q1 + 1, q2)
    }

    private fun escapeJson(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun writeNonBlocking(
        client: SocketChannel,
        buf: ByteBuffer,
    ) {
        val deadline = System.nanoTime() + writeTimeoutNs
        while (buf.hasRemaining()) {
            val written = client.write(buf)
            if (written == 0) {
                if (System.nanoTime() > deadline) {
                    throw IOException("Write timeout exceeded for IPC client")
                }
                Thread.sleep(10)
            }
        }
    }

    companion object {
        private const val MAX_CLIENTS = 10
        private const val MAX_SOCKET_PATH_LENGTH = 90
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val TRANSPORT_POOL_SIZE = 4

        // Pure-function overload for testing the parse + clamp logic without
        // touching System.getenv. Production path delegates here.
        internal fun parseWriteTimeoutMs(raw: String?): Long {
            if (raw == null) return 5_000L
            return raw.toLongOrNull()?.takeIf { it in 100L..600_000L } ?: 5_000L
        }

        internal fun readWriteTimeoutFromEnv(): Long =
            parseWriteTimeoutMs(System.getenv("UNIDRIVE_IPC_WRITE_TIMEOUT_MS"))

        private fun ipcIoThreadFactory(): java.util.concurrent.ThreadFactory {
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            return java.util.concurrent.ThreadFactory { r ->
                Thread(r, "ipc-io-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }

        fun socketBaseName(profileName: String): String {
            val base = "unidrive-$profileName.sock"
            if (base.length > MAX_SOCKET_PATH_LENGTH) {
                return hashedSocketName(profileName)
            }
            return base
        }

        private fun hashedSocketName(profileName: String): String {
            val hash =
                MessageDigest
                    .getInstance("SHA-1")
                    .digest(profileName.toByteArray(Charsets.UTF_8))
                    .take(4)
                    .joinToString("") { "%02x".format(it) }
            return "unidrive-$hash.sock"
        }

        private fun writeMetaFile(
            socketPath: Path,
            profileName: String,
        ) {
            Files.writeString(metaPathFor(socketPath), "$profileName\n")
        }

        private fun metaPathFor(socketPath: Path): Path = socketPath.resolveSibling("${socketPath.fileName}.meta")

        private fun deleteSocketFiles(socketPath: Path) {
            runCatching { Files.deleteIfExists(socketPath) }
            runCatching { Files.deleteIfExists(metaPathFor(socketPath)) }
        }

        /**
         * #419: remove the socket file at [socketPath] and its `.meta` sibling after the
         * daemon that owned them is gone, ignoring failures. [close] never runs when the
         * daemon is hard-killed (`daemon stop` on Windows is TerminateProcess, which skips
         * shutdown hooks), so `daemon stop` calls this once the process has exited. A socket
         * that still accepts a connection belongs to a live daemon and is left alone —
         * the same probe [reclaimStaleSocket] uses on start.
         */
        fun removeStaleSocketFiles(socketPath: Path) {
            runCatching {
                if (Files.exists(socketPath)) {
                    try {
                        SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).close()
                        return // somebody is listening: not ours to delete
                    } catch (_: ConnectException) {
                        // Stale socket — nobody listening
                    } catch (_: java.net.SocketException) {
                        // Stale socket — Windows "Invalid argument"
                    }
                    // Other IOExceptions abort via runCatching — never delete a socket we can't classify
                }
                deleteSocketFiles(socketPath)
            }
        }

        fun defaultSocketPath(profileName: String): Path =
            defaultSocketPath(
                profileName,
                Path.of(System.getProperty("java.io.tmpdir")),
                System.getProperty("os.name", "").lowercase().contains("win"),
            )

        internal fun defaultSocketPath(
            profileName: String,
            tmpDir: Path,
            windows: Boolean,
        ): Path {
            if (windows) {
                // Windows AF_UNIX sockets don't work in %LOCALAPPDATA% directly
                // but do work in %TEMP% (which is %LOCALAPPDATA%\Temp)
                return resolveAndMeta(IpcSocketDir.ensureWindowsDir(tmpDir), profileName)
            }
            // Linux / macOS: /run/user/$UID/, else the per-user folder in the temp dir
            // (a fixed name, so clients find the socket there too).
            val uid = OwnerOnly.posixUid()
            val runDir = Path.of("/run/user/$uid")
            val dir = if (Files.isDirectory(runDir)) runDir else IpcSocketDir.ensurePosixFallbackDir(tmpDir, uid)
            return resolveAndMeta(dir, profileName)
        }

        private fun resolveAndMeta(
            dir: Path,
            profileName: String,
        ): Path {
            val candidate = dir.resolve(socketBaseName(profileName))
            val result =
                if (candidate.toString().length > MAX_SOCKET_PATH_LENGTH) {
                    dir.resolve(hashedSocketName(profileName))
                } else {
                    candidate
                }
            // Write .meta file when name was hashed so UI can recover the profile name
            if (result.fileName.toString() != "unidrive-$profileName.sock") {
                writeMetaFile(result, profileName)
            }
            return result
        }
    }
}
