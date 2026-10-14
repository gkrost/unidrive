package org.krost.unidrive.dropbox

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaCursorExpiredException
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ShareInfo
import org.krost.unidrive.dropbox.DropboxApi.Companion.obj
import org.krost.unidrive.dropbox.DropboxItems.Companion.apiPath
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.streamingFileBody
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections

/**
 * Dropbox through its HTTP API v2 (no SDK).
 *
 * - **Changes:** `files/list_folder` (recursive, deletions included) and `list_folder/continue` are the delta; a
 *   `reset` answer is [DeltaCursorExpiredException]. `get_latest_cursor` is the fast bootstrap. The long-poll on the
 *   last cursor is the wake-up hint.
 * - **Conditional writes:** the engine's write token is the content hash it last saw. A guarded upload or delete reads
 *   the file's metadata, refuses when the hash moved on ([RemoteConflictException]), and otherwise writes against that
 *   metadata's `rev` (`update` mode, `parent_rev`), so a change landing in between is refused by Dropbox itself.
 * - **Ids:** every item's `id:` addresses it directly, so the id-addressed download, move and delete never resolve by
 *   name.
 * - **Team spaces:** when the account's root namespace is not its home namespace, every call carries the root as its
 *   path root, so team folders are visible next to the member folder.
 */
