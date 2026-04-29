package org.krost.unidrive.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * One file's state as recorded in a delta snapshot cursor.
 * WebDAV has no server-side change feed; we compare PROPFIND listings.
 */
@Serializable
data class WebDavSnapshotEntry(
    val size: Long,
    val lastModified: String?, // RFC-1123 / ISO-8601 string from PROPFIND
    val etag: String?,
    val isFolder: Boolean,
)

@Serializable
data class WebDavSnapshot(
    val entries: Map<String, WebDavSnapshotEntry>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun encode(): String {
        val json = Json.encodeToString(this)
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    companion object {
        fun decode(cursor: String): WebDavSnapshot {
            val bytes = Base64.getDecoder().decode(cursor)
            return Json.decodeFromString(String(bytes))
        }
    }
}
