package org.krost.unidrive.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawSyncConfig
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-257 — unidrive_status and unidrive_watch_events must agree on whether
 * the daemon is live.
 *
 * Root cause of the original divergence: `StatusTool` used a synchronous
 * `SocketChannel.connect` round-trip while `WatchEventsTool` read
 * `EventBuffer.connected` — a flag set by a background coroutine that
 * hadn't run yet on the first call in a session. One tool said true,
 * the other said "daemon_not_running" against the exact same socket.
 *
 * These tests pin both tools to the same truth source by comparing their
 * outputs against a real in-process `IpcServer`, and against the case
 * where no server is listening.
 */
class DaemonStatusAgreementTest {
    private lateinit var tmpDir: Path
    private lateinit var configDir: Path
    private lateinit var profileName: String
    private var server: IpcServer? = null
    private var serverScope: CoroutineScope? = null

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("mcp-daemon-agree-")
        configDir = tmpDir.resolve("config")
        Files.createDirectories(configDir)
        // Unique profile name per test so IpcServer.defaultSocketPath returns
        // a fresh socket file that won't collide with other runs.
        profileName = "ud257-${UUID.randomUUID().toString().take(8)}"
        Files.createDirectories(configDir.resolve(profileName))
    }

    @AfterTest
    fun tearDown() {
        stopServer()
        if (::tmpDir.isInitialized) tmpDir.toFile().deleteRecursively()
        // Also clean any socket/meta files we left in the OS temp dir.
        val socketPath = IpcServer.defaultSocketPath(profileName)
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(socketPath.resolveSibling("${socketPath.fileName}.meta")) }
    }

    private fun startServer(): IpcServer {
        val socketPath = IpcServer.defaultSocketPath(profileName)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val srv = IpcServer(socketPath)
        srv.start(scope)
        serverScope = scope
        server = srv
        // Small wait so the accept loop is actually bound before we probe.
        Thread.sleep(100)
        return srv
    }

    private fun stopServer() {
        server?.close()
        server = null
        serverScope?.cancel()
        serverScope = null
    }

    private fun ctx(): ProfileContext =
        ProfileContext(
            profileName = profileName,
            profileInfo = ProfileInfo(profileName, "localfs", tmpDir.resolve("sync"), null),
            config =
                SyncConfig.defaults("localfs").let { cfg ->
                    SyncConfig(
                        syncRoot = tmpDir.resolve("sync"),
                        pollInterval = cfg.pollInterval,
                        conflictPolicy = cfg.conflictPolicy,
                        logFile = null,
                        providers = emptyMap(),
                    )
                },
            configDir = configDir,
            profileDir = configDir.resolve(profileName),
            rawConfig = RawSyncConfig(),
            providerProperties = mapOf("root_path" to tmpDir.resolve("remote").toString()),
        )

    private fun resultText(r: JsonElement): String =
        r.jsonObject["content"]!!
            .jsonArray[0]
            .jsonObject["text"]!!
            .jsonPrimitive.content

    private fun statusDaemonRunning(c: ProfileContext): Boolean {
        val result = statusTool.handler(buildJsonObject {}, c)
        val json = Json.parseToJsonElement(resultText(result)).jsonObject
        return json["daemonRunning"]!!.jsonPrimitive.content.toBoolean()
    }

    private fun watchEventsStatus(c: ProfileContext): String {
        val result = watchEventsTool.handler(buildJsonObject {}, c)
        val json = Json.parseToJsonElement(resultText(result)).jsonObject
        return json["status"]!!.jsonPrimitive.content
    }

    @Test
    fun `both tools report daemon not running when no server is listening`() {
        val c = ctx()

        assertFalse(statusDaemonRunning(c), "status tool should report daemon not running")
        assertEquals(
            "daemon_not_running",
            watchEventsStatus(c),
            "watch_events should report daemon_not_running",
        )
    }

    @Test
    fun `both tools agree daemon is running on first call after server starts`() {
        // UD-257: this is the exact race that was failing before the fix.
        // Back-to-back calls in the same MCP session, with no sleep, must agree.
        startServer()
        val c = ctx()

        val statusSaysUp = statusDaemonRunning(c)
        val watchStatus = watchEventsStatus(c)

        assertTrue(statusSaysUp, "status tool should report daemon running")
        assertEquals(
            "ok",
            watchStatus,
            "watch_events must agree with status on the very first call (UD-257 race)",
        )
    }

    @Test
    fun `both tools agree daemon stopped after server closes`() {
        startServer()
        val c = ctx()
        // Confirm up first
        assertTrue(statusDaemonRunning(c))
        assertEquals("ok", watchEventsStatus(c))

        stopServer()
        // Give the OS a moment to release the socket file.
        Thread.sleep(100)

        assertFalse(
            statusDaemonRunning(c),
            "status tool should flip to daemon_not_running after server stops",
        )
        assertEquals(
            "daemon_not_running",
            watchEventsStatus(c),
            "watch_events should flip to daemon_not_running after server stops",
        )
    }
}
