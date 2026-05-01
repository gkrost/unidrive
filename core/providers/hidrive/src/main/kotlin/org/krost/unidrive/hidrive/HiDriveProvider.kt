package org.krost.unidrive.hidrive

import org.krost.unidrive.*
import org.krost.unidrive.hidrive.model.HiDriveItem
import org.krost.unidrive.sync.computeSnapshotDelta
import java.nio.file.Path
import java.time.Instant

class HiDriveProvider(
    private val config: HiDriveConfig = HiDriveConfig(),
) : CloudProvider {
    override val id = "hidrive"
    override val displayName = "IONOS HiDrive"

    private val oauthService = OAuthService(config)
    private val tokenManager = TokenManager(config, oauthService)
    private val api = HiDriveApiService(config) { tokenManager.getValidToken().accessToken }

    override val isAuthenticated: Boolean get() = tokenManager.isAuthenticated
    override val canAuthenticate: Boolean get() =
        config.clientId.isNotBlank() &&
            config.clientSecret.isNotBlank() &&
            java.nio.file.Files
                .exists(config.tokenPath.resolve("token.json"))

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.QuotaExact,
        )

    override suspend fun authenticate() {
        tokenManager.initialize()
        if (!tokenManager.isAuthenticated) {
            tokenManager.authenticateWithBrowser()
        }
        // UD-752: post-authenticate debug log lifted to authenticateAndLog wrapper.
    }

    override suspend fun logout() = tokenManager.logout()

    override suspend fun listChildren(path: String): List<CloudItem> {
        val home = api.getHome()
        return api.listDirectory(path).map { it.toCloudItem(home) }
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val home = api.getHome()
        return api.getMetadata(path).toCloudItem(home)
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val home = api.getHome()
        val metadata = api.getMetadata(remotePath)
        api.downloadFile(remotePath, destination)
        return metadata.size
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val home = api.getHome()
        val dir =
            remotePath.removePrefix("/").substringBeforeLast("/", "").let {
                if (it.isEmpty()) "/" else "/$it"
            }
        val name = remotePath.substringAfterLast("/")
        return api.uploadFile(localPath, dir, name, onProgress).toCloudItem(home)
    }

    override suspend fun delete(remotePath: String) {
        try {
            api.deleteFile(remotePath)
        } catch (e: HiDriveApiException) {
            if (e.statusCode == 404) {
                // May be a directory — try directory delete
                try {
                    api.deleteDirectory(remotePath)
                } catch (e2: HiDriveApiException) {
                    if (e2.statusCode != 404) throw e2
                }
            } else {
                throw e
            }
        }
    }

    override suspend fun createFolder(path: String): CloudItem {
        val home = api.getHome()
        return api.createFolder(path).toCloudItem(home)
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val home = api.getHome()
        val metadata = api.getMetadata(fromPath)
        return if (metadata.type == "dir") {
            api.moveDirectory(fromPath, toPath).toCloudItem(home)
        } else {
            api.moveFile(fromPath, toPath).toCloudItem(home)
        }
    }

    override suspend fun delta(cursor: String?): DeltaPage {
        val home = api.getHome()
        val currentItems = api.listRecursive("/")
        val snapshotEntries = buildSnapshotEntries(currentItems, home)
        val itemsByPath =
            currentItems.associate { item ->
                api.toRelativePath(item.path ?: "", home) to item.toCloudItem(home)
            }
        return computeSnapshotDelta(
            currentEntries = snapshotEntries,
            currentItemsByPath = itemsByPath,
            prevCursor = cursor,
            entrySerializer = SnapshotEntry.serializer(),
            hasChanged = { prev, curr ->
                prev.chash != curr.chash || prev.size != curr.size || prev.mtime != curr.mtime
            },
            deletedItem = { path, entry ->
                CloudItem(
                    id = entry.id,
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

    override suspend fun quota(): QuotaInfo = api.getQuota()

    override fun close() {
        oauthService.close()
    }

    private fun buildSnapshotEntries(
        items: List<HiDriveItem>,
        home: String,
    ): Map<String, SnapshotEntry> {
        val entries =
            items.associate { item ->
                val relativePath = api.toRelativePath(item.path ?: "", home)
                relativePath to
                    SnapshotEntry(
                        id = item.id ?: "",
                        chash = item.chash,
                        size = item.size,
                        mtime = item.mtime,
                        isFolder = item.type == "dir",
                    )
            }
        return entries
    }

    private fun HiDriveItem.toCloudItem(home: String): CloudItem {
        val absPath = this.path ?: ""
        val relativePath =
            api.toRelativePath(absPath, home).let {
                if (it.isEmpty()) "/" else it
            }

        return CloudItem(
            id = this.id ?: "",
            name = this.name ?: relativePath.substringAfterLast("/"),
            path = relativePath,
            size = this.size,
            isFolder = this.type == "dir",
            modified = this.mtime?.let { Instant.ofEpochSecond(it) },
            created = this.created?.let { Instant.ofEpochSecond(it) },
            hash = this.chash,
            mimeType = this.mimeType,
            deleted = false,
        )
    }
}
