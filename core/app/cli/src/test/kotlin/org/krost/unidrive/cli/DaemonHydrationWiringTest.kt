package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon's wiring order (#607 c): the hydration layer exists before any request can reach it, and the
 * engine's hooks into that layer have a defined answer while it does not exist (yet, or ever on a mirror).
 */
class DaemonHydrationWiringTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-hydration-wiring")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private fun runtime(mode: ProfileMode) =
        DaemonRuntime(
            profileMode = mode,
            profileName = "test_profile",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = if (mode == ProfileMode.MOUNT) tempDir else tempDir.resolve("sync-root"),
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )

    /**
     * A mount daemon builds its hydration layer before it binds the socket, so there is no window in which
     * a request meets a daemon without one: the first request a client can send gets the hydration layer's
     * own reply, never an error for a missing layer and never a dropped line. The request goes out the
     * moment the socket file appears.
     */
    @Test
    fun `the first request on a mount daemon's socket already reaches the hydration layer`() = runBlocking {
        val runtime = runtime(ProfileMode.MOUNT)
        val daemonJob = launch { runtime.start() }
        withTimeout(30_000) {
            while (!Files.exists(socketPath)) {
                check(!daemonJob.isCompleted) { "the daemon start finished without binding its socket" }
                delay(1)
            }
        }
        try {
            assertEquals(
                """{"ok":true,"entries":[]}""",
                exchange("""{"verb":"hydration.list","prefix":"/"}"""),
                "the first request is answered by the hydration layer",
            )
            val status = Json.parseToJsonElement(exchange("""{"verb":"daemon.status"}""")).jsonObject
            assertTrue("cache" in status, "a mount daemon's status carries the hydration cache: $status")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    /**
     * A mirror daemon never builds a hydration layer. Every hydration verb and sync.enumerate get the
     * fixed refusal, and daemon.status leaves out what only a hydration layer can know (uploads, cache).
     */
    @Test
    fun `a daemon without a hydration layer refuses its verbs with wrong_mode and omits uploads and cache`() = runBlocking {
        val runtime = runtime(ProfileMode.MIRROR)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            for (verb in HydrationIpcHandler.VERBS + "sync.enumerate") {
                assertEquals(
                    """{"ok":false,"error":"wrong_mode"}""",
                    exchange("""{"verb":"$verb"}"""),
                    "$verb on a daemon without a hydration layer",
                )
            }
            val status = Json.parseToJsonElement(exchange("""{"verb":"daemon.status"}""")).jsonObject
            assertFalse("uploads" in status, "no upload queue to report: $status")
            assertFalse("cache" in status, "no hydration cache to report: $status")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    /**
     * The engine is built before the hydration layer (which is built over it), so the engine's two hooks into
     * that layer go through [DaemonRuntime.HydrationLink]. Until it is bound (and forever on a mirror daemon)
     * no upload slot is held and a view invalidation has no subscriber to reach: it is dropped, never an error.
     */
    @Test
    fun `an unbound hydration link holds no upload slot and drops a view invalidation`() {
        val link = DaemonRuntime.HydrationLink()
        assertNull(link.hydration, "nothing to shut down before the hydration layer exists")
        assertFalse(link.hasUploadSlot("/a.txt"), "no upload can be in flight without a hydration layer")
        link.dispatchEvent(HydrationEvent.ViewInvalidated(paths = listOf("/a.txt")))
        link.dispatchEvent(HydrationEvent.ViewInvalidated(paths = emptyList(), full = true))
    }

    /** One authenticated request/reply on a fresh connection. */
    private suspend fun exchange(request: String): String {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "test_profile"), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            return readFirstLine(channel)
        } finally {
            channel.close()
        }
    }

    private suspend fun readFirstLine(channel: SocketChannel): String {
        val buffer = ByteBuffer.allocate(64 * 1024)
        val line = StringBuilder()
        val deadline = System.currentTimeMillis() + IPC_STEP_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            val n = channel.read(buffer)
            if (n > 0) {
                line.append(String(buffer.array(), 0, buffer.position()))
                buffer.clear()
                val idx = line.indexOf("\n")
                if (idx >= 0) return line.substring(0, idx)
            }
            delay(10)
        }
        error("no reply within ${IPC_STEP_BUDGET_MS}ms; got: $line")
    }

    /** Minimal provider: authenticated, empty drive. */
    private class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op: already authenticated */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}
