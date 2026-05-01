package org.krost.unidrive.internxt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.*
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
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val segments = pathSegments(remotePath)
        if (segments.isEmpty()) throw ProviderException("Cannot upload to root")

        val fileName = segments.last()
        val ext = if (fileName.contains('.')) fileName.substringAfterLast('.') else null
        val plainName = if (ext != null) fileName.substringBeforeLast('.') else fileName
        val parentPath = "/" + segments.dropLast(1).joinToString("/")

        // 1. Get file size without loading into memory
        val fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
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

        // 9. Register file in drive metadata
        val encryptedName = crypto.encryptName(plainName, "${creds.mnemonic}-$parentUuid")
        val created =
            api.createFile(
                bucket = bucket,
                folderUuid = parentUuid,
                plainName = plainName,
                encryptedName = encryptedName,
                size = fileSize,
                type = ext,
                fileId = bucketEntry.id,
            )
        return created.toCloudItem(parentPath)
    }

    override suspend fun delete(remotePath: String) {
        val metadata = getMetadata(remotePath)
        if (metadata.isFolder) {
            api.deleteFolder(metadata.id)
        } else {
            api.deleteFile(metadata.id)
        }
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
        return folder.toCloudItem(parentPath)
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val metadata = getMetadata(fromPath)
        val toSegments = pathSegments(toPath)
        val toParentPath = "/" + toSegments.dropLast(1).joinToString("/")
        val destFolderUuid = resolveFolder(toParentPath)
        return if (metadata.isFolder) {
            val result = api.moveFolder(metadata.id, destFolderUuid)
            result.toCloudItem(toParentPath)
        } else {
            val result = api.moveFile(metadata.id, destFolderUuid)
            result.toCloudItem(toParentPath)
        }
    }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
    ): DeltaPage {
        foldersScanned.set(0)
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

        return DeltaPage(items = items, cursor = latestUpdatedAt, hasMore = false)
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

    private suspend fun collectFilesFromFolders(
        folderUuid: String,
        accumulator: MutableList<InternxtFile>,
        depth: Int,
    ) {
        val content =
            try {
                api.getFolderContents(folderUuid)
            } catch (e: InternxtApiException) {
                if (e.statusCode in listOf(500, 503)) {
                    log.warn("Skipping folder {} ({}): {}", folderUuid, e.statusCode, e.message)
                    return
                }
                throw e
            }
        accumulator.addAll(content.files)
        val scanned = foldersScanned.incrementAndGet()
        log.debug("Scanning: {} files, {} folders scanned", accumulator.size, scanned)
        for (child in content.children) {
            if (child.status == "EXISTS" && !child.removed && !child.deleted) {
                collectFilesFromFolders(child.uuid, accumulator, depth + 1)
            }
        }
    }

    private suspend fun resolveFolder(path: String): String {
        val creds = authService.getValidCredentials()
        val segments = pathSegments(path)
        var currentUuid = creds.rootFolderId

        for (segment in segments) {
            val content = api.getFolderContents(currentUuid)
            val child =
                // UD-317: sanitise child name before matching path segment.
                content.children.find { sanitizeName(it.plainName ?: it.name ?: "") == segment }
                    ?: throw ProviderException("Folder not found: $segment in $path")
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
        fun rewindCursor(cursor: String): String {
            val instant = Instant.parse(cursor)
            return instant.minus(6, ChronoUnit.HOURS).toString()
        }

        fun pathSegments(path: String): List<String> = path.removePrefix("/").split("/").filter { it.isNotEmpty() }

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
                deleted = file.status == "TRASHED" || file.status == "DELETED",
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
                deleted = file.status == "TRASHED" || file.status == "DELETED",
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
