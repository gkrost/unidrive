package org.krost.unidrive.s3

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.assertNotHtml
import org.krost.unidrive.http.streamingFileBody
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Thin HTTP client for S3 / S3-compatible object storage.
 *
 * Authentication: AWS Signature Version 4 via [SigV4Signer].
 * XML parsing: lightweight regex extraction — avoids adding a DOM/SAX dependency.
 */
open class S3ApiService(
    private val config: S3Config,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(S3ApiService::class.java)

    private val httpClient =
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
                socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
                requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
            }
        }

    override fun close() = httpClient.close()

    // ── Object operations ─────────────────────────────────────────────────────

    /**
     * Download object at [key] to [destination].
     * Returns the number of bytes written.
     */
    open suspend fun getObject(
        key: String,
        destination: Path,
    ): Long {
        val url = objectUrl(key)
        val headers = SigV4Signer.sign("GET", url, config.region, config.accessKey, config.secretKey, SigV4Signer.EMPTY_BODY_HASH)
        log.debug("SigV4: GET {}", url)
        var written = 0L
        httpClient
            .prepareGet(url) { headers.forEach { (k, v) -> header(k, v) } }
            .execute { response ->
                checkResponse(response, url)
                assertNotHtml(response, contextMsg = "Download key=$key")
                val channel: ByteReadChannel = response.bodyAsChannel()
                withContext(Dispatchers.IO) {
                    Files.createDirectories(destination.parent)
                    Files.newOutputStream(destination).use { out ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = channel.readAvailable(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                        }
                    }
                }
            }
        return written
    }

    /**
     * Upload [localPath] as object at [key].
     * Returns the ETag returned by the server (stripped of quotes), or null.
     */
    open suspend fun putObject(
        key: String,
        localPath: Path,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): String? {
        val fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
        // UD-753: per-operation log moved to SyncEngine.applyUpload.
        val url = objectUrl(key)
        val headers =
            SigV4Signer.sign(
                "PUT",
                url,
                config.region,
                config.accessKey,
                config.secretKey,
                "UNSIGNED-PAYLOAD",
                extraHeaders = mapOf("content-type" to "application/octet-stream"),
            )
        val response =
            httpClient.put(url) {
                timeout {
                    requestTimeoutMillis = UploadTimeoutPolicy.computeRequestTimeoutMs(fileSize)
                }
                headers.forEach { (k, v) -> header(k, v) }
                setBody(streamingFileBody(localPath, fileSize))
            }
        checkResponse(response, url)
        onProgress?.invoke(fileSize, fileSize)
        return response.headers["ETag"]?.trim('"')
    }

    /**
     * Server-side copy: copy object at [fromKey] to [toKey] within the same bucket.
     * Uses the `x-amz-copy-source` header to avoid re-downloading the object.
     */
    open suspend fun copyObject(
        fromKey: String,
        toKey: String,
    ) {
        // UD-753: per-operation log moved to SyncEngine.applyMoveRemote.
        val url = objectUrl(toKey)
        val encodedFrom =
            fromKey.removePrefix("/").split("/").joinToString("/") { segment ->
                java.net.URLEncoder
                    .encode(segment, Charsets.UTF_8)
                    .replace("+", "%20")
            }
        val copySource = "/${config.bucket}/$encodedFrom"
        val headers =
            SigV4Signer.sign(
                "PUT",
                url,
                config.region,
                config.accessKey,
                config.secretKey,
                SigV4Signer.EMPTY_BODY_HASH,
                extraHeaders = mapOf("x-amz-copy-source" to copySource),
            )
        val response =
            httpClient.put(url) {
                headers.forEach { (k, v) -> header(k, v) }
            }
        checkResponse(response, url)
    }

    /** Delete object at [key]. 204/404 are both treated as success. */
    open suspend fun deleteObject(key: String) {
        // UD-753: per-operation log moved to SyncEngine.applyDeleteRemote.
        val url = objectUrl(key)
        val headers = SigV4Signer.sign("DELETE", url, config.region, config.accessKey, config.secretKey, SigV4Signer.EMPTY_BODY_HASH)
        val response = httpClient.delete(url) { headers.forEach { (k, v) -> header(k, v) } }
        if (response.status.value != 404) checkResponse(response, url)
    }

    // ── Bucket listing ────────────────────────────────────────────────────────

    /**
     * List all objects in the bucket using ListObjectsV2 with pagination.
     * Returns a list of [S3Object] for files and synthetic folder entries.
     */
    open suspend fun listAll(
        prefix: String = "",
        // UD-352: invoked after each ListObjectsV2 page is appended so the
        // engine can fire scan progress during very long bucket walks.
        // Optional; existing callers omit it for unchanged behaviour.
        onProgress: ((itemsSoFar: Int) -> Unit)? = null,
    ): List<S3Object> {
        val objects = mutableListOf<S3Object>()
        var continuationToken: String? = null

        log.debug("ListObjectsV2: bucket={}", config.bucket)
        do {
            val queryParams =
                buildMap {
                    put("list-type", "2")
                    put("max-keys", "1000")
                    if (prefix.isNotEmpty()) put("prefix", prefix)
                    if (continuationToken != null) put("continuation-token", continuationToken!!)
                }
            val queryString =
                queryParams.entries.joinToString("&") {
                    "${urlEncode(it.key)}=${urlEncode(it.value)}"
                }
            val url = "${bucketUrl()}?$queryString"
            val headers = SigV4Signer.sign("GET", url, config.region, config.accessKey, config.secretKey, SigV4Signer.EMPTY_BODY_HASH)
            val response = httpClient.get(url) { headers.forEach { (k, v) -> header(k, v) } }
            checkResponse(response, url)

            val body = response.bodyAsText()
            objects.addAll(parseListObjectsV2(body))
            log.debug("ListObjectsV2: {} items so far", objects.size)
            onProgress?.invoke(objects.size)
            continuationToken = xmlValue(body, "NextContinuationToken")
        } while (continuationToken != null)

        return objects
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generate a pre-signed URL for an object.
     * @param key S3 object key
     * @param expirySeconds URL validity duration (default 24 hours)
     */
    open fun presign(
        key: String,
        expirySeconds: Int = 86400,
    ): String {
        val url = objectUrl(key)
        val expiryIso =
            java.time.Instant
                .now()
                .plusSeconds(expirySeconds.toLong())
        val amzDate =
            java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd'T'HHmmss'Z'"),
            )
        val shortDate =
            java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMdd"),
            )

        val credential = "${config.accessKey}/$shortDate/${config.region}/s3/aws4_request"
        val queryParams =
            sortedMapOf<String, String>().apply {
                put("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                put("X-Amz-Credential", credential)
                put("X-Amz-Date", amzDate)
                put("X-Amz-Expires", expirySeconds.toString())
                put("X-Amz-SignedHeaders", "host")
            }

        val signedHeaders = "host"
        val canonicalHeaders = "host:${java.net.URI(url).host}\n"
        val canonicalQueryString = queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val canonicalRequest = "GET\n/${key.removePrefix(
            "/",
        )}\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n${SigV4Signer.EMPTY_BODY_HASH}"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$shortDate/${config.region}/s3/aws4_request\n${SigV4Signer.sha256Hex(
            canonicalRequest.toByteArray(),
        )}"

        val kDate = SigV4Signer.hmacSha256("AWS4${config.secretKey}".toByteArray(), shortDate.toByteArray(Charsets.UTF_8))
        val kRegion = SigV4Signer.hmacSha256(kDate, config.region.toByteArray(Charsets.UTF_8))
        val kService = SigV4Signer.hmacSha256(kRegion, "s3".toByteArray(Charsets.UTF_8))
        val kSigning = SigV4Signer.hmacSha256(kService, "aws4_request".toByteArray(Charsets.UTF_8))
        val signature = SigV4Signer.hmacSha256Hex(kSigning, stringToSign)

        queryParams["X-Amz-Signature"] = signature
        val finalQuery = queryParams.entries.joinToString("&") { "${it.key}=${it.value}" }

        return "$url?$finalQuery"
    }

    private fun bucketUrl(): String = "${config.endpoint.trimEnd('/')}/${config.bucket}"

    fun objectUrl(key: String): String {
        val encodedKey =
            key.removePrefix("/").split("/").joinToString("/") { segment ->
                java.net.URLEncoder
                    .encode(segment, Charsets.UTF_8)
                    .replace("+", "%20")
            }
        return "${bucketUrl()}/$encodedKey"
    }

    /** Convert a virtual path (/foo/bar.txt) to an S3 key (foo/bar.txt). */
    fun pathToKey(path: String): String = path.removePrefix("/")

    /** Convert an S3 key (foo/bar.txt) to a virtual path (/foo/bar.txt). */
    fun keyToPath(key: String): String = "/$key"

    private suspend fun checkResponse(
        response: HttpResponse,
        url: String,
    ) {
        if (response.status.isSuccess()) return
        val body = response.bodyAsText()
        val code = xmlValue(body, "Code") ?: response.status.value.toString()
        val message = xmlValue(body, "Message") ?: ""
        when {
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden ->
                throw AuthenticationException("S3 auth failed ($code): $message [$url]")
            else ->
                throw S3Exception("S3 error ($code): $message [$url]", response.status.value)
        }
    }

    private fun parseListObjectsV2(xml: String): List<S3Object> {
        val objects = mutableListOf<S3Object>()

        // Parse <Contents> elements (regular objects)
        val contentsRegex = Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL)
        for (match in contentsRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val key = xmlValue(block, "Key") ?: continue
            val etag = xmlValue(block, "ETag")?.trim('"')
            val size = xmlValue(block, "Size")?.toLongOrNull() ?: 0L
            val lastModified = xmlValue(block, "LastModified")
            objects.add(S3Object(key = key, etag = etag, size = size, lastModified = lastModified, isFolder = false))
        }

        // Parse <CommonPrefixes> elements (folder placeholders from delimiter listings)
        val prefixRegex = Regex("<CommonPrefixes>(.*?)</CommonPrefixes>", RegexOption.DOT_MATCHES_ALL)
        for (match in prefixRegex.findAll(xml)) {
            val prefix = xmlValue(match.groupValues[1], "Prefix") ?: continue
            objects.add(S3Object(key = prefix, etag = null, size = 0, lastModified = null, isFolder = true))
        }

        return objects
    }

    private fun xmlValue(
        xml: String,
        tag: String,
    ): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder
            .encode(s, "UTF-8")
            .replace("+", "%20")
}

data class S3Object(
    val key: String,
    val etag: String?,
    val size: Long,
    val lastModified: String?,
    val isFolder: Boolean,
)
