package org.krost.unidrive.webdav

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.assertNotHtml
import org.krost.unidrive.http.streamingFileBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Thin HTTP client for WebDAV servers using Ktor with Basic auth.
 *
 * XML responses from PROPFIND are parsed with lightweight regex extraction
 * to avoid adding a DOM/SAX dependency.
 *
 * PROPFIND uses `Depth: 1` per-directory in a BFS traversal to avoid
 * servers that reject `Depth: infinity` (Nextcloud, SharePoint).
 */

open class WebDavApiService(
    private val config: WebDavConfig,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(WebDavApiService::class.java)

    // Cache of collection paths known to exist on the server — primed by
    // successful MKCOL (201) and by "already exists" responses (405/409).
    // Eliminates redundant MKCOL round-trips when many files share ancestors.
    // Per-instance: cleared when the service is closed.
    internal val knownCollections: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    private val commonConfig: HttpClientConfig<*>.() -> Unit = {
        install(HttpTimeout) {
            connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
            socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
            requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
        }
        install(Auth) {
            basic {
                credentials { BasicAuthCredentials(config.username, config.password) }
                sendWithoutRequest { true }
            }
        }
        followRedirects = false
    }

    @PublishedApi
    internal val httpClient =
        if (config.trustAllCerts) {
            // Apache5 engine for LAN/self-signed certs.
            // Neither java.net.http.HttpClient (hardcodes HTTPS hostname verification)
            // nor CIO (triggers ProtocolVersion alert on Synology DSM 7.x) work here.
            //
            // Apache5EngineConfig doesn't expose a hostname verifier setter, but
            // HttpAsyncClientBuilder.setConnectionManager() is available in the
            // customizeClient block. We inject a PoolingAsyncClientConnectionManager
            // pre-wired with ClientTlsStrategyBuilder + NoopHostnameVerifier, which
            // overrides the TLS strategy Ktor built from sslContext/policy fields.
            val sslCtx =
                javax.net.ssl.SSLContext
                    .getInstance("TLS")
            sslCtx.init(
                null,
                arrayOf(
                    object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(
                            chain: Array<java.security.cert.X509Certificate>?,
                            authType: String?,
                        ) {}

                        override fun checkServerTrusted(
                            chain: Array<java.security.cert.X509Certificate>?,
                            authType: String?,
                        ) {}

                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                    },
                ),
                java.security.SecureRandom(),
            )
            val tlsStrategy =
                org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder
                    .create()
                    .setSslContext(sslCtx)
                    .setHostnameVerifier(org.apache.hc.client5.http.ssl.NoopHostnameVerifier.INSTANCE)
                    .buildAsync()
            val connManager =
                org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
                    .create()
                    .setTlsStrategy(tlsStrategy)
                    .setDefaultConnectionConfig(
                        org.apache.hc.client5.http.config.ConnectionConfig
                            .custom()
                            .setValidateAfterInactivity(
                                org.apache.hc.core5.util.TimeValue
                                    .ofSeconds(2),
                            ).build(),
                    ).build()
            HttpClient(io.ktor.client.engine.apache5.Apache5) {
                engine {
                    customizeClient {
                        setConnectionManager(connManager)
                        // UD-287: actively rotate stale sockets out of the pool.
                        // DSM Apache mod_dav keep-alive defaults to ~5 min;
                        // 30 s idle eviction stays well inside that window
                        // so we never reuse a server-side-closed connection.
                        evictExpiredConnections()
                        evictIdleConnections(
                            org.apache.hc.core5.util.TimeValue
                                .ofSeconds(30),
                        )
                    }
                }
                commonConfig()
            }
        } else {
            HttpClient(io.ktor.client.engine.cio.CIO, commonConfig)
        }

    override fun close() = httpClient.close()

    /**
     * UD-288: wrap a WebDAV operation in a 5-attempt retry budget.
     *
     * Retries on:
     *   - [HttpRequestTimeoutException]                — Ktor client timeout
     *   - retriable [IOException]                      — see [isRetriableIoException]
     *   - retriable HTTP status (408, 425, 429, 5xx)   — see [isRetriableStatus]
     *
     * Honors [HttpHeaders.RetryAfter] when present on a [ResponseException]
     * (DSM emits it on 503 Service Unavailable). Otherwise falls back to
     * [backoffMs] (default exponential: 1 s, 2 s, 4 s, 8 s, 16 s).
     *
     * Always re-throws [CancellationException] before any retry decision.
     */
    internal suspend fun <T> withRetry(
        op: String,
        target: String,
        maxAttempts: Int = 5,
        backoffMs: (attempt: Int) -> Long = { 1000L * (1L shl it) },
        block: suspend () -> T,
    ): T {
        var lastException: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                lastException = e
                log.warn(
                    "WebDAV {} {} timed out (attempt {}/{}): {}",
                    op,
                    target,
                    attempt + 1,
                    maxAttempts,
                    e.message,
                )
            } catch (e: ResponseException) {
                if (!isRetriableStatus(e.response.status)) throw e
                lastException = e
                log.warn(
                    "WebDAV {} {} HTTP {} (attempt {}/{})",
                    op,
                    target,
                    e.response.status.value,
                    attempt + 1,
                    maxAttempts,
                )
            } catch (e: IOException) {
                if (!isRetriableIoException(e)) throw e
                lastException = e
                log.warn(
                    "WebDAV {} {} I/O error (attempt {}/{}): {}: {}",
                    op,
                    target,
                    attempt + 1,
                    maxAttempts,
                    e.javaClass.simpleName,
                    e.message,
                )
            }
            // Last attempt: don't sleep before throwing.
            if (attempt + 1 < maxAttempts) {
                val retryAfterMs =
                    (lastException as? ResponseException)
                        ?.response
                        ?.headers
                        ?.get(HttpHeaders.RetryAfter)
                        ?.toLongOrNull()
                        ?.times(1000L)
                        ?: backoffMs(attempt)
                delay(retryAfterMs)
            }
        }
        throw lastException ?: IllegalStateException("withRetry budget exhausted with no captured cause")
    }

    /**
     * HTTP statuses where another attempt has a reasonable chance of succeeding.
     * 408 Request Timeout, 425 Too Early, 429 Too Many Requests, and 5xx
     * server errors all imply "try again later." Everything else (404, 401,
     * 403, 409, 410, etc.) is terminal — retrying would mask the real issue.
     */
    internal fun isRetriableStatus(status: HttpStatusCode): Boolean = status.value in setOf(408, 425, 429, 500, 502, 503, 504)

    /**
     * IOException subclasses (or message text) where another attempt is
     * worth trying. Conservatively narrow to known transient failures —
     * UnknownHostException / SSL handshake errors signal misconfiguration,
     * not transient network state, and should NOT be retried.
     */
    internal fun isRetriableIoException(e: IOException): Boolean {
        if (e is java.net.SocketTimeoutException) return true
        if (e is org.apache.hc.core5.http.ConnectionClosedException) return true
        // SocketException covers "Connection reset", "Broken pipe", etc.
        // but UnknownHostException extends IOException too — exclude it.
        if (e is java.net.UnknownHostException) return false
        if (e is javax.net.ssl.SSLPeerUnverifiedException) return false
        if (e is javax.net.ssl.SSLHandshakeException) return false
        if (e is java.net.SocketException) return true
        // Windows TCP WSAECONNABORTED / WSAECONNRESET surface as IOException
        // with localised message text — match by substring as a backstop.
        val msg = e.message ?: return false
        return msg.contains("aborted", ignoreCase = true) ||
            msg.contains("reset", ignoreCase = true) ||
            // German Windows: "Eine bestehende Verbindung wurde softwaregesteuert"
            msg.contains("Verbindung wurde", ignoreCase = true)
    }

    // ── Connectivity check ────────────────────────────────────────────────────

    /** HEAD request to verify credentials and connectivity. */
    suspend fun head(remotePath: String) {
        val url = resourceUrl(remotePath)
        val response = httpClient.head(url)
        checkResponse(response, url)
    }

    // ── File operations ───────────────────────────────────────────────────────

    /** Download resource at [remotePath] to [destination]. Returns bytes written. */
    suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long =
        withRetry("download", remotePath) {
            val url = resourceUrl(remotePath)
            var written = 0L
            httpClient
                .prepareGet(url) {
                    timeout { requestTimeoutMillis = Long.MAX_VALUE }
                }.execute { response ->
                    checkResponse(response, url)
                    assertNotHtml(response, contextMsg = "Download $remotePath")
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
            written
        }

    /** Upload [localPath] as resource at [remotePath]. Returns a [WebDavEntry]. */
    suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): WebDavEntry =
        withRetry("upload", remotePath) {
            uploadOnce(localPath, remotePath, onProgress)
        }

    private suspend fun uploadOnce(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?,
    ): WebDavEntry {
        val fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
        val url = resourceUrl(remotePath)
        ensureParentCollections(remotePath)
        val uploadTimeout =
            UploadTimeoutPolicy.computeRequestTimeoutMs(
                fileSize = fileSize,
                floorMs = config.uploadFloorTimeoutMs,
                minThroughputBytesPerSecond = config.uploadMinThroughputBytesPerSecond,
            )
        val response =
            httpClient.put(url) {
                timeout { requestTimeoutMillis = uploadTimeout }
                setBody(streamingFileBody(localPath, fileSize))
            }
        checkResponse(response, url)
        onProgress?.invoke(fileSize, fileSize)
        val etag = response.headers["ETag"]?.trim('"')
        val now = Instant.now()
        return WebDavEntry(path = remotePath, size = fileSize, lastModified = now, etag = etag, isFolder = false)
    }

    /** Delete resource or empty collection at [remotePath]. 404 is silently ignored. */
    suspend fun delete(remotePath: String) {
        val url = resourceUrl(remotePath)
        val response = httpClient.delete(url)
        if (response.status != HttpStatusCode.NotFound) checkResponse(response, url)
    }

    /** Create a collection (directory) at [remotePath]. */
    suspend fun mkcol(remotePath: String) {
        val normalized = normalizeCollectionKey(remotePath)
        if (normalized in knownCollections) return
        withRetry("mkcol", remotePath) {
            mkcolOnce(remotePath, normalized)
        }
    }

    private suspend fun mkcolOnce(
        remotePath: String,
        normalized: String,
    ) {
        log.debug("MKCOL: {}", remotePath)
        val url = resourceUrl(remotePath)
        val response = httpClient.request(url) { method = HttpMethod("MKCOL") }
        // 405 Method Not Allowed means it already exists — ignore
        if (response.status != HttpStatusCode.MethodNotAllowed &&
            response.status != HttpStatusCode.Conflict
        ) {
            checkResponse(response, url)
        }
        // Cache on both success (newly created) and 405/409 (already existed).
        knownCollections.add(normalized)
    }

    /**
     * Move/rename resource from [fromPath] to [toPath].
     * WebDAV MOVE is a server-side operation that preserves content.
     */
    suspend fun move(
        fromPath: String,
        toPath: String,
    ) {
        val fromUrl = resourceUrl(fromPath)
        val toUrl = resourceUrl(toPath)
        log.debug("MOVE destination: {}", toUrl)
        ensureParentCollections(toPath)
        val response =
            httpClient.request(fromUrl) {
                method = HttpMethod("MOVE")
                header("Destination", toUrl)
                header("Overwrite", "T")
            }
        checkResponse(response, fromUrl)
    }

    // ── Listing ───────────────────────────────────────────────────────────────

    /**
     * Recursively list all resources under [remotePath] using BFS with Depth-1 PROPFIND.
     * Returns files and folder entries.
     */
    suspend fun listAll(
        remotePath: String = "",
        onProgress: ((itemsSoFar: Int) -> Unit)? = null,
    ): List<WebDavEntry> {
        val results = mutableListOf<WebDavEntry>()
        val queue = ArrayDeque<String>()
        queue.add(remotePath.ifEmpty { "/" })

        val rootHref = hrefForPath(remotePath.ifEmpty { "/" })

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val entries = propfind(dir)
            for (entry in entries) {
                // Skip the directory itself (DAV servers include it in their own PROPFIND response)
                val dirHref = hrefForPath(dir)
                if (normalizeHref(entry.rawHref) == normalizeHref(dirHref)) continue
                // Also skip the root itself
                if (normalizeHref(entry.rawHref) == normalizeHref(rootHref)) continue
                results.add(entry)
                if (entry.isFolder) queue.add(entry.path)
            }
            onProgress?.invoke(results.size)
        }
        return results
    }

    /**
     * PROPFIND Depth:1 on [remotePath]. Returns all direct children plus the
     * resource itself.
     *
     */
    open suspend fun propfind(remotePath: String): List<WebDavEntry> =
        withRetry("propfind", remotePath) {
            log.debug("PROPFIND depth=1: {}", remotePath)
            val url = resourceUrl(remotePath)
            val body = """<?xml version="1.0" encoding="UTF-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:resourcetype/>
    <D:getcontentlength/>
    <D:getlastmodified/>
    <D:getetag/>
  </D:prop>
</D:propfind>"""
            val response =
                httpClient.request(url) {
                    method = HttpMethod("PROPFIND")
                    header("Depth", "1")
                    contentType(ContentType.Application.Xml)
                    setBody(body)
                }
            checkResponse(response, url)
            parsePropfindResponse(response.bodyAsText(), remotePath)
        }

    /**
     * RFC 4331 PROPFIND Depth:0 on the root collection to retrieve
     * `quota-available-bytes` and `quota-used-bytes`. Returns null when the
     * server does not advertise these properties (e.g. Synology DSM 7.x,
     * Apache mod_dav).
     */
    open suspend fun quotaPropfind(): QuotaInfo? =
        withRetry("quotaPropfind", "/") {
            val url = resourceUrl("/")
            val body =
                """<?xml version="1.0" encoding="UTF-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:quota-available-bytes/>
    <D:quota-used-bytes/>
  </D:prop>
</D:propfind>"""
            val response =
                httpClient.request(url) {
                    method = HttpMethod("PROPFIND")
                    header("Depth", "0")
                    contentType(ContentType.Application.Xml)
                    setBody(body)
                }
            checkResponse(response, url)
            parseQuotaPropfindXml(response.bodyAsText())
        }

    internal fun parseQuotaPropfindXml(xml: String): QuotaInfo? {
        val available = xmlValue(xml, "quota-available-bytes")?.toLongOrNull()
        val used = xmlValue(xml, "quota-used-bytes")?.toLongOrNull()
        if (available == null && used == null) return null
        val avail = available ?: 0L
        val usedVal = used ?: 0L
        return QuotaInfo(total = avail + usedVal, used = usedVal, remaining = avail)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Build the full URL for a virtual path under [config.baseUrl]. */
    fun resourceUrl(virtualPath: String): String {
        val base = config.baseUrl.trimEnd('/')
        val rel = virtualPath.trimStart('/')
        if (rel.isEmpty()) return "$base/"
        val encoded =
            rel.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
            }
        return "$base/$encoded"
    }

    private fun hrefForPath(virtualPath: String): String {
        val url = resourceUrl(virtualPath)
        return java.net.URI(url).rawPath
    }

    private fun normalizeHref(href: String): String = href.trimEnd('/')

    internal suspend fun ensureParentCollections(remotePath: String) {
        val parts = remotePath.trimStart('/').split("/").dropLast(1)
        if (parts.isEmpty()) return
        // Fast path: if the deepest parent is already known, all ancestors are too.
        val deepest = "/" + parts.joinToString("/")
        if (normalizeCollectionKey(deepest) in knownCollections) return
        var current = ""
        for (part in parts) {
            current = "$current/$part"
            mkcol(current)
        }
    }

    internal fun normalizeCollectionKey(path: String): String = "/" + path.trim('/')

    private fun checkResponse(
        response: HttpResponse,
        url: String,
    ) {
        when {
            response.status == HttpStatusCode.Unauthorized ->
                throw AuthenticationException("WebDAV auth failed (401): $url")
            !response.status.isSuccess() && response.status.value !in listOf(207) ->
                throw WebDavException("WebDAV request failed (${response.status.value}): $url", response.status.value)
        }
    }

    internal fun parsePropfindResponse(
        xml: String,
        requestPath: String,
    ): List<WebDavEntry> {
        val results = mutableListOf<WebDavEntry>()
        // Match <D:response ...> with any namespace prefix or none, and any inline attributes.
        // Synology DSM inlines namespace declarations on each <D:response> element, e.g.:
        //   <D:response xmlns:lp1="DAV:" xmlns:lp2="..." xmlns:g0="DAV:">
        // which the original <[Dd]:response> pattern did not match.
        val responseRegex =
            Regex(
                "<(?:[A-Za-z][A-Za-z0-9]*:)?response(?:\\s[^>]*)?>(.+?)</(?:[A-Za-z][A-Za-z0-9]*:)?response>",
                RegexOption.DOT_MATCHES_ALL,
            )
        for (match in responseRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val rawHref = xmlValue(block, "href") ?: xmlValue(block, "D:href") ?: continue
            val blockLower = block.lowercase()
            val isFolder =
                blockLower.contains("<d:collection") ||
                    blockLower.contains("<collection") ||
                    blockLower.contains("resourcetype>") &&
                    blockLower.contains("collection") ||
                    rawHref.endsWith("/")
            val sizeStr = xmlValue(block, "getcontentlength") ?: xmlValue(block, "D:getcontentlength")
            val size = sizeStr?.toLongOrNull() ?: 0L
            val lastMod = xmlValue(block, "getlastmodified") ?: xmlValue(block, "D:getlastmodified")
            val etag = xmlValue(block, "getetag") ?: xmlValue(block, "D:getetag")
            val modified = lastMod?.let { parseHttpDate(it) }

            // Convert href → virtual path
            val base = config.baseUrl.trimEnd('/')
            val basePath =
                java.net
                    .URI(base)
                    .rawPath
                    .trimEnd('/')
            val decodedHref = java.net.URLDecoder.decode(rawHref, "UTF-8")
            val virtualPath =
                when {
                    decodedHref.startsWith(base) -> "/" + decodedHref.removePrefix(base).trimStart('/')
                    decodedHref.startsWith(basePath) -> "/" + decodedHref.removePrefix(basePath).trimStart('/')
                    else -> decodedHref
                }.trimEnd('/').ifEmpty { "/" }

            results.add(
                WebDavEntry(
                    path = virtualPath,
                    rawHref = rawHref,
                    size = size,
                    lastModified = modified,
                    etag = etag?.trim('"'),
                    isFolder = isFolder,
                ),
            )
        }
        return results
    }

    private fun xmlValue(
        xml: String,
        tag: String,
    ): String? {
        val regex = Regex("<[^>]*$tag[^>]*>(.*?)</[^>]*$tag[^>]*>", RegexOption.DOT_MATCHES_ALL)
        return regex
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private val rfc1123: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)

    private fun parseHttpDate(value: String): Instant? =
        runCatching { ZonedDateTime.parse(value, rfc1123).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(value) }.getOrNull()
}

data class WebDavEntry(
    val path: String,
    val rawHref: String = path,
    val size: Long,
    val lastModified: Instant?,
    val etag: String?,
    val isFolder: Boolean,
)
