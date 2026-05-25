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
import kotlin.test.assertTrue

class HydrationImplNamespaceTest {

    private class NamespaceFakeProvider : CloudProvider {
        override val id = "fake-ns"
        override val displayName = "Fake (namespace test)"
        override var isAuthenticated = true

        var lastCreatedFolder: String? = null
        var lastDeletedPath: String? = null
        var throwOnCreate: Throwable? = null
        var throwOnDelete: Throwable? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun getMetadata(path: String): CloudItem = error("not used")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
        override suspend fun delete(remotePath: String) {
            lastDeletedPath = remotePath
            throwOnDelete?.also { throwOnDelete = null; throw it }
        }
        override suspend fun createFolder(path: String): CloudItem {
            lastCreatedFolder = path
            throwOnCreate?.also { throwOnCreate = null; throw it }
            return CloudItem(
                id = "rid-$path",
                name = path.substringAfterLast('/'),
                path = path,
                size = 0L,
                isFolder = true,
                modified = Instant.parse("2026-05-24T09:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
            )
        }
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private fun freshEnv(): Triple<HydrationImpl, NamespaceFakeProvider, StateDatabase> {
        val cacheRoot = Files.createTempDirectory("unidrive-hydration-ns-cache")
        val dbPath = Files.createTempDirectory("unidrive-hydration-ns-db").resolve("state.db")
        val provider = NamespaceFakeProvider()
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()
        val syncRoot = Files.createTempDirectory("unidrive-hydration-ns-sync")
        val engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            cacheRoot = cacheRoot,
        )
        val hydration = HydrationImpl(syncEngine = engine, stateDb = db)
        return Triple(hydration, provider, db)
    }

    @Test
    fun mkdir_creates_folder_on_provider_and_inserts_state_db_row() = runTest {
        val (impl, provider, stateDb) = freshEnv()

        val result = impl.mkdir("/foo")

        assertEquals(MkdirResult.Ok, result)
        assertEquals("/foo", provider.lastCreatedFolder)

        val entry = stateDb.getEntry("/foo")
        assertTrue(entry != null && entry.isFolder)
    }

    @Test
    fun unlink_refuses_folder_path_with_PathIsFolder() = runTest {
        val (impl, _, stateDb) = freshEnv()

        // Pre-populate a folder row.
        stateDb.upsertEntry(
            SyncEntry(
                path = "/foo",
                remoteId = "rid-foo",
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

        val result = impl.unlink("/foo")

        assertEquals(UnlinkResult.PathIsFolder, result)
    }

    @Test
    fun rmdir_refuses_file_path_with_PathIsFile() = runTest {
        val (impl, _, stateDb) = freshEnv()

        // Pre-populate a file row.
        stateDb.upsertEntry(
            SyncEntry(
                path = "/foo.txt",
                remoteId = "rid-foo",
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

        val result = impl.rmdir("/foo.txt")

        assertEquals(RmdirResult.PathIsFile, result)
    }

    @Test
    fun mkdir_detects_provider_parent_not_found_substring() = runTest {
        // OneDrive wording: GraphApiException("Create folder failed: 404 ...")
        val (impl1, provider1, _) = freshEnv()
        provider1.throwOnCreate = RuntimeException("Create folder failed: 404 Not Found - The resource could not be found.")
        assertEquals(MkdirResult.ParentNotFound, impl1.mkdir("/missing/child"))

        // Internxt wording: ProviderException("Folder not found: <seg> in <path>")
        val (impl2, provider2, _) = freshEnv()
        provider2.throwOnCreate = RuntimeException("Folder not found: missing in /missing/child")
        assertEquals(MkdirResult.ParentNotFound, impl2.mkdir("/missing/child"))
    }

    @Test
    fun unlink_of_never_uploaded_row_skips_provider_and_succeeds() = runTest {
        val (impl, provider, stateDb) = freshEnv()

        // A file created through the mount but not yet uploaded: state.db row
        // with remoteId = null (stored as a `local:` synthetic, surfaced back
        // as null). It only ever existed locally.
        stateDb.upsertEntry(
            SyncEntry(
                path = "/draft.txt",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = Instant.now().toEpochMilli(),
                localSize = 0L,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        val result = impl.unlink("/draft.txt")

        assertEquals(UnlinkResult.Ok, result, "rm of a never-uploaded file must succeed, not EIO")
        assertEquals(
            null,
            provider.lastDeletedPath,
            "provider.delete must NOT be called for a never-uploaded file (would 404)",
        )
        assertEquals(null, stateDb.getEntry("/draft.txt"), "row must be marked deleted")
    }

    @Test
    fun unlink_of_uploaded_row_does_call_provider_delete() = runTest {
        // Contrast case: a normally-synced file (real remoteId) still routes
        // through the provider delete — the skip is scoped strictly to the
        // remoteId == null case.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/synced.txt",
                remoteId = "rid-synced",
                remoteHash = "hash",
                remoteSize = 4L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )

        val result = impl.unlink("/synced.txt")

        assertEquals(UnlinkResult.Ok, result)
        assertEquals("/synced.txt", provider.lastDeletedPath, "uploaded file must hit provider.delete")
    }

    @Test
    fun rmdir_detects_provider_not_empty_substring() = runTest {
        // OneDrive wording
        val (impl1, provider1, stateDb1) = freshEnv()
        stateDb1.upsertEntry(
            SyncEntry(
                path = "/foo",
                remoteId = "rid-foo",
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
        provider1.throwOnDelete = RuntimeException("OneDrive responded 409 Conflict: Folder not empty")
        assertEquals(RmdirResult.NotEmpty, impl1.rmdir("/foo"))

        // Internxt wording
        val (impl2, provider2, stateDb2) = freshEnv()
        stateDb2.upsertEntry(
            SyncEntry(
                path = "/foo",
                remoteId = "rid-foo",
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
        provider2.throwOnDelete = RuntimeException("Internxt API: cannot delete non-empty folder")
        assertEquals(RmdirResult.NotEmpty, impl2.rmdir("/foo"))
    }
}
