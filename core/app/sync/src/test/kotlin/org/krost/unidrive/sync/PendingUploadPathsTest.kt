package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// pendingUploadPaths() is the SQL form of SyncEntry.isPendingUpload: the two must name the same file rows
// (#552 moved its callers from filtering every row of state.db to this query).
class PendingUploadPathsTest {
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-pending").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(
        path: String,
        remoteId: String?,
        hydrated: Boolean,
        folder: Boolean = false,
        status: EntryStatus = EntryStatus.EXISTS,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 0,
        remoteModified = null,
        localMtime = 1_000,
        localSize = 1,
        isFolder = folder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.EPOCH,
        status = status,
    )

    @Test
    fun `pendingUploadPaths names exactly the file rows the engine calls pending uploads`() {
        db.upsertEntry(row("/pending-a.txt", null, hydrated = true))
        db.upsertEntry(row("/pending-b.txt", null, hydrated = true))
        db.upsertEntry(row("/sparse-partial.bin", null, hydrated = false))
        db.upsertEntry(row("/synced.txt", "id-synced", hydrated = true))
        db.upsertEntry(row("/cloud-only.txt", "id-cloud", hydrated = false))
        db.upsertEntry(row("/pending-dir", null, hydrated = true, folder = true))
        db.upsertEntry(row("/trashed.txt", null, hydrated = true, status = EntryStatus.TRASHED))

        val expected = db.getAllEntries().filter { !it.isFolder && it.isPendingUpload }.map { it.path }.sorted()

        assertEquals(listOf("/pending-a.txt", "/pending-b.txt"), expected, "the fixture holds two pending file rows")
        assertEquals(expected, db.pendingUploadPaths())
    }

    // #560: the query does not tell the two writers apart. A new file in the sync root gets a hydrated `local:`
    // row, which the query returns like a mount-written one; the mount's replay drops it only because it has no
    // hydration cache copy (HydrationImpl.replayable, pinned by ReplayPendingUploadsParityTest).
    @Test
    fun `a LocalScanner row for a new sync-root file is a pending upload path`() {
        val syncRoot = Files.createTempDirectory("unidrive-pending-root")
        Files.writeString(syncRoot.resolve("dropped.txt"), "from a backup")

        LocalScanner(syncRoot, db).scan()

        val row = db.getEntry("/dropped.txt")!!
        assertEquals(null, row.remoteId, "never uploaded")
        assertEquals(true, row.isHydrated, "the scanner records real bytes in the sync root")
        assertEquals(listOf("/dropped.txt"), db.pendingUploadPaths())
    }
}
