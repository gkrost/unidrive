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

    companion object {
        /** Wire token for [NotFound]; shared verbatim with the mount crate. */
        const val NOT_FOUND_TOKEN = "not_found"

        /** Wire token for [UnknownPath]; shared verbatim with the mount crate. */
        const val UNKNOWN_PATH_TOKEN = "unknown_path"
    }
}
