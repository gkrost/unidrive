package org.krost.unidrive.s3

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot

/**
 * One object's state as recorded in a delta snapshot cursor.
 * S3 has no server-side change feed, so we compare full listings.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * stays in the S3 module.
 */
@Serializable
data class S3SnapshotEntry(
    val etag: String?,
    val size: Long,
    val lastModified: String?, // ISO-8601 string from ListObjects
    val isFolder: Boolean,
)

/** Backwards-compatible alias for the existing call sites. */
typealias S3Snapshot = Snapshot<S3SnapshotEntry>
