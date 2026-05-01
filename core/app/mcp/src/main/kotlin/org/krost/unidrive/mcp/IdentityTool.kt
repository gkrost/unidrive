package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.onedrive.OneDriveProvider

/**
 * UD-216 — wrap Graph `/me` (or provider-equivalent) for the current profile.
 *
 * Contract (per ticket):
 *   - Returns display name + email + tenant for providers that support it.
 *   - Providers that don't support `/me` return `{ supported: false }`.
 *
 * Only OneDrive implements this today; the rest take the Unsupported path.
 * When additional providers gain identity endpoints, extend the `when` here
 * rather than trying to widen [CloudProvider] — keeps the interface lean.
 */
val identityTool =
    ToolDef(
        name = "unidrive_identity",
        description =
            "Return the authenticated user's identity (display name, email, user principal name) " +
                "for the current profile. For providers without an identity endpoint, returns " +
                "{supported: false}.",
        inputSchema = objectSchema(),
        handler = ::handleIdentity,
    )

private fun handleIdentity(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val provider =
        try {
            ctx.createProvider()
        } catch (e: Exception) {
            return buildToolResult(
                "Failed to create provider for profile '${ctx.profileName}': ${e.message}",
                isError = true,
            )
        }

    if (provider !is OneDriveProvider) {
        return buildToolResult(
            buildJsonObject {
                put("supported", false)
                put("providerType", ctx.profileInfo.type)
                put("profile", ctx.profileName)
            }.toString(),
        )
    }

    return try {
        runBlocking { provider.authenticateAndLog() }
        val me = runBlocking { provider.getIdentity() }
        buildToolResult(
            buildJsonObject {
                put("supported", true)
                put("providerType", ctx.profileInfo.type)
                put("profile", ctx.profileName)
                put("displayName", me.displayName?.let { JsonPrimitive(it) } ?: JsonNull)
                put("userPrincipalName", me.userPrincipalName?.let { JsonPrimitive(it) } ?: JsonNull)
                put("mail", me.mail?.let { JsonPrimitive(it) } ?: JsonNull)
                put("id", me.id?.let { JsonPrimitive(it) } ?: JsonNull)
                // Derive tenant from the UPN when present (user@tenant.onmicrosoft.com / user@corp.com).
                val tenant =
                    (me.userPrincipalName ?: me.mail)
                        ?.substringAfter('@', missingDelimiterValue = "")
                        ?.takeIf { it.isNotBlank() } ?: ""
                put("tenant", tenant)
            }.toString(),
        )
    } catch (e: Exception) {
        buildToolResult(
            "Failed to resolve identity: ${e.message ?: e.javaClass.simpleName}",
            isError = true,
        )
    }
}
