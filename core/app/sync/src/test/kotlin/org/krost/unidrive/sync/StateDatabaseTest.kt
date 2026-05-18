package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Duration
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

    // ── state.db redesign acceptance fixtures ───────────────────────────────

    @Test
    fun `setStatusTrashed is an idempotent UPDATE keyed by remote_id`() {
        db.upsertEntry(entry("/a.txt").copy(remoteId = "uuid-A"))
        assertTrue(db.setStatusTrashed("uuid-A"), "first flip returns true")
        assertNull(db.getEntry("/a.txt"), "alive view no longer sees trashed row")
        // Second flip is a no-op: zero rows where status=EXISTS for this id.
        assertFalse(db.setStatusTrashed("uuid-A"), "second flip is idempotent")
        // The trashed row is reachable via Recovery.
        val trashed = db.recovery.trashedEntries()
        assertEquals(1, trashed.size)
        assertEquals("uuid-A", trashed.single().remoteId)
        assertEquals(EntryStatus.TRASHED, trashed.single().status)
    }

    @Test
    fun `setStatusTrashed on synthetic id is a no-op`() {
        // Pending-upload rows carry remote_id=null in Kotlin (a `local:<uuid>`
        // synthetic at the SQL layer). Asking to "trash" them via the public
        // remote_id surface is meaningless — they never reached the cloud.
        assertFalse(db.setStatusTrashed("local:not-a-real-id"))
    }

    @Test
    fun `trash-then-recreate at same path is INSERT-clean`() {
        // Real cloud row at /foo/bar.txt.
        val original = entry("/foo/bar.txt").copy(remoteId = "uuid-old", remoteHash = "old-hash")
        db.upsertEntry(original)

        // Cloud deletes it → flip to TRASHED. Row survives as tombstone.
        assertTrue(db.setStatusTrashed("uuid-old"))
        assertNull(db.getEntry("/foo/bar.txt"), "alive view no longer sees trashed row")

        // New file with different UUID arrives at the same path. Pre-redesign
        // this collided on the path PK; with the partial unique on path
        // `WHERE status='EXISTS'` it inserts cleanly.
        val replacement = entry("/foo/bar.txt").copy(remoteId = "uuid-new", remoteHash = "new-hash")
        db.upsertEntry(replacement)

        // Both rows coexist; only the EXISTS row is reachable via alive view.
        val live = db.getEntry("/foo/bar.txt")
        assertNotNull(live)
        assertEquals("uuid-new", live.remoteId)
        assertEquals("new-hash", live.remoteHash)

        val all = db.recovery.allEntriesAnyStatus().sortedBy { it.remoteId }
        assertEquals(2, all.size, "TRASHED and EXISTS both persist")
        assertEquals(EntryStatus.TRASHED, all.first { it.remoteId == "uuid-old" }.status)
        assertEquals(EntryStatus.EXISTS, all.first { it.remoteId == "uuid-new" }.status)
    }

    @Test
    fun `rename regression pin — safe to tresor 14 descendants resolve via single UPDATE`() {
        // Originating incident: a `.safe → .tresor` folder rename applied to
        // the parent row but not to 14 descendant rows. Pre-redesign the
        // descendants stayed at the old prefix because path-as-PK forced an
        // N-row UPDATE that hit edge cases.
        db.upsertEntry(entry("/Documents/.safe", isFolder = true).copy(remoteId = "uuid-parent"))
        repeat(14) { i ->
            db.upsertEntry(
                entry("/Documents/.safe/file$i.txt").copy(remoteId = "uuid-child-$i"),
            )
        }
        db.renamePrefix("/Documents/.safe", "/Documents/.tresor")

        // Parent row moved.
        assertNull(db.getEntry("/Documents/.safe"))
        assertNotNull(db.getEntry("/Documents/.tresor"))

        // All 14 descendants moved with it; none stranded under the old prefix.
        repeat(14) { i ->
            assertNull(db.getEntry("/Documents/.safe/file$i.txt"), "child $i must not remain at old prefix")
            val moved = db.getEntry("/Documents/.tresor/file$i.txt")
            assertNotNull(moved, "child $i must appear at new prefix")
            assertEquals("uuid-child-$i", moved.remoteId, "remote_id is rename-stable")
        }
    }

    @Test
    fun `rename perf — 1000 descendants folder rename touches each row exactly once`() {
        // The acceptance criterion is structural — renaming a folder with N
        // descendants is N UPDATEs to the path column. We sanity-check at
        // 1000 by asserting all 1000 land at the new prefix and none remain
        // at the old prefix (the partial unique index would refuse a
        // duplicate alive row, so this also confirms the UPDATEs didn't
        // create conflicts).
        db.upsertEntry(entry("/big", isFolder = true).copy(remoteId = "uuid-big"))
        for (i in 0 until 1000) {
            db.upsertEntry(entry("/big/file$i.txt").copy(remoteId = "uuid-f-$i"))
        }
        assertEquals(1001, db.getAllEntries().size)

        db.renamePrefix("/big", "/huge")

        // Every descendant moved.
        assertEquals(1001, db.getAllEntries().size, "row count unchanged")
        for (i in 0 until 1000) {
            assertNull(db.getEntry("/big/file$i.txt"))
            assertNotNull(db.getEntry("/huge/file$i.txt"))
        }
        // The folder root row moved too.
        assertNull(db.getEntry("/big"))
        assertNotNull(db.getEntry("/huge"))
    }

    @Test
    fun `EXPLAIN QUERY PLAN for alive children of parent_uuid is an index seek`() {
        // Acceptance criterion: the composite (parent_uuid, status) index is
        // structurally present AND the planner will reach for it on the
        // canonical "alive children of X" query. We assert both: the index
        // appears in sqlite_master, and EXPLAIN QUERY PLAN on the canonical
        // query reports `USING INDEX` (an index seek) rather than `SCAN
        // sync_entries` (a tombstone-skipping table scan).
        //
        // Note: do NOT run ANALYZE here. After ANALYZE on a tiny table the
        // planner correctly decides a full scan is cheaper than an index
        // seek, which is fine in production (the index doesn't bloat much)
        // but defeats the structural assertion this test is making.
        db.upsertEntry(entry("/p", isFolder = true).copy(remoteId = "uuid-P"))
        for (i in 0 until 20) {
            db.upsertEntry(
                entry("/p/file$i.txt").copy(remoteId = "uuid-PC-$i", parentUuid = "uuid-P"),
            )
        }

        val dbPath = db.javaClass.getDeclaredField("dbPath")
            .apply { isAccessible = true }.get(db) as java.nio.file.Path
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            // Index existence.
            val indexNames = mutableListOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='sync_entries'",
                )
                while (rs.next()) indexNames.add(rs.getString(1))
            }
            assertTrue(
                "idx_sync_entries_parent_alive" in indexNames,
                "composite (parent_uuid, status) index must exist; got: $indexNames",
            )
            // Plan check.
            val plan = StringBuilder()
            conn.prepareStatement(
                "EXPLAIN QUERY PLAN " +
                    "SELECT * FROM sync_entries WHERE parent_uuid=? AND status='EXISTS'",
            ).use { stmt ->
                stmt.setString(1, "uuid-P")
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    plan.append(rs.getString("detail")).append("\n")
                }
            }
            val planText = plan.toString()
            assertTrue(
                "USING INDEX" in planText && "SCAN sync_entries" !in planText,
                "expected index seek for (parent_uuid, status); got plan:\n$planText",
            )
        }
    }

    @Test
    fun `recovery namespace is the only path to TRASHED rows`() {
        db.upsertEntry(entry("/alive.txt").copy(remoteId = "uuid-alive"))
        db.upsertEntry(entry("/trashed.txt").copy(remoteId = "uuid-trashed"))
        db.setStatusTrashed("uuid-trashed")

        // Every public StateDatabase read excludes TRASHED rows.
        assertEquals(listOf("/alive.txt"), db.getAllEntries().map { it.path })
        assertNull(db.getEntry("/trashed.txt"))
        assertNull(db.getEntryByRemoteId("uuid-trashed"))
        assertEquals(1, db.getEntryCount())
        assertEquals(listOf("/alive.txt"), db.getEntriesByPrefix("/").map { it.path })

        // Recovery sees them.
        assertEquals(
            listOf("uuid-trashed"),
            db.recovery.trashedEntries().mapNotNull { it.remoteId },
        )
        assertNotNull(db.recovery.getEntryByRemoteIdAnyStatus("uuid-trashed"))
        val all = db.recovery.allEntriesAnyStatus().mapNotNull { it.remoteId }.sorted()
        assertEquals(listOf("uuid-alive", "uuid-trashed"), all)
    }

    @Test
    fun `recovery scenario — 100-item mass-delete is one SELECT plus one batched untrash`() {
        // Synthesise a 100-item mass-delete: 100 rows trashed.
        for (i in 0 until 100) {
            db.upsertEntry(entry("/bulk/item$i.txt").copy(remoteId = "uuid-bulk-$i"))
        }
        for (i in 0 until 100) db.setStatusTrashed("uuid-bulk-$i")
        // Nothing alive.
        assertEquals(0, db.getEntryCount())

        // The recovery operator does: SELECT what's TRASHED → batched PATCH.
        val trashed = db.recovery.trashedEntries()
        assertEquals(100, trashed.size, "one SELECT recovers the whole set")
        // No audit-log archaeology, no cloud-trash listing — the (remote_id,
        // parent_uuid) pairs are right here on the row.
        val ids = trashed.mapNotNull { it.remoteId }.sorted()
        assertEquals((0 until 100).map { "uuid-bulk-$it" }.sorted(), ids)

        // Untrash: one batched flip restores everything (in real life this
        // is paired with a cloud-side restore PATCH; here we just exercise
        // the DB primitive).
        db.batch { ids.forEach { db.setStatusExists(it) } }
        assertEquals(100, db.getEntryCount(), "all alive after batched untrash")
        assertEquals(0, db.recovery.trashedEntries().size)
    }

    @Test
    fun `upgrade path — pre-redesign DB drops sync_entries and rebuilds at schema_version=2`() {
        // Inject a pre-redesign-shaped DB: sync_state exists, no schema_version
        // row, sync_entries has the old path-PK shape with a row that the
        // new schema would consider non-rescannable (its remote_id would be
        // a synthetic pending-upload row, but we want to prove that the
        // upgrade drops everything regardless of row content).
        val tmpDir = Files.createTempDirectory("unidrive-upgrade")
        val dbFile = tmpDir.resolve("state.db")
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE sync_entries (
                        path            TEXT PRIMARY KEY,
                        remote_id       TEXT,
                        remote_hash     TEXT,
                        remote_size     INTEGER NOT NULL DEFAULT 0,
                        remote_modified TEXT,
                        local_mtime     INTEGER,
                        local_size      INTEGER,
                        is_folder       INTEGER NOT NULL DEFAULT 0,
                        is_pinned       INTEGER NOT NULL DEFAULT 0,
                        is_hydrated     INTEGER NOT NULL DEFAULT 0,
                        last_synced     TEXT NOT NULL
                    )
                """,
                )
                stmt.executeUpdate(
                    """
                    CREATE TABLE sync_state (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                """,
                )
                // Pre-redesign DBs may already carry the legacy dedupe
                // marker. The upgrade should not depend on its presence.
                stmt.executeUpdate(
                    "INSERT INTO sync_state (key, value) VALUES " +
                        "('migration:dedupe_remote_id', 'deleted=0'), " +
                        "('delta_cursor', '2026-05-17T10:00:00Z')",
                )
                stmt.executeUpdate(
                    "INSERT INTO sync_entries (path, remote_id, remote_size, " +
                        "is_folder, is_pinned, is_hydrated, last_synced) " +
                        "VALUES ('/old/file.txt', 'old-uuid', 100, 0, 0, 1, '2026-05-17T10:00:00Z')",
                )
            }
        }

        val upgraded = StateDatabase(dbFile)
        upgraded.initialize()
        try {
            // sync_entries was dropped; nothing carried over.
            assertEquals(0, upgraded.getAllEntries().size, "pre-redesign rows dropped, next scan repopulates")
            assertEquals(0, upgraded.recovery.allEntriesAnyStatus().size)
            // schema_version stamped.
            assertEquals(
                StateDatabase.SCHEMA_VERSION.toString(),
                upgraded.getSyncState(StateDatabase.SCHEMA_VERSION_KEY),
            )
            // sync_state preserved (cursor survives).
            assertEquals("2026-05-17T10:00:00Z", upgraded.getSyncState("delta_cursor"))
            // The new schema is in place — partial unique on path is honoured.
            upgraded.upsertEntry(entry("/new.txt").copy(remoteId = "uuid-new"))
            assertNotNull(upgraded.getEntry("/new.txt"))
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun `upgrade path — fresh install stamps schema_version and creates everything`() {
        // setUp's `initialize()` already exercised the fresh-install path.
        // Assert the post-state.
        assertEquals(
            StateDatabase.SCHEMA_VERSION.toString(),
            db.getSyncState(StateDatabase.SCHEMA_VERSION_KEY),
        )
        // No row-level backfill code path: a fresh install has no migration
        // markers from the old dedupe scheme.
        assertNull(db.getSyncState("migration:dedupe_remote_id"))
    }

    @Test
    fun `upgrade path — current-version DB is a no-op`() {
        // Re-initialize an already-current DB. Schema and rows survive.
        db.upsertEntry(entry("/keep.txt").copy(remoteId = "uuid-keep"))
        db.close()
        val dbPath = (db.javaClass.getDeclaredField("dbPath").apply { isAccessible = true }.get(db) as java.nio.file.Path)
        val reopened = StateDatabase(dbPath)
        reopened.initialize()
        try {
            assertEquals(1, reopened.getAllEntries().size, "current-version DB preserves rows")
            assertNotNull(reopened.getEntry("/keep.txt"))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `pending-upload row survives an upsert cycle without churning rows`() {
        // The Kotlin-level `remoteId=null` placeholder maps to a `local:<uuid>`
        // synthetic at the storage layer. Re-upserting the same pending row
        // should not multiply rows (the path partial-unique reuses the
        // existing synthetic).
        val pending =
            SyncEntry(
                path = "/pending.txt",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 1L,
                localSize = 100,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.EPOCH,
            )
        db.upsertEntry(pending)
        db.upsertEntry(pending) // same content again
        assertEquals(1, db.getAllEntries().size, "second upsert must not create a duplicate row")

        // The Kotlin-level mapper hides the synthetic.
        val loaded = db.getEntry("/pending.txt")
        assertNotNull(loaded)
        assertNull(loaded.remoteId, "callers never see the local: synthetic")

        // Promoting to real remote_id replaces the pending row cleanly.
        db.upsertEntry(pending.copy(remoteId = "uuid-real", remoteHash = "h"))
        assertEquals(1, db.getAllEntries().size)
        assertEquals("uuid-real", db.getEntry("/pending.txt")?.remoteId)
    }

    // ---- Resumable-scan staging slice ----

    private fun staged(
        id: String,
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = null,
        hash = "hash-$id",
        mimeType = null,
        parentId = null,
    )

    @Test
    fun `staging — staged rows are orthogonal to sync_entries readers`() {
        // The staging slice lives in its own table; the alive view and the
        // recovery namespace both only ever see real `sync_entries` rows.
        db.upsertEntry(entry("/foo.txt").copy(remoteId = "live-uuid"))
        val scanId = db.beginScan(initialMarker = null)
        db.persistScanPage(scanId, listOf(staged(id = "staged-uuid", path = "/foo.txt")), marker = "page1")

        val alive = db.getEntry("/foo.txt")
        assertNotNull(alive)
        assertEquals("live-uuid", alive.remoteId)
        assertEquals(1, db.getAllEntries().size, "staging table must not appear in the alive view")
        assertEquals(1, db.recovery.allEntriesAnyStatus().size, "recovery is sync_entries-only too")
        // Staged rows are loadable through the dedicated reader.
        assertEquals(1, db.loadStagedItems(scanId).size)
    }

    @Test
    fun `staging — beginScan persists checkpoint state`() {
        val scanId = db.beginScan(initialMarker = "2026-05-18T10:00:00Z|offset=0,0")
        assertEquals(scanId, db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
        assertEquals(
            "2026-05-18T10:00:00Z|offset=0,0",
            db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER),
        )
        assertNotNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT))
    }

    @Test
    fun `staging — persistScanPage advances the checkpoint marker per page`() {
        val scanId = db.beginScan(initialMarker = null)
        db.persistScanPage(
            scanId,
            listOf(staged("u1", "/a.txt"), staged("u2", "/b.txt")),
            marker = "marker=page1",
        )
        assertEquals("marker=page1", db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER))

        db.persistScanPage(
            scanId,
            listOf(staged("u3", "/c.txt")),
            marker = "marker=page2",
        )
        assertEquals("marker=page2", db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER))

        val rows = db.loadStagedItems(scanId)
        assertEquals(3, rows.size, "all three pages worth of rows must persist")
    }

    @Test
    fun `staging — completeScan deletes staged rows and clears checkpoint`() {
        val scanId = db.beginScan(initialMarker = null)
        db.persistScanPage(scanId, listOf(staged("u1", "/a.txt")), marker = "m1")
        assertEquals(1, db.loadStagedItems(scanId).size)

        db.completeScan(scanId)

        assertEquals(0, db.loadStagedItems(scanId).size)
        assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
        assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER))
        assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT))
    }

    @Test
    fun `staging — getActiveScan returns the live scan when fresh`() {
        db.beginScan(initialMarker = "resume-marker")
        val active = db.getActiveScan(staleThreshold = Duration.ofHours(6))
        assertNotNull(active)
        assertEquals("resume-marker", active.marker)
    }

    @Test
    fun `staging — getActiveScan clears + returns null on stale checkpoint`() {
        val scanId = db.beginScan(initialMarker = "stale-marker")
        db.persistScanPage(scanId, listOf(staged("u1", "/old.txt")), marker = "stale-marker")
        // Backdate the started_at so the stale-detection threshold trips.
        db.setSyncState(
            StateDatabase.SCAN_IN_PROGRESS_STARTED_AT,
            Instant.now().minus(Duration.ofDays(1)).toString(),
        )

        val active = db.getActiveScan(staleThreshold = Duration.ofHours(6))

        assertNull(active, "stale checkpoint must surface as no active scan")
        assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID), "stale state cleared")
        assertEquals(0, db.loadStagedItems(scanId).size, "stale staged rows discarded")
    }

    @Test
    fun `staging — persistScanPage is atomic across rows and the marker`() {
        // Same remote_id appearing twice in one page (re-emitted on retry):
        // the INSERT OR REPLACE keyed on (scan_id, remote_id) coalesces the
        // two writes into one row, the marker advances exactly once, and the
        // sync_entries side is untouched.
        val scanId = db.beginScan(initialMarker = null)
        db.persistScanPage(
            scanId,
            listOf(staged("u1", "/first.txt", size = 100), staged("u1", "/first.txt", size = 200)),
            marker = "after-collision",
        )
        val rows = db.loadStagedItems(scanId)
        assertEquals(1, rows.size, "(scan_id, remote_id) PK collapses retries")
        assertEquals(200L, rows.single().size, "later write wins via INSERT OR REPLACE")
        assertEquals("after-collision", db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER))
    }

    @Test
    fun `staging — live + staged rows are independent across the two tables`() {
        // Cloud and staged rows for the same path can coexist trivially —
        // they live in different tables. No INSERT failure either way.
        db.upsertEntry(entry("/shared.txt").copy(remoteId = "live-uuid"))
        val scanId = db.beginScan(initialMarker = null)
        db.persistScanPage(scanId, listOf(staged("staged-uuid", "/shared.txt")), marker = "m")
        assertEquals(1, db.loadStagedItems(scanId).size)
        assertEquals("live-uuid", db.getEntry("/shared.txt")?.remoteId)
    }

    @Test
    fun `staging — loadStagedItems preserves cloud identity for path re-resolution`() {
        // The staged row carries the cloud-identity fields (id, parentId,
        // name, isFolder, size, modified, hash) but not the path — the
        // provider re-resolves the path on resume from the rebuilt folder
        // graph. loadStagedItems returns a placeholder path so the CloudItem
        // shape stays uniform; the provider is responsible for overwriting it.
        val scanId = db.beginScan(initialMarker = null)
        val original =
            staged(id = "u1", path = "/dir/file.txt", size = 4096, isFolder = false)
                .copy(parentId = "parent-uuid")
        db.persistScanPage(scanId, listOf(original), marker = "m")

        val loaded = db.loadStagedItems(scanId).single()
        assertEquals("u1", loaded.id)
        assertEquals("file.txt", loaded.name)
        assertEquals("parent-uuid", loaded.parentId, "parent_uuid drives folder-graph reconstruction")
        assertEquals(4096L, loaded.size)
        assertEquals(false, loaded.isFolder)
        assertEquals("hash-u1", loaded.hash)
        assertEquals(original.modified, loaded.modified)
    }

    @Test
    fun `staging — completeScan does not touch live rows written outside the scan`() {
        db.upsertEntry(entry("/live.txt").copy(remoteId = "live-uuid"))
        val scanId = db.beginScan(initialMarker = null)
        db.persistScanPage(scanId, listOf(staged("staged-uuid", "/other.txt")), marker = "m")

        db.completeScan(scanId)

        // Live row remains exactly as written; the engine's updateRemoteEntries
        // call site, not completeScan, is responsible for materialising the
        // staging slice into live rows.
        assertNotNull(db.getEntry("/live.txt"))
        assertEquals("live-uuid", db.getEntry("/live.txt")?.remoteId)
        assertEquals(1, db.getAllEntries().size, "completed scan removes only staged rows")
    }

    @Test
    fun `staging — scan_staging is added in-place to an existing v2 DB without disturbing rows`() {
        // Simulate a v2 DB created before the resumable-scan slice landed:
        // sync_entries + alive_entries + indexes are present, schema_version
        // is 2, scan_staging is absent. The next initialize() must add
        // scan_staging without touching the existing live rows.
        val tmpDir = Files.createTempDirectory("unidrive-staging-migration")
        val dbFile = tmpDir.resolve("state.db")
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE sync_entries (
                        remote_id       TEXT PRIMARY KEY,
                        parent_uuid     TEXT,
                        path            TEXT NOT NULL,
                        remote_hash     TEXT,
                        remote_size     INTEGER NOT NULL DEFAULT 0,
                        remote_modified TEXT,
                        local_mtime     INTEGER,
                        local_size      INTEGER,
                        is_folder       INTEGER NOT NULL DEFAULT 0,
                        is_pinned       INTEGER NOT NULL DEFAULT 0,
                        is_hydrated     INTEGER NOT NULL DEFAULT 0,
                        last_synced     TEXT NOT NULL,
                        status          TEXT NOT NULL DEFAULT 'EXISTS'
                                        CHECK (status IN ('EXISTS','TRASHED','DELETED'))
                    )
                """,
                )
                stmt.executeUpdate(
                    "CREATE UNIQUE INDEX idx_sync_entries_path_alive " +
                        "ON sync_entries(path) WHERE status='EXISTS'",
                )
                stmt.executeUpdate(
                    "CREATE VIEW alive_entries AS SELECT * FROM sync_entries WHERE status='EXISTS'",
                )
                stmt.executeUpdate(
                    "CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                )
                stmt.executeUpdate(
                    "INSERT INTO sync_state VALUES ('${StateDatabase.SCHEMA_VERSION_KEY}', " +
                        "'${StateDatabase.SCHEMA_VERSION}')",
                )
                stmt.executeUpdate(
                    "INSERT INTO sync_entries (remote_id, path, remote_size, " +
                        "is_folder, is_pinned, is_hydrated, last_synced) " +
                        "VALUES ('keep-uuid', '/keep.txt', 100, 0, 0, 1, '2026-05-17T10:00:00Z')",
                )
            }
        }

        val upgraded = StateDatabase(dbFile)
        upgraded.initialize()
        try {
            // Live row survived — additive migration does not touch sync_entries.
            assertEquals(1, upgraded.getAllEntries().size)
            assertNotNull(upgraded.getEntry("/keep.txt"))
            // The new staging primitives operate cleanly on the migrated DB.
            val scanId = upgraded.beginScan(initialMarker = "m0")
            upgraded.persistScanPage(scanId, listOf(staged("new-uuid", "/new.txt")), marker = "m1")
            assertEquals(1, upgraded.loadStagedItems(scanId).size)
            // The staging slice is fully orthogonal — alive view unchanged.
            assertEquals(1, upgraded.getAllEntries().size)
        } finally {
            upgraded.close()
        }
    }
}
