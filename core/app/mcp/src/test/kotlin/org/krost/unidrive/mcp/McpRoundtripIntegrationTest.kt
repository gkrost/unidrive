package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UD-202 — end-to-end JSON-RPC roundtrip against the packaged MCP shadow jar.
 *
 * Spawns the shadow jar as a real subprocess, pipes in JSON-RPC frames over stdin,
 * reads JSON-RPC responses from stdout. The server exits when stdin closes, so we
 * write all frames then close the stream and collect output until EOF.
 *
 * Tests are skipped (via JUnit `Assume`, per UD-704) when the shadow jar is missing.
 */
class McpRoundtripIntegrationTest {
    private lateinit var tmpDir: Path
    private lateinit var configDir: Path
    private lateinit var syncRoot: Path

    // Version-agnostic jar lookup — matches pattern from CliSmokeTest.
    // Test working directory is `core/app/mcp/`, so `build/libs` is relative.
    private val jarFile: File =
        run {
            val libs = File("build/libs")
            libs
                .listFiles()
                ?.firstOrNull {
                    it.name.startsWith("unidrive-mcp-") &&
                        it.name.endsWith(".jar") &&
                        !it.name.contains("-sources") &&
                        !it.name.contains("-javadoc")
                }
                ?: File(libs, "unidrive-mcp.jar")
        }

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("mcp-roundtrip-")
        configDir = tmpDir.resolve("config")
        Files.createDirectories(configDir)
        syncRoot = tmpDir.resolve("sync-root")
        Files.createDirectories(syncRoot)

        // Seed two sample files in the local sync root so that unidrive_sync
        // picks them up and unidrive_ls can surface them from the state DB.
        Files.writeString(syncRoot.resolve("hello.txt"), "hello world\n")
        val docsDir = syncRoot.resolve("docs")
        Files.createDirectories(docsDir)
        Files.writeString(docsDir.resolve("note.md"), "# note\n")

        // Minimal localfs profile. `sync_root` points to our local dir and
        // `root_path` is the provider's "remote" dir (a sibling temp dir so
        // the localfs provider has a distinct tree to reconcile against).
        val remoteRoot = tmpDir.resolve("remote-root")
        Files.createDirectories(remoteRoot)
        val configToml =
            """
            [providers.local]
            type = "localfs"
            sync_root = "${syncRoot.toString().replace("\\", "/")}"
            root_path = "${remoteRoot.toString().replace("\\", "/")}"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), configToml)
    }

    @AfterTest
    fun tearDown() {
        if (::tmpDir.isInitialized) tmpDir.toFile().deleteRecursively()
    }

    /**
     * Aggregated result of a single MCP subprocess invocation.
     *
     * [responses] parses only lines that start with `{"jsonrpc"` so logback
     * output (which logback sends to stderr by default, but we tolerate if any
     * leaks to stdout) doesn't poison the parse.
     */
    private data class RpcRun(
        val responses: List<JsonObject>,
        val stderr: String,
        val exitCode: Int,
    )

    /**
     * Spawn the MCP jar, pipe [frames] plus a notifications/initialized to stdin,
     * close stdin, and return all JSON-RPC responses parsed from stdout.
     *
     * Constraints: test must finish in well under 10s; we use a 15s overall
     * waitFor as a safety net.
     */
    private fun sendRpc(vararg frames: String): RpcRun {
        val pb =
            ProcessBuilder(
                "java",
                "--enable-native-access=ALL-UNNAMED",
                "-jar",
                jarFile.absolutePath,
                "--config-dir",
                configDir.toString(),
                "--profile",
                "local",
            )
        val process = pb.start()

        // Collect stdout + stderr on background threads so writing stdin can
        // never deadlock on a full pipe.
        val stdoutLines = mutableListOf<String>()
        val stderrBuf = StringBuilder()
        val stdoutReader =
            Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                    for (line in r.lineSequence()) {
                        synchronized(stdoutLines) { stdoutLines.add(line) }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        val stderrReader =
            Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { r ->
                    for (line in r.lineSequence()) {
                        synchronized(stderrBuf) { stderrBuf.append(line).append('\n') }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

        // Write frames — each JSON-RPC message on its own line.
        process.outputStream.use { out ->
            for (frame in frames) {
                out.write(frame.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
            }
            // MCP spec: client sends notifications/initialized after initialize
            // completes. We append it unconditionally; handlers ignore unknowns.
            val notif = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
            out.write(notif.toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            out.flush()
        }
        // Stdin closed ⇒ server's readLine returns null ⇒ process exits.

        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw AssertionError("MCP subprocess did not exit within 15s")
        }
        stdoutReader.join(2000)
        stderrReader.join(2000)

        val parsed =
            synchronized(stdoutLines) {
                stdoutLines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("{\"jsonrpc\"")) return@mapNotNull null
                    try {
                        Json.parseToJsonElement(trimmed).jsonObject
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        return RpcRun(parsed, stderrBuf.toString(), process.exitValue())
    }

    private fun initFrame(id: Int) =
        """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ud-202-test","version":"1.0"}}}"""

    private fun toolsListFrame(id: Int) = """{"jsonrpc":"2.0","id":$id,"method":"tools/list"}"""

    private fun toolCallFrame(
        id: Int,
        name: String,
        args: String = "{}",
    ) = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""

