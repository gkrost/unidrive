package org.krost.unidrive.hidrive

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.hidrive.model.HiDriveItem
import org.krost.unidrive.hidrive.model.HiDriveUserInfo
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class HiDriveApiService(
    private val config: HiDriveConfig,
    private val tokenProvider: suspend () -> String,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(HiDriveApiService::class.java)

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
    private val baseUrl = HiDriveConfig.API_BASE_URL
    private var homePath: String? = null

    suspend fun getHome(): String {
        homePath?.let { return it }
        val response = authenticatedGet("$baseUrl/user/me", mapOf("fields" to "home"))
        val user = json.decodeFromString<HiDriveUserInfo>(response)
        val home = user.homePath ?: throw HiDriveApiException("Could not determine home directory")
        log.debug("Home directory resolved: {}", home)
        homePath = home
        return home
    }

    internal fun toAbsolutePath(
        relativePath: String,
        home: String,
    ): String {
        val clean = relativePath.removePrefix("/")
        return if (clean.isEmpty()) home else "$home/$clean"
    }

    internal fun toRelativePath(
        absolutePath: String,
        home: String,
    ): String {
        if (!absolutePath.startsWith(home)) return absolutePath
        val relative = absolutePath.removePrefix(home)
        return if (relative.isEmpty()) "/" else relative
    }

    suspend fun listDirectory(relativePath: String): List<HiDriveItem> {
        val home = getHome()
        val absPath = toAbsolutePath(relativePath, home)
        val response =
            authenticatedGet(
                "$baseUrl/dir",
                mapOf(
                    "path" to absPath,
                    "fields" to
                        "members.id,members.name,members.path,members.type,members.size,members.mtime,members.chash,members.mime_type",
                    "members" to "all",
                ),
            )
        val dir = json.decodeFromString<HiDriveItem>(response)
        return dir.members ?: emptyList()
    }

    suspend fun getMetadata(relativePath: String): HiDriveItem {
        val home = getHome()
        val absPath = toAbsolutePath(relativePath, home)
        val response =
            authenticatedGet(
                "$baseUrl/meta",
                mapOf(
                    "path" to absPath,
                    "fields" to "id,name,path,type,size,mtime,created,chash,mime_type",
                ),
            )
        return json.decodeFromString<HiDriveItem>(response)
    }

    suspend fun downloadFile(
        relativePath: String,
        destination: Path,
    ) {
        log.debug("Download: {}", relativePath)
        val home = getHome()
        val absPath = toAbsolutePath(relativePath, home)
        // UD-332: mirror the closed UD-329 OneDrive fix — switch from
        // `httpClient.get(url)` + `response.body<ByteReadChannel>()` (Ktor 3
        // buffers the entire response into a single `byte[contentLength]`
        // before exposing the channel; >2 GiB files OOM at allocation time)
        // to `prepareGet(url).execute { ... }` + `response.bodyAsChannel()`
        // (true streaming, only the 8 KiB ring buffer below holds bytes).
        val statement =
            httpClient.prepareGet("$baseUrl/file") {
                bearerAuth(tokenProvider())
                parameter("path", absPath)
            }
        statement.execute { response ->
            if (response.status == HttpStatusCode.Unauthorized) {
                throw AuthenticationException("Authentication failed (401)")
            }
            if (!response.status.isSuccess()) {
                throw HiDriveApiException(
                    "Download failed: ${response.status} - ${response.bodyAsText()}",
                    response.status.value,
                )
            }

            // UD-333: mirror the closed UD-231 OneDrive fix. CDN edge nodes
            // occasionally return HTTP 200 with `Content-Type: text/html` —
            // captive-portal pages, throttle redirects, expired-URL login
            // pages — and the raw HTML would otherwise stream straight into
            // the destination file at the matching byte size. The UD-226
            // NUL-stub sweep doesn't catch this because HTML is non-NUL
            // content. Guard before any write hits disk.
            val contentType = response.contentType()
            if (contentType != null && contentType.match(ContentType.Text.Html)) {
                val snippet = readBoundedErrorBody(response, maxBytes = 4096).take(200)
                throw java.io.IOException(
                    "Download returned HTML instead of file bytes (status=${response.status.value}, " +
                        "Content-Type=$contentType): $snippet",
                )
            }

            val channel: ByteReadChannel = response.bodyAsChannel()
            withContext(Dispatchers.IO) {
                Files.createDirectories(destination.parent)
                Files.newOutputStream(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = channel.readAvailable(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    suspend fun uploadFile(
        localPath: Path,
        remoteDir: String,
        fileName: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): HiDriveItem {
        val home = getHome()
        val absDir = toAbsolutePath(remoteDir, home)
        val fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
        log.debug("Upload: {}/{} ({} bytes)", remoteDir, fileName, fileSize)

        val response =
            httpClient.put("$baseUrl/file") {
                bearerAuth(tokenProvider())
                parameter("dir", absDir)
                parameter("name", fileName)
                setBody(
                    object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                        override val contentLength = fileSize
                        override val contentType = ContentType.Application.OctetStream

                        override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                            withContext(Dispatchers.IO) {
                                Files.newInputStream(localPath).use { input ->
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

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401)")
        }
        if (!response.status.isSuccess()) {
            throw HiDriveApiException("Upload failed: ${response.status} - ${response.bodyAsText()}", response.status.value)
        }

        onProgress?.invoke(fileSize, fileSize)
        return json.decodeFromString<HiDriveItem>(response.bodyAsText())
    }

    suspend fun deleteFile(relativePath: String) {
        log.debug("Delete: {}", relativePath)
        val home = getHome()
        val absPath = toAbsolutePath(relativePath, home)
        val response =
            httpClient.delete("$baseUrl/file") {
                bearerAuth(tokenProvider())
                parameter("path", absPath)
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401)")
        }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.NotFound) {
            throw HiDriveApiException("Delete file failed: ${response.status}", response.status.value)
        }
    }

    suspend fun deleteDirectory(relativePath: String) {
        val home = getHome()
        val absPath = toAbsolutePath(relativePath, home)
        val response =
            httpClient.delete("$baseUrl/dir") {
                bearerAuth(tokenProvider())
                parameter("path", absPath)
                parameter("recursive", "true")
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401)")
        }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.NotFound) {
            throw HiDriveApiException("Delete directory failed: ${response.status}", response.status.value)
        }
    }

    suspend fun createFolder(relativePath: String): HiDriveItem {
        log.debug("mkdir: {}", relativePath)
        val home = getHome()
        val absPath = toAbsolutePath(relativePath, home)
        val response =
            httpClient.post("$baseUrl/dir") {
                bearerAuth(tokenProvider())
                parameter("path", absPath)
                parameter("on_exist", "autoname")
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401)")
        }
        if (!response.status.isSuccess()) {
            throw HiDriveApiException("Create folder failed: ${response.status} - ${response.bodyAsText()}", response.status.value)
        }

        return json.decodeFromString<HiDriveItem>(response.bodyAsText())
    }

    suspend fun moveDirectory(
        fromRelative: String,
        toRelative: String,
    ): HiDriveItem {
        log.debug("Move dir: {} -> {}", fromRelative, toRelative)
        val home = getHome()
        val srcAbs = toAbsolutePath(fromRelative, home)
        val dstAbs = toAbsolutePath(toRelative, home)
        val response =
            httpClient.post("$baseUrl/dir/move") {
                bearerAuth(tokenProvider())
                parameter("src", srcAbs)
                parameter("dst", dstAbs)
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401)")
        }
        if (!response.status.isSuccess()) {
            throw HiDriveApiException("Move directory failed: ${response.status} - ${response.bodyAsText()}", response.status.value)
        }

        return json.decodeFromString<HiDriveItem>(response.bodyAsText())
    }

    suspend fun moveFile(
        fromRelative: String,
        toRelative: String,
    ): HiDriveItem {
        log.debug("Move: {} -> {}", fromRelative, toRelative)
        val home = getHome()
        val srcAbs = toAbsolutePath(fromRelative, home)
        val dstAbs = toAbsolutePath(toRelative, home)

        // HiDrive has no /file/move endpoint — use copy + delete
        val dstDir = dstAbs.substringBeforeLast("/")
        val dstName = dstAbs.substringAfterLast("/")
        val copyResponse =
            httpClient.post("$baseUrl/file/copy") {
                bearerAuth(tokenProvider())
                parameter("src", srcAbs)
                parameter("dst", dstDir)
                parameter("dst_name", dstName)
                parameter("on_exist", "overwrite")
            }

        if (copyResponse.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401)")
        }
        if (!copyResponse.status.isSuccess()) {
            throw HiDriveApiException("Copy file failed: ${copyResponse.status} - ${copyResponse.bodyAsText()}", copyResponse.status.value)
        }

        val result = json.decodeFromString<HiDriveItem>(copyResponse.bodyAsText())

        // Delete the original
        deleteFile(fromRelative)

        return result
    }

    suspend fun getQuota(): QuotaInfo {
        val response = authenticatedGet("$baseUrl/user/me", mapOf("fields" to "quota"))
        val user = json.decodeFromString<HiDriveUserInfo>(response)
        val quota = user.quota ?: return QuotaInfo(0, 0, 0)
        return QuotaInfo(
            total = quota.limit,
            used = quota.used,
            remaining = quota.limit - quota.used,
        )
    }

    suspend fun listRecursive(relativePath: String = "/"): List<HiDriveItem> {
        val items = mutableListOf<HiDriveItem>()
        listRecursiveInternal(relativePath, items)
        log.debug("Listing: {} items total", items.size)
        return items
    }

    private suspend fun listRecursiveInternal(
        relativePath: String,
        accumulator: MutableList<HiDriveItem>,
    ) {
        val children = listDirectory(relativePath)
        val home = getHome()
        for (child in children) {
            accumulator.add(child)
            if (child.type == "dir") {
                val childRelative = toRelativePath(child.path ?: "", home)
                listRecursiveInternal(childRelative, accumulator)
            }
        }
    }

    private suspend fun authenticatedGet(
        url: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val response =
            httpClient.get(url) {
                bearerAuth(tokenProvider())
                params.forEach { (k, v) -> parameter(k, v) }
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException("Authentication failed (401): ${response.bodyAsText()}")
        }
        if (!response.status.isSuccess()) {
            throw HiDriveApiException("API error: ${response.status} - ${response.bodyAsText()}", response.status.value)
        }

        return response.bodyAsText()
    }

    // UD-333: bounded read off the raw channel for diagnostic purposes (HTML
    // body sniff in downloadFile). Mirrors the OneDrive UD-293 helper —
    // bodyAsText() would materialise the entire body into a String, OOM-ing
    // when a CDN attaches a multi-GB body to a fake 200/text-html response.
    private suspend fun readBoundedErrorBody(
        response: HttpResponse,
        maxBytes: Int = 4096,
    ): String =
        try {
            val channel = response.bodyAsChannel()
            val buf = ByteArray(maxBytes)
            val read = channel.readAvailable(buf, 0, maxBytes).coerceAtLeast(0)
            String(buf, 0, read, Charsets.UTF_8)
        } catch (_: Exception) {
            "<body unavailable>"
        }

    override fun close() {
        httpClient.close()
    }
}

class HiDriveApiException(
    message: String,
    val statusCode: Int = 0,
) : ProviderException(message)
