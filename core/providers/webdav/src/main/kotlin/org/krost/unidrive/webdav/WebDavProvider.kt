package org.krost.unidrive.webdav

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.*
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant

/**
 * CloudProvider implementation for WebDAV servers (Nextcloud, ownCloud, Synology DSM, QNAP, etc.).
 *
 * Delta strategy: full recursive PROPFIND listing → compare ETag + size + lastModified
 * against the previous snapshot stored as a Base64-encoded JSON cursor (same pattern as
 * HiDrive, S3, and SFTP).  [DeltaPage.hasMore] is always false — one full listing per cycle.
 *
 * Move: WebDAV MOVE header — server-side, preserves content without re-uploading.
 *
 * Authentication: HTTP Basic (username + password) via Ktor Auth plugin.
 * No OAuth — credentials live in [WebDavConfig].
 */
class WebDavProvider internal constructor(
    val config: WebDavConfig,
    api: WebDavApiService,
) : CloudProvider {
    // UD-291: convenience constructor used in production. The internal primary
    // constructor lets tests inject an override of [WebDavApiService] (for
    // verifying the catch behaviour in `quota()`) without an HTTP-stub harness.
    constructor(config: WebDavConfig) : this(config, WebDavApiService(config))

    override val id = "webdav"
    override val displayName = "WebDAV"

    private val log = LoggerFactory.getLogger(WebDavProvider::class.java)
    private val api: WebDavApiService = api

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    override var isAuthenticated: Boolean = false
        private set
    override val canAuthenticate: Boolean get() =
        config.baseUrl.isNotBlank() && config.username.isNotBlank() && config.password.isNotBlank()

    override suspend fun authenticate() {
        // Validate by doing a PROPFIND on the root
        api.propfind("")
        isAuthenticated = true
        log.debug("Auth: {}", config.baseUrl)
    }

    override suspend fun logout() {
        isAuthenticated = false
    }

    override fun close() = api.close()

    /**
     * UD-327: surface the configured per-file size cap. Null when no
     * `max_file_size_bytes` was set in the profile TOML; CloudRelocator's
     * preflightOversized check is a no-op in that case (matches the pre-fix
     * behaviour for generic WebDAV servers like Box / Nextcloud).
     */
    override fun maxFileSizeBytes(): Long? = config.maxFileSizeBytes

    // ── File operations ───────────────────────────────────────────────────────

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = api.download(remotePath, destination)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val entry = api.upload(localPath, remotePath, onProgress)
        return entry.toCloudItem()
    }

    override suspend fun delete(remotePath: String) = api.delete(remotePath)

    override suspend fun createFolder(path: String): CloudItem {
        api.mkcol(path)
        val now = Instant.now()
        return CloudItem(
            id = path,
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
        api.move(fromPath, toPath)
        val now = Instant.now()
        return CloudItem(
            id = toPath,
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
        val raw = api.propfind(path)
        // Normalise to "/foo/bar" form for the self-entry filter.
        // path.trimEnd('/') on "/" yields "", so guard with ifEmpty.
        val selfPath = path.trimEnd('/').ifEmpty { "/" }
        val filtered = raw.filter { it.path != selfPath && it.path != "$selfPath/" }
        return filtered.map { it.toCloudItem() }
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val entries = api.propfind(path)
        return entries.firstOrNull { it.path == path || it.path == path.trimEnd('/') }?.toCloudItem()
            ?: throw WebDavException("WebDAV resource not found: $path", 404)
    }

    // ── Delta ─────────────────────────────────────────────────────────────────

    override suspend fun delta(cursor: String?): DeltaPage {
        val currentEntries = api.listAll()
        log.debug("Delta: {} items", currentEntries.size)
        val currentSnapshot = buildSnapshot(currentEntries)

        if (cursor == null) {
            return DeltaPage(
                items = currentEntries.map { it.toCloudItem() },
                cursor = currentSnapshot.encode(),
                hasMore = false,
            )
        }

        val previousSnapshot = WebDavSnapshot.decode(cursor)
        val changes = mutableListOf<CloudItem>()

        // New and modified entries
        for ((path, entry) in currentSnapshot.entries) {
            val prev = previousSnapshot.entries[path]
            val changed =
                prev == null ||
                    prev.size != entry.size ||
                    (entry.etag != null && prev.etag != entry.etag) ||
                    (entry.etag == null && prev.lastModified != entry.lastModified)
            if (changed) {
                val found = currentEntries.firstOrNull { it.path == path }
                if (found != null) changes.add(found.toCloudItem())
            }
        }

        // Deleted entries
        for ((path, entry) in previousSnapshot.entries) {
            if (path !in currentSnapshot.entries) {
                changes.add(
                    CloudItem(
                        id = path,
                        name = path.substringAfterLast("/"),
                        path = path,
                        size = 0,
                        isFolder = entry.isFolder,
                        modified = null,
                        created = null,
                        hash = null,
                        mimeType = null,
                        deleted = true,
                    ),
                )
            }
        }

        return DeltaPage(
            items = changes,
            cursor = currentSnapshot.encode(),
            hasMore = false,
        )
    }

    // ── Quota ─────────────────────────────────────────────────────────────────

    override suspend fun quota(): QuotaInfo {
        if (!isAuthenticated) return QuotaInfo(total = 0, used = 0, remaining = 0)
        return try {
            api.quotaPropfind() ?: QuotaInfo(total = 0, used = 0, remaining = 0)
        } catch (e: CancellationException) {
            // UD-291: never absorb cancellation. Pre-fix this was caught by
            // the bare `catch (_: Exception)` and degraded to QuotaInfo(0,0,0),
            // breaking structured concurrency on the caller side.
            throw e
        } catch (e: Exception) {
            // UD-291: log the failure with class + throwable. Pre-fix the
            // catch was bare (`catch (_: Exception)`) and dropped the
            // exception entirely — a network failure (timeout, 503, DNS,
            // TLS) was indistinguishable from "server doesn't expose quota
            // properties". RelocateCommand's `targetQuota.total > 0` guard
            // was bypassed in both cases without any signal.
            //
            // Continues to return the (0,0,0) sentinel for API compatibility
            // — every caller already treats `total == 0` as "unknown".
            // Promoting to a nullable / CapabilityResult signature is a
            // separate ticket (would touch all 8 providers + tests) once
            // RelocateCommand grows a `--ignore-quota` flag.
            log.warn(
                "WebDAV quota PROPFIND failed: {}: {}",
                e.javaClass.simpleName,
                e.message,
                e,
            )
            QuotaInfo(total = 0, used = 0, remaining = 0)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSnapshot(entries: List<WebDavEntry>): WebDavSnapshot {
        val map =
            entries.associate { entry ->
                entry.path to
                    WebDavSnapshotEntry(
                        size = entry.size,
                        lastModified = entry.lastModified?.toString(),
                        etag = entry.etag,
                        isFolder = entry.isFolder,
                    )
            }
        return WebDavSnapshot(entries = map)
    }

    private fun WebDavEntry.toCloudItem(): CloudItem =
        CloudItem(
            id = path,
            name = path.substringAfterLast("/"),
            path = path.ifEmpty { "/" },
            size = size,
            isFolder = isFolder,
            modified = lastModified,
            created = lastModified,
            hash = etag,
            mimeType = if (isFolder) null else "application/octet-stream",
        )
}
