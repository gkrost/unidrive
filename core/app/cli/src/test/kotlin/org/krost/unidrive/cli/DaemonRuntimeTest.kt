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
    fun daemon_binds_socket_and_serves_hydration_verbs() = runBlocking {
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
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
}
