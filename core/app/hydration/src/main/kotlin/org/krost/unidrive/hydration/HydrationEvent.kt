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
    data class Failed(override val path: String, val error: HydrationError) : HydrationEvent()

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
