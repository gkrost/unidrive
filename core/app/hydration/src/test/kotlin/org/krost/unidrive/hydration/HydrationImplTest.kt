package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import org.krost.unidrive.ProviderException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Minimal fake CloudProvider for HydrationImpl tests.
 * Only implements the methods exercised by [SyncEngine.ensureHydrated]:
 * [capabilities], [authenticate], [downloadById], and the abstract stubs
 * that Kotlin requires for every non-default interface member.
 */
internal class MinimalFakeProvider(
    private val remoteFiles: MutableMap<String, ByteArray> = mutableMapOf(),
    override val id: String = "fake-hydration",
) : CloudProvider {
    override val displayName = "Fake (hydration test)"
    override var isAuthenticated = true

    // Records content uploaded via uploadFromCache for assertion in write tests.
    private val uploadedFiles: MutableMap<String, ByteArray> = mutableMapOf()

    // Throw-injection for testing error paths
    private var nextThrowable: Throwable? = null

    // WB-3 (#87): the local paths whose staged upload copies unlink asked to discard.
    val discardedStagedUploads = mutableListOf<String>()

    override suspend fun discardStagedUpload(localPath: String) {
        discardedStagedUploads.add(localPath)
    }

    // Optional gate: when set, upload() suspends until this deferred completes.
    // Used by the promptness test to prove openForWrite returns before the upload finishes.
    var uploadGate: CompletableDeferred<Unit>? = null

    // Upload failure injection: upload() throws while this is > 0 (decremented
    // per call). Drives the retry tests: set to N for N transient failures,
    // to a huge number for a permanent failure.
    val uploadFailuresRemaining = AtomicInteger(0)

    // Like [uploadFailuresRemaining] but the failure is a RemoteConflictException (the
    // provider's "remote changed under the edit" refusal).
    val uploadConflictRemaining = AtomicInteger(0)

    // #493: like [uploadFailuresRemaining] but the failure is a PermanentUploadFailureException (the provider refused
    // the request itself).
    val uploadRefusedRemaining = AtomicInteger(0)

    // Upload progress emulation: progressSteps > 0 makes upload() report
    // onProgress in [progressSteps] evenly spaced steps; progressPaceMs > 0
    // additionally delays between steps (real-time coalescing tests).
    var progressSteps: Int = 0
    var progressPaceMs: Long = 0

    // Total upload() invocations (attempt counting for the retry tests) and
    // daemon-wide concurrency tracking for the transfer-cap test.
    private val uploadAttempts = AtomicInteger(0)
    private val activeUploadsTotal = AtomicInteger(0)
    private val maxConcurrentUploadsTotal = AtomicInteger(0)
    private val completedUploads = AtomicInteger(0)
    var lastUploadIfMatch: String? = "not-called"
        private set

    // Per-path concurrency tracking for the serialization test.
    // activeUploadsByPath: how many upload coroutines are currently inside upload() for each path.
    // maxConcurrentUploadsByPath: the peak observed value of activeUploadsByPath per path.
    // Both are updated atomically on entry/exit of each upload invocation.
    private val activeUploadsByPath = ConcurrentHashMap<String, AtomicInteger>()
    private val maxConcurrentUploadsByPath = ConcurrentHashMap<String, AtomicInteger>()

    var downloadCount: Int = 0
        private set

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

    // WB-3 (#87): the unlink ghost check probes the cloud for a local: row's content. A path with
    // no seeded remote content answers "not found" the way the provider would (a 404-shaped
    // exception that remoteItemOrNull maps to null).
    override suspend fun getMetadata(path: String): CloudItem {
        remoteFiles[path]
            ?: throw ProviderException("Item not found: $path")
        return CloudItem(
            id = "id-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = 1,
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T12:00:00Z"),
            hash = null,
            mimeType = null,
        )
    }

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
        downloadCount++
        return download(remotePath, destination)
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        lastUploadIfMatch = ifMatchETag
        uploadAttempts.incrementAndGet()
        if (uploadFailuresRemaining.getAndUpdate { p -> if (p > 0) p - 1 else p } > 0) {
            throw IllegalStateException("injected upload failure")
        }
        uploadConflictRemaining.getAndUpdate { p -> if (p > 0) p - 1 else p }.let { before ->
            if (before > 0) throw org.krost.unidrive.RemoteConflictException("injected remote conflict: $remotePath")
        }
        uploadRefusedRemaining.getAndUpdate { p -> if (p > 0) p - 1 else p }.let { before ->
            if (before > 0) throw org.krost.unidrive.PermanentUploadFailureException("injected refusal: $remotePath")
        }
        // Track per-path concurrency: record entry, update peak, then suspend on gate if set.
        val active = activeUploadsByPath.computeIfAbsent(remotePath) { AtomicInteger(0) }
        val peak = maxConcurrentUploadsByPath.computeIfAbsent(remotePath) { AtomicInteger(0) }
        val currentActive = active.incrementAndGet()
        // Update peak if current active count exceeds the recorded maximum.
        peak.getAndUpdate { prev -> maxOf(prev, currentActive) }
        val totalNow = activeUploadsTotal.incrementAndGet()
        maxConcurrentUploadsTotal.getAndUpdate { prev -> maxOf(prev, totalNow) }

        try {
            uploadGate?.await()
            val bytes = Files.readAllBytes(localPath)
            remoteFiles[remotePath] = bytes
            if (progressSteps > 0 && onProgress != null) {
                val total = bytes.size.toLong()
                for (i in 1..progressSteps) {
                    if (progressPaceMs > 0) kotlinx.coroutines.delay(progressPaceMs)
                    onProgress(total * i / progressSteps, total)
                }
            }
            uploadedFiles[remotePath] = bytes
            completedUploads.incrementAndGet()
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
        } finally {
            active.decrementAndGet()
            activeUploadsTotal.decrementAndGet()
        }
    }

    // Real (recording) delete/move: the safe-save rename sequence deletes the
    // replace destination and moves the uploaded source in the cloud.
    val deletedPaths = mutableListOf<String>()
    val movedPairs = mutableListOf<Pair<String, String>>()

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        deletedPaths.add(remotePath)
        remoteFiles.remove(remotePath)
    }

    // Folders created through createRemoteFolder, recorded for the scope-guard
    // tests (a refused mkdir must not reach the provider).
    val createdFolders = mutableListOf<String>()

    override suspend fun createFolder(path: String): CloudItem {
        createdFolders.add(path)
        return CloudItem(
            id = "folder-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = 0L,
            isFolder = true,
            modified = java.time.Instant.now(),
            created = null,
            hash = null,
            mimeType = null,
        )
    }

    override suspend fun move(fromPath: String, toPath: String): CloudItem {
        movedPairs.add(fromPath to toPath)
        val bytes = remoteFiles.remove(fromPath) ?: ByteArray(0)
        remoteFiles[toPath] = bytes
        return CloudItem(
            id = "uploaded-$toPath",
            name = toPath.substringAfterLast('/'),
            path = toPath,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = java.time.Instant.now(),
            created = null,
            hash = bytes.size.toString(),
            mimeType = null,
        )
    }

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

    /** Live remote object content at [path] (reflects delete/move too). */
    fun remoteContent(path: String): String? = remoteFiles[path]?.toString(Charsets.UTF_8)

    /** Configure the next downloadById call to throw the given exception. */
    fun makeNextDownloadThrow(throwable: Throwable) {
        nextThrowable = throwable
    }

    /**
     * Returns the peak number of concurrent upload() calls observed for [path].
     * Used by the serialization test to assert max-1-concurrent-per-path.
     */
    fun maxConcurrentUploadsForPath(path: String): Int =
        maxConcurrentUploadsByPath[path]?.get() ?: 0

    /** Total upload() invocations seen so far (attempt counter for retry tests). */
    fun uploadAttempts(): Int = uploadAttempts.get()

    /** Peak number of upload() calls in flight across ALL paths (cap test). */
    fun maxConcurrentUploadsTotal(): Int = maxConcurrentUploadsTotal.get()

    /** upload() calls that ran to completion (drain detection in the cap test). */
    fun completedUploads(): Int = completedUploads.get()
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
internal class HydrationTestEnv(
    /** Optional scope for recovery uploads. Pass the [runTest] scope to control
     *  background-job dispatch in recovery-path tests; null uses the default. */
    recoveryUploadScope: CoroutineScope? = null,
    /** Standing sync scope (sync_path set) for the scope-guard tests; empty = whole drive. */
    val syncPaths: List<String> = emptyList(),
    /** Configured exclude patterns for the keep-local tests. */
    val excludePatterns: List<String> = emptyList(),
    /** Upload-queue tuning passed through to [HydrationImpl]. */
    val uploadQueueDepth: Int = 256,
    val maxUploadAttempts: Int = 3,
    val uploadRetryDelaysMs: List<Long> = listOf(2_000L, 10_000L),
    /** Coalescing gap for `uploading` progress events (0 = emit every callback). */
    val uploadProgressMinIntervalMs: Long = 400,
    /** #493: delay of the replay of rows whose last upload failed; 0 = at once. */
    val failedReplayDelayMs: Long = HydrationImpl.DEFAULT_FAILED_REPLAY_DELAY_MS,
    providerId: String = "fake-hydration",
) {
    val cacheRoot: Path = Files.createTempDirectory("unidrive-hydration-cache")
    private val dbPath: Path = Files.createTempDirectory("unidrive-hydration-db").resolve("state.db")
    private val fakeProvider = MinimalFakeProvider(id = providerId)

    /** Staging area for cache files written by write-path tests. */
    val tempDir: Path = Files.createTempDirectory("unidrive-hydration-tmp")

    val stateDb: StateDatabaseFacade
    val syncEngine: SyncEngineFacade
    val hydration: HydrationImpl

    /** Direct access to the fake provider (upload gate, failure/counters). */
    val providerForTest: MinimalFakeProvider get() = fakeProvider

    init {
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()

        val engine = SyncEngine(
            provider = fakeProvider,
            db = db,
            syncRoot = Files.createTempDirectory("unidrive-hydration-sync"),
            cacheRoot = cacheRoot,
            syncPaths = syncPaths,
            standingScope = syncPaths,
            excludePatterns = excludePatterns,
        )

        stateDb = StateDatabaseFacade(db)
        syncEngine = SyncEngineFacade(fakeProvider, engine)
        hydration = HydrationImpl(
            syncEngine = engine,
            stateDb = db,
            recoveryUploadScope = recoveryUploadScope ?: CoroutineScope(Dispatchers.IO),
            uploadQueueDepth = uploadQueueDepth,
            maxUploadAttempts = maxUploadAttempts,
            uploadRetryDelaysMs = uploadRetryDelaysMs,
            uploadProgressMinIntervalMs = uploadProgressMinIntervalMs,
            failedReplayDelayMs = failedReplayDelayMs,
        )
    }

    internal inner class StateDatabaseFacade(private val db: StateDatabase) {
        fun insertUnhydratedEntry(
            path: String,
            remoteSize: Long,
            isFolder: Boolean = false,
            remoteId: String? = "id-$path",
        ) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = remoteId,
                    remoteHash = "hash-$path",
                    remoteSize = remoteSize,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = isFolder,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        }

        /** The row's status regardless of alive/dead (#87 tombstone checks); null when no row exists. */
        fun statusOf(path: String): org.krost.unidrive.sync.model.EntryStatus? = db.statusOf(path)

        fun insertHydratedEntry(
            path: String,
            localSize: Long,
            remoteHash: String = "hash-$path",
        ) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "id-$path",
                    remoteHash = remoteHash,
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

        fun remoteSizeOf(path: String): Long? = db.getEntry(path)?.remoteSize

        // A file created/edited through the mount but not yet uploaded: remoteId
        // null (local: synthetic), remoteSize 0, isHydrated true; the cache holds the
        // just-written bytes and is the only copy. The warm path must trust it.
        fun insertLocalOnlyHydratedEntry(path: String) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(),
                    localSize = null,
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

        /** Drops the row outright (a rename-away / unlink / reap while an upload is queued). */
        fun deleteRow(path: String) = db.deleteEntry(path)

        /** The row's local-mtime watermark (what `hydration.last_synced` reports). */
        fun localMtimeOf(path: String): Long? = db.getEntry(path)?.localMtime

        fun markUploadFailed(path: String, at: Instant): Boolean = db.markUploadFailed(path, at)

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

        fun lastUploadIfMatch(): String? = fakeProvider.lastUploadIfMatch

        /** Resolves a path to its cache location. */
        fun resolveCachePath(path: String): Path = syncEngine.resolveCachePath(path)

        /** Folders the provider was asked to create (scope-guard assertions). */
        fun createdFolders(): List<String> = fakeProvider.createdFolders

        /** Injects [count] RemoteConflictException refusals from upload() before the next success. */
        fun conflictUploads(count: Int) {
            fakeProvider.uploadConflictRemaining.set(count)
        }

        /** #493: injects [count] PermanentUploadFailureException refusals from upload() before the next success. */
        fun refuseUploads(count: Int) {
            fakeProvider.uploadRefusedRemaining.set(count)
        }

        /** Injects [count] upload() failures before the next success. */
        fun failUploads(count: Int) {
            fakeProvider.uploadFailuresRemaining.set(count)
        }

        /** Total upload() attempts seen by the provider (retry tests). */
        fun uploadAttempts(): Int = fakeProvider.uploadAttempts()

        /** Peak concurrent upload() calls across all paths (transfer-cap test). */
        fun maxConcurrentUploadsTotal(): Int = fakeProvider.maxConcurrentUploadsTotal()

        /** Live remote object content at [path] (reflects delete/move too). */
        fun remoteContent(path: String): String? = fakeProvider.remoteContent(path)

        fun deletedPaths(): List<String> = fakeProvider.deletedPaths
        fun movedPairs(): List<Pair<String, String>> = fakeProvider.movedPairs

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

        /** Number of times downloadById has been called on the fake provider. */
        fun downloadCount(): Int = fakeProvider.downloadCount

        /**
         * Install a gate on the fake provider's upload method: the next (and every
         * subsequent) upload call suspends until [gate] is completed.  Used by the
         * promptness test to hold the upload in flight while asserting that
         * openForWrite already returned Ok.
         */
        fun setUploadGate(gate: CompletableDeferred<Unit>) {
            fakeProvider.uploadGate = gate
        }

        /**
         * Returns the peak number of concurrent upload() calls observed for [path].
         * Used by the per-path serialization test to assert max-1-concurrent-per-path.
         */
        fun maxConcurrentUploadsForPath(path: String): Int =
            fakeProvider.maxConcurrentUploadsForPath(path)
    }
}

