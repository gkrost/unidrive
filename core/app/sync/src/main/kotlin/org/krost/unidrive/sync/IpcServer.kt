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

    private val log = LoggerFactory.getLogger(IpcServer::class.java)
    private val clients = CopyOnWriteArrayList<SocketChannel>()
    private val channel = Channel<String>(capacity = 256)
    private var serverChannel: ServerSocketChannel? = null
    private var acceptJob: Job? = null
    private var broadcastJob: Job? = null

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
        serverChannel = server

        acceptJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val client = server.accept()
                        if (clients.size >= MAX_CLIENTS) {
                            log.warn("IPC: max clients ({}) reached, rejecting connection", MAX_CLIENTS)
                            runCatching { client.close() }
                            continue
                        }
                        client.configureBlocking(false)
                        clients.add(client)
                        log.debug("IPC: client connected (total={})", clients.size)
                        flushStateDump(client)
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
                    val dead = mutableListOf<SocketChannel>()
                    for (client in clients) {
                        try {
                            writeNonBlocking(client, ByteBuffer.wrap(line))
                        } catch (e: IOException) {
                            log.debug("IPC: dropping dead client: {}", e.message)
                            dead.add(client)
                        }
                    }
                    for (c in dead) {
                        clients.remove(c)
                        runCatching { c.close() }
                    }
                }
            }
    }

    fun close() {
        channel.close()
        acceptJob?.cancel()
        broadcastJob?.cancel()
        runCatching { serverChannel?.close() }
        for (c in clients) {
            runCatching { c.close() }
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
                clients.remove(client)
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
