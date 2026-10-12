package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.krost.unidrive.telemetry.RequestMetricsRecorder
import org.krost.unidrive.telemetry.RollupQuery
import org.krost.unidrive.telemetry.RollupResult
import org.krost.unidrive.telemetry.RollupRow
import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * IPC verb handler for `metrics.query` (READ class, `IpcAuth.VERB_CLASSES`): the per-minute roll-ups of the
 * request-attempt metrics of this daemon's profile (docs/dev/specs/request-metrics.md).
 *
 * Request: `{"verb":"metrics.query", "from_ms":?, "to_ms":?, "endpoint_class":?, "limit":?}`. The range is
 * `[from_ms, to_ms)` in epoch milliseconds and defaults to the last hour including the open minute;
 * `endpoint_class` is one class exactly as `endpoint_classes` lists it; `limit` is 1 to
 * [RollupQuery.MAX_LIMIT] rows (default [RollupQuery.DEFAULT_LIMIT]). A refusal is `invalid_range`,
 * `invalid_endpoint_class` or `invalid_limit` and changes nothing.
 *
 * Reply: `recording` says whether this daemon records at all (a mirror daemon, or a history that could not be
 * opened, does not); `has_data` whether the series holds a row. **No data is an empty `series` with
 * `has_data:false`, never a row of zeros**; a percentile or byte sum that no attempt could provide is `null`.
 * `oldest_minute_ms` is where the file's data begins, `incomplete_since_ms` the first time attempts were lost
 * (null: none known), `truncated` that `limit` cut the series. A series row with `partial:true` is a minute that
 * is not over: its numbers still grow.
 */
class MetricsRpcHandler(
    private val metrics: RequestMetricsRecorder?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun query(jsonRequest: String): String {
        val body = runCatching { Json.parseToJsonElement(jsonRequest) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val now = clock()
        val defaultTo = (Math.floorDiv(now, MINUTE_MS) + 1) * MINUTE_MS
        val from = body.nonNegativeLong("from_ms")
        val to = body.nonNegativeLong("to_ms")
        if (from == INVALID || to == INVALID) return INVALID_RANGE
        val toMs = to ?: defaultTo
        val fromMs = from ?: (toMs - DEFAULT_RANGE_MS).coerceAtLeast(0)
        if (fromMs >= toMs) return INVALID_RANGE

        val endpoint = body["endpoint_class"]
        val endpointClass =
            when {
                endpoint == null || endpoint is JsonNull -> null
                endpoint is JsonPrimitive && endpoint.isString && endpoint.content.length in 1..MAX_ENDPOINT_CHARS -> endpoint.content
                else -> return INVALID_ENDPOINT_CLASS
            }
        val limitElement = body["limit"]
        val limit =
            when {
                limitElement == null || limitElement is JsonNull -> RollupQuery.DEFAULT_LIMIT
                else -> (limitElement as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.takeIf { it in 1..RollupQuery.MAX_LIMIT }
                    ?: return INVALID_LIMIT
            }

        val recorder = metrics ?: return reply(fromMs, toMs, recording = false, result = null, incompleteSince = null, now = now)
        return try {
            val result = recorder.query(RollupQuery(fromMs, toMs, endpointClass, limit))
            val since = recorder.incompleteSinceMs() ?: if (recorder.stats.incomplete) now else null
            reply(fromMs, toMs, recording = true, result = result, incompleteSince = since, now = now)
        } catch (e: SQLException) {
            log.warn("metrics.query: the metrics file cannot be read ({}: {})", e.javaClass.simpleName, e.message)
            UNAVAILABLE
        }
    }

    private fun JsonObject.nonNegativeLong(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        return if (value == null || value < 0) INVALID else value
    }

    private fun reply(
        fromMs: Long,
        toMs: Long,
        recording: Boolean,
        result: RollupResult?,
        incompleteSince: Long?,
        now: Long,
    ): String =
        buildJsonObject {
            put("ok", true)
            put("recording", recording)
            put("from_ms", fromMs)
            put("to_ms", toMs)
            put("step_ms", MINUTE_MS)
            put("has_data", result?.rows?.isNotEmpty() ?: false)
            put("truncated", result?.truncated ?: false)
            put("incomplete_since_ms", incompleteSince)
            put("oldest_minute_ms", result?.oldestMinuteMs)
            putJsonArray("endpoint_classes") { result?.endpointClasses?.forEach { add(JsonPrimitive(it)) } }
            put("series", JsonArray((result?.rows ?: emptyList()).map { it.toJson(now) }))
        }.toString()

    private fun RollupRow.toJson(now: Long): JsonObject =
        buildJsonObject {
            put("minute_ms", minuteMs)
            put("partial", minuteMs + MINUTE_MS > now)
            put("provider", provider)
            put("endpoint_class", endpointClass)
            put("attempts", attempts)
            putJsonObject("outcomes") { outcomes.forEach { (k, v) -> put(k, v) } }
            put("throttled", throttled)
            put("retries", retries)
            putJsonObject("headers_ms") {
                put("p50", headersP50Ms)
                put("p95", headersP95Ms)
            }
            putJsonObject("total_ms") {
                put("p50", totalP50Ms)
                put("p95", totalP95Ms)
            }
            put("bytes_up", bytesUp)
            put("bytes_down", bytesDown)
        }

    companion object {
        private val log = LoggerFactory.getLogger(MetricsRpcHandler::class.java)
        private const val MINUTE_MS = 60_000L
        private const val DEFAULT_RANGE_MS = 60 * MINUTE_MS
        private const val MAX_ENDPOINT_CHARS = 200
        private const val INVALID = -1L

        private const val INVALID_RANGE = """{"ok":false,"error":"invalid_range"}"""
        private const val INVALID_ENDPOINT_CLASS = """{"ok":false,"error":"invalid_endpoint_class"}"""
        private const val INVALID_LIMIT = """{"ok":false,"error":"invalid_limit"}"""
        private const val UNAVAILABLE = """{"ok":false,"error":"unavailable"}"""
    }
}
