package org.krost.unidrive.onedrive

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.ProviderException
import org.krost.unidrive.ShareInfo
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.onedrive.model.*
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class GraphApiService(
    private val config: OneDriveConfig,
    // UD-232: shared circuit breaker + token bucket. One instance per service (= per
    // profile). Test constructor can inject a fake-clock budget; production picks up the
    // default 8-permit budget with 200ms inter-request spacing. Kept as a constructor
    // parameter (not a property of tokenProvider) so the trailing-lambda call style at
    // OneDriveProvider:24 stays readable.
    private val throttleBudget: HttpRetryBudget = HttpRetryBudget(maxConcurrency = 8),
    // UD-310: tokenProvider takes an explicit forceRefresh hint. Normal callers pass false;
    // the on-401 retry path inside authenticatedRequest passes true to bypass the cached-
    // token fast path and issue a real OAuth refresh before resending.
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(GraphApiService::class.java)

    private val httpClient =
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
                socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
                requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
            }
            // UD-255: per-request correlation id + DEBUG req/response logging.
            install(org.krost.unidrive.http.RequestId)
        }
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val sessionStore = UploadSessionStore(config.tokenPath)
    private val baseUrl = "${OneDriveConfig.GRAPH_BASE_URL}/${OneDriveConfig.GRAPH_VERSION}"

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    suspend fun getDrive(): Drive {
        val response = authenticatedRequest("$baseUrl/me/drive")
        val body = response.bodyAsText()
        val parsed = json.decodeFromString<DriveResponse>(body)

        return Drive(
            id = parsed.id ?: "",
            driveType = parsed.driveType ?: "personal",
            owner = parsed.owner,
            quota = parsed.quota,
        )
    }

    /**
     * UD-216: call Graph `/me` and return a minimal identity record. Only
     * parses the fields the MCP `unidrive_identity` tool surfaces — keeps the
     * serializer tolerant to the dozens of other fields Graph returns.
     */
    suspend fun getMe(): GraphMe {
        val response = authenticatedRequest("$baseUrl/me")
        val body = response.bodyAsText()
        return json.decodeFromString<GraphMe>(body)
    }

    suspend fun getQuota(): org.krost.unidrive.QuotaInfo {
        val drive = getDrive()
        val quota = drive.quota ?: return org.krost.unidrive.QuotaInfo(0, 0, 0)
        return org.krost.unidrive.QuotaInfo(
            total = quota.total,
            used = quota.used,
            remaining = quota.remaining,
        )
    }

    suspend fun getItemByPath(path: String): DriveItem {
        val cleanPath = path.removePrefix("/").ifEmpty { "" }
        val url =
            if (cleanPath.isEmpty()) {
                "$baseUrl/me/drive/root"
            } else {
                "$baseUrl/me/drive/root:/${encodePath(cleanPath)}"
            }

        val response = authenticatedRequest(url)
        val body = response.bodyAsText()
        return json.decodeFromString<DriveItem>(body)
    }

    suspend fun getItemById(id: String): DriveItem {
        val response = authenticatedRequest("$baseUrl/me/drive/items/$id")
        val body = response.bodyAsText()
        return json.decodeFromString<DriveItem>(body)
    }

    suspend fun listChildren(path: String): List<DriveItem> {
        // UD-314: Graph defaults to ~200 items per page and signals more via `@odata.nextLink`.
        // The old implementation read only the first page, silently truncating any folder with
        // > 200 children. Bump `$top=999` (Graph's hard cap) to shrink the common case to one
        // round-trip, then loop-follow nextLink for folders that exceed it. Debug verbosity
        // matches getDelta: at most one line per page walked, not one per item.
        val cleanPath = path.removePrefix("/").ifEmpty { "" }
        val firstUrl =
            if (cleanPath.isEmpty()) {
                "$baseUrl/me/drive/root/children?\$top=999"
            } else {
                "$baseUrl/me/drive/root:/${encodePath(cleanPath)}:/children?\$top=999"
            }

        val accumulated = mutableListOf<DriveItem>()
        var nextUrl: String? = firstUrl
        var pageIndex = 0
        while (nextUrl != null) {
            val response = authenticatedRequest(nextUrl)
            val body = response.bodyAsText()
            val collection = json.decodeFromString<DriveItemCollectionResponse>(body)
            accumulated.addAll(collection.value)
            log.debug("listChildren page {}: {} items, hasMore={}", pageIndex, collection.value.size, collection.nextLink != null)
            nextUrl = collection.nextLink
            pageIndex++
        }
        return accumulated
    }

    suspend fun getDelta(
        link: String? = null,
        fromLatest: Boolean = false,
    ): DeltaResult {
        // UD-223: fromLatest bootstraps a fresh profile via `?token=latest` — Graph
        // returns the current cursor with zero items instead of enumerating the drive.
        // Mutually exclusive with `link`; if a cursor is already in hand, resume from it.
        val url = link ?: if (fromLatest) "$baseUrl/me/drive/root/delta?token=latest" else "$baseUrl/me/drive/root/delta"
        log.debug("Delta cursor: {}", link?.takeLast(40) ?: if (fromLatest) "(token=latest bootstrap)" else "(initial)")

        // UD-308: `bodyAsText()` returns whatever bytes arrived before the connection closed —
        // a transient short-read on a large delta page yields a truncated string that fails
        // `decodeFromString` with "Expected end of the object '}', but had 'EOF'". On a ~1300-
        // page first-sync even a sub-percent flake rate makes full enumeration fail in
        // expectation. Retry same-URL — Graph delta is idempotent at the cursor level.
        var attempt = 0
        val maxAttempts = 3
        while (true) {
            try {
                val response = authenticatedRequest(url)
                val body = response.bodyAsText()
                val parsed = json.decodeFromString<DriveItemCollectionResponse>(body)
                log.debug("Delta: {} items, hasMore={}", parsed.value.size, parsed.nextLink != null)
                return DeltaResult(
                    items = parsed.value,
                    nextLink = parsed.nextLink,
                    deltaLink = parsed.deltaLink,
                )
            } catch (e: AuthenticationException) {
                throw e // token failures aren't retryable
            } catch (e: kotlinx.coroutines.CancellationException) {
                // UD-300: propagate cancellation cleanly, don't retry.
                throw e
            } catch (e: kotlinx.serialization.SerializationException) {
                attempt++
                if (attempt >= maxAttempts) {
                    log.warn("Graph delta parse failed after {} attempts at {}: {}", attempt, url.takeLast(60), e.message)
                    throw e
                }
                val backoff = 2000L * (1L shl (attempt - 1)) // 2s, 4s
                log.warn("Graph delta parse failed (attempt {}/{}), retrying in {}ms: {}", attempt, maxAttempts, backoff, e.message)
                delay(backoff)
            } catch (e: java.io.IOException) {
                attempt++
                if (attempt >= maxAttempts) throw e
                val backoff = 2000L * (1L shl (attempt - 1))
                log.warn("Graph delta I/O failed (attempt {}/{}), retrying in {}ms: {}", attempt, maxAttempts, backoff, e.message)
                delay(backoff)
            }
        }
    }

    suspend fun downloadFile(
        itemId: String,
        destPath: Path,
    ) {
        log.debug("Download: itemId={}", itemId)
        val item = getItemById(itemId)
        val url = item.downloadUrl ?: "$baseUrl/me/drive/items/$itemId/content"
        val authNeeded = item.downloadUrl == null

        // UD-309: the Azure Blob/CDN endpoints that OneDrive hands out as `downloadUrl` close
        // the connection mid-stream often enough on first-sync-scale workloads that Ktor throws
        // "Content-Length mismatch" for ~5 % of files in the 1 MB–500 MB range. Same-URL retry
        // recovers almost all of them. Authenticated retries on the `$baseUrl/.../content`
        // fallback path work identically because both are idempotent GETs.
        //
        // UD-227: the CDN path also returns 429/503. Previously this threw GraphApiException
        // which UD-309's retry explicitly passed through. Now both paths go through the same
        // throttle-and-retry pipeline as authenticatedRequest, and the `retryAfterSeconds` JSON
        // body hint is honoured as a fallback when the `Retry-After` header is missing (Graph's
        // CDN edge sometimes includes it only in the body).
        //
        // UD-329: the previous body-handling went `httpClient.get(url)` →
        // `response.body<ByteReadChannel>()`, which on Ktor 3.x buffers the entire response
        // into a single `byte[contentLength]` before exposing the channel. Files larger than
        // `Integer.MAX_VALUE` (~2.147 GiB) failed at allocation time with "Can't create an
        // array of size N". Switched to `prepareGet(url).execute { }` so `bodyAsChannel()`
        // returns a true streaming channel — no allocation grows with file size, the existing
        // 8 KiB ring buffer is the only memory the download holds. The 401-refresh-once and
        // 429/503 throttle paths are inlined into this loop because `execute { }` is the only
        // way to scope the streaming body lifetime correctly; `authenticatedRequest`'s
        // buffered-response shape doesn't match the streaming case.
        var flakeAttempts = 0
        var throttleAttempts = 0
        var totalThrottleWaitMs = 0L
        var authRefreshed = false
        while (true) {
            try {
                // UD-232: gate every request start through the shared throttle budget so
                // concurrent coroutines cooperate during a storm instead of each burning
                // their own 5-attempt allowance independently.
                throttleBudget.awaitSlot()
                val statement =
                    httpClient.prepareGet(url) {
                        if (authNeeded) bearerAuth(tokenProvider(authRefreshed))
                    }
                val outcome: DownloadOutcome =
                    statement.execute { response ->
                        if (authNeeded && response.status == HttpStatusCode.Unauthorized && !authRefreshed) {
                            return@execute DownloadOutcome.RetryAuth
                        }
                        if (response.status == HttpStatusCode.Unauthorized) {
                            throw AuthenticationException(
                                "Authentication failed (401): ${readBoundedErrorBody(response)}",
                            )
                        }
                        if (shouldBackoff(response, throttleAttempts, totalThrottleWaitMs)) {
                            // UD-227 + UD-293: read a bounded prefix for retryAfterSeconds parsing.
                            // The Graph throttle JSON body is < 1 KB; bounding here keeps the OOM
                            // risk gone even on the off-chance that a CDN edge attaches a giant
                            // body to a 429/503 (observed: text/html captive portal pages with
                            // Content-Length matching the original asset = 2+ GB).
                            val body = readBoundedErrorBody(response, maxBytes = 16384)
                            val waitMs = pickBackoffMsWithBody(response, body, throttleAttempts, totalThrottleWaitMs)
                            return@execute DownloadOutcome.Throttle(waitMs, response.status.value)
                        }
                        if (!response.status.isSuccess()) {
                            throw GraphApiException(
                                "Download failed: ${response.status} - ${readBoundedErrorBody(response)}",
                                response.status.value,
                            )
                        }
                        // UD-231: CDN edge nodes occasionally return HTTP 200 with a `text/html` body
                        // (tenant throttle page, captive-portal redirect, expired-URL login page). The
                        // raw HTML would otherwise stream straight into the destination file at the
                        // correct byte size — sweep detectors for NUL-stub corruption (UD-226) would
                        // NOT flag it, because HTML is non-NUL content. Guard here before any write:
                        // if Content-Type is `text/html` (any charset), treat as a retriable flake so
                        // the UD-309 flake loop catches it, retries the same URL, and surfaces a
                        // hard failure after MAX_FLAKE_ATTEMPTS rather than silently writing garbage.
                        //
                        // UD-293: pre-fix used `response.bodyAsText().take(200)` which materialised
                        // the ENTIRE response body as a String before the take — when the CDN
                        // returned a 2.3 GB binary file with Content-Type: text/html (Graph
                        // throttle page misreported as the file's MIME), bodyAsText OOM'd with
                        // "Can't create an array of size 2_233_659_189". Now reads at most 4 KB
                        // off the channel for the snippet — the diagnostic value is the FIRST
                        // bytes (HTML preamble), not the full page.
                        val contentType = response.contentType()
                        if (contentType != null && contentType.match(ContentType.Text.Html)) {
                            val snippet = readBoundedErrorBody(response, maxBytes = 4096).take(200)
                            throw java.io.IOException(
                                "Download returned HTML instead of file bytes (status=${response.status.value}, " +
                                    "Content-Type=$contentType): $snippet",
                            )
                        }
                        throttleBudget.recordSuccess()
                        // UD-329: bodyAsChannel() inside execute{} is the Ktor-blessed streaming path.
                        val channel: ByteReadChannel = response.bodyAsChannel()
                        withContext(Dispatchers.IO) {
                            Files.createDirectories(destPath.parent)
                            Files.newOutputStream(destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = channel.readAvailable(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        DownloadOutcome.Done
                    }

                when (outcome) {
                    DownloadOutcome.Done -> return
                    DownloadOutcome.RetryAuth -> {
                        log.info("Got 401 on download itemId={} — forcing token refresh and retrying once", itemId)
                        authRefreshed = true
                        continue
                    }
                    is DownloadOutcome.Throttle -> {
                        log.warn(
                            "Got {} on download itemId={} — throttled, waiting {}ms (attempt {}/{})",
                            outcome.statusCode,
                            itemId,
                            outcome.waitMs,
                            throttleAttempts + 1,
                            MAX_THROTTLE_ATTEMPTS,
                        )
                        throttleBudget.recordThrottle(outcome.waitMs)
                        delay(outcome.waitMs)
                        throttleAttempts++
                        totalThrottleWaitMs += outcome.waitMs
                        continue
                    }
                }
            } catch (e: AuthenticationException) {
                throw e
            } catch (e: GraphApiException) {
                // HTTP-status failures (403/404/etc) aren't short-read flakes; don't retry.
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                // UD-300: cancellation must propagate — never retry. A sibling
                // download's auth failure cancelled the scope; the next delay()
                // in the retry loop would throw CancellationException again and
                // we'd chew through the flake budget logging the misleading
                // `ScopeCoroutine was cancelled` (2x observed at unidrive.log
                // 09:16:51 on 2026-04-19). Kotlin's structured-concurrency rule:
                // CancellationException must not be absorbed by a generic catch.
                throw e
            } catch (e: Exception) {
                flakeAttempts++
                if (flakeAttempts >= MAX_FLAKE_ATTEMPTS) {
                    log.warn("Graph download failed after {} attempts for itemId={}: {}", flakeAttempts, itemId, e.message)
                    throw e
                }
                val backoff = 2000L * (1L shl (flakeAttempts - 1)) // 2s, 4s
                log.warn(
                    "Graph download failed (attempt {}/{}), retrying in {}ms: {}",
                    flakeAttempts,
                    MAX_FLAKE_ATTEMPTS,
                    backoff,
                    e.message,
                )
                delay(backoff)
            }
        }
    }

    /**
     * UD-293: read at most [maxBytes] from the response body channel and decode
     * as UTF-8. Used everywhere the streaming-download flow needs a diagnostic
     * snippet from a non-success / non-JSON-shaped response.
     *
     * Pre-fix the surrounding code used `response.bodyAsText()` which
     * materialises the ENTIRE response into a String — when the CDN edge
     * returned a 2.3 GB binary with `Content-Type: text/html` (Graph throttle
     * captive-portal page misreported as the file's MIME), the allocation
     * failed with `Can't create an array of size 2_233_659_189` and crashed
     * the download attempt. The flake-retry loop then re-invoked the request
     * which paper-over-succeeded on attempt 2 because the CDN had reset to
     * the binary path — but it could equally have OOM'd N more times.
     *
     * Output is then run through [truncateErrorBody] (already-existing) so
     * the log layout doesn't get a multi-page HTML preamble.
     */
    private suspend fun readBoundedErrorBody(
        response: HttpResponse,
        maxBytes: Int = 4096,
    ): String =
        try {
            val channel = response.bodyAsChannel()
            val buf = ByteArray(maxBytes)
            val read = channel.readAvailable(buf, 0, maxBytes).coerceAtLeast(0)
            truncateErrorBody(String(buf, 0, read, Charsets.UTF_8))
        } catch (_: Exception) {
            // If even the bounded read fails (channel already consumed,
            // network died), surface a placeholder rather than crash the
            // diagnostic path.
            "<body unavailable>"
        }

    /** UD-227: Graph sometimes tucks the retry-after seconds into the JSON error body, e.g.
     *  `{"error": {..., "retryAfterSeconds": 5}}`. Read the header first; fall back to the body. */
    private fun pickBackoffMsWithBody(
        response: HttpResponse,
        body: String,
        attempts: Int,
        totalWaitMs: Long,
    ): Long {
        val retryAfterHeader = response.headers["Retry-After"]?.toLongOrNull()?.let { it * 1000 }
        val retryAfterBody = parseRetryAfterFromJsonBody(body)?.let { it * 1000 }
        val hintedMs = retryAfterHeader ?: retryAfterBody
        val backoffMs = hintedMs ?: (DEFAULT_BACKOFF_START_MS * (1L shl attempts))
        val remainingBudget = MAX_TOTAL_THROTTLE_WAIT_MS - totalWaitMs
        return minOf(backoffMs, MAX_SINGLE_BACKOFF_MS, remainingBudget)
    }

    private fun parseRetryAfterFromJsonBody(body: String): Long? =
        try {
            val element = json.parseToJsonElement(body)
            element.jsonObject["error"]
                ?.jsonObject
                ?.get("retryAfterSeconds")
                ?.jsonPrimitive
                ?.longOrNull
                ?: element.jsonObject["retryAfterSeconds"]?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            null
        }

    suspend fun uploadSimple(
        remotePath: String,
        content: ByteArray,
    ): DriveItem {
        log.debug("Upload (simple): {} ({} bytes)", remotePath, content.size)
        val cleanPath = remotePath.removePrefix("/")
        val encoded = encodePath(cleanPath)

        val response =
            httpClient.put("$baseUrl/me/drive/root:/$encoded:/content") {
                bearerAuth(tokenProvider(false))
                contentType(ContentType.Application.OctetStream)
                setBody(content)
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401): ${response.bodyAsText()}")
        }
        if (!response.status.isSuccess()) {
            throw GraphApiException("Upload failed: ${response.status} - ${response.bodyAsText()}", response.status.value)
        }

        val body = response.bodyAsText()
        return json.decodeFromString<DriveItem>(body)
    }

    suspend fun deleteItem(itemId: String) {
        log.debug("Delete: itemId={}", itemId)
        val response =
            httpClient.request("$baseUrl/me/drive/items/$itemId") {
                bearerAuth(tokenProvider(false))
                method = HttpMethod("DELETE")
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401): ${response.bodyAsText()}")
        }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.NotFound) {
            throw GraphApiException("Delete failed: ${response.status}", response.status.value)
        }
    }

    suspend fun createFolder(
        name: String,
        parentPath: String?,
    ): DriveItem {
        log.debug("mkdir: {}/{}", parentPath ?: "/", name)
        val url =
            if (parentPath == null || parentPath == "/") {
                "$baseUrl/me/drive/root/children"
            } else {
                val clean = parentPath.removePrefix("/")
                "$baseUrl/me/drive/root:/${encodePath(clean)}:/children"
            }

        val requestBody =
            buildJsonObject {
                put("name", name)
                putJsonObject("folder") {}
            }
        val response =
            httpClient.post(url) {
                bearerAuth(tokenProvider(false))
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401): ${response.bodyAsText()}")
        }
        if (!response.status.isSuccess()) {
            throw GraphApiException("Create folder failed: ${response.status} - ${response.bodyAsText()}", response.status.value)
        }

        return json.decodeFromString<DriveItem>(response.bodyAsText())
    }

    suspend fun moveItem(
        itemId: String,
        newPath: String,
        oldPath: String? = null,
    ): DriveItem {
        log.debug("Move: {} -> {}", oldPath ?: itemId, newPath)
        val cleanPath = newPath.removePrefix("/")
        val newName = cleanPath.substringAfterLast("/")
        val newParentPath = cleanPath.substringBeforeLast("/", "")
        val oldParentPath = oldPath?.removePrefix("/")?.substringBeforeLast("/", "")

        val body =
            buildJsonObject {
                put("name", newName)
                if (oldParentPath == null || newParentPath != oldParentPath) {
                    val parentItem =
                        if (newParentPath.isEmpty()) {
                            getItemByPath("")
                        } else {
                            getItemByPath(newParentPath)
                        }
                    putJsonObject("parentReference") {
                        put("id", parentItem.id)
                    }
                }
            }

        val response =
            httpClient.request("$baseUrl/me/drive/items/$itemId") {
                bearerAuth(tokenProvider(false))
                method = HttpMethod("PATCH")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401): ${response.bodyAsText()}")
        }
        if (!response.status.isSuccess()) {
            throw GraphApiException("Move failed: ${response.status} - ${response.bodyAsText()}", response.status.value)
        }

        return json.decodeFromString<DriveItem>(response.bodyAsText())
    }

    suspend fun uploadLargeFile(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): DriveItem {
        val fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
        log.debug("Upload (chunked): {} ({} bytes)", remotePath, fileSize)
        val cleanPath = remotePath.removePrefix("/")
        val encoded = encodePath(cleanPath)

        // Try to resume a persisted session from a previous run.
        val (uploadUrl, initialOffset) = resolveUploadSession(remotePath, cleanPath, encoded)

        val chunkSize = 10L * 1024 * 1024 // 10 MiB (multiple of 320 KiB)

        if (initialOffset > 0) log.debug("Upload resuming at offset {} / {} bytes", initialOffset, fileSize)
        var offset = initialOffset
        var result: DriveItem? = null

        try {
            while (offset < fileSize) {
                val currentChunkSize = minOf(chunkSize, fileSize - offset).toInt()
                val bytes =
                    withContext(Dispatchers.IO) {
                        val buf = ByteArray(currentChunkSize)
                        RandomAccessFile(localPath.toFile(), "r").use { raf ->
                            raf.seek(offset)
                            raf.readFully(buf)
                        }
                        buf
                    }

                val endByte = offset + currentChunkSize - 1
                val response = uploadChunkWithRetry(uploadUrl, bytes, offset, endByte, fileSize)

                when (response.status.value) {
                    200, 201 -> result = json.decodeFromString<DriveItem>(response.bodyAsText())
                    202 -> {} // More chunks needed
                    else -> throw GraphApiException(
                        "Chunk upload failed at offset $offset: ${response.status} - ${response.bodyAsText()}",
                        response.status.value,
                    )
                }

                offset += currentChunkSize
                log.debug("Upload progress: {}/{} bytes", offset, fileSize)
                onProgress?.invoke(offset, fileSize)
            }
        } catch (e: Exception) {
            // On permanent failure (session expired, auth error) remove the stored session.
            sessionStore.delete(remotePath)
            throw e
        }

        sessionStore.delete(remotePath)
        return result ?: throw GraphApiException("Upload session completed but no DriveItem returned")
    }

    /**
     * Returns the upload URL and the byte offset to start from.
     * Checks [sessionStore] first; if a valid session exists, queries its status to find
     * the committed offset.  If none, creates a fresh session and persists it.
     */
    private suspend fun resolveUploadSession(
        remotePath: String,
        cleanPath: String,
        encoded: String,
    ): Pair<String, Long> {
        val stored = sessionStore.get(remotePath)
        if (stored != null) {
            // Query the stored session to find committed offset.
            val statusResponse = httpClient.get(stored)
            if (statusResponse.status.isSuccess()) {
                val session = json.decodeFromString<UploadSession>(statusResponse.bodyAsText())
                val committedOffset =
                    session.nextExpectedRanges
                        ?.firstOrNull()
                        ?.substringBefore("-")
                        ?.toLongOrNull()
                        ?: 0L
                log.debug("Upload session resumed: {} at offset {}", remotePath, committedOffset)
                return stored to committedOffset
            }
            // Session expired on the server — fall through to create a new one.
            log.debug("Upload session expired for {}, creating new session", remotePath)
            sessionStore.delete(remotePath)
        }

        // Create a new upload session.
        val sessionResponse =
            httpClient.post("$baseUrl/me/drive/root:/$encoded:/createUploadSession") {
                bearerAuth(tokenProvider(false))
                contentType(ContentType.Application.Json)
                setBody("""{"item": {"@microsoft.graph.conflictBehavior": "replace"}}""")
            }

        if (sessionResponse.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401): ${sessionResponse.bodyAsText()}")
        }
        if (!sessionResponse.status.isSuccess()) {
            throw GraphApiException(
                "Create upload session failed: ${sessionResponse.status} - ${sessionResponse.bodyAsText()}",
                sessionResponse.status.value,
            )
        }

        val session = json.decodeFromString<UploadSession>(sessionResponse.bodyAsText())
        val url =
            session.uploadUrl ?: throw GraphApiException(
                "Create upload session succeeded but response has no uploadUrl: " +
                    sessionResponse.bodyAsText().take(200),
                200,
            )
        val expiresAt =
            session.expirationDateTime
                ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                ?: java.time.Instant
                    .now()
                    .plusSeconds(86_400) // fallback: 24 h

        sessionStore.put(remotePath, url, expiresAt)
        return url to 0L
    }

    /**
     * Upload one chunk with up to 3 retries on transient failures.
     * On each retry, queries the session status to find the committed offset
     * so we don't re-send bytes the server already accepted.
     */
    private suspend fun uploadChunkWithRetry(
        uploadUrl: String,
        bytes: ByteArray,
        offset: Long,
        endByte: Long,
        fileSize: Long,
    ): HttpResponse {
        val retryDelays = listOf(2_000L, 4_000L, 8_000L)
        var currentOffset = offset
        var currentBytes = bytes

        for ((attempt, delayMs) in retryDelays.withIndex()) {
            val response =
                httpClient.put(uploadUrl) {
                    contentType(ContentType.Application.OctetStream)
                    header("Content-Range", "bytes $currentOffset-$endByte/$fileSize")
                    setBody(currentBytes)
                }

            // Success or "more chunks" — return immediately
            if (response.status.value in listOf(200, 201, 202)) return response

            // Session expired or gone — non-recoverable
            if (response.status.value in listOf(404, 410)) {
                throw GraphApiException(
                    "Upload session expired at offset $currentOffset: ${response.status}",
                    response.status.value,
                )
            }

            // Rate limit — retry with backoff
            if (response.status.value == 429) {
                if (attempt == retryDelays.lastIndex) {
                    throw GraphApiException(
                        "Rate limited (429) at offset $currentOffset after ${attempt + 1} retries: ${response.status}",
                        response.status.value,
                    )
                }
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()?.times(1000) ?: delayMs
                delay(retryAfter)
                continue
            }

            // Permanent client error — don't retry
            if (response.status.value in 400..499) {
                throw GraphApiException(
                    "Chunk upload failed at offset $currentOffset: ${response.status} - ${response.bodyAsText()}",
                    response.status.value,
                )
            }

            // Transient server error (5xx, 408) — query status and retry
            if (attempt == retryDelays.lastIndex) {
                throw GraphApiException(
                    "Chunk upload failed after ${attempt + 1} retries at offset $currentOffset: ${response.status}",
                    response.status.value,
                )
            }

            delay(delayMs)

            // Query session status to find committed offset
            val statusResponse = httpClient.get(uploadUrl)
            if (statusResponse.status.value in listOf(404, 410)) {
                throw GraphApiException("Upload session expired while retrying at offset $currentOffset", statusResponse.status.value)
            }
            if (statusResponse.status.isSuccess()) {
                val session = json.decodeFromString<UploadSession>(statusResponse.bodyAsText())
                val nextStart =
                    session.nextExpectedRanges
                        ?.firstOrNull()
                        ?.substringBefore("-")
                        ?.toLongOrNull()
                if (nextStart != null && nextStart > currentOffset) {
                    // Server committed some bytes; trim our buffer to what's still needed
                    val skip = (nextStart - currentOffset).toInt()
                    currentBytes = bytes.copyOfRange(skip, bytes.size)
                    currentOffset = nextStart
                }
            }
        }

        // Unreachable — loop always throws or returns
        throw GraphApiException("Upload chunk failed at offset $currentOffset")
    }

    private suspend fun authenticatedRequest(
        url: String,
        method: HttpMethod = HttpMethod.Get,
    ): HttpResponse {
        // UD-310: on 401, force a token refresh and retry once before surfacing the
        // AuthenticationException. Most in-flight failures are transient — the token expired
        // milliseconds before the request reached Graph, or an earlier sibling coroutine just
        // refreshed behind our back. The refresh mutex in TokenManager serialises concurrent
        // refreshes, so a flood of 401s from a concurrent pass won't each spawn a refresh.
        //
        // UD-207: 429 Too Many Requests (and 503 Service Unavailable) are treated as transient
        // throttling. Graph sends `Retry-After` as an integer number of seconds when it wants
        // us to back off. Honour the header, cap per-call + cumulative wait, and retry the
        // same URL (idempotent GET). Concurrent callers all serialise on Graph's throttle
        // window, so each one sees the recommended delay and the pack naturally de-synchronises.
        var refreshed = false
        var throttleAttempts = 0
        var totalThrottleWaitMs = 0L
        while (true) {
            // UD-232: coordinate request starts across coroutines via the shared budget.
            throttleBudget.awaitSlot()
            val response =
                httpClient.request(url) {
                    bearerAuth(tokenProvider(refreshed))
                    this.method = method
                }
            if (response.status == HttpStatusCode.Unauthorized && !refreshed) {
                log.info("Got 401 on {} — forcing token refresh and retrying once", url.takeLast(60))
                refreshed = true
                continue
            }
            if (response.status == HttpStatusCode.Unauthorized) {
                throw AuthenticationException("Authentication failed (401): ${truncateErrorBody(response.bodyAsText())}")
            }
            if (shouldBackoff(response, throttleAttempts, totalThrottleWaitMs)) {
                val waitMs = pickBackoffMs(response, throttleAttempts, totalThrottleWaitMs)
                log.warn(
                    "Got {} on {} — throttled, waiting {}ms (attempt {}/{})",
                    response.status.value,
                    url.takeLast(60),
                    waitMs,
                    throttleAttempts + 1,
                    MAX_THROTTLE_ATTEMPTS,
                )
                throttleBudget.recordThrottle(waitMs)
                delay(waitMs)
                throttleAttempts++
                totalThrottleWaitMs += waitMs
                continue
            }
            if (!response.status.isSuccess()) {
                throw GraphApiException(
                    "API error: ${response.status} - ${truncateErrorBody(response.bodyAsText())}",
                    response.status.value,
                )
            }
            throttleBudget.recordSuccess()
            return response
        }
    }

    private suspend fun authenticatedRequest(
        url: String,
        method: HttpMethod,
        body: String,
    ): HttpResponse {
        // UD-310 + UD-207: same 401 refresh and 429/503 throttle handling as the no-body overload.
        var refreshed = false
        var throttleAttempts = 0
        var totalThrottleWaitMs = 0L
        while (true) {
            // UD-232: coordinate request starts across coroutines via the shared budget.
            throttleBudget.awaitSlot()
            val response =
                httpClient.request(url) {
                    bearerAuth(tokenProvider(refreshed))
                    this.method = method
                    setBody(body)
                    contentType(ContentType.Application.Json)
                }
            if (response.status == HttpStatusCode.Unauthorized && !refreshed) {
                log.info("Got 401 on {} — forcing token refresh and retrying once", url.takeLast(60))
                refreshed = true
                continue
            }
            if (response.status == HttpStatusCode.Unauthorized) {
                throw AuthenticationException("Authentication failed (401): ${truncateErrorBody(response.bodyAsText())}")
            }
            if (shouldBackoff(response, throttleAttempts, totalThrottleWaitMs)) {
                val waitMs = pickBackoffMs(response, throttleAttempts, totalThrottleWaitMs)
                log.warn(
                    "Got {} on {} — throttled, waiting {}ms (attempt {}/{})",
                    response.status.value,
                    url.takeLast(60),
                    waitMs,
                    throttleAttempts + 1,
                    MAX_THROTTLE_ATTEMPTS,
                )
                throttleBudget.recordThrottle(waitMs)
                delay(waitMs)
                throttleAttempts++
                totalThrottleWaitMs += waitMs
                continue
            }
            if (!response.status.isSuccess()) {
                throw GraphApiException(
                    "API error: ${response.status} - ${truncateErrorBody(response.bodyAsText())}",
                    response.status.value,
                )
            }
            throttleBudget.recordSuccess()
            return response
        }
    }

    /** UD-234: many Graph and SharePoint-Online error bodies are full HTML pages (~3-5 KiB of
     *  inline CSS + branding) that bloat `unidrive.log` — one SharePoint 503 dumps ~60 lines of
     *  markup. Truncate any non-JSON body to its first line + a char-count tail so we keep the
     *  diagnostic hint without drowning the log. JSON bodies pass through unchanged; callers
     *  already rely on `{"error":{...}}` for structured parsing (UD-227 retryAfterSeconds). */
    private fun truncateErrorBody(body: String): String {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return body
        val firstLine =
            body
                .lineSequence()
                .firstOrNull()
                ?.trim()
                .orEmpty()
                .take(200)
        val totalLen = body.length
        return if (totalLen <= 200) body else "$firstLine … [$totalLen chars, non-JSON body truncated]"
    }

    private fun shouldBackoff(
        response: HttpResponse,
        attempts: Int,
        totalWaitMs: Long,
    ): Boolean {
        if (attempts >= MAX_THROTTLE_ATTEMPTS) return false
        if (totalWaitMs >= MAX_TOTAL_THROTTLE_WAIT_MS) return false
        val code = response.status.value
        return code == 429 || code == 503
    }

    private fun pickBackoffMs(
        response: HttpResponse,
        attempts: Int,
        totalWaitMs: Long,
    ): Long {
        // Prefer the server's Retry-After if present. Graph sends integer seconds.
        val retryAfterHeader = response.headers["Retry-After"]
        val retryAfterMs =
            retryAfterHeader?.toLongOrNull()?.let { it * 1000 }
                ?: (DEFAULT_BACKOFF_START_MS * (1L shl attempts)) // 2s, 4s, 8s, 16s
        // Cap per-call and per-sequence budgets — never block a request longer than the
        // remaining throttle budget, and never honour a per-call hint above the single-call cap.
        val remainingBudget = MAX_TOTAL_THROTTLE_WAIT_MS - totalWaitMs
        return minOf(retryAfterMs, MAX_SINGLE_BACKOFF_MS, remainingBudget)
    }

    // UD-329: signals from the streaming-download `execute { }` block back to the outer
    // retry loop. Done = response consumed, body written. RetryAuth = 401 with refresh
    // unattempted; outer loop refreshes the token and retries once. Throttle = 429/503
    // with a backoff hint; outer loop sleeps and retries. Sealed because the three are
    // the exhaustive set of "didn't write the file, here's what to do next" outcomes.
    private sealed class DownloadOutcome {
        data object Done : DownloadOutcome()

        data object RetryAuth : DownloadOutcome()

        data class Throttle(
            val waitMs: Long,
            val statusCode: Int,
        ) : DownloadOutcome()
    }

    companion object {
        // UD-207 / UD-232: throttle handling budgets. Single-retry cap lifted to 300s
        // after the UD-712 live sync showed server-demanded Retry-After of up to 297s —
        // the old 120s cap was the kill-shot for 246/454 permanent failures. Cumulative
        // per-request budget widened to 900s (15 min) so a single call can ride out two
        // consecutive near-max waits without exhausting the sequence budget.
        private const val MAX_THROTTLE_ATTEMPTS = 5
        private const val MAX_SINGLE_BACKOFF_MS = 300_000L // 5 min per individual retry
        private const val MAX_TOTAL_THROTTLE_WAIT_MS = 900_000L // 15 min budget per request
        private const val DEFAULT_BACKOFF_START_MS = 2_000L

        // UD-309: budget for network flakes (Content-Length mismatch, I/O errors) on download.
        private const val MAX_FLAKE_ATTEMPTS = 3
    }

    suspend fun createSharingLink(
        path: String,
        type: String = "view",
        scope: String = "anonymous",
        expiryHours: Int = 24,
        password: String? = null,
    ): String? {
        val item = getItemByPath(path)
        val url = "$baseUrl/me/drive/items/${item.id}/createLink"
        val expiration =
            java.time.Instant
                .now()
                .plusSeconds(expiryHours.toLong() * 3600)
                .toString()
        val bodyObj =
            buildJsonObject {
                put("type", type)
                put("scope", scope)
                put("expirationDateTime", expiration)
                if (password != null) put("password", password)
            }
        val body = bodyObj.toString()

        val response = authenticatedRequest(url, HttpMethod("POST"), body)
        if (!response.status.isSuccess()) {
            log.warn("Failed to create sharing link: {}", response.bodyAsText())
            return null
        }

        return try {
            val linkResponse = json.decodeFromString<SharingLinkResponse>(response.bodyAsText())
            linkResponse.link?.webUrl
        } catch (e: Exception) {
            log.warn("Failed to parse sharing link response: {}", e.message)
            null
        }
    }

    suspend fun listPermissions(path: String): List<ShareInfo> {
        val item = getItemByPath(path)
        val url = "$baseUrl/me/drive/items/${item.id}/permissions"
        val response = authenticatedRequest(url)
        val body = response.bodyAsText()
        val collection = json.decodeFromString<PermissionCollectionResponse>(body)
        return collection.value
            .filter { it.link != null }
            .mapNotNull { perm ->
                val link = perm.link ?: return@mapNotNull null
                val id = perm.id ?: return@mapNotNull null
                ShareInfo(
                    id = id,
                    url = link.webUrl ?: "",
                    type = link.type ?: "unknown",
                    scope = link.scope ?: "unknown",
                    hasPassword = perm.hasPassword,
                    expiration = perm.expirationDateTime,
                )
            }
    }

    suspend fun deletePermission(
        path: String,
        permissionId: String,
    ): Boolean {
        val item = getItemByPath(path)
        val url = "$baseUrl/me/drive/items/${item.id}/permissions/$permissionId"
        val response =
            httpClient.request(url) {
                bearerAuth(tokenProvider(false))
                method = HttpMethod("DELETE")
            }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401): ${response.bodyAsText()}")
        }
        return response.status == HttpStatusCode.NoContent
    }

    suspend fun listSharedWithMe(): List<DriveItem> {
        val url = "$baseUrl/me/drive/sharedWithMe"
        val response = authenticatedRequest(url)
        val body = response.bodyAsText()
        val collection = json.decodeFromString<DriveItemCollectionResponse>(body)
        return collection.value
    }

    suspend fun createSubscription(
        notificationUrl: String,
        expirationDateTime: String,
    ): Subscription? {
        val url = "$baseUrl/subscriptions"
        val body =
            """
            {
                "changeType": "updated",
                "notificationUrl": "$notificationUrl",
                "resource": "/me/drive/root",
                "expirationDateTime": "$expirationDateTime"
            }
            """.trimIndent()

        return try {
            val response = authenticatedRequest(url, HttpMethod("POST"), body)
            if (!response.status.isSuccess()) {
                log.warn("Failed to create subscription: {}", response.bodyAsText())
                return null
            }
            val respBody = response.bodyAsText()
            json.decodeFromString<SubscriptionResponse>(respBody).toSubscription()
        } catch (e: Exception) {
            log.warn("Failed to create subscription: {}", e.message)
            null
        }
    }

    suspend fun renewSubscription(
        subscriptionId: String,
        expirationDateTime: String,
    ): Boolean {
        val url = "$baseUrl/subscriptions/$subscriptionId"
        val body = """{"expirationDateTime":"$expirationDateTime"}"""

        return try {
            val response = authenticatedRequest(url, HttpMethod("PATCH"), body)
            response.status.isSuccess()
        } catch (e: Exception) {
            log.warn("Failed to renew subscription: {}", e.message)
            false
        }
    }

    suspend fun deleteSubscription(subscriptionId: String): Boolean {
        val url = "$baseUrl/subscriptions/$subscriptionId"

        return try {
            val response =
                httpClient.request(url) {
                    bearerAuth(tokenProvider(false))
                    method = HttpMethod("DELETE")
                }
            response.status.isSuccess()
        } catch (e: Exception) {
            log.warn("Failed to delete subscription: {}", e.message)
            false
        }
    }

    override fun close() {
        httpClient.close()
    }
}

data class DeltaResult(
    val items: List<DriveItem>,
    val nextLink: String?,
    val deltaLink: String?,
)

class GraphApiException(
    message: String,
    val statusCode: Int = 0,
) : ProviderException(message)
