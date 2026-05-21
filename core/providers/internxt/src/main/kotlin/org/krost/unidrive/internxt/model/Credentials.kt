package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable

@Serializable
data class InternxtCredentials(
    val jwt: String,
    val mnemonic: String,
    val rootFolderId: String,
    val email: String,
    val bridgeUser: String = "",
    val bridgeUserId: String = "",
    val bucket: String = "",
)
