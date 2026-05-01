package org.krost.unidrive.hidrive

import org.krost.unidrive.*
import org.krost.unidrive.hidrive.model.HiDriveItem
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
        val currentSnapshot = buildSnapshot(currentItems, home)

        if (cursor == null) {
            return DeltaPage(
                items = currentItems.map { it.toCloudItem(home) },
                cursor = currentSnapshot.encode(),
                hasMore = false,
            )
        }

        val previousSnapshot = DeltaSnapshot.decode(cursor)
        val changes = mutableListOf<CloudItem>()

        // New and modified items
        for ((path, entry) in currentSnapshot.entries) {
            val prev = previousSnapshot.entries[path]
            if (prev == null || prev.chash != entry.chash || prev.size != entry.size || prev.mtime != entry.mtime) {
                val item = currentItems.find { api.toRelativePath(it.path ?: "", home) == path }
                if (item != null) {
                    changes.add(item.toCloudItem(home))
                }
            }
        }

        // Deleted items
        for ((path, entry) in previousSnapshot.entries) {
            if (path !in currentSnapshot.entries) {
                changes.add(
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

    override suspend fun quota(): QuotaInfo = api.getQuota()

    override fun close() {
        oauthService.close()
    }

    private fun buildSnapshot(
        items: List<HiDriveItem>,
        home: String,
    ): DeltaSnapshot {
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
        return DeltaSnapshot(entries = entries)
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
