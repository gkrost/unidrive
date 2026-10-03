package org.krost.unidrive.internxt

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.http.InFlightDedup
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.assertNotHtml
import org.krost.unidrive.http.currentPriority
import org.krost.unidrive.http.streamingFileBody
import org.krost.unidrive.http.truncateErrorBody
import org.krost.unidrive.internxt.model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class InternxtApiService(
    private val config: InternxtConfig,
    private val credentialsProvider: suspend (forceRefresh: Boolean) -> InternxtCredentials,
    // Two budgets matching drive-desktop's audited `bottleneck` config:
    //   - Drive REST: maxConcurrency=2, minSpacing=500ms (storm widens to 1000ms).
    //   - Bridge upload: maxConcurrency=4, minSpacing=0 (concurrency-only).
    // Composition order on the Drive REST surface is
    // `retryOnTransient { withAuthRetry { budget.awaitSlot(); ... } }` — the
    // budget MUST stay the innermost wrapper so retries re-acquire a
    // per-attempt slot, and `withAuthRetry` sits between so a mid-call 401
    // replay reuses the current `retryOnTransient` attempt's delay budget
    // rather than restarting the 3-iteration ladder. Bridge calls use HTTP
    // Basic auth and do not 401 on JWT expiry, so they remain unwrapped.
    private val driveBudget: HttpRetryBudget =
        HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 500, stormSpacingMs = 1_000),
    private val bridgeBudget: HttpRetryBudget =
        HttpRetryBudget(maxConcurrency = 4, minSpacingMs = 0, stormSpacingMs = 0),
    // The socket (read-idle) timeout of an ordinary call (the default client's too), and the socket and request timeouts
    // of the account-wide listings (InternxtConfig.LISTING_*). Parameters so that loopback tests can run with small ones.
    private val socketTimeoutMs: Long = HttpDefaults.SOCKET_TIMEOUT_MS,
    private val listingSocketTimeoutMs: Long = InternxtConfig.LISTING_SOCKET_TIMEOUT_MS,
    private val listingRequestTimeoutMs: Long = InternxtConfig.LISTING_REQUEST_TIMEOUT_MS,
    // Tests hand in a client on a MockEngine; production builds the default one.
    private val httpClient: HttpClient = defaultHttpClient(socketTimeoutMs),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(InternxtApiService::class.java)

    companion object {
        private fun defaultHttpClient(socketTimeoutMs: Long): HttpClient =
            HttpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = socketTimeoutMs
                    requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
                }
                // UD-255: per-request correlation id + DEBUG req/response logging.
                install(org.krost.unidrive.http.RequestId)
            }

        private val TRANSIENT_STATUSES = setOf(429, 500, 502, 503, 504)

        private const val GET_MAX_ATTEMPTS = 3

        private const val GET_BACKOFF_BASE_MS = 2_000L

        // UD-335: capture `"retry_after": <seconds>` from Cloudflare /
        // Internxt JSON error bodies. Returns the integer seconds.
        private val RETRY_AFTER_REGEX = Regex(""""retry_after"\s*:\s*(\d+)""")

        internal fun listingQueryParams(
            updatedAt: String?,
            limit: Int,
            offset: Int,
            status: String = "ALL",
            sort: String = "uuid",
        ): Map<String, String> {
            val params =
                mutableMapOf(
                    "status" to status,
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "sort" to sort,
                    "order" to "ASC",
                )
            if (updatedAt != null) params["updatedAt"] = updatedAt
            return params
        }

        private const val OVH_PUT_MIN_THROUGHPUT_BPS: Long = 10L * 1024

        // Was the failure the engine's own socket timer rather than the peer closing the connection? The timer closes
        // the client's read channel when no byte has arrived for socketTimeoutMs. Plain HTTP reports that as a
        // SocketTimeoutException, but over TLS the very same close reads as an EOFException ("the server prematurely
        // closed the connection"), the text of a real close by the peer. Only the elapsed time tells them apart: a
        // failure of that shape after at least 90 % of the socket timeout was the timer, a quicker one the peer.
        internal fun isOwnTimeout(
            e: Throwable,
            elapsedMs: Long,
            socketTimeoutMs: Long,
        ): Boolean =
            elapsedMs >= socketTimeoutMs - socketTimeoutMs / 10 &&
                generateSequence(e) { it.cause }.take(8).any {
                    it is java.io.EOFException || it is java.net.SocketTimeoutException
                }

        // scheme://host/path of [url]. Never the query: on a presigned storage URL it carries the signature.
        internal fun withoutQuery(url: String): String = url.substringBefore('?').substringBefore('#')

        private fun seconds(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)

        internal fun ownTimeoutMessage(
            method: String,
            url: String,
            elapsedMs: Long,
            socketTimeoutMs: Long,
        ): String =
            "timed out after ${seconds(elapsedMs)} waiting for the response of $method ${withoutQuery(url)}; " +
                "this is the engine's own limit (socket timeout ${seconds(socketTimeoutMs)}), not a server close"
    }

    private val json = org.krost.unidrive.UnidriveJson
    private val baseUrl = InternxtConfig.API_BASE_URL

    // UD-205: read-heavy provider operations are a known dedup target.
    // Concurrent sync-engine coroutines descending overlapping subtrees can
    // legitimately ask for the same metadata at the same time; the dedup
    // collapses those into one HTTP round-trip without changing call-site
    // semantics. One InFlightDedup per call site (per `InFlightDedup` KDoc
    // key-design guidance), mirroring the original folderContentsDedup wiring.
    internal val folderContentsDedup = InFlightDedup<String, FolderContentResponse>()
    internal val fileMetaDedup = InFlightDedup<String, InternxtFile>()
    internal val folderMetaDedup = InFlightDedup<String, InternxtFolder>()
    internal val listFilesDedup = InFlightDedup<String, List<InternxtFile>>()
    internal val listFoldersDedup = InFlightDedup<String, List<InternxtFolder>>()

    suspend fun getFolderContents(folderUuid: String): FolderContentResponse =
        folderContentsDedup.load(folderUuid, currentPriority()) {
            val body = authenticatedGet("$baseUrl/folders/content/$folderUuid")
            json.decodeFromString<FolderContentResponse>(body)
        }

    suspend fun listFiles(
        updatedAt: String? = null,
        limit: Int = InternxtConfig.LISTING_PAGE_SIZE,
        offset: Int = 0,
        status: String = "ALL",
        sort: String = "uuid",
    ): List<InternxtFile> =
        listFilesDedup.load("$updatedAt|$limit|$offset|$status|$sort", currentPriority()) {
            val body = authenticatedGet("$baseUrl/files", listingQueryParams(updatedAt, limit, offset, status, sort), heavy = true)
            json.decodeFromString<List<InternxtFile>>(body)
        }

    suspend fun listFolders(
        updatedAt: String? = null,
        limit: Int = InternxtConfig.LISTING_PAGE_SIZE,
        offset: Int = 0,
        status: String = "ALL",
        sort: String = "uuid",
    ): List<InternxtFolder> =
        listFoldersDedup.load("$updatedAt|$limit|$offset|$status|$sort", currentPriority()) {
            val body = authenticatedGet("$baseUrl/folders", listingQueryParams(updatedAt, limit, offset, status, sort), heavy = true)
            json.decodeFromString<List<InternxtFolder>>(body)
        }

    suspend fun getFileMeta(uuid: String): InternxtFile =
        fileMetaDedup.load(uuid, currentPriority()) {
            val body = authenticatedGet("$baseUrl/files/$uuid/meta")
            json.decodeFromString<InternxtFile>(body)
        }

    suspend fun getFolderMeta(uuid: String): InternxtFolder =
        folderMetaDedup.load(uuid, currentPriority()) {
            val body = authenticatedGet("$baseUrl/folders/$uuid/meta")
            json.decodeFromString<InternxtFolder>(body)
        }

    suspend fun createFile(
        bucket: String,
        folderUuid: String,
        plainName: String,
        encryptedName: String,
        size: Long,
        type: String?,
        fileId: String? = null,
        // #486: CreateFileDto takes an optional modificationTime; without it the server stamps the upload time.
        modificationTime: java.time.Instant? = null,
    ): InternxtFile =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("bucket", kotlinx.serialization.json.JsonPrimitive(bucket))
                            put("folderUuid", kotlinx.serialization.json.JsonPrimitive(folderUuid))
                            put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                            put("name", kotlinx.serialization.json.JsonPrimitive(encryptedName))
                            put("size", kotlinx.serialization.json.JsonPrimitive(size))
                            put("encryptVersion", kotlinx.serialization.json.JsonPrimitive("03-aes"))
                            if (type != null) put("type", kotlinx.serialization.json.JsonPrimitive(type))
                            if (fileId != null) put("fileId", kotlinx.serialization.json.JsonPrimitive(fileId))
                            if (modificationTime != null) {
                                put("modificationTime", kotlinx.serialization.json.JsonPrimitive(modificationTime.toString()))
                            }
                        }
                    val response =
                        httpClient.post("$baseUrl/files") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFile>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    /**
     * UD-366: replace an existing file's content in place via `PUT /files/{uuid}`.
     *
     * Spec: `ReplaceFileDto` accepts only `fileId` (new bridge bucket-entry id), `size`
     * (required), and optional `modificationTime`. The endpoint preserves the existing
     * file's bucket, encrypted name, parent folder, and encryptVersion — there is nothing
     * to re-derive on the client side. Returns the updated `FileDto` (same `uuid`,
     * swapped `fileId`). #485: `fileId` is required when `size > 0` and must be absent when
     * `size == 0` (an empty file has no bucket entry); pass null for an empty replacement.
     */
    suspend fun replaceFile(
        uuid: String,
        size: Long,
        fileId: String?,
        modificationTime: java.time.Instant? = null,
    ): InternxtFile =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            if (fileId != null) put("fileId", kotlinx.serialization.json.JsonPrimitive(fileId))
                            put("size", kotlinx.serialization.json.JsonPrimitive(size))
                            if (modificationTime != null) {
                                put("modificationTime", kotlinx.serialization.json.JsonPrimitive(modificationTime.toString()))
                            }
                        }
                    val response =
                        httpClient.put("$baseUrl/files/$uuid") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFile>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    /**
     * UD-368: bulk folder creation under a single parent via the polymorphic `POST /folders`.
     *
     * Spec: `CreateFolderDto` accepts either `{plainName}` (single) OR
     * `{folders: [{plainName}], parentFolderUuid}` (bulk, 1–5 items per call). The bulk
     * response is undocumented in the swagger 200 schema (which lists only single-folder
     * `FolderDto`) — we parse as `ResultFoldersDto = {result: [FolderDto]}` based on the
     * sibling endpoint convention; if Internxt ships an integration test or the live API
     * returns a different shape, this will fail loudly at deserialise time.
     *
     * The 409 conflict response is bulk-specific: `CreateBulkFoldersConflictResponseDto` =
     * `{message, existentFolders: [string]}` (names, not UUIDs). Caller is responsible for
     * the 409-recovery dance — there is no per-item partial-success path on the wire.
     *
     * Note: the bulk DTO documents `plainName` only per item; the existing single-folder
     * `createFolder` also sends `name` (encrypted) as an undocumented field that the server
     * accepts. We mirror that here for parity, even though the spec is silent.
     */
    suspend fun createFoldersBatch(
        parentUuid: String,
        items: List<Pair<String, String>>,
    ): List<InternxtFolder> {
        require(items.isNotEmpty()) { "createFoldersBatch requires at least one item" }
        require(items.size <= 5) { "POST /folders bulk form is server-capped at 5; got ${items.size}" }
        return retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("parentFolderUuid", kotlinx.serialization.json.JsonPrimitive(parentUuid))
                            put(
                                "folders",
                                kotlinx.serialization.json.buildJsonArray {
                                    items.forEach { (plainName, encryptedName) ->
                                        add(
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                                                put("name", kotlinx.serialization.json.JsonPrimitive(encryptedName))
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    val response =
                        httpClient.post("$baseUrl/folders") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    // Try ResultFoldersDto shape `{result: [FolderDto]}`; fall back to bare array.
                    val text = response.bodyAsText()
                    val rootElement = json.parseToJsonElement(text)
                    val arr =
                        when {
                            rootElement is kotlinx.serialization.json.JsonObject && "result" in rootElement ->
                                rootElement["result"]!!
                            rootElement is kotlinx.serialization.json.JsonObject && "folders" in rootElement ->
                                rootElement["folders"]!!
                            rootElement is kotlinx.serialization.json.JsonArray ->
                                rootElement
                            else ->
                                throw InternxtApiException(
                                    "createFoldersBatch: unexpected response shape (no result/folders array): ${text.take(200)}",
                                    statusCode = 0,
                                )
                        }
                    json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(InternxtFolder.serializer()),
                        arr,
                    )
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }
    }

    suspend fun createFolder(
        parentUuid: String,
        plainName: String,
        encryptedName: String,
    ): InternxtFolder =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("parentFolderUuid", kotlinx.serialization.json.JsonPrimitive(parentUuid))
                            put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                            put("name", kotlinx.serialization.json.JsonPrimitive(encryptedName))
                        }
                    val response =
                        httpClient.post("$baseUrl/folders") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFolder>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    /**
     * UD-369: rename a file in place via `PUT /files/{uuid}/meta`.
     *
     * Spec: `UpdateFileMetaDto` requires `{plainName, type}`. Notably, the endpoint does NOT
     * accept the `name` (encrypted) field — only the plaintext name + type are updated. The
     * encrypted-name field stays stale after a rename. Unidrive uses `plainName` for path
     * resolution everywhere ([InternxtProvider.kt] `fileToCloudItem`, `buildFolderPath`,
     * `resolveFolder`), so the staleness is invisible to the sync engine — but document it
     * because it's a sharp edge for any future code that relies on the encrypted name.
     */
    suspend fun renameFile(
        uuid: String,
        plainName: String,
        type: String?,
    ): InternxtFile =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                            // Spec says type is required; empty string is valid for extensionless files.
                            put("type", kotlinx.serialization.json.JsonPrimitive(type ?: ""))
                        }
                    val response =
                        httpClient.put("$baseUrl/files/$uuid/meta") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFile>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    /**
     * UD-369: rename a folder in place via `PUT /folders/{uuid}/meta`.
     *
     * Spec: `UpdateFolderMetaDto` requires `{plainName}` only — same encrypted-name caveat
     * as [renameFile].
     */
    suspend fun renameFolder(
        uuid: String,
        plainName: String,
    ): InternxtFolder =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                        }
                    val response =
                        httpClient.put("$baseUrl/folders/$uuid/meta") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFolder>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    suspend fun moveFile(
        uuid: String,
        destinationFolderUuid: String,
    ): InternxtFile =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("destinationFolder", kotlinx.serialization.json.JsonPrimitive(destinationFolderUuid))
                        }
                    val response =
                        httpClient.patch("$baseUrl/files/$uuid") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFile>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    suspend fun moveFolder(
        uuid: String,
        destinationFolderUuid: String,
    ): InternxtFolder =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("destinationFolder", kotlinx.serialization.json.JsonPrimitive(destinationFolderUuid))
                        }
                    val response =
                        httpClient.patch("$baseUrl/folders/$uuid") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFolder>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    suspend fun deleteFile(uuid: String) {
        // 404 is treated as already-gone (idempotent delete) — short-circuits
        // before checkResponse so retryOnTransient never sees it as transient.
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val response =
                        httpClient.delete("$baseUrl/files/$uuid") {
                            applyAuth(creds)
                        }
                    if (response.status != HttpStatusCode.NotFound) checkResponse(response)
                    driveBudget.recordSuccess()
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }
    }

    /**
     * UD-367: move items to Internxt's recycle bin via `POST /storage/trash/add`.
     *
     * Spec: `MoveItemsToTrashDto` accepts `items: [{uuid, type: "file"|"folder"}]` with
     * a server-side cap of 50 items per call. Replaces the permanent `DELETE /files/{uuid}`
     * + `DELETE /folders` collection-form path for routine sync-driven deletes — the user
     * recovers via Internxt's web UI within the configured retention window.
     *
     * The permanent `deleteFile` / `deleteFolder` primitives remain available for explicit
     * purge actions (e.g. a future `unidrive trash purge --remote`).
     *
     * @param items list of (uuid, type) pairs where type is "file" or "folder". Caller is
     *   responsible for chunking >50 items into multiple calls — this method does not split.
     */
    suspend fun trashItems(items: List<Pair<String, String>>) {
        require(items.isNotEmpty()) { "trashItems requires at least one item" }
        require(items.size <= 50) { "trashItems is server-capped at 50; got ${items.size}" }
        require(items.all { it.second == "file" || it.second == "folder" }) {
            "type must be 'file' or 'folder'; got ${items.map { it.second }.distinct()}"
        }
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put(
                                "items",
                                kotlinx.serialization.json.buildJsonArray {
                                    items.forEach { (uuid, type) ->
                                        add(
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("uuid", kotlinx.serialization.json.JsonPrimitive(uuid))
                                                put("type", kotlinx.serialization.json.JsonPrimitive(type))
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    val response =
                        httpClient.post("$baseUrl/storage/trash/add") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    // 200 OK — items moved. 400 means at least one uuid was invalid; let it surface.
                    checkResponse(response)
                    driveBudget.recordSuccess()
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }
    }

    suspend fun deleteFolder(uuid: String) {
        // 404 is treated as already-gone (idempotent delete) — short-circuits
        // before checkResponse so retryOnTransient never sees it as transient.
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put(
                                "items",
                                kotlinx.serialization.json.buildJsonArray {
                                    add(
                                        kotlinx.serialization.json.buildJsonObject {
                                            put("uuid", kotlinx.serialization.json.JsonPrimitive(uuid))
                                            put("type", kotlinx.serialization.json.JsonPrimitive("folder"))
                                        },
                                    )
                                },
                            )
                        }
                    val response =
                        httpClient.delete("$baseUrl/folders") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    if (response.status != HttpStatusCode.NotFound) checkResponse(response)
                    driveBudget.recordSuccess()
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }
    }

    /** Streams encrypted bytes from [downloadUrl] through [cipher] directly to [destination]. Returns byte count. */
    suspend fun downloadFileStreaming(
        downloadUrl: String,
        cipher: javax.crypto.Cipher,
        destination: Path,
    ): Long {
        bridgeBudget.awaitSlot()
        try {
            val written =
                httpClient.prepareGet(downloadUrl).execute { response ->
                    if (!response.status.isSuccess()) {
                        throw InternxtApiException("Download failed: ${response.status}", response.status.value)
                    }

                    assertNotHtml(response, contextMsg = "Download (encrypted) -> ${destination.fileName}")
                    val channel: ByteReadChannel = response.body()
                    var written = 0L
                    withContext(Dispatchers.IO) {
                        Files.createDirectories(destination.parent)
                        val tmpPath = destination.parent.resolve("${destination.fileName}.unidrive-tmp")
                        try {
                            Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (!channel.isClosedForRead) {
                                    val n = channel.readAvailable(buf)
                                    if (n <= 0) break
                                    val decrypted = cipher.update(buf, 0, n)
                                    if (decrypted != null) {
                                        out.write(decrypted)
                                        written += decrypted.size
                                    }
                                }
                                val finalBlock = cipher.doFinal()
                                if (finalBlock != null && finalBlock.isNotEmpty()) {
                                    out.write(finalBlock)
                                    written += finalBlock.size
                                }
                            }
                            Files.move(tmpPath, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } catch (e: Exception) {
                            Files.deleteIfExists(tmpPath)
                            throw e
                        }
                    }
                    written
                }
            bridgeBudget.recordSuccess()
            return written
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        }
    }

    private val bridgeUrl = "https://api.internxt.com"

    private suspend fun bridgeGet(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        bridgeBudget.awaitSlot()
        try {
            // Bridge auth is HTTP Basic — JWT refresh wouldn't change bridgeUser/bridgeUserId,
            // so this stays out of withAuthRetry. Pass forceRefresh = false unconditionally.
            val creds = credentialsProvider(false)
            val authHeader = InternxtCrypto.bridgeAuthHeader(creds.bridgeUser, creds.bridgeUserId)
            val response =
                httpClient.get("$bridgeUrl$path") {
                    header("Authorization", authHeader)
                    header("x-api-version", "2")
                    accept(ContentType.Application.Json)
                    params.forEach { (k, v) -> parameter(k, v) }
                }
            checkResponse(response)
            bridgeBudget.recordSuccess()
            return response.bodyAsText()
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        }
    }

    private suspend fun bridgePost(
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        bridgeBudget.awaitSlot()
        try {
            // Bridge auth is HTTP Basic — JWT refresh wouldn't change bridgeUser/bridgeUserId,
            // so this stays out of withAuthRetry. Pass forceRefresh = false unconditionally.
            val creds = credentialsProvider(false)
            val authHeader = InternxtCrypto.bridgeAuthHeader(creds.bridgeUser, creds.bridgeUserId)
            val response =
                httpClient.post("$bridgeUrl$path") {
                    header("Authorization", authHeader)
                    header("x-api-version", "2")
                    contentType(ContentType.Application.Json)
                    headers.forEach { (k, v) -> header(k, v) }
                    setBody(body)
                }
            checkResponse(response)
            bridgeBudget.recordSuccess()
            return response.bodyAsText()
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        }
    }

    suspend fun getBridgeFileInfo(
        bucket: String,
        fileId: String,
    ): BridgeFileInfo {
        val body = bridgeGet("/buckets/$bucket/files/$fileId/info")
        return json.decodeFromString<BridgeFileInfo>(body)
    }

    suspend fun getBridgeMirrors(
        bucket: String,
        fileId: String,
    ): List<Mirror> {
        val body = bridgeGet("/buckets/$bucket/files/$fileId", mapOf("limit" to "3"))
        return json.decodeFromString<List<Mirror>>(body)
    }

    suspend fun startUpload(
        bucket: String,
        fileSize: Long,
    ): StartUploadResponse {
        val requestBody = """{"uploads":[{"index":0,"size":$fileSize}]}"""
        val body = bridgePost("/v2/buckets/$bucket/files/start?multiparts=1", requestBody)
        return json.decodeFromString<StartUploadResponse>(body)
    }

    suspend fun finishUpload(
        bucket: String,
        indexHex: String,
        shardHash: String,
        shardUuid: String,
        @Suppress("UNUSED_PARAMETER") idempotencyKey: String? = null,
    ): BucketEntry {
        val request =
            FinishUploadRequest(
                index = indexHex,
                shards = listOf(ShardMeta(hash = shardHash, uuid = shardUuid)),
            )
        // TODO: wire idempotencyKey to bridgePost headers once vendor ships Idempotency-Key support
        val body =
            bridgePost(
                "/v2/buckets/$bucket/files/finish",
                json.encodeToString(FinishUploadRequest.serializer(), request),
            )
        return json.decodeFromString<BucketEntry>(body)
    }

    suspend fun putEncryptedShard(
        url: String,
        data: ByteArray,
    ) {
        // OVH PUT to a presigned URL is idempotent at the S3 layer — same key,
        // same body overwrites cleanly — so retryOnTransient is safe here.
        // The streaming variant (putEncryptedShardFromFile) takes its retry at
        // the provider layer instead because that wrap also needs to keep
        // indexBytes / iv / fileKey stable across attempts (see Design constraint
        // in BACKLOG.md history).
        retryOnTransient {
            bridgeBudget.awaitSlot()
            try {
                val response =
                    httpClient.put(url) {
                        // UD-337 + UD-353: size-adaptive request timeout against
                        // OVH (Internxt's shard backend) with a pessimistic
                        // 10 KiB/s floor — see OVH_PUT_MIN_THROUGHPUT_BPS.
                        timeout {
                            requestTimeoutMillis =
                                UploadTimeoutPolicy.computeRequestTimeoutMs(
                                    fileSize = data.size.toLong(),
                                    minThroughputBytesPerSecond = OVH_PUT_MIN_THROUGHPUT_BPS,
                                )
                        }
                        header("Content-Type", "application/octet-stream")
                        setBody(data)
                    }
                if (!response.status.isSuccess()) {
                    throw InternxtApiException("Shard upload failed: ${response.status}", response.status.value)
                }
                bridgeBudget.recordSuccess()
            } catch (e: InternxtApiException) {
                if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
                throw e
            }
        }
    }

    suspend fun putEncryptedShardFromFile(
        url: String,
        file: java.nio.file.Path,
        size: Long,
    ) {
        bridgeBudget.awaitSlot()
        try {
            val response =
                httpClient.put(url) {
                    timeout {
                        requestTimeoutMillis =
                            UploadTimeoutPolicy.computeRequestTimeoutMs(
                                fileSize = size,
                                minThroughputBytesPerSecond = OVH_PUT_MIN_THROUGHPUT_BPS,
                            )
                    }
                    header("Content-Type", "application/octet-stream")
                    setBody(streamingFileBody(file, size))
                }
            if (!response.status.isSuccess()) {
                throw InternxtApiException("Shard upload failed: ${response.status}", response.status.value)
            }
            bridgeBudget.recordSuccess()
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        }
    }

    suspend fun getQuota(): QuotaInfo {
        val usage = authenticatedGet("$baseUrl/users/usage")
        val limit = authenticatedGet("$baseUrl/users/limit")
        val usedBytes = json.decodeFromString<UsageResponse>(usage).drive
        val maxBytes = json.decodeFromString<LimitResponse>(limit).maxSpaceBytes
        return QuotaInfo(total = maxBytes, used = usedBytes, remaining = maxBytes - usedBytes)
    }

    /**
     * UD-364: GET /files/limits — per-tier file size cap.
     *
     * Source of truth: drive-server-wip `src/modules/file/dto/get-file-limits.dto.ts`
     * shape: `{ versioning: VersioningLimitsDto, maxUploadFileSize: number | null }`.
     * Only `maxUploadFileSize` is consumed; versioning fields are ignored
     * (kotlinx-serialization's `ignoreUnknownKeys` via [UnidriveJson] handles them).
     * `null` from the server means unlimited / not configured — surfaces as
     * `FileLimitsResponse.maxUploadFileSize = null`, which the provider maps to
     * `maxFileSizeBytes() = null` (i.e. no cap).
     */
    suspend fun getFileLimits(): FileLimitsResponse {
        val body = authenticatedGet("$baseUrl/files/limits")
        return json.decodeFromString<FileLimitsResponse>(body)
    }

    // A Drive REST GET with a transient ladder: GET_MAX_ATTEMPTS attempts, a server hint (Retry-After header or JSON
    // retry_after) or 2 s, then 4 s, between them. [heavy] marks an account-wide listing, which runs with the long
    // listing timeouts instead of the default ones; every GET gets its socket timeout set here, so that the timeout
    // the failure is judged by is the one that was in force.
    //
    // A failure that looks like the connection being closed before the answer is first told apart from the engine's own
    // socket timer (isOwnTimeout). The timer is not retried: the same request runs into the same timer, at the price of
    // a whole timeout per attempt. It ends as the synthetic 503 the callers map (so the walk fallback and the folder
    // skip still work), flagged InternxtApiException.timedOutLocally. A real close by the peer is retried like a 5xx and
    // ends as the same 503 once the ladder is spent.
    private suspend fun authenticatedGet(
        url: String,
        params: Map<String, String> = emptyMap(),
        heavy: Boolean = false,
    ): String {
        val socketMs = if (heavy) listingSocketTimeoutMs else socketTimeoutMs
        val requestMs = if (heavy) listingRequestTimeoutMs else null
        var lastException: InternxtApiException? = null
        // Budget acquisition is per-attempt (inside the retry loop) so a retrying
        // call doesn't hold a slot through its full backoff and starve other callers.
        // Composition order is `loop { withAuthRetry { budget.awaitSlot(); ... } }`:
        // the auth-replay sits inside one transient-retry iteration so a mid-call
        // 401 doesn't restart the full delay ladder — it consumes
        // only the current attempt's slot.
        for (attempt in 1..GET_MAX_ATTEMPTS) {
            // How long the HTTP exchange of this attempt ran before it failed with an IOException; null while it has not.
            var failedAfterMs: Long? = null
            try {
                return withAuthRetry { creds ->
                    driveBudget.awaitSlot()
                    val startedNs = System.nanoTime()
                    try {
                        val response =
                            httpClient.get(url) {
                                applyAuth(creds)
                                params.forEach { (k, v) -> parameter(k, v) }
                                timeout {
                                    socketTimeoutMillis = socketMs
                                    if (requestMs != null) requestTimeoutMillis = requestMs
                                }
                            }
                        checkResponse(response)
                        driveBudget.recordSuccess()
                        return@withAuthRetry response.bodyAsText()
                    } catch (e: InternxtApiException) {
                        if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                        throw e
                    } catch (e: java.io.IOException) {
                        failedAfterMs = (System.nanoTime() - startedNs) / 1_000_000
                        throw e
                    }
                }
            } catch (e: InternxtApiException) {
                if (e.statusCode !in TRANSIENT_STATUSES) throw e
                lastException = e
                if (attempt < GET_MAX_ATTEMPTS) kotlinx.coroutines.delay(retryDelayMs(e, attempt, GET_BACKOFF_BASE_MS))
            } catch (e: java.io.IOException) {
                val elapsedMs = failedAfterMs
                if (elapsedMs != null && isOwnTimeout(e, elapsedMs, socketMs)) {
                    log.warn(
                        "GET {} timed out after {} ms: the engine's own timer fired (socket timeout {} ms{}), " +
                            "not a server close; not retried with the same parameters",
                        withoutQuery(url),
                        elapsedMs,
                        socketMs,
                        requestMs?.let { ", request timeout $it ms" } ?: "",
                    )
                    throw InternxtApiException(
                        ownTimeoutMessage("GET", url, elapsedMs, socketMs),
                        503,
                        cause = e,
                        timedOutLocally = true,
                    )
                }
                // A network error without a status. The canonical retry matrix (see HttpRetryBudget) retries it
                // like a 5xx. Ktor reports a connection that is closed before any response byte as a
                // ClosedReadChannelException: an IOException that only WRAPS the EOFException. Catching the
                // EOFException alone never saw that commonest case: the call gave up at once as "Connection error"
                // (status 0), and neither the retry nor the /files -> folder walk fallback (500/503 only) ran.
                driveBudget.recordIoRetry()
                log.warn(
                    "GET {} failed after {} ms (socket timeout {} ms), attempt {}/{}: {}",
                    withoutQuery(url),
                    elapsedMs ?: "?",
                    socketMs,
                    attempt,
                    GET_MAX_ATTEMPTS,
                    e.message?.substringBefore(" [url=") ?: e.javaClass.simpleName,
                )
                lastException =
                    if (e.closedBeforeResponse()) {
                        InternxtApiException("Server closed connection for GET $url: ${e.message}", 503, cause = e)
                    } else {
                        InternxtApiException("Connection error for GET $url: ${e.message}", 0, cause = e)
                    }
                if (attempt < GET_MAX_ATTEMPTS) kotlinx.coroutines.delay(GET_BACKOFF_BASE_MS shl (attempt - 1))
            }
        }
        throw lastException!!
    }

    // The server closed the connection before it answered: the EOFException, or what Ktor wraps around it.
    private fun Throwable.closedBeforeResponse(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is java.io.EOFException }

    private fun HttpRequestBuilder.applyAuth(creds: InternxtCredentials) {
        bearerAuth(creds.jwt)
        applyInternxtHeaders(config)
    }

    // UD-203: pull the Internxt server-side request id off a response.
    // Header name confirmed from the upstream SDK
    // (`AxiosResponseError.xRequestId` — extracted at error-normalization
    // time). Returns null if the header isn't present (e.g. synthetic
    // MockEngine responses, pre-flight failures).
    private fun extractRequestId(response: HttpResponse): String? = response.headers["x-request-id"]

    private suspend fun checkResponse(response: HttpResponse) {
        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException(
                // UD-334: wrap the raw body in truncateErrorBody so HTML 401 pages
                // (e.g. a Cloudflare-fronted Internxt error) don't dump 60 lines
                // of inline CSS + branding into the exception message. JSON-prefix
                // bodies (`{...}`) pass through untouched, so the existing
                // `parseRetryAfter(e.message)` path remains intact.
                "Authentication failed (401): ${truncateErrorBody(response.bodyAsText())}",
                requestId = extractRequestId(response),
            )
        }
        if (!response.status.isSuccess()) {
            throw InternxtApiException(
                "API error: ${response.status} - ${truncateErrorBody(response.bodyAsText())}",
                statusCode = response.status.value,
                requestId = extractRequestId(response),
                retryAfterMs = parseRetryAfterHeader(response.headers["Retry-After"]),
            )
        }
    }


    // Single-shot 401 → refresh-and-replay. Mirrors OneDrive's
    // `GraphApiService.authenticatedRequest` ladder: fetch creds, run the
    // body, and if it surfaces an [AuthenticationException] (401 mapped by
    // [checkResponse]) and we haven't refreshed yet, ask the
    // `credentialsProvider` for a forced refresh and retry once. A second
    // consecutive 401 propagates — it indicates either a refresh that
    // silently failed or a new token the server also rejects, both of which
    // are auth-flow bugs the user must see.
    //
    // Concurrent forced-refresh callers coalesce inside
    // [AuthService.refreshToken]'s [RefreshableTokenLatch], so the second
    // and Nth replays cost one shared HTTP refresh, not N.
    //
    // Bridge calls (HTTP Basic auth) MUST NOT be wrapped: a 401 from the
    // Bridge surface means the credentials themselves are wrong, not that
    // the JWT expired, and a JWT refresh wouldn't help.
    private suspend fun <T> withAuthRetry(body: suspend (InternxtCredentials) -> T): T {
        var refreshed = false
        while (true) {
            val creds = credentialsProvider(refreshed)
            try {
                return body(creds)
            } catch (e: AuthenticationException) {
                if (!refreshed) {
                    log.info("Got 401 on Drive REST call — forcing JWT refresh and retrying once")
                    refreshed = true
                    continue
                }
                throw e
            }
        }
    }

    internal suspend fun <T> retryOnTransient(
        maxAttempts: Int = 3,
        op: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return op()
            } catch (e: InternxtApiException) {
                if (e.statusCode !in TRANSIENT_STATUSES) throw e
                attempt++
                if (attempt >= maxAttempts) throw e
                val cappedMs = retryDelayMs(e, attempt, 1_000L)
                log.warn(
                    "Internxt {} on attempt {}/{}, sleeping {}ms before retry",
                    e.statusCode,
                    attempt,
                    maxAttempts,
                    cappedMs,
                )
                kotlinx.coroutines.delay(cappedMs)
            }
        }
    }


    // How long to wait before attempt number [attempt] + 1. Precedence: Retry-After HTTP header (captured at
    // checkResponse time, see InternxtApiException.retryAfterMs) > JSON-body `retry_after` hint (parsed back out of the
    // exception message) > exponential backoff from [baseMs]. Header wins per RFC 7231 §7.1.3, which is the canonical
    // place for the server to put this signal. Capped at 500 ms .. 60 s so a long hint cannot make the engine look hung.
    private fun retryDelayMs(
        e: InternxtApiException,
        attempt: Int,
        baseMs: Long,
    ): Long = (e.retryAfterMs ?: parseRetryAfter(e.message) ?: (baseMs shl (attempt - 1))).coerceIn(500L, 60_000L)

    internal fun parseRetryAfter(message: String?): Long? {
        if (message == null) return null
        val match = RETRY_AFTER_REGEX.find(message) ?: return null
        return match.groupValues[1].toLongOrNull()?.times(1000)
    }

    // Parse the RFC 7231 §7.1.3 `Retry-After` HTTP header. Accepts either
    // a non-negative decimal integer of delta-seconds or an HTTP-date
    // (IMF-fixdate / RFC 1123 form — the only one current servers emit;
    // the obsolete RFC 850 + asctime forms aren't observed in practice
    // and Internxt's gateway never returns them). Returns the wait in
    // milliseconds relative to "now", or null if the header is absent,
    // malformed, or the date is in the past.
    internal fun parseRetryAfterHeader(headerValue: String?): Long? {
        val raw = headerValue?.trim()?.takeUnless { it.isEmpty() } ?: return null
        // Delta-seconds path: a bare integer.
        raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return seconds * 1000
        }
        // HTTP-date path: parse as RFC 1123 and diff against now. Negative
        // diffs (date already in the past) return null so the caller falls
        // through to the next precedence step.
        return try {
            val target =
                java.time.ZonedDateTime
                    .parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
            val deltaMs = target.toEpochMilli() - System.currentTimeMillis()
            deltaMs.takeIf { it > 0 }
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }



    override fun close() {
        httpClient.close()
    }
}

@Serializable
data class UsageResponse(
    val drive: Long = 0,
)

@Serializable
data class LimitResponse(
    val maxSpaceBytes: Long = 0,
)

/**
 * UD-364: response shape of `GET /files/limits` (drive-server-wip
 * `GetFileLimitsDto`). `maxUploadFileSize` is a per-tier byte cap, or null
 * when the server reports no cap. Other fields (`versioning`) are dropped
 * via `ignoreUnknownKeys`.
 */
@Serializable
data class FileLimitsResponse(
    val maxUploadFileSize: Long? = null,
)

class InternxtApiException(
    message: String,
    val statusCode: Int = 0,
    requestId: String? = null,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
    // True for the synthetic 503 of a call that ended on the engine's own socket timer: the server neither answered
    // nor closed the connection, and the same request would run into the same timer again.
    val timedOutLocally: Boolean = false,
) : ProviderException(message, cause = cause, requestId = requestId)
