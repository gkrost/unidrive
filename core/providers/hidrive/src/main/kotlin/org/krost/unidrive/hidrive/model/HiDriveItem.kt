package org.krost.unidrive.hidrive.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HiDriveItem(
    val id: String? = null,
    val name: String? = null,
    val path: String? = null,
    val type: String? = null,
    val size: Long = 0,
    val mtime: Long? = null,
    val created: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val chash: String? = null,
    val mhash: String? = null,
    val nhash: String? = null,
    val members: List<HiDriveItem>? = null,
)

@Serializable
data class HiDriveUserInfo(
    val account: String? = null,
    val alias: String? = null,
    @SerialName("home") val homePath: String? = null,
    val quota: HiDriveQuota? = null,
)

@Serializable
data class HiDriveQuota(
    val limit: Long = 0,
    val used: Long = 0,
)
