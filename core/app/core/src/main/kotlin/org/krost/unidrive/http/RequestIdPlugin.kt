package org.krost.unidrive.http

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpStatusCode
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
            val id =
                response.call.request.attributes
                    .getOrNull(RequestIdKey)
            val elapsed =
                response.responseTime.timestamp - response.requestTime.timestamp
            val status = response.status.value
            val level = if (status >= HttpStatusCode.BadRequest.value) "WARN" else "DEBUG"
            if (level == "WARN") {
                log.warn(
                    "← req={} {} {} ({}ms)",
                    id ?: "--------",
                    status,
                    response.status.description,
                    elapsed,
                )
            } else {
                log.debug(
                    "← req={} {} ({}ms)",
                    id ?: "--------",
                    status,
                    elapsed,
                )
            }
        }
    }

/**
 * Strip query string from a URL for logging. Query params can leak bearer
 * tokens and access hashes for some providers (e.g. SAS-style URLs).
 */
internal fun redact(url: String): String {
    val q = url.indexOf('?')
    return if (q < 0) url else url.substring(0, q) + "?<redacted>"
}
