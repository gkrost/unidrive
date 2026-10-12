package org.krost.unidrive.telemetry

/**
 * Tuning of one profile's transfer history. The retention defaults (90 days or 200 MB, whichever is hit
 * first) are the owner's decision; every value is configurable.
 */
data class HistoryConfig(
    val maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    /** Rows waiting for the writer; a full queue drops the new row (counted), it never blocks the caller. */
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    /** Rows the writer puts in one SQLite transaction. */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** How often the writer applies the retention when it is idle or busy. */
    val pruneIntervalMs: Long = DEFAULT_PRUNE_INTERVAL_MS,
    /** Retention also runs after this many rows were written since the last run. */
    val pruneEveryRows: Int = DEFAULT_PRUNE_EVERY_ROWS,
) {
    init {
        require(maxAgeDays > 0) { "maxAgeDays must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(pruneIntervalMs > 0) { "pruneIntervalMs must be positive" }
        require(pruneEveryRows > 0) { "pruneEveryRows must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_AGE_DAYS = 90
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
        const val DEFAULT_QUEUE_CAPACITY = 20_000
        const val DEFAULT_BATCH_SIZE = 500
        const val DEFAULT_PRUNE_INTERVAL_MS = 10 * 60_000L
        const val DEFAULT_PRUNE_EVERY_ROWS = 10_000
    }
}

/**
 * Counters of one open history. [written] and [dropped] count row snapshots: a transfer is written
 * when it starts and again when it finishes, and a batch may merge the two. [incomplete] is true once
 * any snapshot was dropped (full queue or a failed write).
 */
data class HistoryStats(
    val written: Long,
    val dropped: Long,
    val incomplete: Boolean,
    val queued: Int,
)
