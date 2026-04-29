package org.krost.unidrive.mcp

import kotlinx.serialization.json.*

private const val MAX_ENTRIES = 1000

val lsTool =
    ToolDef(
        name = "unidrive_ls",
        description = "Lists synced files and folders from the local state database. Returns path, size, hydration status, and modification time.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("path", stringProp("Path prefix to list (default: \"/\")"))
                        put("recursive", booleanProp("If true, list all descendants. If false, immediate children only (default: false)."))
                    },
            ),
        handler = ::handleLs,
    )

private fun handleLs(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val path = args["path"]?.jsonPrimitive?.content?.let { if (it.startsWith("/")) it else "/$it" } ?: "/"
    val recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
    val prefix =
        if (path == "/") {
            "/"
        } else if (path.endsWith("/")) {
            path
        } else {
            "$path/"
        }

    val db = ctx.openDb()
    try {
        val allEntries = db.getEntriesByPrefix(prefix)
        val entries =
            if (recursive) {
                allEntries.take(MAX_ENTRIES)
            } else {
                // Immediate children: path has exactly one more segment after prefix
                allEntries
                    .filter { entry ->
                        val rel = entry.path.removePrefix(prefix)
                        !rel.contains("/") || (entry.isFolder && rel.trimEnd('/').count { it == '/' } == 0)
                    }.take(MAX_ENTRIES)
            }

        val result =
            buildJsonArray {
                for (entry in entries) {
                    addJsonObject {
                        put("path", entry.path)
                        put("name", entry.path.substringAfterLast("/"))
                        put("isFolder", entry.isFolder)
                        put("size", entry.remoteSize)
                        put("isHydrated", entry.isHydrated)
                        put("modified", entry.remoteModified?.toString())
                    }
                }
            }
        return buildToolResult(result.toString())
    } finally {
        db.close()
    }
}
