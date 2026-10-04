package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #552: the daemon's rescan only uploads, so its scan must not look for deletions: that checks every cloud-only
// row against the exclude patterns and the file system. The walk still finds new and changed files.
class LocalScannerDeletionDetectionTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-scan-nodel")
        db = StateDatabase(Files.createTempDirectory("unidrive-scan-nodel-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(
        path: String,
        mtime: Long?,
        size: Long?,
        hydrated: Boolean,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = "hash",
        remoteSize = size ?: 10,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = mtime,
        localSize = size,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
    )

    private fun fixture() {
        Files.writeString(syncRoot.resolve("same.txt"), "hello")
        Files.writeString(syncRoot.resolve("changed.txt"), "hello, longer now")
        Files.writeString(syncRoot.resolve("new.txt"), "brand new")
        db.upsertEntry(row("/same.txt", Files.getLastModifiedTime(syncRoot.resolve("same.txt")).toMillis(), 5, hydrated = true))
        db.upsertEntry(row("/changed.txt", 1_000L, 5, hydrated = true))
        for (i in 1..2_000) db.upsertEntry(row("/cloud/only-$i.bin", null, null, hydrated = false))
    }

    @Test
    fun `without deletion detection the cloud-only rows are not reported and new and changed files still are`() {
        fixture()

        val changes = LocalScanner(syncRoot, db).scan(detectDeletions = false)

        assertEquals(mapOf("/new.txt" to ChangeState.NEW, "/changed.txt" to ChangeState.MODIFIED), changes)
    }

    @Test
    fun `the default scan still reports the rows that are missing on disk as deleted`() {
        fixture()

        val changes = LocalScanner(syncRoot, db).scan()

        assertEquals(ChangeState.NEW, changes["/new.txt"])
        assertEquals(ChangeState.MODIFIED, changes["/changed.txt"])
        assertNull(changes["/same.txt"])
        assertEquals(2_000, changes.count { it.value == ChangeState.DELETED }, "every cloud-only row is gone from the disk")
    }

    @Test
    fun `without deletion detection a new file still gets its pending-upload row`() {
        Files.writeString(syncRoot.resolve("fresh.txt"), "x")

        LocalScanner(syncRoot, db).scan(detectDeletions = false)

        val row = assertNotNull(db.getEntry("/fresh.txt"))
        assertNull(row.remoteId)
        assertTrue(row.isPendingUpload)
    }

    @Test
    fun `without deletion detection a file in a known folder is compared with its row`() {
        Files.createDirectories(syncRoot.resolve("dir"))
        Files.writeString(syncRoot.resolve("dir/inner.txt"), "abc")
        val mtime = Files.getLastModifiedTime(syncRoot.resolve("dir/inner.txt")).toMillis()
        db.upsertEntry(row("/dir/inner.txt", mtime, 3, hydrated = true))
        db.upsertEntry(row("/dir", null, null, hydrated = true).copy(isFolder = true))

        assertEquals(emptyMap(), LocalScanner(syncRoot, db).scan(detectDeletions = false))
    }
}
