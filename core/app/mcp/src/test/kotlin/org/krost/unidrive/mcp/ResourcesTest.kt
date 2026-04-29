package org.krost.unidrive.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.RawSyncConfig
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourcesTest {
    // -- mcpResources ---------------------------------------------------------

    @Test
    fun `mcpResources returns three resources`() {
        val ctx = McpServerTest.createTestProfileContext()
        val resources = mcpResources(ctx)
        assertEquals(3, resources.size)
    }

    @Test
    fun `mcpResources contains config resource`() {
        val ctx = McpServerTest.createTestProfileContext()
        val config = mcpResources(ctx).first { it.uri == "unidrive://config" }
        assertEquals("config.toml", config.name)
        assertEquals("application/toml", config.mimeType)
    }

    @Test
    fun `mcpResources contains conflicts resource`() {
        val ctx = McpServerTest.createTestProfileContext()
        val conflicts = mcpResources(ctx).first { it.uri == "unidrive://conflicts" }
        assertEquals("conflicts.jsonl", conflicts.name)
        assertEquals("application/x-ndjson", conflicts.mimeType)
    }

    @Test
    fun `mcpResources contains profiles resource`() {
        val ctx = McpServerTest.createTestProfileContext()
        val profiles = mcpResources(ctx).first { it.uri == "unidrive://profiles" }
        assertEquals("profiles", profiles.name)
        assertEquals("application/json", profiles.mimeType)
    }

    // -- readResource dispatch ------------------------------------------------

    @Test
    fun `readResource returns null for unknown URI`() {
        val ctx = McpServerTest.createTestProfileContext()
        assertNull(readResource("unidrive://unknown", ctx))
    }

    @Test
    fun `readResource dispatches config URI`() {
        val ctx = McpServerTest.createTestProfileContext()
        val result = readResource("unidrive://config", ctx)!!
        assertEquals("application/toml", result.first)
    }

    @Test
    fun `readResource dispatches conflicts URI`() {
        val ctx = McpServerTest.createTestProfileContext()
        val result = readResource("unidrive://conflicts", ctx)!!
        assertEquals("application/x-ndjson", result.first)
    }

    @Test
    fun `readResource dispatches profiles URI`() {
        val ctx = McpServerTest.createTestProfileContext()
        val result = readResource("unidrive://profiles", ctx)!!
        assertEquals("application/json", result.first)
    }

    // -- config resource ------------------------------------------------------

    @Test
    fun `config resource returns placeholder when file missing`() {
        val ctx = McpServerTest.createTestProfileContext()
        val (mime, text) = readResource("unidrive://config", ctx)!!
        assertEquals("application/toml", mime)
        assertEquals("# No config.toml found\n", text)
    }

    @Test
    fun `config resource reads existing config file`() {
        val ctx = McpServerTest.createTestProfileContext()
        val content = "[general]\npoll_interval = 120\n"
        Files.writeString(ctx.configDir.resolve("config.toml"), content)
        val (mime, text) = readResource("unidrive://config", ctx)!!
        assertEquals("application/toml", mime)
        assertEquals(content, text)
    }

    // -- conflicts resource ---------------------------------------------------

    @Test
    fun `conflicts resource returns empty when file missing`() {
        val ctx = McpServerTest.createTestProfileContext()
        val (mime, text) = readResource("unidrive://conflicts", ctx)!!
        assertEquals("application/x-ndjson", mime)
        assertEquals("", text)
    }

    @Test
    fun `conflicts resource reads existing file`() {
        val ctx = McpServerTest.createTestProfileContext()
        val lines = listOf("""{"a":1}""", """{"b":2}""")
        Files.writeString(ctx.profileDir.resolve("conflicts.jsonl"), lines.joinToString("\n") + "\n")
        val (mime, text) = readResource("unidrive://conflicts", ctx)!!
        assertEquals("application/x-ndjson", mime)
        // tailLines reads lines; trailing newline produces an empty last line
        assertTrue(text.contains("""{"a":1}"""))
        assertTrue(text.contains("""{"b":2}"""))
    }

    @Test
    fun `conflicts resource tails to last 100 lines`() {
        val ctx = McpServerTest.createTestProfileContext()
        val allLines = (1..150).map { """{"line":$it}""" }
        Files.writeString(ctx.profileDir.resolve("conflicts.jsonl"), allLines.joinToString("\n") + "\n")
        val (_, text) = readResource("unidrive://conflicts", ctx)!!
        val outputLines = text.lines().filter { it.isNotBlank() }
        assertEquals(100, outputLines.size)
        // Should contain line 51..150, not 1..50
        assertTrue(outputLines.first().contains("\"line\":51"))
        assertTrue(outputLines.last().contains("\"line\":150"))
    }

    // -- profiles resource ----------------------------------------------------

    @Test
    fun `profiles resource returns empty array when no providers`() {
        val ctx = McpServerTest.createTestProfileContext()
        val (mime, text) = readResource("unidrive://profiles", ctx)!!
        assertEquals("application/json", mime)
        val arr = Json.parseToJsonElement(text).jsonArray
        assertEquals(0, arr.size)
    }

    @Test
    fun `profiles resource lists configured providers`() {
        val tmpDir = Files.createTempDirectory("mcp-res-test")
        val configDir = tmpDir.resolve("config")
        Files.createDirectories(configDir)
        val profileDir = configDir.resolve("myprofile")
        Files.createDirectories(profileDir)

        val rawConfig =
            RawSyncConfig(
                providers =
                    mapOf(
                        "localfs" to RawProvider(type = "localfs", sync_root = tmpDir.toString()),
                    ),
            )

        val ctx =
            ProfileContext(
                profileName = "myprofile",
                profileInfo =
                    org.krost.unidrive.sync
                        .ProfileInfo("myprofile", "localfs", tmpDir, null),
                config = SyncConfig.defaults("localfs"),
                configDir = configDir,
                profileDir = profileDir,
                rawConfig = rawConfig,
                providerProperties = emptyMap(),
            )

        val (mime, text) = readResource("unidrive://profiles", ctx)!!
        assertEquals("application/json", mime)
        val arr = Json.parseToJsonElement(text).jsonArray
        assertEquals(1, arr.size)
        val entry = arr[0].jsonObject
        assertEquals("localfs", entry["name"]?.jsonPrimitive?.content)
        assertEquals("localfs", entry["type"]?.jsonPrimitive?.content)
        assertTrue(entry["syncRoot"]?.jsonPrimitive?.content?.isNotBlank() == true)
    }

    @Test
    fun `profiles resource lists multiple providers`() {
        val tmpDir = Files.createTempDirectory("mcp-res-test")
        val configDir = tmpDir.resolve("config")
        Files.createDirectories(configDir)
        val profileDir = configDir.resolve("multi")
        Files.createDirectories(profileDir)

        val rawConfig =
            RawSyncConfig(
                providers =
                    mapOf(
                        "localfs" to RawProvider(type = "localfs", sync_root = tmpDir.toString()),
                        "sftp" to RawProvider(type = "sftp", sync_root = tmpDir.toString(), host = "example.com"),
                    ),
            )

        val ctx =
            ProfileContext(
                profileName = "multi",
                profileInfo =
                    org.krost.unidrive.sync
                        .ProfileInfo("multi", "localfs", tmpDir, null),
                config = SyncConfig.defaults("localfs"),
                configDir = configDir,
                profileDir = profileDir,
                rawConfig = rawConfig,
                providerProperties = emptyMap(),
            )

        val (_, text) = readResource("unidrive://profiles", ctx)!!
        val arr = Json.parseToJsonElement(text).jsonArray
        assertEquals(2, arr.size)
        val names = arr.map { it.jsonObject["name"]?.jsonPrimitive?.content }.toSet()
        assertTrue("localfs" in names)
        assertTrue("sftp" in names)
    }
}
