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
    /** While [Phase.RESOLVING_PATHS] (and after a failure in it): folders resolved of [pathsTotal]. */
    val pathsDone: Int? = null,
    /** The folders the listing left out and an item hangs below, known so far (a lower bound: it can grow). */
    val pathsTotal: Int? = null,
    /** Items per second, smoothed over the last minute. */
    val ratePerS: Double? = null,
    val etaS: Long? = null,
    val etaKind: EtaKind? = null,
    val lastSuccessAtMs: Long? = null,

    /**
     * #523: whether the last completed gather was complete — null when nothing has gathered yet
     * or the field is unknown. A false here is the honest after-state the status UI and
     * daemon.status lacked: the walk skipped a folder it could not list, and items under it are
     * not in state.db until something mutates them. Set by the engine from
     * pending_cursor_complete once a gather ends; the tracker leaves it null while running.
     */
    val lastScanComplete: Boolean? = null,
    /**
     * The items of the last full listing that the provider could not place, because a folder above them is permanently
     * gone: unreachable, not deleted. Null when there are none (or the figure is unknown); set by the engine from
     * sync_state, never by the tracker.
     */
    val unreachableItems: Int? = null,
    /**
     * True while the running attempt lists the drive again because the previous scan ended incomplete (the recovery
     * latch was set when it started). False otherwise, and always false when no attempt runs; set by the tracker.
     */
    val recovery: Boolean = false,
    /**
     * The tracked items the last full listing did not return, because it ended incomplete; null when there are none.
     * Set by the engine from sync_state, never by the tracker. Once two listings in a row left out the same items they
     * are accepted (the recovery ends); the figure stays until a complete full listing.
     */
    val droppedItems: Int? = null,
    /** One sanitised line: no paths, at most [EnumerationTracker.ERROR_MAX_CHARS] characters. */
    val lastError: String? = null,
    // #878: a machine-readable cause for the failure (`http_408`, `timeout`, `auth_rejected`); absent when unknown.
    val lastErrorCode: String? = null,
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

        /**
         * The listing is over; the provider resolves the paths of what it gathered, which asks for the folders the
         * listing did not return one by one. [EnumerationStatus.pathsDone] of [EnumerationStatus.pathsTotal].
         */
        RESOLVING_PATHS("resolving_paths"),

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
