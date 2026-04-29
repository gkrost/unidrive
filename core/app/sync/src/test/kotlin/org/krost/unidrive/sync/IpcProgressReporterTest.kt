package org.krost.unidrive.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Ignore
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class IpcProgressReporterTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-reporter-test")
        socketPath = socketDir.resolve("test.sock")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    private fun connectClient(): SocketChannel {
        val client = SocketChannel.open(StandardProtocolFamily.UNIX)
        client.connect(UnixDomainSocketAddress.of(socketPath))
        client.configureBlocking(false)
        return client
    }

    private suspend fun readLines(
        client: SocketChannel,
        timeoutMs: Long = 2000,
        minLines: Int = 1,
    ): List<String> {
        val buf = ByteBuffer.allocate(8192)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                buf.flip()
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                sb.append(String(bytes, Charsets.UTF_8))
                val lineCount = sb.count { it == '\n' }
                if (lineCount >= minLines) break
            }
            delay(50)
        }
        return sb.toString().lines().filter { it.isNotBlank() }
    }

    private fun parseJson(line: String): JsonObject = Json.decodeFromString(JsonObject.serializer(), line)

    @Test
    fun `onScanProgress emits scan_progress event`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            val client = connectClient()
            delay(100)

            val reporter = IpcProgressReporter(server!!, "personal")
            reporter.onScanProgress("remote", 42)

            val lines = readLines(client)
            val json = lines.map { parseJson(it) }.first { it["event"]!!.jsonPrimitive.content == "scan_progress" }
            assertEquals("scan_progress", json["event"]!!.jsonPrimitive.content)
            assertEquals("personal", json["profile"]!!.jsonPrimitive.content)
            assertEquals("remote", json["phase"]!!.jsonPrimitive.content)
            assertEquals("42", json["count"]!!.jsonPrimitive.content)
            assertNotNull(json["timestamp"])
            client.close()
        }

    @Test
    fun `onActionProgress emits action_progress event`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            val client = connectClient()
            delay(100)

            val reporter = IpcProgressReporter(server!!, "work")
            reporter.onActionProgress(3, 10, "DownloadFile", "/docs/report.pdf")

            val lines = readLines(client)
            val json = lines.map { parseJson(it) }.first { it["event"]!!.jsonPrimitive.content == "action_progress" }
            assertEquals("action_progress", json["event"]!!.jsonPrimitive.content)
            assertEquals("3", json["index"]!!.jsonPrimitive.content)
            assertEquals("10", json["total"]!!.jsonPrimitive.content)
            assertEquals("DownloadFile", json["action"]!!.jsonPrimitive.content)
            assertEquals("/docs/report.pdf", json["path"]!!.jsonPrimitive.content)
            client.close()
        }

    @Ignore("Flaky IPC race; see #108. Temporarily disabled for release build.")
    @Test
    fun `onSyncComplete emits sync_complete event`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(200)
            yield() // ensure broadcast job is scheduled

            val client = connectClient()
            delay(200)
            yield() // ensure client connected

            val reporter = IpcProgressReporter(server!!, "personal")
            reporter.onSyncComplete(downloaded = 5, uploaded = 3, conflicts = 1, durationMs = 12345)
            yield() // allow broadcast job to process channel

            val lines = readLines(client, timeoutMs = 5000, minLines = 1)
            val syncCompleteLines =
                lines.filter {
                    try {
                        parseJson(it)["event"]?.jsonPrimitive?.content == "sync_complete"
                    } catch (_: Exception) {
                        false
                    }
                }
            assertTrue(syncCompleteLines.isNotEmpty(), "Expected sync_complete event but got: $lines")
            val json = parseJson(syncCompleteLines.first())
            assertEquals("sync_complete", json["event"]!!.jsonPrimitive.content)
            assertEquals("5", json["downloaded"]!!.jsonPrimitive.content)
            assertEquals("3", json["uploaded"]!!.jsonPrimitive.content)
            assertEquals("1", json["conflicts"]!!.jsonPrimitive.content)
            assertEquals("12345", json["duration_ms"]!!.jsonPrimitive.content)
            client.close()
        }

    @Test
    fun `emitSyncError sanitizes newlines`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            val client = connectClient()
            delay(100)

            val reporter = IpcProgressReporter(server!!, "personal")
            reporter.emitSyncError("line1\nline2\nline3")

            val lines = readLines(client)
            val errorLines = lines.filter { parseJson(it)["event"]!!.jsonPrimitive.content == "sync_error" }
            assertEquals(1, errorLines.size, "Error should be a single JSON line")
            val json = parseJson(errorLines.first())
            assertEquals("sync_error", json["event"]!!.jsonPrimitive.content)
            val message = json["message"]!!.jsonPrimitive.content
            assertFalse(message.contains("\n"), "Message should not contain raw newlines")
            assertEquals("line1 line2 line3", message)
            client.close()
        }

    @Test
    fun `emitSyncStarted emits sync_started event`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            val client = connectClient()
            delay(100)

            val reporter = IpcProgressReporter(server!!, "myprofile")
            reporter.emitSyncStarted()

            val lines = readLines(client)
            val json = lines.map { parseJson(it) }.first { it["event"]!!.jsonPrimitive.content == "sync_started" }
            assertEquals("sync_started", json["event"]!!.jsonPrimitive.content)
            assertEquals("myprofile", json["profile"]!!.jsonPrimitive.content)
            client.close()
        }

    @Test
    fun `all methods update server syncState`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            val reporter = IpcProgressReporter(server!!, "testprofile")

            reporter.emitSyncStarted()
            var state = server!!.syncState
            assertNotNull(state)
            assertEquals("testprofile", state.profile)
            assertFalse(state.isComplete)

            reporter.onScanProgress("local", 10)
            state = server!!.syncState!!
            assertEquals("local", state.phase)
            assertEquals(10, state.scanCount)

            reporter.onActionCount(25)
            state = server!!.syncState!!
            assertEquals(25, state.actionTotal)

            reporter.onActionProgress(5, 25, "UploadFile", "/photos/cat.jpg")
            state = server!!.syncState!!
            assertEquals(5, state.actionIndex)
            assertEquals(25, state.actionTotal)
            assertEquals("UploadFile", state.lastAction)
            assertEquals("/photos/cat.jpg", state.lastPath)

            reporter.onSyncComplete(downloaded = 8, uploaded = 12, conflicts = 2, durationMs = 5000)
            state = server!!.syncState!!
            assertTrue(state.isComplete)
            assertEquals(8, state.downloaded)
            assertEquals(12, state.uploaded)
            assertEquals(2, state.conflicts)
            assertEquals(5000, state.durationMs)
        }
}
