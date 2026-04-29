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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.QuotaInfo
import org.slf4j.LoggerFactory
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
// UD-291: open so tests can inject a throwing override on quota / propfind
// without a heavyweight HTTP-stub harness. No behaviour change for production
// callers; the only consumers are WebDavProvider and the in-tree tests.
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

    private val httpClient =
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
                    .build()
            HttpClient(io.ktor.client.engine.apache5.Apache5) {
                engine {
                    customizeClient {
                        setConnectionManager(connManager)
                    }
                }
                commonConfig()
            }
        } else {
            HttpClient(io.ktor.client.engine.cio.CIO, commonConfig)
        }

    override fun close() = httpClient.close()

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
    ): Long {
        log.debug("Download: {}", remotePath)
        val url = resourceUrl(remotePath)
        val response = httpClient.get(url)
        checkResponse(response, url)
        val channel: ByteReadChannel = response.body()
        var written = 0L
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
        return written
    }

    /** Upload [localPath] as resource at [remotePath]. Returns a [WebDavEntry]. */
    suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): WebDavEntry {
        val fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
        log.debug("Upload: {} ({} bytes)", remotePath, fileSize)
        val url = resourceUrl(remotePath)
        ensureParentCollections(remotePath)
        val response =
            httpClient.put(url) {
                setBody(
                    object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                        override val contentLength = fileSize
                        override val contentType = ContentType.Application.OctetStream

                        override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                            withContext(Dispatchers.IO) {
                                java.nio.file.Files.newInputStream(localPath).use { input ->
                                    val buf = ByteArray(65536)
                                    var n: Int
                                    while (input.read(buf).also { n = it } != -1) {
                                        channel.writeFully(buf, 0, n)
                                    }
                                }
                            }
                            channel.flushAndClose()
                        }
                    },
                )
            }
        checkResponse(response, url)
        onProgress?.invoke(fileSize, fileSize)
        val etag = response.headers["ETag"]?.trim('"')
        val now = Instant.now()
        return WebDavEntry(path = remotePath, size = fileSize, lastModified = now, etag = etag, isFolder = false)
    }

    /** Delete resource or empty collection at [remotePath]. 404 is silently ignored. */
    suspend fun delete(remotePath: String) {
        log.debug("Delete: {}", remotePath)
        val url = resourceUrl(remotePath)
        val response = httpClient.delete(url)
        if (response.status != HttpStatusCode.NotFound) checkResponse(response, url)
    }

    /** Create a collection (directory) at [remotePath]. */
    suspend fun mkcol(remotePath: String) {
        val normalized = normalizeCollectionKey(remotePath)
        if (normalized in knownCollections) return
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
        log.debug("Move: {} -> {}", fromPath, toPath)
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
    suspend fun listAll(remotePath: String = ""): List<WebDavEntry> {
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
        }
        return results
    }

    /**
     * PROPFIND Depth:1 on [remotePath]. Returns all direct children plus the
     * resource itself.
     *
     * UD-291: `open` so tests can override with a no-op fake — used together
     * with the `quotaPropfind` override to validate the catch behaviour in
     * [WebDavProvider.quota] without an HTTP-stub harness. (Production calls
     * propfind from `WebDavProvider.authenticate` to flip `isAuthenticated`
     * to `true`; the test fake makes that a free no-op.)
     */
    open suspend fun propfind(remotePath: String): List<WebDavEntry> {
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
        return parsePropfindResponse(response.bodyAsText(), remotePath)
    }

    /**
     * RFC 4331 PROPFIND Depth:0 on the root collection to retrieve
     * `quota-available-bytes` and `quota-used-bytes`. Returns null when the
     * server does not advertise these properties (e.g. Synology DSM 7.x,
     * Apache mod_dav).
     *
     * UD-291: `open` so tests can override to verify that
     * [WebDavProvider.quota] logs + propagates cancellation cleanly.
     */
    open suspend fun quotaPropfind(): QuotaInfo? {
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
        return parseQuotaPropfindXml(response.bodyAsText())
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
