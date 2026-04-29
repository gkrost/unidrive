package org.krost.unidrive.mcp

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.SyncConfig
import java.io.BufferedReader
import java.nio.file.Files

private const val MAX_CONFLICT_LINES = 100

fun mcpResources(ctx: ProfileContext): List<McpResource> =
    listOf(
        McpResource(
            uri = "unidrive://config",
            name = "config.toml",
            description = "UniDrive sync configuration (TOML format)",
            mimeType = "application/toml",
        ),
        McpResource(
            uri = "unidrive://conflicts",
            name = "conflicts.jsonl",
            description = "Recent conflict history (NDJSON, last $MAX_CONFLICT_LINES entries)",
            mimeType = "application/x-ndjson",
        ),
        McpResource(
            uri = "unidrive://profiles",
            name = "profiles",
            description = "Configured provider profiles (name, type, syncRoot)",
            mimeType = "application/json",
        ),
    )

fun readResource(
    uri: String,
    ctx: ProfileContext,
): Pair<String, String>? =
    when (uri) {
        "unidrive://config" -> readConfigResource(ctx)
        "unidrive://conflicts" -> readConflictsResource(ctx)
        "unidrive://profiles" -> readProfilesResource(ctx)
        else -> null
    }

private fun readConfigResource(ctx: ProfileContext): Pair<String, String> {
    val configFile = ctx.configDir.resolve("config.toml")
    val text = if (Files.exists(configFile)) Files.readString(configFile) else "# No config.toml found\n"
    return "application/toml" to text
}

private fun readConflictsResource(ctx: ProfileContext): Pair<String, String> {
    val conflictsFile = ctx.profileDir.resolve("conflicts.jsonl")
    if (!Files.exists(conflictsFile)) {
        return "application/x-ndjson" to ""
    }
    val lines =
        Files.newBufferedReader(conflictsFile).use { reader ->
            tailLines(reader, MAX_CONFLICT_LINES)
        }
    return "application/x-ndjson" to lines.joinToString("\n")
}

private fun readProfilesResource(ctx: ProfileContext): Pair<String, String> {
    val arr =
        buildJsonArray {
            for ((name, _) in ctx.rawConfig.providers) {
                val info = SyncConfig.resolveProfile(name, ctx.rawConfig)
                add(
                    buildJsonObject {
                        put("name", info.name)
                        put("type", info.type)
                        put("syncRoot", info.syncRoot.toString())
                    },
                )
            }
        }
    return "application/json" to arr.toString()
}

/** Read last N lines from a BufferedReader. */
private fun tailLines(
    reader: BufferedReader,
    n: Int,
): List<String> {
    val buffer = ArrayDeque<String>(n)
    reader.forEachLine { line ->
        if (buffer.size >= n) buffer.removeFirst()
        buffer.addLast(line)
    }
    return buffer.toList()
}
