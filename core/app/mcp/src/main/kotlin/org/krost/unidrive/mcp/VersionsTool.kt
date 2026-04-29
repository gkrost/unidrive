package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.sync.VersionManager
import java.time.Instant

val versionsTool =
    ToolDef(
        name = "unidrive_versions",
        description = "Manages file version history. Lists snapshots, restores a specific version, or purges old versions.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("action", stringProp("Action to perform", enum = listOf("list", "restore", "purge")))
                        put("path", stringProp("File path (optional for 'list' — omit to show all, required for 'restore')"))
                        put("timestamp", stringProp("ISO 8601 timestamp of the version to restore (required for 'restore')"))
                        put("retentionDays", intProp("Purge versions older than N days (default: 90)"))
                    },
                required = listOf("action"),
            ),
        handler = ::handleVersions,
    )

private fun handleVersions(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val action =
        args["action"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'action' parameter", isError = true)

    val versions = VersionManager(ctx.config.syncRoot)

    return when (action) {
        "list" -> {
            val path = args["path"]?.jsonPrimitive?.content
            val items = if (path != null) versions.listVersions(path) else versions.listAll()
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
            val tsStr =
                args["timestamp"]?.jsonPrimitive?.content
                    ?: return buildToolResult("Missing 'timestamp' for restore", isError = true)
            val ts =
                try {
                    Instant.parse(tsStr)
                } catch (_: Exception) {
                    return buildToolResult("Invalid timestamp: $tsStr", isError = true)
                }
            val ok = versions.restore(path, ts)
            if (ok) {
                buildToolResult(
                    buildJsonObject {
                        put("restored", path)
                        put("timestamp", tsStr)
                    }.toString(),
                )
            } else {
                buildToolResult("No version found for: $path at $tsStr", isError = true)
            }
        }
        "purge" -> {
            val days = args["retentionDays"]?.jsonPrimitive?.intOrNull ?: 90
            versions.pruneByAge(days)
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
