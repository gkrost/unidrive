package org.krost.unidrive.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class IpcServer(
    private val socketPath: Path,
) {
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

    // Convenience: look up ClientEntry by SocketChannel (null if already gone).
    private fun entryFor(sc: SocketChannel): ClientEntry? = clients.firstOrNull { it.channel == sc }

    private val log = LoggerFactory.getLogger(IpcServer::class.java)
    // Each connected client is tracked as a (SocketChannel, connectionId) pair.
    // connectionId is a random UUID assigned at accept-time; stable for the lifetime
    // of the connection regardless of how other clients join or leave.
    private data class ClientEntry(val channel: SocketChannel, val id: String)
    private val clients = CopyOnWriteArrayList<ClientEntry>()
    private val channel = Channel<String>(capacity = 256)
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, suspend (String, String) -> String>()
    private val closeListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var serverChannel: ServerSocketChannel? = null
    private var acceptJob: Job? = null
    private var broadcastJob: Job? = null

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

    fun emit(json: String) {
        if (!channel.isClosedForSend) {
            runCatching { channel.trySend(json) }
        }
    }

    fun start(scope: CoroutineScope) {
        reclaimStaleSocket()

        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        // UD-100: defense-in-depth — set 0600 on socket file for parity with parent dir 0700 (tempSocketDir() at line 316).
        runCatching {
            Files.setPosixFilePermissions(
                socketPath,
                PosixFilePermissions.fromString("rw-------"),
            )
        }
        serverChannel = server

        acceptJob =
            scope.launch(Dispatchers.IO) {
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
                        clients.add(entry)
                        log.debug("IPC: client connected id={} (total={})", connId, clients.size)
                        flushStateDump(sc)
                        scope.launch(Dispatchers.IO) {
                            val buf = ByteBuffer.allocate(MAX_REQUEST_BYTES)
                            val pending = StringBuilder()
                            try {
                                while (isActive) {
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
                                        idx = pending.indexOf('\n')
                                    }
                                }
                            } catch (e: IOException) {
                                log.debug("IPC: client reader closed: {}", e.message)
                            } finally {
                                clients.remove(entry)
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
            scope.launch(Dispatchers.IO) {
                for (json in channel) {
                    val line = (json + "\n").toByteArray(Charsets.UTF_8)
                    val dead = mutableListOf<ClientEntry>()
                    for (entry in clients) {
                        try {
                            writeNonBlocking(entry.channel, ByteBuffer.wrap(line))
                        } catch (e: IOException) {
                            log.debug("IPC: dropping dead client id={}: {}", entry.id, e.message)
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
        runCatching { Files.deleteIfExists(socketPath) }
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

    private fun flushStateDump(client: SocketChannel) {
        val state = syncState ?: return
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
                    """"phase":"${state.phase}","count":${state.scanCount}""",
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
                    """"index":${state.actionIndex},"total":${state.actionTotal},"action":"${state.lastAction}","path":"${state.lastPath ?: ""}"""",
                ),
            )
        }
        for (line in lines) {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            try {
                writeNonBlocking(client, ByteBuffer.wrap(bytes))
            } catch (e: IOException) {
                log.debug("IPC: failed to flush state dump to new client: {}", e.message)
                val entry = entryFor(client)
                if (entry != null) clients.remove(entry)
                runCatching { client.close() }
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
        sb.append("""{"event":"$event","profile":"$profile"""")
        if (extra != null) {
            sb.append(",")
            sb.append(extra)
        }
        sb.append(""","timestamp":"$timestamp"}""")
        return sb.toString()
    }

    private suspend fun dispatchRequest(client: SocketChannel, connId: String, line: String) {
        val verb = parseVerb(line) ?: run {
            log.warn("IPC: request without 'verb' field, dropping: {}", line.take(80))
            return
        }
        val handler = handlers[verb] ?: run {
            log.warn("IPC: no handler for verb '{}'", verb)
            return
        }
        val reply = try {
            handler(connId, line)
        } catch (e: Exception) {
            log.error("IPC: handler '$verb' threw", e)
            """{"error":"handler_threw","verb":"$verb","message":${escapeJson(e.message ?: "")}}"""
        }
        runCatching {
            writeNonBlocking(client, ByteBuffer.wrap((reply + "\n").toByteArray(Charsets.UTF_8)))
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
        val deadline = System.nanoTime() + WRITE_TIMEOUT_NS
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
        private const val WRITE_TIMEOUT_NS = 5_000_000_000L // 5 seconds
        private const val MAX_SOCKET_PATH_LENGTH = 90
        private const val MAX_REQUEST_BYTES = 64 * 1024

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
            val metaPath = socketPath.resolveSibling("${socketPath.fileName}.meta")
            Files.writeString(metaPath, "$profileName\n")
        }

        fun defaultSocketPath(profileName: String): Path {
            val os = System.getProperty("os.name", "").lowercase()
            if (os.contains("win")) {
                // Windows AF_UNIX sockets don't work in %LOCALAPPDATA% directly
                // but do work in %TEMP% (which is %LOCALAPPDATA%\Temp)
                val tmpDir = System.getProperty("java.io.tmpdir")
                val dir = Path.of(tmpDir, "unidrive-ipc")
                Files.createDirectories(dir)
                return resolveAndMeta(dir, profileName)
            }
            // Linux / macOS: /run/user/$UID/
            return try {
                val uid = getUid()
                val runDir = Path.of("/run/user/$uid")
                if (Files.isDirectory(runDir)) {
                    resolveAndMeta(runDir, profileName)
                } else {
                    resolveAndMeta(tempSocketDir(), profileName)
                }
            } catch (_: Exception) {
                resolveAndMeta(tempSocketDir(), profileName)
            }
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

        private fun getUid(): Int {
            val linker = Linker.nativeLinker()
            val getuid =
                linker.downcallHandle(
                    linker.defaultLookup().find("getuid").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT),
                )
            return getuid.invoke() as Int
        }

        private fun tempSocketDir(): Path {
            val perms = PosixFilePermissions.fromString("rwx------")
            return try {
                Files.createTempDirectory(
                    "unidrive-ipc-",
                    PosixFilePermissions.asFileAttribute(perms),
                )
            } catch (_: UnsupportedOperationException) {
                // Windows doesn't support POSIX permissions
                Files.createTempDirectory("unidrive-ipc-")
            }
        }
    }
}
