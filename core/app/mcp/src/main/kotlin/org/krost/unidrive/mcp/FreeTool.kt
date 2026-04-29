package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.sync.PlaceholderManager
import java.nio.file.Files
import java.time.Instant

val freeTool =
    ToolDef(
        name = "unidrive_free",
        description = "Frees disk space by dehydrating a file back to a sparse placeholder. The file remains visible in the directory tree.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("path", stringProp("Path of the file to dehydrate"))
                    },
                required = listOf("path"),
            ),
        handler = ::handleFree,
    )

private fun handleFree(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val rawPath =
        args["path"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'path' parameter", isError = true)
    val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"

    val lock =
        ctx.acquireLock()
            ?: return buildToolResult(
                "Another unidrive process is running for profile '${ctx.profileName}'. Stop it first.",
                isError = true,
            )

    try {
        val db = ctx.openDb()
        try {
            val entry =
                db.getEntry(path)
                    ?: return buildToolResult("Not tracked: $path", isError = true)

            if (!entry.isHydrated) {
                return buildToolResult(
                    buildJsonObject {
                        put("path", path)
                        put("status", "already_dehydrated")
                    }.toString(),
                )
            }

            val placeholder = PlaceholderManager(ctx.config.syncRoot)
            val localPath = placeholder.resolveLocal(path)

            if (Files.exists(localPath)) {
                val currentMtime = Files.getLastModifiedTime(localPath).toMillis()
                if (entry.localMtime != null && currentMtime != entry.localMtime) {
                    return buildToolResult("File modified since last sync. Sync first, then free.", isError = true)
                }
            }

            val freed = entry.localSize ?: 0L
            placeholder.dehydrate(path, entry.remoteSize, entry.remoteModified)
            db.upsertEntry(entry.copy(isHydrated = false, lastSynced = Instant.now()))

            return buildToolResult(
                buildJsonObject {
                    put("path", path)
                    put("status", "dehydrated")
                    put("freedBytes", freed)
                }.toString(),
            )
        } finally {
            db.close()
        }
    } finally {
        lock.unlock()
    }
}
