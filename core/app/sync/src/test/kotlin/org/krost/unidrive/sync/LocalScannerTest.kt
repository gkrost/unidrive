package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class LocalScannerTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var scanner: LocalScanner

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-scan-test")
        val dbPath = Files.createTempDirectory("unidrive-scan-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        scanner = LocalScanner(syncRoot, db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun entry(
        path: String,
        mtime: Long,
        size: Long,
        isHydrated: Boolean = true,
    ) = SyncEntry(
        path = path,
        remoteId = "id",
        remoteHash = "hash",
        remoteSize = size,
        remoteModified = Instant.now(),
        localMtime = mtime,
        localSize = size,
        isFolder = false,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = Instant.now(),
    )

    @Test
    fun `detects new local file`() {
        Files.writeString(syncRoot.resolve("new.txt"), "hello")
        val changes = scanner.scan()
        assertEquals(1, changes.size)
        assertEquals(ChangeState.NEW, changes["/new.txt"])
    }

    @Test
    fun `detects modified local file`() {
        val file = syncRoot.resolve("mod.txt")
        Files.writeString(file, "original")
        val mtime = Files.getLastModifiedTime(file).toMillis()
        db.upsertEntry(entry("/mod.txt", mtime = mtime, size = Files.size(file)))

        // Modify the file
        Thread.sleep(50) // Ensure mtime changes
        Files.writeString(file, "modified content here")

        val changes = scanner.scan()
        assertEquals(ChangeState.MODIFIED, changes["/mod.txt"])
    }

    @Test
    fun `detects deleted local file`() {
        db.upsertEntry(entry("/gone.txt", mtime = 123, size = 10))
        // File does not exist on disk
        val changes = scanner.scan()
        assertEquals(ChangeState.DELETED, changes["/gone.txt"])
    }

    @Test
    fun `skips dehydrated files for modification check`() {
        val file = syncRoot.resolve("placeholder.txt")
        Files.writeString(file, "x")
        db.upsertEntry(entry("/placeholder.txt", mtime = 0, size = 100, isHydrated = false))

        val changes = scanner.scan()
        assertNull(changes["/placeholder.txt"])
    }

    @Test
    fun `detects deleted dehydrated file`() {
        db.upsertEntry(entry("/gone-placeholder.txt", mtime = 0, size = 100, isHydrated = false))
        val changes = scanner.scan()
        assertEquals(ChangeState.DELETED, changes["/gone-placeholder.txt"])
    }

    @Test
    fun `unchanged file produces no entry`() {
        val file = syncRoot.resolve("same.txt")
        Files.writeString(file, "same")
        val mtime = Files.getLastModifiedTime(file).toMillis()
        val size = Files.size(file)
        db.upsertEntry(entry("/same.txt", mtime = mtime, size = size))

        val changes = scanner.scan()
        assertNull(changes["/same.txt"])
    }

    @Test
    fun `scans nested directories`() {
        Files.createDirectories(syncRoot.resolve("a/b"))
        Files.writeString(syncRoot.resolve("a/b/deep.txt"), "deep")
        val changes = scanner.scan()
        assertEquals(ChangeState.NEW, changes["/a/b/deep.txt"])
    }

    @Test
    fun `excluded directory subtree is skipped`() {
        Files.createDirectories(syncRoot.resolve("node_modules/pkg/lib"))
        Files.writeString(syncRoot.resolve("node_modules/pkg/lib/index.js"), "code")
        Files.writeString(syncRoot.resolve("node_modules/pkg/package.json"), "{}")
        Files.writeString(syncRoot.resolve("keep.txt"), "keep")

        val scannerWithExclude = LocalScanner(syncRoot, db, excludePatterns = listOf("node_modules/**"))
        val changes = scannerWithExclude.scan()

        assertEquals(1, changes.size)
        assertEquals(ChangeState.NEW, changes["/keep.txt"])
        assertNull(changes["/node_modules/pkg/lib/index.js"])
        assertNull(changes["/node_modules/pkg/package.json"])
    }

    @Test
    fun `excluded file pattern skips matching files`() {
        Files.createDirectories(syncRoot.resolve("work"))
        Files.writeString(syncRoot.resolve("work/scratch.tmp"), "temp")
        Files.writeString(syncRoot.resolve("work/notes.txt"), "keep")

        val scannerWithExclude = LocalScanner(syncRoot, db, excludePatterns = listOf("*.tmp"))
        val changes = scannerWithExclude.scan()

        assertNull(changes["/work/scratch.tmp"])
        assertEquals(ChangeState.NEW, changes["/work/notes.txt"])
    }

    @Test
    fun `excluded deleted paths do not generate DELETED`() {
        db.upsertEntry(entry("/cache/old.tmp", mtime = 123, size = 10))

        val scannerWithExclude = LocalScanner(syncRoot, db, excludePatterns = listOf("*.tmp"))
        val changes = scannerWithExclude.scan()

        assertNull(changes["/cache/old.tmp"])
    }

    // UD-736 — visitFileFailed override tests.

    @Test
    fun `UD-736 lastScanSkipped is zero when all files visit cleanly`() {
        Files.writeString(syncRoot.resolve("a.txt"), "1")
        Files.writeString(syncRoot.resolve("b.txt"), "2")
        scanner.scan()
        assertEquals(0, scanner.lastScanSkipped)
    }

    // UD-742 — onProgress callback fires during long walks

    @Test
    fun `UD-742 scan with no callback does not throw and returns same map shape`() {
        Files.writeString(syncRoot.resolve("a.txt"), "1")
        Files.writeString(syncRoot.resolve("b.txt"), "2")
        val out = scanner.scan() // no callback overload still works
        assertEquals(2, out.size)
    }

    @Test
    fun `UD-742 scan fires onProgress at item-count threshold during walk`() {
        // Threshold inside scan() is 5000 items per fire. Generate enough files
        // for at least one heartbeat. Tempdir cleanup is fine — small files.
        val target = 5_500
        for (i in 0 until target) {
            Files.writeString(syncRoot.resolve("f$i.txt"), "x")
        }
        val seen = mutableListOf<Int>()
        scanner.scan { count -> seen.add(count) }
        // At least one mid-scan fire
        assertTrue(seen.isNotEmpty(), "expected at least one heartbeat fire for $target files")
        // Last fire should be <= total items visited
        assertTrue(
            seen.last() <= target,
            "heartbeat count ${seen.last()} should be <= total visited $target",
        )
    }

    @Test
    fun `UD-736 visitFileFailed continues walk and increments skipped count`() {
        // POSIX-only: make a subdirectory unreadable so walkFileTree's attempt
        // to enter it triggers the visitor's visitFileFailed callback. Windows
        // ACL semantics make this hard to set up portably, so guard the test.
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        org.junit.Assume.assumeFalse("POSIX-only test for visitFileFailed defence", isWindows)

        // Sibling files that should still be visited
        Files.writeString(syncRoot.resolve("ok-1.txt"), "1")
        Files.writeString(syncRoot.resolve("ok-2.txt"), "2")

        val unreadable = syncRoot.resolve("blocked")
        Files.createDirectory(unreadable)
        Files.writeString(unreadable.resolve("hidden.txt"), "x")
        // Strip read+execute permissions so walkFileTree fails to enter
        Files.setPosixFilePermissions(unreadable, emptySet())

        try {
            val changes = scanner.scan()
            // Both healthy siblings must still be reported NEW
            assertEquals(ChangeState.NEW, changes["/ok-1.txt"])
            assertEquals(ChangeState.NEW, changes["/ok-2.txt"])
            // The blocked directory raises visitFileFailed — at minimum once
            assertTrue(scanner.lastScanSkipped >= 1, "expected >=1 skipped, got ${scanner.lastScanSkipped}")
        } finally {
            // Restore permissions so @AfterTest cleanup can remove the tempdir
            Files.setPosixFilePermissions(
                unreadable,
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwx------"),
            )
        }
    }
}
