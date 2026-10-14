package org.krost.unidrive.dropbox

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.ProviderException
import org.krost.unidrive.http.EndpointClassifier
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.http.RequestId
import org.krost.unidrive.http.RequestMetricsSink
import org.krost.unidrive.http.RequestMetricsTap
import org.krost.unidrive.http.readBoundedErrorBody
import org.krost.unidrive.http.truncateErrorBody
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * A refusal of the Dropbox API. [summary] is the `error_summary` (`path/not_found/..`, `path/conflict/file/..`), the
 * stable machine-readable part; [error] the structured `error` object when the body had one.
 */
class DropboxApiException(
    message: String,
    val status: Int,
    val summary: String,
    val error: JsonObject? = null,
    requestId: String? = null,
) : ProviderException(message, requestId = requestId, httpStatus = status) {
    /** True when the summary starts with [prefix] (`path/not_found`, `to/conflict`, `reset`). */
    fun isA(prefix: String): Boolean = summary.startsWith(prefix)
}

/**
 * The Dropbox HTTP API (v2): RPC calls (JSON in, JSON out) on the API host, uploads and downloads on the content host
 * with their arguments in the `Dropbox-API-Arg` header, and the long-poll on the notify host, which takes no token.
 *
 * Every authenticated request goes through [send]: a slot from the shared [budget], a forced token refresh and one
 * retry on 401, and the server's own wait on 429 and 503 (Dropbox's `Retry-After`, or `retry_after` in the body).
 * Anything else reaches the caller as it is, so a write that may have taken effect is never repeated here.
 */
