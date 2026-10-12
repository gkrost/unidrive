package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.telemetry.TransferHistory
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #652: a mount daemon records its transfers in `telemetry.db` beside `state.db`, and the file is flushed and closed
 * on the way out. A mirror daemon keeps none. A history that cannot be opened is never a reason to stop serving.
 */
class DaemonTransferHistoryTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-transfer-history")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private val historyFile: Path get() = tempDir.resolve("telemetry.db")

    private fun runtime(
        mode: ProfileMode,
        openHistory: ((String, Path) -> TransferHistory)? = null,
    ) = DaemonRuntime(
        profileMode = mode,
        profileName = "test_profile",
        lockFile = tempDir.resolve(".lock"),
        dbPath = tempDir.resolve("state.db"),
        syncRoot = if (mode == ProfileMode.MOUNT) tempDir else tempDir.resolve("sync-root"),
        socketPath = socketPath,
        providerFactory = { StubProvider() },
    ).also { rt -> openHistory?.let { rt.openHistory = it } }

    // A cloud file the daemon has not downloaded yet, as an earlier enumeration leaves it.
    private fun seedColdFile(path: String, size: Long) {
        val db = StateDatabase(tempDir.resolve("state.db"))
        db.initialize()
        try {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "id-$path",
                    remoteHash = "hash",
                    remoteSize = size,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        } finally {
            db.close()
        }
    }

    private fun rows(): List<Triple<String, String, String>> {
        DriverManager.getConnection("jdbc:sqlite:$historyFile").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT direction, path, result FROM transfers ORDER BY queued_at").use { rs ->
                    return buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getString(3))) }
                }
            }
        }
    }

    @Test
    fun `a mount daemon records a download in telemetry db beside state db`() = runBlocking {
        seedColdFile("/a.txt", size = 5)
        val runtime = runtime(ProfileMode.MOUNT)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            assertEquals("""{"ok":true}""", exchange("""{"verb":"hydration.hydrate","path":"/a.txt"}"""))
        } finally {
            runtime.close()
            daemonJob.join()
        }

        assertTrue(Files.exists(historyFile), "the history file lives in the profile's state folder")
        assertEquals(listOf(Triple("download", "/a.txt", "success")), rows())
    }

    @Test
    fun `the shutdown writes what is queued and closes the history`() = runBlocking {
        seedColdFile("/a.txt", size = 5)
        var opened: TransferHistory? = null
        val runtime = runtime(ProfileMode.MOUNT) { profile, file -> TransferHistory.open(profile, file).also { opened = it } }
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            exchange("""{"verb":"hydration.hydrate","path":"/a.txt"}""")
        } finally {
            runtime.close()
            daemonJob.join()
        }

        val history = checkNotNull(opened) { "the daemon opened a history" }
        assertFalse(history.flush(1_000), "the writer is stopped: the history was closed on the way out")
        assertEquals(listOf(Triple("download", "/a.txt", "success")), rows(), "and what was queued reached the file")
    }

    @Test
    fun `a history that cannot be opened leaves the daemon serving`() = runBlocking {
        seedColdFile("/a.txt", size = 5)
        // A folder where the file should be: SQLite cannot open it.
        Files.createDirectory(historyFile)
        val runtime = runtime(ProfileMode.MOUNT)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            assertEquals("""{"ok":true}""", exchange("""{"verb":"hydration.hydrate","path":"/a.txt"}"""), "the transfer itself works")
            val status = Json.parseToJsonElement(exchange("""{"verb":"daemon.status"}""")).jsonObject
            assertEquals("true", status["ok"].toString())
        } finally {
            runtime.close()
            daemonJob.join()
        }
        assertTrue(Files.isDirectory(historyFile), "nothing replaced the obstacle")
    }

    @Test
    fun `an open that throws is the same, whatever it throws`() = runBlocking {
        seedColdFile("/a.txt", size = 5)
        val runtime = runtime(ProfileMode.MOUNT) { _, _ -> throw java.sql.SQLException("disk I/O error") }
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            assertEquals("""{"ok":true}""", exchange("""{"verb":"hydration.hydrate","path":"/a.txt"}"""))
        } finally {
            runtime.close()
            daemonJob.join()
        }
        assertFalse(Files.exists(historyFile))
    }

    @Test
    fun `a mirror daemon keeps no history`() = runBlocking {
        var opens = 0
        val runtime = runtime(ProfileMode.MIRROR) { profile, file -> opens++; TransferHistory.open(profile, file) }
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            val status = Json.parseToJsonElement(exchange("""{"verb":"daemon.status"}""")).jsonObject
            assertEquals("true", status["ok"].toString())
        } finally {
            runtime.close()
            daemonJob.join()
        }
        assertEquals(0, opens)
        assertFalse(Files.exists(historyFile))
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

    /** Minimal provider: authenticated, serves five bytes for any download. */
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
        ): Long {
            Files.createDirectories(destination.parent)
            Files.write(destination, "hello".toByteArray())
            return 5L
        }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long = download(remotePath, destination)

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
