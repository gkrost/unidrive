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
     * The path matches the profile's exclude_patterns. Two uses, one token:
     * on create/open_write/open_write_begin it is NOT a refusal — the local
     * write is accepted and the content stays local-only, deliberately never
     * uploaded (carried as [Completed.error] so a client marking in-sync on a
     * Completed event cannot mark an excluded file as uploaded). On rename it
     * IS a refusal (for a synced source; a never-uploaded file renames
     * locally): moving cloud content onto an excluded name would take a
     * synced object out of every sync action silently (the Reconciler and
     * LocalScanner skip excluded names), so the move is refused and both
     * paths stay untouched. Its [message] is the STABLE wire token `excluded`.
     * Changing this string breaks that cross-repo contract.
     */
    data object Excluded : HydrationError {
        override val message: String = EXCLUDED_TOKEN
    }

    /**
     * The upload was cancelled through [Hydration.cancelUpload] (the user
     * deleted the file or moved it out while the upload was queued, running,
     * or in a retry backoff). Carried as [Completed.error] so the client's
     * handle correlation terminates with a cause instead of hanging. Its
     * [message] is the STABLE wire token `cancelled`. Changing this string
     * breaks that cross-repo contract.
     */
    data object Cancelled : HydrationError {
        override val message: String = CANCELLED_TOKEN
    }

    /**
     * The stored object is shorter than the size the drive reports (#536) — a multipart upload
     * another client truncated. The refusal is correct (the cloud copy cannot decrypt to a whole
     * file), but it is not a gone file: its own wire form carries the STABLE token
     * `remote_incomplete` plus the numbers, `remote_incomplete: got <stored> of <declared> bytes`.
     * A mount crate matches tokens verbatim and falls to the EIO catch-all (correct: the download
     * failed); a client column parses the prefix to say "the cloud copy is incomplete". The numbers
     * are the stored object's bytes and the declared drive size, in bytes. Changing the token
     * prefix breaks that contract.
     */
    data class RemoteIncomplete(
        val storedBytes: Long,
        val declaredBytes: Long,
    ) : HydrationError {
        override val message: String = "$REMOTE_INCOMPLETE_TOKEN: got $storedBytes of $declaredBytes bytes"
    }

    companion object {
        /** Wire token for [NotFound]; shared verbatim with the mount crate. */
        const val NOT_FOUND_TOKEN = "not_found"

        /** Wire token prefix for [RemoteIncomplete]; the numbers follow after ": got ". */
        const val REMOTE_INCOMPLETE_TOKEN = "remote_incomplete"

        /** Wire token for [UnknownPath]; shared verbatim with the mount crate. */
        const val UNKNOWN_PATH_TOKEN = "unknown_path"

        /** Wire token for [Conflict]; shared verbatim with the mount crate. */
        const val CONFLICT_TOKEN = "conflict"

        /** Wire token for [OutOfScope]; shared verbatim with the mount crate. */
        const val OUTSIDE_SCOPE_TOKEN = "outside_scope"

        /** Wire token for [Excluded]; shared verbatim with the mount crate. */
        const val EXCLUDED_TOKEN = "excluded"

        /** Wire token for [Cancelled]; shared verbatim with the mount crate. */
        const val CANCELLED_TOKEN = "cancelled"
    }
}
