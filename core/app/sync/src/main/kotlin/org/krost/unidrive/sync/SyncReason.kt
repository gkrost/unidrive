package org.krost.unidrive.sync

/**
 * UD-254: classifies WHY a sync pass was started.
 *
 * Surfaced in the `Scan started` / `Scan ended` log banners so log review
 * can distinguish legitimate boot/poll activity from post-error rescans.
 * Extend rather than repurpose values; downstream tooling may grep by name.
 */
enum class SyncReason {
    /** First sync after daemon start. */
    BOOT,

    /** Periodic poll inside `sync --watch`. */
    WATCH_POLL,

    /**
     * User-invoked sync (one-shot CLI command, MCP `sync` tool, etc.).
     * Default for callers that don't classify themselves.
     */
    MANUAL,

    /**
     * Sync pass triggered by a filesystem / remote change event while
     * the watch loop is otherwise idle (LocalWatcher, webhook).
     */
    EVENT_DRIVEN,

    /**
     * Rescan triggered after transient errors. UD-248 will make this
     * deliberate (vs. the current accidental behaviour); UD-254 adds the
     * enum value now so the reason is already usable for diagnosis.
     */
    RESCAN_AFTER_ERROR,
}
