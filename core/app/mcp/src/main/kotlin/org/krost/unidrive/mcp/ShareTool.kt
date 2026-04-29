package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.CapabilityResult

val shareTool =
    ToolDef(
        name = "unidrive_share",
        description = "Creates, lists, or revokes share links for files and folders.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("action", stringProp("Action to perform", enum = listOf("create", "list", "revoke")))
                        put("path", stringProp("File or folder path"))
                        put("expiryHours", intProp("Link expiry in hours (default: 24, for 'create')"))
                        put("password", stringProp("Optional password for the share link (for 'create')"))
                        put("shareId", stringProp("Share link ID (required for 'revoke')"))
                    },
                required = listOf("action", "path"),
            ),
        handler = ::handleShare,
    )

private fun handleShare(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val action =
        args["action"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'action' parameter", isError = true)
    val path =
        args["path"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'path' parameter", isError = true)
    val normalizedPath = if (path.startsWith("/")) path else "/$path"

    val provider = ctx.createProvider()
    runBlocking { provider.authenticate() }

    return when (action) {
        "create" -> {
            val expiryHours = args["expiryHours"]?.jsonPrimitive?.intOrNull ?: 24
            val password = args["password"]?.jsonPrimitive?.content
            when (val result = runBlocking { provider.share(normalizedPath, expiryHours, password) }) {
                is CapabilityResult.Success ->
                    buildToolResult(
                        buildJsonObject {
                            put("url", result.value)
                            put("path", normalizedPath)
                            put("expiryHours", expiryHours)
                        }.toString(),
                    )
                is CapabilityResult.Unsupported ->
                    buildToolResult(
                        "Unsupported: ${result.reason}",
                        isError = true,
                    )
            }
        }
        "list" -> {
            when (val result = runBlocking { provider.listShares(normalizedPath) }) {
                is CapabilityResult.Success -> {
                    val arr =
                        buildJsonArray {
                            for (s in result.value) {
                                addJsonObject {
                                    put("id", s.id)
                                    put("url", s.url)
                                    put("type", s.type)
                                    put("scope", s.scope)
                                    put("hasPassword", s.hasPassword)
                                    if (s.expiration != null) put("expiration", s.expiration)
                                }
                            }
                        }
                    buildToolResult(arr.toString())
                }
                is CapabilityResult.Unsupported ->
                    buildToolResult(
                        "Unsupported: ${result.reason}",
                        isError = true,
                    )
            }
        }
        "revoke" -> {
            val shareId =
                args["shareId"]?.jsonPrimitive?.content
                    ?: return buildToolResult("Missing 'shareId' for revoke", isError = true)
            when (val result = runBlocking { provider.revokeShare(normalizedPath, shareId) }) {
                is CapabilityResult.Success ->
                    buildToolResult(
                        buildJsonObject {
                            put("revoked", shareId)
                        }.toString(),
                    )
                is CapabilityResult.Unsupported ->
                    buildToolResult(
                        "Unsupported: ${result.reason}",
                        isError = true,
                    )
            }
        }
        else -> buildToolResult("Unknown action: $action. Use 'create', 'list', or 'revoke'.", isError = true)
    }
}
