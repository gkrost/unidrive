package org.krost.unidrive.onedrive

import org.krost.unidrive.*
import org.krost.unidrive.onedrive.model.DriveItem
import org.krost.unidrive.onedrive.model.Subscription
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class OneDriveProvider(
    private val config: OneDriveConfig = OneDriveConfig(),
) : CloudProvider {
    override val id = "onedrive"
    override val displayName = "Microsoft OneDrive"

    private val log = LoggerFactory.getLogger(OneDriveProvider::class.java)
    private val oauthService = OAuthService(config)
    private val tokenManager = TokenManager(config, oauthService)

    private val loggedVaultThisSession = AtomicBoolean(false)

    private val graphApi =
        GraphApiService(config) { forceRefresh ->
            tokenManager.getValidToken(forceRefresh).accessToken
        }

    override val isAuthenticated: Boolean get() = tokenManager.isAuthenticated
    override val canAuthenticate: Boolean get() = Files.exists(config.tokenPath.resolve("token.json"))
    val includeShared: Boolean get() = config.includeShared

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.DeltaShared,
            Capability.Webhook,
            Capability.Share,
            Capability.ListShares,
            Capability.RevokeShare,
            Capability.VerifyItem,
            Capability.QuotaExact,
            Capability.FastBootstrap,
        )

    override suspend fun authenticate() {
        tokenManager.initialize()
        if (!tokenManager.isAuthenticated) {
            tokenManager.authenticateWithBrowser()
        }
    }

    suspend fun authenticateWithDeviceCode() {
        tokenManager.initialize()
        if (!tokenManager.isAuthenticated) {
            tokenManager.authenticateWithDeviceCode()
        }
    }

    override suspend fun logout() = tokenManager.logout()

    override fun close() {
        oauthService.close()
        graphApi.close()
    }

    override suspend fun listChildren(path: String): List<CloudItem> {
            return graphApi.listChildren(path).filterNot { noteAndSkipVault(it) }.map { it.toCloudItem() }
    }

    override suspend fun getMetadata(path: String): CloudItem = graphApi.getItemByPath(path).toCloudItem()

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val item = graphApi.getItemByPath(remotePath)
        graphApi.downloadFile(item.id, destination)
        return item.size
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val fileSize = Files.size(localPath)
        return try {
            if (fileSize <= 4 * 1024 * 1024) {
                val content = Files.readAllBytes(localPath)
                graphApi.uploadSimple(remotePath, content).toCloudItem()
            } else {
                graphApi.uploadLargeFile(localPath, remotePath, onProgress).toCloudItem()
            }
        } catch (e: GraphApiException) {
            if (e.statusCode == 409 && e.message?.contains("nameAlreadyExists") == true) {
                log.warn(
                    "UD-307: OneDrive rejected '{}' with nameAlreadyExists — likely a " +
                        "ZWJ-compound emoji filename or other server-side normalisation " +
                        "collision. See docs/SPECS.md §3.1. Sync continues; rename the " +
                        "source file to work around.",
                    remotePath,
                )
            }
            throw e
        }
    }

    override suspend fun delete(remotePath: String) {
        val item =
            try {
                graphApi.getItemByPath(remotePath)
            } catch (e: GraphApiException) {
                if (e.statusCode == 404) return // Already deleted
                throw e
            }
        graphApi.deleteItem(item.id)
    }

    override suspend fun createFolder(path: String): CloudItem {
        val cleanPath = path.removePrefix("/")
        val name = cleanPath.substringAfterLast("/")
        val parent =
            cleanPath.substringBeforeLast("/", "").let {
                if (it.isEmpty()) "/" else "/$it"
            }
        return graphApi.createFolder(name, parent).toCloudItem()
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val item = graphApi.getItemByPath(fromPath)
        return graphApi.moveItem(item.id, toPath, fromPath).toCloudItem()
    }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
    ): DeltaPage {
        val result = graphApi.getDelta(cursor)
        val items =
            result.items
                .filterNot { it.isRootItem() }
                .filterNot { noteAndSkipVault(it) }
                .map { it.toCloudItem() }

        return DeltaPage(
            items = items,
            cursor = result.deltaLink ?: result.nextLink ?: "",
            hasMore = result.nextLink != null,
        )
    }

    override suspend fun deltaFromLatest(): CapabilityResult<DeltaPage> {
        val result = graphApi.getDelta(fromLatest = true)
        val items = result.items.filterNot { it.isRootItem() }.map { it.toCloudItem() }
        return CapabilityResult.Success(
            DeltaPage(
                items = items,
                cursor = result.deltaLink ?: result.nextLink ?: "",
                hasMore = result.nextLink != null,
            ),
        )
    }

    override suspend fun deltaWithShared(cursor: String?): CapabilityResult<DeltaPage> {
        if (!config.includeShared) {
            // The capability is supported in principle, but the config has not
            // opted in. Fall through to a plain delta by returning Unsupported so
            // callers can decide what to do — mirrors the previous super call.
            return CapabilityResult.Unsupported(
                Capability.DeltaShared,
                "includeShared is disabled in provider config",
            )
        }
        val result = graphApi.getDelta(cursor)

        val mainItems =
            result.items
                .filterNot { it.isRootItem() }
                .filterNot { noteAndSkipVault(it) }
                .map { it.toCloudItem() }

        val sharedItems =
            graphApi.listSharedWithMe().map { item ->
                val sharedPath = "/Shared/${item.name ?: "unnamed"}"
                CloudItem(
                    id = "shared:${item.id}",
                    name = item.name ?: "unnamed",
                    path = sharedPath,
                    size = item.size,
                    isFolder = item.isFolder,
                    modified = item.lastModifiedDateTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    created = item.createdDateTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    hash = item.file?.hashes?.sha256Hash,
                    mimeType = item.file?.mimeType,
                )
            }
        log.debug("Delta: {} shared items", sharedItems.size)

        return CapabilityResult.Success(
            DeltaPage(
                items = mainItems + sharedItems,
                cursor = result.deltaLink ?: result.nextLink ?: "",
                hasMore = result.nextLink != null,
            ),
        )
    }

    override suspend fun quota(): QuotaInfo = graphApi.getQuota()

    suspend fun getIdentity(): org.krost.unidrive.onedrive.model.GraphMe = graphApi.getMe()

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> =
        try {
            graphApi.getItemById(remoteId)
            CapabilityResult.Success(true)
        } catch (e: GraphApiException) {
            if (e.statusCode == 404) {
                CapabilityResult.Success(false)
            } else {
                throw e
            }
        }

    private fun DriveItem.isRootItem(): Boolean = parentReference?.path == null || parentReference?.path == "/drive/root"

    private fun noteAndSkipVault(item: DriveItem): Boolean {
        if (!item.isPersonalVault) return false
        val displayName = item.name ?: "(unnamed)"
        if (loggedVaultThisSession.compareAndSet(false, true)) {
            log.info("Skipping Personal Vault entry (protected): {}", displayName)
        } else {
            log.debug("Skipping Personal Vault entry (protected): {}", displayName)
        }
        return true
    }

    private fun DriveItem.toCloudItem(): CloudItem {
        val parentPath =
            parentReference
                ?.path
                ?.removePrefix("/drive/root:")
                ?.ifEmpty { "/" }
                ?: "/"
        val fullPath = if (parentPath == "/") "/${name ?: ""}" else "$parentPath/${name ?: ""}"

        return CloudItem(
            id = id,
            name = name ?: "",
            path = fullPath,
            size = size,
            isFolder = folder != null,
            modified = lastModifiedDateTime?.let { Instant.parse(it) },
            created = createdDateTime?.let { Instant.parse(it) },
            hash = file?.hashes?.sha256Hash ?: file?.hashes?.quickXorHash,
            mimeType = file?.mimeType,
            deleted = removed != null || this.deleted != null,
        )
    }

    override suspend fun share(
        path: String,
        expiryHours: Int,
        password: String?,
    ): CapabilityResult<String> {
        val url =
            graphApi.createSharingLink(path, expiryHours = expiryHours, password = password)
                ?: return CapabilityResult.Unsupported(Capability.Share, "OneDrive could not create a sharing link for $path")
        return CapabilityResult.Success(url)
    }

    override suspend fun listShares(path: String): CapabilityResult<List<ShareInfo>> =
        CapabilityResult.Success(graphApi.listPermissions(path))

    override suspend fun revokeShare(
        path: String,
        shareId: String,
    ): CapabilityResult<Unit> =
        if (graphApi.deletePermission(path, shareId)) {
            CapabilityResult.Success(Unit)
        } else {
            CapabilityResult.Unsupported(Capability.RevokeShare, "OneDrive failed to revoke share $shareId at $path")
        }

    suspend fun createSubscription(
        notificationUrl: String,
        expirationDateTime: String,
    ): Subscription? = graphApi.createSubscription(notificationUrl, expirationDateTime)

    suspend fun renewSubscription(
        subscriptionId: String,
        expirationDateTime: String,
    ): Boolean = graphApi.renewSubscription(subscriptionId, expirationDateTime)

    suspend fun deleteSubscription(subscriptionId: String): Boolean = graphApi.deleteSubscription(subscriptionId)

    override fun hashAlgorithm(): HashAlgorithm = HashAlgorithm.QuickXor

    override fun statusFields(): List<StatusField> =
        listOf(
            StatusField(
                label = "Include shared",
                value = config.includeShared.toString(),
            ),
        )
}
