package org.krost.unidrive.http

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * UD-255: correlation id for every outbound HTTP request.
 *
 * Each request gets an 8-char id injected into the `X-Unidrive-Request-Id`
 * header. The plugin also logs a DEBUG line on request send and on
 * response receipt, carrying `req=<id>`, the HTTP method, a redacted URL
 * (query string dropped), status code, and latency. A single grep
 * `req=<id>` gives the request/response pair plus any provider-level
 * WARN/ERROR that references the id in its message text.
 *
 * No MDC propagation: Ktor's request/response pipelines run on dispatchers
 * that don't consistently carry MDC across suspensions, so we attach the
 * id to a per-request attribute and surface it in log messages instead.
 * Callers that need a correlation id in their own log lines can pull it
 * via [requestId].
 */
val RequestIdKey: AttributeKey<String> = AttributeKey("unidrive.requestId")

/**
 * Accessor for the correlation id assigned to a request. Returns `null`
 * if called before the plugin has run, or for clients that don't install
 * the plugin.
 */
fun HttpRequestBuilder.requestId(): String? = attributes.getOrNull(RequestIdKey)

/**
 * Build the Ktor client plugin. Install with:
 * ```
 * HttpClient { install(RequestId) }
 * ```
 * To also report every attempt (see [RequestAttempt]) to whatever the daemon attaches to [RequestIdConfig.metrics]:
 * ```
 * HttpClient { install(RequestId) { metrics = tap; provider = "x"; endpoints = classifier } }
 * ```
 * With no sink on the tap, the metrics part costs one volatile read per request.
 */
@Suppress("TooGenericExceptionCaught") // the failure is classified, recorded and rethrown unchanged
val RequestId =
    createClientPlugin("UnidriveRequestId", ::RequestIdConfig) {
        val log = LoggerFactory.getLogger("org.krost.unidrive.http.RequestId")
        val metrics = pluginConfig.metrics
        val provider = pluginConfig.provider
        val endpoints = pluginConfig.endpoints
        val profile = pluginConfig.profile
        val serverRequestIdHeaders = pluginConfig.serverRequestIdHeaders
        val retries = RetryTracker()

        // Attempt metrics. The Send hook wraps one trip to the engine: it sees the failure of a request that never
        // got a response, and the response whose body is consumed later (its job completes then). Redirects and
        // provider-level retries are further requests, each its own attempt.
        on(Send) { request ->
            val sink = metrics?.sink ?: return@on proceed(request)
            val startedAtMs = System.currentTimeMillis()
            val startNanos = System.nanoTime()
            val method = request.method.value
            val endpointClass = endpoints.classify(method, request.url.host, "/" + request.url.encodedPathSegments.joinToString("/"))
            val retryKey = RetryTracker.keyOf(method + ' ' + request.url.buildString())
            val retryNo = retries.retryNo(retryKey, startNanos)

            fun report(
                outcome: AttemptOutcome,
                headersMs: Long?,
                bytesUp: Long?,
                bytesDown: Long?,
                throttled: Boolean,
            ) {
                val endNanos = System.nanoTime()
                retries.ended(retryKey, outcome.retryable, endNanos)
                // The sink promises not to throw; a broken one must still never fail the request that was measured.
                runCatching {
                    sink.record(
                        RequestAttempt(
                            startedAtMs = startedAtMs,
                            provider = provider,
                            endpointClass = endpointClass,
                            method = method,
                            outcome = outcome,
                            headersMs = headersMs,
                            totalMs = nanosToMs(endNanos - startNanos),
                            bytesUp = bytesUp,
                            bytesDown = bytesDown,
                            retryNo = retryNo,
                            throttled = throttled,
                        ),
                    )
                }
            }

            val call =
                try {
                    proceed(request)
                } catch (e: Throwable) {
                    report(AttemptOutcome.ofFailure(e), headersMs = null, bytesUp = null, bytesDown = null, throttled = false)
                    throw e
                }
            val headersMs = nanosToMs(System.nanoTime() - startNanos)
            val response = call.response
            val status = response.status.value
            val throttled = status == HttpStatusCode.TooManyRequests.value || response.headers.contains(HttpHeaders.RetryAfter)
            val bytesUp = call.request.content.contentLength
            fun finish(cause: Throwable?) {
                val outcome = if (cause == null) AttemptOutcome.ofStatus(status) else AttemptOutcome.ofFailure(cause)
                report(outcome, headersMs, bytesUp, if (cause == null) response.contentLength() else null, throttled)
            }
            val job = response.coroutineContext[Job]
            if (job == null) finish(null) else job.invokeOnCompletion { finish(it) }
            call
        }

        onRequest { request, _ ->
            val id = UUID.randomUUID().toString().take(8)
            request.attributes.put(RequestIdKey, id)
            request.headers.append("X-Unidrive-Request-Id", id)
            log.debug(
                "→ req={} {} {}",
                id,
                request.method.value,
                redact(request.url.buildString()),
            )
        }

        onResponse { response ->
            val request = response.call.request
            val id = request.attributes.getOrNull(RequestIdKey)
            val elapsed =
                response.responseTime.timestamp - response.requestTime.timestamp
            val status = response.status.value
            // #644: the profile travels in the message itself, independent of the thread's MDC (hydration
            // downloads and other worker threads have none), so a shared log splits per account. The server's
            // own request id (first present header of [RequestIdConfig.serverRequestIdHeaders]) rides along
            // so a slow call can be handed to the provider's support. Both segments are empty when unknown,
            // which keeps the line byte for byte the old format for clients that do not name them.
            // UD-773: append payload byte counts to the close line so log analysers can
            // derive bytes/sec throughput per provider/host instead of just ops/sec. Goes
            // AFTER the `(Nms)` chunk so existing regexes that end at `\d+ms\)` still match.
            // up = request body size, dn = response body size; `?` when Content-Length is
            // absent (streaming bodies). Suppress the entire segment when both are zero
            // (most metadata calls on streamed paths) to keep the log readable.
            val upBytes = request.content.contentLength
            val dnBytes = response.contentLength()
            val sizeSegment = formatSizeSegment(upBytes, dnBytes)
            val profileSegment = profile?.let { " [$it]" } ?: ""
            val serverId = serverRequestIdHeaders.firstNotNullOfOrNull { response.headers[it] }
            val srvSegment = serverId?.let { " srv=$it" } ?: ""
            val level = if (status >= HttpStatusCode.BadRequest.value) "WARN" else "DEBUG"
            if (level == "WARN") {
                log.warn(
                    "← req={}{}{} {} {} ({}ms){}",
                    id ?: "--------",
                    profileSegment,
                    srvSegment,
                    status,
                    response.status.description,
                    elapsed,
                    sizeSegment,
                )
            } else {
                log.debug(
                    "← req={}{}{} {} ({}ms){}",
                    id ?: "--------",
                    profileSegment,
                    srvSegment,
                    status,
                    elapsed,
                    sizeSegment,
                )
            }
        }
    }

