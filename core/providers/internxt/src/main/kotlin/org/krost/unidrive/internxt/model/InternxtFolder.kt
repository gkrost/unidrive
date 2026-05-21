package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

@Serializable
data class InternxtFolder(
    val id: Long = 0,
    val uuid: String,
    val name: String? = null,
    val plainName: String? = null,
    val type: String? = null,
    val parentId: Long = 0,
    val parentUuid: String? = null,
    val bucket: String? = null,
    val encryptVersion: String? = null,
    val status: String = "EXISTS",
    val size: Long = 0,
    val creationTime: String? = null,
    val modificationTime: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val removed: Boolean = false,
    val deleted: Boolean = false,
) {
    // UD-372: see InternxtFile — eagerly parse the two timestamps that the hot delta() loop
    // consumes; createdAt / updatedAt stay as String (cursor lexicographic max only).
    @Transient
    val creationInstant: Instant? = tryParseInternxtInstant(creationTime)

    @Transient
    val modificationInstant: Instant? = tryParseInternxtInstant(modificationTime)
}

@Serializable
data class FolderListResponse(
    val list: List<InternxtFolder> = emptyList(),
)
