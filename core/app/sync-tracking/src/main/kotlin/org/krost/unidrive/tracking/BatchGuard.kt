package org.krost.unidrive.tracking

/**
 * Batch-level deletion safety net (spec Amendment 3).
 *
 * Even with the tracking-set invariant ("only tracked paths can be
 * deleted"), an honest bug in tracking maintenance — or a profile-level
 * blast like a sync_root remount over the wrong volume — could still
 * produce a delete plan large enough to be catastrophic. The batch
 * guard is the second line of defence: if the proposed delete count
 * exceeds [maxDeleteRatio] of the tracked set, the engine refuses to
 * commit and reports back to the user.
 *
 * Semantics:
 *  - `maxDeleteRatio = 0.5` (default) means "abort if more than 50% of
 *    the tracked set would be deleted in this pass."
 *  - `maxDeleteAbsolute = 50` means "abort if the absolute count exceeds
 *    50 deletes, regardless of ratio." Both axes trip independently.
 *  - Pass a guard with both axes set to `Long.MAX_VALUE` / `1.0` to
 *    effectively disable; `--force-delete`-equivalent is achieved by
 *    handing the engine a permissive guard.
 *
 * The guard is INDEPENDENT of the per-path NoOp/Action decision — the
 * reconciler still emits PropagateLocalDelete / PropagateRemoteDelete
 * actions one at a time; the guard inspects the full batch before
 * apply. If the guard trips, NO deletes from the batch are applied;
 * non-delete actions in the same batch are unaffected (uploads and
 * downloads still proceed).
 */
class BatchGuard(
    private val maxDeleteRatio: Double = 0.5,
    private val maxDeleteAbsolute: Int = 50,
) {
    init {
        require(maxDeleteRatio in 0.0..1.0) { "maxDeleteRatio must be in [0.0, 1.0]" }
        require(maxDeleteAbsolute >= 0) { "maxDeleteAbsolute must be >= 0" }
    }

    /**
     * Inspect [actions] against the current tracked-set size [trackedTotal].
     * Returns [Verdict.Allow] when the delete-count is within both axes,
     * [Verdict.Deny] otherwise. [Verdict.Deny] carries the offending
     * counts so the caller can render an actionable error.
     */
    fun inspect(
        actions: List<ReconcileAction>,
        trackedTotal: Int,
    ): Verdict {
        val deletes =
            actions.count {
                it is ReconcileAction.PropagateLocalDelete || it is ReconcileAction.PropagateRemoteDelete
            }
        if (deletes == 0) return Verdict.Allow

        val ratio = if (trackedTotal == 0) 1.0 else deletes.toDouble() / trackedTotal.toDouble()
        val ratioTrip = ratio > maxDeleteRatio
        val absoluteTrip = deletes > maxDeleteAbsolute

        return if (ratioTrip || absoluteTrip) {
            Verdict.Deny(
                deleteCount = deletes,
                trackedTotal = trackedTotal,
                ratio = ratio,
                maxRatio = maxDeleteRatio,
                maxAbsolute = maxDeleteAbsolute,
                ratioTripped = ratioTrip,
                absoluteTripped = absoluteTrip,
            )
        } else {
            Verdict.Allow
        }
    }

    sealed class Verdict {
        object Allow : Verdict()

        data class Deny(
            val deleteCount: Int,
            val trackedTotal: Int,
            val ratio: Double,
            val maxRatio: Double,
            val maxAbsolute: Int,
            val ratioTripped: Boolean,
            val absoluteTripped: Boolean,
        ) : Verdict() {
            fun describe(): String =
                buildString {
                    append("BatchGuard tripped: ")
                    append(deleteCount).append(" delete(s) requested ")
                    append("(tracked total: ").append(trackedTotal).append(", ")
                    append("ratio: ").append("%.2f".format(ratio)).append(").")
                    if (ratioTripped) {
                        append(" Ratio exceeds ").append("%.2f".format(maxRatio)).append(".")
                    }
                    if (absoluteTripped) {
                        append(" Absolute count exceeds ").append(maxAbsolute).append(".")
                    }
                    append(" No deletes applied this pass.")
                }
        }
    }
}
