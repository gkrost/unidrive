package org.krost.unidrive.internxt

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.krost.unidrive.*
import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.krost.unidrive.sync.ScanHeartbeat
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

class InternxtProvider(
    private val config: InternxtConfig = InternxtConfig(),
) : CloudProvider {
    override val id = "internxt"
    override val displayName = "Internxt Drive"

    private val log = org.slf4j.LoggerFactory.getLogger(InternxtProvider::class.java)
    private val crypto = InternxtCrypto()
    private val authService = AuthService(config)
    private val api = InternxtApiService(config, credentialsProvider = { forceRefresh -> authService.getValidCredentials(forceRefresh) })
    private val secureRandom = java.security.SecureRandom()

    // Internxt notifications (socket.io). Constructed lazily on the first
    // authenticate() success so we have a JWT to hand to the WS handshake;
    // disposed in close() / logout(). The current remote-change callback is
    // stashed in a volatile reference so onRemoteChangeHint() called BEFORE
    // authenticate() still wires through once the socket comes up.
    @Volatile
    private var notificationsClient: NotificationsClient? = null

    @Volatile
    private var remoteChangeCallback: () -> Unit = {}

    // UD-357: process-local cache of (parentUuid, sanitizedName) -> uuid for
    // folder lookups. Populated on createFolder success (the API returns the
    // new folder's UUID — without this the next resolveFolder call races
    // Internxt's read-after-write inconsistency window: POST /drive/folders
    // returns 200, but GET /drive/folders/{parentUuid} doesn't yet show the
    // child for ~1-3 seconds). Consulted at the top of each resolveFolder
    // segment-walk so multi-level mkdir cascades survive that window.
    //
    // Also populated on read (after a successful list) so subsequent
    // resolveFolder calls hitting the same parent don't re-list — a free
    // latency win on top of the bug fix.
    //
    // Eviction: none. Process-lifetime cache. A folder created via this
    // cache then deleted out-of-band (web UI, another sync session) leaves
    // a stale entry whose UUID will 404 on the next operation; acceptable
    // trade for the in-process scope.
    private val folderCache = FolderUuidCache()

    // UD-364: process-lifetime cache of /files/limits.maxUploadFileSize.
    // Lazy single-flight: first maxFileSizeBytes() call fetches + caches;
    // subsequent calls return cached. Server returns null for "no cap",
    // which we still cache (the negative answer is also stable per plan).
    // Failures (network, 401) do NOT poison the cache — the next call retries.
    // User plans change rarely; no eviction.
    private val limitsLock = Any()

    @Volatile
    private var fileLimitsCached: Long? = null

    @Volatile
    private var fileLimitsResolved: Boolean = false

    // UD-007: authService is the source of truth — see OneDriveProvider for
    // the rationale. Setter is a no-op; the override of `logout()` below
    // flips state via `authService.logout()`.
    override var isAuthenticated: Boolean
        get() = authService.isAuthenticated
        set(_) { /* delegated to authService; setter is a no-op */ }
    override val canAuthenticate: Boolean get() = Files.exists(config.tokenPath.resolve("credentials.json"))

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.QuotaExact,
        )

    /**
     * UD-364: per-plan upload cap from `GET /files/limits` (`maxUploadFileSize`).
     *
     * Caches the first successful result for the lifetime of this provider
     * instance. On fetch failure (network, 401, malformed body) returns null
     * and leaves the cache unset so the next call retries — never caches
     * the failure. The non-suspend SPI requires `runBlocking`; the call is
     * O(one HTTP round-trip) and only runs once per provider lifetime.
     */
    override fun maxFileSizeBytes(): Long? {
        if (fileLimitsResolved) return fileLimitsCached
        return synchronized(limitsLock) {
            if (fileLimitsResolved) return@synchronized fileLimitsCached
            try {
                val limits = runBlocking { api.getFileLimits() }
                fileLimitsCached = limits.maxUploadFileSize
                fileLimitsResolved = true
                fileLimitsCached
            } catch (e: Exception) {
                log.warn("GET /files/limits failed; maxFileSizeBytes()=null, will retry next call", e)
                null
            }
        }
    }

    override suspend fun authenticate() {
        authService.initialize()
        if (!authService.isAuthenticated || authService.isJwtExpired()) {
            authService.authenticateInteractive()
        }
        // Bring up the notifications WS now that we have a JWT. Failures are
        // logged inside NotificationsClient; the periodic poll is the safety
        // net so a WS that won't connect mustn't fail the authenticate path.
        ensureNotificationsClient()
    }

    override suspend fun logout() {
        notificationsClient?.disconnect()
        notificationsClient = null
        authService.logout()
    }

    override fun close() {
        notificationsClient?.disconnect()
        notificationsClient = null
        authService.close()
        api.close()
    }

    override fun onRemoteChangeHint(callback: () -> Unit) {
        remoteChangeCallback = callback
    }

    private suspend fun ensureNotificationsClient() {
        if (notificationsClient != null) return
        val creds =
            try {
                authService.getValidCredentials()
            } catch (e: Exception) {
                log.debug("ensureNotificationsClient: no credentials yet ({}), deferring", e.message)
                return
            }
        val client =
            NotificationsClient(
                notificationsUrl = config.notificationsUrl,
                ownClientName = config.clientName,
                tokenSupplier = { forceRefresh -> authService.getValidCredentials(forceRefresh).jwt },
                onRemoteChange = { remoteChangeCallback() },
            )
        notificationsClient = client
        client.connect(creds.jwt)
    }

    override suspend fun listChildren(path: String): List<CloudItem> {
        val folderUuid = resolveFolder(path)
        val content = api.getFolderContents(folderUuid)
        return content.children.map { it.toCloudItem(path) } +
            content.files.map { it.toCloudItem(path) }
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val segments = pathSegments(path)
        if (segments.isEmpty()) {
            val creds = authService.getValidCredentials()
            return CloudItem(creds.rootFolderId, "", "/", 0, true, null, null, null, null)
        }
        val parentPath = "/" + segments.dropLast(1).joinToString("/")
        val name = segments.last()
        val parentUuid = resolveFolder(parentPath)
        val content = api.getFolderContents(parentUuid)

        // UD-317: sanitise child name so entries with stray `\n` still match a clean `name` arg.
        val folder = content.children.find { sanitizeName(it.plainName ?: it.name ?: "") == name }
        if (folder != null) return folder.toCloudItem(parentPath)

        val file =
            content.files.find { file ->
                val baseName = sanitizeName(file.plainName ?: file.name ?: "")
                val cleanType = file.type?.let { sanitizeName(it) }
                val fullName = if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) "$baseName.$cleanType" else baseName
                fullName == name || baseName == name
            }
        if (file != null) return file.toCloudItem(parentPath)

        throw ProviderException("Item not found: $path")
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = downloadWithFileUuid(remoteId, remotePath, destination)

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val metadata = getMetadata(remotePath)
        if (metadata.isFolder) throw ProviderException("Cannot download a folder: $remotePath")
        return downloadWithFileUuid(metadata.id, remotePath, destination)
    }

    private suspend fun downloadWithFileUuid(
        fileUuid: String,
        remotePath: String,
        destination: Path,
    ): Long {
        val fileMeta = api.getFileMeta(fileUuid)
        val bucket = fileMeta.bucket ?: throw ProviderException("File has no bucket: $remotePath")
        val fileId = fileMeta.fileId ?: throw ProviderException("File has no fileId: $remotePath")

        // 1. Get encryption index from bridge
        val bridgeInfo = api.getBridgeFileInfo(bucket, fileId)
        val indexBytes = InternxtCrypto.hexToBytes(bridgeInfo.index)
        val iv = indexBytes.copyOfRange(0, 16)

        // 2. Derive file key: mnemonic → seed → bucketKey → fileKey
        val creds = authService.getValidCredentials()
        val seed = crypto.mnemonicToSeed(creds.mnemonic)
        val bucketKey = crypto.deriveBucketKey(seed, bucket)
        val fileKey = crypto.deriveFileKey(bucketKey, indexBytes)

        // 3. Stream: encrypted bytes → CipherInputStream(AES-256-CTR) → disk
        val downloadUrl =
            bridgeInfo.shards.firstOrNull { it.url.isNotBlank() }?.url
                ?: throw ProviderException("No download URL in bridge info for $remotePath")

        val cipher = crypto.createContentDecryptCipher(fileKey, iv)
        return api.downloadFileStreaming(downloadUrl, cipher, destination)
    }

    // UD-304: multipart constants available in InternxtConfig.MULTIPART_*; not yet consumed (pending UD-307 multipart endpoint impl).
    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val segments = pathSegments(remotePath)
        if (segments.isEmpty()) throw ProviderException("Cannot upload to root")

        val fileName = segments.last()
        val ext = if (fileName.contains('.')) fileName.substringAfterLast('.') else null
        val plainName = if (ext != null) fileName.substringBeforeLast('.') else fileName
        val parentPath = "/" + segments.dropLast(1).joinToString("/")

        // 1. Get file size + mtime without loading into memory
        val fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
        val localMtime = withContext(Dispatchers.IO) { Files.getLastModifiedTime(localPath).toInstant() }
        onProgress?.invoke(0L, fileSize)

        // 2. Generate random 32-byte index; derive key + iv
        val indexBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val iv = indexBytes.copyOfRange(0, 16)
        val creds = authService.getValidCredentials()

        // 3. Resolve parent folder and get bucket from credentials
        val parentUuid = resolveFolder(parentPath)
        val bucket =
            creds.bucket.ifEmpty {
                throw ProviderException("No bucket in credentials — re-authenticate with 'unidrive auth --provider internxt'")
            }

        val seed = crypto.mnemonicToSeed(creds.mnemonic)
        val bucketKey = crypto.deriveBucketKey(seed, bucket)
        val fileKey = crypto.deriveFileKey(bucketKey, indexBytes)

        // 4. Stream encrypted file to temp file (avoids OOM on large files)
        val encCipher = crypto.createContentEncryptCipher(fileKey, iv)
        val tempFile = withContext(Dispatchers.IO) { Files.createTempFile("unidrive-enc-", ".tmp") }
        val encryptedSize: Long
        val hashHex: String
        try {
            // Encrypt to temp file + compute SHA-256 in a single pass
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            withContext(Dispatchers.IO) {
                javax.crypto.CipherInputStream(Files.newInputStream(localPath), encCipher).use { cipherIn ->
                    Files.newOutputStream(tempFile).use { out ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (cipherIn.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                        }
                    }
                }
            }
            encryptedSize = Files.size(tempFile)
            hashHex = digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(tempFile) }
            throw e
        }
        val indexHex = indexBytes.joinToString("") { "%02x".format(it) }

        // 5. Start upload → get presigned PUT url + uuid
        val startResp = api.startUpload(bucket, encryptedSize)
        val descriptor =
            startResp.uploads.firstOrNull()
                ?: throw ProviderException("No upload URL returned for $remotePath")
        val putUrl = descriptor.url ?: throw ProviderException("Upload descriptor has no URL for $remotePath")

        // 6. PUT encrypted shard from temp file
        try {
            api.putEncryptedShardFromFile(putUrl, tempFile, encryptedSize)
        } finally {
            withContext(Dispatchers.IO) { Files.deleteIfExists(tempFile) }
        }
        onProgress?.invoke(fileSize, fileSize)

        // 8. Finish upload → get bridge fileId
        val bucketEntry = api.finishUpload(bucket, indexHex, hashHex, descriptor.uuid)

        // 9. Register file in drive metadata.
        // UD-366: MODIFIED uploads route through PUT /files/{uuid} (replace-in-place);
        // NEW uploads POST /files (create). Defensive fallback below catches the 409 that
        // appears when the reconciler thought a path was new but the remote already has it.
        if (existingRemoteId != null) {
            val replaced =
                api.replaceFile(
                    uuid = existingRemoteId,
                    size = fileSize,
                    fileId = bucketEntry.id,
                    modificationTime = localMtime,
                )
            return replaced.toCloudItem(parentPath)
        }

        val encryptedName = crypto.encryptName(plainName, "${creds.mnemonic}-$parentUuid")
        val created =
            try {
                api.createFile(
                    bucket = bucket,
                    folderUuid = parentUuid,
                    plainName = plainName,
                    encryptedName = encryptedName,
                    size = fileSize,
                    type = ext,
                    fileId = bucketEntry.id,
                )
            } catch (e: InternxtApiException) {
                // UD-366 defensive fallback: reconciler/DB drift can leave us POSTing a
                // path that already exists on remote. Re-resolve the UUID and retry as PUT
                // rather than stranding the local edit.
                if (e.statusCode != 409) throw e
                log.warn(
                    "UD-366: 409 on POST /files for {} despite existingRemoteId=null — " +
                        "reconciler/DB drift; re-resolving UUID and retrying as PUT /files/{{uuid}}",
                    remotePath,
                )
                val existing = getMetadata(remotePath)
                if (existing.isFolder) throw e
                api.replaceFile(
                    uuid = existing.id,
                    size = fileSize,
                    fileId = bucketEntry.id,
                    modificationTime = localMtime,
                )
            }
        return created.toCloudItem(parentPath)
    }

    override suspend fun delete(remotePath: String) {
        // UD-367: route routine sync-driven deletes through Internxt's recycle bin
        // (POST /storage/trash/add) so any spurious del-local from a partial delta()
        // gather is recoverable rather than permanently destructive. The permanent
        // deleteFile/deleteFolder primitives in InternxtApiService remain available
        // for an explicit `unidrive trash purge --remote` operator action (not yet wired).
        val metadata = getMetadata(remotePath)
        val type = if (metadata.isFolder) "folder" else "file"
        api.trashItems(listOf(metadata.id to type))
    }

    override suspend fun createFolder(path: String): CloudItem {
        val segments = pathSegments(path)
        val parentPath = "/" + segments.dropLast(1).joinToString("/")
        val name = segments.last()
        val parentUuid = resolveFolder(parentPath)
        val creds = authService.getValidCredentials()
        val encryptedName = crypto.encryptName(name, "${creds.mnemonic}-$parentUuid")
        val folder =
            try {
                api.createFolder(parentUuid, name, encryptedName)
            } catch (e: InternxtApiException) {
                if (e.statusCode != 409) throw e
                // Folder already exists on the server — recover its UUID instead of failing.
                val existing =
                    api
                        .getFolderContents(parentUuid)
                        .children
                        // UD-317: sanitise child name before matching.
                        .find { sanitizeName(it.plainName ?: it.name ?: "") == name }
                        ?: throw e // 409 but folder not found: re-throw original error
                existing
            }
        // UD-357: cache the new folder's UUID so the next resolveFolder call
        // doesn't race Internxt's read-after-write inconsistency on listing.
        // Use sanitizeName(name) so the lookup key matches what resolveFolder
        // compares against.
        folderCache.put(parentUuid, sanitizeName(name), folder.uuid)
        return folder.toCloudItem(parentPath)
    }

    /**
     * UD-368: bulk-aware override. Groups paths by their parent folder, resolves each parent
     * UUID once, and calls [InternxtApiService.createFoldersBatch] in chunks of 5 (the
     * Internxt server cap). Falls back to the per-path [createFolder] (which carries the
     * UD-317 409-recovery dance and UD-357 cache population) for cross-parent groups,
     * preserving today's correctness guarantees.
     *
     * **Caveat — partial-success on bulk 409.** The server returns
     * `CreateBulkFoldersConflictResponseDto` for any conflict in the batch, listing only the
     * conflicting plain names. Recovering UUIDs for the non-conflicting ones from a bulk
     * response is awkward; on 409 we fall back to serial [createFolder] for the entire
     * group, which trades round-trips for correctness.
     */
    override suspend fun createFolders(paths: List<String>): List<CloudItem> {
        if (paths.isEmpty()) return emptyList()
        if (paths.size == 1) return listOf(createFolder(paths.single()))

        // Preserve input order in the result.
        data class Indexed(val originalIndex: Int, val path: String, val name: String, val parentPath: String)
        val indexed =
            paths.mapIndexed { i, p ->
                val segs = pathSegments(p)
                require(segs.isNotEmpty()) { "Cannot create root: $p" }
                Indexed(i, p, segs.last(), "/" + segs.dropLast(1).joinToString("/"))
            }
        val byParent = indexed.groupBy { it.parentPath }
        val results = arrayOfNulls<CloudItem>(paths.size)

        for ((parentPath, group) in byParent) {
            val parentUuid = resolveFolder(parentPath)
            val creds = authService.getValidCredentials()
            // Chunk against the server's 5-item cap.
            for (chunk in group.chunked(5)) {
                val items =
                    chunk.map { idx ->
                        val encryptedName = crypto.encryptName(idx.name, "${creds.mnemonic}-$parentUuid")
                        idx.name to encryptedName
                    }
                try {
                    val created = api.createFoldersBatch(parentUuid, items)
                    require(created.size == chunk.size) {
                        "createFoldersBatch returned ${created.size} folders for ${chunk.size} requested"
                    }
                    chunk.zip(created).forEach { (idx, folder) ->
                        // UD-357: populate cache so later resolveFolder calls don't race.
                        folderCache.put(parentUuid, sanitizeName(idx.name), folder.uuid)
                        results[idx.originalIndex] = folder.toCloudItem(parentPath)
                    }
                } catch (e: InternxtApiException) {
                    if (e.statusCode != 409) throw e
                    // UD-368 caveat: bulk 409 doesn't tell us per-item success/failure.
                    // Fall back to serial createFolder which handles 409 per-item via UD-317.
                    log.warn(
                        "UD-368: bulk POST /folders returned 409 for parent {}; falling back to " +
                            "serial createFolder for {} item(s)",
                        parentPath,
                        chunk.size,
                    )
                    chunk.forEach { idx ->
                        results[idx.originalIndex] = createFolder(idx.path)
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return (results as Array<CloudItem>).toList()
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val fromSegments = pathSegments(fromPath)
        val toSegments = pathSegments(toPath)
        if (fromSegments.isEmpty() || toSegments.isEmpty()) {
            throw ProviderException("Cannot move from/to root: $fromPath -> $toPath")
        }
        val fromParent = fromSegments.dropLast(1)
        val toParent = toSegments.dropLast(1)
        val fromName = fromSegments.last()
        val toName = toSegments.last()
        val toParentPath = "/" + toParent.joinToString("/")

        val metadata = getMetadata(fromPath)
        val sameParent = fromParent == toParent
        val sameName = fromName == toName

        if (sameParent && sameName) return metadata // no-op

        // UD-369: route the rename leg through PUT /…/meta when the parent doesn't change,
        // avoiding the engine's worst-case download → re-encrypt → re-upload → delete-old
        // cycle for what is metadata-only on the wire. Cross-parent moves keep PATCH; mixed
        // (different parent AND different name) does both in sequence.
        if (sameParent) {
            return if (metadata.isFolder) {
                api.renameFolder(metadata.id, toName).toCloudItem(toParentPath)
            } else {
                val newType = newFileType(toName)
                api.renameFile(metadata.id, plainName = stripExtension(toName), type = newType)
                    .toCloudItem(toParentPath)
            }
        }

        val destFolderUuid = resolveFolder(toParentPath)
        if (metadata.isFolder) {
            val moved = api.moveFolder(metadata.id, destFolderUuid)
            return if (sameName) {
                moved.toCloudItem(toParentPath)
            } else {
                api.renameFolder(moved.uuid, toName).toCloudItem(toParentPath)
            }
        } else {
            val moved = api.moveFile(metadata.id, destFolderUuid)
            return if (sameName) {
                moved.toCloudItem(toParentPath)
            } else {
                val newType = newFileType(toName)
                api.renameFile(moved.uuid, plainName = stripExtension(toName), type = newType)
                    .toCloudItem(toParentPath)
            }
        }
    }


    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage {
        foldersScanned.set(0)
        foldersSkipped.set(0)
        val adjustedCursor = cursor?.let { rewindCursor(it) }
        val limit = InternxtConfig.LISTING_PAGE_SIZE

        // Resume support: a non-null scanContext means the engine is tracking
        // staged pages in state.db. On a resumed scan the previously-fetched
        // rows arrive via scanContext.resumedItems and the per-stream offsets
        // are encoded in resumeMarker. A fresh scan starts both offsets at 0
        // with an empty resumedItems list.
        val resume = parseResumeOffsets(scanContext?.resumeMarker)
        val resumedFolders = scanContext?.resumedItems.orEmpty().filter { it.isFolder }
        val resumedFiles = scanContext?.resumedItems.orEmpty().filter { !it.isFolder }
        val allFiles = mutableListOf<InternxtFile>()
        val allFolders = mutableListOf<InternxtFolder>()
        // Resumed items rejoin the gather as Internxt model objects so the
        // post-loop folder-graph reconstruction sees the full union. Their
        // updatedAt is unknown (the engine stripped it during staging), so
        // they're absent from the cursor-advance computation below — the
        // freshly-fetched pages dominate that anyway.
        resumedFolders.mapTo(allFolders) { it.toResumedFolder() }
        resumedFiles.mapTo(allFiles) { it.toResumedFile() }

        // UD-352: this delta() loops internally over both /files and /folders
        // pagination, accumulating *all* pages before returning a single
        // DeltaPage. The engine's outer post-delta tick fires only at the
        // end — for a 63 k-item profile that's many minutes of silence. Hook
        // the heartbeat here so onScanProgress fires every 5k items / 10s
        // wall-clock during the gather.
        val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }

        // Parallel listing pagination. Files and folders streams run concurrently;
        // inside each stream up to 2 page fetches stay in flight at a time (matches
        // the Drive HttpRetryBudget concurrency cap). Under 28-52s per-page gateway
        // latency, this roughly halves scan wall-clock vs the prior sequential loop.
        // Running counts via AtomicInteger so the heartbeat reports monotonically
        // non-decreasing totals as pages arrive on either stream. The resumed-row
        // contribution is baked in up front so the heartbeat total is monotonic
        // across the seam.
        val filesCount = AtomicInteger(allFiles.size)
        val foldersCount = AtomicInteger(allFolders.size)

        val combinedTotal: () -> Int = { filesCount.get() + foldersCount.get() }

        // Per-page persistence is gated behind the scanContext + the
        // PageBoundaryPersister helper, which coalesces files+folders page
        // arrivals into a single sync_state marker write per page (the engine
        // doesn't care which stream the page came from; only that the
        // checkpoint advances atomically). On a snapshot/no-scanContext
        // delta() this collapses to a no-op.
        val persister =
            scanContext?.let { ctx ->
                PageBoundaryPersister(
                    persistPage = ctx.persistPage,
                    creds = authService.getValidCredentials(),
                    nextFilesOffset = resume.filesOffset,
                    nextFoldersOffset = resume.foldersOffset,
                )
            }

        coroutineScope {
            val foldersDeferred =
                async {
                    speculativeFetchPages(
                        limit = limit,
                        runningCount = foldersCount,
                        heartbeat = heartbeat,
                        combinedTotal = combinedTotal,
                        label = "folders",
                        startOffset = resume.foldersOffset,
                        fetchPage = { offset -> api.listFolders(adjustedCursor, limit, offset) },
                        onPage = { page, nextOffset ->
                            persister?.notifyFoldersPage(page, nextOffset)
                        },
                    )
                }
            // Files stream owns its own 500/503 fallback to the folder walk — the
            // /folders endpoint has no equivalent fallback, so a folders failure
            // propagates and cancels the files stream via structured concurrency.
            val filesResult: List<InternxtFile> =
                try {
                    speculativeFetchPages(
                        limit = limit,
                        runningCount = filesCount,
                        heartbeat = heartbeat,
                        combinedTotal = combinedTotal,
                        label = "files",
                        startOffset = resume.filesOffset,
                        fetchPage = { offset -> api.listFiles(adjustedCursor, limit, offset) },
                        onPage = { page, nextOffset ->
                            persister?.notifyFilesPage(page, nextOffset)
                        },
                    )
                } catch (e: InternxtApiException) {
                    if (e.statusCode !in SERVER_UNAVAILABLE_STATUSES) throw e
                    log.warn("/files endpoint unavailable ({}), falling back to folder-based listing", e.statusCode)
                    val fallback = mutableListOf<InternxtFile>()
                    collectFilesFromFolders(authService.getValidCredentials().rootFolderId, fallback, 0)
                    filesCount.set(allFiles.size + fallback.size)
                    heartbeat?.tick(combinedTotal())
                    fallback
                }
            allFiles.addAll(filesResult)
            allFolders.addAll(foldersDeferred.await())
        }

        val creds = authService.getValidCredentials()
        val folderMap = allFolders.associateBy { it.uuid }
        // Folders last so that path collisions in gatherRemoteChanges resolve in favour of folders.
        // toDeltaCloudItem returns null when an ancestor uuid is absent from folderMap
        // (typical when /folders delta returned a changed leaf without its unchanged
        // ancestors). Dropped items are counted so `complete` reflects the gap and
        // the engine skips detectMissingAfterFullSync + leaves the cursor un-advanced.
        val rawFiles = allFiles.map { it.toDeltaCloudItem(folderMap, creds.rootFolderId) }
        val rawFolders = allFolders.map { it.toDeltaCloudItem(folderMap, creds.rootFolderId) }
        val filesDropped = rawFiles.count { it == null }
        val foldersDropped = rawFolders.count { it == null }
        val items = rawFiles.filterNotNull() + rawFolders.filterNotNull()

        // Cursor = max(updatedAt) seen across successfully-fetched pages.
        // Skipped (500/503 fallback) folders contribute nothing — their items
        // never made it into allFiles/allFolders.
        val seenMax =
            (allFiles.mapNotNull { it.updatedAt } + allFolders.mapNotNull { it.updatedAt })
                .maxOrNull()
        val latestUpdatedAt = advanceCursor(seenMax, cursor)

        val skipped = foldersSkipped.get()
        if (skipped > 0) {
            log.warn(
                "Internxt delta gather skipped {} folder(s) due to 500/503; " +
                    "returning DeltaPage(complete=false). detectMissingAfterFullSync will be suppressed.",
                skipped,
            )
        }
        val ancestorDrops = filesDropped + foldersDropped
        if (ancestorDrops > 0) {
            log.warn(
                "Internxt delta gather dropped {} item(s) ({} file(s), {} folder(s)) " +
                    "whose ancestor uuid was missing from this page's folderMap; " +
                    "returning DeltaPage(complete=false). Dropped items recover when " +
                    "their ancestors next change or via --reset.",
                ancestorDrops,
                filesDropped,
                foldersDropped,
            )
        }

        return DeltaPage(
            items = items,
            cursor = latestUpdatedAt,
            hasMore = false,
            complete = skipped == 0 && ancestorDrops == 0,
        )
    }

    /**
     * Coalesces files + folders page arrivals into a single [persistPage] call
     * per page boundary so the checkpoint marker advances atomically and the
     * staged-row INSERTs ride a single SQLite transaction. Stream offsets are
     * tracked independently so a resume from the persisted marker lands each
     * stream at the right position.
     *
     * The class is stateful and the two notify methods are called from
     * concurrent coroutines, so writes go through a [kotlinx.coroutines.sync.Mutex]
     * to serialise the SQL transactions — concurrent persistPage calls on the
     * same DB connection would deadlock SQLite's per-connection serialised
     * write model.
     */
    private class PageBoundaryPersister(
        private val persistPage: suspend (items: List<org.krost.unidrive.CloudItem>, marker: String) -> Unit,
        private val creds: org.krost.unidrive.internxt.model.InternxtCredentials,
        nextFilesOffset: Int,
        nextFoldersOffset: Int,
    ) {
        private val mutex = kotlinx.coroutines.sync.Mutex()

        @Volatile
        private var filesOffset: Int = nextFilesOffset

        @Volatile
        private var foldersOffset: Int = nextFoldersOffset

        suspend fun notifyFilesPage(
            page: List<InternxtFile>,
            newOffset: Int,
        ) {
            mutex.lock()
            try {
                filesOffset = newOffset
                val items = page.map { it.toStagedCloudItem(creds.rootFolderId) }
                persistPage(items, buildMarker(filesOffset, foldersOffset))
            } finally {
                mutex.unlock()
            }
        }

        suspend fun notifyFoldersPage(
            page: List<InternxtFolder>,
            newOffset: Int,
        ) {
            mutex.lock()
            try {
                foldersOffset = newOffset
                val items = page.map { it.toStagedCloudItem(creds.rootFolderId) }
                persistPage(items, buildMarker(filesOffset, foldersOffset))
            } finally {
                mutex.unlock()
            }
        }
    }

    /**
     * Two-wide speculative page fetcher used by [delta]. Keeps up to 2 in-flight
     * [fetchPage] calls; when a page comes back with `size < limit` (the last
     * page), any speculative siblings are cancelled rather than waited on — under
     * the live 28-52s per-page latency, one wasted fetch is much cheaper than
     * one extra round-trip's worth of wall-clock. Pages are appended in offset
     * order so the resulting list matches the sequential walk.
     *
     * [startOffset] supports the resumable-scan handshake: a resumed scan
     * begins paginating from the persisted boundary rather than offset 0.
     *
     * [onPage], when supplied, fires once per successfully-fetched page with
     * the page contents and the offset the NEXT page would start at. Resumable-
     * scan staging hooks here so each page is durably persisted before the
     * pipeline tops up.
     *
     * [runningCount] is incremented as pages arrive; [combinedTotal] reads it
     * alongside the sibling stream's counter so the heartbeat fires with a
     * monotonic file+folder total without cross-coroutine list synchronization
     * on the merge path.
     */
    private suspend fun <T> speculativeFetchPages(
        limit: Int,
        runningCount: AtomicInteger,
        heartbeat: ScanHeartbeat?,
        combinedTotal: () -> Int,
        label: String,
        startOffset: Int = 0,
        fetchPage: suspend (offset: Int) -> List<T>,
        onPage: (suspend (page: List<T>, nextOffset: Int) -> Unit)? = null,
    ): List<T> =
        coroutineScope {
            val accumulated = mutableListOf<T>()
            val pipeline: ArrayDeque<Pair<Int, Deferred<List<T>>>> = ArrayDeque()
            var nextOffset = startOffset
            // Seed the pipeline with up to 2 in-flight page fetches.
            repeat(2) {
                val offset = nextOffset
                pipeline.addLast(offset to async { fetchPage(offset) })
                nextOffset += limit
            }
            while (pipeline.isNotEmpty()) {
                val (pageOffset, deferred) = pipeline.removeFirst()
                val batch = deferred.await()
                accumulated.addAll(batch)
                runningCount.addAndGet(batch.size)
                log.debug("Scanning {}: {}", label, runningCount.get())
                heartbeat?.tick(combinedTotal())
                // Per-page persistence runs after we have a known-good batch but
                // before we top up the pipeline, so a transient failure on the
                // staging write rolls the page back without losing the offset
                // we'd otherwise have queued ahead.
                onPage?.invoke(batch, pageOffset + batch.size)
                if (batch.size < limit) {
                    // Last page: cancel any speculative siblings still in flight.
                    pipeline.forEach { it.second.cancel() }
                    pipeline.clear()
                    break
                }
                // Top up: keep two in flight.
                val offset = nextOffset
                pipeline.addLast(offset to async { fetchPage(offset) })
                nextOffset += limit
            }
            accumulated
        }

    private fun buildFolderPath(
        uuid: String,
        folderMap: Map<String, InternxtFolder>,
        rootUuid: String,
    ): String? = Companion.buildFolderPath(uuid, folderMap, rootUuid)

    override suspend fun quota(): QuotaInfo = api.getQuota()

    private val foldersScanned =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    // UD-361: count of subtrees we skipped due to 500/503 during the recursive
    // /files fallback. Inspected at the end of delta() to refuse partial gathers.
    private val foldersSkipped =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    private suspend fun collectFilesFromFolders(
        folderUuid: String,
        accumulator: MutableList<InternxtFile>,
        depth: Int,
    ) {
        collectFilesFromFoldersImpl(
            getContents = api::getFolderContents,
            folderUuid = folderUuid,
            accumulator = accumulator,
            depth = depth,
            scanned = foldersScanned,
            skipped = foldersSkipped,
            log = log,
        )
    }

    private suspend fun resolveFolder(path: String): String {
        val creds = authService.getValidCredentials()
        val segments = pathSegments(path)
        var currentUuid = creds.rootFolderId

        for (segment in segments) {
            // UD-357: cache hit short-circuits Internxt's eventual-consistency
            // window. On the first resolveFolder right after createFolder of
            // a new subfolder, the API listing still doesn't show it; the
            // cache (populated by createFolder) does.
            val cached = folderCache.get(currentUuid, segment)
            if (cached != null) {
                currentUuid = cached
                continue
            }
            val content = api.getFolderContents(currentUuid)
            val child =
                // UD-317: sanitise child name before matching path segment.
                content.children.find { sanitizeName(it.plainName ?: it.name ?: "") == segment }
                    ?: throw ProviderException("Folder not found: $segment in $path")
            // UD-357: cache the discovered UUID too — subsequent resolveFolder
            // calls on the same parent skip the round-trip.
            folderCache.put(currentUuid, segment, child.uuid)
            currentUuid = child.uuid
        }
        return currentUuid
    }

    // UD-317: instance wrappers; actual CloudItem construction (with name
    // sanitisation) lives as pure companion functions below so tests can
    // exercise the conversion directly without a real InternxtProvider.
    private fun InternxtFile.toCloudItem(parentPath: String): CloudItem = fileToCloudItem(this, parentPath)

    private fun InternxtFolder.toCloudItem(parentPath: String): CloudItem = folderToCloudItem(this, parentPath)

    private fun InternxtFile.toDeltaCloudItem(
        folderMap: Map<String, InternxtFolder>,
        rootUuid: String,
    ): CloudItem? {
        val parentPath =
            when (val fUuid = folderUuid) {
                null -> ""
                else -> buildFolderPath(fUuid, folderMap, rootUuid) ?: return null
            }
        return fileToDeltaCloudItem(this, parentPath)
    }

    private fun InternxtFolder.toDeltaCloudItem(
        folderMap: Map<String, InternxtFolder>,
        rootUuid: String,
    ): CloudItem? {
        val parentPath =
            when (val pUuid = parentUuid) {
                null -> ""
                else -> buildFolderPath(pUuid, folderMap, rootUuid) ?: return null
            }
        return folderToDeltaCloudItem(this, parentPath)
    }

    companion object {
        // Server-unavailable status codes that trigger the slower fallback
        // walk over the folder tree (line 518) or skip-this-folder (line 699).
        // Narrower than TRANSIENT_STATUSES: 429 (rate-limited) and 502/504
        // (gateway timing issues) should not trigger fallback — they should
        // honour Retry-After and retry the same call.
        private val SERVER_UNAVAILABLE_STATUSES = setOf(500, 503)

        /**
         * UD-361: testable recursion driver. Walks the folder tree rooted at
         * [folderUuid], accumulating files into [accumulator]. On 500/503
         * from [getContents], increments [skipped] and returns (the subtree
         * is silently dropped from the accumulator — caller is responsible
         * for inspecting [skipped] and rejecting partial gathers). Other
         * exceptions propagate. Increments [scanned] for every successfully
         * walked folder.
         */
        internal suspend fun collectFilesFromFoldersImpl(
            getContents: suspend (String) -> FolderContentResponse,
            folderUuid: String,
            accumulator: MutableList<InternxtFile>,
            depth: Int,
            scanned: java.util.concurrent.atomic.AtomicInteger,
            skipped: java.util.concurrent.atomic.AtomicInteger,
            log: org.slf4j.Logger,
        ) {
            val content =
                try {
                    getContents(folderUuid)
                } catch (e: InternxtApiException) {
                    if (e.statusCode in SERVER_UNAVAILABLE_STATUSES) {
                        log.warn("Skipping folder {} ({})", folderUuid, e.statusCode, e)
                        skipped.incrementAndGet()
                        return
                    }
                    throw e
                }
            accumulator.addAll(content.files)
            val total = scanned.incrementAndGet()
            log.debug("Scanning: {} files, {} folders scanned", accumulator.size, total)
            for (child in content.children) {
                if (child.status == "EXISTS" && !child.removed && !child.deleted) {
                    collectFilesFromFoldersImpl(
                        getContents,
                        child.uuid,
                        accumulator,
                        depth + 1,
                        scanned,
                        skipped,
                        log,
                    )
                }
            }
        }

        fun rewindCursor(cursor: String): String {
            val instant = Instant.parse(cursor)
            return instant.minus(6, ChronoUnit.HOURS).toString()
        }

        fun pathSegments(path: String): List<String> = path.removePrefix("/").split("/").filter { it.isNotEmpty() }

        /** UD-369: split a leaf filename into (plainName, type). Returned type is the bare extension or null. */
        fun stripExtension(filename: String): String =
            if (filename.contains('.')) filename.substringBeforeLast('.') else filename

        fun newFileType(filename: String): String? =
            if (filename.contains('.')) filename.substringAfterLast('.') else null

        /**
         * UD-317: strip leading/trailing whitespace (incl. `\n`, `\r`, `\t`)
         * from names returned by internxt's listing APIs. Observed
         * 2026-04-19: a desktop-shortcut filename arrived as
         * `"\ninternxt-cli.desktop"`, which caused `SyncEngine` URI
         * construction to fail with `Illegal char <\n> at index 0`. We
         * `.trim()` defensively rather than reject, so the file still
         * syncs. Interior whitespace is preserved (legal filename char).
         */
        fun sanitizeName(raw: String): String = raw.trim()

        private fun InternxtFile.isTombstoned(): Boolean =
            status == "TRASHED" || status == "DELETED" || removed || deleted

        private fun InternxtFolder.isTombstoned(): Boolean =
            status == "TRASHED" || status == "DELETED" || removed || deleted

        /** UD-317: pure converter for `listChildren` / `getMetadata` file entries. */
        internal fun fileToCloudItem(
            file: InternxtFile,
            parentPath: String,
        ): CloudItem {
            val baseName = sanitizeName(file.plainName ?: file.name ?: "")
            val cleanType = file.type?.let { sanitizeName(it) }
            val name =
                if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) {
                    "$baseName.$cleanType"
                } else {
                    baseName
                }
            val fullPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
            return CloudItem(
                id = file.uuid,
                name = name,
                path = fullPath,
                size = file.size.toLongOrNull() ?: 0,
                isFolder = false,
                modified = file.modificationInstant,
                created = file.creationInstant,
                hash = null,
                // UD-352c: Internxt's API returns `file.type` as a file
                // EXTENSION (e.g. "heic", "jpg"), not a real MIME type
                // (e.g. "image/heic"). Setting mimeType = cleanType here
                // produced Android intent-resolver mismatches (no app
                // declares an intent-filter for MIME literally "heic").
                // Set mimeType = null so consumers (Android LocalFileSource,
                // MediaStoreBridge, etc.) derive the real MIME from the
                // filename extension via their platform-native lookup.
                // The extension itself is preserved in the `name` field
                // a few lines above (the `name = "$baseName.$cleanType"`
                // construction).
                mimeType = null,
                // UD-359: surface `removed`/`deleted` boolean flags alongside
                // the `status` enum. Mirrors the folder helper at line ~562.
                deleted = file.isTombstoned(),
                // Parent's folder UUID — populated so state.db can index alive
                // children of a folder via (parent_uuid, status). Internxt's
                // `folderUuid` is null only for items directly under the root
                // bucket; we collapse that to CloudItem.parentId=null too so
                // root rows store parent_uuid=NULL.
                parentId = file.folderUuid,
            )
        }

        /** UD-317: pure converter for `listChildren` folder entries. */
        internal fun folderToCloudItem(
            folder: InternxtFolder,
            parentPath: String,
        ): CloudItem {
            val name = sanitizeName(folder.plainName ?: folder.name ?: "")
            val fullPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
            return CloudItem(
                id = folder.uuid,
                name = name,
                path = fullPath,
                size = 0,
                isFolder = true,
                modified = folder.modificationInstant,
                created = folder.creationInstant,
                hash = null,
                mimeType = null,
                deleted = folder.isTombstoned(),
                parentId = folder.parentUuid,
            )
        }

        /** UD-317: pure converter for delta file entries (parentPath already resolved by caller). */
        internal fun fileToDeltaCloudItem(
            file: InternxtFile,
            parentPath: String,
        ): CloudItem {
            val baseName = sanitizeName(file.plainName ?: file.name ?: "")
            val cleanType = file.type?.let { sanitizeName(it) }
            val name =
                if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) {
                    "$baseName.$cleanType"
                } else {
                    baseName
                }
            return CloudItem(
                id = file.uuid,
                name = name,
                path = "$parentPath/$name",
                size = file.size.toLongOrNull() ?: 0,
                isFolder = false,
                modified = file.modificationInstant,
                created = file.creationInstant,
                hash = null,
                // UD-352c: Internxt's API returns `file.type` as a file
                // EXTENSION (e.g. "heic", "jpg"), not a real MIME type
                // (e.g. "image/heic"). Setting mimeType = cleanType here
                // produced Android intent-resolver mismatches (no app
                // declares an intent-filter for MIME literally "heic").
                // Set mimeType = null so consumers (Android LocalFileSource,
                // MediaStoreBridge, etc.) derive the real MIME from the
                // filename extension via their platform-native lookup.
                // The extension itself is preserved in the `name` field
                // a few lines above (the `name = "$baseName.$cleanType"`
                // construction).
                mimeType = null,
                // UD-359: surface `removed`/`deleted` boolean flags alongside
                // the `status` enum. Mirrors the folder helper at line ~562.
                deleted = file.isTombstoned(),
                parentId = file.folderUuid,
            )
        }

        /**
         * Resolves the absolute path of a folder uuid by walking ancestors
         * through [folderMap]. Returns the empty string for [rootUuid]
         * (parentPath sentinel) and `null` when any non-root ancestor is
         * absent from [folderMap].
         *
         * A null return signals that the caller cannot safely construct a
         * full path for an item under this uuid — typically because the
         * /folders delta page returned a changed leaf without its
         * unchanged ancestors. Callers must drop the item and signal
         * `complete = false` upstream rather than silently rooting it at
         * `/` (the prior silent-empty fallback produced ~84k duplicate
         * `remote_id` rows in user state.db). See
         * docs/audits/internxt-phantom-investigation.md.
         */
        internal fun buildFolderPath(
            uuid: String,
            folderMap: Map<String, InternxtFolder>,
            rootUuid: String,
        ): String? {
            if (uuid == rootUuid) return ""
            val folder = folderMap[uuid] ?: return null
            val parentPath: String =
                when (val parentUuid = folder.parentUuid) {
                    null -> ""
                    else -> buildFolderPath(parentUuid, folderMap, rootUuid) ?: return null
                }
            val name = sanitizeName(folder.plainName ?: folder.name ?: "")
            return "$parentPath/$name"
        }

        /** UD-317: pure converter for delta folder entries (parentPath already resolved by caller). */
        internal fun folderToDeltaCloudItem(
            folder: InternxtFolder,
            parentPath: String,
        ): CloudItem {
            val name = sanitizeName(folder.plainName ?: folder.name ?: "")
            return CloudItem(
                id = folder.uuid,
                name = name,
                path = "$parentPath/$name",
                size = 0,
                isFolder = true,
                modified = folder.modificationInstant,
                created = folder.creationInstant,
                hash = null,
                mimeType = null,
                deleted = folder.isTombstoned(),
                parentId = folder.parentUuid,
            )
        }

        // ---- Resumable-scan helpers ----

        /**
         * Compute the cursor to return from a delta gather. Internxt cursors
         * are ISO-8601 timestamps that the gateway uses as a "items modified
         * since X" filter. Returns `max(seenMax, requestCursor)` so:
         *  - A gather that fetched items advances to the freshest updatedAt
         *    seen across all completed pages.
         *  - A gather that fetched no items (every page empty, or every
         *    folder hit the 503 fallback) leaves the cursor where it was
         *    rather than regressing to `Instant.now()` and skipping past
         *    items modified before now.
         *  - Both null (first scan, no items) falls back to `Instant.now()`
         *    so the next launch doesn't ask the gateway for "since epoch".
         * Lexicographic comparison is sound because the timestamps are
         * always ISO-8601 with the same timezone suffix (`Z` from the API).
         */
        internal fun advanceCursor(
            seenMax: String?,
            requestCursor: String?,
        ): String =
            when {
                seenMax != null && requestCursor != null ->
                    if (seenMax > requestCursor) seenMax else requestCursor
                seenMax != null -> seenMax
                requestCursor != null -> requestCursor
                else -> Instant.now().toString()
            }

        /**
         * Marker format used by the page-boundary persister. Two non-negative
         * integers separated by `|`: files offset first, folders offset
         * second. Stored verbatim in `sync_state.scan_in_progress_marker` and
         * parsed back via [parseResumeOffsets] on resume.
         */
        internal fun buildMarker(
            filesOffset: Int,
            foldersOffset: Int,
        ): String = "$filesOffset|$foldersOffset"

        internal fun parseResumeOffsets(marker: String?): ResumeOffsets {
            if (marker.isNullOrEmpty()) return ResumeOffsets(0, 0)
            val parts = marker.split('|')
            if (parts.size != 2) return ResumeOffsets(0, 0)
            // Both tokens must parse to a non-negative integer; a half-corrupt
            // marker (one token good, one bad) is treated as a fresh start so
            // a corrupted persistence write doesn't silently skip a range.
            val filesOffset = parts[0].toIntOrNull() ?: return ResumeOffsets(0, 0)
            val foldersOffset = parts[1].toIntOrNull() ?: return ResumeOffsets(0, 0)
            if (filesOffset < 0 || foldersOffset < 0) return ResumeOffsets(0, 0)
            return ResumeOffsets(filesOffset, foldersOffset)
        }

        internal data class ResumeOffsets(val filesOffset: Int, val foldersOffset: Int)

        /**
         * Convert a freshly-fetched [InternxtFile] into the [CloudItem] shape
         * that the staging slice persists. The `path` field is left empty
         * because the folder graph isn't complete mid-scan — the resume path
         * re-resolves it from `parentId` against the rebuilt graph.
         */
        internal fun InternxtFile.toStagedCloudItem(rootUuid: String): CloudItem {
            val baseName = sanitizeName(plainName ?: name ?: "")
            val cleanType = type?.let { sanitizeName(it) }
            val resolvedName =
                if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) {
                    "$baseName.$cleanType"
                } else {
                    baseName
                }
            // Root-level files have folderUuid == null; collapse to null
            // parentId so the staged row mirrors what a fully-resolved
            // CloudItem from this file would carry. Items whose parent is
            // the bucket root surface as parentId=null too.
            val pid = folderUuid?.takeIf { it != rootUuid }
            return CloudItem(
                id = uuid,
                name = resolvedName,
                path = "/$resolvedName",
                size = size.toLongOrNull() ?: 0,
                isFolder = false,
                modified = modificationInstant,
                created = creationInstant,
                hash = null,
                mimeType = null,
                parentId = pid,
            )
        }

        /** Folder equivalent of [toStagedCloudItem]. */
        internal fun InternxtFolder.toStagedCloudItem(rootUuid: String): CloudItem {
            val resolvedName = sanitizeName(plainName ?: name ?: "")
            val pid = parentUuid?.takeIf { it != rootUuid }
            return CloudItem(
                id = uuid,
                name = resolvedName,
                path = "/$resolvedName",
                size = 0,
                isFolder = true,
                modified = modificationInstant,
                created = creationInstant,
                hash = null,
                mimeType = null,
                parentId = pid,
            )
        }

        /**
         * Re-inflate a resumed-from-staging [CloudItem] back into the
         * Internxt model object that the gather loop consumes. The staged
         * row preserves cloud identity (uuid, plainName, parentUuid,
         * size, modified, hash) — everything the folder-graph reconstruction
         * needs. `updatedAt` is unknown post-staging (the engine strips it),
         * which is fine: the cursor-advance computation in delta() looks at
         * the freshly-fetched pages, which set the high-water mark.
         */
        internal fun CloudItem.toResumedFile(): InternxtFile =
            InternxtFile(
                uuid = id,
                plainName = name.substringBeforeLast('.', name),
                name = name.substringBeforeLast('.', name),
                type = name.substringAfterLast('.', ""),
                folderUuid = parentId,
                size = size.toString(),
                modificationTime = modified?.toString(),
                updatedAt = null,
            )

        internal fun CloudItem.toResumedFolder(): InternxtFolder =
            InternxtFolder(
                uuid = id,
                plainName = name,
                name = name,
                parentUuid = parentId,
                modificationTime = modified?.toString(),
                updatedAt = null,
            )
    }
}
