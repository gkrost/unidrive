package org.krost.unidrive.sync

/**
 * What the engine can tell a client about the enumeration of the remote: whether one is running,
 * how far it is, and how the last attempt ended. A snapshot of an [EnumerationTracker]; every field
 * except [state], [first] and [attempt] is null while unknown.
 */
data class EnumerationStatus(
    val state: State,
    /** No enumeration has completed for this profile yet (the delta cursor is empty): the view is incomplete. */
    val first: Boolean,
    /** Attempts since the last success, counting the running one; 0 after a success. */
    val attempt: Int,
    val phase: Phase? = null,
    /** The listing strategy of the running attempt, see [org.krost.unidrive.ScanProgress]. */
    val listing: String? = null,
    /** Start of the running attempt, or of the last one. */
    val startedAtMs: Long? = null,
    /**
     * Elapsed time of the running attempt; once its phase is [Phase.SAVING] it stays at the duration of the listing
     * (the save time is not part of it). Absent unless an attempt is running.
     */
    val elapsedMs: Long? = null,
    val items: Int? = null,
    val foldersDone: Int? = null,
    val foldersKnown: Int? = null,
    val foldersSkipped: Int? = null,
    /** Items per second, smoothed over the last minute. */
    val ratePerS: Double? = null,
    val etaS: Long? = null,
    val etaKind: EtaKind? = null,
    val lastSuccessAtMs: Long? = null,
    /** One sanitised line: no paths, at most [EnumerationTracker.ERROR_MAX_CHARS] characters. */
    val lastError: String? = null,
    /** When the poller tries again after a failure. */
    val nextAttemptAtMs: Long? = null,
) {
    enum class State(val wire: String) {
        IDLE("idle"),
        RUNNING("running"),
        FAILED("failed"),
    }

    enum class Phase(val wire: String) {
        /** Gathering from the remote. */
        LISTING("listing"),

        /** Writing the result to state.db. */
        SAVING("saving"),
    }

    enum class EtaKind(val wire: String) {
        /** Only the known queue is counted; the real value can be larger. */
        LOWER_BOUND("lower_bound"),

        /** A previous complete enumeration gives the total. */
        ESTIMATE("estimate"),
    }
}
