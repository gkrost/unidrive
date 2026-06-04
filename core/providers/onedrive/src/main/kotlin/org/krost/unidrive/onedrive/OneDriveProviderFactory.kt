package org.krost.unidrive.onedrive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.auth.JwtExtractor
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

open class OneDriveProviderFactory : ProviderFactory {
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
                JwtExtractor.extractClaim(token.accessToken, "preferred_username")
                    ?: JwtExtractor.extractClaim(token.accessToken, "upn")
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
            maxConcurrentTransfers = 8,
            minRequestSpacingMs = 200L,
            // Backwards-compat: existing users have a ~/OneDrive directory.
            // Title-casing the id would give ~/Onedrive, silently orphaning it.
            syncRootDirName = "OneDrive",
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

    override fun supportsInteractiveAuth(): Boolean = true

    override suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult {
        val oauth = newOAuthServiceForBegin(profileDir)
        val deviceCode =
            try {
                oauth.getDeviceCode()
            } catch (e: Exception) {
                // No handle issued yet — close the HttpClient here, the
                // registry-cleanup paths cannot reach it.
                oauth.close()
                throw e
            }

        val state =
            OneDriveDeviceFlowState(
                deviceCode = deviceCode.deviceCode,
                expiresAtMillis = System.currentTimeMillis() + deviceCode.expiresIn * 1000L,
                oauthService = oauth,
            )
        val handle = OneDriveDeviceFlowRegistry.put(state)

        return BeginAuthResult.of(
            continuationHandle = handle,
            // UD-375: typed JsonPrimitive values so the MCP serializer emits
            // numerics as JSON numbers (not strings). Pre-fix the .toString()
            // on `interval` and `expiresIn` made every numeric field arrive
            // as a JSON string on the wire, breaking clients parsing it as
            // a number.
            fields =
                linkedMapOf(
                    "verification_uri" to JsonPrimitive(deviceCode.verificationUri),
                    "user_code" to JsonPrimitive(deviceCode.userCode),
                    "interval_seconds" to JsonPrimitive(deviceCode.interval),
                    "expires_in" to JsonPrimitive(deviceCode.expiresIn),
                    "message" to JsonPrimitive(deviceCode.message),
                ),
            expiresAt = Instant.ofEpochMilli(state.expiresAtMillis),
            retryAfterSeconds = deviceCode.interval,
        )
    }

    override suspend fun completeInteractiveAuth(
        profileDir: Path,
        continuationHandle: String,
    ): CompleteAuthResult {
        val state =
            OneDriveDeviceFlowRegistry.get(continuationHandle)
                ?: return CompleteAuthResult.Failure(
                    "Unknown or expired continuation_handle. Call auth_begin again.",
                )

        if (System.currentTimeMillis() > state.expiresAtMillis) {
            OneDriveDeviceFlowRegistry.remove(continuationHandle)
            state.oauthService.close()
            return CompleteAuthResult.Failure("Device code expired. Call auth_begin again.")
        }

        val oauth = state.oauthService
        // pollOnceForToken is total: it catches its own network errors and
        // returns DevicePollOutcome.Failed. The only way an exception escapes
        // is via 2xx-with-malformed-body (SerializationException from the
        // TokenResponse decode at OAuthService.kt:193) or an unexpected JVM
        // error. Either way we drain the registry and surface Failure.
        val outcome: OAuthService.DevicePollOutcome =
            try {
                oauth.pollOnceForToken(state.deviceCode)
            } catch (e: Exception) {
                OneDriveDeviceFlowRegistry.remove(continuationHandle)
                oauth.close()
                return CompleteAuthResult.Failure(e.message ?: e.javaClass.simpleName)
            }

        return when (outcome) {
            is OAuthService.DevicePollOutcome.Pending ->
                CompleteAuthResult.Pending(outcome.retryAfterSeconds)
            is OAuthService.DevicePollOutcome.Success -> {
                try {
                    oauth.saveToken(outcome.token)
                } catch (e: Exception) {
                    OneDriveDeviceFlowRegistry.remove(continuationHandle)
                    oauth.close()
                    return CompleteAuthResult.Failure("Token received but save failed: ${e.message}")
                }
                OneDriveDeviceFlowRegistry.remove(continuationHandle)
                oauth.close()
                CompleteAuthResult.Success
            }
            is OAuthService.DevicePollOutcome.Failed -> {
                OneDriveDeviceFlowRegistry.remove(continuationHandle)
                oauth.close()
                CompleteAuthResult.Failure(outcome.message)
            }
        }
    }

    override suspend fun cancelInteractiveAuth(continuationHandle: String) {
        OneDriveDeviceFlowRegistry.remove(continuationHandle)?.oauthService?.close()
    }

    /** UD-014 test seam: subclassed in OneDriveInteractiveAuthContractTest
     *  to inject a Ktor MockEngine-backed HttpClient. Production code
     *  uses the default. Open + internal so the test in the same module
     *  can override; never overridden in production. */
    internal open fun newOAuthServiceForBegin(profileDir: Path): OAuthService =
        OAuthService(OneDriveConfig(tokenPath = profileDir))
}
