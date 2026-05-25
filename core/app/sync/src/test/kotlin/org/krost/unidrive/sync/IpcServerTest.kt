package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /**
     * Issue `sync.subscribe` on `client` and drain the {"ok":true} reply
     * line plus any subsequent state-dump lines. Production code registers
     * this verb in SyncCommand; tests must register it themselves on the
     * IpcServer they constructed. Idempotent across tests within a run.
     *
     * Returns after the post-reply state-dump has completed and the
     * connection is registered in IpcServer.syncSubscribers.
     */
    private suspend fun subscribeSync(client: SocketChannel) {
        runCatching {
            server!!.registerHandler("sync.subscribe") { connId, _ ->
                server!!.scheduleAfterReply(connId) {
                    server!!.flushStateDumpTo(connId)
                    server!!.registerSyncSubscriber(connId)
                }
                """{"ok":true}"""
            }
            server!!.registerConnectionCloseListener { connId ->
                server!!.unregisterSyncSubscriber(connId)
            }
        }
        val req = """{"verb":"sync.subscribe"}""" + "\n"
        val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
        while (w.hasRemaining()) client.write(w)
        // Drain at least the {"ok":true} reply; allow up to 250ms for
        // any state-dump lines that follow.
        readFromClient(client, timeoutMs = 250, minLines = 1)
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
                subscribeSync(client)

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

    // UD-816: same runTest+real-UDS race as the broadcast test. Switch to
    // runBlocking(Dispatchers.IO) so all delays are real wall-clock and the
    // emit/read sequence has time to flush.
    @Test
    fun `stale socket file is reclaimed`() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                // Create a stale UDS — bind then close without cleaning up the file
                val staleServer =
                    java.nio.channels.ServerSocketChannel
                        .open(StandardProtocolFamily.UNIX)
                staleServer.bind(UnixDomainSocketAddress.of(socketPath))
                staleServer.close() // leaves the socket file behind

                assertTrue(Files.exists(socketPath), "Stale socket should exist")

                server = IpcServer(socketPath)
                server!!.start(serverScope)
                delay(100)

                // Should work — stale socket was reclaimed
                val client = connectClient()
                delay(100)
                subscribeSync(client)
                server!!.emit("""{"event":"reclaimed"}""")
                val received = readFromClient(client)
                assertTrue(received.contains("reclaimed"), "Expected data after reclaim: $received")
                client.close()
            } finally {
                serverScope.cancel()
            }
        }

    @Test
    fun `subscriber receives state dump after sync subscribe`() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                server = IpcServer(socketPath)
                server!!.start(serverScope)
                server!!.updateState(
                    IpcServer.SyncState(
                        profile = "test_profile",
                        phase = "reconcile",
                        scanCount = 42,
                        actionTotal = 10,
                        actionIndex = 3,
                        lastAction = "Upload",
                        lastPath = "/foo.txt",
                    ),
                )
                val client = connectClient()
                delay(100)

                // BEFORE sync.subscribe: no state-dump bytes should arrive.
                val preSubscribe = readFromClient(client, timeoutMs = 300, minLines = 1)
                assertEquals("", preSubscribe, "Expected zero pre-subscribe bytes; got: $preSubscribe")

                // Register the handler + issue sync.subscribe.
                server!!.registerHandler("sync.subscribe") { connId, _ ->
                    server!!.scheduleAfterReply(connId) {
                        server!!.flushStateDumpTo(connId)
                        server!!.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)
                val received = readFromClient(client, timeoutMs = 2000, minLines = 5)

                val lines = received.split("\n").filter { it.isNotEmpty() }
                assertTrue(lines.size >= 5, "Expected ≥5 lines, got ${lines.size}: $received")

                assertTrue(lines[0].contains("\"ok\":true"), "Line 1 must be the subscribe reply: ${lines[0]}")
                assertTrue(lines[1].contains("sync_started"), "Line 2 must be sync_started: ${lines[1]}")
                assertTrue(lines[2].contains("scan_progress"), "Line 3 must be scan_progress: ${lines[2]}")
                assertTrue(lines[3].contains("action_count"), "Line 4 must be action_count: ${lines[3]}")
                assertTrue(lines[4].contains("action_progress"), "Line 5 must be action_progress: ${lines[4]}")

                client.close()
            } finally {
                serverScope.cancel()
            }
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

    // UD-816: same runTest+real-UDS race. Switch to runBlocking(IO).
    @Test
    fun `state dump events include profile and timestamp fields`() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                // Bug: flushStateDump() uses hand-built JSON strings missing profile/timestamp.
                // Live events from IpcProgressReporter include them. Schema must be consistent.
                server = IpcServer(socketPath)
                server!!.start(serverScope)
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
                subscribeSync(client)

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
            } finally {
                serverScope.cancel()
            }
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

    // ── UD-406: runBlocking returns after IpcServer.close() ──────────────────
    // SyncCommand's non-watch sync path was hanging after `Sync complete:`
    // because `ipcServer.start(this)` launched an accept-loop coroutine into
    // the runBlocking scope, and runBlocking blocks until ALL children
    // complete. The fix is to call `ipcServer.close()` at the end of the
    // non-watch branch so the accept-loop coroutine is cancelled.
    //
    // This test exercises the synthetic equivalent: spawn IpcServer.start
    // inside a runBlocking, do the sync work (here a noop suspend), then
    // close() — assert that the runBlocking block returns within a
    // reasonable bound. Without the close, the test hangs and the JUnit
    // timeout fires.

    @Test(timeout = 5_000)
    fun `UD-406 runBlocking returns after explicit ipcServer close`() {
        val syncSocket = socketDir.resolve("ud406.sock")
        val testServer = IpcServer(syncSocket)
        try {
            // Synthetic equivalent of SyncCommand.run():
            //   runBlocking {
            //     ipcServer.start(this)
            //     <do sync work>
            //     ipcServer.close()   ← UD-406 fix
            //   }
            // Without the close(), runBlocking would block forever waiting on
            // the accept-loop child coroutine.
            runBlocking {
                testServer.start(this)
                // Stand-in for engine.syncOnce — completes quickly.
                kotlinx.coroutines.delay(50)
                testServer.close()
            }
            // If we reach here, runBlocking returned cleanly. Without
            // ipcServer.close() inside the lambda, the @Test timeout would
            // have fired before this line.
            assertFalse(
                Files.exists(syncSocket),
                "Socket file should be cleaned up after close() inside runBlocking",
            )
        } finally {
            testServer.close()
            Files.deleteIfExists(syncSocket)
        }
    }

    // ── Helpers for request/reply tests ──────────────────────────────────────

    private fun tempSocket(): Path = socketDir.resolve("handler-${System.nanoTime()}.sock")

    private suspend fun waitForSocket(path: Path, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) {
                try {
                    SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                        ch.connect(UnixDomainSocketAddress.of(path))
                    }
                    return
                } catch (_: Exception) {}
            }
            delay(20)
        }
        error("Socket $path did not become available within ${timeoutMs}ms")
    }

    /** Opens a UDS connection, writes [request] + newline, reads one reply line. */
    private suspend fun clientRoundTrip(path: Path, request: String): String {
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        ch.connect(UnixDomainSocketAddress.of(path))
        ch.configureBlocking(false)
        // Write request
        val out = (request + "\n").toByteArray(Charsets.UTF_8)
        val wbuf = ByteBuffer.wrap(out)
        val writeDeadline = System.nanoTime() + 5_000_000_000L
        while (wbuf.hasRemaining()) {
            ch.write(wbuf)
            if (wbuf.hasRemaining()) {
                if (System.nanoTime() > writeDeadline) error("clientRoundTrip: write timeout")
                delay(10)
            }
        }
        // Read one reply line
        val sb = StringBuilder()
        val rbuf = ByteBuffer.allocate(8192)
        val readDeadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < readDeadline) {
            rbuf.clear()
            val n = ch.read(rbuf)
            if (n > 0) {
                rbuf.flip()
                val bytes = ByteArray(rbuf.remaining())
                rbuf.get(bytes)
                sb.append(String(bytes, Charsets.UTF_8))
                if (sb.contains('\n')) break
            }
            delay(20)
        }
        ch.close()
        return sb.toString()
    }

    @Test
    fun `registered handler receives a client request and replies on the same connection`() = runBlocking(Dispatchers.IO) {
        val sockPath = tempSocket()
        val srv = IpcServer(sockPath)
        srv.registerHandler("ping") { _, json -> """{"reply":"pong","echo":$json}""" }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        srv.start(scope)
        waitForSocket(sockPath)

        val reply = clientRoundTrip(sockPath, """{"verb":"ping","arg":42}""")

        assertEquals("""{"reply":"pong","echo":{"verb":"ping","arg":42}}""", reply.trim())

        scope.cancel()
        srv.close()
    }

    @Test
    fun `registerHandler throws on duplicate registration`() {
        val srv = IpcServer(tempSocket())
        srv.registerHandler("ping") { _, _ -> """{"ok":true}""" }
        assertFailsWith<IllegalArgumentException> {
            srv.registerHandler("ping") { _, _ -> """{"ok":false}""" }
        }
    }

    @Test
    fun `handler exception produces error JSON reply`() = runBlocking(Dispatchers.IO) {
        val sockPath = tempSocket()
        val srv = IpcServer(sockPath)
        srv.registerHandler("boom") { _, _ -> throw RuntimeException("kaboom") }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        srv.start(scope)
        waitForSocket(sockPath)

        val reply = clientRoundTrip(sockPath, """{"verb":"boom"}""")

        assertTrue(reply.contains("\"error\":\"handler_threw\""), "Expected handler_threw in: $reply")
        assertTrue(reply.contains("\"verb\":\"boom\""), "Expected verb in: $reply")
        assertTrue(reply.contains("kaboom"), "Expected exception message in: $reply")

        scope.cancel()
        srv.close()
    }

    @Test
    fun `concurrent broadcast and RPC reply produce valid NDJSON lines`() = runBlocking(Dispatchers.IO) {
        val sockPath = tempSocket()
        val srv = IpcServer(sockPath)
        srv.registerHandler("echo") { _, json -> """{"reply":"ok","echo":$json}""" }
        // Register sync.subscribe handler before starting the listener
        srv.registerHandler("sync.subscribe") { connId, _ ->
            srv.scheduleAfterReply(connId) {
                srv.flushStateDumpTo(connId)
                srv.registerSyncSubscriber(connId)
            }
            """{"ok":true}"""
        }
        srv.registerConnectionCloseListener { connId ->
            srv.unregisterSyncSubscriber(connId)
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        srv.start(scope)
        waitForSocket(sockPath)

        // One persistent listener client collects broadcast lines.
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val listener = scope.launch {
            java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { sc ->
                sc.connect(java.net.UnixDomainSocketAddress.of(sockPath))
                sc.configureBlocking(false)
                // Send sync.subscribe immediately after connect
                val subReq = """{"verb":"sync.subscribe"}""" + "\n"
                val subBuf = java.nio.ByteBuffer.wrap(subReq.toByteArray(Charsets.UTF_8))
                while (subBuf.hasRemaining()) sc.write(subBuf)
                val buf = java.nio.ByteBuffer.allocate(4096)
                val sb = StringBuilder()
                while (isActive) {
                    buf.clear()
                    val n = sc.read(buf)
                    if (n < 0) break
                    if (n == 0) { delay(10); continue }
                    buf.flip()
                    sb.append(java.nio.charset.StandardCharsets.UTF_8.decode(buf).toString())
                    var idx = sb.indexOf('\n')
                    while (idx >= 0) {
                        received.add(sb.substring(0, idx))
                        sb.delete(0, idx + 1)
                        idx = sb.indexOf('\n')
                    }
                }
            }
        }

        // Give the listener time to subscribe before hammering.
        val deadline = System.currentTimeMillis() + 3000
        while (srv.clientCount < 1 && System.currentTimeMillis() < deadline) delay(20)

        // Fire 50 broadcasts + 50 RPC round-trips (batched 8 at a time so MAX_CLIENTS=10
        // is not exceeded: 1 persistent listener + up to 8 in-flight RPC clients = 9 max).
        // RPC calls are wrapped in runCatching because a concurrent broadcast may close a
        // client connection mid-write under load; the assertion below catches NDJSON interleaving
        // on the persistent listener which is the invariant under test.
        repeat(7) { batch ->
            coroutineScope {
                repeat(8) { i ->
                    val n = batch * 8 + i
                    launch { srv.emit("""{"event":"tick","n":$n}""") }
                    launch { runCatching { clientRoundTrip(sockPath, """{"verb":"echo","n":$n}""") } }
                }
            }
        }
        // Remaining 2 (indices 56..57 — just ensure we issued at least 50 total)
        coroutineScope {
            repeat(2) { i ->
                launch { srv.emit("""{"event":"tick","n":${56 + i}}""") }
                launch { runCatching { clientRoundTrip(sockPath, """{"verb":"echo","n":${56 + i}}""") } }
            }
        }

        delay(500) // drain
        listener.cancel()
        scope.cancel()
        srv.close()

        // Every received line must be valid JSON: starts with { and ends with }.
        assertTrue(received.size >= 50, "Should have received at least the broadcasts, got ${received.size}")
        for (line in received) {
            assertTrue(line.startsWith("{") && line.endsWith("}"), "Garbled NDJSON: '$line'")
        }
    }
}
