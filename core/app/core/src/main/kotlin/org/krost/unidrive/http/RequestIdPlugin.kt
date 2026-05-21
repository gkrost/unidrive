package org.krost.unidrive.http

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.util.AttributeKey
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
 */
val RequestId =
    createClientPlugin("UnidriveRequestId") {
        val log = LoggerFactory.getLogger("org.krost.unidrive.http.RequestId")

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
            // UD-773: append payload byte counts to the close line so log analysers can
            // derive bytes/sec throughput per provider/host instead of just ops/sec. Goes
            // AFTER the `(Nms)` chunk so existing regexes that end at `\d+ms\)` still match.
            // up = request body size, dn = response body size; `?` when Content-Length is
            // absent (streaming bodies). Suppress the entire segment when both are zero
            // (most metadata calls on streamed paths) to keep the log readable.
            val upBytes = request.content.contentLength
            val dnBytes = response.contentLength()
            val sizeSegment = formatSizeSegment(upBytes, dnBytes)
            val level = if (status >= HttpStatusCode.BadRequest.value) "WARN" else "DEBUG"
            if (level == "WARN") {
                log.warn(
                    "← req={} {} {} ({}ms){}",
                    id ?: "--------",
                    status,
                    response.status.description,
                    elapsed,
                    sizeSegment,
                )
            } else {
                log.debug(
                    "← req={} {} ({}ms){}",
                    id ?: "--------",
                    status,
                    elapsed,
                    sizeSegment,
                )
            }
        }
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
