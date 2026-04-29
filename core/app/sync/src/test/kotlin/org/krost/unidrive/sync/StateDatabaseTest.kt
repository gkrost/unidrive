package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class StateDatabaseTest {
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        val tmpDir = Files.createTempDirectory("unidrive-test")
        db = StateDatabase(tmpDir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun entry(
        path: String,
        isFolder: Boolean = false,
        remoteSize: Long = 100,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = "hash-$path",
        remoteSize = remoteSize,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = 1711627200000,
        localSize = remoteSize,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.now(),
    )

    @Test
    fun `upsert and get entry`() {
        val e = entry("/test.txt")
        db.upsertEntry(e)
        val loaded = db.getEntry("/test.txt")
        assertNotNull(loaded)
        assertEquals("/test.txt", loaded.path)
        assertEquals("id-/test.txt", loaded.remoteId)
        assertEquals(100L, loaded.remoteSize)
    }

    @Test
    fun `get nonexistent entry returns null`() {
        assertNull(db.getEntry("/nope"))
    }

    @Test
    fun `delete entry`() {
        db.upsertEntry(entry("/del.txt"))
        db.deleteEntry("/del.txt")
        assertNull(db.getEntry("/del.txt"))
    }

    @Test
    fun `get all entries`() {
        db.upsertEntry(entry("/a.txt"))
        db.upsertEntry(entry("/b.txt"))
        assertEquals(2, db.getAllEntries().size)
    }

    @Test
    fun `upsert overwrites existing`() {
        db.upsertEntry(entry("/test.txt", remoteSize = 100))
        db.upsertEntry(entry("/test.txt", remoteSize = 999))
        assertEquals(999, db.getEntry("/test.txt")!!.remoteSize)
    }

    @Test
    fun `get and set sync state`() {
        assertNull(db.getSyncState("delta_cursor"))
        db.setSyncState("delta_cursor", "abc123")
        assertEquals("abc123", db.getSyncState("delta_cursor"))
        db.setSyncState("delta_cursor", "def456")
        assertEquals("def456", db.getSyncState("delta_cursor"))
    }

    @Test
    fun `pin rules CRUD`() {
        db.addPinRule("Documents/**", pinned = true)
        db.addPinRule("*.tmp", pinned = false)
        val rules = db.getPinRules()
        assertEquals(2, rules.size)
        db.removePinRule("*.tmp")
        assertEquals(1, db.getPinRules().size)
    }

    @Test
    fun `case insensitive lookup`() {
        db.upsertEntry(entry("/Documents/Report.pdf"))
        val result = db.getEntryCaseInsensitive("/documents/report.pdf")
        assertNotNull(result)
        assertEquals("/Documents/Report.pdf", result.path)
    }

    @Test
    fun `entries by prefix`() {
        db.upsertEntry(entry("/docs/a.txt"))
        db.upsertEntry(entry("/docs/b.txt"))
        db.upsertEntry(entry("/other/c.txt"))
        val docs = db.getEntriesByPrefix("/docs/")
        assertEquals(2, docs.size)
    }

    @Test
    fun `rename prefix updates all matching paths`() {
        db.upsertEntry(entry("/old/a.txt"))
        db.upsertEntry(entry("/old/sub/b.txt"))
        db.upsertEntry(entry("/other/c.txt"))
        db.renamePrefix("/old/", "/new/")
        assertNull(db.getEntry("/old/a.txt"))
        assertNotNull(db.getEntry("/new/a.txt"))
        assertNotNull(db.getEntry("/new/sub/b.txt"))
        assertNotNull(db.getEntry("/other/c.txt"))
    }

    @Test
    fun `renamePrefix works without trailing slashes`() {
        db.upsertEntry(entry("/old/a.txt"))
        db.upsertEntry(entry("/old/sub/b.txt"))
        db.upsertEntry(entry("/other/c.txt"))
        db.renamePrefix("/old", "/new")
        assertNull(db.getEntry("/old/a.txt"))
        assertNull(db.getEntry("/old/sub/b.txt"))
        assertNotNull(db.getEntry("/new/a.txt"))
        assertNotNull(db.getEntry("/new/sub/b.txt"))
        assertNotNull(db.getEntry("/other/c.txt"))
    }

    @Test
    fun `renamePrefix with underscore in path does not match wildcard`() {
        db.upsertEntry(entry("/folder_a/file.txt"))
        db.upsertEntry(entry("/folderXa/other.txt"))
        db.renamePrefix("/folder_a", "/folder_b")
        assertNotNull(db.getEntry("/folder_b/file.txt"))
        assertNotNull(db.getEntry("/folderXa/other.txt"))
        assertNull(db.getEntry("/folder_a/file.txt"))
    }

    @Test
    fun `renamePrefix with percent in path does not match wildcard`() {
        db.upsertEntry(entry("/100%done/file.txt"))
        db.upsertEntry(entry("/100Xdone/other.txt"))
        db.renamePrefix("/100%done", "/finished")
        assertNotNull(db.getEntry("/finished/file.txt"))
        assertNotNull(db.getEntry("/100Xdone/other.txt"))
    }

    @Test
    fun `getEntriesByPrefix with underscore does not match wildcard`() {
        db.upsertEntry(entry("/data_2024/jan.csv"))
        db.upsertEntry(entry("/dataX2024/feb.csv"))
        val results = db.getEntriesByPrefix("/data_2024/")
        assertEquals(1, results.size)
        assertEquals("/data_2024/jan.csv", results[0].path)
    }

    @Test
    fun `getEntriesByPrefix with percent does not match wildcard`() {
        db.upsertEntry(entry("/50%off/sale.txt"))
        db.upsertEntry(entry("/50Xoff/nosale.txt"))
        val results = db.getEntriesByPrefix("/50%off/")
        assertEquals(1, results.size)
        assertEquals("/50%off/sale.txt", results[0].path)
    }

    @Test
    fun `repeated initialize does not leak connections`() {
        // First initialize already called in setUp
        // Call initialize again
        db.initialize()
        // Should not throw; ensure database still works
        db.upsertEntry(entry("/test.txt"))
        assertNotNull(db.getEntry("/test.txt"))
    }
}
