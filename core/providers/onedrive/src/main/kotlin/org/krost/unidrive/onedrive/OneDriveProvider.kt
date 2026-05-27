package org.krost.unidrive.onedrive

import org.krost.unidrive.*
import org.krost.unidrive.onedrive.model.DriveItem
import org.krost.unidrive.onedrive.model.FileSystemInfo
import org.krost.unidrive.onedrive.model.Subscription
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

    // UD-007: tokenManager is the source of truth — the setter is a no-op so
    // the default CloudProvider.logout()'s `isAuthenticated = false` becomes
    // harmless; this provider's own `logout()` override flips state via
    // `tokenManager.logout()` which the getter then reflects.
    override var isAuthenticated: Boolean
        get() = tokenManager.isAuthenticated
        set(_) { /* delegated to tokenManager; setter is a no-op */ }
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
        return try {
            graphApi.downloadFile(item.id, destination)
            item.size
        } catch (e: GraphApiException) {
            // A 404 on the content GET means the pre-resolved download URL went
            // stale (the CDN URL expired) and/or the cached remoteId drifted off
            // the live item. Re-resolving BY PATH once fixes both: it yields a
            // fresh id AND a fresh download URL. Bounded to a single retry so a
            // genuinely-missing item still surfaces a clean not-found instead of
            // spinning. Any non-404 (403, 410, 5xx) propagates unchanged.
            if (e.statusCode != 404) throw e
            log.info(
                "OneDrive download of '{}' got 404 (stale download URL or drifted remoteId); " +
                    "re-resolving the item by path and retrying once",
                remotePath,
            )
            val fresh = graphApi.getItemByPath(remotePath)
            graphApi.downloadFile(fresh.id, destination)
            fresh.size
        }
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val attrs = Files.readAttributes(localPath, BasicFileAttributes::class.java)
        val fileSize = attrs.size()
        val fsInfo =
            FileSystemInfo(
                createdDateTime = attrs.creationTime().toInstant().toString(),
                lastModifiedDateTime = attrs.lastModifiedTime().toInstant().toString(),
            )

        // One upload, two conflict policies. "replace" is only legitimate for an UPDATE of a
        // file we own (existingRemoteId != null) — a re-upload of a touched file must not 409.
        // A CREATE (existingRemoteId == null) uploads with "fail" so a pre-existing remote at
        // the path is never blind-overwritten (UD-366 data-loss); the create-collision is then
        // resolved by keeping BOTH below.
        suspend fun put(conflictBehavior: String, ifMatchETag: String? = null) =
            if (fileSize <= 4 * 1024 * 1024) {
                val content = Files.readAllBytes(localPath)
                graphApi.uploadSimple(remotePath, content, fsInfo, conflictBehavior)
            } else {
                graphApi.uploadLargeFile(localPath, remotePath, onProgress, fsInfo, conflictBehavior, ifMatchETag)
            }

        if (existingRemoteId != null) {
            // MODIFIED file we own → replace-in-place. For large-file uploads the session-create
            // is guarded by If-Match so a concurrent edit surfaces as 412 instead of silently
            // overwriting the other editor's change (#113).
            val ifMatchETag =
                if (fileSize > 4 * 1024 * 1024) {
                    runCatching { graphApi.getItemByPath(remotePath) }.getOrNull()?.eTag
                } else {
                    null
                }
            return try {
                put("replace", ifMatchETag).toCloudItem()
            } catch (e: GraphApiException) {
                if (e.statusCode == 409 && e.message?.contains("nameAlreadyExists") == true) {
                    log.warn(
                        "UD-307: OneDrive rejected '{}' with nameAlreadyExists on a replace — likely a " +
                            "ZWJ-compound emoji filename or other server-side normalisation collision. " +
                            "See docs/SPECS.md §3.1. Sync continues; rename the source file to work around.",
                        remotePath,
                    )
                }
                throw e
            }
        }

        // NEW file → never blind-overwrite an unknown remote (UD-366). Try "fail"; on a
        // collision keep BOTH — the pre-existing remote is preserved untouched and the local
        // copy lands under a server-assigned name via "rename".
        return try {
            put("fail").toCloudItem()
        } catch (e: GraphApiException) {
            if (e.statusCode == 409 && e.message?.contains("nameAlreadyExists") == true) {
                log.warn(
                    "UD-366: create-collision on '{}' — a different remote already exists at this path; " +
                        "preserving it untouched and landing the local copy as a keep-both copy " +
                        "(conflictBehavior=rename) rather than overwriting it.",
                    remotePath,
                )
                put("rename").toCloudItem()
            } else {
                throw e
            }
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
        return graphApi.moveItem(item.id, toPath, fromPath, ifMatchETag = item.eTag).toCloudItem()
    }

    // 410 Gone on any delta call = cursor aged out / drive re-keyed.  Convert to a
    // typed DeltaCursorExpiredException so callers can self-heal with a full
    // re-enumeration.  Any other status propagates unchanged.
    private suspend fun getDeltaConverting410(cursor: String?) =
        try {
            graphApi.getDelta(cursor)
        } catch (e: GraphApiException) {
            if (e.statusCode == 410) {
                throw DeltaCursorExpiredException(e.message ?: "Delta cursor expired (410 Gone)", e, e.requestId)
            }
            throw e
        }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage {
        // OneDrive's Graph delta endpoint pages via opaque @odata.nextLink
        // tokens that already encode a resumable cursor; the engine-side
        // staging slice is therefore redundant here and the parameter is
        // intentionally unused. Left in the signature so the SPI shape is
        // uniform across providers — see CloudProvider.kt for the contract.
        val result = getDeltaConverting410(cursor)
        val visibleItems =
            result.items
                .filterNot { it.isRootItem() }
                .filterNot { noteAndSkipVault(it) }
        logDeletionStateSummary(visibleItems)
        val items = visibleItems.map { it.toCloudItem() }

        return DeltaPage(
            items = items,
            cursor = result.deltaLink ?: result.nextLink ?: "",
            hasMore = result.nextLink != null,
        )
    }

    private fun logDeletionStateSummary(items: List<DriveItem>) {
        // Graph's two deletion signals on a delta page mean different things; surface the
        // distinction at DEBUG so an operator triaging "deleted X items today" can tell
        // hard deletes (recycle bin) from access-revocations (still extant elsewhere).
        var hardDelete = 0 // @microsoft.graph.removed state=deleted, or deleted facet present
        var softRemove = 0 // @microsoft.graph.removed state=removed (permission lost / out of view)
        var unspecified = 0 // either facet present but no state field (pre-2017 schema fallback)
        for (item in items) {
            val removedState = item.removed?.state
            val deletedPresent = item.deleted != null
            when {
                removedState == "removed" -> softRemove++
                removedState == "deleted" || deletedPresent -> hardDelete++
                item.removed != null -> unspecified++
            }
        }
        if (hardDelete + softRemove + unspecified > 0) {
            log.debug(
                "Delta page deletion mix: hardDelete={} softRemove={} unspecified={} (totalItems={})",
                hardDelete,
                softRemove,
                unspecified,
                items.size,
            )
        }
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
        val result = getDeltaConverting410(cursor)

        val mainItems =
            result.items
                .filterNot { it.isRootItem() }
                .filterNot { noteAndSkipVault(it) }
                .map { it.toCloudItem() }

        val sharedItems =
            graphApi.listSharedWithMe().map { item ->
                val sharedPath = "/Shared/${item.name ?: "unnamed"}"
                val modifiedSource = item.fileSystemInfo?.lastModifiedDateTime ?: item.lastModifiedDateTime
                val createdSource = item.fileSystemInfo?.createdDateTime ?: item.createdDateTime
                CloudItem(
                    id = "shared:${item.id}",
                    name = item.name ?: "unnamed",
                    path = sharedPath,
                    size = item.size,
                    isFolder = item.isFolder,
                    modified = modifiedSource?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    created = createdSource?.let { runCatching { Instant.parse(it) }.getOrNull() },
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

        // Graph's top-level lastModifiedDateTime/createdDateTime are server-stamped on
        // commit; fileSystemInfo carries the client-supplied local mtime/ctime that
        // round-trips through the API. Prefer it when present so a fresh download
        // gives the local file the same mtime it had at upload time.
        val modifiedSource = fileSystemInfo?.lastModifiedDateTime ?: lastModifiedDateTime
        val createdSource = fileSystemInfo?.createdDateTime ?: createdDateTime

        return CloudItem(
            id = id,
            name = name ?: "",
            path = fullPath,
            size = size,
            isFolder = folder != null,
            modified = modifiedSource?.let { runCatching { Instant.parse(it) }.getOrNull() },
            created = createdSource?.let { runCatching { Instant.parse(it) }.getOrNull() },
            // #112 / PR #193: prefer quickXorHash so remoteHash is consistent with
            // hashAlgorithm()=QuickXor. Personal OneDrive reliably supplies quickXorHash
            // for all files; sha256Hash is a fallback for drives that omit it (e.g. some
            // SharePoint/business tenants). When neither field is present (folder, root,
            // or draft item) hash is null and #112's isTouchOnly guard (remoteHash != null)
            // safely falls back to mtime+size. One-time effect: existing DB entries whose
            // stored remoteHash was SHA-256 will mismatch on the next enumeration and get
            // re-stored as QuickXor — a single re-compare, not data loss.
            hash = file?.hashes?.quickXorHash ?: file?.hashes?.sha256Hash,
            mimeType = file?.mimeType,
            deleted = isHardDeleted(),
            // #183: a `@microsoft.graph.removed` facet with state="removed" means the
            // item's access was revoked (shared link revoked / moved out of scope). The
            // path resolver (SyncEngine.resolveItemPath) uses this flag to retire the
            // DB row via TRASHED without touching the local file.
            accessRevoked = removed?.state == "removed",
        )
    }

    // Graph's delta page carries two distinct removal signals, and only one of them
    // means the local copy should go. A `@microsoft.graph.removed` facet with
    // `state == "removed"` is a SOFT removal — the item still exists, the user merely
    // lost access (a shared-with-me link was revoked, or the item moved out of scope).
    // Reaping the local copy on that signal destroys still-valid data. A hard delete is
    // either `removed.state == "deleted"` or the standalone `deleted` facet. A removed
    // facet with no state at all is the pre-2017 schema fallback; we cannot prove it is
    // soft, so we keep the historical reading and treat it as a hard delete.
    private fun DriveItem.isHardDeleted(): Boolean {
        val removedFacet = removed
        if (removedFacet != null) {
            return removedFacet.state != "removed"
        }
        return deleted != null
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
