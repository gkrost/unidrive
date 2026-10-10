package org.krost.unidrive.sync

import org.krost.unidrive.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// The in-memory provider the engine tests drive. A test fixture (java-test-fixtures) so the mount
// front-end's tests in :app:hydration build their SyncEngine host on the same fake as the mirror
// tests here, without a test dependency from this module on :app:hydration.
class FakeCloudProvider : CloudProvider {
    override val id = "fake"
    override val displayName = "Fake"
    override var isAuthenticated = true

    var supportsFastBootstrap = false
    var deltaFromLatestCalls = 0
    var deltaCalls = 0

    // #396: null = hashless provider (Internxt-style, the default); set before the
    // engine is constructed to stand in for a hash-capable one (OneDrive-style).
    var hashAlgorithmOverride: HashAlgorithm? = null

    override fun hashAlgorithm(): HashAlgorithm? = hashAlgorithmOverride

    override fun capabilities(): Set<org.krost.unidrive.Capability> =
        buildSet {
            add(org.krost.unidrive.Capability.Delta)
            add(org.krost.unidrive.Capability.VerifyItem)
            if (supportsFastBootstrap) add(org.krost.unidrive.Capability.FastBootstrap)
        }

    override suspend fun deltaFromLatest(scanContext: org.krost.unidrive.ScanContext?): org.krost.unidrive.CapabilityResult<DeltaPage> {
        deltaFromLatestCalls++
        return if (supportsFastBootstrap) {
            org.krost.unidrive.CapabilityResult.Success(
                DeltaPage(items = emptyList(), cursor = deltaCursor, hasMore = false),
            )
        } else {
            org.krost.unidrive.CapabilityResult.Unsupported(
                org.krost.unidrive.Capability.FastBootstrap,
                "fake provider opts out",
            )
        }
    }

    var deltaItems = listOf<CloudItem>()
    var deltaCursor = "cursor-1"

    // Multi-page delta driver: when non-empty, successive delta() calls
    // return successive entries (last page has hasMore=false). Lets a test
    // drive the streaming gather across ≥2 pages (the single-page deltaItems
    // path always returns hasMore=false). Each entry is (items, cursor).
    var deltaPages: List<Pair<List<CloudItem>, String>> = emptyList()
    private var deltaPageIndex = 0
    val uploadedPaths = mutableListOf<String>()
    val deletedPaths = mutableListOf<String>()
    val files = mutableMapOf<String, ByteArray>()

    // #123: created-folder order, captured under the bounded-concurrency
    // create-folder run. Thread-safe because createFolder runs from
    // concurrent coroutines; the synchronized list preserves completion
    // order for the parent-before-child ordering assertion.
    val createdFolders: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    // Paths whose createFolder must throw, simulating a single provider
    // mkdir failure mid-bulk-import (the rest of the plan must still apply).
    val createFolderFailPaths: MutableSet<String> = mutableSetOf()

    var downloadFailCount = 0
    var uploadFailCount = 0
    var deleteFailCount = 0
    var deltaFailCount = 0
    var authFailOnDownload = false
    // #110: when true, delta() throws DeltaCursorExpiredException if called
    // with a non-null cursor (simulating an aged-out OneDrive delta cursor),
    // and clears itself after the first throw so the recovery full-enum
    // (cursor=null) proceeds normally.
    var deltaThrowExpiredOnResumedCursor = false

    // Paths for which downloadById / download should throw the
    // permanent-failure signal (e.g. Internxt "Bucket entry … not found").
    // Mirrors the live 1,248-retry incident shape — the engine must
    // quarantine the row instead of retrying forever.
    val permanentDownloadFailurePaths: MutableSet<String> = mutableSetOf()

    override suspend fun authenticate() {}

    override suspend fun logout() {}

    // #116: pre-existing remote tree the fast-bootstrap planner can't see.
    // Keyed by parent path; listChildren returns the configured children so a
    // test can stage top-level folders that already exist on the cloud.
    val childrenByParent = mutableMapOf<String, List<CloudItem>>()

    // #419: every listChildren() path, and per-path exceptions to throw from it, so a
    // reaper test can prove which directories were probed and simulate a 404 on one.
    val listChildrenCalls = mutableListOf<String>()
    val listChildrenThrow = mutableMapOf<String, Throwable>()

    override suspend fun listChildren(path: String): List<CloudItem> {
        listChildrenCalls.add(path)
        listChildrenThrow[path]?.let { throw it }
        return childrenByParent[path] ?: emptyList()
    }

