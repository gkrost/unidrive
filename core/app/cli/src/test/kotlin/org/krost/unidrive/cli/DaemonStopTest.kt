package org.krost.unidrive.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcEndpoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `daemon stop` must ask the daemon to stop over IPC (daemon.shutdown) before it resorts to
 * Process.destroy(), which is TerminateProcess on Windows and skips every shutdown hook.
 */
class DaemonStopTest {
    private class Script(
        val shutdownAccepted: Boolean,
        val exitsAfterShutdown: Boolean,
        val exitsAfterDestroy: Boolean = true,
    ) {
        var shutdownRequests = 0
        var destroys = 0
        private var alive = true

        fun stop(): DaemonStopOutcome =
            stopDaemonProcess(
                requestShutdown = {
                    shutdownRequests++
                    if (shutdownAccepted && exitsAfterShutdown) alive = false
                    shutdownAccepted
                },
                awaitExit = { _ -> !alive },
                destroy = {
                    destroys++
                    if (exitsAfterDestroy) alive = false
                },
                gracefulDeadlineMs = 1,
                forcedDeadlineMs = 1,
            )
    }

    @Test
    fun `a daemon that accepts daemon_shutdown and exits is never destroyed`() {
        val script = Script(shutdownAccepted = true, exitsAfterShutdown = true)
        assertEquals(DaemonStopOutcome.GRACEFUL, script.stop())
        assertEquals(1, script.shutdownRequests)
        assertEquals(0, script.destroys, "destroy() is the fallback, not the first resort")
    }

    @Test
    fun `an unreachable socket falls back to destroy`() {
        val script = Script(shutdownAccepted = false, exitsAfterShutdown = false)
        assertEquals(DaemonStopOutcome.FORCED, script.stop())
        assertEquals(1, script.destroys)
    }

    @Test
    fun `a daemon that accepts daemon_shutdown but does not exit in time is destroyed`() {
        val script = Script(shutdownAccepted = true, exitsAfterShutdown = false)
        assertEquals(DaemonStopOutcome.FORCED, script.stop())
        assertEquals(1, script.destroys)
    }

    @Test
    fun `a daemon that survives destroy is reported as failed`() {
        val script = Script(shutdownAccepted = false, exitsAfterShutdown = false, exitsAfterDestroy = false)
        assertEquals(DaemonStopOutcome.FAILED, script.stop())
    }

    @Test
    fun `requestDaemonShutdown returns false when nothing listens on the socket`() {
        val dir = Files.createTempDirectory("daemon-stop-test")
        try {
            assertFalse(requestDaemonShutdown(IpcEndpoint(dir.resolve("absent.sock"), dir, "stop_profile")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `requestDaemonShutdown stops a live daemon through its clean shutdown path`() = runBlocking {
        val dir = Files.createTempDirectory("daemon-stop-test")
        val socketPath: Path = dir.resolve("daemon.sock")
        val lockFile = dir.resolve(".lock")
        val runtime = DaemonRuntime(
            profileName = "stop_profile",
            lockFile = lockFile,
            dbPath = dir.resolve("state.db"),
            syncRoot = dir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        // start() runs on this thread and registers every handler before its first suspension; the
        // blocking client call below runs on another one.
        val daemonJob = launch { runtime.start() }
        try {
            repeat(50) {
                if (Files.exists(socketPath)) return@repeat
                delay(50)
            }
            assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

            assertTrue(
                withContext(Dispatchers.IO) { requestDaemonShutdown(IpcEndpoint(socketPath, dir, "stop_profile")) },
                "a live daemon must ack daemon.shutdown",
            )

            withTimeout(10_000) { daemonJob.join() }
            assertFalse(Files.exists(socketPath), "the clean shutdown path removes the socket")
            assertFalse(Files.exists(lockFile.resolveSibling(".lock.pid")), "and releases the lock")
        } finally {
            runtime.close()
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `requestDaemonShutdown without the daemon's token does not stop it`() = runBlocking {
        val dir = Files.createTempDirectory("daemon-stop-test")
        val socketPath: Path = dir.resolve("daemon.sock")
        val runtime = DaemonRuntime(
            profileName = "stop_profile",
            lockFile = dir.resolve(".lock"),
            dbPath = dir.resolve("state.db"),
            syncRoot = dir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        val daemonJob = launch { runtime.start() }
        try {
            repeat(50) {
                if (Files.exists(socketPath)) return@repeat
                delay(50)
            }
            assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")
            // Only the read token: daemon.shutdown is an admin verb and needs the full one.
            val readOnly = Files.createDirectories(dir.resolve("read-only"))
            Files.copy(IpcAuth.tokenFile(dir, IpcAuth.Scope.READ), IpcAuth.tokenFile(readOnly, IpcAuth.Scope.FULL))
            val stale = Files.createDirectories(dir.resolve("stale"))
            Files.writeString(IpcAuth.tokenFile(stale, IpcAuth.Scope.FULL), "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")

            for (tokenDir in listOf(readOnly, stale)) {
                assertFalse(
                    withContext(Dispatchers.IO) { requestDaemonShutdown(IpcEndpoint(socketPath, tokenDir, "stop_profile")) },
                    "no shutdown without the full token ($tokenDir)",
                )
            }
            delay(300)
            assertTrue(daemonJob.isActive, "the daemon still runs")
            assertTrue(Files.exists(socketPath))
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}
