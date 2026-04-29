package org.krost.unidrive.mcp

import kotlinx.serialization.json.*

val watchEventsTool =
    ToolDef(
        name = "unidrive_watch_events",
        description = "Polls real-time sync events from the running daemon. Returns new events since the last call. Call repeatedly to monitor sync progress.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("limit", intProp("Maximum events to return (default: 50)"))
                    },
            ),
        handler = ::handleWatchEvents,
    )

// Tracks the high-water mark across calls within this MCP session
private var lastSeq = 0L

private fun handleWatchEvents(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50

    // UD-257: use the same daemon-liveness probe as `unidrive_status` so both
    // tools agree within the same MCP session. Prior implementation read
    // `EventBuffer.connected`, a flag set asynchronously by a background
    // coroutine that hadn't yet run on the first call — producing a false
    // `daemon_not_running` the moment after `unidrive_status` cheerfully
    // reported `daemonRunning: true` against the exact same socket.
    val daemonRunning = ctx.isDaemonRunning()

    // Still drain any events the background reader has accumulated in prior
    // calls. On the first call within a session the buffer is typically empty
    // (the reader hasn't produced anything yet); subsequent calls will see
    // whatever was broadcast.
    val buffer = ctx.eventBuffer
    val (events, newSeq) = buffer.drain(lastSeq, limit)
    lastSeq = newSeq

    val status = if (daemonRunning) "ok" else "daemon_not_running"

    return buildToolResult(
        buildJsonObject {
            put("status", status)
            put("eventCount", events.size)
            putJsonArray("events") {
                for (event in events) {
                    // Each event is already a JSON string — parse and embed
                    try {
                        add(Json.parseToJsonElement(event))
                    } catch (_: Exception) {
                        addJsonObject { put("raw", event) }
                    }
                }
            }
        }.toString(),
    )
}
