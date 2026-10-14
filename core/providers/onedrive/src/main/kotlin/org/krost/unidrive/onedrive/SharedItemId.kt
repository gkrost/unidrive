package org.krost.unidrive.onedrive

/**
 * A shared-with-me item lives in another user's drive, so the item id alone cannot address it: Graph answers
 * `/me/drive/items/{id}` from the caller's own drive. The pair (drive id, item id) from the item's `remoteItem`
 * does address it, as `/drives/{driveId}/items/{itemId}`.
 *
 * The engine stores one opaque string per item, so the pair travels inside the id: `shared:{driveId}:{itemId}`, with
 * `%` and `:` written as `%25` and `%3A` in each part so the split is unambiguous whatever the drive hands out.
 */
internal data class SharedItemRef(
    val driveId: String,
    val itemId: String,
) {
    val id: String get() = SharedItemId.PREFIX + escape(driveId) + ":" + escape(itemId)

    private fun escape(part: String): String = part.replace("%", "%25").replace(":", "%3A")
}

internal object SharedItemId {
    const val PREFIX = "shared:"

    fun isShared(id: String): Boolean = id.startsWith(PREFIX)

    /**
     * The pair behind [id], or null when [id] is not a shared id at all. A shared id of any other shape (the
     * single-part `shared:{itemId}` an earlier listing wrote) names no drive and cannot be addressed: it is rejected.
     */
    fun parse(id: String): SharedItemRef? {
        if (!isShared(id)) return null
        val parts = id.removePrefix(PREFIX).split(':')
        if (parts.size != 2 || parts.any { it.isEmpty() }) {
            throw GraphApiException(
                "API error: 404 Not Found - {\"error\":{\"code\":\"itemNotFound\",\"message\":\"shared item id '$id' names no drive\"}}",
                404,
            )
        }
        return SharedItemRef(unescape(parts[0]), unescape(parts[1]))
    }

    private fun unescape(part: String): String = part.replace("%3A", ":").replace("%25", "%")
}
