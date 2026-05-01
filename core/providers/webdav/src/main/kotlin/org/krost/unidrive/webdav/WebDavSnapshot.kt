package org.krost.unidrive.webdav

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot

/**
 * One file's state as recorded in a delta snapshot cursor.
 * WebDAV has no server-side change feed; we compare PROPFIND listings.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * stays in the WebDAV module.
 */
@Serializable
data class WebDavSnapshotEntry(
    val size: Long,
    val lastModified: String?, // RFC-1123 / ISO-8601 string from PROPFIND
    val etag: String?,
    val isFolder: Boolean,
)

/** Backwards-compatible alias for the existing call sites. */
typealias WebDavSnapshot = Snapshot<WebDavSnapshotEntry>
