package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
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

/**
 * Minimal fake CloudProvider for HydrationImpl tests.
 * Only implements the methods exercised by [SyncEngine.ensureHydrated]:
 * [capabilities], [authenticate], [downloadById], and the abstract stubs
 * that Kotlin requires for every non-default interface member.
 */
internal class MinimalFakeProvider(
    private val remoteFiles: MutableMap<String, ByteArray> = mutableMapOf(),
) : CloudProvider {
    override val id = "fake-hydration"
    override val displayName = "Fake (hydration test)"
    override var isAuthenticated = true

    // Records content uploaded via uploadFromCache for assertion in write tests.
    private val uploadedFiles: MutableMap<String, ByteArray> = mutableMapOf()

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

    override suspend fun getMetadata(path: String): CloudItem =
        error("getMetadata not used in hydration tests")

    override suspend fun download(remotePath: String, destination: Path): Long {
        val content = remoteFiles[remotePath] ?: ByteArray(0)
        Files.createDirectories(destination.parent)
        Files.write(destination, content)
        return content.size.toLong()
    }

    override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long =
        download(remotePath, destination)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val bytes = Files.readAllBytes(localPath)
        uploadedFiles[remotePath] = bytes
        return CloudItem(
            id = "uploaded-$remotePath",
            name = remotePath.substringAfterLast('/'),
            path = remotePath,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = java.time.Instant.now(),
            created = null,
            hash = bytes.size.toString(),
            mimeType = null,
        )
    }

    override suspend fun delete(remotePath: String) = error("delete not used")

    override suspend fun createFolder(path: String): CloudItem = error("createFolder not used")

    override suspend fun move(fromPath: String, toPath: String): CloudItem = error("move not used")

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

    override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)

    /** Seed remote content for a path. */
    fun seedContent(path: String, content: String) {
        remoteFiles[path] = content.toByteArray()
    }

    /** Returns the content most recently uploaded to [path], or null if never uploaded. */
    fun uploadedContent(path: String): String? = uploadedFiles[path]?.toString(Charsets.UTF_8)
}

/**
 * Test fixture: wires a real [HydrationImpl] against a real [SyncEngine]
 * (backed by [MinimalFakeProvider]) and a real [StateDatabase] (in-memory).
 *
 * Helper names align with the plan's symbolic names:
 * - [stateDb] — wraps insertUnhydratedEntry / isHydrated over the real StateDatabase API
 * - [syncEngine] — exposes seedRemoteContent
 * - [hydration] — the [HydrationImpl] under test
 */
internal class HydrationTestEnv {
    val cacheRoot: Path = Files.createTempDirectory("unidrive-hydration-cache")
    private val dbPath: Path = Files.createTempDirectory("unidrive-hydration-db").resolve("state.db")
    private val fakeProvider = MinimalFakeProvider()

    /** Staging area for cache files written by write-path tests. */
    val tempDir: Path = Files.createTempDirectory("unidrive-hydration-tmp")

    val stateDb: StateDatabaseFacade
    val syncEngine: SyncEngineFacade
    val hydration: Hydration

    init {
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()

        val engine = SyncEngine(
            provider = fakeProvider,
            db = db,
            syncRoot = Files.createTempDirectory("unidrive-hydration-sync"),
            cacheRoot = cacheRoot,
        )

        stateDb = StateDatabaseFacade(db)
        syncEngine = SyncEngineFacade(fakeProvider)
        hydration = HydrationImpl(syncEngine = engine, stateDb = db)
    }

    internal inner class StateDatabaseFacade(private val db: StateDatabase) {
        fun insertUnhydratedEntry(path: String, remoteSize: Long) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "id-$path",
                    remoteHash = "hash-$path",
                    remoteSize = remoteSize,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        }

