package org.krost.unidrive.rclone

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class RcloneSnapshotEntry(
    val size: Long,
    val modTime: String,
    val isFolder: Boolean,
    val hash: String? = null,
)

@Serializable
data class RcloneSnapshot(
    val entries: Map<String, RcloneSnapshotEntry>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun encode(): String {
        val json = Json.encodeToString(this)
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    companion object {
        fun decode(cursor: String): RcloneSnapshot {
            val bytes = Base64.getDecoder().decode(cursor)
            return Json.decodeFromString(String(bytes))
        }

        fun hasChanged(
            prev: RcloneSnapshotEntry,
            curr: RcloneSnapshotEntry,
        ): Boolean {
            if (prev.hash != null && curr.hash != null) {
                return prev.hash != curr.hash
            }
            return prev.size != curr.size || prev.modTime != curr.modTime
        }
    }
}