    // #504 review: when set, getMetadata throws it (a remote item that is gone, an outage).
    var getMetadataError: Exception? = null

    // #531: folders that exist remotely but are invisible to the delta (the fast-bootstrap
    // adopted cursor never enumerated them) — getMetadata sees them, the delta does not.
    val remoteFoldersExisting = mutableSetOf<String>()

    override suspend fun getMetadata(path: String): CloudItem {
        getMetadataError?.let { throw it }
        if (path in remoteFoldersExisting) {
            return CloudItem(
                id = "exists-" + path,
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.now(),
                created = Instant.now(),
                hash = null,
                mimeType = null,
            )
        }
        return deltaItems.first { it.path == path }
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        if (authFailOnDownload) throw AuthenticationException("Token expired")
        if (remotePath in permanentDownloadFailurePaths) {
            throw org.krost.unidrive.PermanentDownloadFailureException(
                "Bucket entry for $remotePath not found",
            )
        }
        if (downloadFailCount > 0) {
            downloadFailCount--
            throw ProviderException("Network timeout on download")
        }
        downloadByPathCalls.add(remotePath)
        val content = files[remotePath] ?: ByteArray(0)
        Files.createDirectories(destination.parent)
        Files.write(destination, content)
        return content.size.toLong()
    }

    // UD-225b: track id-vs-path dispatch separately so tests can verify the
    // engine takes the fast/robust path when the action carries a remoteId.
    // Default base impl in CloudProvider delegates to download(); this
    // override lets us count both call paths distinctly.
    val downloadByIdCalls = mutableListOf<Pair<String, String>>() // (remoteId, remotePath)
    val downloadByPathCalls = mutableListOf<String>()

    // C8 follow-up: observe per-path download concurrency so a test can prove
    // same-path transfers are serialized. downloadDelayMs opens an overlap window
    // (a suspension point) so a non-serialized race would surface peak concurrency 2.
    var downloadDelayMs: Long = 0L
    val maxConcurrentDownloadsByPath = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val activeDownloadsByPath = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private suspend fun trackDownloadConcurrency(remotePath: String) {
        val active = (activeDownloadsByPath[remotePath] ?: 0) + 1
        activeDownloadsByPath[remotePath] = active
        maxConcurrentDownloadsByPath[remotePath] =
            maxOf(maxConcurrentDownloadsByPath[remotePath] ?: 0, active)
        if (downloadDelayMs > 0) kotlinx.coroutines.delay(downloadDelayMs)
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long {
        if (authFailOnDownload) throw AuthenticationException("Token expired")
        if (remotePath in permanentDownloadFailurePaths) {
            throw org.krost.unidrive.PermanentDownloadFailureException(
                "Bucket entry for $remotePath not found",
            )
        }
        if (downloadFailCount > 0) {
            downloadFailCount--
            throw ProviderException("Network timeout on download")
        }
        downloadByIdCalls.add(remoteId to remotePath)
        trackDownloadConcurrency(remotePath)
        val content = files[remotePath] ?: ByteArray(0)
        Files.createDirectories(destination.parent)
        Files.write(destination, content)
        activeDownloadsByPath[remotePath] = (activeDownloadsByPath[remotePath] ?: 1) - 1
        return content.size.toLong()
    }

    var lastUploadExistingRemoteId: String? = null
        private set

    // #417: when set, upload() reports this as the item's modified time (what a real
    // provider returns for the write: the time the server will list next).
    var uploadModified: Instant? = null

    // Runs after upload() has read the bytes it sends, to model a local edit that lands
    // while a long upload is still in flight.
    var duringUpload: ((Path) -> Unit)? = null

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        lastUploadExistingRemoteId = existingRemoteId
        if (uploadFailCount > 0) {
            uploadFailCount--
            throw ProviderException("Network timeout on upload")
        }
        uploadedPaths.add(remotePath)
        val content = Files.readAllBytes(localPath)
        files[remotePath] = content
        duringUpload?.invoke(localPath)
        return CloudItem(
            id = "id-$remotePath",
            name = remotePath.substringAfterLast("/"),
            path = remotePath,
            size = content.size.toLong(),
            isFolder = false,
            modified = uploadModified ?: Instant.now(),
            created = Instant.now(),
            hash = "uploaded",
            mimeType = null,
        )
    }

