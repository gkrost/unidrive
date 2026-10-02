package org.krost.unidrive.hydration

/**
 * Hydration state-change events emitted as a Flow by the SPI.
 * Phase 3 consumers subscribe via `hydration.subscribe` to drive
 * icon overlays / desktop notifications.
 */
sealed class HydrationEvent {
    abstract val path: String

    data class Hydrating(override val path: String) : HydrationEvent()
    data class Hydrated(override val path: String, val bytes: Long) : HydrationEvent()
    data class Dehydrated(override val path: String) : HydrationEvent()
    data class Failed(
        override val path: String,
        val error: HydrationError,
        /**
         * Upload attempts only: whether the daemon will retry this upload on
         * its own (true — the client can show "waiting/retrying" and need do
         * nothing) or has given up for good (false — the row stays visibly
         * failed until a client re-submit or a daemon restart replays it).
         * Null (absent on the wire) for non-upload failures, keeping the
         * pre-existing event shape byte-identical there.
         */
        val retryScheduled: Boolean? = null,
    ) : HydrationEvent()

    /**
     * An upload submitted through `open_write` was accepted into the daemon's
     * upload queue but has not started transferring yet (the daemon-wide
     * per-provider transfer budget is busy). A client shows the file as
     * waiting until hydrating/failed arrives. Always followed by one of those.
     *
     * Wire shape (NDJSON line on `hydration.subscribe` stream):
     *   `{"event":"queued","path":"/a/save.doc"}`
     */
    data class Queued(override val path: String) : HydrationEvent()

    /**
     * Emitted instead of the hydrating/hydrated pair when a write lands on a
     * path matched by the profile's exclude_patterns: the content is accepted
     * and kept local-only — it is deliberately never uploaded, so the row must
     * never present as in-sync. Followed by a [Completed] with
     * [HydrationError.Excluded] so the client's handle correlation still
     * terminates.
     *
     * Wire shape (NDJSON line on `hydration.subscribe` stream):
     *   `{"event":"skipped","path":"/a/scratch.tmp"}`
     */
    data class Skipped(override val path: String) : HydrationEvent()

    /**
     * Correlated completion of a handle-scoped transfer. Emitted when the work
     * behind an `open_read` (download) or `open_write` (upload, including the
     * crash-recovery replay) finishes — [handleId] is the client's own handle id,
     * so a write-back client can mark a file in sync (or surface the failure)
     * without guessing from the uncorrelated hydrating/hydrated/failed stream.
     * Verbs whose reply already IS the result (hydrate, dehydrate) emit no
     * Completed.
     *
     * Wire shape (NDJSON line on `hydration.subscribe`):
     *   - success: `{"event":"completed","path":"/a","handle_id":"h1","direction":"upload","ok":true}`
     *   - failure: `{"event":"completed","path":"/a","handle_id":"h1","direction":"upload","ok":false,"error":"<token>"}`
     */
    data class Completed(
        override val path: String,
        /** The client-supplied handle id the transfer was started under. */
        val handleId: String,
        val direction: Direction,
        val ok: Boolean,
        /** Failure token (stable wire token for typed errors, provider message for Generic). Null on success. */
        val error: HydrationError? = null,
    ) : HydrationEvent() {
        enum class Direction { DOWNLOAD, UPLOAD }
    }

    /**
     * Byte progress of a client-written upload, correlated to the open_write
     * handle like [Completed]. Coalesced to at most a few per second per file
     * (providers that cannot report progress simply never emit this — the
     * stream only carries the hydrating/hydrated pair and the Completed).
     *
     * Wire shape (NDJSON line on `hydration.subscribe` stream):
     *   `{"event":"uploading","path":"/a/save.doc","handle_id":"h1","bytes_done":4096,"bytes_total":65536}`
     */
    data class Uploading(
        override val path: String,
        val handleId: String,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) : HydrationEvent()

    /**
     * Emitted after [org.krost.unidrive.sync.SyncEngine.enumerateRemoteIntoState] mutates
     * `state.db` (upserts and/or reaps rows). Signals subscribed FUSE co-daemons to drop
     * stale `readdir`/`getattr` cache entries.
     *
     * Wire shape (NDJSON line on `hydration.subscribe` stream):
     *   - Up to [VIEW_INVALIDATED_PATH_CAP] paths: `{"event":"view.invalidated","paths":["/a","/b",…]}`
     *   - More than [VIEW_INVALIDATED_PATH_CAP] paths:  `{"event":"view.invalidated","full":true}`
     *
     * [path] is always the empty string — this event is not tied to a single path.
     */
    data class ViewInvalidated(
        /** Affected paths, or empty when [full] is true. */
        val paths: List<String>,
        /** True when the changed-path count exceeded [VIEW_INVALIDATED_PATH_CAP]; co-daemon should invalidate all cache entries. */
        val full: Boolean = false,
    ) : HydrationEvent() {
        override val path: String get() = ""
    }

    companion object {
        /** Maximum number of individual paths emitted before collapsing to `full:true`. */
        const val VIEW_INVALIDATED_PATH_CAP = 256
    }
}
