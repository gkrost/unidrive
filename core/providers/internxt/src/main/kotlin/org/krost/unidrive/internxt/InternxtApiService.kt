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
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.assertNotHtml
import org.krost.unidrive.http.streamingFileBody
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
        // UD-335: retry-eligible HTTP statuses for transient-failure write
        // endpoints. 429 is rate-limit; 5xx are server-side. The Cloudflare
        // 502 origin_bad_gateway pattern that drove this ticket lives here.
        private val TRANSIENT_STATUSES = setOf(429, 500, 502, 503, 504)

        // UD-335: capture `"retry_after": <seconds>` from Cloudflare /
        // Internxt JSON error bodies. Returns the integer seconds.
        private val RETRY_AFTER_REGEX = Regex(""""retry_after"\s*:\s*(\d+)""")

        /**
         * UD-358: query params shared by `listFiles` and `listFolders`. The
         * Drive API exposes `sort` ∈ {`updatedAt`, `uuid`} and `order` ∈
         * {`ASC`, `DESC`}; without an explicit stable sort, paginated
         * full-scans can drop or duplicate rows whenever the server-side
         * default order reshuffles between requests (a row inserted /
         * updated / deleted mid-walk shifts every subsequent page boundary).
         * `sort=uuid&order=ASC` is the only choice that's stable under
         * concurrent mutation: UUID is opaque and immutable per row, where
         * `updatedAt` is reshuffled by every write.
         *
         * Extracted as a pure helper so a unit test can pin the wire shape
         * without HTTP infrastructure.
         */
        internal fun listingQueryParams(
            updatedAt: String?,
            limit: Int,
            offset: Int,
        ): Map<String, String> {
            val params =
                mutableMapOf(
                    "status" to "ALL",
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "sort" to "uuid",
                    "order" to "ASC",
                )
            if (updatedAt != null) params["updatedAt"] = updatedAt
            return params
        }

        // UD-353: throughput floor for OVH temporal-uploads-bucket PUTs.
        // Internxt's shard-upload backend is `s3.gra.io.cloud.ovh.net`,
        // a third-party S3-compatible endpoint with no Internxt-side SLA.
        // Observed at 30–50 KB/s during congestion (2026-04-30 incident:
        // 5 ~30 MiB videos torn down at the 600 s floor while OVH was
        // still receiving). Override the UploadTimeoutPolicy default
        // (50 KiB/s) with a more pessimistic 10 KiB/s for OVH PUTs only;
        // Internxt's own API calls keep the default. The 60 s
        // socketTimeoutMillis still fires on stalled connections, so
        // slow-loris exposure is unchanged.
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

    // UD-343: shared :app:core UnidriveJson singleton.
    private val json = org.krost.unidrive.UnidriveJson
    private val baseUrl = InternxtConfig.API_BASE_URL

    suspend fun getFolderContents(folderUuid: String): FolderContentResponse {
        val body = authenticatedGet("$baseUrl/folders/content/$folderUuid")
        return json.decodeFromString<FolderContentResponse>(body)
    }

    suspend fun listFiles(
        updatedAt: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<InternxtFile> {
        val body = authenticatedGet("$baseUrl/files", listingQueryParams(updatedAt, limit, offset))
        return json.decodeFromString<List<InternxtFile>>(body)
    }

    suspend fun listFolders(
        updatedAt: String? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<InternxtFolder> {
        val body = authenticatedGet("$baseUrl/folders", listingQueryParams(updatedAt, limit, offset))
        return json.decodeFromString<List<InternxtFolder>>(body)
    }

    suspend fun getFileMeta(uuid: String): InternxtFile {
        val body = authenticatedGet("$baseUrl/files/$uuid/meta")
        return json.decodeFromString<InternxtFile>(body)
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
            // UD-340: shared assertNotHtml at :app:core/http (UD-333/UD-231/
            // UD-293 lineage). On Internxt this is HIGHER severity than on
            // the OneDrive baseline because the body is fed through
            // cipher.update() (AES-CTR) before write — HTML XOR'd through a
            // strong keystream produces high-entropy bytes indistinguishable
            // from a real decrypted file. UD-226's NUL-stub sweep does not
            // catch it. Permanent silent corruption is the failure mode if
            // the guard isn't here. Must execute BEFORE the first
            // cipher.update() call so the AES-CTR keystream / IV state stays
            // untouched and the URL can be retried cleanly.
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
        // UD-342: shared streamingFileBody adds UD-287 finally-flushAndClose
        // (Internxt's previous inline body lacked it — silent connection-
        // pool corruption on cancellation).
        val response =
            httpClient.put(url) {
                // UD-337 + UD-353: streaming variant that actually triggered
                // the 2026-04-30 incident. Same pessimistic OVH floor — see
                // OVH_PUT_MIN_THROUGHPUT_BPS.
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
        applyInternxtHeaders()
    }

    private suspend fun checkResponse(response: HttpResponse) {
        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401): ${response.bodyAsText()}")
        }
        if (!response.status.isSuccess()) {
            throw InternxtApiException("API error: ${response.status} - ${response.bodyAsText()}", response.status.value)
        }
    }

    // UD-335: tactical retry on transient 5xx + 429 for write endpoints
    // (createFolder / createFile / moveFile / moveFolder). Cloudflare 502
    // origin_bad_gateway during a 1230-folder mkdir burst on 2026-04-30
    // surfaced as ~30 hard failures in a single sync — Internxt's API has
    // no retry layer, so the action propagates straight to SyncEngine
    // which logs WARN and moves on. With this wrapper the action retries
    // in-band against the captured retry_after hint (or exponential
    // fallback) before bubbling up.
    //
    // UD-330 is the structural cross-provider HttpRetryBudget lift. This is
    // narrower: only the write endpoints, only Internxt, no shared budget
    // across concurrent calls (each caller backs off independently).
    //
    // Idempotency caveat: POST `/folders` / `/files` MAY create a duplicate
    // if the server completed the write but Cloudflare 502'd the response
    // edge. Internxt's documented behaviour for duplicate-name creates is
    // a 4xx (which we don't retry), so the typical case is "request never
    // landed → safe to retry". A tiny tail of double-creates is the
    // accepted cost vs the much-larger pain of dropping every transient
    // failure.
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
                val retryAfterMs =
                    parseRetryAfter(e.message)
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

    // UD-335: parse `"retry_after": <int seconds>` from the Cloudflare /
    // Internxt error JSON body (which our InternxtApiException carries
    // verbatim in its message). Returns ms, or null if absent/unparseable.
    internal fun parseRetryAfter(message: String?): Long? {
        if (message == null) return null
        val match = RETRY_AFTER_REGEX.find(message) ?: return null
        return match.groupValues[1].toLongOrNull()?.times(1000)
    }

    // UD-336 (UD-334 Part A): readBoundedErrorBody lifted to
    // org.krost.unidrive.http.ErrorBody so HiDrive / Internxt / OneDrive
    // share a single implementation. UD-333 / UD-293 origin notes are
    // preserved on the lifted helper.

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

class InternxtApiException(
    message: String,
    val statusCode: Int = 0,
) : ProviderException(message)
