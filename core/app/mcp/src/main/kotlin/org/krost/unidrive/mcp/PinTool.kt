package org.krost.unidrive.mcp

import kotlinx.serialization.json.*

val pinTool =
    ToolDef(
        name = "unidrive_pin",
        description = "Adds, removes, or lists eager-download glob patterns (pin rules). Pinned patterns are automatically hydrated on the next sync.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("pattern", stringProp("Glob pattern (e.g. '*.pdf', 'Documents/work/**')"))
                        put("action", stringProp("Action to perform", enum = listOf("add", "remove", "list")))
                    },
                required = listOf("action"),
            ),
        handler = ::handlePin,
    )

private fun handlePin(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val action =
        args["action"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'action' parameter", isError = true)

    if (action == "list") {
        val db = ctx.openDb()
        try {
            val rules = db.getPinRules()
            val result =
                buildJsonArray {
                    for ((pattern, pinned) in rules) {
                        addJsonObject {
                            put("pattern", pattern)
                            put("pinned", pinned)
                        }
                    }
                }
            return buildToolResult(result.toString())
        } finally {
            db.close()
        }
    }

    val pattern =
        args["pattern"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'pattern' parameter for add/remove", isError = true)

    val lock =
        ctx.acquireLock()
            ?: return buildToolResult(
                "Another unidrive process is running for profile '${ctx.profileName}'. Stop it first.",
                isError = true,
            )

    try {
        val db = ctx.openDb()
        try {
            return when (action) {
                "add" -> {
                    db.addPinRule(pattern, pinned = true)
                    buildToolResult(
                        buildJsonObject {
                            put("pattern", pattern)
                            put("action", "add")
                            put("status", "pinned")
                        }.toString(),
                    )
                }
                "remove" -> {
                    db.removePinRule(pattern)
                    buildToolResult(
                        buildJsonObject {
                            put("pattern", pattern)
                            put("action", "remove")
                            put("status", "unpinned")
                        }.toString(),
                    )
                }
                else -> buildToolResult("Unknown action: $action. Use 'add', 'remove', or 'list'.", isError = true)
            }
        } finally {
            db.close()
        }
    } finally {
        lock.unlock()
    }
}