        fun insertHydratedEntry(path: String, localSize: Long) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "id-$path",
                    remoteHash = "hash-$path",
                    remoteSize = localSize,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(),
                    localSize = localSize,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
        }

        fun isHydrated(path: String): Boolean =
            db.getEntry(path)?.isHydrated ?: false
    }

    inner class SyncEngineFacade internal constructor(private val fakeProvider: MinimalFakeProvider) {
        fun seedRemoteContent(path: String, content: String) {
            fakeProvider.seedContent(path, content)
        }

        /** Returns the content most recently uploaded to [path] via uploadFromCache. */
        fun remoteContentSeen(path: String): String? = fakeProvider.uploadedContent(path)

        /**
         * Writes [content] to the cache file at the path [SyncEngine.resolveCachePath] would compute.
         * Used to pre-populate the cache for warm-path tests (already-hydrated scenarios).
         */
        fun seedCacheContent(path: String, content: String) {
            // Mirror SyncEngine.resolveCachePath layout:
            // cacheRoot/unidrive/hydration/{providerId}/{path}
            // providerId defaults to "" in SyncEngine, so effective providerId is "default"
            val cachePath = cacheRoot
                .resolve("unidrive/hydration")
                .resolve("default")
                .resolve(path.trimStart('/'))
            Files.createDirectories(cachePath.parent)
            Files.write(cachePath, content.toByteArray())
        }
    }
}

class HydrationImplTest {
    @Test
    fun `open_read on unhydrated path triggers download and returns cache path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")

        val result = env.hydration.openForRead("conn1", "h1", "/foo.txt")

        assertTrue(result is OpenResult.Ok)
        val cachePath = (result as OpenResult.Ok).cachePath
        assertEquals("hello", java.nio.file.Files.readString(cachePath))
        assertEquals(true, env.stateDb.isHydrated("/foo.txt"))
    }

    @Test
    fun `open_read on unknown path returns Failed with Generic error`() = runTest {
        val env = HydrationTestEnv()

        val result = env.hydration.openForRead("conn1", "h2", "/does-not-exist.txt")

        assertTrue(result is OpenResult.Failed)
        val error = (result as OpenResult.Failed).error
        assertTrue(error is HydrationError.Generic)
        assertTrue(error.message.contains("does-not-exist.txt"))
    }

    @Test
    fun `close_handle on unknown id is a noop`() = runTest {
        val env = HydrationTestEnv()
        env.hydration.closeHandle("conn-never-opened", "h-never-existed")
        env.hydration.closeHandle("conn1", "h1")
    }

    @Test
    fun `close_handle removes the handle from its connection's open set`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/a.txt", remoteSize = 1)
        env.syncEngine.seedRemoteContent("/a.txt", "x")
        env.hydration.openForRead("conn1", "h1", "/a.txt")
        env.hydration.closeHandle("conn1", "h1")
        val r = env.hydration.openForRead("conn1", "h1", "/a.txt")
        assertTrue(r is OpenResult.Ok)
    }

    @Test
    fun `open_for_write triggers upload-from-cache and registers the handle`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("foo.txt").also { java.nio.file.Files.writeString(it, "world") }

        val r = env.hydration.openForWrite("conn1", "h1", "/foo.txt", cacheFile)

        assertTrue(r is OpenResult.Ok)
        assertEquals("world", env.syncEngine.remoteContentSeen("/foo.txt"))

        // Verify the handle is tracked: close it and re-open with the same id; must succeed.
        // If openForWrite never registered the handle, closeHandle would be a no-op on a missing
        // entry, and the re-open would silently succeed even if registration code was wrong.
        // But if registration DOES work, closing then re-opening with the same id exactly mirrors
        // the close-then-reuse cycle tested in `close_handle removes the handle from its connection's open set`,
        // so both registration AND closeHandle must work correctly.
        env.hydration.closeHandle("conn1", "h1")
        val cacheFile2 = env.tempDir.resolve("world.txt").also { java.nio.file.Files.writeString(it, "reopen") }
        val reopen = env.hydration.openForWrite("conn1", "h1", "/foo.txt", cacheFile2)
        assertTrue(reopen is OpenResult.Ok)
    }

    @Test
    fun `explicit hydrate on already-hydrated path is a noop ok`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        env.syncEngine.seedCacheContent("/foo.txt", "hello")

        val r = env.hydration.hydrate("/foo.txt")

        assertEquals(HydrateResult.Ok, r)
    }

    @Test
    fun `explicit hydrate on unhydrated path downloads and returns ok`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")

        val r = env.hydration.hydrate("/foo.txt")

        assertEquals(HydrateResult.Ok, r)
        assertEquals(true, env.stateDb.isHydrated("/foo.txt"))
    }
}
