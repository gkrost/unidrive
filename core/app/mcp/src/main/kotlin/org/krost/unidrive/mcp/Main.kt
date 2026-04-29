package org.krost.unidrive.mcp

import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Path

fun main(args: Array<String>) {
    // UD-234: surface the short git sha in every log line so end-user copy-paste
    // feedback uniquely identifies which build produced the output.
    org.slf4j.MDC.put("build", BuildInfo.COMMIT)
    val parsed = parseArgs(args)
    val configDir = parsed.configDir ?: SyncConfig.defaultConfigDir()
    // UD-252: delegate to the shared resolver so the MCP and CLI agree on what
    // "no -p" means. Previously this picked the first provider key from ktoml's
    // map ordering, which drifted from the CLI's hardcoded "onedrive" default.
    val profileName = parsed.profile ?: SyncConfig.resolveDefaultProfile(configDir)

    val ctx =
        try {
            ProfileContext.create(profileName, configDir)
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
            throw e // unreachable
        }

    val tools =
        listOf(
            statusTool,
            syncTool,
            getTool,
            freeTool,
            pinTool,
            conflictsTool,
            lsTool,
            configTool,
            trashTool,
            versionsTool,
            shareTool,
            relocateTool,
            watchEventsTool,
            quotaTool,
            backupTool,
            // UD-216: admin verbs for end-to-end LLM-driven management.
            authBeginTool,
            authCompleteTool,
            logoutTool,
            profileListTool,
            profileAddTool,
            profileRemoveTool,
            profileSetTool,
            identityTool,
        )
    val resources = mcpResources(ctx)

    val server = McpServer(ctx, tools, resources) { uri -> readResource(uri, ctx) }
    server.run()
}

private data class ParsedArgs(
    val profile: String?,
    val configDir: Path?,
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var profile: String? = System.getenv("UNIDRIVE_PROFILE")
    var configDir: Path? = System.getenv("UNIDRIVE_CONFIG_DIR")?.let { Path.of(it) }

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-p", "--profile" -> {
                i++
                if (i < args.size) profile = args[i]
            }
            "-c", "--config-dir" -> {
                i++
                if (i < args.size) configDir = Path.of(args[i])
            }
        }
        i++
    }
    return ParsedArgs(profile, configDir)
}
