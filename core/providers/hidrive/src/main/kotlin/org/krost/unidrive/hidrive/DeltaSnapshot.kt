package org.krost.unidrive.hidrive

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class SnapshotEntry(
    val id: String,
    val chash: String?,
    val size: Long,
    val mtime: Long?,
    val isFolder: Boolean,
)

@Serializable
data class DeltaSnapshot(
    val entries: Map<String, SnapshotEntry>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun encode(): String {
        val jsonString = Json.encodeToString(this)
        return Base64.getEncoder().encodeToString(jsonString.toByteArray())
    }

    companion object {
        fun decode(cursor: String): DeltaSnapshot {
            val jsonBytes = Base64.getDecoder().decode(cursor)
            return Json.decodeFromString(String(jsonBytes))
        }
    }
}
