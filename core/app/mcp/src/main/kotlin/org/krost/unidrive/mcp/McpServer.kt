package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

class McpServer(
    private val ctx: ProfileContext,
    private val tools: List<ToolDef>,
    private val resources: List<McpResource>,
    private val resourceReader: (String) -> Pair<String, String>?, // uri → (mimeType, text)
) {
    private val stdout = PrintWriter(System.out, false)

    fun run() {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        for (line in reader.lineSequence()) {
            if (line.isBlank()) continue
            try {
                val msg = Json.parseToJsonElement(line).jsonObject
                dispatch(msg)
            } catch (e: Exception) {
                // Parse error — can't extract id
                send(buildErrorResponse(JsonNull, ErrorCodes.PARSE_ERROR, "Parse error: ${e.message}"))
            }
        }
    }

    private fun dispatch(msg: JsonObject) {
        val method = msg["method"]?.jsonPrimitive?.content
        val id = msg["id"]

        // Notification (no id) — fire and forget
        if (id == null) return

        if (method == null) {
            send(buildErrorResponse(id, ErrorCodes.INVALID_REQUEST, "Missing method"))
            return
        }

        when (method) {
            "initialize" -> handleInitialize(id, msg["params"]?.jsonObject)
            "ping" -> send(buildResponse(id, buildJsonObject {}))
            "tools/list" -> send(buildResponse(id, buildToolsListResult(tools)))
            "tools/call" -> handleToolCall(id, msg["params"]?.jsonObject)
            "resources/list" -> send(buildResponse(id, buildResourcesListResult(resources)))
            "resources/read" -> handleResourceRead(id, msg["params"]?.jsonObject)
            else -> send(buildErrorResponse(id, ErrorCodes.METHOD_NOT_FOUND, "Unknown method: $method"))
        }
    }

    private fun handleInitialize(
        id: JsonElement,
        params: JsonObject?,
    ) {
        val clientVersion = params?.get("protocolVersion")?.jsonPrimitive?.content
        if (clientVersion != null && clientVersion != "2024-11-05") {
            send(
                buildErrorResponse(
                    id,
                    ErrorCodes.INVALID_PARAMS,
                    "Unsupported protocol version: $clientVersion. Server supports 2024-11-05",
                ),
            )
            return
        }
        send(buildResponse(id, buildInitializeResult("unidrive-mcp", BuildInfo.versionString())))
    }

    private fun handleToolCall(
        id: JsonElement,
        params: JsonObject?,
    ) {
        val name = params?.get("name")?.jsonPrimitive?.content
        if (name == null) {
            send(buildErrorResponse(id, ErrorCodes.INVALID_PARAMS, "Missing tool name"))
            return
        }
        val tool = tools.find { it.name == name }
        if (tool == null) {
            send(buildErrorResponse(id, ErrorCodes.INVALID_PARAMS, "Unknown tool: $name"))
            return
        }
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}

        // UD-283: validate the optional `profile` argument BEFORE invoking the
        // tool handler. Pre-fix, every tool ignored args["profile"] entirely
        // (no tool ever read it) — passing the wrong profile name silently
        // returned data for the active default profile. That's worse than the
        // CLI's `Unknown profile: X` error because an LLM caller can't see
        // the configured-profiles list to course-correct, and mutating tools
        // (sync, pin, relocate, profile_remove) could operate on the wrong
        // target without any signal.
        val profileError = profileMismatchError(args, ctx)
        if (profileError != null) {
            send(profileError(id))
            return
        }

        val result =
            try {
                tool.handler(args, ctx)
            } catch (e: Exception) {
                buildToolResult("Error: ${e.message ?: e.javaClass.simpleName}", isError = true)
            }
        send(buildResponse(id, result))
    }

    private fun handleResourceRead(
        id: JsonElement,
        params: JsonObject?,
    ) {
        val uri = params?.get("uri")?.jsonPrimitive?.content
        if (uri == null) {
            send(buildErrorResponse(id, ErrorCodes.INVALID_PARAMS, "Missing uri"))
            return
        }
        val content = resourceReader(uri)
        if (content == null) {
            send(buildErrorResponse(id, -32002, "Resource not found: $uri"))
            return
        }
        send(buildResponse(id, buildResourceReadResult(uri, content.first, content.second)))
    }

    private fun send(json: String) {
        synchronized(stdout) {
            stdout.println(json)
            stdout.flush()
        }
    }
}
