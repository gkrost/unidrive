package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files

val backupTool =
    ToolDef(
        name = "unidrive_backup",
        description = "Lists backup profiles (upload-only sync configs). Use 'add' to create, 'list' to show all.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("action", stringProp("Action to perform", enum = listOf("list")))
                        put("name", stringProp("Backup profile name (optional for 'list')"))
                    },
                required = listOf("action"),
            ),
        handler = ::handleBackup,
    )

private fun handleBackup(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val action =
        args["action"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'action' parameter", isError = true)

    return when (action) {
        "list" -> handleBackupList(args, ctx)
        else -> buildToolResult("Unknown action: $action. Use 'list'.", isError = true)
    }
}

private fun handleBackupList(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val configFile = ctx.configDir.resolve("config.toml")
    if (!Files.exists(configFile)) {
        return buildToolResult("[]")
    }

    val configText = Files.readString(configFile)
    val raw = SyncConfig.parseRaw(configText)
    val sectionFields = parseSectionFields(configText)

    val backups =
        buildJsonArray {
            for ((name, cfg) in raw.providers) {
                val fields = sectionFields[name] ?: emptyMap()
                if (fields["sync_direction"] == "upload") {
                    addJsonObject {
                        put("name", name)
                        put("type", cfg?.type ?: "?")
                        put("sync_root", cfg?.sync_root ?: "~/")
                        put("remote_path", cfg?.remote_path ?: "-")
                        put("file_versioning", fields["file_versioning"] == "true")
                        put("max_versions", fields["max_versions"]?.toIntOrNull() ?: 5)
                    }
                }
            }
        }

    return buildToolResult(backups.toString())
}

/** Parse TOML text to extract key=value pairs per [providers.X] section. */
private fun parseSectionFields(toml: String): Map<String, Map<String, String>> {
    val result = mutableMapOf<String, MutableMap<String, String>>()
    var currentSection: String? = null

    for (line in toml.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("[providers.") && trimmed.endsWith("]") -> {
                currentSection = trimmed.removePrefix("[providers.").removeSuffix("]").trim()
                result[currentSection] = mutableMapOf()
            }
            trimmed.contains("=") && currentSection != null -> {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim().trim('"', ' ')
                    result[currentSection]!![key] = value
                }
            }
            trimmed.startsWith("[") && !trimmed.startsWith("[providers.") -> {
                currentSection = null
            }
        }
    }
    return result
}
