package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CompleteAuthResult
import java.util.concurrent.ConcurrentHashMap

/**
 * UD-216 — Two-phase device-code auth over MCP, made provider-agnostic
 * by UD-014. The actual flow body lives behind ProviderFactory's
 * beginInteractiveAuth / completeInteractiveAuth / cancelInteractiveAuth
 * SPI methods; this file is just the JSON-RPC adapter.
 *
 * Per-handle device-flow state (HTTP clients, device codes, expiry) is
 * owned by the provider that issued the handle. This file's only
 * persistent state is the [McpHandleRouter] — a flat map from handle
 * to provider-type-id, used to route auth_complete back to the right
 * factory.
 *
 * State is process-scoped. If the MCP server restarts mid-flow the
 * caller just starts over — device codes are cheap and expire in
 * ~15 min anyway.
 */
internal object McpHandleRouter {
    private val routes: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    fun register(
        handle: String,
        providerType: String,
    ) {
        routes[handle] = providerType
    }

    fun providerFor(handle: String): String? = routes[handle]

    fun forget(handle: String) {
        routes.remove(handle)
    }
}

val authBeginTool =
    ToolDef(
        name = "unidrive_auth_begin",
        description =
            "Start the interactive auth flow for the current profile (if it supports interactive auth). " +
                "Returns a continuation handle plus provider-specific instructions (e.g. a verification URL " +
                "and user code for device-flow). Call unidrive_auth_complete with the handle to advance.",
        inputSchema = objectSchema(),
        handler = ::handleAuthBegin,
    )

val authCompleteTool =
    ToolDef(
        name = "unidrive_auth_complete",
        description =
            "Poll once for an outstanding auth flow started by unidrive_auth_begin. " +
                "Returns status=pending (retry later), status=ok (token persisted to disk), " +
                "or status=failed with an error message. The handle is cleared on ok/failed.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("continuation_handle", stringProp("Handle returned by unidrive_auth_begin"))
                    },
                required = listOf("continuation_handle"),
            ),
        handler = ::handleAuthComplete,
    )

private fun handleAuthBegin(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val factory =
        org.krost.unidrive.ProviderRegistry
            .get(ctx.profileInfo.type)
    if (factory == null || !factory.supportsInteractiveAuth()) {
        return buildToolResult(
            "Provider '${ctx.profileInfo.type}' does not support interactive auth " +
                "(unidrive_auth_begin / unidrive_auth_complete).",
            isError = true,
        )
    }

    val result: BeginAuthResult =
        try {
            runBlocking { factory.beginInteractiveAuth(ctx.profileDir) }
        } catch (e: Exception) {
            return buildToolResult(
                "Failed to start interactive auth: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        }

    McpHandleRouter.register(result.continuationHandle, ctx.profileInfo.type)

    return buildToolResult(
        buildJsonObject {
            put("profile", ctx.profileName)
            put("continuation_handle", result.continuationHandle)
            for ((k, v) in result.fields) put(k, v)
        }.toString(),
    )
}

private fun handleAuthComplete(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val handle =
        args["continuation_handle"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'continuation_handle' argument", isError = true)

    val providerType =
        McpHandleRouter.providerFor(handle)
            ?: return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", "Unknown or expired continuation_handle. Call unidrive_auth_begin again.")
                }.toString(),
                isError = true,
            )

    val factory =
        org.krost.unidrive.ProviderRegistry
            .get(providerType)
            ?: return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", "Provider '$providerType' no longer registered.")
                }.toString(),
                isError = true,
            )

    val outcome: CompleteAuthResult =
        try {
            runBlocking { factory.completeInteractiveAuth(ctx.profileDir, handle) }
        } catch (e: Exception) {
            McpHandleRouter.forget(handle)
            return buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", e.message ?: e.javaClass.simpleName)
                }.toString(),
            )
        }

    return when (outcome) {
        is CompleteAuthResult.Pending ->
            buildToolResult(
                buildJsonObject {
                    put("status", "pending")
                    put("retry_after_seconds", outcome.retryAfterSeconds)
                }.toString(),
            )
        CompleteAuthResult.Success -> {
            McpHandleRouter.forget(handle)
            buildToolResult(
                buildJsonObject {
                    put("status", "ok")
                    put("profile", ctx.profileName)
                }.toString(),
            )
        }
        is CompleteAuthResult.Failure -> {
            McpHandleRouter.forget(handle)
            buildToolResult(
                buildJsonObject {
                    put("status", "failed")
                    put("error", outcome.message)
                }.toString(),
            )
        }
    }
}
