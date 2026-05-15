package org.krost.unidrive.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * UD-010: shared JWT body parsing for the three sites that previously
 * each rolled their own base64url-decode + claim-extract:
 *   - `OneDriveProviderFactory.extractJwtClaim` (regex over the body)
 *   - `InternxtProviderFactory.checkCredentialHealth` (exp regex)
 *   - `AuthService.isJwtExpired` (exp via kotlinx-serialization)
 *
 * This helper does NOT verify the JWT signature. Signature verification
 * happens at the API layer when the token is actually used; for our
 * purposes (display the user's preferred_username, sniff `exp` for a
 * proactive refresh) we trust the issuer and just need the claim value.
 *
 * UD-308 follow-up (PR #23 Codex P2): base64url payload segments need
 * padding so the total length is a multiple of 4. The pre-UD-308 OneDrive
 * + Internxt copies used a hardcoded `+ "=="` that only worked when
 * `raw.length % 4 == 2`; for `% 4` in {0, 3} the decode threw. The shared
 * impl pads based on `length % 4`, matching the UD-308 fix in
 * `AuthService.isJwtExpired`.
 */
object JwtExtractor {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decode the JWT body (middle segment) into a [JsonObject], or `null`
     * if the input is not a parseable JWT.
     *
     * Returns `null` (does not throw) for: missing `.` separator, malformed
     * base64, or non-JSON body. Callers that need a more specific signal
     * (e.g. "treat unparseable as expired") apply that policy on top.
     */
    fun decodeBody(jwt: String): JsonObject? {
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        val raw = parts[1]
        val padding = (4 - raw.length % 4) % 4
        val payload =
            try {
                String(
                    Base64
                        .getUrlDecoder()
                        .decode(raw + "=".repeat(padding)),
                )
            } catch (_: IllegalArgumentException) {
                return null
            }
        return try {
            json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract a string-valued claim from the JWT body, or `null` if the
     * JWT is malformed or the claim is absent / non-string.
     */
    fun extractClaim(
        jwt: String,
        claim: String,
    ): String? = decodeBody(jwt)?.get(claim)?.jsonPrimitive?.contentOrNull

    /**
     * Extract the numeric `exp` claim (Unix seconds), or `null` if absent
     * or unparseable.
     */
    fun extractExp(jwt: String): Long? = decodeBody(jwt)?.get("exp")?.jsonPrimitive?.longOrNull
}
