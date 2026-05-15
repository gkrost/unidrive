package org.krost.unidrive.s3

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot
import org.krost.unidrive.sync.SnapshotEntry

/**
 * One object's state as recorded in a delta snapshot cursor.
 * S3 has no server-side change feed, so we compare full listings.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * stays in the S3 module.
 *
 * UD-008: implements [SnapshotEntry] for shared `defaultDeletedItem` use
 * (S3 passes `id = api.pathToKey(path)` rather than the default
 * `id = path`).
 */
@Serializable
data class S3SnapshotEntry(
    val etag: String?,
    val size: Long,
    val lastModified: String?, // ISO-8601 string from ListObjects
    override val isFolder: Boolean,
) : SnapshotEntry

/** Backwards-compatible alias for the existing call sites. */
typealias S3Snapshot = Snapshot<S3SnapshotEntry>
