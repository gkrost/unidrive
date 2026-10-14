package org.krost.unidrive.dropbox

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.PromptSpec
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Files
import java.nio.file.Path

class DropboxProviderFactory : ProviderFactory {
    override val id = "dropbox"

    override val metadata =
        ProviderMetadata(
            id = "dropbox",
            displayName = "Dropbox",
            description = "Dropbox personal and team cloud storage",
            authType = "OAuth2 (browser, PKCE)",
            encryption = "Server-side (Dropbox-managed keys)",
            jurisdiction = "USA",
            gdprCompliant = true,
            cloudActExposure = true,
            signupUrl = null,
            tier = "Global",
            maxConcurrentTransfers = 4,
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider = DropboxProvider(DropboxConfig(appKey = appKey(properties), tokenPath = tokenPath))

    private fun appKey(properties: Map<String, String?>): String =
        properties["client_id"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv(DropboxConfig.CLIENT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ConfigurationException(
                id,
                "client_id is missing: register a Dropbox app (scoped access, PKCE) with the redirect URI " +
                    "http://localhost:${DropboxConfig.DEFAULT_OAUTH_CALLBACK_PORT}/callback and put its app key in the profile as client_id, " +
                    "or set ${DropboxConfig.CLIENT_ID_ENV}",
            )

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = Files.exists(profileDir.resolve(DropboxAuth.TOKEN_FILE))

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        if (properties["client_id"].isNullOrBlank() && System.getenv(DropboxConfig.CLIENT_ID_ENV).isNullOrBlank()) {
            return CredentialHealth.Missing("No client_id")
        }
        val tokenFile = profileDir.resolve(DropboxAuth.TOKEN_FILE)
        if (!Files.exists(tokenFile)) return CredentialHealth.Missing("No token.json")
        return try {
            val token = org.krost.unidrive.UnidriveJson.decodeFromString(DropboxToken.serializer(), Files.readString(tokenFile))
            when {
                // A refresh token renews the short-lived access token on its own.
                token.refreshToken != null -> CredentialHealth.Ok
                token.isExpired -> CredentialHealth.ExpiresIn(0, "Access token expired, no refresh token — sign in again", remainingMs = 0)
                else -> {
                    val remainingMs = token.expiresAt - System.currentTimeMillis()
                    CredentialHealth.ExpiresIn(remainingMs / 3_600_000, "${remainingMs / 3_600_000}h", remainingMs = remainingMs)
                }
            }
        } catch (_: Exception) {
            CredentialHealth.Warning("token.json exists but could not be parsed")
        }
    }

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String = if (isAuthenticated(properties, profileDir)) "dropbox (signed in)" else "dropbox (no token)"

    override fun credentialPrompts(): List<PromptSpec> =
        listOf(PromptSpec(key = "client_id", label = "Dropbox app key (from the Dropbox App Console)"))

    override fun envVarMappings(): Map<String, String> = mapOf(DropboxConfig.CLIENT_ID_ENV to "client_id")
}