class DropboxProvider(
    private val config: DropboxConfig,
    engine: HttpClientEngine? = null,
) : CloudProvider {
    override val id = "dropbox"
    override val displayName = "Dropbox"

    private val log = org.slf4j.LoggerFactory.getLogger(DropboxProvider::class.java)
    private val api: DropboxApi = DropboxApi(config, { force -> auth.accessToken(force) }, engine)
    private val auth: DropboxAuth = DropboxAuth(config, api.httpClient)
    private val items = DropboxItems()
    private val pathRootLock = Mutex()

    @Volatile
    private var pathRootKnown = false

    // The cursor of the last finished delta walk: what the long-poll waits on.
    @Volatile
    private var lastCursor: String? = null

    // The engine's heartbeat climbs (itemsSoFar): the walk's running total across the delta calls that chain its
    // pages, and whether the last page said hasMore (a no after it means the next call begins a new walk).
    private var pageProgress = 0
    private var walkHasMore = false

    // Cursors handed out in the middle of a full listing (one that began without a cursor). Their pages list what
    // exists; a deleted entry there is history from before the walk, never a deletion to act on.
    private val fullListingCursors = Collections.synchronizedSet(HashSet<String>())

    private val hintScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hintJob: Job? = null

    @Volatile
    private var hintCallback: () -> Unit = {}

    override var isAuthenticated: Boolean
        get() = auth.isAuthenticated
        set(_) { /* the token is the state; logout() clears it */ }

    override val canAuthenticate: Boolean get() = Files.exists(config.tokenPath.resolve(DropboxAuth.TOKEN_FILE))

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.FastBootstrap,
            Capability.Share,
            Capability.ListShares,
            Capability.RevokeShare,
            Capability.QuotaExact,
            Capability.VerifyItem,
        )

    override fun hashAlgorithm(): HashAlgorithm = HashAlgorithm.DropboxContentHash

    override fun attachRequestMetrics(sink: org.krost.unidrive.http.RequestMetricsSink?) = api.attachRequestMetrics(sink)

    override suspend fun authenticate() {
        auth.load()
        if (!auth.isAuthenticated) auth.signInWithBrowser()
    }

    override suspend fun logout() {
        auth.logout()
        pathRootKnown = false
        api.pathRoot = null
    }

    override fun close() {
        hintScope.cancel()
        api.close()
    }

    // The account's root namespace, read once before the first call that names a path.
    private suspend fun ready() {
        if (pathRootKnown) return
        pathRootLock.withLock {
            if (pathRootKnown) return
            val account = api.rpc("users/get_current_account")
            val rootInfo = account["root_info"]?.jsonObject
            val root = rootInfo?.get("root_namespace_id")?.jsonPrimitive?.contentOrNull
            val home = rootInfo?.get("home_namespace_id")?.jsonPrimitive?.contentOrNull
            api.pathRoot =
                if (root != null && root != home) {
                    log.info("Dropbox account is in a team space; paths start at the team root")
                    DropboxApi.headerSafeJson(obj(".tag" to "root", "root" to root))
                } else {
                    null
                }
            pathRootKnown = true
        }
    }

    override suspend fun listChildren(path: String): List<CloudItem> {
        ready()
        val out = mutableListOf<CloudItem>()
        var page = api.rpc("files/list_folder", listArgs(apiPath(path), recursive = false, includeDeleted = false))
        while (true) {
            page["entries"]?.jsonArray?.forEach { out += items.toCloudItem(it.jsonObject) }
            if (page["has_more"]?.jsonPrimitive?.booleanOrNull != true) return out
            page = api.rpc("files/list_folder/continue", obj("cursor" to page.cursor()))
        }
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val target = apiPath(path)
        if (target.isEmpty()) return ROOT
        ready()
        return items.toCloudItem(metadata(target))
    }

    private suspend fun metadata(target: String): JsonObject = api.rpc("files/get_metadata", obj("path" to target, "include_deleted" to false))

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = fetch(apiPath(remotePath), remotePath, destination)

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = fetch(if (remoteId.startsWith("id:")) remoteId else apiPath(remotePath), remotePath, destination)

    private suspend fun fetch(
        target: String,
        remotePath: String,
        destination: Path,
    ): Long {
        ready()
        return try {
            api.download(target, destination).second
        } catch (e: DropboxApiException) {
            if (e.isA("path/not_found")) {
                throw PermanentDownloadFailureException("Dropbox has no file at '$remotePath' any more", e, e.requestId)
            }
            throw e
        }
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val target = apiPath(remotePath)
        val name = target.substringAfterLast('/')
        if (DropboxItems.isIgnoredName(name)) {
            throw PermanentUploadFailureException("Dropbox does not sync files named '$name'")
        }
        ready()
        val size = Files.size(localPath)
        val mode =
            if (ifMatchETag != null) {
                obj(".tag" to "update", "update" to currentRev(target, ifMatchETag))
            } else {
                obj(".tag" to "overwrite")
            }
        val commit =
            obj(
                "path" to target,
                "mode" to mode,
                "autorename" to false,
                // Dropbox keeps whole seconds; the engine's own record of the time comes back from this answer.
                "client_modified" to Files.getLastModifiedTime(localPath).toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
                "strict_conflict" to (ifMatchETag != null),
            )
        val meta =
            try {
                if (size <= config.simpleUploadMaxBytes) {
                    api.content("files/upload", commit, UploadTimeoutPolicy.computeRequestTimeoutMs(size)) {
                        streamingFileBody(localPath, size)
                    }
                } else {
                    uploadInSession(localPath, size, commit, onProgress)
                }
            } catch (e: DropboxApiException) {
                throw uploadFailure(e, remotePath, guarded = ifMatchETag != null)
            }
        onProgress?.invoke(size, size)
        return items.toCloudItem(meta)
    }

    // The rev to write against when the caller saw the content [expectedHash]; a different content (or none) is a
    // conflict. A token that is not a content hash is taken as a rev as it is.
    private suspend fun currentRev(
        target: String,
        expectedHash: String,
    ): String {
        if (!CONTENT_HASH.matches(expectedHash)) return expectedHash
        val meta =
            try {
                metadata(target)
            } catch (e: DropboxApiException) {
                throw if (e.isA("path/not_found")) RemoteConflictException("'$target' was removed on Dropbox since it was last synced", e) else e
            }
        val sameContent = !items.isFolder(meta) && items.contentHash(meta)?.equals(expectedHash, ignoreCase = true) == true
        return items.rev(meta)?.takeIf { sameContent }
            ?: throw RemoteConflictException("'$target' changed on Dropbox since it was last synced")
    }

    private suspend fun uploadInSession(
        localPath: Path,
        size: Long,
        commit: JsonObject,
        onProgress: ((Long, Long) -> Unit)?,
    ): JsonObject {
        val sessionId =
            api.content("files/upload_session/start", obj("close" to false)) { ByteArray(0) }["session_id"]
                ?.jsonPrimitive
                ?.contentOrNull ?: throw ProviderException("Dropbox started an upload session without an id")
        val buffer = ByteArray(config.uploadChunkBytes)
        var offset = 0L
        RandomAccessFile(localPath.toFile(), "r").use { file ->
            while (offset < size) {
                val length = withContext(Dispatchers.IO) {
                    file.seek(offset)
                    file.read(buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt())
                }
                if (length <= 0) throw java.io.IOException("'$localPath' became shorter while it was uploaded")
                val chunk = buffer.copyOf(length)
                api.content(
                    "files/upload_session/append_v2",
                    obj("cursor" to obj("session_id" to sessionId, "offset" to offset), "close" to false),
                    UploadTimeoutPolicy.computeRequestTimeoutMs(length.toLong()),
                ) { chunk }
                offset += length
                onProgress?.invoke(offset, size)
            }
        }
        return api.content(
            "files/upload_session/finish",
            obj("cursor" to obj("session_id" to sessionId, "offset" to offset), "commit" to commit),
        ) { ByteArray(0) }
    }

    private fun uploadFailure(
        e: DropboxApiException,
        remotePath: String,
        guarded: Boolean,
    ): Exception {
        val summary = e.summary.substringAfter("path/", e.summary)
        return when {
            guarded && (summary.startsWith("conflict") || e.summary.contains("/conflict")) ->
                RemoteConflictException("'$remotePath' changed on Dropbox since it was last synced", e, e.requestId)
            summary.startsWith("disallowed_name") || summary.startsWith("malformed_path") ->
                PermanentUploadFailureException("Dropbox refuses the name of '$remotePath' (${e.summary})", e, e.requestId)
            else -> e
        }
    }

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) = remove(apiPath(remotePath), ifMatchETag)

    override suspend fun deleteById(
        remoteId: String,
        remotePath: String,
    ) = remove(if (remoteId.startsWith("id:")) remoteId else apiPath(remotePath), null)

    private suspend fun remove(
        target: String,
        ifMatchETag: String?,
    ) {
        if (target.isEmpty()) throw ProviderException("Refusing to delete the Dropbox root")
        ready()
        val parentRev = if (ifMatchETag != null) revUnlessGone(target, ifMatchETag) ?: return else null
        try {
            api.rpc("files/delete_v2", obj("path" to target, "parent_rev" to parentRev))
        } catch (e: DropboxApiException) {
            if (e.isA("path_lookup/not_found")) return
            throw if (parentRev != null && e.summary.contains("conflict")) {
                RemoteConflictException("'$target' changed on Dropbox since it was last synced", e, e.requestId)
            } else {
                e
            }
        }
    }

    // [currentRev] for a delete: null when the file is gone already, which is what a delete wants.
    private suspend fun revUnlessGone(
        target: String,
        expectedHash: String,
    ): String? =
        try {
            currentRev(target, expectedHash)
        } catch (e: RemoteConflictException) {
            if ((e.cause as? DropboxApiException)?.isA("path/not_found") == true) null else throw e
        }

    override suspend fun createFolder(path: String): CloudItem {
        val target = apiPath(path)
        if (target.isEmpty()) return ROOT
        ready()
        return try {
            items.toCloudItem(api.rpc("files/create_folder_v2", obj("path" to target, "autorename" to false))["metadata"]!!.jsonObject)
        } catch (e: DropboxApiException) {
            // A folder that is there already is the folder asked for (a retry after a lost answer).
            if (e.isA("path/conflict/folder")) return getMetadata(path)
            throw e
        }
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = relocate(apiPath(fromPath), apiPath(toPath))

    override suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem = relocate(if (remoteId.startsWith("id:")) remoteId else apiPath(fromPath), apiPath(toPath), apiPath(fromPath))

    // Dropbox compares names without case, so a rename that changes only the case goes through a temporary name.
    private suspend fun relocate(
        from: String,
        to: String,
        fromPath: String = from,
    ): CloudItem {
        ready()
        if (fromPath != to && fromPath.equals(to, ignoreCase = true)) {
            val parked = "${to.substringBeforeLast('/')}/.unidrive-case-${java.util.UUID.randomUUID().toString().take(8)}"
            moveOnce(from, parked)
            return moveOnce(parked, to)
        }
        return moveOnce(from, to)
    }

    private suspend fun moveOnce(
        from: String,
        to: String,
    ): CloudItem =
        items.toCloudItem(
            api.rpc("files/move_v2", obj("from_path" to from, "to_path" to to, "autorename" to false))["metadata"]!!.jsonObject,
        )

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        ready()
        if (cursor == null || !walkHasMore) pageProgress = 0
        val fullListing = cursor == null || cursor in fullListingCursors
        val page =
            try {
                if (cursor == null) {
                    api.rpc("files/list_folder", listArgs("", recursive = true))
                } else {
                    api.rpc("files/list_folder/continue", obj("cursor" to cursor))
                }
            } catch (e: DropboxApiException) {
                if (e.isA("reset")) {
                    throw DeltaCursorExpiredException("Dropbox reset the change cursor; a full listing is needed", e, e.requestId)
                }
                throw e
            }
        val entries = page["entries"]?.jsonArray?.map { items.toCloudItem(it.jsonObject) }.orEmpty()
        val visible = if (fullListing) entries.filterNot { it.deleted } else entries
        val next = page.cursor()
        val hasMore = page["has_more"]?.jsonPrimitive?.booleanOrNull == true
        cursor?.let { fullListingCursors.remove(it) }
        if (hasMore && fullListing) fullListingCursors += next
        if (!hasMore) lastCursor = next
        pageProgress += visible.size
        walkHasMore = hasMore
        onPageProgress?.invoke(pageProgress)
        return DeltaPage(visible, next, hasMore)
    }

    override suspend fun deltaFromLatest(scanContext: ScanContext?): CapabilityResult<DeltaPage> {
        ready()
        val cursor = api.rpc("files/list_folder/get_latest_cursor", listArgs("", recursive = true)).cursor()
        lastCursor = cursor
        return CapabilityResult.Success(DeltaPage(emptyList(), cursor, hasMore = false))
    }

    // Deletions are always asked for: the cursor keeps the arguments it was made with, and a cursor without them
    // would never report a deletion. Files that cannot be downloaded (Paper docs, cloud-only Google files) are left out.
    private fun listArgs(
        path: String,
        recursive: Boolean,
        includeDeleted: Boolean = true,
    ) = obj(
        "path" to path,
        "recursive" to recursive,
        "include_deleted" to includeDeleted,
        "include_non_downloadable_files" to false,
        "limit" to LIST_LIMIT,
    )

    private fun JsonObject.cursor(): String =
        this["cursor"]?.jsonPrimitive?.contentOrNull ?: throw ProviderException("Dropbox answered a listing without a cursor")

    /**
     * Long-polls the cursor of the last finished delta walk and calls [callback] when something under it changed. After a
     * hint the cursor stays the same until the engine walks the delta, so the next poll waits for a new cursor first
     * (or [HINT_REPEAT_MS], in case the engine did not walk).
     */
    override fun onRemoteChangeHint(callback: () -> Unit) {
        hintCallback = callback
        synchronized(this) {
            if (hintJob != null) return
            hintJob = hintScope.launch { longPollLoop() }
        }
    }

    @Suppress("TooGenericExceptionCaught") // the hint loop outlives any one failed poll; cancellation still ends it
    private suspend fun CoroutineScope.longPollLoop() {
        var failures = 0
        while (isActive) {
            val cursor = lastCursor
            if (cursor == null || !auth.isAuthenticated) {
                delay(IDLE_POLL_MS)
                continue
            }
            try {
                val answer = api.longpoll(cursor, LONGPOLL_TIMEOUT_S)
                failures = 0
                if (answer["changes"]?.jsonPrimitive?.booleanOrNull == true) {
                    hintCallback()
                    awaitNewCursor(cursor)
                }
                answer["backoff"]?.jsonPrimitive?.intOrNull?.let { delay(it * 1000L) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DropboxApiException) {
                // A reset cursor: the next delta walk sees it too and starts over; wait for its new cursor.
                if (e.isA("reset")) awaitNewCursor(cursor) else delay(failureWaitMs(++failures))
            } catch (e: Exception) {
                log.debug("Dropbox long-poll failed ({}); retrying", e.javaClass.simpleName)
                delay(failureWaitMs(++failures))
            }
        }
    }

    private suspend fun awaitNewCursor(seen: String) {
        val until = System.currentTimeMillis() + HINT_REPEAT_MS
        while (lastCursor == seen && System.currentTimeMillis() < until) delay(IDLE_POLL_MS)
    }

    private fun failureWaitMs(failures: Int): Long = (IDLE_POLL_MS shl failures.coerceAtMost(6)).coerceAtMost(MAX_FAILURE_WAIT_MS)

    override suspend fun quota(): QuotaInfo {
        val usage = api.rpc("users/get_space_usage")
        val used = usage["used"]?.jsonPrimitive?.longOrNull ?: 0
        val total = usage["allocation"]?.jsonObject?.get("allocated")?.jsonPrimitive?.longOrNull ?: 0
        return QuotaInfo(total = total, used = used, remaining = (total - used).coerceAtLeast(0))
    }

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> {
        ready()
        return try {
            metadata(remoteId)
            CapabilityResult.Success(true)
        } catch (e: DropboxApiException) {
            if (e.isA("path/not_found")) CapabilityResult.Success(false) else throw e
        }
    }

    override suspend fun share(
        path: String,
        expiryHours: Int,
        password: String?,
    ): CapabilityResult<String> {
        ready()
        val target = apiPath(path)
        val settings =
            obj(
                "expires" to if (expiryHours > 0) Instant.now().plus(expiryHours.toLong(), ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString() else null,
                "link_password" to password,
                "requested_visibility" to if (password != null) "password" else null,
            )
        return try {
            val link = api.rpc("sharing/create_shared_link_with_settings", obj("path" to target, "settings" to settings))
            CapabilityResult.Success(link["url"]!!.jsonPrimitive.content)
        } catch (e: DropboxApiException) {
            val existing = e.error?.get("shared_link_already_exists")?.jsonObject?.get("metadata")?.jsonObject?.get("url")
            when {
                e.isA("shared_link_already_exists") && existing != null && settings.isEmpty() ->
                    CapabilityResult.Success(existing.jsonPrimitive.content)
                e.isA("shared_link_already_exists") ->
                    throw ProviderException("'$path' already has a Dropbox link; revoke it first to make one with an expiry or a password", e, e.requestId)
                e.isA("settings_error/not_authorized") ->
                    throw ProviderException("This Dropbox plan cannot give links an expiry or a password; share with --expiry 0 and no password", e, e.requestId)
                else -> throw e
            }
        }
    }

    override suspend fun listShares(path: String): CapabilityResult<List<ShareInfo>> {
        ready()
        val answer = api.rpc("sharing/list_shared_links", obj("path" to apiPath(path), "direct_only" to true))
        val links =
            answer["links"]?.jsonArray.orEmpty().map { it.jsonObject }.map { link ->
                val permissions = link["link_permissions"]?.jsonObject
                val visibility =
                    permissions?.get("resolved_visibility")?.jsonObject?.get(".tag")?.jsonPrimitive?.contentOrNull ?: "public"
                ShareInfo(
                    id = link["url"]!!.jsonPrimitive.content,
                    url = link["url"]!!.jsonPrimitive.content,
                    type = "view",
                    scope = visibility,
                    hasPassword = visibility == "password",
                    expiration = link["expires"]?.jsonPrimitive?.contentOrNull,
                )
            }
        return CapabilityResult.Success(links)
    }

    override suspend fun revokeShare(
        path: String,
        shareId: String,
    ): CapabilityResult<Unit> {
        ready()
        api.rpc("sharing/revoke_shared_link", obj("url" to shareId))
        return CapabilityResult.Success(Unit)
    }

    companion object {
        private const val LIST_LIMIT = 2000
        private const val LONGPOLL_TIMEOUT_S = 480
        private const val IDLE_POLL_MS = 5_000L
        private const val HINT_REPEAT_MS = 60_000L
        private const val MAX_FAILURE_WAIT_MS = 300_000L
        private val CONTENT_HASH = Regex("^[0-9a-fA-F]{64}$")
        private val ROOT = CloudItem("", "", "/", 0, isFolder = true, modified = null, created = null, hash = null, mimeType = null)
    }
}