class HydrationImplTest {
    // Extracts the wire error token from any Failed result of a state-db-precondition
    // verb. Fails the test loudly if the verb did not fail (an unknown path must fail).
    private fun tokenOf(result: Any?): String = when (result) {
        is OpenResult.Failed -> result.error.message
        is DehydrateResult.Failed -> result.error.message
        is UnlinkResult.Failed -> result.error.message
        is RmdirResult.Failed -> result.error.message
        else -> error("expected a Failed result for an unknown path; got $result")
    }

    @Test
    fun `open_read on unhydrated path triggers download and returns cache path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")

        val result = env.hydration.openForRead("conn1", "h1", "/foo.txt")

        assertTrue(result is OpenResult.Ok)
        val cachePath = result.cachePath
        assertEquals("hello", java.nio.file.Files.readString(cachePath))
        assertEquals(true, env.stateDb.isHydrated("/foo.txt"))
    }

    @Test
    fun `open_read on unknown path returns the typed unknown_path token`() = runTest {
        val env = HydrationTestEnv()

        val result = env.hydration.openForRead("conn1", "h2", "/does-not-exist.txt")

        assertTrue(result is OpenResult.Failed)
        val error = result.error
        assertTrue(error is HydrationError.UnknownPath)
        assertEquals("unknown_path", error.message)
    }

    // #207: a hydrated row over a truncated/0-byte cache (crash mid-download,
    // external clear, reset/poll window) must NOT be served as-is — ensureHydrated
    // re-downloads when the cached size doesn't match the remote size.
    @Test
    fun `open_read re-downloads when a hydrated cache is truncated below the remote size`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 11) // remoteSize = 11
        env.syncEngine.seedRemoteContent("/foo.txt", "hello world") // the real 11 bytes
        env.syncEngine.seedCacheContent("/foo.txt", "")             // stale 0-byte cache

        val result = env.hydration.openForRead("conn1", "h1", "/foo.txt")

        assertTrue(result is OpenResult.Ok, "must re-download, not serve the truncated cache")
        val cachePath = result.cachePath
        assertEquals("hello world", java.nio.file.Files.readString(cachePath))
        assertEquals(1, env.syncEngine.downloadCount(), "the size-mismatched cache must trigger a re-download")
    }

    // #207 + C7: completeness is enforced at the provider — a download that comes up
    // short of the declared remote size throws there (see the per-provider tests). The
    // hydration layer must surface that as a typed failure (→ EIO, retryable), never
    // serve truncated content as success.
    @Test
    fun `open_read fails when the provider rejects an incomplete download`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/bar.txt", remoteSize = 100)
        env.syncEngine.makeNextDownloadThrow(
            IllegalStateException("Incomplete download of /bar.txt: got 5 of 100 bytes"),
        )

        val result = env.hydration.openForRead("conn1", "h1", "/bar.txt")

        assertTrue(result is OpenResult.Failed, "a short hydration must fail, not serve truncated content")
        assertTrue(result.error is HydrationError.Generic)
    }

    // C1/C7: when the remote changed size since the last enumeration, ensureHydrated
    // re-downloads and persists the FRESH size as remoteSize. Before the fix the
    // pre-download snapshot (stale remoteSize) made openForRead EIO a perfectly valid
    // file. The cache must be served and the row's remoteSize refreshed to match.
    @Test
    fun `open_read succeeds and refreshes remoteSize when the remote grew since enumeration`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/big.txt", remoteSize = 5) // stale: last enum saw 5
        env.syncEngine.seedRemoteContent("/big.txt", "hello world") // remote is now 11 bytes

        val result = env.hydration.openForRead("conn1", "h1", "/big.txt")

        assertTrue(result is OpenResult.Ok, "a grown remote must hydrate, not EIO on the stale size")
        assertEquals("hello world", java.nio.file.Files.readString(result.cachePath))
        assertEquals(11L, env.stateDb.remoteSizeOf("/big.txt"), "remoteSize must be refreshed to the downloaded size")
    }

    // #207 regression: a local-only hydrated file (created via the mount, not yet
    // uploaded — remoteId null, remoteSize 0) must be served from its cache on the
    // warm path, NEVER re-downloaded (there is no remote object yet, and the cache
    // is the only copy of the user's just-written bytes).
    @Test
    fun `open_read serves a local-only hydrated cache without re-downloading`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertLocalOnlyHydratedEntry("/draft.txt")
        env.syncEngine.seedCacheContent("/draft.txt", "just typed this") // 15 bytes, only copy

        val result = env.hydration.openForRead("conn1", "h1", "/draft.txt")

        assertTrue(result is OpenResult.Ok, "local-only hydrated row must be served from cache")
        assertEquals(
            "just typed this",
            java.nio.file.Files.readString(result.cachePath),
        )
        assertEquals(0, env.syncEngine.downloadCount(), "must NOT re-download a local-only file")
    }

    // #122: a path that is not in state.db is a genuinely-unknown path. Every verb
    // whose precondition is a state.db row lookup must surface the SAME stable wire
    // token (`unknown_path`) so the mount maps the identical condition to the
    // identical errno (ENOENT) regardless of which verb the kernel happened to call.
    // Before the fix, open_read/open_write/dehydrate/unlink/rmdir returned the
    // free-text "Unknown path: <p>" (→ catch-all EIO) while open_write_begin already
    // returned the bare `unknown_path` token (→ ENOENT) — the same condition, two
    // errnos. If this test is removed or loosened, that divergence silently regresses.
    @Test
    fun `unknown path yields the same unknown_path token across every state-db-precondition verb`() = runTest {
        val env = HydrationTestEnv()
        val missing = "/never-existed.txt"

        val tokens: List<Pair<String, String>> = listOf(
            "open_read" to tokenOf(env.hydration.openForRead("conn1", "h1", missing)),
            "open_write" to tokenOf(
                env.hydration.openForWrite("conn1", "h2", missing, env.tempDir.resolve("c.txt")),
            ),
            "open_write_begin" to tokenOf(env.hydration.openWriteBegin("conn1", missing)),
            "dehydrate" to tokenOf(env.hydration.dehydrate(missing)),
            "unlink" to tokenOf(env.hydration.unlink(missing)),
            "rmdir" to tokenOf(env.hydration.rmdir(missing)),
        )

        for ((verb, token) in tokens) {
            assertEquals(
                "unknown_path",
                token,
                "verb $verb must surface the stable unknown_path token for an unknown path; got $token",
            )
        }
    }

    // #87 (WB-3): a folder deleted from the mount forgets its whole subtree — every row below it is
    // tombstoned (the live case left ~133k of them EXISTS, and the next fresh mount re-listed the
    // folder and planned the subtree for re-download) and the hydration-cache tree goes with it.
    // The 20k-row scale of the tombstone statement is pinned at the engine layer (SyncEngineTest);
    // this test pins the verb's end: rmdir → tombstoned rows + evicted cache tree.
    @Test
    fun `#87 rmdir of a folder tombstones the rows below and evicts the cache tree`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/sub", remoteSize = 0, isFolder = true)
        env.stateDb.insertUnhydratedEntry("/sub/a.txt", remoteSize = 5)
        env.stateDb.insertUnhydratedEntry("/sub/nested", remoteSize = 0, isFolder = true)
        env.stateDb.insertUnhydratedEntry("/sub/nested/b.txt", remoteSize = 6)
        env.syncEngine.seedCacheContent("/sub/a.txt", "cached bytes")

