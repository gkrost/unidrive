package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable

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
    // UD-359: server-provided deletion flags. Until 2026-05-03 these were not
    // deserialised, so server schema drift to `removed=true`/`deleted=true`
    // while leaving `status="EXISTS"` would mask deletions. Folder DTO already
    // had both — files now match (see InternxtProvider.fileToCloudItem and
    // fileToDeltaCloudItem for the OR-into-deleted handling).
    val removed: Boolean = false,
    val deleted: Boolean = false,
)

@Serializable
data class FolderContentResponse(
    val children: List<InternxtFolder> = emptyList(),
    val files: List<InternxtFile> = emptyList(),
)
