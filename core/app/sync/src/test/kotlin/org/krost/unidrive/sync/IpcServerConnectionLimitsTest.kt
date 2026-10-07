package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the IPC server limits connections (private tracker, item F7; engine #494):
 * a connection over the cap is told why before it is closed, and a connection that sent no
 * request for the idle timeout is closed, unless it subscribed to sync progress or used one of
 * the verbs the daemon exempts (the hydration verbs). Time is a manual clock, so no test waits
 * for a timeout.
 */
class IpcServerConnectionLimitsTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = mutableListOf<SocketChannel>()
    private val now = AtomicLong(1_000_000L)

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-limits")
        socketPath = socketDir.resolve("limits.sock")
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        serverScope.cancel()
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    private fun newServer(idleTimeoutMs: Long): IpcServer =
        IpcServer(socketPath, idleTimeoutMs = idleTimeoutMs, clock = { now.get() }).also {
            server = it
            it.registerHandler("ping") { _, _ -> """{"ok":true}""" }
        }

    // ── the refusal line ────────────────────────────────────────────────────

    @Test
    fun `a connection over the limit reads too_many_clients and then end of stream`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 0)
            srv.start(serverScope)
            fillToTheLimit(srv)

            val refused = connect()
            assertEquals(listOf(TOO_MANY_CLIENTS), readUntilEof(refused))
            assertEquals(IpcServer.MAX_CLIENTS, srv.clientCount, "the refused connection never counts")
        }

    @Test
    fun `a refused client that sends its request at once still reads the reason`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 0)
            srv.start(serverScope)
            fillToTheLimit(srv)

            val refused = connect()
            send(refused, """{"verb":"ping"}""")
            assertEquals(listOf(TOO_MANY_CLIENTS), readUntilEof(refused))
        }

    @Test
    fun `a slot freed by a closing client is available again`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 0)
            srv.start(serverScope)
            fillToTheLimit(srv)
            clients.removeAt(0).close()
            waitUntil("the closed client is removed") { srv.clientCount == IpcServer.MAX_CLIENTS - 1 }

            val next = connect()
            assertEquals("""{"ok":true}""", roundTrip(next, """{"verb":"ping"}"""))
        }

    // ── the idle timeout ────────────────────────────────────────────────────

    @Test
    fun `a connection that sent no request for the idle timeout is closed, not before`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 60_000)
            srv.start(serverScope)
            val silent = connect() // never sends anything
            val quiet = connect().also { assertEquals("""{"ok":true}""", roundTrip(it, """{"verb":"ping"}""")) }
            waitUntil("both clients registered") { srv.clientCount == 2 }

            now.addAndGet(59_000)
            delay(300) // several idle checks run
            assertEquals(2, srv.clientCount, "nothing is closed before the timeout")
            assertStillAnswers(quiet)

            now.addAndGet(61_000) // quiet's last request was at +59 s; now +120 s
            assertClosed(silent)
            assertClosed(quiet)
            waitUntil("both removed") { srv.clientCount == 0 }
        }

    @Test
    fun `a request resets the idle timer, so an active connection stays open`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 60_000)
            srv.start(serverScope)
            val active = connect()
            repeat(5) {
                now.addAndGet(50_000) // 250 s in all, never 60 s without a request
                assertEquals("""{"ok":true}""", roundTrip(active, """{"verb":"ping"}"""))
            }
            delay(300)
            assertEquals(1, srv.clientCount)
            assertStillAnswers(active)
        }

    @Test
    fun `a sync subscriber is never closed for being idle`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 60_000)
            srv.registerHandler("sync.subscribe") { connId, _ ->
                srv.scheduleAfterReply(connId) { srv.registerSyncSubscriber(connId) }
                """{"ok":true}"""
            }
            srv.start(serverScope)
            val subscriber = connect().also { assertEquals("""{"ok":true}""", roundTrip(it, """{"verb":"sync.subscribe"}""")) }
            val other = connect()
            waitUntil("subscriber registered") { srv.syncSubscribersSnapshot.size == 1 && srv.clientCount == 2 }

            now.addAndGet(24 * 3_600_000L)
            assertClosed(other)
            delay(300)
            assertEquals(1, srv.clientCount)
            srv.emit("""{"event":"tick"}""")
            assertEquals(listOf("""{"event":"tick"}"""), readLines(subscriber, 1))
        }

    @Test
    fun `a connection that used an exempt verb is never closed for being idle`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 60_000)
            // The daemon passes the hydration verbs: their handles and subscription live on the
            // connection, and its mount clients are recognised by them.
            srv.registerHandler("hold") { _, _ -> """{"ok":true}""" }
            srv.registerIdleExemptVerbs(listOf("hold"))
            srv.start(serverScope)
            val holder = connect().also { assertEquals("""{"ok":true}""", roundTrip(it, """{"verb":"hold"}""")) }
            val other = connect().also { assertEquals("""{"ok":true}""", roundTrip(it, """{"verb":"ping"}""")) }
            waitUntil("both registered") { srv.clientCount == 2 }

            now.addAndGet(24 * 3_600_000L)
            assertClosed(other)
            delay(300)
            assertEquals(1, srv.clientCount, "the connection that used the exempt verb stays open")
            assertStillAnswers(holder)
        }

    @Test
    fun `an exempt verb that is not registered exempts nothing`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 60_000)
            srv.registerIdleExemptVerbs(listOf("hold"))
            srv.start(serverScope)
            // No handler: the request is answered unknown_verb and marks nothing.
            val client = connect().also { assertEquals("""{"ok":false,"error":"unknown_verb"}""", roundTrip(it, """{"verb":"hold"}""")) }
            now.addAndGet(120_000)
            assertClosed(client)
        }

    @Test
    fun `an idle timeout of zero never closes a connection`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer(idleTimeoutMs = 0)
            srv.start(serverScope)
            val client = connect()
            waitUntil("registered") { srv.clientCount == 1 }
            now.addAndGet(365 * 24 * 3_600_000L)
            delay(300)
            assertEquals(1, srv.clientCount)
            assertStillAnswers(client)
        }

    @Test
    fun `the idle timeout from the environment is clamped`() {
        assertEquals(IpcServer.DEFAULT_IDLE_TIMEOUT_MS, IpcServer.parseIdleTimeoutMs(null), "unset")
        assertEquals(IpcServer.DEFAULT_IDLE_TIMEOUT_MS, IpcServer.parseIdleTimeoutMs("soon"), "not a number")
        assertEquals(IpcServer.DEFAULT_IDLE_TIMEOUT_MS, IpcServer.parseIdleTimeoutMs("-5"), "negative")
        assertEquals(0L, IpcServer.parseIdleTimeoutMs("0"), "0 switches the idle close off")
        assertEquals(60_000L, IpcServer.parseIdleTimeoutMs("1000"), "below the minimum: the minimum")
        assertEquals(2_700_000L, IpcServer.parseIdleTimeoutMs(" 2700000 "), "in range: as given")
        assertEquals(86_400_000L, IpcServer.parseIdleTimeoutMs("999999999999"), "above the maximum: the maximum")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    // Opens MAX_CLIENTS connections, each confirmed by a reply, so the server has registered all.
    private suspend fun fillToTheLimit(srv: IpcServer) {
        repeat(IpcServer.MAX_CLIENTS) {
            assertEquals("""{"ok":true}""", roundTrip(connect(), """{"verb":"ping"}"""))
        }
        waitUntil("all slots taken") { srv.clientCount == IpcServer.MAX_CLIENTS }
    }

    private suspend fun connect(): SocketChannel {
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        ch.connect(UnixDomainSocketAddress.of(socketPath))
        ch.configureBlocking(false)
        clients += ch
        return ch
    }

    private suspend fun send(client: SocketChannel, request: String) {
        val buf = ByteBuffer.wrap((request + "\n").toByteArray(Charsets.UTF_8))
        while (buf.hasRemaining()) {
            if (client.write(buf) == 0) delay(5)
        }
    }

    private suspend fun roundTrip(client: SocketChannel, request: String): String {
        send(client, request)
        val lines = readLines(client, 1)
        assertEquals(1, lines.size, "one reply line to $request")
        return lines[0]
    }

    private suspend fun assertStillAnswers(client: SocketChannel) {
        assertEquals("""{"ok":true}""", roundTrip(client, """{"verb":"ping"}"""))
    }

    // The server closed the connection: the read reaches end of stream, with no line before it.
    private suspend fun assertClosed(client: SocketChannel) {
        assertEquals(emptyList(), readUntilEof(client), "a connection closed for being idle gets no line")
    }

    private suspend fun readLines(client: SocketChannel, count: Int): List<String> {
        val bytes = java.io.ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(4096)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && bytes.toByteArray().count { it == '\n'.code.toByte() } < count) {
            buf.clear()
            val n = client.read(buf)
            if (n < 0) break
            if (n == 0) delay(10) else bytes.write(buf.array(), 0, n)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).split('\n').dropLast(1)
    }

    private suspend fun readUntilEof(client: SocketChannel): List<String> {
        val bytes = java.io.ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(4096)
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            check(System.currentTimeMillis() < deadline) {
                "no end of stream within 10 s; read so far: ${String(bytes.toByteArray(), Charsets.UTF_8)}"
            }
            buf.clear()
            // A reset ends the stream too; what arrived before it still counts.
            val n = try { client.read(buf) } catch (_: java.io.IOException) { -1 }
            if (n < 0) break
            if (n == 0) delay(10) else bytes.write(buf.array(), 0, n)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).split('\n').dropLast(1)
    }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting until $what" }
            delay(10)
        }
    }

    private companion object {
        const val TOO_MANY_CLIENTS = """{"ok":false,"error":"too_many_clients"}"""
    }
}
