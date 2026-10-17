package org.krost.unidrive.s3

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.ProviderException
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.http.EndpointClassifier
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.http.RequestId
import org.krost.unidrive.http.RequestMetricsSink
import org.krost.unidrive.http.RequestMetricsTap
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.parseRetryAfterHeader
import org.krost.unidrive.http.readBoundedErrorBody
import org.krost.unidrive.http.streamingFileBody
import org.krost.unidrive.http.truncateErrorBody
import org.krost.unidrive.s3.SigV4Signer.Companion.uriEncode
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** An S3 refusal: the HTTP [status] and S3's error [code] (`NoSuchKey`, `NoSuchUpload`, `PreconditionFailed`, ...). */
class S3Exception(
    message: String,
    val status: Int,
    val code: String,
    requestId: String? = null,
) : ProviderException(message, requestId = requestId, httpStatus = status)

/** One object as a listing or a HEAD reports it. [etag] is without quotes; [mtime] is `x-amz-meta-mtime` when a HEAD read it. */
data class S3Object(
    val key: String,
    val etag: String?,
    val size: Long,
    val lastModified: Instant?,
    val mtime: Instant? = null,
)

data class S3ListPage(
    val objects: List<S3Object>,
    val prefixes: List<String>,
    val nextToken: String?,
)

data class S3Part(
    val number: Int,
    val etag: String,
    val size: Long,
)

/**
 * The S3 REST API, path-style, signed with [SigV4Signer]. Payloads are sent unsigned (`UNSIGNED-PAYLOAD`, over TLS)
 * except the small XML bodies, whose SHA-256 is signed. Every request takes a slot from the shared [budget]; a 500,
 * 503 (SlowDown) or 429 is retried with backoff, since every S3 call used here is safe to repeat. Anything else
 * reaches the caller.
 */
