package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import java.nio.file.Files

val configTool =
    ToolDef(
        name = "unidrive_config",
        description = "Reads the sync configuration. Returns the full config or a specific key. Read-only in this version.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put(
                            "key",
                            stringProp(
                                "Specific config key to read (e.g. 'syncRoot', 'pollInterval', 'conflictPolicy'). Omit for full config.",
                            ),
                        )
                    },
            ),
        handler = ::handleConfig,
    )

private fun handleConfig(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val key = args["key"]?.jsonPrimitive?.content

    val full =
        buildJsonObject {
            put("syncRoot", ctx.config.syncRoot.toString())
            put("pollInterval", ctx.config.pollInterval)
            put("conflictPolicy", ctx.config.conflictPolicy.name)
            put("syncDirection", ctx.config.syncDirection.name)
            put("maxDeletePercentage", ctx.config.maxDeletePercentage)
            put("verifyIntegrity", ctx.config.verifyIntegrity)
            put("useTrash", ctx.config.useTrash)
            put("trashRetentionDays", ctx.config.trashRetentionDays)
            put("fileVersioning", ctx.config.fileVersioning)
            put("maxVersions", ctx.config.maxVersions)
            put("desktopNotifications", ctx.config.desktopNotifications)
            putJsonArray("globalExcludePatterns") {
                ctx.config.globalExcludePatterns.forEach { add(it) }
            }
            // Raw TOML available via resource; this gives parsed view
            put("configFile", ctx.configDir.resolve("config.toml").toString())
            put("configFileExists", Files.exists(ctx.configDir.resolve("config.toml")))
        }

    if (key == null) {
        return buildToolResult(full.toString())
    }

    val value = full[key]
    if (value == null) {
        return buildToolResult("Unknown config key: '$key'. Available keys: ${full.keys.joinToString(", ")}", isError = true)
    }
    return buildToolResult(buildJsonObject { put(key, value) }.toString())
}
