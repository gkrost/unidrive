package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirror drift on the `localfs` provider: the "cloud" is a plain directory, so a remote change is a plain file
 * operation on it, and the real mirror engine (`SyncEngine`) runs against it in-process, with no account and no
 * network. Each test starts from the same converged tree, changes either side (or both) behind the engine's back,
 * syncs, and then asserts everything the user could look at: the local tree, the remote tree and the rows of
 * state.db all agree on the expected tree, and a second sync plans nothing and touches nothing.
 *
 * The expectations are about the end state, not about how the engine got there (a folder rename may be one move or
 * a delete and a create); only the end state is the contract.
 */
class LocalfsMirrorDriftTest {
    private lateinit var base: Path
    private lateinit var local: Path
    private lateinit var remote: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private val reporter = CountingReporter()

    // File times only ever move forward, ten seconds per write, so an edit differs from its predecessor in time as
    // well as in size and no test depends on how fast the machine is.
    private var stamp = System.currentTimeMillis() - 86_400_000L

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-mirror-drift")
        local = Files.createDirectories(base.resolve("local"))
        remote = Files.createDirectories(base.resolve("remote"))
        val profileDir = Files.createDirectories(base.resolve("profile"))
        db = StateDatabase(profileDir.resolve("state.db")).also { it.initialize() }
        val provider = LocalFsProvider(remote)
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = local,
                reporter = reporter,
                cacheRoot = Files.createDirectories(base.resolve("cache")),
            )
        runBlocking { provider.authenticate() }
        for ((path, content) in START) put(remote, path, content)
        sync()
        assertConverged(START, "the first sync downloads the remote tree")
    }

    @AfterTest
    fun tearDown() {
        runCatching { db.close() }
        runCatching { base.toFile().deleteRecursively() }
    }

    // ── one kind of change at a time ─────────────────────────────────────────

    @Test
    fun `a local edit and a local new file reach the remote`() {
        put(local, "keep.txt", "keep v2, longer than v1")
        put(local, "docs/new-here.txt", "created locally")

        sync()

        assertConverged(START + mapOf("keep.txt" to "keep v2, longer than v1", "docs/new-here.txt" to "created locally"))
    }

    @Test
    fun `a remote edit and a remote new file reach the local tree`() {
        put(remote, "notes/n1.txt", "note one, edited in the cloud")
        put(remote, "notes/new-there.txt", "created in the cloud")

        sync()

        assertConverged(START + mapOf("notes/n1.txt" to "note one, edited in the cloud", "notes/new-there.txt" to "created in the cloud"))
    }

    @Test
    fun `a local delete and a remote delete reach the other side`() {
        Files.delete(local.resolve("docs/b.txt"))
        Files.delete(remote.resolve("notes/n2.txt"))

        sync()

        assertConverged(START - "docs/b.txt" - "notes/n2.txt")
    }

    @Test
    fun `a local folder rename carries the folder's files to the remote under the new name`() {
        Files.move(local.resolve("photos"), local.resolve("pictures"))

        sync()

        assertConverged(START.renamedFolder("photos", "pictures"))
    }

    @Test
    fun `a remote file rename and a remote move into another folder reach the local tree`() {
        Files.move(remote.resolve("notes/n2.txt"), remote.resolve("notes/n2-renamed.txt"))
        Files.move(remote.resolve("docs/a.txt"), remote.resolve("notes/a-moved.txt"))

        sync()

        assertConverged(
            START - "notes/n2.txt" - "docs/a.txt" +
                mapOf("notes/n2-renamed.txt" to "note two", "notes/a-moved.txt" to "alpha"),
        )
    }

    @Test
    fun `a remote folder rename and a remote move of a whole folder reach the local tree`() {
        Files.move(remote.resolve("photos"), remote.resolve("pictures"))
        Files.move(remote.resolve("docs/sub"), remote.resolve("notes/sub"))

        sync()

        assertConverged(START.renamedFolder("photos", "pictures").renamedFolder("docs/sub", "notes/sub"))
    }

    // ── both sides drift at once ─────────────────────────────────────────────

    @Test
    fun `local edit new file delete and folder rename together with remote edit new file rename and move`() {
        // the local side
        put(local, "keep.txt", "keep v2, longer than v1")
        put(local, "local-new.txt", "created locally")
        Files.delete(local.resolve("docs/b.txt"))
        Files.move(local.resolve("photos"), local.resolve("pictures"))
        // the remote side, on other files
        put(remote, "notes/n1.txt", "note one, edited in the cloud")
        put(remote, "remote-new.txt", "created in the cloud")
        Files.move(remote.resolve("notes/n2.txt"), remote.resolve("notes/n2-renamed.txt"))
        Files.move(remote.resolve("docs/a.txt"), remote.resolve("notes/a-moved.txt"))

        sync()

        assertConverged(
            START.renamedFolder("photos", "pictures") - "docs/b.txt" - "notes/n2.txt" - "docs/a.txt" +
                mapOf(
                    "keep.txt" to "keep v2, longer than v1",
                    "local-new.txt" to "created locally",
                    "notes/n1.txt" to "note one, edited in the cloud",
                    "remote-new.txt" to "created in the cloud",
                    "notes/n2-renamed.txt" to "note two",
                    "notes/a-moved.txt" to "alpha",
                ),
        )
    }

    @Test
    fun `the same file edited on both sides keeps both versions and converges`() {
        put(local, "keep.txt", "keep, edited here")
        put(remote, "keep.txt", "keep, edited there, a little longer")

        sync()

        val localTree = tree(local)
        val remoteTree = tree(remote)
        assertEquals(localTree, remoteTree, "both sides hold the same tree after the conflict")
        val contents = localTree.filterKeys { it.startsWith("keep") }.values
        assertTrue("keep, edited here" in contents, "the local edit survives somewhere: $localTree")
        assertTrue("keep, edited there, a little longer" in contents, "the remote edit survives somewhere: $localTree")
        assertQuiet("the conflict has been resolved once")
    }

    // ── the assertions ───────────────────────────────────────────────────────

    /**
     * Local tree, remote tree and the rows of state.db all agree on [expected], and a second sync plans nothing and
     * changes nothing.
     */
    private fun assertConverged(
        expected: Map<String, String>,
        why: String = "",
    ) {
        assertState(expected, why)
        assertQuiet(why)
    }

    private fun assertState(
        expected: Map<String, String>,
        why: String,
    ) {
        val files = expected.toSortedMap()
        assertEquals(withFolders(files), tree(local), "local tree $why")
        assertEquals(withFolders(files), tree(remote), "remote tree $why")

        val rows = db.getAllEntries().associateBy { it.path.trimStart('/') }
        assertEquals(withFolders(files).keys.map { it.trimEnd('/') }.toSortedSet(), rows.keys.toSortedSet(), "state.db rows $why")
        for ((path, content) in files) {
            val row = rows.getValue(path)
            assertTrue(!row.isFolder && row.isHydrated, "row of $path is a hydrated file: $row")
            assertEquals(content.toByteArray().size.toLong(), row.localSize, "row of $path records the size of the bytes on disk")
            assertTrue(row.remoteId != null, "row of $path carries a remote id: $row")
        }
        for (folder in withFolders(files).keys.filter { it.endsWith("/") }) {
            assertTrue(rows.getValue(folder.trimEnd('/')).isFolder, "row of $folder is a folder")
        }
    }

    /** One more sync with nothing changed: no action planned, nothing failed, both trees and the rows as they were. */
    private fun assertQuiet(why: String = "") {
        val localBefore = tree(local)
        val remoteBefore = tree(remote)
        val rowsBefore = db.getAllEntries().map { it.path to it.remoteId }.sortedBy { it.first }

        sync()

        assertEquals(0, reporter.planned, "a second sync plans nothing $why")
        assertEquals(0, reporter.failed, "a second sync fails nothing $why")
        assertEquals(localBefore, tree(local), "a second sync leaves the local tree alone $why")
        assertEquals(remoteBefore, tree(remote), "a second sync leaves the remote tree alone $why")
        assertEquals(rowsBefore, db.getAllEntries().map { it.path to it.remoteId }.sortedBy { it.first }, "and the rows $why")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun sync() {
        reporter.reset()
        runBlocking { engine.syncOnce() }
        assertEquals(0, reporter.failed, "no action failed; warnings: ${reporter.warnings}")
    }

    private fun put(
        root: Path,
        rel: String,
        content: String,
    ) {
        val file = root.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        stamp += 10_000
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp))
    }

    /** Relative path to content for every file, and `<folder>/` to the empty string for every folder, sorted. */
    private fun tree(root: Path): Map<String, String> =
        Files.walk(root).use { stream ->
            stream
                .filter { it != root }
                .map { path ->
                    val rel = root.relativize(path).joinToString("/")
                    if (Files.isDirectory(path)) "$rel/" to "" else rel to Files.readString(path)
                }.toList()
        }.toMap().toSortedMap()

    // Expected files plus the folders above them.
    private fun withFolders(files: Map<String, String>): Map<String, String> {
        val all = files.toMutableMap()
        for (path in files.keys) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                all["$parent/"] = ""
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return all.toSortedMap()
    }

    private fun Map<String, String>.renamedFolder(
        from: String,
        to: String,
    ): Map<String, String> = mapKeys { (path, _) -> if (path.startsWith("$from/")) to + path.removePrefix(from) else path }

    private class CountingReporter : ProgressReporter {
        @Volatile var planned = 0

        @Volatile var failed = 0
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun reset() {
            planned = 0
            failed = 0
            warnings.clear()
        }

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {
            planned = total
        }

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {}

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {
            this.failed = failed
        }

        override fun onWarning(message: String) {
            warnings += message
        }
    }

    companion object {
        // The converged tree every test starts from (relative path to content).
        private val START =
            mapOf(
                "keep.txt" to "keep v1",
                "docs/a.txt" to "alpha",
                "docs/b.txt" to "bravo",
                "docs/sub/c.txt" to "charlie",
                "photos/p1.bin" to "p1 bytes",
                "photos/p2.bin" to "p2 bytes",
                "notes/n1.txt" to "note one",
                "notes/n2.txt" to "note two",
            )
    }
}
