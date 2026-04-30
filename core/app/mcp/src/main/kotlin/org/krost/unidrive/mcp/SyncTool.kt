package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.sync.*
import java.nio.file.Paths

val syncTool =
    ToolDef(
        name = "unidrive_sync",
        description = "Triggers a Gather-Reconcile-Apply sync cycle. Use dryRun=true to preview actions without applying them.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("dryRun", booleanProp("If true, preview actions without applying (default: false)"))
                        put("syncPath", stringProp("Sync only files under this path (optional)"))
                        put("direction", stringProp("Sync direction", enum = listOf("bidirectional", "upload", "download")))
                    },
            ),
        handler = ::handleSync,
    )

private fun handleSync(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val dryRun = args["dryRun"]?.jsonPrimitive?.booleanOrNull ?: false
    val syncPath = args["syncPath"]?.jsonPrimitive?.content
    val directionStr = args["direction"]?.jsonPrimitive?.content
    val direction =
        when (directionStr?.lowercase()) {
            "upload" -> SyncDirection.UPLOAD
            "download" -> SyncDirection.DOWNLOAD
            else -> SyncDirection.BIDIRECTIONAL
        }

    val lock =
        ctx.acquireLock()
            ?: return buildToolResult(
                "Another unidrive process is running for profile '${ctx.profileName}'. Stop it first.",
                isError = true,
            )

    try {
        val db = ctx.openDb()
        try {
            val provider = ctx.createProvider()
            runBlocking { provider.authenticate() }

            val os = System.getProperty("os.name", "").lowercase()
            val conflictBackupDir =
                if (os.contains("win")) {
                    val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
                    Paths.get(localAppData, "unidrive", ctx.profileName, "conflict-backups")
                } else {
                    Paths.get(
                        System.getenv("HOME") ?: System.getProperty("user.home"),
                        ".local",
                        "share",
                        "unidrive",
                        ctx.profileName,
                        "conflict-backups",
                    )
                }
            val conflictLog =
                ConflictLog(
                    logFile = ctx.profileDir.resolve("conflicts.jsonl"),
                    backupDir = conflictBackupDir,
                )

            val reporter = SummaryReporter()
            val placeholder = PlaceholderManager(ctx.config.syncRoot)

            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = ctx.config.syncRoot,
                    conflictPolicy = ctx.config.conflictPolicy,
                    conflictOverrides = ctx.config.providerConflictOverrides(ctx.profileName),
                    excludePatterns = ctx.config.effectiveExcludePatterns(ctx.profileName),
                    reporter = reporter,
                    failureLogPath = ctx.profileDir.resolve("failures.jsonl"),
                    conflictLog = conflictLog,
                    syncPath = syncPath,
                    syncDirection = direction,
                    maxDeletePercentage = ctx.config.maxDeletePercentage,
                    verifyIntegrity = ctx.config.verifyIntegrity,
                    providerId = ctx.profileInfo.type,
                    useTrash = ctx.config.useTrash,
                    placeholder = placeholder,
                )

            // UD-254: MCP-invoked sync is classified MANUAL since the caller
            // is an external agent (Claude, scripted client, etc.).
            runBlocking {
                engine.syncOnce(
                    dryRun = dryRun,
                    reason = org.krost.unidrive.sync.SyncReason.MANUAL,
                )
            }

            return buildToolResult(
                buildJsonObject {
                    put("dryRun", dryRun)
                    put("downloaded", reporter.downloaded)
                    put("uploaded", reporter.uploaded)
                    put("conflicts", reporter.conflicts)
                    put("totalActions", reporter.totalActions)
                    put("durationMs", reporter.durationMs)
                }.toString(),
            )
        } finally {
            db.close()
        }
    } finally {
        lock.unlock()
    }
}

/** Collects sync summary without printing to console. */
private class SummaryReporter : ProgressReporter {
    var downloaded: Int = 0
        private set
    var uploaded: Int = 0
        private set
    var conflicts: Int = 0
        private set
    var totalActions: Int = 0
        private set
    var durationMs: Long = 0
        private set

    override fun onScanProgress(
        phase: String,
        count: Int,
    ) {}

    override fun onActionCount(total: Int) {
        totalActions = total
    }

    override fun onActionProgress(
        index: Int,
        total: Int,
        action: String,
        path: String,
    ) {}

    override fun onTransferProgress(
        path: String,
        bytesTransferred: Long,
        totalBytes: Long,
    ) {}

    override fun onWarning(message: String) {}

    override fun onSyncComplete(
        downloaded: Int,
        uploaded: Int,
        conflicts: Int,
        durationMs: Long,
        actionCounts: Map<String, Int>,
        failed: Int,
    ) {
        this.downloaded = downloaded
        this.uploaded = uploaded
        this.conflicts = conflicts
        this.durationMs = durationMs
    }
}
