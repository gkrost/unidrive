package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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

    // Throw-injection for testing error paths
    private var nextThrowable: Throwable? = null

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

    override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long {
        nextThrowable?.also {
            nextThrowable = null
            throw it
        }
        return download(remotePath, destination)
    }

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

    /** Configure the next downloadById call to throw the given exception. */
    fun makeNextDownloadThrow(throwable: Throwable) {
        nextThrowable = throwable
    }
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
        syncEngine = SyncEngineFacade(fakeProvider, engine)
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

        fun insertFolderEntry(path: String) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "id-$path",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(),
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        }

        fun isHydrated(path: String): Boolean =
            db.getEntry(path)?.isHydrated ?: false

        /**
         * Insert a created-but-never-uploaded row (remoteId = null), exactly
         * the shape HydrationImpl.create writes for a file made through the
         * mount before its upload runs.
         */
        fun insertCreatedRow(path: String) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
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
        }

        fun lastErrorAt(path: String): Instant? = db.getEntry(path)?.lastErrorAt

        fun countWriteUploadFailed(): Int = db.countWriteUploadFailed()
    }

    inner class SyncEngineFacade internal constructor(
        private val fakeProvider: MinimalFakeProvider,
        private val syncEngine: SyncEngine,
    ) {
        fun seedRemoteContent(path: String, content: String) {
            fakeProvider.seedContent(path, content)
        }

        /** Returns the content most recently uploaded to [path] via uploadFromCache. */
        fun remoteContentSeen(path: String): String? = fakeProvider.uploadedContent(path)

        /** Resolves a path to its cache location. */
        fun resolveCachePath(path: String): Path = syncEngine.resolveCachePath(path)

        /**
         * Writes [content] to the cache file at the path [SyncEngine.resolveCachePath] would compute.
         * Used to pre-populate the cache for warm-path tests (already-hydrated scenarios).
         */
        fun seedCacheContent(path: String, content: String) {
            val cachePath = syncEngine.resolveCachePath(path)
            Files.createDirectories(cachePath.parent)
            Files.writeString(cachePath, content)
        }

        /** Configure the next downloadById call to throw the given exception. */
        fun makeNextDownloadThrow(throwable: Throwable) {
            fakeProvider.makeNextDownloadThrow(throwable)
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
    fun `openForWrite_marks_row_on_upload_failure`() = runTest {
        val env = HydrationTestEnv()
        // A file created through the mount, never uploaded (remoteId = null).
        env.stateDb.insertCreatedRow("/draft.txt")
        // Force the upload to fail: uploadFromCache require()s the cache file
        // to exist; point at a path that doesn't. The close() already returned
        // 0 to the user, so the only durability surface is the state.db stamp.
        val missingCache = env.tempDir.resolve("does-not-exist.txt")

        val r = env.hydration.openForWrite("conn1", "h1", "/draft.txt", missingCache)

        assertTrue(r is OpenResult.Failed, "a failed upload must surface OpenResult.Failed")
        assertTrue(
            env.stateDb.lastErrorAt("/draft.txt") != null,
            "upload failure must stamp last_error_at so doctor can surface the unsynced row",
        )
        assertEquals(
            1,
            env.stateDb.countWriteUploadFailed(),
            "the never-uploaded + stamped row must count as written-but-not-synced",
        )
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

    @Test
    fun `dehydrate refuses while a handle is open across any connection`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        env.syncEngine.seedCacheContent("/foo.txt", "hello")

        env.hydration.openForRead("conn1", "h1", "/foo.txt")

        assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/foo.txt"))
        // Busy must not have side-effects: cache must still be there for the open handle.
        assertTrue(java.nio.file.Files.exists(env.syncEngine.resolveCachePath("/foo.txt")))
    }

    @Test
    fun `dehydrate succeeds once all handles are closed`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        env.syncEngine.seedCacheContent("/foo.txt", "hello")

        env.hydration.openForRead("conn1", "h1", "/foo.txt")
        env.hydration.openForRead("conn2", "h1", "/foo.txt")  // different connection, same handle-id

        assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/foo.txt"))
        env.hydration.closeHandle("conn1", "h1")
        assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/foo.txt"))  // still conn2
        env.hydration.closeHandle("conn2", "h1")
        assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/foo.txt"))
        // Verify the side-effects of dehydrate(Ok) actually landed.
        assertEquals(false, env.stateDb.isHydrated("/foo.txt"))
        assertTrue(java.nio.file.Files.notExists(env.syncEngine.resolveCachePath("/foo.txt")))
    }

    @Test
    fun `dehydrate on unknown path returns Failed not Ok`() = runTest {
        val env = HydrationTestEnv()
        val r = env.hydration.dehydrate("/never-existed.txt")
        assertTrue(r is DehydrateResult.Failed)
        assertTrue(r.error.message.startsWith("Unknown path:"))
    }

    @Test
    fun `ipc disconnect clears that connection's open set entirely`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/a.txt", localSize = 1)
        env.stateDb.insertHydratedEntry("/b.txt", localSize = 1)
        env.syncEngine.seedCacheContent("/a.txt", "x")
        env.syncEngine.seedCacheContent("/b.txt", "y")

        env.hydration.openForRead("conn1", "h1", "/a.txt")
        env.hydration.openForRead("conn1", "h2", "/b.txt")
        assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/a.txt"))

        env.hydration.onConnectionClosed("conn1")

        assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/a.txt"))
        assertEquals(false, env.stateDb.isHydrated("/a.txt"))
        assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/b.txt"))
        assertEquals(false, env.stateDb.isHydrated("/b.txt"))
    }

    @Test
    fun `events flow emits Hydrating then Hydrated for successful open_read`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")

        val collected = mutableListOf<HydrationEvent>()
        val job = launch { env.hydration.events.collect { collected += it } }

        // Yield to ensure the collector coroutine has subscribed before we emit
        yield()

        env.hydration.openForRead("conn1", "h1", "/foo.txt")

        // Yield long enough for the SharedFlow to deliver
        yield(); yield()
        job.cancel()

        assertEquals(2, collected.size)
        assertTrue(collected[0] is HydrationEvent.Hydrating)
        assertTrue(collected[1] is HydrationEvent.Hydrated)
    }

    @Test
    fun `lastSynced returns Ok with mtime for hydrated path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)

        val r = env.hydration.lastSynced("/foo.txt")

        assertTrue(r is LastSyncedResult.Ok)
        assertEquals(Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(), (r as LastSyncedResult.Ok).mtimeEpochMillis)
    }

    @Test
    fun `lastSynced returns Unknown for unknown path`() = runTest {
        val env = HydrationTestEnv()

        val r = env.hydration.lastSynced("/never-existed.txt")

        assertTrue(r is LastSyncedResult.Unknown)
        assertEquals("unknown_path", (r as LastSyncedResult.Unknown).reason)
    }

    @Test
    fun `lastSynced returns Unknown for unhydrated path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)

        val r = env.hydration.lastSynced("/foo.txt")

        assertTrue(r is LastSyncedResult.Unknown)
    }

    @Test
    fun `list returns direct children of prefix only`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/Documents")
        env.stateDb.insertHydratedEntry("/Documents/foo.txt", localSize = 5)
        env.stateDb.insertHydratedEntry("/Documents/bar.txt", localSize = 7)
        env.stateDb.insertFolderEntry("/Documents/sub")
        // Deeper descendant must NOT be returned.
        env.stateDb.insertHydratedEntry("/Documents/sub/deep.txt", localSize = 3)
        // Sibling at root must NOT be returned.
        env.stateDb.insertHydratedEntry("/other.txt", localSize = 2)

        val r = env.hydration.list("/Documents")

        assertTrue(r is ListResult.Ok)
        val paths = (r as ListResult.Ok).entries.map { it.path }.toSet()
        assertEquals(setOf("/Documents/foo.txt", "/Documents/bar.txt", "/Documents/sub"), paths)
    }

    @Test
    fun `list returns empty for prefix with no children`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/Empty")

        val r = env.hydration.list("/Empty")

        assertTrue(r is ListResult.Ok)
        assertEquals(emptyList(), (r as ListResult.Ok).entries)
    }

    @Test
    fun `list normalises trailing slash on prefix`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/Documents")
        env.stateDb.insertHydratedEntry("/Documents/foo.txt", localSize = 5)

        val withSlash = env.hydration.list("/Documents/")
        val withoutSlash = env.hydration.list("/Documents")

        assertTrue(withSlash is ListResult.Ok)
        assertTrue(withoutSlash is ListResult.Ok)
        assertEquals(
            (withSlash as ListResult.Ok).entries.map { it.path },
            (withoutSlash as ListResult.Ok).entries.map { it.path },
        )
    }

    @Test
    fun `list returns root children for prefix slash`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/a.txt", localSize = 1)
        env.stateDb.insertFolderEntry("/Documents")
        env.stateDb.insertHydratedEntry("/Documents/foo.txt", localSize = 5)

        val r = env.hydration.list("/")

        assertTrue(r is ListResult.Ok)
        val paths = (r as ListResult.Ok).entries.map { it.path }.toSet()
        assertEquals(setOf("/a.txt", "/Documents"), paths)
    }

    @Test
    fun `list returns both files and folders with correct isFolder flag`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/Documents")
        env.stateDb.insertHydratedEntry("/Documents/file.txt", localSize = 5)
        env.stateDb.insertFolderEntry("/Documents/subdir")

        val r = env.hydration.list("/Documents")

        assertTrue(r is ListResult.Ok)
        val byPath = (r as ListResult.Ok).entries.associateBy { it.path }
        assertEquals(false, byPath.getValue("/Documents/file.txt").isFolder)
        assertEquals(true, byPath.getValue("/Documents/subdir").isFolder)
    }

    @Test
    fun `list returns size and mtime from state db`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/Documents")
        env.stateDb.insertHydratedEntry("/Documents/foo.txt", localSize = 42)

        val r = env.hydration.list("/Documents")

        assertTrue(r is ListResult.Ok)
        val e = (r as ListResult.Ok).entries.single { it.path == "/Documents/foo.txt" }
        assertEquals(42L, e.size)
        assertEquals(Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(), e.mtimeEpochMillis)
        assertEquals(true, e.isHydrated)
    }

    @Test
    fun `events flow emits Failed when download throws`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.makeNextDownloadThrow(RuntimeException("boom"))

        val collected = mutableListOf<HydrationEvent>()
        val job = launch { env.hydration.events.collect { collected += it } }

        // Yield to ensure the collector coroutine has subscribed before we emit
        yield()

        val r = env.hydration.openForRead("conn1", "h1", "/foo.txt")

        yield(); yield()
        job.cancel()

        assertTrue(r is OpenResult.Failed)
        assertTrue(collected.any { it is HydrationEvent.Failed })
    }
}
