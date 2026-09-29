package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.test.assertTrue

class HydrationImplCreateTest {

    private class CreateFakeProvider : CloudProvider {
        override val id = "fake-create"
        override val displayName = "Fake (create test)"
        override var isAuthenticated = true
        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun getMetadata(path: String): CloudItem = error("not used")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
        override suspend fun delete(remotePath: String, ifMatchETag: String?) {}
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private data class Env(
        val hydration: HydrationImpl,
        val stateDb: StateDatabase,
        val cacheRoot: Path,
    )

    private fun freshEnv(): Env {
        val cacheRoot = Files.createTempDirectory("unidrive-hydration-create-cache")
        val dbPath = Files.createTempDirectory("unidrive-hydration-create-db").resolve("state.db")
        val provider = CreateFakeProvider()
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()
        val syncRoot = Files.createTempDirectory("unidrive-hydration-create-sync")
        val engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            cacheRoot = cacheRoot,
        )
        return Env(HydrationImpl(syncEngine = engine, stateDb = db), db, cacheRoot)
    }

    @Test
    fun create_at_root_materialises_empty_cache_file_and_inserts_state_db_row() = runTest {
        val env = freshEnv()

        val result = env.hydration.create("conn-1", "create-1", "/foo.txt")

        assertTrue(result is CreateResult.Ok, "expected Ok, got $result")
        assertEquals("create-1", result.handleId)
        assertTrue(Files.exists(result.cachePath), "cache file must exist on disk")
        assertEquals(0L, Files.size(result.cachePath), "cache file must be empty")

        val entry = env.stateDb.getEntry("/foo.txt")
        assertNotNull(entry, "state.db row must exist after create")
        assertEquals(false, entry.isFolder)
        assertEquals(true, entry.isHydrated)
        assertEquals(0L, entry.localSize)
        // remote_id stays null until the upload-on-RELEASE path runs.
        assertEquals(null, entry.remoteId)
    }

    @Test
    fun create_under_existing_folder_writes_into_nested_cache_dir() = runTest {
        val env = freshEnv()

        // Pre-populate a folder row at /sub.
        env.stateDb.upsertEntry(
            SyncEntry(
                path = "/sub",
                remoteId = "rid-sub",
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

        val result = env.hydration.create("conn-1", "create-1", "/sub/bar.txt")

        assertTrue(result is CreateResult.Ok)
        assertTrue(Files.exists(result.cachePath))
        assertTrue(result.cachePath.toString().contains("sub"))
    }

    @Test
    fun create_returns_ParentNotFound_when_parent_row_missing() = runTest {
        val env = freshEnv()

        val result = env.hydration.create("conn-1", "create-1", "/missing-parent/child.txt")

        assertEquals(CreateResult.ParentNotFound, result)
        assertEquals(null, env.stateDb.getEntry("/missing-parent/child.txt"))
    }

    @Test
    fun create_returns_PathExists_when_state_db_already_holds_path() = runTest {
        val env = freshEnv()

        env.stateDb.upsertEntry(
            SyncEntry(
                path = "/already.txt",
                remoteId = "rid-already",
                remoteHash = "h",
                remoteSize = 12L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )

        val result = env.hydration.create("conn-1", "create-1", "/already.txt")

        assertEquals(CreateResult.PathExists, result)
    }

    @Test
    fun concurrent_creates_for_same_path_serialise_exactly_one_succeeds() = runTest {
        // Reproduces the TOCTOU race where two concurrent
        // `hydration.create(samePath)` callers can both pass the
        // existence check, both clobber the cache file via
        // TRUNCATE_EXISTING, and both return Ok. The per-path mutex
        // in HydrationImpl.create must serialise the check+materialise
        // +upsert tuple so the loser sees the winner's row and returns
        // PathExists. Without the fix, both calls return Ok and the
        // assertion below fires.
        val env = freshEnv()
        val path = "/concurrent-create.txt"
        val parallelism = 8

        val results = coroutineScope {
            (0 until parallelism).map { idx ->
                async {
                    env.hydration.create("conn-$idx", "create-$idx", path)
                }
            }.awaitAll()
        }

        val oks = results.count { it is CreateResult.Ok }
        val pathExists = results.count { it === CreateResult.PathExists }
        assertEquals(
            1, oks,
            "exactly one concurrent create must win; got $oks Ok, $pathExists PathExists (results: $results)",
        )
        assertEquals(
            parallelism - 1, pathExists,
            "every loser must see PathExists, not Ok or Failed (results: $results)",
        )
        assertNotNull(env.stateDb.getEntry(path))
    }
}
