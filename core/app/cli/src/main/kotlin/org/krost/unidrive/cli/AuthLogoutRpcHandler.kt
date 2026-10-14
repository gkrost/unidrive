package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `auth.logout` IPC verb (docs/dev/specs/unidrive-daemon-design.md §4.10): a front-end signs an
 * account out while its daemon is running. The daemon — the profile-lock holder — revokes the
 * provider's token, deletes the credential files and goes on running without credentials; the sync
 * state is never touched (a front-end's sign-out must not lose it, and the daemon holds state.db
 * open). The reply says what happened; the CLI's `logout --json` wraps it.
 *
 * - Refused with `uploads_pending` (with `pending` / `failed` counts) while the state.db still holds
 *   uploads on their way — without credentials they cannot finish; `profile remove` counts them by the
 *   same rule. `"force":true` signs out anyway.
 * - Refused with `credential_protection_unavailable` when a credential marker's OS secret-store item
 *   could not be removed; nothing is deleted, the sign-out can be retried.
 * - `revoked` is true when the provider confirmed the revocation, false when it could not be reached
 *   or refused it (the local cleanup goes on either way), null when the profile was already signed out.
 *
 * Admin class: a `read` connection gets `forbidden` ([IpcAuth] default).
 */
internal class AuthLogoutRpcHandler(
    private val profileDir: Path,
    private val db: StateDatabase,
    private val providerFactory: () -> CloudProvider,
    // After a sign-out the daemon's own provider instance must stop reporting itself authenticated.
    private val markUnauthenticated: () -> Unit,
) {
    private val log = LoggerFactory.getLogger(AuthLogoutRpcHandler::class.java)

    suspend fun handle(jsonRequest: String): String {
        val body = runCatching { Json.parseToJsonElement(jsonRequest) as? JsonObject }.getOrNull()
        val force =
            when (val element = body?.get("force")) {
                null -> false
                else -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: return INVALID_FORCE
            }
        if (!force) {
            val rows = db.pendingUploadRows()
            val pending = rows.size
            val failed = rows.count { it.lastErrorAt != null }
            if (pending - failed > 0) {
                return """{"ok":false,"error":"uploads_pending","pending":$pending,"failed":$failed}"""
            }
        }
        // Any failure of the revocation attempt (the provider unreachable, the revocation refused) means
        // "not confirmed": the sign-out still goes on with the local cleanup, and the reply says false.
        @Suppress("TooGenericExceptionCaught")
        val revoked =
            try {
                val provider = providerFactory()
                try {
                    provider.logout()
                } finally {
                    provider.close()
                }
                true
            } catch (_: AuthenticationException) {
                null
            } catch (e: CredentialProtectionException) {
                log.warn("auth.logout: the credential marker of '{}' could not be removed from the secret store: {}", profileDir, e.message)
                return CREDENTIAL_PROTECTION
            } catch (e: Exception) {
                log.warn("auth.logout: revoking the token of '{}' failed: {}", profileDir, e.message)
                false
            }
        var credentialsRemoved = false
        for (name in StoragePermissions.CREDENTIAL_FILES) {
            if (Files.deleteIfExists(profileDir.resolve(name))) credentialsRemoved = true
        }
        markUnauthenticated()
        return """{"ok":true,"revoked":$revoked,"credentials_removed":$credentialsRemoved}"""
    }

    companion object {
        const val VERB: String = "auth.logout"

        private const val INVALID_FORCE = """{"ok":false,"error":"invalid_force"}"""
        private const val CREDENTIAL_PROTECTION = """{"ok":false,"error":"credential_protection_unavailable"}"""
    }
}
