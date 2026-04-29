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

    /** UD-310: true if the token expires within [thresholdMs] from now, even if still nominally valid. */
    fun isNearExpiry(thresholdMs: Long = 5 * 60 * 1000): Boolean = System.currentTimeMillis() + thresholdMs >= expiresAt

    /**
     * UD-312: cheap sanity check on the access_token's shape before returning
     * it to a caller that's about to send it to Graph. We deliberately do NOT
     * assert the JWS compact form (three dot-separated segments) — personal
     * Microsoft accounts mint OPAQUE bearer tokens (no dots, ~1.5 KB prefixed
     * `EwB…`), while work/school accounts mint JWTs (3 segments). Both are
     * valid at Graph; only "empty or truncated" is the failure mode we guard
     * here — matches the 32-char floor used by `scripts/dev/oauth-mcp/
     * graph_client.py::refresh`.
     */
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