    /** Extract the JSON text payload inside a tools/call result's `content[0].text`. */
    private fun extractToolText(response: JsonObject): String {
        val result = response["result"]?.jsonObject ?: error("no result field in $response")
        val content = result["content"]?.jsonArray ?: error("no content array in result")
        return content
            .first()
            .jsonObject["text"]
            ?.jsonPrimitive
            ?.content ?: error("no text in content[0]")
    }

    private fun responseById(
        run: RpcRun,
        id: Int,
    ): JsonObject =
        run.responses.firstOrNull { it["id"]?.jsonPrimitive?.intOrNull == id }
            ?: error("no response for id=$id in ${run.responses}")

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `initialize advertises tools and resources capabilities`() {
        assumeTrue("requires :app:mcp:shadowJar — run ./gradlew :app:mcp:shadowJar first", jarFile.exists())

        val run = sendRpc(initFrame(1))

        val initResp = responseById(run, 1)
        val result = initResp["result"]?.jsonObject
        assertNotNull(result, "initialize must return a result")
        val caps = result["capabilities"]?.jsonObject
        assertNotNull(caps, "initialize result must contain capabilities")
        assertTrue(caps.containsKey("tools"), "capabilities should advertise tools")
        assertTrue(caps.containsKey("resources"), "capabilities should advertise resources")
    }

    @Test
    fun `tools list returns 23 named tools starting with unidrive_`() {
        // UD-216: surface now includes 8 admin verbs (auth begin/complete,
        // logout, profile list/add/remove/set, identity) on top of the
        // original 15 data/ops tools.
        assumeTrue("requires :app:mcp:shadowJar — run ./gradlew :app:mcp:shadowJar first", jarFile.exists())

        val run = sendRpc(initFrame(1), toolsListFrame(2))

        val resp = responseById(run, 2)
        val tools =
            resp["result"]?.jsonObject?.get("tools")?.jsonArray
                ?: error("tools/list must return result.tools array")
        assertEquals(23, tools.size, "expected 23 tools advertised, got ${tools.size}")
        for (t in tools) {
            val name = t.jsonObject["name"]?.jsonPrimitive?.content ?: error("tool missing name")
            assertTrue(name.startsWith("unidrive_"), "tool name '$name' should start with 'unidrive_'")
        }
    }

    @Test
    fun `unidrive_status returns profile and providerType`() {
        assumeTrue("requires :app:mcp:shadowJar — run ./gradlew :app:mcp:shadowJar first", jarFile.exists())

        val run = sendRpc(initFrame(1), toolCallFrame(2, "unidrive_status"))

        val payload = Json.parseToJsonElement(extractToolText(responseById(run, 2))).jsonObject
        assertEquals("local", payload["profile"]?.jsonPrimitive?.content)
        assertEquals("localfs", payload["providerType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unidrive_sync then unidrive_ls shows seeded files`() {
        assumeTrue("requires :app:mcp:shadowJar — run ./gradlew :app:mcp:shadowJar first", jarFile.exists())

        val run =
            sendRpc(
                initFrame(1),
                toolCallFrame(2, "unidrive_sync"),
                toolCallFrame(3, "unidrive_ls", """{"path":"/"}"""),
                toolCallFrame(4, "unidrive_ls", """{"path":"/docs"}"""),
            )

        // Sync should succeed — the tool either returns a stats object or an
        // error payload. We only require it not to be flagged isError=true.
        val syncResp = responseById(run, 2)
        val syncResult = syncResp["result"]?.jsonObject
        assertNotNull(syncResult, "sync must return a result")
        assertTrue(
            syncResult["isError"]?.jsonPrimitive?.booleanOrNull != true,
            "unidrive_sync returned isError: ${extractToolText(syncResp)}",
        )

        val rootListing = Json.parseToJsonElement(extractToolText(responseById(run, 3))).jsonArray
        val rootNames = rootListing.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue("hello.txt" in rootNames, "ls / should contain hello.txt — got $rootNames")
        assertTrue("docs" in rootNames, "ls / should contain docs folder — got $rootNames")

        val docsListing = Json.parseToJsonElement(extractToolText(responseById(run, 4))).jsonArray
        val docsNames = docsListing.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue("note.md" in docsNames, "ls /docs should contain note.md — got $docsNames")
    }

    @Test
    fun `unidrive_quota returns positive total`() {
        assumeTrue("requires :app:mcp:shadowJar — run ./gradlew :app:mcp:shadowJar first", jarFile.exists())

        val run = sendRpc(initFrame(1), toolCallFrame(2, "unidrive_quota"))

        val payload = Json.parseToJsonElement(extractToolText(responseById(run, 2))).jsonObject
        val total =
            payload["total"]?.jsonPrimitive?.longOrNull
                ?: error("quota payload missing total: $payload")
        assertTrue(total > 0, "quota.total should be > 0, was $total")
    }

    @Test
    fun `post-UD-304 no path-traversal errors under localfs sync`() {
        assumeTrue("requires :app:mcp:shadowJar — run ./gradlew :app:mcp:shadowJar first", jarFile.exists())

        val run = sendRpc(initFrame(1), toolCallFrame(2, "unidrive_sync"))

        assertTrue(
            !run.stderr.contains("Path traversal rejected"),
            "UD-304 regression: 'Path traversal rejected' appeared in stderr:\n${run.stderr}",
        )
    }
}
