package org.krost.unidrive.localfs

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot
import org.krost.unidrive.sync.SnapshotEntry

/**
 * One file's state as recorded in a delta snapshot cursor.
 * Local filesystem has no change feed — we compare full directory listings.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * stays in the localfs module.
 *
 * UD-008: implements [SnapshotEntry] so the engine's `defaultDeletedItem`
 * helper can build the deleted-row `CloudItem` without per-provider boilerplate.
 */
@Serializable
data class LocalFsSnapshotEntry(
    val size: Long,
    val mtimeMillis: Long,
    override val isFolder: Boolean,
) : SnapshotEntry

/** Backwards-compatible alias for the existing call sites. */
typealias LocalFsSnapshot = Snapshot<LocalFsSnapshotEntry>
