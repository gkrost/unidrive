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
        // A never-uploaded local file is genuinely absent on the cloud — the
        // rename/unlink ghost-probe expects a typed not-found, not a generic error.
        override suspend fun getMetadata(path: String): CloudItem = throw ProviderException("Item not found: $path")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
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

    private data class Env(
        val impl: HydrationImpl,
        val provider: NamespaceFakeProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private fun freshEnv(): Env {
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
        return Env(hydration, provider, db, engine)
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
        assertEquals(null, stateDb.getEntry("/draft.txt"), "row must no longer be alive")
        // Hard-delete, not tombstone: a never-uploaded file has no cloud
        // counterpart, so leaving a TRASHED tombstone would grow sync_entries
        // unboundedly under create/delete temp-file churn. The row must be gone
        // from the table entirely (no row of ANY status at that path).
        assertEquals(
            emptyList<SyncEntry>(),
            stateDb.recovery.allEntriesAnyStatus().filter { it.path == "/draft.txt" },
            "never-uploaded unlink must hard-delete the row, leaving no tombstone",
        )
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
    fun unlink_treats_remote_already_gone_404_as_success() = runTest {
        // Guards: rm of a stale (already-deleted-on-cloud) file must return Ok,
        // not EIO. The row must be tombstoned so the reconciler tracks the deletion.
        // Uses the typed resolution-error shape (ProviderException with "Folder not found: "
        // prefix) that InternxtProvider.resolveFolder emits when a parent is gone.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/gone.txt",
                remoteId = "rid-gone",
                remoteHash = "hash",
                remoteSize = 10L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider.throwOnDelete = ProviderException("Folder not found: gone in /gone.txt")

        val result = impl.unlink("/gone.txt")

        assertEquals(UnlinkResult.Ok, result, "rm of already-gone remote file must succeed, not EIO")
        // Row must be tombstoned (status DELETED) — getEntry returns null (alive view), but
        // the tombstone is present so the reconciler can track the cloud deletion.
        assertEquals(null, stateDb.getEntry("/gone.txt"), "row must no longer be alive after unlink")
        val tombstone = stateDb.recovery.allEntriesAnyStatus().find { it.path == "/gone.txt" }
        assertTrue(tombstone != null, "a DELETED tombstone must remain for reconciler tracking")
    }

    @Test
    fun unlink_does_not_swallow_5xx_mentioning_404_in_message() = runTest {
        // Guards: codex P2 — a ProviderException whose MESSAGE contains "404" or
        // "not found" but does NOT carry a recognised typed not-found prefix must
        // surface as Failed, not Ok. Proves the free-text misclassification hole is closed.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/live.txt",
                remoteId = "rid-live",
                remoteHash = "hash",
                remoteSize = 10L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        // 502 proxy error whose body contains both "404" and "not found" —
        // old free-text check would have returned Ok and tombstoned a live file.
        provider.throwOnDelete = ProviderException("502 Bad Gateway: upstream /live.txt not found (404)")

        val result = impl.unlink("/live.txt")

        assertTrue(result is UnlinkResult.Failed, "5xx with '404' in message must be Failed, not Ok")
        assertTrue(stateDb.getEntry("/live.txt") != null, "row must remain alive — 5xx must not tombstone a live file")
    }

    @Test
    fun unlink_does_not_mask_non_notfound_errors() = runTest {
        // Guards: idempotent-on-gone must NOT become swallow-all-errors.
        // A real server error (5xx, auth, throttle) must surface as Failed, not Ok.
        // The row must remain alive — a real error leaves the file in an unknown state.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/real-error.txt",
                remoteId = "rid-real",
                remoteHash = "hash",
                remoteSize = 10L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider.throwOnDelete = ProviderException("500 Internal Server Error")

        val result = impl.unlink("/real-error.txt")

        assertTrue(result is UnlinkResult.Failed, "5xx error must surface as Failed, not Ok")
        assertTrue(stateDb.getEntry("/real-error.txt") != null, "row must remain alive after a real error")
    }

    @Test
    fun rmdir_treats_remote_already_gone_404_as_success() = runTest {
        // Guards: rm -d of a folder whose cloud copy is already gone must return Ok.
        // "Not empty" errors must NOT be masked — they are a distinct failure class.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/gone-dir",
                remoteId = "rid-gone-dir",
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
        provider.throwOnDelete = ProviderException("Folder not found: gone-dir in /gone-dir")

        val result = impl.rmdir("/gone-dir")

        assertEquals(RmdirResult.Ok, result, "rmdir of already-gone remote folder must succeed, not EIO")
        assertEquals(null, stateDb.getEntry("/gone-dir"), "row must no longer be alive after rmdir")
        // "not empty" must still fail — not-found idempotency must not swallow a different error class
        val (impl2, provider2, stateDb2) = freshEnv()
        stateDb2.upsertEntry(
            SyncEntry(
                path = "/nonempty-dir",
                remoteId = "rid-nonempty",
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
        provider2.throwOnDelete = RuntimeException("Folder not empty")
        assertEquals(RmdirResult.NotEmpty, impl2.rmdir("/nonempty-dir"), "not-empty must not be swallowed as gone")
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

    @Test
    fun rmdir_maps_typed_FolderNotEmpty_to_NotEmpty() = runTest {
        // The typed signal must map to ENOTEMPTY regardless of the message text:
        // a provider raising FolderNotEmptyException (even with a message that
        // contains none of the substrings the fallback matcher looks for) is
        // surfaced as NotEmpty, not Failed.
        val env = freshEnv()
        env.db.upsertEntry(
            SyncEntry(
                path = "/typed-dir",
                remoteId = "rid-typed",
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
        env.provider.throwOnDelete =
            org.krost.unidrive.FolderNotEmptyException("Verzeichnis nicht leer")

        assertEquals(RmdirResult.NotEmpty, env.impl.rmdir("/typed-dir"))
    }

    @Test
    fun unlink_evicts_hydration_cache_file() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(
            SyncEntry(
                path = "/cached.txt",
                remoteId = "rid-cached",
                remoteHash = "hash",
                remoteSize = 5L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        val cacheFile = env.engine.resolveCachePath("/cached.txt")
        Files.createDirectories(cacheFile.parent)
        Files.writeString(cacheFile, "stale")
        assertTrue(Files.exists(cacheFile), "precondition: cache file must exist before unlink")

        val result = env.impl.unlink("/cached.txt")

        assertEquals(UnlinkResult.Ok, result)
        assertTrue(
            Files.notExists(cacheFile),
            "unlink must evict the hydration-cache file so stale bytes do not linger",
        )
    }

    @Test
    fun rmdir_evicts_hydration_cache_subtree() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(
            SyncEntry(
                path = "/cdir",
                remoteId = "rid-cdir",
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
        // Populate the folder's cache subtree with a nested hydrated file.
        val nested = env.engine.resolveCachePath("/cdir/sub/file.bin")
        Files.createDirectories(nested.parent)
        Files.writeString(nested, "bytes")
        val cacheDir = env.engine.resolveCachePath("/cdir")
        assertTrue(Files.exists(cacheDir), "precondition: cache subtree must exist before rmdir")

        val result = env.impl.rmdir("/cdir")

        assertEquals(RmdirResult.Ok, result)
        assertTrue(
            Files.notExists(cacheDir),
            "rmdir must recursively evict the folder's hydration-cache subtree",
        )
    }
}
