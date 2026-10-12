package org.krost.unidrive.telemetry

/** What a logical transfer did to the cloud item; the wire value is what `telemetry.db` stores. */
enum class TransferDirection(
    val wire: String,
) {
    UPLOAD("upload"),
    DOWNLOAD("download"),
    DELETE("delete"),
    RENAME("rename"),
    MKDIR("mkdir"),
    OTHER("other"),
    ;

    companion object {
        /** The direction of an [org.krost.unidrive.engine.AuditSink] action verb (`Upload`, `Move`, ...). */
        fun ofAction(action: String): TransferDirection =
            when (action.lowercase()) {
                "upload" -> UPLOAD
                "download", "hydrate" -> DOWNLOAD
                "delete", "unlink" -> DELETE
                "move", "rename" -> RENAME
                "createremotefolder", "mkdir" -> MKDIR
                else -> OTHER
            }

        fun ofWire(wire: String): TransferDirection = entries.firstOrNull { it.wire == wire } ?: OTHER
    }
}

/**
 * How a logical transfer ended. [RUNNING] is a row that was started and not finished yet; the next
 * open of the store turns every such row into [INTERRUPTED], so a crash is never read as a success.
 * [INTERRUPTED] also ends a transfer that was cancelled, or aborted by a pause: it neither failed nor finished.
 * [CONFLICT] is an upload the cloud refused because its copy changed since the version the write started from.
 */
enum class TransferResult(
    val wire: String,
) {
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed"),
    SKIPPED("skipped"),
    CONFLICT("conflict"),
    INTERRUPTED("interrupted"),
    ;

    companion object {
        fun ofWire(wire: String): TransferResult = entries.firstOrNull { it.wire == wire } ?: INTERRUPTED
    }
}

/**
 * One logical transfer as the user sees it (not one request attempt). The three instants are wall
 * clock milliseconds: [queuedAt] the operation was handed to the transfer machinery, [startedAt] a
 * transfer permit was acquired, [finishedAt] it ended. [queueWaitMs] and [transferMs] are measured on
 * a monotonic clock and are never one duration. A value that was not measured is null, never 0.
 */
data class TransferRow(
    val operationId: String,
    val profile: String,
    val direction: TransferDirection,
    val path: String,
    val queuedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val queueWaitMs: Long? = null,
    val transferMs: Long? = null,
    val bytesTotal: Long? = null,
    val bytesDone: Long? = null,
    val result: TransferResult = TransferResult.RUNNING,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val attempts: Int? = null,
    val oldHash: String? = null,
    val newHash: String? = null,
)
