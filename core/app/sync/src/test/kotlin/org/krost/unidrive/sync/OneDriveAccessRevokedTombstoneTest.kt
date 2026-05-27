package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * #183: minimal-tombstone orphan fix — a Graph delta item carrying only `id` +
 * `@microsoft.graph.removed state="removed"` (no parentReference / path) arrives
 * as CloudItem(deleted=false, accessRevoked=true, path="/"). The engine must retire
 * the DB row via TRASHED without touching the local file.
 *
 * Three invariants:
 *  (a) DB row is retired (TRASHED) — no longer an unreapable orphan, won't reappear.
 *  (b) Local file (hydration cache entry) is preserved — NOT deleted.
 *  (c) A genuine root-level item (accessRevoked=false, path="/something") is unaffected.
 *
 * Negative tests:
 *  (d) A normal hard-delete tombstone (deleted=true) still reaps as before.
 *  (e) An access-revoked item with no DB row is a no-op (no crash, no orphan created).
 */
class OneDriveAccessRevokedTombstoneTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-183-test")
        val dbPath = Files.createTempDirectory("ud-183-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(cacheRoot: Path? = null) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            cacheRoot = cacheRoot,
        )

    private fun seedEntry(
        path: String,
        remoteId: String,
        isHydrated: Boolean = true,
    ) {
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = remoteId,
                remoteHash = "hash-$remoteId",
                remoteSize = 42L,
                remoteModified = Instant.parse("2026-01-01T00:00:00Z"),
                localMtime = if (isHydrated) 1_700_000_000_000L else null,
                localSize = if (isHydrated) 42L else null,
                isFolder = false,
                isPinned = false,
                isHydrated = isHydrated,
                lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // (a) + (b): DB row is retired; local file is preserved
    // -------------------------------------------------------------------------

    @Test
    fun `access_revoked_tombstone_retires_db_row_and_preserves_local_file`() =
        runTest {
            // Seed an existing DB row (hydrated) and a local cache file for /shared.txt.
            val cacheRoot = Files.createTempDirectory("ud-183-cache")
            seedEntry("/shared.txt", remoteId = "item-shared-1", isHydrated = true)

            val eng = engine(cacheRoot = cacheRoot)
            // Create the cache file that ensureHydrated would have written.
            val cachePath = eng.resolveCachePath("/shared.txt")
            Files.createDirectories(cachePath.parent)
            Files.write(cachePath, "hello from shared".toByteArray())
            assertTrue(Files.exists(cachePath), "pre: cache file must exist")

            // Deliver an access-revoked tombstone: only id, no parentReference/path.
            // CloudItem models the Graph minimal tombstone: deleted=false, accessRevoked=true, path="/".
            val tombstone = CloudItem(
                id = "item-shared-1",
                name = "",
                path = "/",
                size = 0,
                isFolder = false,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                deleted = false,
                accessRevoked = true,
            )
            provider.deltaItems = listOf(tombstone)
            provider.deltaCursor = "cursor-after-revocation"

            eng.syncOnce()

            // (a) The DB row must be retired — not in alive_entries.
            assertNull(
                db.getEntryByRemoteId("item-shared-1"),
                "(a) retired row must not be in alive_entries after access-revoked tombstone",
            )
            // (b) The local cache file must still exist.
            assertTrue(
                Files.exists(cachePath),
                "(b) local file must be preserved after access-revoked tombstone",
            )
        }

    // -------------------------------------------------------------------------
    // Confirm the test FAILS before the fix (row orphaned): this is verified
    // by the test itself — before the fix, getEntryByRemoteId returns non-null,
    // so assertion (a) fails. The test is the regression guard.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // (c): a genuine root-level item (accessRevoked=false) is unaffected
    // -------------------------------------------------------------------------

    @Test
    fun `genuine_root_item_is_not_retired`() =
        runTest {
            // A normal (non-revoked) item at a real path — must flow through normally.
            // We don't seed a DB row; the engine should create a placeholder for it.
            val normalItem = CloudItem(
                id = "item-normal-root",
                name = "rootfile.txt",
                path = "/rootfile.txt",
                size = 10,
                isFolder = false,
                modified = Instant.parse("2026-01-01T00:00:00Z"),
                created = Instant.parse("2026-01-01T00:00:00Z"),
                hash = "hash-normal",
                mimeType = "text/plain",
                deleted = false,
                accessRevoked = false,
            )
            provider.deltaItems = listOf(normalItem)
            provider.deltaCursor = "cursor-normal"
            provider.files["/rootfile.txt"] = ByteArray(10) { 0x01 }

            engine().syncOnce()

            // The row must now exist in state.db (engine created it via updateRemoteEntries).
            val entry = db.getEntry("/rootfile.txt")
            assertNotNull(entry, "(c) normal root item must be tracked in state.db")
            assertEquals("item-normal-root", entry.remoteId, "(c) remoteId must match")
        }

    // -------------------------------------------------------------------------
    // (d): a normal hard-delete tombstone (deleted=true) still reaps the row
    // -------------------------------------------------------------------------

    @Test
    fun `hard_delete_tombstone_still_reaps_db_row`() =
        runTest {
            // Seed a row for a file that will be hard-deleted.
            seedEntry("/deleted.txt", remoteId = "item-deleted-1", isHydrated = false)
            assertNotNull(db.getEntry("/deleted.txt"), "pre: row must exist")

            // Normal hard-delete tombstone: deleted=true, accessRevoked=false.
            // Because this is a full sync (no prior cursor), detectMissingAfterFullSync
            // will inject a deleted=true tombstone for item-deleted-1 if it's absent.
            // Send it explicitly too for clarity.
            val hardDelete = CloudItem(
                id = "item-deleted-1",
                name = "deleted.txt",
                path = "/deleted.txt",
                size = 0,
                isFolder = false,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                deleted = true,
                accessRevoked = false,
            )
            provider.deltaItems = listOf(hardDelete)
            provider.deltaCursor = "cursor-hard-delete"

            engine().syncOnce()

            // The row must be gone (reaped via RemoveEntry on a full sync).
            assertNull(
                db.getEntry("/deleted.txt"),
                "(d) hard-deleted row must be reaped after a hard-delete tombstone",
            )
        }

    // -------------------------------------------------------------------------
    // (e): access-revoked tombstone with no DB row is a no-op (no crash)
    // -------------------------------------------------------------------------

    @Test
    fun `access_revoked_tombstone_with_no_db_row_is_a_noop`() =
        runTest {
            // No DB row for this id — engine should handle gracefully.
            val tombstone = CloudItem(
                id = "item-never-tracked",
                name = "",
                path = "/",
                size = 0,
                isFolder = false,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                deleted = false,
                accessRevoked = true,
            )
            provider.deltaItems = listOf(tombstone)
            provider.deltaCursor = "cursor-noop"

            // Must not throw.
            engine().syncOnce()

            // No row should have been created.
            assertNull(
                db.getEntryByRemoteId("item-never-tracked"),
                "(e) no row must be created for an untracked access-revoked tombstone",
            )
        }
}
