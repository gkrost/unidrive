package org.krost.unidrive.rclone

import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.slf4j.LoggerFactory
import java.nio.file.Path

class RcloneProvider(
    private val config: RcloneConfig,
) : CloudProvider {
    override val id = "rclone"
    override val displayName = "Rclone"
    override var isAuthenticated: Boolean = false
        private set
    override val canAuthenticate: Boolean get() = config.remote.isNotBlank()

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    private val cli = RcloneCliService(config)
    private val log = LoggerFactory.getLogger(RcloneProvider::class.java)

    // ── Auth ────────────────────────────────────────────────────────────────

    override suspend fun authenticate() {
        cli.verifyRemote()
        isAuthenticated = true
    }

    override suspend fun logout() {
        isAuthenticated = false
    }

    // ── Listing ─────────────────────────────────────────────────────────────

    override suspend fun listChildren(path: String): List<CloudItem> = cli.list(path).map { RcloneCliService.toCloudItem(it, config.path) }

    override suspend fun getMetadata(path: String): CloudItem = RcloneCliService.toCloudItem(cli.stat(path), config.path)

    // ── File operations ─────────────────────────────────────────────────────

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = cli.copyTo(remotePath, destination)

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = download(remotePath, destination)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem = cli.upload(localPath, remotePath)

    override suspend fun delete(remotePath: String) {
        cli.deleteFile(remotePath)
    }

    override suspend fun createFolder(path: String): CloudItem = cli.mkdir(path)

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = cli.moveTo(fromPath, toPath)

    // ── Delta ───────────────────────────────────────────────────────────────

    override suspend fun delta(cursor: String?): DeltaPage {
        val currentEntries = cli.listAllRecursive()
        return computeDelta(currentEntries, cursor, config.path)
    }

    // ── Quota ───────────────────────────────────────────────────────────────

    override suspend fun quota(): QuotaInfo =
        try {
            val info = cli.about()
            QuotaInfo(
                total = info["total"] ?: 0L,
                used = info["used"] ?: 0L,
                remaining = info["free"] ?: 0L,
            )
        } catch (e: RcloneException) {
            log.warn("Quota not supported by rclone backend, reporting 0: {}", e.message)
            QuotaInfo(total = 0, used = 0, remaining = 0)
        }

    // ── Delta computation (public for testing) ──────────────────────────────

    companion object {
        fun computeDelta(
            currentEntries: List<RcloneEntry>,
            cursor: String?,
            basePath: String,
        ): DeltaPage {
            val currentSnapshot = buildSnapshot(currentEntries, basePath)

            if (cursor == null) {
                return DeltaPage(
                    items = currentEntries.map { RcloneCliService.toCloudItem(it, basePath) },
                    cursor = currentSnapshot.encode(),
                    hasMore = false,
                )
            }

            val previousSnapshot = RcloneSnapshot.decode(cursor)
            val changes = mutableListOf<CloudItem>()

            for ((path, entry) in currentSnapshot.entries) {
                val prev = previousSnapshot.entries[path]
                if (prev == null || RcloneSnapshot.hasChanged(prev, entry)) {
                    val found =
                        currentEntries.firstOrNull {
                            "/${it.path}" == path
                        }
                    if (found != null) changes.add(RcloneCliService.toCloudItem(found, basePath))
                }
            }

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

        private fun buildSnapshot(
            entries: List<RcloneEntry>,
            @Suppress("UNUSED_PARAMETER") basePath: String,
        ): RcloneSnapshot {
            val map =
                entries.associate { entry ->
                    val virtualPath = "/${entry.path}"
                    virtualPath to
                        RcloneSnapshotEntry(
                            size = entry.size,
                            modTime = entry.modTime,
                            isFolder = entry.isDir,
                            hash = entry.hashes?.values?.firstOrNull(),
                        )
                }
            return RcloneSnapshot(entries = map)
        }
    }
}
