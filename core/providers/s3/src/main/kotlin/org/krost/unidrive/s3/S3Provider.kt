package org.krost.unidrive.s3

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * A bucket on Amazon S3 or an S3-compatible service.
 *
 * - **Keys and paths:** the path `/a/b.txt` is the key `a/b.txt`, and the key is the item's id. Folders are key
 *   prefixes. A folder made here gets a marker object (`a/`); one implied by keys below it is reported all the same.
 * - **Changes:** S3 has no change feed. [delta] lists the whole bucket ([deltaIsFullListing]), so the engine finds
 *   deletions by absence. Through the scan context, a listing cut off half-way resumes after the last key it kept.
 * - **Uploads:** above [S3Config.multipartThreshold], multipart, with parts streamed from the file and the open upload
 *   kept in [MultipartStore], so a second attempt sends only the parts S3 does not hold yet. `x-amz-meta-mtime` carries
 *   the file's mtime as rclone writes it.
 * - **Conditional writes:** the engine's write token is the ETag it saw. Where the vendor honours `If-Match`
 *   (AWS), the write carries it and a 412 is a conflict. Elsewhere a HEAD compares first, which leaves a short race.
 * - **Copies** (moves) above 5 GiB go through UploadPartCopy on every vendor.
 */
class S3Provider(
    val config: S3Config,
    engine: HttpClientEngine? = null,
    clock: () -> Instant = Instant::now,
) : CloudProvider {
    override val id = "s3"
    override val displayName = "S3 / S3-compatible"

    private val log = org.slf4j.LoggerFactory.getLogger(S3Provider::class.java)
    private val api = S3Api(config, engine, clock)
    private val uploads = MultipartStore(config.tokenPath.resolve("s3-uploads"))

    override var isAuthenticated: Boolean = false

    override val canAuthenticate: Boolean
        get() = config.bucket.isNotBlank() && config.accessKey.isNotBlank() && config.secretKey.isNotBlank()

    override val deltaIsFullListing: Boolean get() = true

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta, Capability.Share, Capability.VerifyItem)

    override fun hashAlgorithm(): HashAlgorithm = HashAlgorithm.Md5Hex

    override fun attachRequestMetrics(sink: org.krost.unidrive.http.RequestMetricsSink?) = api.attachRequestMetrics(sink)

    /** The keys are the credential: one listing of a single key proves them against the bucket. */
    override suspend fun authenticate() {
        try {
            api.list("", maxKeys = 1)
            isAuthenticated = true
        } catch (e: AuthenticationException) {
            isAuthenticated = false
            throw e
        }
    }

    override fun close() = api.close()

    // ── reading ──

    override suspend fun listChildren(path: String): List<CloudItem> {
        val prefix = prefixOf(path)
        val out = mutableListOf<CloudItem>()
        var token: String? = null
        do {
            val page = api.list(prefix, delimiter = "/", token = token)
            page.objects.filter { it.key != prefix }.forEach { out += fileItem(it) }
            page.prefixes.forEach { out += folderItem(pathOf(it)) }
            token = page.nextToken
        } while (token != null)
        return out
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val key = keyOf(path)
        if (key.isEmpty()) return folderItem("/")
        api.head(key)?.let { return fileItem(it) }
        if (api.list("$key/", maxKeys = 1).let { it.objects.isNotEmpty() || it.prefixes.isNotEmpty() }) return folderItem(pathOf(key))
        throw S3Exception("S3 has nothing at '$path'", 404, "NoSuchKey")
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long =
        try {
            api.get(keyOf(remotePath), destination)
        } catch (e: S3Exception) {
            if (e.status == 404) throw PermanentDownloadFailureException("S3 has no object at '$remotePath' any more", e, e.requestId)
            throw e
        }

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> {
        val exists =
            if (remoteId.endsWith("/")) {
                api.list(remoteId, maxKeys = 1).let { it.objects.isNotEmpty() || it.prefixes.isNotEmpty() }
            } else {
                api.head(remoteId) != null
            }
        return CapabilityResult.Success(exists)
    }

    /**
     * The whole bucket, folders included (from markers and from the keys below them). With a scan context each page is
     * persisted with its last key as the marker, and a resumed listing starts after it, from the items staged before.
     */
    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        val files = LinkedHashMap<String, CloudItem>()
        val folders = sortedSetOf<String>()
        fun admit(item: CloudItem) {
            if (item.isFolder) folders += item.path else files[item.path] = item
            var parent = item.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty() && folders.add(parent)) parent = parent.substringBeforeLast('/', "")
        }
        val resumeAfter = scanContext?.resumeMarker
        // A staged item's path is a placeholder; its id is the key, which is the path.
        scanContext?.resumedItems?.forEach { admit(if (it.isFolder) folderItem(pathOf(it.id)) else it.copy(path = pathOf(it.id), name = nameOf(it.id))) }
        var token: String? = null
        do {
            val page = api.list("", startAfter = resumeAfter, token = token)
            val pageItems = page.objects.map { if (it.key.endsWith("/")) folderItem(pathOf(it.key)) else fileItem(it) }
            pageItems.forEach(::admit)
            val last = page.objects.lastOrNull()?.key
            if (scanContext != null && last != null && !scanContext.readOnly) scanContext.persistPage(pageItems, last)
            val seen = files.size + folders.size
            onPageProgress?.invoke(seen)
            scanContext?.onProgress?.invoke(ScanProgress(seen, listing = ScanProgress.LISTING_ACCOUNT))
            token = page.nextToken
        } while (token != null)
        val items = folders.map(::folderItem) + files.values
        return DeltaPage(items, cursor = FULL_LISTING, hasMore = false)
    }

    override suspend fun quota(): QuotaInfo = QuotaInfo(0, 0, 0)

    // ── writing ──

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val key = keyOf(remotePath)
        if (key.isEmpty()) throw ProviderException("Refusing to upload to the bucket root")
        val size = Files.size(localPath)
        val mtime = Files.getLastModifiedTime(localPath).toInstant()
        val meta = mapOf("mtime" to S3Api.epochText(mtime))
        if (ifMatchETag != null && !config.vendor.conditionalWrites) requireUnchanged(key, ifMatchETag)
        val ifMatch = ifMatchETag?.takeIf { config.vendor.conditionalWrites }
        if (size <= config.multipartThreshold) {
            api.put(key, localPath, meta, ifMatch)
        } else {
            uploadInParts(localPath, key, size, mtime.toEpochMilli(), meta, ifMatch, onProgress)
        }
        onProgress?.invoke(size, size)
        return fileItem(api.head(key) ?: throw ProviderException("S3 does not show '$key' right after its upload"))
    }

    // The racy form of a conditional write, for vendors without If-Match: the object must still carry the ETag the engine saw.
    private suspend fun requireUnchanged(
        key: String,
        etag: String,
    ) {
        val current = api.head(key)
        if (current?.etag != etag) throw RemoteConflictException("'$key' changed on S3 since it was last synced")
    }

    private suspend fun uploadInParts(
        localPath: Path,
        key: String,
        size: Long,
        mtimeMillis: Long,
        meta: Map<String, String>,
        ifMatch: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val partSize = partSizeFor(size)
        val (uploadId, held) = openUpload(localPath, key, size, mtimeMillis, partSize, meta)
        val parts = mutableListOf<Pair<Int, String>>()
        var offset = 0L
        var number = 1
        while (offset < size) {
            val length = minOf(partSize, size - offset)
            val md5 = md5Of(localPath, offset, length)
            val md5Hex = md5.joinToString("") { "%02x".format(it) }
            val etag = held[number]?.takeIf { it.size == length && it.etag == md5Hex }?.etag
                ?: api.uploadPart(key, uploadId, number, localPath, offset, length, md5)
            parts += number to etag
            offset += length
            number++
            onProgress?.invoke(offset, size)
        }
        api.completeMultipart(key, uploadId, parts, ifMatch)
        uploads.remove(key)
    }

    // The upload to send parts to: the one this profile left open for the same unchanged file, or a new one.
    private suspend fun openUpload(
        localPath: Path,
        key: String,
        size: Long,
        mtimeMillis: Long,
        partSize: Long,
        meta: Map<String, String>,
    ): Pair<String, Map<Int, S3Part>> {
        val pending = uploads.find(key)
        if (pending != null) {
            val same = pending.localPath == localPath.toString() && pending.size == size && pending.mtimeMillis == mtimeMillis && pending.partSize == partSize
            val held = if (same) api.listParts(key, pending.uploadId) else null
            if (held != null) {
                log.info("Resuming the multipart upload of '{}' with {} part(s) already on S3", key, held.size)
                return pending.uploadId to held.associateBy { it.number }
            }
            abortQuietly(key, pending.uploadId)
            uploads.remove(key)
        }
        val uploadId = api.createMultipart(key, meta)
        uploads.save(MultipartStore.Pending(key, uploadId, localPath.toString(), size, mtimeMillis, partSize))
        return uploadId to emptyMap()
    }

    // The smallest part size (a whole MiB, at least the configured minimum) that fits [size] into the vendor's part limit.
    internal fun partSizeFor(size: Long): Long {
        val needed = (size + config.vendor.maxParts - 1) / config.vendor.maxParts
        val mib = 1024L * 1024
        val partSize = maxOf(config.minPartSize, (needed + mib - 1) / mib * mib)
        if (partSize > S3Config.MAX_PART_SIZE) throw ProviderException("${size / mib} MiB is more than ${config.vendor.name} can take in one object")
        return partSize
    }

    /** A delete of a file that was never uploaded also drops the multipart upload it may have left open. */
    override suspend fun discardStagedUpload(localPath: String) {
        uploads.all().filter { it.localPath == localPath }.forEach {
            abortQuietly(it.key, it.uploadId)
            uploads.remove(it.key)
        }
    }

    @Suppress("TooGenericExceptionCaught") // best effort: S3 drops an abandoned upload by lifecycle rule or never bills more than its parts
    private suspend fun abortQuietly(
        key: String,
        uploadId: String,
    ) {
        try {
            api.abortMultipart(key, uploadId)
        } catch (e: Exception) {
            log.warn("Could not abort the multipart upload of '{}' ({}); its parts stay until a lifecycle rule removes them", key, e.javaClass.simpleName)
        }
    }

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) {
        val key = keyOf(remotePath)
        if (key.isEmpty()) throw ProviderException("Refusing to delete the whole bucket")
        val current = api.head(key)
        when {
            current != null -> {
                if (ifMatchETag != null && current.etag != ifMatchETag) {
                    throw RemoteConflictException("'$key' changed on S3 since it was last synced")
                }
                api.delete(key)
            }
            // No object: a folder (every key below it goes, as a folder delete does on the other providers), or nothing.
            else -> deleteTree("$key/")
        }
    }

    private suspend fun deleteTree(prefix: String) {
        val keys = allKeys(prefix)
        var failed = 0
        for (batch in keys.chunked(S3Api.MAX_BATCH_DELETE)) {
            failed +=
                try {
                    api.deleteMany(batch).size
                } catch (e: S3Exception) {
                    // A vendor without DeleteObjects: one call per key.
                    log.info("Batch delete refused ({}); deleting {} key(s) one by one", e.code, batch.size)
                    batch.forEach { api.delete(it) }
                    0
                }
        }
        if (failed > 0) throw ProviderException("S3 could not delete $failed of ${keys.size} object(s) under '$prefix'")
    }

    private suspend fun allKeys(prefix: String): List<String> {
        val keys = mutableListOf<String>()
        var token: String? = null
        do {
            val page = api.list(prefix, token = token)
            page.objects.forEach { keys += it.key }
            token = page.nextToken
        } while (token != null)
        return keys
    }

    override suspend fun createFolder(path: String): CloudItem {
        val key = keyOf(path)
        if (key.isEmpty()) return folderItem("/")
        api.put("$key/", null)
        return folderItem(pathOf(key))
    }

    /** Copy, then delete the source: S3 has no rename. A folder moves key by key. */
    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val from = keyOf(fromPath)
        val to = keyOf(toPath)
        val source = api.head(from)
        if (source != null) {
            copyObject(source, to)
            api.delete(from)
            return fileItem(api.head(to) ?: throw ProviderException("S3 does not show '$to' right after the copy"))
        }
        val keys = allKeys("$from/")
        if (keys.isEmpty()) throw S3Exception("S3 has nothing at '$fromPath'", 404, "NoSuchKey")
        for (key in keys) {
            val target = "$to/" + key.removePrefix("$from/")
            val obj = api.head(key) ?: continue
            copyObject(obj, target)
        }
        keys.chunked(S3Api.MAX_BATCH_DELETE).forEach { api.deleteMany(it) }
        return folderItem(pathOf(to))
    }

    // One CopyObject up to 5 GiB; above, a multipart upload whose parts are copied server-side (every vendor, not only AWS).
    @Suppress("TooGenericExceptionCaught") // the half-made copy is aborted, then the failure is rethrown unchanged
    private suspend fun copyObject(
        source: S3Object,
        to: String,
    ) {
        if (source.size <= S3Config.MAX_SINGLE_COPY) {
            api.copy(source.key, to)
            return
        }
        val partSize = maxOf(partSizeFor(source.size), COPY_PART_SIZE)
        val meta = source.mtime?.let { mapOf("mtime" to S3Api.epochText(it)) } ?: emptyMap()
        val uploadId = api.createMultipart(to, meta)
        try {
            val parts = mutableListOf<Pair<Int, String>>()
            var offset = 0L
            var number = 1
            while (offset < source.size) {
                val end = minOf(offset + partSize, source.size) - 1
                parts += number to api.uploadPartCopy(to, uploadId, number, source.key, offset..end)
                offset = end + 1
                number++
            }
            api.completeMultipart(to, uploadId, parts)
        } catch (e: Exception) {
            abortQuietly(to, uploadId)
            throw e
        }
    }

    // ── sharing ──

    /** A presigned GET. S3 caps it at seven days and cannot put a password on it. */
    override suspend fun share(
        path: String,
        expiryHours: Int,
        password: String?,
    ): CapabilityResult<String> {
        if (password != null) throw ProviderException("An S3 link cannot carry a password")
        val seconds = if (expiryHours <= 0) MAX_PRESIGN_SECONDS else minOf(expiryHours * 3600L, MAX_PRESIGN_SECONDS)
        return CapabilityResult.Success(api.presign(keyOf(path), seconds))
    }

    // ── mapping ──

    private fun fileItem(obj: S3Object): CloudItem =
        CloudItem(
            id = obj.key,
            name = nameOf(obj.key),
            path = pathOf(obj.key),
            size = obj.size,
            isFolder = false,
            // The listing's time: a listing carries no user metadata, and the engine compares the time it recorded.
            modified = obj.lastModified,
            created = null,
            hash = obj.etag,
            mimeType = null,
        )

    private fun folderItem(path: String): CloudItem {
        val key = keyOf(path)
        return CloudItem(
            id = if (key.isEmpty()) "" else "$key/",
            name = nameOf(key),
            path = if (key.isEmpty()) "/" else "/$key",
            size = 0,
            isFolder = true,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
        )
    }

    private suspend fun md5Of(
        file: Path,
        offset: Long,
        length: Long,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val md = MessageDigest.getInstance("MD5")
            java.io.RandomAccessFile(file.toFile(), "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) throw java.io.IOException("'$file' became shorter while it was uploaded")
                    md.update(buf, 0, n)
                    left -= n
                }
            }
            md.digest()
        }

    companion object {
        private const val FULL_LISTING = "full-listing"
        private const val MAX_PRESIGN_SECONDS = 7L * 24 * 3600
        private const val COPY_PART_SIZE = 512L * 1024 * 1024

        fun keyOf(path: String): String = path.trim().trim('/')

        private fun prefixOf(path: String): String = keyOf(path).let { if (it.isEmpty()) "" else "$it/" }

        fun pathOf(key: String): String = "/" + key.trimEnd('/')

        private fun nameOf(key: String): String = key.trimEnd('/').substringAfterLast('/')
    }
}
