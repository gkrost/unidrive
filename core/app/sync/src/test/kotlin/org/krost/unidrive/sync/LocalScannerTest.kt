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
}
