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
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `unidrive ls` (the live-query surface) must not contradict the FUSE mount (the
 * daemon's state.db view) during the stale window. The fix makes `ls` read the SAME
 * state.db view the mount serves (via hydration.list) when a daemon is running, so
 * the two surfaces are a single source of truth.
 */
class LsCommandTest {
    private lateinit var tempDir: Path
    private lateinit var lockFile: Path
    private lateinit var dbPath: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("ls-command-test")
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
    fun parses_hydration_list_reply_into_view_entries() {
        // Pin the wire-shape contract between hydration.list's reply (serialiseListEntries
        // in HydrationIpcHandler) and ls's parser. If the daemon's list reply shape drifts,
        // this fails — keeping ls's view consistent with the mount's view.
        val reply =
            """{"ok":true,"entries":[""" +
                """{"path":"/Docs","size":0,"mtime_ms":1000,"hydrated":false,"folder":true},""" +
                """{"path":"/a.txt","size":42,"mtime_ms":2000,"hydrated":true,"folder":false}""" +
                """]}"""
        val entries = LsCommand.parseListEntries(reply)
        assertEquals(2, entries.size, "both entries must parse")
        val folder = entries.single { it.isFolder }
        assertEquals("/Docs", folder.path)
        assertEquals(1000L, folder.mtimeMs)
        val file = entries.single { !it.isFolder }
        assertEquals("/a.txt", file.path)
        assertEquals(42L, file.size)
        assertEquals(2000L, file.mtimeMs)
    }

    @Test
    fun ls_agrees_with_mount_view() = runBlocking {
        // End-to-end: start a daemon serving a remote with two items, drive the
        // mount-view enumerate (via hydration.subscribe, the co-daemon's mount signal),
        // then assert ls's daemon-view query returns the SAME entries hydration.list
        // serves to the mount. Same socket, same state.db → the two can never disagree.
        val provider: CloudProvider = TwoRemoteFilesStubProvider()

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

        try {
            // Trigger the reactive enumerate so state.db is populated, then wait for
            // view.invalidated to confirm the enumerate completed.
            SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { sub ->
                sub.configureBlocking(false)
                sub.write(ByteBuffer.wrap(("""{"verb":"hydration.subscribe"}""" + "\n").toByteArray()))
                val collected = readUntil(sub, "view.invalidated", timeoutMs = 10_000)
                assertTrue(
                    collected.contains("view.invalidated"),
                    "enumerate must complete and push view.invalidated; got: $collected",
                )
            }

            // The mount-side view: query hydration.list directly.
            val mountReply =
                SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { ch ->
                    ch.write(ByteBuffer.wrap(("""{"verb":"hydration.list","prefix":""}""" + "\n").toByteArray()))
                    readOneLine(ch)
                }
            val mountView = LsCommand.parseListEntries(mountReply)
            assertTrue(mountView.isNotEmpty(), "mount view must be non-empty after enumerate; got: $mountReply")

            // The ls-side view: the exact code path `unidrive ls` takes when a daemon runs.
            val lsView = LsCommand().queryDaemonView(socketPath, "/")
            assertTrue(lsView != null, "ls must reach the daemon view")

            // Single source of truth: the two surfaces list identical paths.
            assertEquals(
                mountView.map { it.path }.toSortedSet(),
                lsView!!.map { it.path }.toSortedSet(),
                "ls must list exactly the paths the mount view serves (no stale-window disagreement)",
            )
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    private suspend fun readUntil(channel: SocketChannel, needle: String, timeoutMs: Long): String {
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

    private fun readOneLine(channel: SocketChannel): String {
        val collected = StringBuilder()
        while (!collected.contains('\n')) {
            val buf = ByteBuffer.allocate(4096)
            val n = channel.read(buf)
            if (n <= 0) return collected.toString()
            buf.flip()
            collected.append(String(buf.array(), 0, buf.limit()))
        }
        return collected.toString().substringBefore('\n')
    }

    /** Provider whose complete delta reports two remote files. */
    private class TwoRemoteFilesStubProvider : CloudProvider {
        override val id: String = "stub-two-files"
        override val displayName: String = "Stub (two remote files)"
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
                    file("alpha.txt", "/alpha.txt"),
                    file("beta.txt", "/beta.txt"),
                ),
                cursor = "cursor-1",
                hasMore = false,
            )

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)

        private fun file(name: String, path: String) =
            CloudItem(
                id = "id-$name",
                name = name,
                path = path,
                size = 5L,
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "h-$name",
                mimeType = "text/plain",
            )
    }
}
