package org.krost.unidrive.localfs

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot

/**
 * One file's state as recorded in a delta snapshot cursor.
 * Local filesystem has no change feed — we compare full directory listings.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * stays in the localfs module.
 */
@Serializable
data class LocalFsSnapshotEntry(
    val size: Long,
    val mtimeMillis: Long,
    val isFolder: Boolean,
)

/** Backwards-compatible alias for the existing call sites. */
typealias LocalFsSnapshot = Snapshot<LocalFsSnapshotEntry>
