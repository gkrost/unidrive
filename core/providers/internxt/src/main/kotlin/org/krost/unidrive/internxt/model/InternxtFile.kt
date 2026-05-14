package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

@Serializable
data class InternxtFile(
    val id: Long = 0,
    val uuid: String,
    val fileId: String? = null,
    val name: String? = null,
    val plainName: String? = null,
    val type: String? = null,
    val size: String = "0",
    val bucket: String? = null,
    val folderId: Long = 0,
    val folderUuid: String? = null,
    val encryptVersion: String? = null,
    val status: String = "EXISTS",
    val creationTime: String? = null,
    val modificationTime: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val removed: Boolean = false,
    val deleted: Boolean = false,
) {
    // UD-372: eagerly parse the two timestamps that the hot delta() loop reads so
    // toDeltaCloudItem doesn't pay the `?.let { parseTime(it) }` lambda + try/catch cost
    // per row. createdAt / updatedAt stay as String — they're only used as cursor strings
    // (lexicographic max in InternxtProvider.delta).
    @Transient
    val creationInstant: Instant? = tryParseInternxtInstant(creationTime)

    @Transient
    val modificationInstant: Instant? = tryParseInternxtInstant(modificationTime)
}

@Serializable
data class FolderContentResponse(
    val children: List<InternxtFolder> = emptyList(),
    val files: List<InternxtFile> = emptyList(),
)
