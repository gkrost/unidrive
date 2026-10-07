package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `resetKeepingPending` is `resetAll` for a profile whose mount may hold writes that have not reached
 * the cloud: those rows stay, everything else goes as before. Real database files, no fakes.
 */
class ResetKeepingPendingTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-reset-keep")
        dbFile = dir.resolve("state.db")
        db = StateDatabase(dbFile).also { it.initialize() }
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
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 3,
        remoteModified = null,
        localMtime = 1_000,
        localSize = 3,
        isFolder = folder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.EPOCH,
    )

    /** A file written through the mount whose upload has not landed: no cloud id yet, real bytes. */
    private fun pending(path: String) = row(path, remoteId = null, hydrated = true)

    private fun synced(path: String) = row(path, remoteId = "id-$path", hydrated = true)

    private fun allRows(): List<String> = db.recovery.allEntriesAnyStatus().map { it.path }.sorted()

    @Test
    fun `rows that await upload stay and every other row goes`() {
        db.upsertEntry(pending("/new-a.txt"))
        db.upsertEntry(pending("/dir/new-b.txt"))
        db.upsertEntry(synced("/synced.txt"))
        db.upsertEntry(row("/cloud-only.txt", "id-cloud", hydrated = false))
        db.upsertEntry(row("/sparse-partial.bin", null, hydrated = false))
        db.upsertEntry(row("/dir", "id-dir", hydrated = true, folder = true))
        db.upsertEntry(synced("/was-deleted.txt"))
        db.markDeleted("/was-deleted.txt")

        val kept = db.resetKeepingPending()

        assertEquals(2, kept, "the two pending file rows")
        assertEquals(listOf("/dir/new-b.txt", "/new-a.txt"), allRows(), "nothing else, tombstones included")
        assertEquals(listOf("/dir/new-b.txt", "/new-a.txt"), db.pendingUploadPaths())
    }

    @Test
    fun `a kept row is exactly as it was, error stamp and refusal included`() {
        db.upsertEntry(pending("/new.txt"))
        db.markUploadFailed("/new.txt", Instant.parse("2026-01-02T03:04:05Z"))
        db.markUploadRefused("/new.txt", "1000|3|too large")
        val before = db.getEntry("/new.txt")!!

        db.resetKeepingPending()

        assertEquals(before, db.getEntry("/new.txt"))
        assertEquals("1000|3|too large", db.uploadRefusal("/new.txt"))
        assertNotNull(db.getEntry("/new.txt")!!.lastErrorAt)
    }

    @Test
    fun `sync_state is cleared as before and the schema stamp survives a reopen with the kept row`() {
        db.upsertEntry(pending("/new.txt"))
        db.setSyncState("delta_cursor", "old-cursor")
        db.setSyncState("sync_root", "/somewhere")

        db.resetKeepingPending()

        assertNull(db.getSyncState("delta_cursor"))
        assertNull(db.getSyncState("sync_root"))
        assertEquals(StateDatabase.SCHEMA_VERSION.toString(), db.getSyncState(StateDatabase.SCHEMA_VERSION_KEY))
        db.close()

        val reopened = StateDatabase(dbFile).also { it.initialize() }
        try {
            assertNotNull(reopened.getEntry("/new.txt"), "a reset database must not be read as pre-redesign")
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `with no pending row it clears the database like resetAll`() {
        db.upsertEntry(synced("/a.txt"))
        db.upsertEntry(row("/dir", "id-dir", hydrated = true, folder = true))
        db.setSyncState("delta_cursor", "c")

        assertEquals(0, db.resetKeepingPending())

        assertEquals(emptyList(), allRows())
        assertNull(db.getSyncState("delta_cursor"))
        assertEquals(StateDatabase.SCHEMA_VERSION.toString(), db.getSyncState(StateDatabase.SCHEMA_VERSION_KEY))
    }

    @Test
    fun `alsoKeep names further rows to keep and is asked about neither pending rows nor folders`() {
        db.upsertEntry(pending("/new.txt"))
        db.upsertEntry(synced("/edited.txt"))
        db.upsertEntry(synced("/clean.txt"))
        db.upsertEntry(row("/dir", "id-dir", hydrated = true, folder = true))
        val asked = mutableListOf<String>()

        val kept =
            db.resetKeepingPending { candidate ->
                asked += candidate.path
                candidate.path == "/edited.txt"
            }

        assertEquals(2, kept)
        assertEquals(listOf("/clean.txt", "/edited.txt"), asked.sorted(), "file rows other than the pending ones")
        assertEquals(listOf("/edited.txt", "/new.txt"), allRows())
        assertEquals("id-/edited.txt", db.getEntry("/edited.txt")!!.remoteId, "a kept row keeps its cloud id")
    }

    @Test
    fun `a failure while clearing leaves rows and sync_state exactly as they were`() {
        db.upsertEntry(pending("/new.txt"))
        db.upsertEntry(synced("/synced.txt"))
        db.setSyncState("delta_cursor", "old-cursor")
        // The rows are deleted first, sync_state second: make the second step fail.
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { other ->
            other.createStatement().use {
                it.executeUpdate(
                    "CREATE TRIGGER refuse_state_wipe BEFORE DELETE ON sync_state " +
                        "BEGIN SELECT RAISE(ABORT, 'refused for the test'); END",
                )
            }
        }

        assertFailsWith<Exception> { db.resetKeepingPending() }

        assertEquals(listOf("/new.txt", "/synced.txt"), allRows(), "the row delete was rolled back")
        assertEquals("old-cursor", db.getSyncState("delta_cursor"))
        assertEquals(StateDatabase.SCHEMA_VERSION.toString(), db.getSyncState(StateDatabase.SCHEMA_VERSION_KEY))
        // And the database is still usable: no transaction left open.
        db.upsertEntry(synced("/after.txt"))
        assertNotNull(db.getEntry("/after.txt"))
    }

    @Test
    fun `a throwing alsoKeep changes nothing`() {
        db.upsertEntry(pending("/new.txt"))
        db.upsertEntry(synced("/a.txt"))
        db.upsertEntry(synced("/b.txt"))
        db.setSyncState("delta_cursor", "old-cursor")

        assertFailsWith<IllegalStateException> {
            db.resetKeepingPending { if (it.path == "/b.txt") error("cannot tell") else false }
        }

        assertEquals(listOf("/a.txt", "/b.txt", "/new.txt"), allRows())
        assertEquals("old-cursor", db.getSyncState("delta_cursor"))
        db.upsertEntry(synced("/after.txt"))
        assertTrue(db.getEntry("/after.txt") != null, "the database is usable afterwards")
    }

    @Test
    fun `a reset that failed can be run again once the cause is gone`() {
        db.upsertEntry(pending("/new.txt"))
        db.upsertEntry(synced("/synced.txt"))
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { other ->
            other.createStatement().use {
                it.executeUpdate(
                    "CREATE TRIGGER refuse_state_wipe BEFORE DELETE ON sync_state " +
                        "BEGIN SELECT RAISE(ABORT, 'refused for the test'); END",
                )
            }
        }
        assertFailsWith<Exception> { db.resetKeepingPending() }
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { other ->
            other.createStatement().use { it.executeUpdate("DROP TRIGGER refuse_state_wipe") }
        }

        assertEquals(1, db.resetKeepingPending())

        assertEquals(listOf("/new.txt"), allRows())
    }

    @Test
    fun `more kept rows than one statement can bind are all kept`() {
        // 35,000 rows to keep: more ids than an IN list of bound parameters holds (SQLite allows 32,766 by default).
        db.batch { repeat(40_000) { db.upsertEntry(synced("/s/$it.txt")) } }
        db.upsertEntry(pending("/new.txt"))

        val kept = db.resetKeepingPending { it.path.removePrefix("/s/").removeSuffix(".txt").toInt() % 8 != 0 }

        assertEquals(35_000 + 1, kept)
        assertEquals(35_000 + 1, db.getEntryCount())
        assertEquals(EntryStatus.EXISTS, db.getEntry("/s/7.txt")!!.status)
        assertNull(db.getEntry("/s/8.txt"))
        assertNotNull(db.getEntry("/new.txt"))
    }
}
