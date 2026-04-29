package org.krost.unidrive.localfs

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * One file's state as recorded in a delta snapshot cursor.
 * Local filesystem has no change feed — we compare full directory listings.
 */
@Serializable
data class LocalFsSnapshotEntry(
    val size: Long,
    val mtimeMillis: Long,
    val isFolder: Boolean,
)

@Serializable
data class LocalFsSnapshot(
    val entries: Map<String, LocalFsSnapshotEntry>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun encode(): String {
        val json = Json.encodeToString(this)
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    companion object {
        fun decode(cursor: String): LocalFsSnapshot {
            val bytes = Base64.getDecoder().decode(cursor)
            return Json.decodeFromString(String(bytes))
        }
    }
}
