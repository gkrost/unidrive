package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable

/** One page of a cursor listing: its items and the cursor of the next page, null on the last one. */
data class SyncPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)

// The wire shapes of `GET /files/sync` and `GET /folders/sync`. Every member is optional: a page without
// its array is an empty page, a missing or null `nextCursor` is the last page.
@Serializable
internal data class FilesSyncResponse(
    val files: List<InternxtFile>? = null,
    val nextCursor: String? = null,
)

@Serializable
internal data class FoldersSyncResponse(
    val folders: List<InternxtFolder>? = null,
    val nextCursor: String? = null,
)
