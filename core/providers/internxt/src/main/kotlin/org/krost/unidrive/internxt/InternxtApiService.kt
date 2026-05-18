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
import org.krost.unidrive.http.InFlightDedup
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.assertNotHtml
import org.krost.unidrive.http.streamingFileBody
import org.krost.unidrive.http.truncateErrorBody
import org.krost.unidrive.internxt.model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class InternxtApiService(
    private val config: InternxtConfig,
    private val credentialsProvider: suspend () -> InternxtCredentials,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(InternxtApiService::class.java)

    companion object {
        private val TRANSIENT_STATUSES = setOf(429, 500, 502, 503, 504)

        // UD-335: capture `"retry_after": <seconds>` from Cloudflare /
        // Internxt JSON error bodies. Returns the integer seconds.
        private val RETRY_AFTER_REGEX = Regex(""""retry_after"\s*:\s*(\d+)""")

        internal fun listingQueryParams(
            updatedAt: String?,
            limit: Int,
            offset: Int,
            status: String = "ALL",
        ): Map<String, String> {
            val params =
                mutableMapOf(
                    "status" to status,
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "sort" to "uuid",
                    "order" to "ASC",
                )
            if (updatedAt != null) params["updatedAt"] = updatedAt
            return params
        }

        private const val OVH_PUT_MIN_THROUGHPUT_BPS: Long = 10L * 1024
    }

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
    internal val listFilesDedup = InFlightDedup<String, List<InternxtFile>>()
    internal val listFoldersDedup = InFlightDedup<String, List<InternxtFolder>>()

    suspend fun getFolderContents(folderUuid: String): FolderContentResponse =
        folderContentsDedup.load(folderUuid) {
            val body = authenticatedGet("$baseUrl/folders/content/$folderUuid")
            json.decodeFromString<FolderContentResponse>(body)
        }

    suspend fun listFiles(
        updatedAt: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        status: String = "ALL",
    ): List<InternxtFile> =
        listFilesDedup.load("$updatedAt|$limit|$offset|$status") {
            val body = authenticatedGet("$baseUrl/files", listingQueryParams(updatedAt, limit, offset, status))
            json.decodeFromString<List<InternxtFile>>(body)
        }

    suspend fun listFolders(
        updatedAt: String? = null,
        limit: Int = 50,
        offset: Int = 0,
        status: String = "ALL",
    ): List<InternxtFolder> =
        listFoldersDedup.load("$updatedAt|$limit|$offset|$status") {
            val body = authenticatedGet("$baseUrl/folders", listingQueryParams(updatedAt, limit, offset, status))
            json.decodeFromString<List<InternxtFolder>>(body)
        }

    suspend fun getFileMeta(uuid: String): InternxtFile =
        fileMetaDedup.load(uuid) {
            val body = authenticatedGet("$baseUrl/files/$uuid/meta")
            json.decodeFromString<InternxtFile>(body)
        }

    suspend fun createFile(
        bucket: String,
        folderUuid: String,
        plainName: String,
        encryptedName: String,
        size: Long,
        type: String?,
        fileId: String? = null,
    ): InternxtFile =
        retryOnTransient {
            val creds = credentialsProvider()
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
                }
            val response =
                httpClient.post("$baseUrl/files") {
                    applyAuth(creds)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                }
            checkResponse(response)
            json.decodeFromString<InternxtFile>(response.bodyAsText())
        }

    /**
     * UD-366: replace an existing file's content in place via `PUT /files/{uuid}`.
     *
     * Spec: `ReplaceFileDto` accepts only `fileId` (new bridge bucket-entry id), `size`
     * (required), and optional `modificationTime`. The endpoint preserves the existing
     * file's bucket, encrypted name, parent folder, and encryptVersion — there is nothing
     * to re-derive on the client side. Returns the updated `FileDto` (same `uuid`,
     * swapped `fileId`).
     */
    suspend fun replaceFile(
        uuid: String,
        size: Long,
        fileId: String,
        modificationTime: java.time.Instant? = null,
    ): InternxtFile =
        retryOnTransient {
            val creds = credentialsProvider()
            val requestBody =
                kotlinx.serialization.json.buildJsonObject {
                    put("fileId", kotlinx.serialization.json.JsonPrimitive(fileId))
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
            json.decodeFromString<InternxtFile>(response.bodyAsText())
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
            val creds = credentialsProvider()
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
        }
    }

    suspend fun createFolder(
        parentUuid: String,
        plainName: String,
        encryptedName: String,
    ): InternxtFolder =
        retryOnTransient {
            val creds = credentialsProvider()
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
            json.decodeFromString<InternxtFolder>(response.bodyAsText())
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
            val creds = credentialsProvider()
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
            json.decodeFromString<InternxtFile>(response.bodyAsText())
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
            val creds = credentialsProvider()
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
            json.decodeFromString<InternxtFolder>(response.bodyAsText())
        }

    suspend fun moveFile(
        uuid: String,
        destinationFolderUuid: String,
    ): InternxtFile =
        retryOnTransient {
            val creds = credentialsProvider()
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
            json.decodeFromString<InternxtFile>(response.bodyAsText())
        }

    suspend fun moveFolder(
        uuid: String,
        destinationFolderUuid: String,
    ): InternxtFolder =
        retryOnTransient {
            val creds = credentialsProvider()
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
            json.decodeFromString<InternxtFolder>(response.bodyAsText())
        }

    suspend fun deleteFile(uuid: String) {
        val creds = credentialsProvider()
        val response =
            httpClient.delete("$baseUrl/files/$uuid") {
                applyAuth(creds)
            }
        if (response.status != HttpStatusCode.NotFound) checkResponse(response)
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
            val creds = credentialsProvider()
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
        }
    }

    suspend fun deleteFolder(uuid: String) {
        val creds = credentialsProvider()
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
    }

    /** Streams encrypted bytes from [downloadUrl] through [cipher] directly to [destination]. Returns byte count. */
    suspend fun downloadFileStreaming(
        downloadUrl: String,
        cipher: javax.crypto.Cipher,
        destination: Path,
    ): Long =
        httpClient.prepareGet(downloadUrl).execute { response ->
            if (!response.status.isSuccess()) {
                throw InternxtApiException("Download failed: ${response.status}", response.status.value)
            }

            assertNotHtml(response, contextMsg = "Download (encrypted) -> ${destination.fileName}")
            val channel: ByteReadChannel = response.body()
            var written = 0L
            withContext(Dispatchers.IO) {
                Files.createDirectories(destination.parent)
                Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
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
            }
            written
        }

    // --- Bridge API ---

    private val bridgeUrl = "https://api.internxt.com"

    private suspend fun bridgeGet(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val creds = credentialsProvider()
        val authHeader = InternxtCrypto.bridgeAuthHeader(creds.bridgeUser, creds.bridgeUserId)
        val response =
            httpClient.get("$bridgeUrl$path") {
                header("Authorization", authHeader)
                header("x-api-version", "2")
                accept(ContentType.Application.Json)
                params.forEach { (k, v) -> parameter(k, v) }
            }
        checkResponse(response)
        return response.bodyAsText()
    }

    private suspend fun bridgePost(
        path: String,
        body: String,
    ): String {
        val creds = credentialsProvider()
        val authHeader = InternxtCrypto.bridgeAuthHeader(creds.bridgeUser, creds.bridgeUserId)
        val response =
            httpClient.post("$bridgeUrl$path") {
                header("Authorization", authHeader)
                header("x-api-version", "2")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        checkResponse(response)
        return response.bodyAsText()
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
    ): BucketEntry {
        val request =
            FinishUploadRequest(
                index = indexHex,
                shards = listOf(ShardMeta(hash = shardHash, uuid = shardUuid)),
            )
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
    }

    suspend fun putEncryptedShardFromFile(
        url: String,
        file: java.nio.file.Path,
        size: Long,
    ) {

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

    private suspend fun authenticatedGet(
        url: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val creds = credentialsProvider()
        var lastException: InternxtApiException? = null
        val delays = listOf(2_000L, 4_000L, 8_000L)
        for (delay in delays) {
            try {
                val response =
                    httpClient.get(url) {
                        applyAuth(creds)
                        params.forEach { (k, v) -> parameter(k, v) }
                    }
                checkResponse(response)
                return response.bodyAsText()
            } catch (e: InternxtApiException) {
                if (e.statusCode in listOf(500, 503)) {
                    lastException = e
                    kotlinx.coroutines.delay(delay)
                } else {
                    throw e
                }
            } catch (e: java.io.EOFException) {
                lastException = InternxtApiException("Server closed connection for GET $url: ${e.message}", 503)
                kotlinx.coroutines.delay(delay)
            } catch (e: java.io.IOException) {
                throw InternxtApiException("Connection error for GET $url: ${e.message}", 0)
            }
        }
        throw lastException!!
    }

    private fun HttpRequestBuilder.applyAuth(creds: InternxtCredentials) {
        bearerAuth(creds.jwt)
        applyInternxtHeaders(config)
    }

    /**
     * UD-203: pull the Internxt server-side request id off a response.
     * Header name confirmed from the upstream SDK
     * (`AxiosResponseError.xRequestId` — extracted at error-normalization
     * time). Returns null if the header isn't present (e.g. synthetic
     * MockEngine responses, pre-flight failures).
     */
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
                // Precedence: Retry-After HTTP header (captured at checkResponse
                // time, see InternxtApiException.retryAfterMs) > JSON-body
                // `retry_after` hint (parsed back out of the exception message)
                // > exponential backoff. Header wins per RFC 7231 §7.1.3, which
                // is the canonical place for the server to put this signal.
                val retryAfterMs =
                    e.retryAfterMs
                        ?: parseRetryAfter(e.message)
                        ?: (1000L shl (attempt - 1))
                val cappedMs = retryAfterMs.coerceIn(500L, 60_000L)
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
) : ProviderException(message, requestId = requestId)
