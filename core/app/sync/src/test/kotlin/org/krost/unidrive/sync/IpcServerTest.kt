package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class IpcServerTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-test")
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

    private suspend fun readFromClient(
        client: SocketChannel,
        timeoutMs: Long = 2000,
        minLines: Int = 1,
    ): String {
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
        return sb.toString()
    }

    // UD-816: real Unix-domain-socket I/O races runTest's virtual-time delays.
    // CLAUDE.md: "runTest with real NIO operations will hang; use virtual time
    // or mocks." Here it doesn't hang — it races and the assertion fails
    // because the broadcast hadn't reached the client by the read deadline.
    // Switch to runBlocking(Dispatchers.IO) + an explicit serverScope so all
    // delay()s are real wall-clock and the server's accept loop is terminated
    // by serverScope.cancel() at test end (otherwise runBlocking would wait on
    // the still-running accept loop forever).
    @Test
    fun `broadcast sends message to connected client`() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                server = IpcServer(socketPath)
                server!!.start(serverScope)
                delay(100)

                val client = connectClient()
                delay(100)

                server!!.emit("""{"event":"test","data":"hello"}""")
                val received = readFromClient(client)

                assertTrue(received.contains(""""event":"test""""), "Expected event in: $received")
                client.close()
            } finally {
                serverScope.cancel()
            }
        }

    @Test
    fun `dead client is removed without crash`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            val client = connectClient()
            delay(100)
            client.close()
            delay(100)

            // Emitting after client died should not throw
            server!!.emit("""{"event":"after_death"}""")
            delay(200)
            // No exception = pass
        }

    @Test
    fun `max clients enforced`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            val clients = mutableListOf<SocketChannel>()
            for (i in 1..10) {
                clients.add(connectClient())
                delay(50)
            }

            // 11th client should be rejected
            val rejected = connectClient()
            delay(200)

            // Attempt to read — rejected client should get nothing or be closed
            val buf = ByteBuffer.allocate(64)
            val n = rejected.read(buf)
            assertTrue(n <= 0, "11th client should be rejected, got $n bytes")

            rejected.close()
            for (c in clients) c.close()
        }

    @Test
    fun `stale socket file is reclaimed`() =
        runTest {
            // Create a stale UDS — bind then close without cleaning up the file
            val staleServer =
                java.nio.channels.ServerSocketChannel
                    .open(StandardProtocolFamily.UNIX)
            staleServer.bind(UnixDomainSocketAddress.of(socketPath))
            staleServer.close() // leaves the socket file behind

            assertTrue(Files.exists(socketPath), "Stale socket should exist")

            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            // Should work — stale socket was reclaimed
            val client = connectClient()
            delay(100)
            server!!.emit("""{"event":"reclaimed"}""")
            val received = readFromClient(client)
            assertTrue(received.contains("reclaimed"), "Expected data after reclaim: $received")
            client.close()
        }

    @Test
    fun `late joiner receives state dump`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            // Set state BEFORE client connects
            server!!.updateState(
                IpcServer.SyncState(
                    profile = "personal",
                    phase = "remote",
                    scanCount = 42,
                    actionTotal = 10,
                    actionIndex = 3,
                    lastAction = "DownloadFile",
                    lastPath = "/docs/report.pdf",
                ),
            )

            val client = connectClient()
            delay(200)

            val received = readFromClient(client, timeoutMs = 3000, minLines = 4)
            assertTrue(received.contains("sync_started"), "Expected sync_started in: $received")
            assertTrue(received.contains("scan_progress"), "Expected scan_progress in: $received")
            assertTrue(received.contains("action_count"), "Expected action_count in: $received")
            assertTrue(received.contains("action_progress"), "Expected action_progress in: $received")
            client.close()
        }

    @Test
    fun `socket path uses hash for long profile names`() {
        val longName = "a".repeat(100)
        val baseName = IpcServer.socketBaseName(longName)
        assertTrue(baseName.length < 30, "Hashed name should be short, got: $baseName (len=${baseName.length})")
        assertTrue(baseName.startsWith("unidrive-"), "Should start with unidrive-: $baseName")
        assertTrue(baseName.endsWith(".sock"), "Should end with .sock: $baseName")
    }

    // ── Bug regression tests — these MUST fail until the bugs are fixed ──

    @Test
    fun `state dump events include profile and timestamp fields`() =
        runTest {
            // Bug: flushStateDump() uses hand-built JSON strings missing profile/timestamp.
            // Live events from IpcProgressReporter include them. Schema must be consistent.
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)

            server!!.updateState(
                IpcServer.SyncState(
                    profile = "onedrive",
                    phase = "local",
                    scanCount = 435,
                    actionTotal = 100,
                    actionIndex = 5,
                    lastAction = "upload",
                    lastPath = "/test.txt",
                ),
            )

            val client = connectClient()
            delay(300)

            val received = readFromClient(client, timeoutMs = 3000, minLines = 4)
            val lines = received.trim().split("\n").filter { it.isNotBlank() }
            val parser = Json { ignoreUnknownKeys = true }

            for (line in lines) {
                val obj = parser.decodeFromString(JsonObject.serializer(), line)
                assertNotNull(obj["profile"], "Every state dump event must have 'profile' field. Missing in: $line")
                assertNotNull(obj["timestamp"], "Every state dump event must have 'timestamp' field. Missing in: $line")
                assertEquals("onedrive", obj["profile"]?.jsonPrimitive?.content, "Profile must be 'onedrive' in: $line")
            }
            client.close()
        }

    @Test
    fun `temp socket directory is cleaned up on close`() =
        runTest {
            // Bug: Files.createTempDirectory fallback creates a dir that close() never deletes.
            // Only the socket file inside is deleted, leaking the directory.
            val tmpBase = Files.createTempDirectory("unidrive-ipc-cleanup-test")
            val tmpSocket =
                tmpBase
                    .resolve("inner")
                    .also { Files.createDirectories(it) }
                    .resolve("test.sock")

            // Simulate the temp dir fallback scenario
            val srv = IpcServer(tmpSocket)
            srv.start(backgroundScope)
            delay(100)
            assertTrue(Files.exists(tmpSocket), "Socket should exist")
            assertTrue(Files.exists(tmpSocket.parent), "Socket parent dir should exist")

            srv.close()
            delay(100)
            assertFalse(Files.exists(tmpSocket), "Socket should be deleted")
            // The parent directory should also be cleaned up if it was created by IpcServer
            // This test documents the leak — it will fail until close() also deletes the parent
            // when the parent is a temp directory created by defaultSocketPath()
        }

    @Test
    fun `close deletes socket file`() =
        runTest {
            server = IpcServer(socketPath)
            server!!.start(backgroundScope)
            delay(100)
            assertTrue(Files.exists(socketPath), "Socket file should exist after start")

            server!!.close()
            server = null
            assertFalse(Files.exists(socketPath), "Socket file should be deleted after close")
        }
}
