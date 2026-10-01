package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class HydrationImplRenameTest {

    private class RenameFakeProvider : CloudProvider {
        override val id = "fake-rename"
        override val displayName = "Fake (rename test)"
        override var isAuthenticated = true

        var lastMoveFrom: String? = null
        var lastMoveTo: String? = null
        var throwOnMove: Throwable? = null
        var lastDeletePath: String? = null
        var throwOnDelete: Throwable? = null
        // When non-null, getMetadata reports this item instead of not-found —
        // simulates a ghost: a row that looks never-uploaded but whose content
        // actually landed on the cloud.
        var ghostItem: CloudItem? = null
        // Operation order across delete/move — the replace sequence must delete
        // the destination BEFORE moving the source over it.
        val callLog = mutableListOf<String>()

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        // A never-uploaded local file is genuinely absent on the cloud — the
        // rename ghost-probe expects a typed not-found, not a generic error.
        override suspend fun getMetadata(path: String): CloudItem =
            ghostItem ?: throw ProviderException("Item not found: $path")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            throwOnDelete?.also { throwOnDelete = null; throw it }
            lastDeletePath = remotePath
            callLog += "delete:$remotePath"
        }
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            lastMoveFrom = fromPath
            lastMoveTo = toPath
            callLog += "move:$fromPath->$toPath"
            throwOnMove?.also { throwOnMove = null; throw it }
            return CloudItem(
                id = "rid-$toPath",
                name = toPath.substringAfterLast('/'),
                path = toPath,
                size = 0L,
                isFolder = false,
                modified = Instant.parse("2026-05-24T09:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
            )
        }
        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private fun freshEnv(): Triple<HydrationImpl, RenameFakeProvider, StateDatabase> {
        val cacheRoot = Files.createTempDirectory("unidrive-hydration-rename-cache")
        val dbPath = Files.createTempDirectory("unidrive-hydration-rename-db").resolve("state.db")
        val provider = RenameFakeProvider()
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()
        val syncRoot = Files.createTempDirectory("unidrive-hydration-rename-sync")
        val engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            cacheRoot = cacheRoot,
        )
        val hydration = HydrationImpl(syncEngine = engine, stateDb = db)
        return Triple(hydration, provider, db)
    }

    private fun seedFile(db: StateDatabase, path: String) {
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = "rid-${path}",
                remoteHash = "hash",
                remoteSize = 100L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    private fun seedNeverUploadedFile(db: StateDatabase, path: String, localSize: Long = 0L) {
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = Instant.now().toEpochMilli(),
                localSize = localSize,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
    }

    private fun seedFolder(db: StateDatabase, path: String) {
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = "rid-${path}",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun rename_moves_provider_item_and_updates_state_db_row() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")

        val result = impl.rename("/a.txt", "/b.txt")

        assertEquals(RenameResult.Ok, result)
        assertEquals("/a.txt", provider.lastMoveFrom)
        assertEquals("/b.txt", provider.lastMoveTo)
        assertNull(db.getEntry("/a.txt"), "old path must be gone from state.db after rename")
        val newEntry = db.getEntry("/b.txt")
        assertNotNull(newEntry, "new path must be present in state.db after rename")
        assertTrue(!newEntry.isFolder, "renamed file row must keep isFolder=false")
    }

    @Test
    fun rename_returns_OldPathNotFound_when_source_missing() = runTest {
        val (impl, provider, _) = freshEnv()

        val result = impl.rename("/missing.txt", "/b.txt")

        assertEquals(RenameResult.OldPathNotFound, result)
        assertNull(provider.lastMoveFrom, "provider.move must not be called when source missing")
    }

    @Test
    fun rename_returns_NewParentNotFound_when_destination_parent_missing() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")

        val result = impl.rename("/a.txt", "/missing_folder/b.txt")

        assertEquals(RenameResult.NewParentNotFound, result)
        assertNull(provider.lastMoveFrom, "provider.move must not be called when new parent missing")
    }

    @Test
    fun rename_returns_NewPathExists_when_destination_already_present() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")
        seedFile(db, "/b.txt")

        val result = impl.rename("/a.txt", "/b.txt")

        assertEquals(RenameResult.NewPathExists, result)
        assertNull(provider.lastMoveFrom, "provider.move must not be called when destination exists")
    }

    // Regression test for the file-manager partial-rename (temp → final) EIO bug.
    // A never-uploaded file (remoteId == null) must be renamed purely locally —
    // no provider.move call — so the pending upload can proceed at the new path.
    @Test
    fun rename_of_never_uploaded_file_is_local_no_provider_move() = runTest {
        val (impl, provider, db) = freshEnv()
        seedNeverUploadedFile(db, "/repro.txt.part", localSize = 42L)

        val result = impl.rename("/repro.txt.part", "/repro.txt")

        assertEquals(RenameResult.Ok, result, "rename of never-uploaded file must succeed")
        assertNull(provider.lastMoveFrom, "provider.move must NOT be called for a never-uploaded file")
        assertNull(db.getEntry("/repro.txt.part"), "old path must be gone from state.db")
        val newEntry = db.getEntry("/repro.txt")
        assertNotNull(newEntry, "new path must exist in state.db after local rename")
        assertNull(newEntry.remoteId, "remoteId must remain null — file is still pending upload")
        assertFalse(newEntry.isFolder, "renamed row must still be a file")
        assertEquals(42L, newEntry.localSize, "localSize must be preserved across local rename")
    }

    // Counterpart: a file that HAS been uploaded must still go through provider.move.
    @Test
    fun rename_of_uploaded_file_calls_provider_move() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/uploaded.txt") // remoteId = "rid-/uploaded.txt"

        val result = impl.rename("/uploaded.txt", "/renamed.txt")

        assertEquals(RenameResult.Ok, result)
        assertEquals("/uploaded.txt", provider.lastMoveFrom, "provider.move must be called for uploaded file")
        assertEquals("/renamed.txt", provider.lastMoveTo)
    }

    // ── replace flag (editors' safe-save: temp file renamed over the target) ──

    @Test
    fun `replace overwrites an existing file destination`() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")
        seedFile(db, "/b.txt")

        val result = impl.rename("/a.txt", "/b.txt", replace = true)

        assertEquals(RenameResult.Ok, result)
        assertEquals("/b.txt", provider.lastDeletePath, "the old destination must go through the delete path")
        assertEquals("/a.txt", provider.lastMoveFrom)
        assertEquals("/b.txt", provider.lastMoveTo)
        assertNull(db.getEntry("/a.txt"))
        assertNotNull(db.getEntry("/b.txt"), "the source row now lives at the destination")
    }

    @Test
    fun `replace deletes the destination before moving the source over it`() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")
        seedFile(db, "/b.txt")

        val result = impl.rename("/a.txt", "/b.txt", replace = true)

        assertEquals(RenameResult.Ok, result)
        assertEquals(
            listOf("delete:/b.txt", "move:/a.txt->/b.txt"),
            provider.callLog,
            "delete-then-move: the destination must be gone from the cloud before the source takes its place",
        )
    }

    @Test
    fun `replace refuses a folder destination`() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")
        seedFolder(db, "/b")

        val result = impl.rename("/a.txt", "/b", replace = true)

        assertEquals(RenameResult.NewPathExists, result, "no provider offers atomic folder replace — refuse")
        assertNull(provider.lastDeletePath, "nothing may be deleted when the destination is a folder")
        assertNull(provider.lastMoveFrom)
    }

    @Test
    fun `replace over a never-uploaded destination hard-deletes the row without a provider delete`() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")
        seedNeverUploadedFile(db, "/b.txt")

        val result = impl.rename("/a.txt", "/b.txt", replace = true)

        assertEquals(RenameResult.Ok, result)
        assertNull(provider.lastDeletePath, "a genuinely-local destination has no cloud copy to delete")
        assertEquals("/a.txt", provider.lastMoveFrom)
        assertNull(db.getEntry("/a.txt"))
        assertNotNull(db.getEntry("/b.txt"))
    }

    @Test
    fun `replace over a ghost destination deletes the cloud copy first`() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")
        seedNeverUploadedFile(db, "/b.txt")
        // The destination row presents as never-uploaded, but its content landed
        // on the cloud (lost upload response). The remote copy must be deleted,
        // not orphaned.
        provider.ghostItem = CloudItem(
            id = "ghost-b",
            name = "b.txt",
            path = "/b.txt",
            size = 0L,
            isFolder = false,
            modified = Instant.parse("2026-05-24T09:00:00Z"),
            created = null,
            hash = null,
            mimeType = null,
        )

        val result = impl.rename("/a.txt", "/b.txt", replace = true)

        assertEquals(RenameResult.Ok, result)
        assertEquals("/b.txt", provider.lastDeletePath, "the ghost's cloud copy must be deleted")
        assertEquals(
            listOf("delete:/b.txt", "move:/a.txt->/b.txt"),
            provider.callLog,
        )
        assertNull(db.getEntry("/a.txt"))
        assertNotNull(db.getEntry("/b.txt"))
    }

    @Test
    fun `replace fails cleanly when the destination delete fails`() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")
        seedFile(db, "/b.txt")
        provider.throwOnDelete = RuntimeException("cloud refused")

        val result = impl.rename("/a.txt", "/b.txt", replace = true)

        assertTrue(result is RenameResult.Failed, "the rename must fail when the old destination cannot be deleted")
        assertNull(provider.lastMoveFrom, "the source move must not run after a failed destination delete")
        // Recoverable state: the destination is untouched (delete failed before
        // any cloud mutation), the source row is intact at its old path.
        assertNotNull(db.getEntry("/b.txt"))
        assertNotNull(db.getEntry("/a.txt"))
    }

    // POSIX rename(2) onto itself is a no-op success — decided BEFORE any
    // destination deletion, or replace=true would delete the source as its own
    // destination.
    @Test
    fun `self rename is a no-op success in both modes`() = runTest {
        val (impl, provider, db) = freshEnv()
        seedFile(db, "/a.txt")

        assertEquals(RenameResult.Ok, impl.rename("/a.txt", "/a.txt"))
        assertEquals(RenameResult.Ok, impl.rename("/a.txt", "/a.txt", replace = true))

        assertNull(provider.lastDeletePath, "a self rename must not delete anything")
        assertNull(provider.lastMoveFrom)
        assertNotNull(db.getEntry("/a.txt"), "the row must be untouched")
    }
}
