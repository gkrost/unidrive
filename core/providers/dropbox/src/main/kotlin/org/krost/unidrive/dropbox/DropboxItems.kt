package org.krost.unidrive.dropbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.krost.unidrive.CloudItem
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns Dropbox metadata into [CloudItem]s. Dropbox paths are case-insensitive: `path_lower` is the key, and
 * `path_display` is only guaranteed to carry the right case in its last component. So the display path of an item is
 * built from its parent's (when this process has seen the parent) plus its own `name`, and a deleted entry, which has
 * no id, gets the id last seen at its path.
 */
internal class DropboxItems {
    private val displayByLower = ConcurrentHashMap<String, String>()
    private val idByLower = ConcurrentHashMap<String, String>()

    fun toCloudItem(meta: JsonObject): CloudItem {
        val tag = meta.str(".tag") ?: "file"
        val name = meta.str("name") ?: ""
        val lower = meta.str("path_lower") ?: ""
        val path = displayPath(lower, name, meta.str("path_display"))
        if (tag == "deleted") {
            val id = idByLower.remove(lower) ?: ""
            displayByLower.remove(lower)
            return CloudItem(id, name, path, 0, isFolder = false, modified = null, created = null, hash = null, mimeType = null, deleted = true)
        }
        val id = meta.str("id") ?: ""
        if (lower.isNotEmpty()) {
            if (id.isNotEmpty()) idByLower[lower] = id
            if (tag == "folder") displayByLower[lower] = path
        }
        return if (tag == "folder") {
            CloudItem(id, name, path, 0, isFolder = true, modified = null, created = null, hash = null, mimeType = null, parentId = null)
        } else {
            CloudItem(
                id = id,
                name = name,
                path = path,
                size = meta["size"]?.jsonPrimitive?.longOrNull ?: 0,
                isFolder = false,
                // client_modified is the time the uploading client gave (unidrive sends the file's own mtime); the
                // server time is when Dropbox received it.
                modified = (meta.str("client_modified") ?: meta.str("server_modified"))?.let(::parseInstant),
                created = null,
                hash = meta.str("content_hash"),
                mimeType = null,
            )
        }
    }

    /** The `rev` of a file's metadata: the version token Dropbox's conditional writes take. */
    fun rev(meta: JsonObject): String? = meta.str("rev")

    fun contentHash(meta: JsonObject): String? = meta.str("content_hash")

    fun isFolder(meta: JsonObject): Boolean = meta.str(".tag") == "folder"

    private fun displayPath(
        lower: String,
        name: String,
        display: String?,
    ): String {
        if (lower.isEmpty()) return display?.ifEmpty { "/" } ?: "/"
        val parentLower = lower.substringBeforeLast('/', "")
        val parentDisplay = if (parentLower.isEmpty()) "" else displayByLower[parentLower]
        return when {
            parentDisplay != null && name.isNotEmpty() -> "$parentDisplay/$name"
            display != null -> display
            else -> lower
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    companion object {
        /** The API's form of an engine path: the root is the empty string, every other path starts with `/` and has no trailing `/`. */
        fun apiPath(path: String): String {
            val trimmed = path.trim().trim('/')
            return if (trimmed.isEmpty()) "" else "/$trimmed"
        }

        /**
         * Names Dropbox never syncs (its desktop client's ignore list, which the API refuses with `disallowed_name`):
         * Office lock and temp files, and the system files Windows and macOS drop into folders.
         */
        fun isIgnoredName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("~$") ||
                lower.startsWith(".~") ||
                (lower.startsWith("~") && lower.endsWith(".tmp")) ||
                lower in IGNORED_NAMES
        }

        private val IGNORED_NAMES = setOf("desktop.ini", "thumbs.db", ".ds_store", "icon\r", ".dropbox", ".dropbox.attr")
    }
}
