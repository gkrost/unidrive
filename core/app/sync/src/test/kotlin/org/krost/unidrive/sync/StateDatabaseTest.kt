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

    // ── UD-901c: renamePrefix tolerates pre-existing destination rows ───────
    // Pre-fix the UPDATE collided with SQLite's PK uniqueness when LocalScanner
    // had already written UD-901 pending rows at the destination prefix
    // (because the user moved a folder locally and the new path appeared as
    // NEW during local-scan). The action threw SQLITE_CONSTRAINT_PRIMARYKEY,
    // the engine logged WARN, and the DB was left half-moved — source rows
    // at old prefix, pending rows at new prefix — feeding a permanent failure
    // cascade. Captured live 2026-05-03 10:13.

    @Test
    fun `UD-901c renamePrefix tolerates pre-existing pending row at destination`() {
        // Source row carries the canonical metadata (remoteId, hash).
        db.upsertEntry(entry("/old/Sample/photo.jpg"))
        // Destination row is a UD-901 pending placeholder with remoteId=null —
        // exactly the shape LocalScanner writes when the new path appears NEW.
        db.upsertEntry(
            SyncEntry(
                path = "/new/Sample/photo.jpg",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 1711627200000,
                localSize = 100,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.EPOCH,
            ),
        )

        // Pre-fix this threw SQLITE_CONSTRAINT_PRIMARYKEY. Post-fix it succeeds.
        db.renamePrefix("/old/Sample", "/new/Sample")

        // Exactly one row at the destination, carrying the SOURCE's metadata
        // (the canonical remoteId), not the pending placeholder's null.
        val resolved = db.getEntry("/new/Sample/photo.jpg")
        assertNotNull(resolved, "destination row must exist after rename")
        assertEquals("id-/old/Sample/photo.jpg", resolved.remoteId)
        assertEquals("hash-/old/Sample/photo.jpg", resolved.remoteHash)
        // Source is gone.
        assertNull(db.getEntry("/old/Sample/photo.jpg"))
    }

    @Test
    fun `UD-901c renamePrefix wipes deeper destination descendants too`() {
        // Source has /old/Sample/sub/file.bin; destination already has
        // ./sub/file.bin AND ./other.bin (both pending). After rename the
        // destination should ONLY hold the source-rooted descendants.
        db.upsertEntry(entry("/old/Sample/sub/file.bin"))
        // Pending rows at destination — including a deeper one and an
        // unrelated leaf at the same level.
        db.upsertEntry(
            SyncEntry(
                path = "/new/Sample/sub/file.bin",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 0,
                localSize = 0,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.EPOCH,
            ),
        )
        db.upsertEntry(
            SyncEntry(
                path = "/new/Sample/other.bin",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 0,
                localSize = 0,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.EPOCH,
            ),
        )

        db.renamePrefix("/old/Sample", "/new/Sample")

        // Source-rooted file is now at the destination, with canonical metadata.
        val moved = db.getEntry("/new/Sample/sub/file.bin")
        assertNotNull(moved)
        assertEquals("id-/old/Sample/sub/file.bin", moved.remoteId)
        // Pre-existing destination-only sibling row is gone (was a pending
        // placeholder; canonical-source-rooted-rename wins).
        assertNull(db.getEntry("/new/Sample/other.bin"))
        // Source is gone.
        assertNull(db.getEntry("/old/Sample/sub/file.bin"))
    }

    @Test
    fun `UD-901c renamePrefix preserves rows outside both prefixes`() {
        db.upsertEntry(entry("/old/x.txt"))
        db.upsertEntry(entry("/new/y.txt")) // legitimate, non-colliding
        db.upsertEntry(entry("/totally-other/z.txt"))

        db.renamePrefix("/old", "/new")

        assertNotNull(db.getEntry("/new/x.txt"))
        // /new/y.txt is INSIDE the destination prefix, so it gets wiped along
        // with the rename's DELETE phase. This is the documented trade — the
        // post-rename destination subtree reflects what came from /old/.
        assertNull(db.getEntry("/new/y.txt"))
        // Wholly unrelated rows stay.
        assertNotNull(db.getEntry("/totally-other/z.txt"))
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

    // UD-738 — in-memory shadow DB for `--reset --dry-run`

    @Test
    fun `UD-738 inMemory true creates a working DB without touching dbPath on disk`() {
        val tmpDir = Files.createTempDirectory("unidrive-shadow")
        val sentinelPath = tmpDir.resolve("never-created.db")
        val shadow = StateDatabase(sentinelPath, inMemory = true)
        shadow.initialize()
        try {
            // The shadow DB schema is set up and writable
            shadow.upsertEntry(entry("/in-memory-only.txt"))
            assertNotNull(shadow.getEntry("/in-memory-only.txt"))
            // Critically: nothing was created at sentinelPath
            assertFalse(
                Files.exists(sentinelPath),
                "in-memory mode must NOT touch the file at dbPath",
            )
        } finally {
            shadow.close()
        }
    }

    @Test
    fun `UD-738 inMemory true is isolated from any real on-disk DB at the same path`() {
        // Real, file-backed DB with one entry
        val tmpDir = Files.createTempDirectory("unidrive-isolated")
        val realPath = tmpDir.resolve("state.db")
        val real = StateDatabase(realPath)
        real.initialize()
        real.upsertEntry(entry("/file-backed.txt"))
        real.close()

        // Open a shadow at the same path — must be empty, not see the real entry
        val shadow = StateDatabase(realPath, inMemory = true)
        shadow.initialize()
        try {
            assertNull(shadow.getEntry("/file-backed.txt"))
            assertEquals(0, shadow.getAllEntries().size)
        } finally {
            shadow.close()
        }

        // Real DB on disk still has its entry
        val realAgain = StateDatabase(realPath)
        realAgain.initialize()
        try {
            assertNotNull(realAgain.getEntry("/file-backed.txt"))
        } finally {
            realAgain.close()
        }
    }
}
