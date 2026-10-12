package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.krost.unidrive.telemetry.HistoryCursor
import org.krost.unidrive.telemetry.HistoryQuery
import org.krost.unidrive.telemetry.TransferDirection
import org.krost.unidrive.telemetry.TransferResult

/**
 * A `history.list` request read into a [HistoryQuery], or the error token that refuses it (see the wire in
 * docs/dev/specs/unidrive-daemon-design.md §4.8). Unknown members are ignored. A refusal changes nothing.
 */
internal sealed interface HistoryListRequest {
    class Ok(
        val query: HistoryQuery,
    ) : HistoryListRequest

    class Refused(
        val token: String,
    ) : HistoryListRequest

    companion object {
        private const val MAX_PREFIX_LENGTH = 4096
        private const val ABSENT = Long.MIN_VALUE

        @Suppress("CyclomaticComplexMethod", "ReturnCount") // one early refusal per member of the request
        fun parse(jsonRequest: String): HistoryListRequest {
            val body = runCatching { Json.parseToJsonElement(jsonRequest) as? JsonObject }.getOrNull() ?: return Refused("invalid_request")

            val limit =
                when (val element = body["limit"]) {
                    null, JsonNull -> HistoryQuery.DEFAULT_LIMIT
                    else -> {
                        val n = (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                        if (n == null || n < 1) return Refused("invalid_limit")
                        // Over the maximum is the maximum: the next_cursor carries on.
                        minOf(n, HistoryQuery.MAX_LIMIT.toLong()).toInt()
                    }
                }
            val after =
                when (val element = body["cursor"]) {
                    null, JsonNull -> null
                    else -> {
                        val token = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                        HistoryCursor.decode(token ?: "") ?: return Refused("invalid_cursor")
                    }
                }

            var directions = emptySet<TransferDirection>()
            var results = emptySet<TransferResult>()
            var prefix: String? = null
            var from = ABSENT
            var to = ABSENT
            val filters = body["filters"]
            if (filters != null && filters != JsonNull) {
                if (filters !is JsonObject) return Refused("invalid_filters")
                directions = enumFilter(filters["direction"], TransferDirection.entries) { it.wire } ?: return Refused("invalid_direction")
                results = enumFilter(filters["result"], TransferResult.entries) { it.wire } ?: return Refused("invalid_result")
                val prefixElement = filters["path_prefix"]
                if (prefixElement != null && prefixElement != JsonNull) {
                    prefix = (prefixElement as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_PREFIX_LENGTH }
                        ?: return Refused("invalid_path_prefix")
                }
                from = instant(filters["from_ms"]) ?: return Refused("invalid_time_range")
                to = instant(filters["to_ms"]) ?: return Refused("invalid_time_range")
                if (from != ABSENT && to != ABSENT && from > to) return Refused("invalid_time_range")
            }
            return Ok(
                HistoryQuery(
                    directions = directions,
                    results = results,
                    pathPrefix = prefix,
                    fromMs = from.takeIf { it != ABSENT },
                    toMs = to.takeIf { it != ABSENT },
                    after = after,
                    limit = limit,
                ),
            )
        }

        // A string or an array of strings, each a known wire value; absent = no filter; null = refused.
        private fun <T> enumFilter(
            element: JsonElement?,
            all: List<T>,
            wire: (T) -> String,
        ): Set<T>? {
            if (element == null || element == JsonNull) return emptySet()
            val tokens =
                when (element) {
                    is JsonArray -> element.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
                    is JsonPrimitive -> listOf(element.takeIf { it.isString }?.content ?: return null)
                    else -> return null
                }
            return tokens.map { token -> all.firstOrNull { wire(it) == token } ?: return null }.toSet()
        }

        // The instant as a JSON integer; absent is ABSENT (no bound); anything else null (refused).
        private fun instant(element: JsonElement?): Long? =
            when (element) {
                null, JsonNull -> ABSENT
                else -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            }
    }
}
