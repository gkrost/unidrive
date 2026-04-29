package org.krost.unidrive.mcp

import kotlinx.serialization.json.*

data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: (JsonObject, ProfileContext) -> JsonElement,
)

// ── Schema builder helpers ──────────────────────────────────────────────────────

fun objectSchema(
    properties: JsonObject = buildJsonObject {},
    required: List<String> = emptyList(),
): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }

fun stringProp(
    description: String,
    enum: List<String>? = null,
): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) {
            putJsonArray("enum") { enum.forEach { add(it) } }
        }
    }

fun booleanProp(description: String): JsonObject =
    buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

fun intProp(description: String): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
