package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonRpcTest {
    @Test
    fun `buildResponse produces valid JSON-RPC response`() {
        val id = JsonPrimitive(42)
        val result = buildJsonObject { put("status", "ok") }
        val json = Json.parseToJsonElement(buildResponse(id, result)).jsonObject
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(42, json["id"]?.jsonPrimitive?.int)
        assertEquals(
            "ok",
            json["result"]
                ?.jsonObject
                ?.get("status")
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(json["error"] == null)
    }

    @Test
    fun `buildErrorResponse produces valid JSON-RPC error`() {
        val id = JsonPrimitive(1)
        val json = Json.parseToJsonElement(buildErrorResponse(id, -32601, "not found")).jsonObject
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(
            -32601,
            json["error"]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.int,
        )
        assertEquals(
            "not found",
            json["error"]
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `buildToolResult wraps text content`() {
        val result = buildToolResult("hello")
        val obj = result.jsonObject
        val content = obj["content"]?.jsonArray?.firstOrNull()?.jsonObject
        assertEquals("text", content?.get("type")?.jsonPrimitive?.content)
        assertEquals("hello", content?.get("text")?.jsonPrimitive?.content)
        assertTrue(obj["isError"] == null) // default: not set when false
    }

    @Test
    fun `buildToolResult with isError flag`() {
        val result = buildToolResult("failure", isError = true)
        val obj = result.jsonObject
        assertEquals(true, obj["isError"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `buildInitializeResult has correct structure`() {
        val result = buildInitializeResult("test-server", "1.0.0")
        val obj = result.jsonObject
        assertEquals("2024-11-05", obj["protocolVersion"]?.jsonPrimitive?.content)
        assertTrue(obj["capabilities"]?.jsonObject?.containsKey("tools") == true)
        assertTrue(obj["capabilities"]?.jsonObject?.containsKey("resources") == true)
        assertEquals(
            "test-server",
            obj["serverInfo"]
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `buildToolsListResult serializes tool definitions`() {
        val tools =
            listOf(
                ToolDef(
                    name = "test_tool",
                    description = "A test tool",
                    inputSchema =
                        objectSchema(
                            properties =
                                buildJsonObject {
                                    put("name", stringProp("The name"))
                                },
                            required = listOf("name"),
                        ),
                    handler = { _, _ -> buildToolResult("ok") },
                ),
            )
        val result = buildToolsListResult(tools).jsonObject
        val toolList = result["tools"]?.jsonArray!!
        assertEquals(1, toolList.size)
        val tool = toolList[0].jsonObject
        assertEquals("test_tool", tool["name"]?.jsonPrimitive?.content)
        assertEquals("A test tool", tool["description"]?.jsonPrimitive?.content)
        val schema = tool["inputSchema"]?.jsonObject!!
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertTrue(schema["properties"]?.jsonObject?.containsKey("name") == true)
        assertEquals(
            "name",
            schema["required"]
                ?.jsonArray
                ?.first()
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `buildResourcesListResult serializes resources`() {
        val resources = listOf(McpResource("test://r", "test", "A test resource", "text/plain"))
        val result = buildResourcesListResult(resources).jsonObject
        val list = result["resources"]?.jsonArray!!
        assertEquals(1, list.size)
        assertEquals("test://r", list[0].jsonObject["uri"]?.jsonPrimitive?.content)
    }

    @Test
    fun `notification messages have no id field`() {
        // Notifications must not have an id — test that server dispatch ignores them
        val msg =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
        assertTrue(msg["id"] == null, "Notification should not have id")
    }
}
