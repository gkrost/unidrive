package org.krost.unidrive.mcp

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRegistryTest {
    private val allTools =
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
            // UD-216: admin verbs (auth begin/complete, logout, profile list/add/remove/set, identity).
            authBeginTool,
            authCompleteTool,
            logoutTool,
            profileListTool,
            profileAddTool,
            profileRemoveTool,
            profileSetTool,
            identityTool,
        )

    @Test
    fun `all 23 tools are registered`() {
        assertEquals(23, allTools.size)
    }

    @Test
    fun `tool names are unique`() {
        val names = allTools.map { it.name }
        assertEquals(names.size, names.toSet().size, "Duplicate tool names found")
    }

    @Test
    fun `all tools have unidrive_ prefix`() {
        for (tool in allTools) {
            assertTrue(tool.name.startsWith("unidrive_"), "Tool ${tool.name} missing unidrive_ prefix")
        }
    }

    @Test
    fun `all tools have non-empty descriptions`() {
        for (tool in allTools) {
            assertTrue(tool.description.isNotBlank(), "Tool ${tool.name} has empty description")
        }
    }

    @Test
    fun `all input schemas are valid JSON Schema objects`() {
        for (tool in allTools) {
            val schema = tool.inputSchema
            assertEquals(
                "object",
                schema["type"]?.jsonPrimitive?.content,
                "Tool ${tool.name} inputSchema.type should be 'object'",
            )
            assertTrue(
                schema.containsKey("properties"),
                "Tool ${tool.name} inputSchema should have 'properties'",
            )
        }
    }

    @Test
    fun `tools with required parameters declare them`() {
        // get, free require "path"
        val getTool = allTools.find { it.name == "unidrive_get" }!!
        val required = getTool.inputSchema["required"]?.jsonArray
        assertTrue(required.toString().contains("path"), "unidrive_get should require 'path'")

        // pin requires "action" (pattern optional for list action)
        val pinTool = allTools.find { it.name == "unidrive_pin" }!!
        val pinRequired = pinTool.inputSchema["required"].toString()
        assertTrue(pinRequired.contains("action"), "unidrive_pin should require 'action'")
    }
}
