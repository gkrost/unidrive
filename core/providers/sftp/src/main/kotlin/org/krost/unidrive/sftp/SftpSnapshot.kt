package org.krost.unidrive.sftp

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot
import org.krost.unidrive.sync.SnapshotEntry

/**
 * One file's state as recorded in a delta snapshot cursor.
 * SFTP has no server-side change feed — we compare full directory listings.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * stays in the SFTP module.
 *
 * UD-008: implements [SnapshotEntry] for shared `defaultDeletedItem` use.
 */
@Serializable
data class SftpSnapshotEntry(
    val size: Long,
    val mtimeSeconds: Long, // Unix epoch seconds from SFTP ATTRS
    override val isFolder: Boolean,
) : SnapshotEntry

/** Backwards-compatible alias for the existing call sites. */
typealias SftpSnapshot = Snapshot<SftpSnapshotEntry>
