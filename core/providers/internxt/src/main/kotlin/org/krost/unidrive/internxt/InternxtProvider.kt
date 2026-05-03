package org.krost.unidrive.internxt

import kotlinx.coroutines.Dispatchers
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

class InternxtProvider(
    private val config: InternxtConfig = InternxtConfig(),
) : CloudProvider {
    override val id = "internxt"
    override val displayName = "Internxt Drive"

    private val log = org.slf4j.LoggerFactory.getLogger(InternxtProvider::class.java)
    private val crypto = InternxtCrypto()
    private val authService = AuthService(config)
    private val api = InternxtApiService(config) { authService.getValidCredentials() }

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

    override val isAuthenticated: Boolean get() = authService.isAuthenticated
    override val canAuthenticate: Boolean get() = Files.exists(config.tokenPath.resolve("credentials.json"))

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.QuotaExact,
        )

    override suspend fun authenticate() {
        authService.initialize()
        if (!authService.isAuthenticated || authService.isJwtExpired()) {
            authService.authenticateInteractive()
        }
    }

    override suspend fun logout() = authService.logout()

    override fun close() {
        authService.close()
        api.close()
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
                val baseName = file.plainName ?: file.name ?: ""
                val fullName = if (!file.type.isNullOrEmpty() && !baseName.endsWith(".${file.type}")) "$baseName.${file.type}" else baseName
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
        val indexBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
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
    ): DeltaPage {
        foldersScanned.set(0)
        foldersSkipped.set(0)
        val adjustedCursor = cursor?.let { rewindCursor(it) }
        val allFiles = mutableListOf<InternxtFile>()
        val allFolders = mutableListOf<InternxtFolder>()
        val limit = 50

        // UD-352: this delta() loops internally over both /files and /folders
        // pagination, accumulating *all* pages before returning a single
        // DeltaPage. The engine's outer post-delta tick fires only at the
        // end — for a 63 k-item profile that's many minutes of silence. Hook
        // the heartbeat here so onScanProgress fires every 5k items / 10s
        // wall-clock during the gather.
        val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }

        // Paginate files (may be unavailable — 503)
        try {
            var offset = 0
            while (true) {
                val batch = api.listFiles(adjustedCursor, limit, offset)
                allFiles.addAll(batch)
                log.debug("Scanning files: {}", allFiles.size)
                heartbeat?.tick(allFiles.size + allFolders.size)
                if (batch.size < limit) break
                offset += limit
            }
        } catch (e: InternxtApiException) {
            if (e.statusCode in listOf(500, 503)) {
                log.warn("/files endpoint unavailable ({}), falling back to folder-based listing", e.statusCode)
                collectFilesFromFolders(authService.getValidCredentials().rootFolderId, allFiles, 0)
            } else {
                throw e
            }
        }

        // Paginate folders
        var offset = 0
        while (true) {
            val batch = api.listFolders(adjustedCursor, limit, offset)
            allFolders.addAll(batch)
            log.debug("Scanning folders: {}", allFolders.size)
            heartbeat?.tick(allFiles.size + allFolders.size)
            if (batch.size < limit) break
            offset += limit
        }

        val creds = authService.getValidCredentials()
        val folderMap = allFolders.associateBy { it.uuid }
        // Folders last so that path collisions in gatherRemoteChanges resolve in favour of folders
        val items =
            allFiles.map { it.toDeltaCloudItem(folderMap, creds.rootFolderId) } +
                allFolders.map { it.toDeltaCloudItem(folderMap, creds.rootFolderId) }

        val latestUpdatedAt =
            (allFiles.mapNotNull { it.updatedAt } + allFolders.mapNotNull { it.updatedAt })
                .maxOrNull() ?: cursor ?: Instant.now().toString()

        // UD-360: signal partial gather to the engine instead of throwing.
        // Replaces the UD-361 fail-loud ProviderException — the engine now
        // honours `complete=false` by skipping detectMissingAfterFullSync,
        // so we no longer need to abort the run to prevent spurious
        // del-local. Sync continues with whatever was successfully gathered;
        // the missing subtree is picked up on the next run.
        val skipped = foldersSkipped.get()
        if (skipped > 0) {
            log.warn(
                "Internxt delta gather skipped {} folder(s) due to 500/503; " +
                    "returning DeltaPage(complete=false). detectMissingAfterFullSync will be suppressed.",
                skipped,
            )
        }

        return DeltaPage(
            items = items,
            cursor = latestUpdatedAt,
            hasMore = false,
            complete = skipped == 0,
        )
    }

    private fun buildFolderPath(
        uuid: String,
        folderMap: Map<String, InternxtFolder>,
        rootUuid: String,
    ): String {
        if (uuid == rootUuid) return ""
        val folder = folderMap[uuid] ?: return ""
        val parentPath = folder.parentUuid?.let { buildFolderPath(it, folderMap, rootUuid) } ?: ""
        // UD-317: sanitise name returned by createFolder API.
        val name = sanitizeName(folder.plainName ?: folder.name ?: "")
        return "$parentPath/$name"
    }

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
    ): CloudItem {
        val parentPath = folderUuid?.let { buildFolderPath(it, folderMap, rootUuid) } ?: ""
        return fileToDeltaCloudItem(this, parentPath)
    }

    private fun InternxtFolder.toDeltaCloudItem(
        folderMap: Map<String, InternxtFolder>,
        rootUuid: String,
    ): CloudItem {
        val parentPath = parentUuid?.let { buildFolderPath(it, folderMap, rootUuid) } ?: ""
        return folderToDeltaCloudItem(this, parentPath)
    }

    companion object {
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
                    if (e.statusCode in listOf(500, 503)) {
                        log.warn("Skipping folder {} ({}): {}", folderUuid, e.statusCode, e.message)
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
                modified = file.modificationTime?.let { parseTime(it) },
                created = file.creationTime?.let { parseTime(it) },
                hash = null,
                mimeType = cleanType,
                // UD-359: surface `removed`/`deleted` boolean flags alongside
                // the `status` enum. Mirrors the folder helper at line ~562.
                deleted = file.status == "TRASHED" || file.status == "DELETED" || file.removed || file.deleted,
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
                modified = folder.modificationTime?.let { parseTime(it) },
                created = folder.creationTime?.let { parseTime(it) },
                hash = null,
                mimeType = null,
                deleted = folder.status == "TRASHED" || folder.status == "DELETED" || folder.removed || folder.deleted,
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
                modified = file.modificationTime?.let { parseTime(it) },
                created = file.creationTime?.let { parseTime(it) },
                hash = null,
                mimeType = cleanType,
                // UD-359: surface `removed`/`deleted` boolean flags alongside
                // the `status` enum. Mirrors the folder helper at line ~562.
                deleted = file.status == "TRASHED" || file.status == "DELETED" || file.removed || file.deleted,
            )
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
                modified = folder.modificationTime?.let { parseTime(it) },
                created = folder.creationTime?.let { parseTime(it) },
                hash = null,
                mimeType = null,
                deleted = folder.status == "TRASHED" || folder.status == "DELETED" || folder.removed || folder.deleted,
            )
        }

        private fun parseTime(iso: String): Instant? =
            try {
                Instant.parse(iso)
            } catch (_: Exception) {
                null
            }
    }
}
