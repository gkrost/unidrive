package org.krost.unidrive.s3

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * One object's state as recorded in a delta snapshot cursor.
 * S3 has no server-side change feed, so we compare full listings.
 */
@Serializable
data class S3SnapshotEntry(
    val etag: String?,
    val size: Long,
    val lastModified: String?, // ISO-8601 string from ListObjects
    val isFolder: Boolean,
)

@Serializable
data class S3Snapshot(
    val entries: Map<String, S3SnapshotEntry>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun encode(): String {
        val json = Json.encodeToString(this)
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    companion object {
        fun decode(cursor: String): S3Snapshot {
            val bytes = Base64.getDecoder().decode(cursor)
            return Json.decodeFromString(String(bytes))
        }
    }
}
