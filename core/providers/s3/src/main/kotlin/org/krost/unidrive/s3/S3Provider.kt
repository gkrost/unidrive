package org.krost.unidrive.s3

import org.krost.unidrive.*
import org.krost.unidrive.sync.ScanHeartbeat
import org.krost.unidrive.sync.computeSnapshotDelta
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant

/**
 * CloudProvider implementation for S3 / S3-compatible object storage.
 *
 * Delta strategy: full ListObjectsV2 → compare ETag + size + lastModified against
 * the previous snapshot stored as a Base64-encoded JSON cursor (same pattern as
 * the HiDrive provider).  hasMore is always false — one full listing per cycle.
 *
 * Move: S3 has no server-side move — implemented as copy + delete.
 * Folders: represented as zero-byte objects with a trailing "/" key; the provider
 * synthesises folder entries from key prefixes so the sync engine sees them.
 *
 * Authentication: [S3Provider.authenticate] validates credentials by calling
 * HeadBucket on the configured bucket.  No OAuth tokens — credentials live in
 * [S3Config] (env vars or constructor args).
 */
class S3Provider(
    val config: S3Config,
    api: S3ApiService? = null,
) : CloudProvider {
    override val id = "s3"
    override val displayName = "S3 / S3-compatible"

    private val log = LoggerFactory.getLogger(S3Provider::class.java)
    private val api = api ?: S3ApiService(config)

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.Share, // Presigned URLs.
        )

    override var isAuthenticated: Boolean = false
        private set
    override val canAuthenticate: Boolean get() =
        config.bucket.isNotBlank() && config.accessKey.isNotBlank() && config.secretKey.isNotBlank()

    override suspend fun authenticate() {
        // Validate by listing the bucket (cheap, catches bad creds immediately).
        try {
            api.listAll(prefix = "unidrive-auth-check-nonexistent-prefix/")
            isAuthenticated = true
            log.debug("Auth: bucket={}", config.bucket)
        } catch (e: AuthenticationException) {
            isAuthenticated = false
            log.warn("Auth failed: {}", e.message)
            throw e
        }
    }

    override suspend fun logout() {
        isAuthenticated = false
    }

    override fun close() = api.close()

    // ── File operations ───────────────────────────────────────────────────────

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val key = api.pathToKey(remotePath)
        return api.getObject(key, destination)
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val key = api.pathToKey(remotePath)
        val etag = api.putObject(key, localPath, onProgress)
        val size =
            java.nio.file.Files
                .size(localPath)
        val mtime =
            java.nio.file.Files
                .getLastModifiedTime(localPath)
                .toInstant()
        return CloudItem(
            id = key,
            name = remotePath.substringAfterLast("/"),
            path = remotePath,
            size = size,
            isFolder = false,
            modified = mtime,
            created = mtime,
            hash = etag,
            mimeType = "application/octet-stream",
        )
    }

    override suspend fun delete(remotePath: String) {
        api.deleteObject(api.pathToKey(remotePath))
    }

    override suspend fun createFolder(path: String): CloudItem {
        // S3 folders are virtual — create a zero-byte marker object with trailing "/"
        val key = api.pathToKey(path).trimEnd('/') + "/"
        val tmpFile = kotlin.io.path.createTempFile("s3-folder-marker")
        try {
            api.putObject(key, tmpFile)
        } finally {
            java.nio.file.Files
                .deleteIfExists(tmpFile)
        }
        val now = Instant.now()
        return CloudItem(
            id = key,
            name = path.substringAfterLast("/"),
            path = path,
            size = 0,
            isFolder = true,
            modified = now,
            created = now,
            hash = null,
            mimeType = null,
        )
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        // S3 has no atomic rename — server-side copy then delete source.
        val fromKey = api.pathToKey(fromPath)
        val toKey = api.pathToKey(toPath)
        api.copyObject(fromKey, toKey)
        try {
            api.deleteObject(fromKey)
        } catch (e: Exception) {
            // Rollback: delete the copy to avoid silent duplicate
            runCatching { api.deleteObject(toKey) }
            throw e
        }

        val now = Instant.now()
        return CloudItem(
            id = toKey,
            name = toPath.substringAfterLast("/"),
            path = toPath,
            size = 0,
            isFolder = false,
            modified = now,
            created = now,
            hash = null,
            mimeType = null,
        )
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    override suspend fun listChildren(path: String): List<CloudItem> {
        val prefix = api.pathToKey(path).trimEnd('/') + "/"
        return api.listAll(prefix).map { it.toCloudItem() }
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val key = api.pathToKey(path)
        val objects = api.listAll(prefix = key)
        return objects.firstOrNull { it.key == key || it.key == "$key/" }?.toCloudItem()
            ?: throw S3Exception("Object not found: $path", 404)
    }

    // ── Delta ─────────────────────────────────────────────────────────────────

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
    ): DeltaPage {
        // UD-352: thread per-page progress through the bucket-listing pagination.
        val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }
        val currentObjects = api.listAll(onProgress = { count -> heartbeat?.tick(count) })
        val snapshotEntries = buildSnapshotEntries(currentObjects)
        val itemsByPath =
            currentObjects.associate { obj ->
                api.keyToPath(obj.key).trimEnd('/') to obj.toCloudItem()
            }
        return computeSnapshotDelta(
            currentEntries = snapshotEntries,
            currentItemsByPath = itemsByPath,
            prevCursor = cursor,
            entrySerializer = S3SnapshotEntry.serializer(),
            hasChanged = { prev, curr -> prev.etag != curr.etag || prev.size != curr.size },
            deletedItem = { path, entry ->
                CloudItem(
                    id = api.pathToKey(path),
                    name = path.substringAfterLast("/"),
                    path = path,
                    size = 0,
                    isFolder = entry.isFolder,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                    deleted = true,
                )
            },
        )
    }

    // ── Quota ─────────────────────────────────────────────────────────────────

    override suspend fun quota(): QuotaInfo {
        // S3 has no bucket quota API — return unknown
        return QuotaInfo(total = 0, used = 0, remaining = 0)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSnapshotEntries(objects: List<S3Object>): Map<String, S3SnapshotEntry> =
        objects.associate { obj ->
            val path = api.keyToPath(obj.key).trimEnd('/')
            path to
                S3SnapshotEntry(
                    etag = obj.etag,
                    size = obj.size,
                    lastModified = obj.lastModified,
                    isFolder = obj.isFolder,
                )
        }

    private fun S3Object.toCloudItem(): CloudItem {
        val path = api.keyToPath(this.key).trimEnd('/')
        val modified = lastModified?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return CloudItem(
            id = this.key,
            name = path.substringAfterLast("/"),
            path = path.ifEmpty { "/" },
            size = this.size,
            isFolder = this.isFolder,
            modified = modified,
            created = modified,
            hash = this.etag,
            mimeType = if (this.isFolder) null else "application/octet-stream",
        )
    }

    override suspend fun share(
        path: String,
        expiryHours: Int,
        password: String?,
    ): CapabilityResult<String> {
        if (!isAuthenticated) {
            return CapabilityResult.Unsupported(Capability.Share, "S3 provider is not authenticated")
        }
        val key = api.pathToKey(path)
        return CapabilityResult.Success(api.presign(key, expiryHours * 3600))
    }

    override fun hashAlgorithm(): org.krost.unidrive.HashAlgorithm =
        org.krost.unidrive.HashAlgorithm.Md5Hex
}
