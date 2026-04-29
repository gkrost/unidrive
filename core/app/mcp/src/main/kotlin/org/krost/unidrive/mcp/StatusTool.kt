package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.CredentialHealth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val statusTool =
    ToolDef(
        name = "unidrive_status",
        description = "Retrieves sync status: file counts, storage usage, last sync time, credential health, and daemon status.",
        inputSchema = objectSchema(),
        handler = ::handleStatus,
    )

private val SYNC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun handleStatus(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val health = ctx.checkCredentialHealth()
    val daemonRunning = ctx.isDaemonRunning()

    val dbPath = ctx.profileDir.resolve("state.db")
    if (!java.nio.file.Files
            .exists(dbPath)
    ) {
        return buildToolResult(
            buildJsonObject {
                put("profile", ctx.profileName)
                put("providerType", ctx.profileInfo.type)
                put("syncRoot", ctx.config.syncRoot.toString())
                put("files", 0)
                put("sparse", 0)
                put("cloudSizeBytes", 0)
                put("localSizeBytes", 0)
                put("lastSync", JsonNull)
                put("credentialHealth", healthToString(health))
                put("daemonRunning", daemonRunning)
            }.toString(),
        )
    }

    val db = ctx.openDb()
    try {
        val entries = db.getAllEntries()
        val fileCount = entries.count { !it.isFolder }
        val sparseCount = entries.count { !it.isHydrated && !it.isFolder }
        val cloudSize = entries.filter { !it.isFolder }.sumOf { it.remoteSize }
        val localSize = entries.filter { it.isHydrated }.sumOf { it.localSize ?: 0L }
        val lastSyncRaw = db.getSyncState("last_full_scan")
        val hasCursor = db.getSyncState("delta_cursor") != null
        val lastSync = formatLastSync(lastSyncRaw)

        return buildToolResult(
            buildJsonObject {
                put("profile", ctx.profileName)
                put("providerType", ctx.profileInfo.type)
                put("syncRoot", ctx.config.syncRoot.toString())
                put("files", fileCount)
                put("sparse", sparseCount)
                put("cloudSizeBytes", cloudSize)
                put("localSizeBytes", localSize)
                if (lastSync != null) put("lastSync", lastSync) else put("lastSync", JsonNull as JsonElement)
                put("hasDeltaCursor", hasCursor)
                put("credentialHealth", healthToString(health))
                put("daemonRunning", daemonRunning)
            }.toString(),
        )
    } finally {
        db.close()
    }
}

private fun healthToString(health: CredentialHealth): String =
    when (health) {
        is CredentialHealth.Ok -> "ok"
        is CredentialHealth.Warning -> "warning: ${health.message}"
        is CredentialHealth.Missing -> "missing: ${health.message}"
        is CredentialHealth.ExpiresIn -> "expires: ${health.message}"
    }

private fun formatLastSync(raw: String?): String? {
    if (raw == null) return null
    return try {
        SYNC_FORMAT.format(Instant.parse(raw).atZone(ZoneId.systemDefault()))
    } catch (_: Exception) {
        raw
    }
}
