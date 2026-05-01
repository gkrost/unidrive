package org.krost.unidrive.sftp

import org.krost.unidrive.*
import org.krost.unidrive.sync.ScanHeartbeat
import org.krost.unidrive.sync.computeSnapshotDelta
import java.nio.file.Path
import java.time.Instant

/**
 * CloudProvider implementation for SFTP servers (OpenSSH, ProFTPD, Hetzner Storage Box, Synology, etc.).
 *
 * Delta strategy: full recursive [SftpApiService.listAll] → compare mtime + size against
 * the previous snapshot stored as a Base64-encoded JSON cursor (same pattern as HiDrive
 * and S3).  [DeltaPage.hasMore] is always false — one full listing per cycle.
 *
 * Move: uses SFTP `rename` (atomic on most servers).
 *
 * Authentication: SSH public-key (identity file) or password; configured via [SftpConfig].
 * No OAuth — credentials are resolved at construction time from [SftpConfig].
 */
class SftpProvider(
    val config: SftpConfig,
) : CloudProvider {
    override val id = "sftp"
    override val displayName = "SFTP"

    private val api = SftpApiService(config)

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    override var isAuthenticated: Boolean = false
        private set
    override val canAuthenticate: Boolean get() =
        config.host.isNotBlank() && (config.identityFile != null || config.password != null)

    override suspend fun authenticate() {
        api.connect()
        isAuthenticated = true
    }

    override suspend fun logout() {
        api.close()
        isAuthenticated = false
    }

    override fun close() = api.close()

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
        api.mkdir(path)
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
        api.rename(fromPath, toPath)
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
        val prefix = path.trimEnd('/') + "/"
        return api
            .listAll(prefix)
            .filter { it.path.removePrefix(prefix).let { rel -> rel.isNotEmpty() && !rel.contains("/") } }
            .map { it.toCloudItem() }
    }

    override suspend fun getMetadata(path: String): CloudItem =
        api.stat(path)?.toCloudItem()
            ?: throw SftpException("SFTP object not found: $path", 2)

    // ── Delta ─────────────────────────────────────────────────────────────────

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
    ): DeltaPage {
        // UD-352: thread per-directory progress through the BFS readdir walk.
        val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }
        val currentEntries = api.listAll(onProgress = { count -> heartbeat?.tick(count) })
        val snapshotEntries = buildSnapshotEntries(currentEntries)
        val itemsByPath = currentEntries.associate { it.path to it.toCloudItem() }
        return computeSnapshotDelta(
            currentEntries = snapshotEntries,
            currentItemsByPath = itemsByPath,
            prevCursor = cursor,
            entrySerializer = SftpSnapshotEntry.serializer(),
            hasChanged = { prev, curr ->
                prev.size != curr.size || prev.mtimeSeconds != curr.mtimeSeconds
            },
            deletedItem = { path, entry ->
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
                )
            },
        )
    }

    // ── Quota ─────────────────────────────────────────────────────────────────

    override suspend fun quota(): QuotaInfo {
        // Standard SFTP has no quota API — return unknown
        return QuotaInfo(total = 0, used = 0, remaining = 0)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSnapshotEntries(entries: List<SftpEntry>): Map<String, SftpSnapshotEntry> =
        entries.associate { entry ->
            entry.path to
                SftpSnapshotEntry(
                    size = entry.size,
                    mtimeSeconds = entry.mtimeSeconds,
                    isFolder = entry.isFolder,
                )
        }

    private fun SftpEntry.toCloudItem(): CloudItem =
        CloudItem(
            id = path,
            name = path.substringAfterLast("/"),
            path = path.ifEmpty { "/" },
            size = size,
            isFolder = isFolder,
            modified = modified,
            created = modified,
            hash = null,
            mimeType = if (isFolder) null else "application/octet-stream",
        )
}
