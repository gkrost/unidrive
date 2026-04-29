package org.krost.unidrive.hidrive.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HiDriveTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
)

@Serializable
data class HiDriveToken(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Long,
    val refreshToken: String? = null,
    val scope: String? = null,
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAt - 60_000
}
