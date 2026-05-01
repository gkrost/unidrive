package org.krost.unidrive.hidrive

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot

/**
 * Per-file state in a HiDrive delta-snapshot cursor.
 *
 * The wrapper class (`Snapshot<E>` + Base64-of-JSON `encode`/`decode`)
 * lives in `:app:sync` (UD-345). Only the entry shape — provider-specific
 * — stays here.
 */
@Serializable
data class SnapshotEntry(
    val id: String,
    val chash: String?,
    val size: Long,
    val mtime: Long?,
    val isFolder: Boolean,
)

/** Backwards-compatible alias so existing call sites read naturally. */
typealias DeltaSnapshot = Snapshot<SnapshotEntry>
