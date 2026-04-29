package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import java.io.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerTest {
    private fun runSession(vararg messages: String): List<JsonObject> {
        val input = messages.joinToString("\n") + "\n"
        val oldIn = System.`in`
        val oldOut = System.out
        val outputCapture = ByteArrayOutputStream()

        try {
            System.setIn(ByteArrayInputStream(input.toByteArray()))
            System.setOut(PrintStream(outputCapture))

            val dummyTools =
                listOf(
                    ToolDef(
                        name = "test_echo",
                        description = "Echo test",
                        inputSchema = objectSchema(),
                        handler = { args, _ -> buildToolResult("echo: $args") },
                    ),
                )
            val dummyResources =
                listOf(
                    McpResource("test://hello", "hello", "Test resource", "text/plain"),
                )
            val resourceReader: (String) -> Pair<String, String>? = { uri ->
                if (uri == "test://hello") "text/plain" to "Hello, World!" else null
            }

            // Create minimal ProfileContext — won't be used by test_echo
            val server =
                McpServer(
                    ctx = createTestProfileContext(),
                    tools = dummyTools,
                    resources = dummyResources,
                    resourceReader = resourceReader,
                )
            server.run()

            val output = outputCapture.toString()
            return output
                .lines()
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
        } finally {
            System.setIn(oldIn)
            System.setOut(oldOut)
        }
    }

    @Test
    fun `initialize handshake`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}}""",
            )
        assertEquals(1, responses.size)
        val result = responses[0]["result"]?.jsonObject!!
        assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.content)
        assertTrue(result["capabilities"]?.jsonObject?.containsKey("tools") == true)
    }

    @Test
    fun `tools list returns registered tools`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}}""",
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            )
        assertEquals(2, responses.size) // initialize response + tools/list response
        val toolsList = responses[1]["result"]?.jsonObject?.get("tools")?.jsonArray!!
        assertEquals(1, toolsList.size)
        assertEquals("test_echo", toolsList[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools call invokes handler`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"test_echo","arguments":{"msg":"hi"}}}""",
            )
        assertEquals(2, responses.size)
        val content = responses[1]["result"]?.jsonObject?.get("content")?.jsonArray!!
        assertTrue(
            content[0]
                .jsonObject["text"]
                ?.jsonPrimitive
                ?.content
                ?.contains("hi") == true,
        )
    }

    @Test
    fun `unknown method returns error`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","id":1,"method":"nonexistent/method"}""",
            )
        assertEquals(1, responses.size)
        val error = responses[0]["error"]?.jsonObject!!
        assertEquals(-32601, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `ping returns empty result`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            )
        assertEquals(1, responses.size)
        assertTrue(responses[0]["result"]?.jsonObject?.isEmpty() == true)
    }

    @Test
    fun `resources list returns registered resources`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","id":1,"method":"resources/list"}""",
            )
        val resources = responses[0]["result"]?.jsonObject?.get("resources")?.jsonArray!!
        assertEquals(1, resources.size)
        assertEquals("test://hello", resources[0].jsonObject["uri"]?.jsonPrimitive?.content)
    }

    @Test
    fun `resources read returns content`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"test://hello"}}""",
            )
        val contents = responses[0]["result"]?.jsonObject?.get("contents")?.jsonArray!!
        assertEquals("Hello, World!", contents[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `resources read returns error for unknown URI`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"test://unknown"}}""",
            )
        val error = responses[0]["error"]?.jsonObject!!
        assertEquals(-32002, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `notification does not produce response`() {
        val responses =
            runSession(
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            )
        assertEquals(0, responses.size)
    }

    companion object {
        /**
         * Creates a minimal ProfileContext for tests that don't actually call tools
         * requiring a real config or database.
         */
        fun createTestProfileContext(): ProfileContext {
            val tmpDir =
                java.nio.file.Files
                    .createTempDirectory("mcp-test")
            val configDir = tmpDir.resolve("config")
            java.nio.file.Files
                .createDirectories(configDir)
            val profileDir = configDir.resolve("test-profile")
            java.nio.file.Files
                .createDirectories(profileDir)

            return ProfileContext(
                profileName = "test-profile",
                profileInfo =
                    org.krost.unidrive.sync
                        .ProfileInfo("test-profile", "localfs", tmpDir, null),
                config =
                    org.krost.unidrive.sync.SyncConfig
                        .defaults("localfs"),
                configDir = configDir,
                profileDir = profileDir,
                rawConfig =
                    org.krost.unidrive.sync
                        .RawSyncConfig(),
                providerProperties = emptyMap(),
            )
        }
    }
}
