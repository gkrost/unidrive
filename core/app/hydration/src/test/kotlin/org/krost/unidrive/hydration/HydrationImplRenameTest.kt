package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
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

class HydrationImplRenameTest {

    private class RenameFakeProvider : CloudProvider {
        override val id = "fake-rename"
        override val displayName = "Fake (rename test)"
        override var isAuthenticated = true

        var lastMoveFrom: String? = null
        var lastMoveTo: String? = null
        var throwOnMove: Throwable? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun getMetadata(path: String): CloudItem = error("not used")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
        override suspend fun delete(remotePath: String) {}
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            lastMoveFrom = fromPath
            lastMoveTo = toPath
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
}