/** Settings of [RequestId]; every one is optional. */
class RequestIdConfig {
    /** Where attempts are reported once the tap has a sink; null for a client that reports nothing. */
    var metrics: RequestMetricsTap? = null

    /** The provider id written into every attempt. */
    var provider: String = "unknown"

    /** Names the endpoints of this client's API; the default hides every path ([EndpointClassifier.OPAQUE]). */
    var endpoints: EndpointClassifier = EndpointClassifier.OPAQUE

    /**
     * The profile name printed into the response line (`[profile]`); null (the default) keeps the line in
     * the old format. The name travels in the message because the MDC does not reach every worker thread.
     */
    var profile: String? = null

    /**
     * Response headers that may carry the server's own request id, tried in order; the first present
     * value is printed as `srv=<id>` (empty when none). Redaction rule as ever: the URL stays
     * query-free, the header value comes from the response, not from anything the request carried.
     */
    var serverRequestIdHeaders: List<String> = emptyList()
}

/**
 * UD-773: format the `up=N dn=M` segment for the RequestId close line.
 *
 * Suppress (return empty) when:
 * - both byte counts are explicit zero (a true metadata call — no body either way), OR
 * - both byte counts are null (Content-Length absent on both sides — no signal anyway).
 *
 * Otherwise emit `" up=N dn=M"` with `?` substituted for any single null (e.g. a known
 * upload size with a streaming response → `up=12345 dn=?`, signalling the streaming
 * response distinctly from a zero-byte one).
 */
internal fun formatSizeSegment(
    up: Long?,
    dn: Long?,
): String {
    if (up == null && dn == null) return ""
    if (up == 0L && dn == 0L) return ""
    val upStr = up?.toString() ?: "?"
    val dnStr = dn?.toString() ?: "?"
    return " up=$upStr dn=$dnStr"
}

/**
 * Strip query string from a URL for logging. Query params can leak bearer
 * tokens and access hashes for some providers (e.g. SAS-style URLs).
 */
internal fun redact(url: String): String {
    val q = url.indexOf('?')
    return if (q < 0) url else url.substring(0, q) + "?<redacted>"
}
