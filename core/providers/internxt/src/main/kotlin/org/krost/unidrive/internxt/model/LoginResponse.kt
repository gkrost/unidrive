package org.krost.unidrive.internxt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginChallengeResponse(
    val sKey: String? = null,
    val tfa: Boolean = false,
)

@Serializable
data class LoginAccessResponse(
    val newToken: String,
    val token: String? = null,
    val user: InternxtUser,
)

@Serializable
data class InternxtUser(
    val mnemonic: String,
    @SerialName("root_folder_id") val rootFolderIdNum: Long? = null,
    val rootFolderId: String? = null,
    val uuid: String? = null,
    val email: String? = null,
    val bucket: String? = null,
    val bridgeUser: String? = null,
    val userId: String? = null,
)

@Serializable
data class TokenRefreshResponse(
    val newToken: String,
)
