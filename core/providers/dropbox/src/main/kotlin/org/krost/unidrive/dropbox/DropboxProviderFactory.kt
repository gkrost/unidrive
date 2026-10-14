package org.krost.unidrive.dropbox

import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.PromptSpec
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Files
import java.nio.file.Path

open class DropboxProviderFactory : ProviderFactory {
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

    // #866: the no-redirect code flow. `auth begin` hands out the authorize url and keeps the PKCE
    // verifier as a pending flow in the profile folder; `auth complete --code-stdin` exchanges the
    // code the user pasted. Dropbox has no device-code flow, so there is nothing to poll: one call.

    /** The app key a begin needs when no profile config is in reach: the environment, as `create` falls back to. */
    internal open fun appKeyForBegin(): String =
        System.getenv(DropboxConfig.CLIENT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw org.krost.unidrive.ConfigurationException(
                id,
                "client_id is missing: set ${DropboxConfig.CLIENT_ID_ENV} (the app key of the Dropbox app with PKCE) " +
                    "or put it in the profile as client_id before 'auth begin'",
            )

    internal open fun newAuthForBegin(profileDir: Path): DropboxAuth =
        DropboxAuth(DropboxConfig(appKey = appKeyForBegin(), tokenPath = profileDir), io.ktor.client.HttpClient())

    override fun supportsInteractiveAuth(): Boolean = true

    override suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult {
        val auth = newAuthForBegin(profileDir)
        val verifier = org.krost.unidrive.auth.Pkce.generateVerifier()
        val state = java.util.UUID.randomUUID().toString()
        val handle = "dropbox-" + java.util.UUID.randomUUID()
        val expiresAt = java.time.Instant.now().plus(java.time.Duration.ofMinutes(10))
        val flow = PendingDropboxFlow(handle, verifier, expiresAt.toString())
        try {
            pendingFlowStore(profileDir).save(flow)
        } catch (e: java.io.IOException) {
            // No pending flow means no later process can complete it: do not hand out the handle.
            throw e
        }
        return BeginAuthResult.of(
            continuationHandle = handle,
            fields =
                linkedMapOf(
                    "authorize_url" to kotlinx.serialization.json.JsonPrimitive(
                        auth.authorizationUrlNoRedirect(state, org.krost.unidrive.auth.Pkce.generateChallenge(verifier)),
                    ),
                    "expires_in" to kotlinx.serialization.json.JsonPrimitive(600),
                    "message" to kotlinx.serialization.json.JsonPrimitive(
                        "Open the url, allow the app, and paste the shown code with: auth complete --handle $handle --code-stdin",
                    ),
                ),
            expiresAt = expiresAt,
        )
    }

    override suspend fun completeInteractiveAuth(
        profileDir: Path,
        continuationHandle: String,
        secret: String?,
    ): CompleteAuthResult {
        val store = pendingFlowStore(profileDir)
        val flow = store.load()
            ?: return CompleteAuthResult.Failure(
                "Unknown or expired continuation handle. Call auth begin again.",
                CompleteAuthResult.Reason.UNKNOWN_HANDLE,
            )
        if (flow.handle != continuationHandle) {
            return CompleteAuthResult.Failure(
                "Unknown or expired continuation handle. Call auth begin again.",
                CompleteAuthResult.Reason.UNKNOWN_HANDLE,
            )
        }
        if (java.time.Instant.parse(flow.expiresAt).isBefore(java.time.Instant.now())) {
            store.delete()
            return CompleteAuthResult.Failure("The sign-in window has passed. Call auth begin again.", CompleteAuthResult.Reason.EXPIRED)
        }
        val code = secret?.trim()?.takeIf { it.isNotEmpty() }
            ?: return CompleteAuthResult.Failure(
                "The code Dropbox showed is required: pipe it with --code-stdin.",
            )
        val auth = newAuthForBegin(profileDir)
        return try {
            val token = auth.exchangeCodeNoRedirect(code, flow.verifier)
            auth.saveToken(token)
            store.delete()
            CompleteAuthResult.Success
        } catch (e: org.krost.unidrive.AuthenticationException) {
            // A wrong or spent code stores nothing; the pending flow stays until it expires so the
            // same handle can try again with a freshly shown code.
            CompleteAuthResult.Failure("Dropbox refused the code: ${e.message}")
        }
    }

    /** The pending no-redirect flow: the PKCE verifier and the expiry, kept for the completing process. */
    @kotlinx.serialization.Serializable
    data class PendingDropboxFlow(
        val handle: String,
        val verifier: String,
        val expiresAt: String,
    )

    private fun pendingFlowStore(profileDir: Path) =
        org.krost.unidrive.auth.CredentialStore(profileDir, PENDING_FLOW_FILE, PendingDropboxFlow.serializer())

    internal companion object {
        const val PENDING_FLOW_FILE = "pending_dropbox_flow.json"
    }

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
