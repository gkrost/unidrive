package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.sync.ConflictLog
import java.nio.file.Paths

val conflictsTool =
    ToolDef(
        name = "unidrive_conflicts",
        description = "Lists, restores, or clears conflict history. 'list' shows recent conflicts, 'restore' recovers a backup, 'clear' deletes all conflict data.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("action", stringProp("Action to perform", enum = listOf("list", "restore", "clear")))
                        put("path", stringProp("File path (required for 'restore')"))
                        put("limit", intProp("Number of entries to show for 'list' (default: 20)"))
                    },
                required = listOf("action"),
            ),
        handler = ::handleConflicts,
    )

private fun conflictLog(ctx: ProfileContext): ConflictLog {
    val os = System.getProperty("os.name", "").lowercase()
    val dataDir =
        if (os.contains("win")) {
            val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            Paths.get(localAppData, "unidrive", ctx.profileName)
        } else {
            Paths.get(
                System.getenv("HOME") ?: System.getProperty("user.home"),
                ".local",
                "share",
                "unidrive",
                ctx.profileName,
            )
        }
    return ConflictLog(
        logFile = ctx.profileDir.resolve("conflicts.jsonl"),
        backupDir = dataDir.resolve("conflict-backups"),
    )
}

private fun handleConflicts(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val action =
        args["action"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'action' parameter", isError = true)

    val log = conflictLog(ctx)

    return when (action) {
        "list" -> {
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20
            val entries = log.recent(limit)
            val result =
                buildJsonArray {
                    for (entry in entries) {
                        addJsonObject {
                            put("timestamp", entry.timestamp)
                            put("path", entry.path)
                            put("localState", entry.localState)
                            put("remoteState", entry.remoteState)
                            put("policy", entry.policy)
                            put("hasBackup", entry.backupFile != null)
                        }
                    }
                }
            buildToolResult(result.toString())
        }
        "restore" -> {
            val path =
                args["path"]?.jsonPrimitive?.content
                    ?: return buildToolResult("Missing 'path' for restore", isError = true)

            val lock =
                ctx.acquireLock()
                    ?: return buildToolResult(
                        "Another unidrive process is running for profile '${ctx.profileName}'. Stop it first.",
                        isError = true,
                    )
            try {
                val backups = log.findBackups(path)
                if (backups.isEmpty()) {
                    return buildToolResult("No backups found for: $path", isError = true)
                }
                val entry = backups.first()
                val target = log.restore(entry, ctx.config.syncRoot)
                buildToolResult(
                    buildJsonObject {
                        put("restored", path)
                        put("from", entry.backupFile ?: "unknown")
                        put("to", target.toString())
                    }.toString(),
                )
            } finally {
                lock.unlock()
            }
        }
        "clear" -> {
            val lock =
                ctx.acquireLock()
                    ?: return buildToolResult(
                        "Another unidrive process is running for profile '${ctx.profileName}'. Stop it first.",
                        isError = true,
                    )
            try {
                log.clear()
                buildToolResult(buildJsonObject { put("status", "cleared") }.toString())
            } finally {
                lock.unlock()
            }
        }
        else -> buildToolResult("Unknown action: $action. Use 'list', 'restore', or 'clear'.", isError = true)
    }
}
