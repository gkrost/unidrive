package org.krost.unidrive.mcp

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.sync.CloudRelocator
import org.krost.unidrive.sync.MigrateEvent

val relocateTool =
    ToolDef(
        name = "unidrive_relocate",
        description = "Migrates files between two cloud providers. Downloads from source and uploads to target via buffered streaming. Does NOT delete source files.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("fromProfile", stringProp("Source provider profile name"))
                        put("toProfile", stringProp("Target provider profile name"))
                        put("sourcePath", stringProp("Path within source provider (default: \"/\")"))
                        put("targetPath", stringProp("Path within target provider (default: \"/\")"))
                        put("bufferMb", intProp("Transfer buffer size in MB (default: 8)"))
                    },
                required = listOf("fromProfile", "toProfile"),
            ),
        handler = ::handleRelocate,
    )

private fun handleRelocate(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val fromProfile =
        args["fromProfile"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'fromProfile' parameter", isError = true)
    val toProfile =
        args["toProfile"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'toProfile' parameter", isError = true)
    val sourcePath = args["sourcePath"]?.jsonPrimitive?.content ?: "/"
    val targetPath = args["targetPath"]?.jsonPrimitive?.content ?: "/"
    val bufferMb = args["bufferMb"]?.jsonPrimitive?.intOrNull ?: 8

    val source =
        try {
            ctx.createProviderForProfile(fromProfile)
        } catch (e: Exception) {
            return buildToolResult("Failed to create source provider '$fromProfile': ${e.message}", isError = true)
        }
    val target =
        try {
            ctx.createProviderForProfile(toProfile)
        } catch (e: Exception) {
            return buildToolResult("Failed to create target provider '$toProfile': ${e.message}", isError = true)
        }

    runBlocking {
        source.authenticate()
        target.authenticate()
    }

    val relocator = CloudRelocator(source, target, bufferMb.toLong() * 1024 * 1024)
    var result: MigrateEvent.Completed? = null

    runBlocking {
        relocator.migrate(sourcePath, targetPath).collect { event ->
            if (event is MigrateEvent.Completed) {
                result = event
            }
        }
    }

    val completed =
        result
            ?: return buildToolResult("Migration did not complete", isError = true)

    return buildToolResult(
        buildJsonObject {
            put("fromProfile", fromProfile)
            put("toProfile", toProfile)
            put("migratedFiles", completed.doneFiles)
            put("migratedSizeBytes", completed.doneSize)
            put("errorCount", completed.errorCount)
            put("durationMs", completed.durationMs)
        }.toString(),
    )
}
