package org.krost.unidrive.sftp

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * One file's state as recorded in a delta snapshot cursor.
 * SFTP has no server-side change feed — we compare full directory listings.
 */
@Serializable
data class SftpSnapshotEntry(
    val size: Long,
    val mtimeSeconds: Long, // Unix epoch seconds from SFTP ATTRS
    val isFolder: Boolean,
)

@Serializable
data class SftpSnapshot(
    val entries: Map<String, SftpSnapshotEntry>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun encode(): String {
        val json = Json.encodeToString(this)
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    companion object {
        fun decode(cursor: String): SftpSnapshot {
            val bytes = Base64.getDecoder().decode(cursor)
            return Json.decodeFromString(String(bytes))
        }
    }
}
