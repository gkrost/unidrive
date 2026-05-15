package org.krost.unidrive.rclone

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot
import org.krost.unidrive.sync.SnapshotEntry

/**
 * One object's state as recorded in a delta snapshot cursor.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * (and the rclone-specific `hasChanged` predicate) stay in the rclone module.
 *
 * UD-008: implements [SnapshotEntry] for shared `defaultDeletedItem` use.
 */
@Serializable
data class RcloneSnapshotEntry(
    val size: Long,
    val modTime: String,
    override val isFolder: Boolean,
    val hash: String? = null,
) : SnapshotEntry

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
