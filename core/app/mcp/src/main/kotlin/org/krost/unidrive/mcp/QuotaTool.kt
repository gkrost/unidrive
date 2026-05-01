package org.krost.unidrive.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.krost.unidrive.authenticateAndLog

val quotaTool =
    ToolDef(
        name = "unidrive_quota",
        description = "Shows storage quota: used, total, and remaining bytes.",
        inputSchema = objectSchema(),
        handler = ::handleQuota,
    )

private fun handleQuota(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val provider = ctx.createProvider()
    runBlocking { provider.authenticateAndLog() }

    val quota = runBlocking { provider.quota() }

    return buildToolResult(
        buildJsonObject {
            put("used", quota.used)
            put("total", quota.total)
            put("remaining", quota.remaining)
        }.toString(),
    )
}
