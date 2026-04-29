package org.krost.unidrive.onedrive

import kotlinx.serialization.json.Json
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path

class OneDriveProviderFactory : ProviderFactory {
    override val id = "onedrive"

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        val tokenFile = profileDir.resolve("token.json")
        if (!Files.exists(tokenFile)) return "onedrive (no token)"
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val token = json.decodeFromString<Token>(Files.readString(tokenFile))
            val parts = mutableListOf<String>()
            // Try to extract email/upn from JWT access token payload
            val email =
                extractJwtClaim(token.accessToken, "preferred_username")
                    ?: extractJwtClaim(token.accessToken, "upn")
            if (email != null) parts += email
            // Token expiry
            val expiresAt = java.time.Instant.ofEpochMilli(token.expiresAt)
            val now = java.time.Instant.now()
            if (now.isAfter(expiresAt)) {
                parts += "token expired (refresh available)"
            } else {
                val minutes =
                    java.time.Duration
                        .between(now, expiresAt)
                        .toMinutes()
                parts += "token expires in ${minutes}m"
            }
            "onedrive (${parts.joinToString(", ")})"
        } catch (_: Exception) {
            "onedrive (token unreadable)"
        }
    }

    /** Best-effort JWT payload claim extraction — no signature verification. */
    private fun extractJwtClaim(
        jwt: String,
        claim: String,
    ): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payload =
                String(
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(parts[1]),
                )
            // Simple extraction without pulling in a JSON parse for a single field
            val regex = """"$claim"\s*:\s*"([^"]+)"""".toRegex()
            regex.find(payload)?.groupValues?.get(1)
        } catch (_: Exception) {
            null
        }
    }

    override val metadata =
        ProviderMetadata(
            id = "onedrive",
            displayName = "Microsoft OneDrive",
            description = "Microsoft 365 personal and business cloud storage",
            authType = "OAuth2 (browser)",
            encryption = "Server-side (Microsoft-managed keys)",
            jurisdiction = "USA (with EU Data Boundary option)",
            gdprCompliant = true,
            cloudActExposure = true,
            signupUrl = null,
            tier = "Global",
            userRating = 4.5,
            benchmarkGrade = "A",
            // UD-263: matches HttpRetryBudget(maxConcurrency = 8) in
            // GraphApiService — UD-712 measured ~119 files/min as steady-state.
            // 200 ms spacing follows UD-200's minSpacingMs default for
            // post-throttle cooldown; 0 ms steady-state is enforced inside
            // HttpRetryBudget so SyncEngine doesn't double-pace.
            maxConcurrentTransfers = 8,
            minRequestSpacingMs = 200L,
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val authEndpoint = properties["authority_url"] ?: OneDriveConfig.DEFAULT_AUTH_ENDPOINT
        val includeShared = properties["include_shared"]?.toBoolean() ?: false
        val config =
            OneDriveConfig(
                tokenPath = tokenPath,
                authEndpoint = authEndpoint,
                includeShared = includeShared,
            )
        return OneDriveProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = Files.exists(profileDir.resolve("token.json"))

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        val tokenFile = profileDir.resolve("token.json")
        if (!Files.exists(tokenFile)) {
            return CredentialHealth.Missing("No token.json — run 'unidrive -p onedrive auth'")
        }
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val token = json.decodeFromString<Token>(Files.readString(tokenFile))
            when {
                token.isExpired && token.refreshToken == null ->
                    CredentialHealth.Warning("Access token expired, no refresh token — re-auth required")
                token.isExpired ->
                    CredentialHealth.Ok // expired access token with refresh token is normal; authenticate() will refresh
                else -> {
                    val hoursRemaining =
                        java.time.Duration
                            .between(
                                java.time.Instant.now(),
                                java.time.Instant.ofEpochMilli(token.expiresAt),
                            ).toHours()
                    if (hoursRemaining < 24) {
                        CredentialHealth.ExpiresIn(hoursRemaining, "${hoursRemaining}h")
                    } else {
                        CredentialHealth.Ok
                    }
                }
            }
        } catch (_: Exception) {
            CredentialHealth.Warning("token.json exists but could not be parsed")
        }
    }
}
