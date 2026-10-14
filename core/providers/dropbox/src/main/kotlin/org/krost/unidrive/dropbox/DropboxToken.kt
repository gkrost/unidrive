package org.krost.unidrive.dropbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The answer of Dropbox's token endpoint. A refresh answers without a new refresh token: the old one stays valid. */
@Serializable
internal data class DropboxTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("account_id") val accountId: String? = null,
)

/** The stored credential (`token.json` in the profile folder). */
@Serializable
data class DropboxToken(
    val accessToken: String,
    val expiresAt: Long,
    val refreshToken: String? = null,
    val accountId: String? = null,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAt

    fun isNearExpiry(thresholdMs: Long = 5 * 60 * 1000): Boolean = System.currentTimeMillis() + thresholdMs >= expiresAt

    fun hasPlausibleShape(): Boolean = accessToken.length >= 32
}
