package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spec test T1: `daemon_binds_socket_and_serves_hydration_verbs`.
 *
 * Pins the end-to-end happy path of the daemon lifecycle from
 * unidrive-daemon-design.md §3.2: ProcessLock(DAEMON) acquired →
 * StateDatabase opened → provider.authenticateAndLog() succeeded →
 * IpcServer bound → hydration.* handlers wired → graceful close.
 *
 * Does NOT use mockk — :app:cli has only kotlin-test on its test
 * classpath. The test stubs CloudProvider directly with a tiny inline
 * object since the only method T1 exercises is authenticateAndLog().
 */
class DaemonRuntimeTest {
    private lateinit var tempDir: Path
    private lateinit var lockFile: Path
    private lateinit var dbPath: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-runtime-test")
        lockFile = tempDir.resolve(".lock")
        dbPath = tempDir.resolve("state.db")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(dbPath) }
        runCatching { Files.deleteIfExists(lockFile) }
        runCatching { Files.deleteIfExists(lockFile.resolveSibling(".lock.pid")) }
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    @Test
    fun daemon_fail_fast_on_auth_failure_does_not_bind_socket() = runBlocking {
        // Spec T4: provider.authenticate() throws → daemon refuses to bind
        // the socket, releases the lock, exits non-zero.
        val provider: CloudProvider = AuthFailingStubProvider()

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        // start() rethrows the auth exception (per DaemonRuntime catch+rethrow).
        // For T4 to be cleanly testable, DaemonRuntime.start() must propagate
        // auth exceptions BEFORE the socket is bound. The catch block in start()
        // rethrows after cleanup() — that's the design contract this test pins.
        val ex = kotlin.runCatching {
            runtime.start()
        }.exceptionOrNull()

        assertTrue(
            ex is org.krost.unidrive.AuthenticationException ||
                ex?.cause is org.krost.unidrive.AuthenticationException,
            "auth failure must propagate; got: $ex",
        )

        // Invariant I4: socket file must NOT exist after auth failure.
        assertTrue(
            !Files.exists(socketPath),
            "socket file must not be left behind after auth failure; found $socketPath",
        )

        // Lock must be released — the .lock.pid sidecar should be gone.
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        assertTrue(
            !Files.exists(pidFile),
            ".lock.pid sidecar must be cleaned up after auth failure; found $pidFile",
        )
    }

    @Test
    fun daemon_binds_socket_and_serves_hydration_verbs() = runBlocking {
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }

        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
        try {
            // hydration.list takes "prefix", not "path" (cf. HydrationIpcHandler.handle()).
            // On a brand-new empty StateDatabase, list("/") returns Ok(emptyList()) → ok:true.
            val req = """{"verb":"hydration.list","prefix":"/"}""" + "\n"
            channel.write(ByteBuffer.wrap(req.toByteArray()))
            val buf = ByteBuffer.allocate(4096)
            channel.read(buf)
            buf.flip()
            val reply = String(buf.array(), 0, buf.limit())
            assertTrue(reply.contains("\"ok\":true"), "expected ok:true reply; got: $reply")
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun refresh_run_emits_terminal_event_after_completing_enumeration() = runBlocking {
        // Spec T5: subscribe -> refresh.run -> await refresh.done terminal event.
        // Then re-issue refresh.run; must succeed (not stuck in 'busy').
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }

        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
        try {
            // Non-blocking reads so the deadline loop below actually polls the
            // deadline. With blocking reads, a read with nothing buffered hangs
            // forever and the test JVM never exits even on assertion failure.
            channel.configureBlocking(false)
            // Subscribe first
            channel.write(ByteBuffer.wrap(("""{"verb":"sync.subscribe"}""" + "\n").toByteArray()))
            val subReply = readUntil(channel, "\"ok\":true", timeoutMs = 5_000)
            assertTrue(subReply.contains("\"ok\":true"), "subscribe must succeed; got: $subReply")

            // Issue refresh.run
            channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))

            // Read until we see the terminal event "refresh.done"
            val collected = readUntil(channel, "refresh.done", timeoutMs = 10_000)
            assertTrue(
                collected.contains("\"event\":\"refresh.done\""),
                "expected refresh.done terminal event within 10s; got: $collected",
            )
            assertTrue(
                collected.contains("\"ok\":true"),
                "expected ok:true on terminal event; got: $collected",
            )

            // Issue refresh.run again - must NOT be 'busy' since first one completed.
            channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))
            val secondReply = readUntil(channel, "\"job_id\"", timeoutMs = 5_000)
            assertTrue(
                secondReply.contains("\"ok\":true"),
                "second refresh.run must succeed (not busy); got: $secondReply",
            )
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun view_invalidated_pushed_on_detected_remote_change_after_subscribe() = runBlocking {
        // Reactive-freshness invariant: when the FUSE co-daemon issues hydration.subscribe
        // on mount, the daemon runs ONE reactive enumerate; because the provider's delta
        // reports a remote file, state.db is mutated and the engine pushes a view.invalidated
        // event back over the SAME subscribe stream — without any manual `refresh` or poll loop.
        val provider: CloudProvider = OneRemoteFileStubProvider()

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
        try {
            channel.configureBlocking(false)
            // Subscribe via hydration.subscribe (the verb the co-daemon issues on mount).
            channel.write(ByteBuffer.wrap(("""{"verb":"hydration.subscribe"}""" + "\n").toByteArray()))
            // First line is the subscribe ok-reply; the view.invalidated event follows
            // post-reply once the auto-enumerate mutates state.db.
            val collected = readUntil(channel, "view.invalidated", timeoutMs = 10_000)
            assertTrue(
                collected.contains("\"event\":\"view.invalidated\""),
                "expected a view.invalidated push after subscribe-triggered enumerate; got: $collected",
            )
            assertTrue(
                collected.contains("/remote-new.txt"),
                "view.invalidated must name the newly-enumerated path; got: $collected",
            )
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun daemon_status_returns_uptime_clients_and_refresh_state() = runBlocking {
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound")

        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))
        channel.configureBlocking(false)
        try {
            channel.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
            val reply = readUntil(channel, "\"ok\"", timeoutMs = 5_000L)
            assertTrue(reply.contains("\"ok\":true"), "expected ok:true; got: $reply")
            assertTrue(
                reply.contains("\"protocol_version\":${DaemonRuntime.IPC_PROTOCOL_VERSION}"),
                "expected protocol_version handshake field; got: $reply",
            )
            assertTrue(reply.contains("\"uptime_ms\""), "expected uptime_ms field; got: $reply")
            assertTrue(reply.contains("\"clients_connected\""), "expected clients_connected; got: $reply")
            assertTrue(reply.contains("\"refresh_in_flight\":false"), "expected refresh_in_flight:false; got: $reply")
            assertTrue(reply.contains("\"refresh_job_id\":null"), "expected refresh_job_id:null; got: $reply")
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    /**
     * Poll [channel] (configured non-blocking) into a StringBuilder until either
     * [needle] appears in collected bytes or [timeoutMs] elapses. Used by T5 to
     * avoid the blocking-read hang that would otherwise prevent the test JVM
     * from exiting on assertion failure or daemon stall.
     */
    private suspend fun readUntil(
        channel: SocketChannel,
        needle: String,
        timeoutMs: Long,
    ): String {
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !collected.contains(needle)) {
            val buf = ByteBuffer.allocate(4096)
            val n = channel.read(buf)
            if (n > 0) {
                buf.flip()
                collected.append(String(buf.array(), 0, buf.limit()))
            } else {
                delay(20)
            }
        }
        return collected.toString()
    }

    /**
     * Minimal CloudProvider stub. Only authenticateAndLog() (via authenticate())
     * is exercised by T1's happy-path lifecycle; the rest must compile but never
     * runs in this test.
     */
    private class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op: already authenticated */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            error("not used in T1")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used in T1")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used in T1")

        override suspend fun delete(remotePath: String) = error("not used in T1")

        override suspend fun createFolder(path: String): CloudItem = error("not used in T1")

        override suspend fun move(fromPath: String, toPath: String): CloudItem =
            error("not used in T1")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    /**
     * Stub that throws AuthenticationException from authenticate().
     * Used by T4 to pin the fail-fast contract: socket must NOT be bound
     * when auth fails.
     */
    private class AuthFailingStubProvider : CloudProvider {
        override val id: String = "stub-auth-failing"
        override val displayName: String = "Stub (auth fails)"
        override var isAuthenticated: Boolean = false

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {
            throw org.krost.unidrive.AuthenticationException("test auth failure (T4)")
        }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            error("not used in T4")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used in T4")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used in T4")

        override suspend fun delete(remotePath: String) = error("not used in T4")

        override suspend fun createFolder(path: String): CloudItem = error("not used in T4")

        override suspend fun move(fromPath: String, toPath: String): CloudItem =
            error("not used in T4")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    /**
     * Stub whose delta reports a single remote file on a complete (hasMore=false)
     * enumeration. Used by the reactive-freshness test to make the subscribe-triggered enumerate
     * mutate state.db so a view.invalidated event fires on the subscribe stream.
     */
    private class OneRemoteFileStubProvider : CloudProvider {
        override val id: String = "stub-one-file"
        override val displayName: String = "Stub (one remote file)"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage =
            DeltaPage(
                items = listOf(
                    CloudItem(
                        id = "remote-1",
                        name = "remote-new.txt",
                        path = "/remote-new.txt",
                        size = 7L,
                        isFolder = false,
                        modified = java.time.Instant.now(),
                        created = java.time.Instant.now(),
                        hash = "abc123",
                        mimeType = "text/plain",
                    ),
                ),
                cursor = "cursor-1",
                hasMore = false,
            )

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}
