package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #504: a daemon that serves a mount never scanned the sync root, so a file that reached it by any
 * other route (copied in, dropped while the daemon was down, restored from a backup) was neither
 * uploaded nor listed until a one-shot `unidrive sync`. The daemon now runs an upload-only rescan at
 * start and then on a timer.
 */
class DaemonSyncRootRescanTest {
    private lateinit var tempDir: Path
    private lateinit var syncRoot: Path
    private lateinit var lockFile: Path
    private lateinit var dbPath: Path
    private lateinit var socketPath: Path
    private val provider = RecordingProvider()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-rescan-test")
        syncRoot = Files.createDirectories(tempDir.resolve("root"))
        lockFile = tempDir.resolve(".lock")
        dbPath = tempDir.resolve("state.db")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private fun runtime(rescanIntervalMs: Long) =
        DaemonRuntime(
            profileName = "rescan_test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = syncRoot,
            socketPath = socketPath,
            providerFactory = { provider },
            syncRootRescanIntervalMs = rescanIntervalMs,
        )

    private suspend fun awaitSocket() {
        repeat(100) {
            if (Files.exists(socketPath)) return
            delay(50)
        }
        check(Files.exists(socketPath)) { "socket must be bound within 5s" }
    }

    private suspend fun awaitUploads(
        count: Int,
        timeoutMs: Long = 15_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && provider.uploads.size < count) delay(50)
    }

    private fun send(request: String): String {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "rescan_test_profile"), IpcAuth.Scope.READ)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            val collected = StringBuilder()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
                val buf = ByteBuffer.allocate(8192)
                val n = channel.read(buf)
                if (n > 0) {
                    buf.flip()
                    collected.append(String(buf.array(), 0, buf.limit()))
                } else {
                    Thread.sleep(20)
                }
            }
            return collected.toString().substringBefore('\n')
        } finally {
            channel.close()
        }
    }

    @Test
    fun `a file already in the sync root when the daemon starts is uploaded by the start pass`() =
        runBlocking {
            Files.writeString(syncRoot.resolve("dropped-while-down.txt"), "hello")
            val rt = runtime(rescanIntervalMs = 3_600_000) // the timer never fires: only the start pass can do it
            val job = launch { rt.start() }
            try {
                awaitSocket()
                awaitUploads(1)
                assertEquals(listOf("/dropped-while-down.txt"), provider.uploads.toList())
                val list = send("""{"verb":"hydration.list","prefix":"/"}""")
                assertTrue(list.contains("dropped-while-down.txt"), "the mount lists it from state.db; got: $list")
            } finally {
                rt.close()
                job.join()
            }
        }

    @Test
    fun `a file copied into the sync root while the daemon runs is uploaded within one interval`() =
        runBlocking {
            val rt = runtime(rescanIntervalMs = 300)
            val job = launch { rt.start() }
            try {
                awaitSocket()
                Files.writeString(syncRoot.resolve("copied-in.txt"), "bytes")
                awaitUploads(1)
                assertEquals(listOf("/copied-in.txt"), provider.uploads.toList())
                assertTrue(provider.downloads.isEmpty() && provider.deletes.isEmpty(), "upload-only")
            } finally {
                rt.close()
                job.join()
            }
        }

    @Test
    fun `an interval of zero turns the rescan off`() =
        runBlocking {
            Files.writeString(syncRoot.resolve("stays-put.txt"), "x")
            val rt = runtime(rescanIntervalMs = 0)
            val job = launch { rt.start() }
            try {
                awaitSocket()
                delay(1_500)
                assertTrue(provider.uploads.isEmpty(), "no rescan, no upload; got ${provider.uploads}")
            } finally {
                rt.close()
                job.join()
            }
        }

    private class RecordingProvider : CloudProvider {
        override val id: String = "rescan-stub"
        override val displayName: String = "Rescan stub"
        override var isAuthenticated: Boolean = true
        val uploads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val downloads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val deletes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            downloads.add(remotePath)
            error("a rescan must not download")
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploads.add(remotePath)
            return CloudItem(
                id = "id-$remotePath",
                name = remotePath.substringAfterLast('/'),
                path = remotePath,
                size = Files.size(localPath),
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "h",
                mimeType = null,
            )
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            deletes.add(remotePath)
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}
