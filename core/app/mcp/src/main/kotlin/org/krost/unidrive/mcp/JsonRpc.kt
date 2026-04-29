package org.krost.unidrive.mcp

import kotlinx.serialization.json.*

// ── JSON-RPC 2.0 wire types ────────────────────────────────────────────────────

object ErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

fun buildResponse(
    id: JsonElement,
    result: JsonElement,
): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

fun buildErrorResponse(
    id: JsonElement,
    code: Int,
    message: String,
): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }.toString()

fun buildToolResult(
    text: String,
    isError: Boolean = false,
): JsonElement =
    buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
        if (isError) put("isError", true)
    }

// ── MCP capability response builders ────────────────────────────────────────────

fun buildInitializeResult(
    serverName: String,
    serverVersion: String,
): JsonElement =
    buildJsonObject {
        put("protocolVersion", "2024-11-05")
        put(
            "capabilities",
            buildJsonObject {
                put("tools", buildJsonObject {})
                put("resources", buildJsonObject {})
            },
        )
        put(
            "serverInfo",
            buildJsonObject {
                put("name", serverName)
                put("version", serverVersion)
            },
        )
    }

fun buildToolsListResult(tools: List<ToolDef>): JsonElement =
    buildJsonObject {
        putJsonArray("tools") {
            for (tool in tools) {
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                }
            }
        }
    }

data class McpResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
)

fun buildResourcesListResult(resources: List<McpResource>): JsonElement =
    buildJsonObject {
        putJsonArray("resources") {
            for (r in resources) {
                addJsonObject {
                    put("uri", r.uri)
                    put("name", r.name)
                    put("description", r.description)
                    put("mimeType", r.mimeType)
                }
            }
        }
    }

fun buildResourceReadResult(
    uri: String,
    mimeType: String,
    text: String,
): JsonElement =
    buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("uri", uri)
                put("mimeType", mimeType)
                put("text", text)
            }
        }
    }
