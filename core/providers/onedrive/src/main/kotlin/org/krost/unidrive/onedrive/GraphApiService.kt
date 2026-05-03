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
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.assertNotHtml
import org.krost.unidrive.http.readBoundedErrorBody
import org.krost.unidrive.http.truncateErrorBody
import org.krost.unidrive.onedrive.model.*
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class GraphApiService(
    private val config: OneDriveConfig,
    private val throttleBudget: HttpRetryBudget = HttpRetryBudget(maxConcurrency = 8),
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
            install(org.krost.unidrive.http.RequestId)
        }

    private val json = org.krost.unidrive.UnidriveJson
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
        val url = link ?: if (fromLatest) "$baseUrl/me/drive/root/delta?token=latest" else "$baseUrl/me/drive/root/delta"
        log.debug("Delta cursor: {}", link?.takeLast(40) ?: if (fromLatest) "(token=latest bootstrap)" else "(initial)")

        var attempt = 0
        val maxAttempts = 3
        while (true) {
            try {
                val response = authenticatedRequest(url)
                val body = response.bodyAsText()
                val parsed = json.decodeFromString<DriveItemCollectionResponse>(body)
                return DeltaResult(
                    items = parsed.value,
                    nextLink = parsed.nextLink,
                    deltaLink = parsed.deltaLink,
                )
            } catch (e: AuthenticationException) {
                throw e // token failures aren't retryable
            } catch (e: kotlinx.coroutines.CancellationException) {
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
        var flakeAttempts = 0
        var throttleAttempts = 0
        var totalThrottleWaitMs = 0L
        var authRefreshed = false
        while (true) {
            try {
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
                        assertNotHtml(response, contextMsg = "Download itemId=$itemId")
                        throttleBudget.recordSuccess()
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
                timeout {
                    requestTimeoutMillis = UploadTimeoutPolicy.computeRequestTimeoutMs(content.size.toLong())
                }
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
                    timeout {
                        requestTimeoutMillis = UploadTimeoutPolicy.computeRequestTimeoutMs(currentBytes.size.toLong())
                    }
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
        var refreshed = false
        var throttleAttempts = 0
        var totalThrottleWaitMs = 0L
        while (true) {
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
        var refreshed = false
        var throttleAttempts = 0
        var totalThrottleWaitMs = 0L
        while (true) {
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

       private sealed class DownloadOutcome {
        data object Done : DownloadOutcome()

        data object RetryAuth : DownloadOutcome()

        data class Throttle(
            val waitMs: Long,
            val statusCode: Int,
        ) : DownloadOutcome()
    }

    companion object {
        private const val MAX_THROTTLE_ATTEMPTS = 5
        private const val MAX_SINGLE_BACKOFF_MS = 300_000L // 5 min per individual retry
        private const val MAX_TOTAL_THROTTLE_WAIT_MS = 900_000L // 15 min budget per request
        private const val DEFAULT_BACKOFF_START_MS = 2_000L
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
