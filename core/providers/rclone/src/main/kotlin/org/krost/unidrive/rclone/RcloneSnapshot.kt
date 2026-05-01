package org.krost.unidrive.rclone

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot

/**
 * One object's state as recorded in a delta snapshot cursor.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * (and the rclone-specific `hasChanged` predicate) stay in the rclone module.
 */
@Serializable
data class RcloneSnapshotEntry(
    val size: Long,
    val modTime: String,
    val isFolder: Boolean,
    val hash: String? = null,
)

/** Backwards-compatible alias for the existing call sites. */
typealias RcloneSnapshot = Snapshot<RcloneSnapshotEntry>

/**
 * Rclone-specific change-detection predicate. `lsjson` returns a hash for
 * remotes that support it; when both sides have one we compare hashes,
 * otherwise we fall back to size+modTime.
 */
internal fun rcloneHasChanged(
    prev: RcloneSnapshotEntry,
    curr: RcloneSnapshotEntry,
): Boolean {
    if (prev.hash != null && curr.hash != null) {
        return prev.hash != curr.hash
    }
    return prev.size != curr.size || prev.modTime != curr.modTime
}