class S3Api(
    private val config: S3Config,
    engine: HttpClientEngine? = null,
    clock: () -> Instant = Instant::now,
    private val budget: HttpRetryBudget = HttpRetryBudget(maxConcurrency = MAX_CONCURRENCY),
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(S3Api::class.java)
    private val signer = SigV4Signer(config.accessKey, config.secretKey, config.region, clock)
    private val metrics = RequestMetricsTap()
    private val httpClient: HttpClient = s3HttpClient(metrics, config, engine)
    private val base = config.endpoint.trimEnd('/')

    fun attachRequestMetrics(sink: RequestMetricsSink?) {
        metrics.sink = sink
    }

    override fun close() = httpClient.close()

    // ── listing and metadata ──

    suspend fun list(
        prefix: String,
        delimiter: String? = null,
        startAfter: String? = null,
        token: String? = null,
        maxKeys: Int = 1000,
    ): S3ListPage {
        val query =
            buildList {
                add("list-type" to "2")
                add("max-keys" to maxKeys.toString())
                if (prefix.isNotEmpty()) add("prefix" to uriEncode(prefix))
                if (delimiter != null) add("delimiter" to uriEncode(delimiter))
                if (startAfter != null && token == null) add("start-after" to uriEncode(startAfter))
                if (token != null) add("continuation-token" to uriEncode(token))
            }
        val root = xml(requireOk(send(HttpMethod.Get, null, query)).bodyAsText())
        return S3ListPage(
            objects =
                root.children("Contents").map {
                    S3Object(
                        key = it.text("Key") ?: "",
                        etag = it.text("ETag")?.trim('"'),
                        size = it.text("Size")?.toLongOrNull() ?: 0,
                        lastModified = it.text("LastModified")?.let(::parseIso),
                    )
                },
            prefixes = root.children("CommonPrefixes").mapNotNull { it.text("Prefix") },
            nextToken = root.text("NextContinuationToken")?.takeIf { root.text("IsTruncated") == "true" },
        )
    }

    /** The object at [key], or null when there is none. */
    suspend fun head(key: String): S3Object? {
        val response = send(HttpMethod.Head, key)
        if (response.status.value == 404) return null
        requireOk(response)
        return S3Object(
            key = key,
            etag = response.headers["ETag"]?.trim('"'),
            size = response.headers["Content-Length"]?.toLongOrNull() ?: 0,
            lastModified = response.headers["Last-Modified"]?.let(::parseHttpDate),
            mtime = response.headers[MTIME_HEADER]?.let(::parseEpoch),
        )
    }

    // ── content ──

    /** Downloads [key] to [destination] through a staging sibling moved over it only when complete. */
    suspend fun get(
        key: String,
        destination: Path,
    ): Long {
        var attempt = 0
        while (true) {
            budget.awaitSlot()
            val statement = httpClient.prepareRequest { signed(HttpMethod.Get, key, emptyList(), emptyMap(), SigV4Signer.EMPTY_SHA256) }
            val outcome: Fetch =
                statement.execute { response ->
                    when {
                        isRetryable(response.status.value) && attempt < MAX_ATTEMPTS -> Fetch.Wait(backoffMs(response, attempt))
                        !response.status.isSuccess() -> throw failure(response, readBoundedErrorBody(response), "GET")
                        else -> {
                            budget.recordSuccess()
                            Fetch.Done(writeStaged(response, destination, key))
                        }
                    }
                }
            when (outcome) {
                is Fetch.Done -> return outcome.bytes
                is Fetch.Wait -> {
                    budget.recordThrottle(outcome.ms)
                    delay(outcome.ms)
                    attempt++
                }
            }
        }
    }

    private sealed interface Fetch {
        data class Wait(val ms: Long) : Fetch

        data class Done(val bytes: Long) : Fetch
    }

    /** PUTs [file] (or an empty body) to [key] with [meta]; [ifMatch] makes it conditional. Returns the new ETag. */
    suspend fun put(
        key: String,
        file: Path?,
        meta: Map<String, String> = emptyMap(),
        ifMatch: String? = null,
    ): String? {
        val size = file?.let { Files.size(it) } ?: 0L
        val headers = meta.mapKeys { "x-amz-meta-${it.key}" } + listOfNotNull(ifMatch?.let { "If-Match" to "\"$it\"" })
        val response =
            send(HttpMethod.Put, key, emptyList(), headers, SigV4Signer.UNSIGNED_PAYLOAD, timeoutMs = UploadTimeoutPolicy.computeRequestTimeoutMs(size)) {
                if (file != null) streamingFileBody(file, size) else ByteArray(0)
            }
        return requireOk(response, "PUT").headers["ETag"]?.trim('"')
    }

    // ── multipart ──

    suspend fun createMultipart(
        key: String,
        meta: Map<String, String>,
    ): String {
        val response = send(HttpMethod.Post, key, listOf("uploads" to ""), meta.mapKeys { "x-amz-meta-${it.key}" })
        return xml(requireOk(response).bodyAsText()).text("UploadId") ?: throw ProviderException("S3 started a multipart upload without an id")
    }

    /** Uploads [length] bytes of [file] from [offset] as part [number], streamed; [md5] is their MD5 (S3 checks it). */
    suspend fun uploadPart(
        key: String,
        uploadId: String,
        number: Int,
        file: Path,
        offset: Long,
        length: Long,
        md5: ByteArray,
    ): String {
        val response =
            send(
                HttpMethod.Put,
                key,
                listOf("partNumber" to number.toString(), "uploadId" to uriEncode(uploadId)),
                mapOf("Content-MD5" to Base64.getEncoder().encodeToString(md5)),
                SigV4Signer.UNSIGNED_PAYLOAD,
                timeoutMs = UploadTimeoutPolicy.computeRequestTimeoutMs(length),
            ) { rangeBody(file, offset, length) }
        return requireOk(response).headers["ETag"]?.trim('"') ?: throw ProviderException("S3 accepted part $number without an ETag")
    }

    /** Copies [range] of [sourceKey] into part [number]: how an object above [S3Config.MAX_SINGLE_COPY] is copied. */
    suspend fun uploadPartCopy(
        key: String,
        uploadId: String,
        number: Int,
        sourceKey: String,
        range: LongRange,
    ): String {
        val response =
            send(
                HttpMethod.Put,
                key,
                listOf("partNumber" to number.toString(), "uploadId" to uriEncode(uploadId)),
                mapOf("x-amz-copy-source" to copySource(sourceKey), "x-amz-copy-source-range" to "bytes=${range.first}-${range.last}"),
            )
        // A copy can fail after the 200 status line; the error is then the body.
        val root = xml(requireOk(response).bodyAsText())
        if (root.tagName == "Error") throw S3Exception("S3 part copy failed: ${root.text("Message")}", 200, root.text("Code") ?: "")
        return root.text("ETag")?.trim('"') ?: throw ProviderException("S3 copied part $number without an ETag")
    }

    suspend fun completeMultipart(
        key: String,
        uploadId: String,
        parts: List<Pair<Int, String>>,
        ifMatch: String? = null,
    ): String? {
        val body =
            parts.sortedBy { it.first }.joinToString("", "<CompleteMultipartUpload>", "</CompleteMultipartUpload>") { (n, etag) ->
                "<Part><PartNumber>$n</PartNumber><ETag>\"$etag\"</ETag></Part>"
            }.toByteArray(Charsets.UTF_8)
        val response =
            send(
                HttpMethod.Post,
                key,
                listOf("uploadId" to uriEncode(uploadId)),
                listOfNotNull(ifMatch?.let { "If-Match" to "\"$it\"" }).toMap(),
                SigV4Signer.sha256Hex(body),
            ) { body }
        val root = xml(requireOk(response, "CompleteMultipartUpload").bodyAsText())
        if (root.tagName == "Error") {
            val code = root.text("Code") ?: ""
            if (code == "PreconditionFailed") throw RemoteConflictException("'$key' changed on S3 since it was last synced")
            throw S3Exception("S3 could not complete the upload of '$key': ${root.text("Message")}", 200, code)
        }
        return root.text("ETag")?.trim('"')
    }

    suspend fun abortMultipart(
        key: String,
        uploadId: String,
    ) {
        val response = send(HttpMethod.Delete, key, listOf("uploadId" to uriEncode(uploadId)))
        if (response.status.value != 404) requireOk(response)
    }

    /** The parts uploaded so far, or null when the upload no longer exists (completed, aborted or expired). */
    suspend fun listParts(
        key: String,
        uploadId: String,
    ): List<S3Part>? {
        val parts = mutableListOf<S3Part>()
        var marker: String? = null
        do {
            val query = listOfNotNull("uploadId" to uriEncode(uploadId), marker?.let { "part-number-marker" to it })
            val response = send(HttpMethod.Get, key, query)
            if (response.status.value == 404) return null
            val root = xml(requireOk(response).bodyAsText())
            root.children("Part").forEach {
                parts += S3Part(it.text("PartNumber")!!.toInt(), it.text("ETag")!!.trim('"'), it.text("Size")?.toLongOrNull() ?: 0)
            }
            marker = root.text("NextPartNumberMarker")?.takeIf { root.text("IsTruncated") == "true" }
        } while (marker != null)
        return parts
    }

    // ── copy and delete ──

    /** Copies [sourceKey] to [key] in one call (at most [S3Config.MAX_SINGLE_COPY]), metadata included. */
    suspend fun copy(
        sourceKey: String,
        key: String,
    ): String? {
        val response = send(HttpMethod.Put, key, emptyList(), mapOf("x-amz-copy-source" to copySource(sourceKey), "x-amz-metadata-directive" to "COPY"))
        val root = xml(requireOk(response).bodyAsText())
        if (root.tagName == "Error") throw S3Exception("S3 copy of '$sourceKey' failed: ${root.text("Message")}", 200, root.text("Code") ?: "")
        return root.text("ETag")?.trim('"')
    }

    /** Deletes [key]; a key that is not there is deleted already. */
    suspend fun delete(key: String) {
        val response = send(HttpMethod.Delete, key)
        if (response.status.value != 404) requireOk(response)
    }

    /** Deletes up to 1,000 keys in one call; returns the keys S3 could not delete. */
    suspend fun deleteMany(keys: List<String>): List<String> {
        require(keys.size <= MAX_BATCH_DELETE)
        val body =
            keys.joinToString("", "<Delete><Quiet>true</Quiet>", "</Delete>") { "<Object><Key>${escapeXml(it)}</Key></Object>" }.toByteArray(Charsets.UTF_8)
        val response = send(HttpMethod.Post, null, listOf("delete" to ""), mapOf("Content-MD5" to md5Base64(body)), SigV4Signer.sha256Hex(body)) { body }
        return xml(requireOk(response).bodyAsText()).children("Error").mapNotNull { it.text("Key") }
    }

    /** A presigned GET of [key] valid for [seconds]. */
    fun presign(
        key: String,
        seconds: Long,
    ): String {
        val path = path(key)
        return base + path.removePrefix(basePath()) + "?" + signer.presignQuery(config.host, path, seconds).joinToString("&") { "${it.first}=${it.second}" }
    }

    // ── plumbing ──

    private fun basePath(): String = java.net.URI(base).rawPath ?: ""

    // The encoded path of [key] (or of the bucket), as signed and as sent.
    private fun path(key: String?): String = basePath() + "/" + uriEncode(config.bucket) + (key?.let { "/" + uriEncode(it, keepSlash = true) } ?: "")

    private fun copySource(sourceKey: String): String = "/" + uriEncode(config.bucket) + "/" + uriEncode(sourceKey, keepSlash = true)

    private fun io.ktor.client.request.HttpRequestBuilder.signed(
        method: HttpMethod,
        key: String?,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadHash: String,
    ) {
        val path = path(key)
        val queryText = if (query.isEmpty()) "" else "?" + query.joinToString("&") { "${it.first}=${it.second}" }
        url(base.removeSuffix(basePath()) + path + queryText)
        this.method = method
        headers.forEach { (k, v) -> header(k, v) }
        signer.sign(method.value, config.host, path, query, headers, payloadHash).forEach { (k, v) -> header(k, v) }
    }

    private suspend fun send(
        method: HttpMethod,
        key: String?,
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        payloadHash: String = SigV4Signer.EMPTY_SHA256,
        timeoutMs: Long? = null,
        body: (() -> Any)? = null,
    ): HttpResponse {
        var attempt = 0
        while (true) {
            budget.awaitSlot()
            val response =
                httpClient.request {
                    signed(method, key, query, headers, payloadHash)
                    if (timeoutMs != null) timeout { requestTimeoutMillis = timeoutMs }
                    if (body != null) setBody(body())
                }
            if (isRetryable(response.status.value) && attempt < MAX_ATTEMPTS) {
                val waitMs = backoffMs(response, attempt)
                log.warn("S3 answered {} on {} {}; retrying in {} ms", response.status.value, method.value, key ?: "(bucket)", waitMs)
                budget.recordThrottle(waitMs)
                delay(waitMs)
                attempt++
                continue
            }
            if (response.status.isSuccess()) budget.recordSuccess()
            return response
        }
    }

    private suspend fun requireOk(
        response: HttpResponse,
        what: String = response.call.request.method.value,
    ): HttpResponse {
        if (response.status.isSuccess()) return response
        throw failure(response, if (response.call.request.method == HttpMethod.Head) "" else response.bodyAsText(), what)
    }

    private fun failure(
        response: HttpResponse,
        body: String,
        what: String,
    ): Exception {
        val root = runCatching { xml(body) }.getOrNull()
        val code = root?.text("Code") ?: response.status.value.toString()
        val message = "S3 $what failed (${response.status.value} $code): ${truncateErrorBody(root?.text("Message") ?: body)}"
        val requestId = response.headers["x-amz-request-id"]
        return when (response.status.value) {
            401, 403 -> AuthenticationException(message, requestId = requestId)
            412 -> RemoteConflictException(message, requestId = requestId)
            else -> S3Exception(message, response.status.value, code, requestId)
        }
    }

    private fun isRetryable(status: Int): Boolean = status == 500 || status == 503 || status == 429

    private fun backoffMs(
        response: HttpResponse,
        attempt: Int,
    ): Long {
        val retryAfterMs = parseRetryAfterHeader(response.headers["Retry-After"])
        return if (retryAfterMs != null) {
            retryAfterMs.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
        } else {
            (MIN_WAIT_MS shl attempt).coerceAtMost(MAX_WAIT_MS)
        }
    }

    @Suppress("TooGenericExceptionCaught") // whatever fails, the staging file goes and the failure is rethrown unchanged
    private suspend fun writeStaged(
        response: HttpResponse,
        destination: Path,
        key: String,
    ): Long =
        withContext(Dispatchers.IO) {
            Files.createDirectories(destination.parent)
            val staging = destination.parent.resolve(org.krost.unidrive.io.stagingSiblingName(destination.fileName.toString(), ".unidrive-tmp"))
            try {
                val channel = response.bodyAsChannel()
                val count =
                    Files.newOutputStream(staging, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var n = 0L
                        while (true) {
                            val r = channel.readAvailable(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            n += r
                        }
                        n
                    }
                val expected = response.contentLength()
                if (expected != null && expected >= 0 && count != expected) {
                    throw java.io.IOException("Truncated S3 download of '$key': got $count of $expected bytes")
                }
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                count
            } catch (e: Exception) {
                Files.deleteIfExists(staging)
                throw e
            }
        }

    companion object {
        const val MTIME_HEADER = "x-amz-meta-mtime"
        const val MAX_BATCH_DELETE = 1000
        const val MAX_CONCURRENCY = 16
        private const val MAX_ATTEMPTS = 5
        private const val MIN_WAIT_MS = 500L
        private const val MAX_WAIT_MS = 60_000L

        fun md5Base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))

        fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        /** `x-amz-meta-mtime` as rclone writes it: seconds since the epoch, with a fraction. */
        fun epochText(instant: Instant): String = "${instant.epochSecond}.${"%09d".format(instant.nano)}"

        private fun parseEpoch(text: String): Instant? =
            text.trim().toBigDecimalOrNull()?.let {
                val seconds = it.toLong()
                Instant.ofEpochSecond(seconds, it.subtract(seconds.toBigDecimal()).movePointRight(9).toLong())
            }

        private fun parseIso(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

        private fun parseHttpDate(text: String): Instant? = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()

        private fun escapeXml(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        // No DTDs, no external entities: an answer is data, never a reason to fetch anything.
        private val documents =
            DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
                isNamespaceAware = false
            }

        fun xml(text: String): Element =
            synchronized(documents) { documents.newDocumentBuilder() }
                .parse(org.xml.sax.InputSource(java.io.StringReader(text)))
                .documentElement

        private fun Element.children(tag: String): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
        }

        private fun Element.text(tag: String): String? = children(tag).firstOrNull()?.textContent
    }
}

