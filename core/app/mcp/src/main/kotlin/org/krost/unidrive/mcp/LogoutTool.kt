package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.AuthenticationException
import java.nio.file.Files

/**
 * UD-216 — MCP parity with the CLI `logout` command.
 *
 * Safety rail: refuses to act without an explicit `confirm: true` argument.
 * Destructive ops over MCP go through an LLM with no native notion of a
 * "Are you sure?" prompt; the explicit flag forces the caller to make the
 * intent legible in the tool-call itself.
 */
val logoutTool =
    ToolDef(
        name = "unidrive_logout",
        description =
            "Log out of the current profile: revoke the token if the provider supports it, " +
                "then delete token.json / credentials.json and (by default) state.db + failures.jsonl. " +
                "REQUIRES confirm=true. Pass keepState=true to keep the sync state database.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("confirm", booleanProp("Must be true — safety rail for a destructive op."))
                        put("keepState", booleanProp("If true, preserve state.db and failures.jsonl (default: false)."))
                    },
                required = listOf("confirm"),
            ),
        handler = ::handleLogout,
    )

private fun handleLogout(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val confirm = args["confirm"]?.jsonPrimitive?.booleanOrNull ?: false
    if (!confirm) {
        return buildToolResult(
            "unidrive_logout requires confirm=true (destructive: removes credentials and, by default, state.db).",
            isError = true,
        )
    }

    val keepState = args["keepState"]?.jsonPrimitive?.booleanOrNull ?: false

    // Attempt to revoke tokens with the provider. Same fall-through as CLI:
    // if revocation fails we still clean up local files.
    try {
        val provider = ctx.createProvider()
        try {
            runBlocking { provider.logout() }
        } catch (_: AuthenticationException) {
            // Already not authenticated — fine.
        } catch (_: Exception) {
            // Network error, provider unreachable — continue with local cleanup.
        }
    } catch (_: Exception) {
        // Can't construct a provider (bad config, missing creds) — still proceed.
    }

    val deleted = mutableListOf<String>()
    val tokenFile = ctx.profileDir.resolve("token.json")
    val credentialsFile = ctx.profileDir.resolve("credentials.json")
    if (Files.deleteIfExists(tokenFile)) deleted.add(tokenFile.toString())
    if (Files.deleteIfExists(credentialsFile)) deleted.add(credentialsFile.toString())

    if (!keepState) {
        val stateDb = ctx.profileDir.resolve("state.db")
        val failures = ctx.profileDir.resolve("failures.jsonl")
        if (Files.deleteIfExists(stateDb)) deleted.add(stateDb.toString())
        if (Files.deleteIfExists(failures)) deleted.add(failures.toString())
    }

    return buildToolResult(
        buildJsonObject {
            put("profile", ctx.profileName)
            put("keepState", keepState)
            putJsonArray("deleted") {
                deleted.forEach { add(it) }
            }
        }.toString(),
    )
}
