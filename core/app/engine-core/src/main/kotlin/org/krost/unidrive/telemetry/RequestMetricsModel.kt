package org.krost.unidrive.telemetry

import org.krost.unidrive.http.RequestAttempt

/**
 * Tuning of one profile's request-attempt metrics. The raw rows age out sooner than the roll-ups; both live
 * under the history's byte budget ([HistoryConfig.maxBytes]) and give way to it before any transfer row does.
 */
data class RequestMetricsConfig(
    /** How long a raw attempt row is kept. The roll-ups of its minute outlive it. */
    val rawRetentionHours: Int = DEFAULT_RAW_RETENTION_HOURS,
    /** How long a per-minute roll-up is kept. */
    val rollupRetentionDays: Int = DEFAULT_ROLLUP_RETENTION_DAYS,
    /** Attempts waiting for the writer; a full queue drops the new attempt (counted), it never blocks the request. */
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    /** Attempts the writer puts in one SQLite transaction. */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** How often the writer rolls up the minutes that received attempts since the last pass. */
    val rollupIntervalMs: Long = DEFAULT_ROLLUP_INTERVAL_MS,
    /** How often the writer applies the age windows and the shared byte budget. */
    val pruneIntervalMs: Long = DEFAULT_PRUNE_INTERVAL_MS,
) {
    init {
        require(rawRetentionHours > 0) { "rawRetentionHours must be positive" }
        require(rollupRetentionDays > 0) { "rollupRetentionDays must be positive" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(rollupIntervalMs > 0) { "rollupIntervalMs must be positive" }
        require(pruneIntervalMs > 0) { "pruneIntervalMs must be positive" }
    }

    companion object {
        const val DEFAULT_RAW_RETENTION_HOURS = 24
        const val DEFAULT_ROLLUP_RETENTION_DAYS = 30
        const val DEFAULT_QUEUE_CAPACITY = 20_000
        const val DEFAULT_BATCH_SIZE = 500
        const val DEFAULT_ROLLUP_INTERVAL_MS = 5_000L
        const val DEFAULT_PRUNE_INTERVAL_MS = 10 * 60_000L
    }
}

/** Counters of one open recorder; [written] and [dropped] count attempts. [incomplete] is true once any was dropped. */
data class RequestMetricsStats(
    val written: Long,
    val dropped: Long,
    val incomplete: Boolean,
    val queued: Int,
)

/**
 * The roll-up of one minute of attempts at one endpoint class.
 *
 * - [minuteMs]: the start of the minute, epoch milliseconds (a multiple of 60 000), by the attempt's start time.
 * - [outcomes]: attempts per outcome wire name (`2xx`, `429`, `reset`, ...); the counts add up to [attempts].
 * - [throttled]: attempts that were throttled; [retries]: attempts with a retry number above 0.
 * - header and total percentiles are nearest-rank in milliseconds over the attempts of the minute. A header
 *   percentile is null when no attempt of the minute got a response; total time is known for every attempt.
 * - [bytesUp] / [bytesDown]: the sum over the attempts that know the value, null when none does. Never 0 for "unknown".
 */
data class RollupRow(
    val minuteMs: Long,
    val provider: String,
    val endpointClass: String,
    val attempts: Int,
    val outcomes: Map<String, Int>,
    val throttled: Int,
    val retries: Int,
    val headersP50Ms: Long?,
    val headersP95Ms: Long?,
    val totalP50Ms: Long,
    val totalP95Ms: Long,
    val bytesUp: Long?,
    val bytesDown: Long?,
)

/** A read of the roll-ups: minutes in [[fromMs], [toMs]) , optionally one endpoint class, at most [limit] rows (oldest first). */
data class RollupQuery(
    val fromMs: Long,
    val toMs: Long,
    val endpointClass: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 1_000
        const val MAX_LIMIT = 5_000
    }
}

/**
 * The answer to a [RollupQuery]. [endpointClasses] are the classes that have roll-ups in the range (the filter ignored),
 * [oldestMinuteMs] the oldest roll-up the file holds at all (null when it holds none): where the data begins.
 */
data class RollupResult(
    val rows: List<RollupRow>,
    val truncated: Boolean,
    val endpointClasses: List<String>,
    val oldestMinuteMs: Long?,
)

internal const val MINUTE_MS = 60_000L

internal fun minuteOf(epochMs: Long): Long = Math.floorDiv(epochMs, MINUTE_MS) * MINUTE_MS

/** The pure arithmetic of one roll-up, apart from the database. */
internal object Rollups {
    /** Nearest-rank percentile of [sorted] (ascending, non-empty): the value at rank ceil(q * n). */
    fun percentile(
        sorted: List<Long>,
        q: Double,
    ): Long {
        require(sorted.isNotEmpty()) { "no values" }
        val rank = Math.ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** One roll-up per endpoint class for the attempts that started in the minute [minuteMs]. */
    fun compute(
        minuteMs: Long,
        attempts: Collection<RequestAttempt>,
    ): List<RollupRow> =
        attempts
            .groupBy { it.provider to it.endpointClass }
            .map { (key, group) ->
                val headers = group.mapNotNull { it.headersMs }.sorted()
                val totals = group.map { it.totalMs }.sorted()
                val ups = group.mapNotNull { it.bytesUp }
                val downs = group.mapNotNull { it.bytesDown }
                RollupRow(
                    minuteMs = minuteMs,
                    provider = key.first,
                    endpointClass = key.second,
                    attempts = group.size,
                    outcomes = group.groupingBy { it.outcome.wire }.eachCount().toSortedMap(),
                    throttled = group.count { it.throttled },
                    retries = group.count { it.retryNo > 0 },
                    headersP50Ms = headers.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.50) },
                    headersP95Ms = headers.takeIf { it.isNotEmpty() }?.let { percentile(it, 0.95) },
                    totalP50Ms = percentile(totals, 0.50),
                    totalP95Ms = percentile(totals, 0.95),
                    bytesUp = ups.takeIf { it.isNotEmpty() }?.sum(),
                    bytesDown = downs.takeIf { it.isNotEmpty() }?.sum(),
                )
            }.sortedBy { it.endpointClass }

    fun encodeOutcomes(outcomes: Map<String, Int>): String = outcomes.entries.joinToString(",") { "${it.key}:${it.value}" }

    fun decodeOutcomes(text: String): Map<String, Int> =
        if (text.isEmpty()) {
            emptyMap()
        } else {
            text.split(',').associate { part -> part.substringBeforeLast(':') to part.substringAfterLast(':').toInt() }
        }
}