/** [length] bytes of [file] from [offset], read afresh for every attempt. */
internal fun rangeBody(
    file: Path,
    offset: Long,
    length: Long,
): io.ktor.http.content.OutgoingContent =
    object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = length
        override val contentType: io.ktor.http.ContentType = io.ktor.http.ContentType.Application.OctetStream

        override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
            withContext(Dispatchers.IO) {
                java.io.RandomAccessFile(file.toFile(), "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(64 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) throw java.io.IOException("'$file' became shorter while it was uploaded")
                        channel.writeFully(buf, 0, n)
                        left -= n
                    }
                }
            }
        }
    }

internal fun s3HttpClient(
    tap: RequestMetricsTap,
    config: S3Config,
    engine: HttpClientEngine?,
): HttpClient {
    val configure: HttpClientConfig<*>.() -> Unit = {
        install(HttpTimeout) {
            connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
            socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
            requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
        }
        // Every path segment is a bucket or a key, so every one becomes {id}: nothing identifying reaches the metrics.
        // #644: the token path's leaf is the profile name; S3 answers x-amz-request-id.
        install(RequestId) {
            metrics = tap
            provider = "s3"
            endpoints = EndpointClassifier(setOf(java.net.URI(config.endpoint).host ?: ""), emptySet())
            profile = config.tokenPath.fileName.toString()
            serverRequestIdHeaders = listOf("x-amz-request-id")
        }
    }
    return if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
}
