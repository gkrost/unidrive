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
)
