package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable

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
)

@Serializable
data class FolderListResponse(
    val list: List<InternxtFolder> = emptyList(),
)