    // When non-null, the NEXT delete() call throws this exception (single-use, cleared after throw).
    var deleteThrow: Throwable? = null

    // #419: per-path variant of deleteThrow (not single-use), for failing one specific delete.
    val deleteThrowByPath = mutableMapOf<String, Throwable>()

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        deleteThrowByPath[remotePath]?.let { throw it }
        deleteThrow?.also { deleteThrow = null; throw it }
        if (deleteFailCount > 0) {
            deleteFailCount--
            throw ProviderException("Network timeout on delete")
        }
        deletedPaths.add(remotePath)
    }

    override suspend fun createFolder(path: String): CloudItem {
        if (path in createFolderFailPaths) {
            throw ProviderException("Simulated createFolder failure for $path")
        }
        if (path in remoteFoldersExisting) {
            // the live #531 shape: 409 nameAlreadyExists from OneDrive
            throw ProviderException("Create folder failed: 409 Conflict - nameAlreadyExists")
        }
        createdFolders.add(path)
        return CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = 0,
            isFolder = true,
            modified = Instant.now(),
            created = Instant.now(),
            hash = null,
            mimeType = null,
        )
    }

    val movedPaths = mutableListOf<Pair<String, String>>() // (fromPath, toPath)

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        movedPaths.add(Pair(fromPath, toPath))
        return createFolder(toPath)
    }

    // UD-360: when set, delta() returns DeltaPage(complete=false) so that
    // tests can exercise the engine's partial-gather suppression path.
    var deltaComplete = true

    // #422: stands in for a provider whose delta() lists the whole tree on every call and
    // never sends tombstones (localfs).
    var deltaFullListing = false
    override val deltaIsFullListing: Boolean get() = deltaFullListing

    // Resumable-scan instrumentation: captures the ScanContext the engine
    // passed in (resume_marker, # of resumedItems) so tests can verify
    // start-from-scratch vs resume behavior.
    var lastScanContext: org.krost.unidrive.ScanContext? = null
        private set

    // Optional per-page persistence simulation: if set, delta() pushes
    // each "page" through the staging callback before returning, so a
    // test can assert what landed in the staging slice.
    var stagedPages: List<Pair<List<CloudItem>, String>> = emptyList()

    // Mid-scan-crash simulator: run the stagedPages persistPage loop,
    // THEN throw, mimicking a daemon that persisted partial pages before
    // the next API call failed.
    var persistThenFail: Boolean = false

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage {
        deltaCalls++
        lastScanContext = scanContext
        if (deltaThrowExpiredOnResumedCursor && cursor != null) {
            deltaThrowExpiredOnResumedCursor = false // self-clearing; recovery pass uses cursor=null
            throw org.krost.unidrive.DeltaCursorExpiredException("410 Gone — delta token is too old (test)")
        }
        if (deltaFailCount > 0) {
            deltaFailCount--
            throw ProviderException("Network timeout on delta")
        }
        // Drive the engine's persistPage callback so a test can pin that
        // staged rows land in scan_staging.
        if (scanContext != null) {
            for ((page, marker) in stagedPages) {
                scanContext.persistPage(page, marker)
            }
        }
        if (persistThenFail) {
            throw ProviderException("Simulated mid-scan crash after partial persistence")
        }
        if (deltaPages.isNotEmpty()) {
            val idx = deltaPageIndex.coerceAtMost(deltaPages.size - 1)
            deltaPageIndex = (deltaPageIndex + 1).coerceAtMost(deltaPages.size)
            val (items, pageCursor) = deltaPages[idx]
            return DeltaPage(
                items = items,
                cursor = pageCursor,
                hasMore = idx < deltaPages.size - 1,
                complete = deltaComplete,
            )
        }
        return DeltaPage(
            items = deltaItems,
            cursor = deltaCursor,
            hasMore = false,
            complete = deltaComplete,
        )
    }

    override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)

    val remoteIdExists = mutableMapOf<String, Boolean>()

    override suspend fun verifyItemExists(remoteId: String): org.krost.unidrive.CapabilityResult<Boolean> =
        org.krost.unidrive.CapabilityResult
            .Success(remoteIdExists[remoteId] ?: true)

    // Captured callback from registerRemoteWakeListener — tests fire it
    // directly to simulate the provider observing remote events.
    var registeredRemoteChangeCallback: (() -> Unit)? = null

    override fun onRemoteChangeHint(callback: () -> Unit) {
        registeredRemoteChangeCallback = callback
    }
}