        val result = env.hydration.rmdir("/sub")

        assertTrue(result is RmdirResult.Ok, "rmdir succeeded: $result")
        assertEquals(
            org.krost.unidrive.sync.model.EntryStatus.DELETED,
            env.stateDb.statusOf("/sub/a.txt"),
            "the child row is tombstoned",
        )
        assertEquals(
            org.krost.unidrive.sync.model.EntryStatus.DELETED,
            env.stateDb.statusOf("/sub/nested/b.txt"),
            "the grandchild row is tombstoned too",
        )
        val cacheFile = env.syncEngine.resolveCachePath("/sub/a.txt")
        assertFalse(Files.exists(cacheFile), "the hydration-cache copy went with the folder")
    }

    // #87 (WB-3): the delete of a never-uploaded file must remove the staged encrypted copy of its
    // failed upload — it is the only other copy of the content, and the resume path would otherwise
    // keep it until the resume TTL (7 days) expires.
    @Test
    fun `#87 unlink of a never-uploaded file discards its staged upload copy`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/pending.txt", remoteSize = 5, remoteId = null)

        val result = env.hydration.unlink("/pending.txt")

        assertTrue(result is UnlinkResult.Ok, "unlink succeeded: $result")
        assertEquals(
            1,
            env.providerForTest.discardedStagedUploads.size,
            "the provider was asked to discard the staged copy; got ${env.providerForTest.discardedStagedUploads}",
        )
        assertTrue(
            env.providerForTest.discardedStagedUploads[0].replace('\\', '/').endsWith("/pending.txt"),
            "the discard names the file's cache path; got ${env.providerForTest.discardedStagedUploads[0]}",
        )
        assertNull(env.stateDb.statusOf("/pending.txt"), "the row is hard-deleted")
    }

    // #87 (WB-3): deleting a file whose own upload is queued or in flight must not drop the row and
    // the cache copy under the upload — the upload would land its cloud copy into a deleted row.
    // The delete answers busy (the token dehydrate has used since #301); the client cancels the
    // upload and retries, or retries after the completed event.
    @Test
    fun `#87 unlink answers busy while the file's own upload is in flight`() = runTest {
        val env = HydrationTestEnv()
        val gate = CompletableDeferred<Unit>()
        env.syncEngine.setUploadGate(gate)
        env.stateDb.insertUnhydratedEntry("/busy.txt", remoteSize = 5)
        env.syncEngine.seedCacheContent("/busy.txt", "the bytes")

        val opened = env.hydration.openForWrite("conn1", "h1", "/busy.txt", env.syncEngine.resolveCachePath("/busy.txt"))
        assertTrue(opened is OpenResult.Ok, "open_write queued the upload: $opened")
        assertTrue(env.hydration.hasUploadSlot("/busy.txt"), "the upload is queued or in flight")

        val result = env.hydration.unlink("/busy.txt")

        assertTrue(result is UnlinkResult.Busy, "the delete answers busy: $result")
        assertNotNull(env.stateDb.statusOf("/busy.txt"), "the row was not deleted under the upload")
        gate.complete(Unit)
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
        // Pass the test scope so background uploads are dispatched on the test dispatcher
        // and drained deterministically by yield().
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("foo.txt").also { java.nio.file.Files.writeString(it, "world") }

        val r = env.hydration.openForWrite("conn1", "h1", "/foo.txt", cacheFile)

        assertTrue(r is OpenResult.Ok)
        // Drain the background upload coroutine before asserting its side-effect.
        yield()
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
        // Pass the test scope so the background upload failure is dispatched on the test
        // dispatcher and drained deterministically by yield().
        val env = HydrationTestEnv(recoveryUploadScope = this)
        // A file created through the mount, never uploaded (remoteId = null).
        env.stateDb.insertCreatedRow("/draft.txt")
        // Force the upload to fail: uploadFromCache require()s the cache file
        // to exist; point at a path that doesn't. The close() already returned
        // 0 to the user, so the only durability surface is the state.db stamp.
        val missingCache = env.tempDir.resolve("does-not-exist.txt")

        val r = env.hydration.openForWrite("conn1", "h1", "/draft.txt", missingCache)

        // Upload is now backgrounded: openForWrite returns Ok immediately; the failure
        // happens asynchronously on recoveryUploadScope.
        assertTrue(r is OpenResult.Ok, "normal dirty-close open_write must return Ok immediately (upload is backgrounded)")
        // Drain the background upload attempt so the failure side-effects land.
        yield()
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
    fun `recovery_handle_open_write_returns_ok_without_blocking_on_upload`() = runTest {
        // Invariant: open_write with a "recovery-N" handle_id returns Ok immediately,
        // without waiting for the upload to complete. This is the crash-recovery path:
        // the cache_scanner fires open_write before the FUSE mount goes live; a
        // synchronous upload of a large file would block the co-daemon indefinitely.
        //
        // Pass `this` (the test scope) so the background launch runs on the test
        // dispatcher — yield() then drains it deterministically.
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/big.bin", localSize = 5)
        val cacheFile = env.tempDir.resolve("big.bin").also { java.nio.file.Files.writeString(it, "bytes") }

        val r = env.hydration.openForWrite("conn1", "recovery-1", "/big.bin", cacheFile)

        assertTrue(r is OpenResult.Ok, "recovery open_write must return Ok immediately")
        // The upload is in flight on the test scope; yield so the test dispatcher
        // can run the background job before we assert its side-effect.
        yield()
        assertEquals("bytes", env.syncEngine.remoteContentSeen("/big.bin"),
            "recovery upload must complete even though the call returned immediately")
    }

    @Test
    fun `recovery_handle_open_write_stamps_upload_failed_on_error`() = runTest {
        // Invariant: when a recovery upload fails, markUploadFailed is still called
        // (same durability contract as normal RELEASE open_write), even though the
        // open_write call itself returned Ok.
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/big.bin", localSize = 5)
        val missingCache = env.tempDir.resolve("does-not-exist-recovery.bin")

        val r = env.hydration.openForWrite("conn1", "recovery-1", "/big.bin", missingCache)
        assertTrue(r is OpenResult.Ok, "recovery open_write must return Ok even when upload will fail")

        // Let the background upload attempt run and fail on the test scope.
        yield()
        assertTrue(
            env.stateDb.lastErrorAt("/big.bin") != null,
            "recovery upload failure must stamp last_error_at so doctor can surface the unsynced row",
        )
    }

    // The write-back client learns the upload outcome through a Completed event
    // carrying ITS handle id — without it, hydrated/failed events are
    // indistinguishable between upload and download and uncorrelated to the
    // open_write that started the work.
    @Test
    fun `open_write emits a Completed upload event correlated to the handle`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("doc.txt").also { java.nio.file.Files.writeString(it, "bytes") }
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        // Ensure the collector has subscribed before the first emit (SharedFlow
        // keeps no replay; a late subscriber misses everything).
        yield()

        env.hydration.openForWrite("conn1", "h-save", "/doc.txt", cacheFile)
        advanceUntilIdle()

        val completed = events.filterIsInstance<HydrationEvent.Completed>()
            .single { it.handleId == "h-save" }
        assertEquals("/doc.txt", completed.path)
        assertEquals(HydrationEvent.Completed.Direction.UPLOAD, completed.direction)
        assertTrue(completed.ok, "a successful upload must emit ok=true")
        assertNull(completed.error)
        collector.cancel()
    }

    @Test
    fun `failed open_write upload emits a Completed upload event with the error token`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val missingCache = env.tempDir.resolve("missing.txt")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h-save", "/doc.txt", missingCache)
        advanceUntilIdle()

        val completed = events.filterIsInstance<HydrationEvent.Completed>()
            .single { it.handleId == "h-save" }
        assertEquals(HydrationEvent.Completed.Direction.UPLOAD, completed.direction)
        assertFalse(completed.ok)
        assertNotNull(completed.error, "a failed upload must carry the failure token")
        collector.cancel()
    }

    @Test
    fun `open_read emits a Completed download event correlated to the handle`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/read.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/read.txt", "hello")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForRead("conn1", "h-read", "/read.txt")
        advanceUntilIdle()

        val completed = events.filterIsInstance<HydrationEvent.Completed>()
            .single { it.handleId == "h-read" }
        assertEquals("/read.txt", completed.path)
        assertEquals(HydrationEvent.Completed.Direction.DOWNLOAD, completed.direction)
        assertTrue(completed.ok)
        collector.cancel()
    }

    // ── open_write base_etag guard ────────────────────────────────────────────

    // A write whose base etag no longer matches the row's token would silently
    // overwrite the newer remote version. The guard must refuse BEFORE the
    // background upload runs — after it, the clobber has already happened.
    @Test
    fun `open_write with a stale base_etag refuses with conflict and uploads nothing`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("doc.txt").also { java.nio.file.Files.writeString(it, "mine") }

        val r = env.hydration.openForWrite("conn1", "h1", "/doc.txt", cacheFile, baseEtag = "hash-/doc.txt-old")

        assertTrue(r is OpenResult.Failed)
        assertEquals("conflict", r.error.message)
        advanceUntilIdle()
        assertNull(env.syncEngine.remoteContentSeen("/doc.txt"), "the upload must never run — remote stays untouched")
        assertNull(env.stateDb.lastErrorAt("/doc.txt"), "a refused write is not a failed upload")
    }

    @Test
    fun `open_write with the current base_etag uploads normally`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, providerId = "onedrive")
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("doc.txt").also { java.nio.file.Files.writeString(it, "mine") }

        val r = env.hydration.openForWrite("conn1", "h1", "/doc.txt", cacheFile, baseEtag = "hash-/doc.txt")

        assertTrue(r is OpenResult.Ok, "a current base etag must not block the write")
        advanceUntilIdle()
        assertEquals("mine", env.syncEngine.remoteContentSeen("/doc.txt"))
        assertNull(env.syncEngine.lastUploadIfMatch(), "the content-hash base token is not a provider If-Match token (#511)")
    }

    @Test
    fun `a mount write preserves Internxt's version token`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, providerId = "internxt")
        val versionToken = "uuid-1|2026-10-04T12:00:00Z|5"
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5, remoteHash = versionToken)
        val cacheFile = env.tempDir.resolve("doc.txt").also { Files.writeString(it, "mine") }

        val r = env.hydration.openForWrite("conn1", "h1", "/doc.txt", cacheFile, baseEtag = versionToken)

        assertTrue(r is OpenResult.Ok)
        advanceUntilIdle()
        assertEquals(versionToken, env.syncEngine.lastUploadIfMatch())
    }

    @Test
    fun `open_write without base_etag stays unconditional`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("doc.txt").also { java.nio.file.Files.writeString(it, "mine") }

        val r = env.hydration.openForWrite("conn1", "h1", "/doc.txt", cacheFile, baseEtag = null)

        assertTrue(r is OpenResult.Ok, "null base etag means no guard — legacy contract")
        advanceUntilIdle()
        assertEquals("mine", env.syncEngine.remoteContentSeen("/doc.txt"))
    }

    // A never-uploaded row (etag null) has no remote version to lose, so even a
    // mismatching base etag cannot clobber anything — the write must proceed.
    @Test
    fun `open_write with base_etag on a never-uploaded row skips the guard`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertLocalOnlyHydratedEntry("/new.txt")
        val cacheFile = env.tempDir.resolve("new.txt").also { java.nio.file.Files.writeString(it, "first") }

        val r = env.hydration.openForWrite("conn1", "h1", "/new.txt", cacheFile, baseEtag = "whatever")

        assertTrue(r is OpenResult.Ok, "no remote version exists, so no conflict is possible")
        advanceUntilIdle()
        assertEquals("first", env.syncEngine.remoteContentSeen("/new.txt"))
    }

    @Test
    fun `normal_handle_dirty_close_returns_promptly_without_awaiting_upload`() = runTest {
        // Invariant (#188): a normal dirty close (FUSE release) must return Ok immediately,
        // without blocking on the cloud upload.  A slow/hung upload must not freeze the file
        // manager (Dolphin) for the duration of the upload.
        //
        // Mechanism: install a gate on the fake provider's upload method.  The upload
        // coroutine is launched on the test scope (via recoveryUploadScope = this) and
        // immediately suspends at the gate.  openForWrite must return Ok BEFORE the gate
        // is released — proving the call doesn't await the upload.
        //
        // After releasing the gate, the upload completes and its side-effect
        // (remoteContentSeen) is visible, proving the background upload still runs.
        val uploadGate = CompletableDeferred<Unit>()
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.syncEngine.setUploadGate(uploadGate)
        env.stateDb.insertHydratedEntry("/large.bin", localSize = 5)
        val cacheFile = env.tempDir.resolve("large.bin").also { java.nio.file.Files.writeString(it, "data") }

        // openForWrite must return Ok without waiting for the upload
        val r = env.hydration.openForWrite("conn1", "h-normal", "/large.bin", cacheFile)

        assertTrue(r is OpenResult.Ok, "normal dirty-close open_write must return Ok immediately (#188)")
        // Upload is in flight but gated — not yet complete
        assertNull(
            env.syncEngine.remoteContentSeen("/large.bin"),
            "upload must not have completed before the gate is released (would mean openForWrite blocked)",
        )

        // Release the gate: background upload can now finish
        uploadGate.complete(Unit)
        yield()

        assertEquals(
            "data",
            env.syncEngine.remoteContentSeen("/large.bin"),
            "background upload must complete after the gate is released",
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
        assertTrue(r.error is HydrationError.UnknownPath)
        assertEquals("unknown_path", r.error.message)
    }

    // #301: an upload queued by open_write holds the path's only unsynced copy in
    // the cache. Dehydrate must refuse (busy) until that upload has landed — the
    // open-set check alone misses this window, because the FUSE handle that wrote
    // the bytes is already closed by the time the background upload runs.
    @Test
    fun `dehydrate refuses while the path's upload is queued and works once it lands`() = runTest {
        val uploadGate = CompletableDeferred<Unit>()
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.syncEngine.setUploadGate(uploadGate)
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 4)
        val cacheFile =
            env.syncEngine.resolveCachePath("/foo.txt").also { parent ->
                java.nio.file.Files.createDirectories(parent.parent)
                java.nio.file.Files.writeString(parent, "data")
            }
        assertTrue(env.hydration.openForWrite("conn1", "h1", "/foo.txt", cacheFile) is OpenResult.Ok)

        assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/foo.txt"))
        assertTrue(
            java.nio.file.Files.exists(cacheFile),
            "a refused dehydrate must not evict the queued upload's only copy",
        )

        uploadGate.complete(Unit)
        advanceUntilIdle()
        // The FUSE client closes its handle once the write is done; only then is
        // the open-set check cleared and the upload-slot check decides.
        env.hydration.closeHandle("conn1", "h1")
        assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/foo.txt"))
    }

    // #301/#136: a pending-upload row (remoteId == null && isHydrated) has no remote
    // copy at all — its cache file IS the file. Dehydrate must refuse rather than
    // destroy the only copy.
    @Test
    fun `dehydrate refuses a pending-upload row whose cache is the only copy`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertLocalOnlyHydratedEntry("/new.txt")
        env.syncEngine.seedCacheContent("/new.txt", "only copy")

        assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/new.txt"))
        assertTrue(java.nio.file.Files.exists(env.syncEngine.resolveCachePath("/new.txt")))
        assertNotNull(env.stateDb.remoteSizeOf("/new.txt"), "a refused dehydrate must not touch the row")
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

        assertEquals(2 + 1, collected.size)
        assertTrue(collected[0] is HydrationEvent.Hydrating)
        assertTrue(collected[1] is HydrationEvent.Hydrated)
        val completed = collected[2] as HydrationEvent.Completed
        assertEquals("h1", completed.handleId)
        assertEquals(HydrationEvent.Completed.Direction.DOWNLOAD, completed.direction)
        assertTrue(completed.ok)
    }

    @Test
    fun `lastSynced returns Ok with mtime for hydrated path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)

        val r = env.hydration.lastSynced("/foo.txt")

        assertTrue(r is LastSyncedResult.Ok)
        assertEquals(Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(), r.mtimeEpochMillis)
    }

    @Test
    fun `lastSynced returns Unknown for unknown path`() = runTest {
        val env = HydrationTestEnv()

        val r = env.hydration.lastSynced("/never-existed.txt")

        assertTrue(r is LastSyncedResult.Unknown)
        assertEquals("unknown_path", r.reason)
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
        val paths = r.entries.map { it.path }.toSet()
        assertEquals(setOf("/Documents/foo.txt", "/Documents/bar.txt", "/Documents/sub"), paths)
    }

    @Test
    fun `list returns empty for prefix with no children`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/Empty")

        val r = env.hydration.list("/Empty")

        assertTrue(r is ListResult.Ok)
        assertEquals(emptyList(), r.entries)
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
            withSlash.entries.map { it.path },
            withoutSlash.entries.map { it.path },
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
        val paths = r.entries.map { it.path }.toSet()
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
        val byPath = r.entries.associateBy { it.path }
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
        val e = r.entries.single { it.path == "/Documents/foo.txt" }
        assertEquals(42L, e.size)
        assertEquals(Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(), e.mtimeEpochMillis)
        assertEquals(true, e.isHydrated)
    }

    // A stale / i32-overflowed remoteSize (e.g. a multi-GB folder size that wrapped
    // past Int.MAX to a negative) must never reach the wire: a negative size breaks
    // strict u64 list-entry parsers (the FUSE co-daemon EIO'd the whole directory on
    // it). list() clamps size to >= 0 at the source.
    @Test
    fun `list clamps a negative remote size to zero`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/gernot", remoteSize = -650676500L)

        val r = env.hydration.list("")

        assertTrue(r is ListResult.Ok)
        val e = r.entries.single { it.path == "/gernot" }
        assertEquals(0L, e.size, "a negative remote size must clamp to 0, never reach the wire")
    }

    // The remote fields a mirroring client needs: the provider's modified time
    // (mtime_ms alone is the LOCAL watermark — enumeration time for cloud-only
    // rows), the remote id for rename recognition, the change-detection token,
    // and the pending-upload / last-error flags.
    @Test
    fun `list exposes remote modified time, id, etag and pending flags`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/cloud.txt", remoteSize = 5)

        val r = env.hydration.list("")

        assertTrue(r is ListResult.Ok)
        val e = r.entries.single { it.path == "/cloud.txt" }
        assertEquals(Instant.parse("2026-03-28T12:00:00Z").toEpochMilli(), e.remoteModifiedEpochMillis)
        assertEquals("id-/cloud.txt", e.remoteId)
        assertEquals("hash-/cloud.txt", e.etag)
        assertFalse(e.pendingUpload, "a row with a remote id has landed cloud-side")
        assertFalse(e.hasError)
    }

    @Test
    fun `list marks a never-uploaded row as pending upload`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertLocalOnlyHydratedEntry("/local.txt")

        val r = env.hydration.list("")

        assertTrue(r is ListResult.Ok)
        val e = r.entries.single { it.path == "/local.txt" }
        assertTrue(e.pendingUpload, "a row without a remote id is still owed an upload")
        assertNull(e.remoteId)
        assertNull(e.remoteModifiedEpochMillis, "the provider never reported a modified time for it")
        assertFalse(e.hasError, "no upload was attempted, so no failure is stamped")
    }

    @Test
    fun `list marks a failed upload row with the error flag`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertCreatedRow("/draft.txt")
        env.stateDb.markUploadFailed("/draft.txt", Instant.now())

        val r = env.hydration.list("")

        assertTrue(r is ListResult.Ok)
        val e = r.entries.single { it.path == "/draft.txt" }
        assertTrue(e.pendingUpload)
        assertTrue(e.hasError, "last_error_at must surface as the error flag")
    }

    // An edit of a file that already has a remote id is owed to the cloud from
    // open_write until the upload lands — remoteId alone cannot say so.
    @Test
    fun `list marks an uploaded file pending while its edit is being uploaded`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val gate = CompletableDeferred<Unit>()
        env.syncEngine.setUploadGate(gate)
        val cacheFile = env.tempDir.resolve("doc.txt").also { java.nio.file.Files.writeString(it, "edited") }

        env.hydration.openForWrite("conn1", "h1", "/doc.txt", cacheFile)
        testScheduler.runCurrent() // the upload coroutine is now parked inside the provider

        val during = (env.hydration.list("") as ListResult.Ok).entries.single { it.path == "/doc.txt" }
        assertTrue(during.pendingUpload, "an edit whose upload is in flight is owed to the cloud")

        gate.complete(Unit)
        advanceUntilIdle()
        val after = (env.hydration.list("") as ListResult.Ok).entries.single { it.path == "/doc.txt" }
        assertFalse(after.pendingUpload, "once the upload landed nothing is pending")
    }

    // A client that reacts to the Completed event by re-listing must already see the
    // upload settled. An Unconfined collector runs at the emit site, i.e. before the
    // emitting coroutine could do anything else, so it pins the ordering.
    @Test
    fun `a Completed upload event is emitted after pending_upload settled`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        val cacheFile = env.tempDir.resolve("doc.txt").also { java.nio.file.Files.writeString(it, "edited") }
        var pendingWhenCompleted: Boolean? = null
        val collector = launch(Dispatchers.Unconfined) {
            env.hydration.events.collect { event ->
                if (event is HydrationEvent.Completed) {
                    val e = (env.hydration.list("") as ListResult.Ok).entries.single { it.path == "/doc.txt" }
                    pendingWhenCompleted = e.pendingUpload
                }
            }
        }

        env.hydration.openForWrite("conn1", "h1", "/doc.txt", cacheFile)
        advanceUntilIdle()

        assertEquals(false, pendingWhenCompleted, "the list a client takes on Completed must show the upload settled")
        collector.cancel()
    }

    // markUploadFailed stamps last_error_at; a later upload that lands must clear it,
    // or `error` stays raised for a file that is in the cloud.
    @Test
    fun `a successful retry clears the error flag a failed upload raised`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 5)
        env.hydration.openForWrite("conn1", "h1", "/doc.txt", env.tempDir.resolve("missing.txt"))
        advanceUntilIdle()
        assertNotNull(env.stateDb.lastErrorAt("/doc.txt"), "precondition: the failed upload stamped last_error_at")

        val cacheFile = env.tempDir.resolve("doc.txt").also { java.nio.file.Files.writeString(it, "bytes") }
        env.hydration.openForWrite("conn1", "h2", "/doc.txt", cacheFile)
        advanceUntilIdle()
        assertEquals("bytes", env.syncEngine.remoteContentSeen("/doc.txt"), "precondition: the retry landed")

        val e = (env.hydration.list("") as ListResult.Ok).entries.single { it.path == "/doc.txt" }
        assertFalse(e.hasError, "the upload landed; the error flag must not stay raised")
        assertNull(env.stateDb.lastErrorAt("/doc.txt"))
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

    @Test
    fun `open_write_begin on non-hydrated file returns Ok with empty cache file and no download`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/big.bin", remoteSize = 1024)
        val expectedCachePath = env.syncEngine.resolveCachePath("/big.bin")

        val result = env.hydration.openWriteBegin("conn1", "/big.bin")

        assertTrue(result is OpenResult.Ok)
        assertEquals(expectedCachePath, result.cachePath)
        assertTrue(java.nio.file.Files.exists(expectedCachePath), "cache file must exist")
        assertEquals(0L, java.nio.file.Files.size(expectedCachePath), "cache file must be 0 bytes")
        assertEquals(0, env.syncEngine.downloadCount(), "no download must have occurred")
    }

    @Test
    fun `open_write_begin on absent row returns Failed with unknown_path`() = runTest {
        val env = HydrationTestEnv()

        val result = env.hydration.openWriteBegin("conn1", "/nope")

        assertTrue(result is OpenResult.Failed)
        assertEquals("unknown_path", result.error.message)
    }

    @Test
    fun `open_write_begin on folder row returns Failed with path_is_folder`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/dir")

        val result = env.hydration.openWriteBegin("conn1", "/dir")

        assertTrue(result is OpenResult.Failed)
        assertEquals("path_is_folder", result.error.message)
    }

    @Test
    fun `openWriteBegin truncates a pre-existing stale cache file to zero`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/big.bin", remoteSize = 1024)
        // Seed a non-empty cache file to simulate a stale/partial cache left by a previous session.
        env.syncEngine.seedCacheContent("/big.bin", "stale content that must be discarded")

        val result = env.hydration.openWriteBegin("conn1", "/big.bin")

        assertTrue(result is OpenResult.Ok)
        val cachePath = result.cachePath
        assertEquals(0L, Files.size(cachePath), "TRUNCATE_EXISTING must discard all pre-existing bytes")
    }

    @Test
    fun `openWriteBegin with handleId registers an open-set entry making the path busy`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/live.bin", localSize = 512)
        env.syncEngine.seedCacheContent("/live.bin", "content")

        val result = env.hydration.openWriteBegin("conn1", "/live.bin", handleId = "wh-42")

        assertTrue(result is OpenResult.Ok, "openWriteBegin must succeed: $result")
        // The registered handle must block dehydrate — path is now busy.
        assertEquals(
            DehydrateResult.Busy,
            env.hydration.dehydrate("/live.bin"),
            "dehydrate must return Busy while open-set entry wh-42 is registered",
        )
        // After closeHandle the path is no longer busy.
        env.hydration.closeHandle("conn1", "wh-42")
        val afterClose = env.hydration.dehydrate("/live.bin")
        assertTrue(
            afterClose !is DehydrateResult.Busy,
            "after closeHandle the path must not be Busy; got $afterClose",
        )
    }

    @Test
    fun `openWriteBegin without handleId does NOT register an open-set entry`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/oneshot.bin", localSize = 100)
        env.syncEngine.seedCacheContent("/oneshot.bin", "content")

        // null handleId → one-shot / bare-truncate path; no open-set registration.
        val result = env.hydration.openWriteBegin("conn1", "/oneshot.bin", handleId = null)

        assertTrue(result is OpenResult.Ok, "openWriteBegin must succeed: $result")
        // dehydrate must NOT be Busy — no open-set entry was registered.
        val dr = env.hydration.dehydrate("/oneshot.bin")
        assertTrue(dr !is DehydrateResult.Busy, "dehydrate must not be Busy when handleId was null; got $dr")
    }

    // Invariant: two rapid dirty closes for the SAME path must NOT upload concurrently.
    // The per-path Mutex in launchSerializedUpload must serialize them FIFO so that:
    //   (a) max observed concurrency for the path == 1 (no simultaneous uploads), and
    //   (b) the final remote content reflects the LATEST submission, not the stale one.
    //
    // Mechanism: install a gate on the first upload so it suspends inside upload().
    // Then submit a second openForWrite for the same path with newer content.
    // Drive both coroutines via advanceUntilIdle with the gate still closed.
    // Assert the second upload has NOT yet run (it is queued behind the mutex).
    // Release the gate; advanceUntilIdle drains both coroutines.
    // Assert peak concurrency == 1 and final content == "v2" (the latest submission).
    @Test
    fun `same_path_background_uploads_are_serialized_FIFO_and_never_run_concurrently`() = runTest {
        val uploadGate = CompletableDeferred<Unit>()
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.syncEngine.setUploadGate(uploadGate)
        env.stateDb.insertHydratedEntry("/doc.txt", localSize = 2)

        // Prepare two cache files with distinct content to detect which upload "won".
        val cacheV1 = env.tempDir.resolve("doc-v1.txt").also { Files.writeString(it, "v1") }
        val cacheV2 = env.tempDir.resolve("doc-v2.txt").also { Files.writeString(it, "v2") }

        // Submit first dirty close — upload C1 is launched and will block on the gate.
        val r1 = env.hydration.openForWrite("conn1", "h1", "/doc.txt", cacheV1)
        assertTrue(r1 is OpenResult.Ok, "first openForWrite must return Ok immediately")

        // Submit second dirty close — upload C2 is launched and will queue behind the mutex.
        val r2 = env.hydration.openForWrite("conn1", "h2", "/doc.txt", cacheV2)
        assertTrue(r2 is OpenResult.Ok, "second openForWrite must return Ok immediately")

        // Drive coroutines: C1 acquires the mutex, hits the gate, suspends.
        // C2 tries to acquire the mutex, cannot (C1 holds it), suspends.
        // Neither upload has completed yet; the gate is still closed.
        advanceUntilIdle()
        assertNull(
            env.syncEngine.remoteContentSeen("/doc.txt"),
            "no upload must have completed while the gate is closed",
        )
        // Crucially: C2 must not be inside upload() yet — it's blocked on the mutex,
        // not inside the provider call. Peak concurrency for the path must be 1 (only C1).
        assertEquals(
            1,
            env.syncEngine.maxConcurrentUploadsForPath("/doc.txt"),
            "at most one upload must be inside the provider upload() at any moment (serialization invariant)",
        )

        // Release the gate: C1 finishes, C2 acquires the mutex and runs its upload.
        uploadGate.complete(Unit)
        advanceUntilIdle()

        // After both uploads complete:
        // (a) peak concurrency is still 1 — C2 only started after C1 released the lock.
        assertEquals(
            1,
            env.syncEngine.maxConcurrentUploadsForPath("/doc.txt"),
            "peak concurrency must remain 1 even after both uploads complete",
        )
        // (b) final remote content is "v2" — the last submission wins (FIFO order preserves it).
        assertEquals(
            "v2",
            env.syncEngine.remoteContentSeen("/doc.txt"),
            "final remote content must be from the last submission (v2), not the stale first one",
        )
    }

    // Invariant: after a path's upload slot has been fully drained (pending count
    // reaches zero and the slot is removed), subsequent uploads for the SAME path
    // must still be serialized correctly by the re-created slot.
    //
    // This exercises the create→drain→recreate path that the old two-map cleanup
    // could corrupt: coroutine A's decrementAndGet()→0 and its remove() were not
    // atomic, so a concurrent submitter B could see the stale mutex just before A
    // deleted it, causing a later submitter C to create a fresh mutex and run
    // concurrently with B.
    //
    // Mechanism:
    //   Round 1 — submit one gated upload; let it fully drain (slot removed).
    //   Round 2 — submit two more gated uploads for the same path; hold the gate.
    //             Assert peak concurrency is still 1 (new slot serializes them).
    //             Release the gate; assert final content is the latest submission.
    @Test
    fun `upload_slot_recreated_after_drain_still_serializes_same_path_uploads`() = runTest {
        val gate1 = CompletableDeferred<Unit>()
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertHydratedEntry("/report.txt", localSize = 2)

        val cacheV0 = env.tempDir.resolve("report-v0.txt").also { Files.writeString(it, "v0") }
        val cacheV1 = env.tempDir.resolve("report-v1.txt").also { Files.writeString(it, "v1") }
        val cacheV2 = env.tempDir.resolve("report-v2.txt").also { Files.writeString(it, "v2") }

        // ── Round 1: single upload; drain completely ─────────────────────────────
        env.syncEngine.setUploadGate(gate1)
        env.hydration.openForWrite("conn1", "h0", "/report.txt", cacheV0)
        gate1.complete(Unit)          // let it through immediately
        advanceUntilIdle()            // drain: slot pending→0, slot removed
        assertEquals("v0", env.syncEngine.remoteContentSeen("/report.txt"), "round-1 upload must complete")

        // ── Round 2: two rapid uploads; slot must be re-created and still serialize ─
        // Reset the peak counter by reinstalling a fresh gate that suspends.
        val gate2 = CompletableDeferred<Unit>()
        env.syncEngine.setUploadGate(gate2)

        val r1 = env.hydration.openForWrite("conn1", "h1", "/report.txt", cacheV1)
        val r2 = env.hydration.openForWrite("conn1", "h2", "/report.txt", cacheV2)
        assertTrue(r1 is OpenResult.Ok && r2 is OpenResult.Ok, "both round-2 openForWrite calls must return Ok immediately")

        // Drive: first upload acquires the re-created slot's mutex and suspends on gate2.
        // Second upload is queued behind the mutex — not inside provider.upload() yet.
        advanceUntilIdle()
        // Only v0 has been fully uploaded so far; neither v1 nor v2 has completed yet.
        assertEquals(
            "v0",
            env.syncEngine.remoteContentSeen("/report.txt"),
            "no round-2 upload must have completed while gate2 is closed",
        )
        assertEquals(
            1,
            env.syncEngine.maxConcurrentUploadsForPath("/report.txt"),
            "re-created slot must still serialize: at most 1 concurrent upload per path",
        )

        // Release: both round-2 uploads finish in FIFO order.
        gate2.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            1,
            env.syncEngine.maxConcurrentUploadsForPath("/report.txt"),
            "peak concurrency must remain 1 after both round-2 uploads complete",
        )
        assertEquals(
            "v2",
            env.syncEngine.remoteContentSeen("/report.txt"),
            "final content must be from the last round-2 submission (v2)",
        )
    }
}