class DropboxApi(
    private val config: DropboxConfig,
    private val accessToken: suspend (forceRefresh: Boolean) -> String,
    engine: HttpClientEngine? = null,
    private val budget: HttpRetryBudget = HttpRetryBudget(maxConcurrency = MAX_CONCURRENCY),
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(DropboxApi::class.java)
    private val json = org.krost.unidrive.UnidriveJson
    private val metrics = RequestMetricsTap()

    val httpClient: HttpClient = dropboxHttpClient(metrics, config.userAgent, engine)

    /**
     * The `Dropbox-API-Path-Root` header value, set at sign-in when the account's root namespace is not its home
     * namespace (a team space): paths then start at the team root, so team folders are visible too.
     */
    @Volatile
    var pathRoot: String? = null

    fun attachRequestMetrics(sink: RequestMetricsSink?) {
        metrics.sink = sink
    }

    override fun close() = httpClient.close()

    /** An RPC call on the API host. [args] null sends the literal `null` the argument-less endpoints take. */
    suspend fun rpc(
        endpoint: String,
        args: JsonElement? = null,
    ): JsonObject {
        val response =
            send("${config.apiBase}/$endpoint") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonElement.serializer(), args ?: JsonNull))
            }
        return parseOrThrow(response, endpoint)
    }

    /** An upload-style call on the content host: [args] in the header, [body] built afresh for every attempt. */
    suspend fun content(
        endpoint: String,
        args: JsonElement,
        requestTimeoutMs: Long? = null,
        body: () -> Any,
    ): JsonObject {
        val response =
            send("${config.contentBase}/$endpoint") {
                header(API_ARG, headerSafeJson(args))
                if (requestTimeoutMs != null) timeout { requestTimeoutMillis = requestTimeoutMs }
                contentType(ContentType.Application.OctetStream)
                setBody(body())
            }
        return parseOrThrow(response, endpoint)
    }

    /**
     * Downloads [path] (a path or an `id:`) to [destination] through a sibling staging file that is moved over it only
     * when complete. Returns the file's metadata (the `Dropbox-API-Result` header) and the bytes written.
     */
    suspend fun download(
        path: String,
        destination: Path,
    ): Pair<JsonObject, Long> {
        var refreshed = false
        var throttleAttempts = 0
        while (true) {
            budget.awaitSlot()
            val statement =
                httpClient.prepareRequest {
                    url("${config.contentBase}/files/download")
                    method = HttpMethod.Post
                    bearerAuth(accessToken(refreshed))
                    pathRoot?.let { header(PATH_ROOT, it) }
                    header(API_ARG, headerSafeJson(obj("path" to path)))
                }
            val outcome: Fetch =
                statement.execute { response ->
                    when {
                        response.status == HttpStatusCode.Unauthorized && !refreshed -> Fetch.RetryAuth
                        isThrottle(response.status) && throttleAttempts < MAX_THROTTLE_ATTEMPTS ->
                            Fetch.Wait(backoffMs(response, readBoundedErrorBody(response), throttleAttempts))
                        !response.status.isSuccess() -> throw failure(response, readBoundedErrorBody(response), "files/download")
                        else -> {
                            budget.recordSuccess()
                            val meta =
                                response.headers[API_RESULT]?.let { json.parseToJsonElement(it).jsonObject }
                                    ?: throw ProviderException("Dropbox download of '$path' came without its metadata")
                            Fetch.Done(meta, writeStaged(response, response.bodyAsChannel(), destination, path))
                        }
                    }
                }
            when (outcome) {
                Fetch.RetryAuth -> refreshed = true
                is Fetch.Wait -> {
                    budget.recordThrottle(outcome.ms)
                    delay(outcome.ms)
                    throttleAttempts++
                }
                is Fetch.Done -> return outcome.meta to outcome.bytes
            }
        }
    }

    /**
     * One long-poll on the notify host: blocks up to [timeoutSeconds] until something under [cursor] changes. Takes no
     * token. The answer carries `changes` and, when Dropbox wants the client to wait before the next one, `backoff`.
     */
    suspend fun longpoll(
        cursor: String,
        timeoutSeconds: Int,
    ): JsonObject {
        val response =
            httpClient.request {
                url("${config.notifyBase}/files/list_folder/longpoll")
                method = HttpMethod.Post
                // Dropbox adds up to 90 s of jitter to the requested wait.
                timeout {
                    requestTimeoutMillis = (timeoutSeconds + 120) * 1000L
                    socketTimeoutMillis = (timeoutSeconds + 120) * 1000L
                }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonElement.serializer(), obj("cursor" to cursor, "timeout" to timeoutSeconds)))
            }
        return parseOrThrow(response, "files/list_folder/longpoll")
    }

    private suspend fun send(
        endpoint: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        var refreshed = false
        var throttleAttempts = 0
        while (true) {
            budget.awaitSlot()
            val response =
                httpClient.request {
                    url(endpoint)
                    method = HttpMethod.Post
                    bearerAuth(accessToken(refreshed))
                    pathRoot?.let { header(PATH_ROOT, it) }
                    configure()
                }
            if (response.status == HttpStatusCode.Unauthorized && !refreshed) {
                log.info("Dropbox answered 401 on {}; refreshing the token and retrying once", endpoint.substringAfter("/2/"))
                refreshed = true
                continue
            }
            if (isThrottle(response.status) && throttleAttempts < MAX_THROTTLE_ATTEMPTS) {
                val waitMs = backoffMs(response, response.bodyAsText(), throttleAttempts)
                log.warn("Dropbox answered {} on {}; waiting {} ms", response.status.value, endpoint.substringAfter("/2/"), waitMs)
                budget.recordThrottle(waitMs)
                delay(waitMs)
                throttleAttempts++
                continue
            }
            if (response.status.isSuccess()) budget.recordSuccess()
            return response
        }
    }

    private suspend fun parseOrThrow(
        response: HttpResponse,
        endpoint: String,
    ): JsonObject {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw failure(response, body, endpoint)
        if (body.isBlank() || body == "null") return JsonObject(emptyMap())
        return json.parseToJsonElement(body).jsonObject
    }

    private fun failure(
        response: HttpResponse,
        body: String,
        endpoint: String,
    ): ProviderException {
        val requestId = response.headers[REQUEST_ID]
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val summary = parsed?.get("error_summary")?.jsonPrimitive?.contentOrNull ?: ""
        val message = "Dropbox $endpoint failed (${response.status.value}): ${truncateErrorBody(summary.ifEmpty { body })}"
        if (response.status == HttpStatusCode.Unauthorized) return AuthenticationException(message, requestId = requestId)
        return DropboxApiException(message, response.status.value, summary, parsed?.get("error") as? JsonObject, requestId)
    }

    private fun isThrottle(status: HttpStatusCode): Boolean = status.value == 429 || status.value == 503

    // Dropbox's own wait first (the Retry-After header, or retry_after in the body), else exponential backoff.
    private fun backoffMs(
        response: HttpResponse,
        body: String,
        attempt: Int,
    ): Long {
        val header = response.headers["Retry-After"]?.trim()?.toLongOrNull()
        val inBody =
            runCatching {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("retry_after")?.jsonPrimitive?.intOrNull?.toLong()
            }.getOrNull()
        val seconds = header ?: inBody
        return if (seconds != null) {
            (seconds * 1000L).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
        } else {
            (MIN_WAIT_MS shl attempt.coerceAtMost(6)).coerceAtMost(MAX_WAIT_MS)
        }
    }

    @Suppress("TooGenericExceptionCaught") // whatever fails, the staging file goes and the failure is rethrown unchanged
    private suspend fun writeStaged(
        response: HttpResponse,
        channel: ByteReadChannel,
        destination: Path,
        what: String,
    ): Long =
        withContext(Dispatchers.IO) {
            Files.createDirectories(destination.parent)
            val staging =
                destination.parent.resolve(org.krost.unidrive.io.stagingSiblingName(destination.fileName.toString(), ".unidrive-tmp"))
            try {
                val written =
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
                if (expected != null && expected >= 0L && written != expected) {
                    throw java.io.IOException("Truncated Dropbox download of '$what': got $written of $expected bytes")
                }
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                written
            } catch (e: Exception) {
                Files.deleteIfExists(staging)
                throw e
            }
        }

    companion object {
        const val API_ARG = "Dropbox-API-Arg"
        const val API_RESULT = "Dropbox-API-Result"
        const val PATH_ROOT = "Dropbox-API-Path-Root"
        private const val REQUEST_ID = "X-Dropbox-Request-Id"
        const val MAX_CONCURRENCY = 8
        private const val MAX_THROTTLE_ATTEMPTS = 6
        private const val MIN_WAIT_MS = 1_000L
        private const val MAX_WAIT_MS = 300_000L

        /**
         * [value] as JSON fit for an HTTP header: Dropbox requires every character above 0x7E (DEL and all of
         * non-ASCII) as a `\uXXXX` escape, or the header is refused or misread.
         */
        fun headerSafeJson(value: JsonElement): String {
            val raw = org.krost.unidrive.UnidriveJson.encodeToString(JsonElement.serializer(), value)
            val out = StringBuilder(raw.length + 16)
            for (c in raw) {
                if (c.code > 0x7E) out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
            return out.toString()
        }

        /** A JSON object of the given fields; null values are left out. */
        fun obj(vararg fields: Pair<String, Any?>): JsonObject =
            JsonObject(
                fields
                    .filter { it.second != null }
                    .associate { (k, v) ->
                        k to
                            when (v) {
                                is JsonElement -> v
                                is String -> kotlinx.serialization.json.JsonPrimitive(v)
                                is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                                else -> error("unsupported JSON value ${v!!::class.simpleName}")
                            }
                    },
            )
    }
}

