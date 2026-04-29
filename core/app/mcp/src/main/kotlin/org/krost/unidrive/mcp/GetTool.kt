package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.sync.PlaceholderManager
import java.nio.file.Files
import java.time.Instant

val getTool =
    ToolDef(
        name = "unidrive_get",
        description = "Downloads file content (hydrates a sparse placeholder). Single file only.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("path", stringProp("Remote path to download"))
                    },
                required = listOf("path"),
            ),
        handler = ::handleGet,
    )

private fun handleGet(
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

            if (entry.isFolder) {
                return buildToolResult("Cannot hydrate a folder. Use a file path.", isError = true)
            }

            val placeholder = PlaceholderManager(ctx.config.syncRoot)
            val localPath = placeholder.resolveLocal(path)
            val isSparse = placeholder.isSparse(localPath, entry.remoteSize)

            if (entry.isHydrated && !isSparse) {
                return buildToolResult(
                    buildJsonObject {
                        put("path", path)
                        put("status", "already_hydrated")
                        put("size", entry.remoteSize)
                    }.toString(),
                )
            }

            val provider = ctx.createProvider()
            runBlocking { provider.authenticate() }

            val bytes =
                runBlocking {
                    val rid = entry.remoteId
                    if (rid != null) {
                        provider.downloadById(rid, path, localPath)
                    } else {
                        provider.download(path, localPath)
                    }
                }

            val modified = entry.remoteModified
            placeholder.restoreMtime(path, modified)
            db.upsertEntry(
                entry.copy(
                    isHydrated = true,
                    localMtime = Files.getLastModifiedTime(localPath).toMillis(),
                    localSize = Files.size(localPath),
                    lastSynced = Instant.now(),
                ),
            )

            return buildToolResult(
                buildJsonObject {
                    put("path", path)
                    put("status", "hydrated")
                    put("size", bytes)
                }.toString(),
            )
        } finally {
            db.close()
        }
    } finally {
        lock.unlock()
    }
}
