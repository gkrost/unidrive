package org.krost.unidrive.onedrive.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("ext_expires_in") val extExpiresIn: Long? = null,
)

@Serializable
data class Token(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Long,
    val refreshToken: String? = null,
    val scope: String? = null,
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAt

    fun isNearExpiry(thresholdMs: Long = 5 * 60 * 1000): Boolean = System.currentTimeMillis() + thresholdMs >= expiresAt

    fun hasPlausibleAccessTokenShape(): Boolean = accessToken.length >= 32
}

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Long,
    val interval: Long,
    val message: String,
)
