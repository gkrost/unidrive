package org.krost.unidrive

import java.time.Instant

data class CloudItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val isFolder: Boolean,
    val modified: Instant?,
    val created: Instant?,
    val hash: String?,
    val mimeType: String?,
    val deleted: Boolean = false,
    val hydrated: Boolean = true,
    // Parent's provider UUID. Internxt populates from folderUuid/parentUuid so
    // state.db can index alive children of a folder via (parent_uuid, status).
    // Null = drive root (or provider doesn't expose parent identity).
    val parentId: String? = null,
)
