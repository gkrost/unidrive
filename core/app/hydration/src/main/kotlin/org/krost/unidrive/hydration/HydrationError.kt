package org.krost.unidrive.hydration

/**
 * Sealed extension point for hydration failure modes. Phase 1 ships
 * only Generic; Phase 3 adds Transient / Permanent / QuotaExhausted /
 * Busy as the icon-overlay UX surfaces them.
 */
sealed interface HydrationError {
    val message: String

    data class Generic(override val message: String) : HydrationError

    /**
     * The remote object is genuinely gone (provider download still
     * not-found after the re-resolve). Its [message] is the STABLE wire
     * token `not_found`, matched verbatim by the mount crate's
     * `ipc_error_to_errno` to return ENOENT instead of the catch-all EIO.
     * Changing this string breaks that cross-repo contract.
     */
    data object NotFound : HydrationError {
        override val message: String = NOT_FOUND_TOKEN
    }

    /**
     * The path is not present in state.db — it was never part of the synced
     * view. Its [message] is the STABLE wire token `unknown_path`, matched by
     * the mount crate to return ENOENT (POSIX-correct for a path that does not
     * exist) instead of the catch-all EIO. Used uniformly by every verb whose
     * precondition is a state.db row lookup, so the same condition surfaces the
     * same errno regardless of which verb hit it. Changing this string breaks
     * that cross-repo contract.
     */
    data object UnknownPath : HydrationError {
        override val message: String = UNKNOWN_PATH_TOKEN
    }

    /**
     * Optimistic-concurrency mismatch on a write: the client's base etag does
     * not match the row's current change-detection token, so the write would
     * silently overwrite a newer remote version. The write is refused BEFORE
     * any upload starts (nothing was modified — the client may keep both).
     * Its [message] is the STABLE wire token `conflict`; a mount client maps
     * it to its own conflict handling (EBUSY-flavoured) rather than EIO.
     * Changing this string breaks that cross-repo contract.
     */
    data object Conflict : HydrationError {
        override val message: String = CONFLICT_TOKEN
    }

    /**
     * The path lies outside the profile's sync_path set. A mounted profile
     * shows its scope as the whole drive, so a folder or file created outside
     * the scope would land in the cloud but never appear in the view — the
     * write is refused instead (nothing is created remotely). Raised by
     * create, mkdir, rename (either end) and open_write_begin. Its [message]
     * is the STABLE wire token `outside_scope`; a mount client surfaces it as
     * a failed state with a readable reason. Changing this string breaks that
     * cross-repo contract.
     */
    data object OutOfScope : HydrationError {
        override val message: String = OUTSIDE_SCOPE_TOKEN
    }

    /**
     * The path matches the profile's exclude_patterns. Not a refusal: the
     * local write is accepted and the content stays local-only — it is
     * deliberately never uploaded (the same keep-local rule the sync engine's
     * upload path applies). Carried as [Completed.error] so a client marking
     * in-sync on a Completed event cannot mark an excluded file as uploaded.
     * Its [message] is the STABLE wire token `excluded`. Changing this string
     * breaks that cross-repo contract.
     */
    data object Excluded : HydrationError {
        override val message: String = EXCLUDED_TOKEN
    }

    companion object {
        /** Wire token for [NotFound]; shared verbatim with the mount crate. */
        const val NOT_FOUND_TOKEN = "not_found"

        /** Wire token for [UnknownPath]; shared verbatim with the mount crate. */
        const val UNKNOWN_PATH_TOKEN = "unknown_path"

        /** Wire token for [Conflict]; shared verbatim with the mount crate. */
        const val CONFLICT_TOKEN = "conflict"

        /** Wire token for [OutOfScope]; shared verbatim with the mount crate. */
        const val OUTSIDE_SCOPE_TOKEN = "outside_scope"

        /** Wire token for [Excluded]; shared verbatim with the mount crate. */
        const val EXCLUDED_TOKEN = "excluded"
    }
}