private sealed interface Fetch {
    data object RetryAuth : Fetch

    data class Wait(val ms: Long) : Fetch

    data class Done(val meta: JsonObject, val bytes: Long) : Fetch
}

/** The words of the Dropbox API this client calls; every endpoint path is static, the data travels in bodies and headers. */
internal object DropboxEndpoints {
    val classifier =
        EndpointClassifier(
            apiHosts =
                setOf(DropboxConfig.API_BASE, DropboxConfig.CONTENT_BASE, DropboxConfig.NOTIFY_BASE).mapTo(HashSet()) {
                    java.net.URI(it).host
                },
            literals =
                setOf(
                    "2", "files", "list_folder", "continue", "get_latest_cursor", "longpoll", "get_metadata", "download", "upload",
                    "upload_session", "start", "append_v2", "finish", "delete_v2", "move_v2", "create_folder_v2", "users",
                    "get_space_usage", "get_current_account", "sharing", "create_shared_link_with_settings", "list_shared_links",
                    "revoke_shared_link", "oauth2", "token",
                ),
        )
}

internal fun dropboxHttpClient(
    tap: RequestMetricsTap,
    userAgent: String,
    engine: HttpClientEngine? = null,
): HttpClient {
    val configure: HttpClientConfig<*>.() -> Unit = {
        install(HttpTimeout) {
            connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
            socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
            requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
        }
        install(UserAgent) { agent = userAgent }
        install(RequestId) {
            metrics = tap
            provider = "dropbox"
            endpoints = DropboxEndpoints.classifier
        }
    }
    return if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
}
