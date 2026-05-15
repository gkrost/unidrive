package org.krost.unidrive.webdav

import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.Snapshot
import org.krost.unidrive.sync.SnapshotEntry

/**
 * One file's state as recorded in a delta snapshot cursor.
 * WebDAV has no server-side change feed; we compare PROPFIND listings.
 *
 * The wrapper class lives in `:app:sync` (UD-345); only this entry shape
 * stays in the WebDAV module.
 *
 * UD-008: implements [SnapshotEntry] for shared `defaultDeletedItem` use.
 */
@Serializable
data class WebDavSnapshotEntry(
    val size: Long,
    val lastModified: String?, // RFC-1123 / ISO-8601 string from PROPFIND
    val etag: String?,
    override val isFolder: Boolean,
) : SnapshotEntry

/** Backwards-compatible alias for the existing call sites. */
typealias WebDavSnapshot = Snapshot<WebDavSnapshotEntry>
