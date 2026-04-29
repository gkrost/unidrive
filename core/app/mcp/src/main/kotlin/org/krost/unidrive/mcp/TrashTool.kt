package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.sync.TrashManager

val trashTool =
    ToolDef(
        name = "unidrive_trash",
        description = "Manages the local trash bin. Lists, restores, or purges trashed files that were deleted during sync.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("action", stringProp("Action to perform", enum = listOf("list", "restore", "purge")))
                        put("path", stringProp("Original file path (required for 'restore')"))
                        put("retentionDays", intProp("Purge entries older than N days (default: 30)"))
                    },
                required = listOf("action"),
            ),
        handler = ::handleTrash,
    )

private fun handleTrash(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val action =
        args["action"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'action' parameter", isError = true)

    val trash = TrashManager(ctx.config.syncRoot)

    return when (action) {
        "list" -> {
            val items = trash.list()
            val result =
                buildJsonArray {
                    for (item in items) {
                        addJsonObject {
                            put("timestamp", item.timestamp.toString())
                            put("originalPath", item.originalPath)
                            put("sizeBytes", item.sizeBytes)
                        }
                    }
                }
            buildToolResult(result.toString())
        }
        "restore" -> {
            val path =
                args["path"]?.jsonPrimitive?.content
                    ?: return buildToolResult("Missing 'path' for restore", isError = true)
            val ok = trash.restore(path)
            if (ok) {
                buildToolResult(buildJsonObject { put("restored", path) }.toString())
            } else {
                buildToolResult("No trash entry found for: $path", isError = true)
            }
        }
        "purge" -> {
            val days = args["retentionDays"]?.jsonPrimitive?.intOrNull ?: 30
            trash.purge(days)
            buildToolResult(
                buildJsonObject {
                    put("status", "purged")
                    put("retentionDays", days)
                }.toString(),
            )
        }
        else -> buildToolResult("Unknown action: $action. Use 'list', 'restore', or 'purge'.", isError = true)
    }
}
