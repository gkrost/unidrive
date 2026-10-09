package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #687: the connection reader sleeps until its socket has data instead of polling every 20 ms. The
 * bounds are loose on purpose (CI machines are slow): the old poll put a ~20 ms floor under every
 * request, so a median far under it can only come from waking on data.
 */
class IpcServerWakeupTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = mutableListOf<SocketChannel>()

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-wakeup")
        socketPath = socketDir.resolve("wakeup.sock")
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        serverScope.cancel()
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    private fun startServer(): IpcServer =
        IpcServer(socketPath, idleTimeoutMs = 0).also {
            server = it
            it.registerHandler("daemon.status") { _, _ -> STATUS_REPLY }
            it.start(serverScope)
        }

    // A blocking client: a round trip's time is what the caller sees, with no client-side polling.
    private fun connect(): SocketChannel {
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        ch.connect(UnixDomainSocketAddress.of(socketPath))
        clients += ch
        return ch
    }

    private fun roundTrip(client: SocketChannel): String {
        val out = ByteBuffer.wrap(STATUS_REQUEST.toByteArray(Charsets.UTF_8))
        while (out.hasRemaining()) client.write(out)
        val bytes = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(4096)
        while (!bytes.toString(Charsets.UTF_8).endsWith("\n")) {
            buf.clear()
            val n = client.read(buf)
            check(n >= 0) { "closed before a reply" }
            bytes.write(buf.array(), 0, n)
        }
        return bytes.toString(Charsets.UTF_8).trimEnd()
    }

    private fun readsEof(
        client: SocketChannel,
        withinMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        client.configureBlocking(false)
        val buf = ByteBuffer.allocate(256)
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n =
                try {
                    client.read(buf)
                } catch (_: java.io.IOException) {
                    -1
                }
            if (n < 0) return true
            if (n == 0) Thread.sleep(5)
        }
        return false
    }

    private suspend fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting until $what" }
            delay(10)
        }
    }

    @Test
    fun `sequential round trips on one connection are answered well under the old 20 ms poll`() =
        runBlocking(Dispatchers.IO) {
            startServer()
            val client = connect()
            repeat(10) { assertEquals(STATUS_REPLY, roundTrip(client)) } // warm-up
            val micros = LongArray(50)
            for (i in micros.indices) {
                val t0 = System.nanoTime()
                assertEquals(STATUS_REPLY, roundTrip(client))
                micros[i] = (System.nanoTime() - t0) / 1_000
            }
            micros.sort()
            val median = micros[micros.size / 2] / 1000.0
            val p95 = micros[(micros.size * 95) / 100] / 1000.0
            println("IPC round trip over 50 requests: median=%.2f ms p95=%.2f ms".format(median, p95))
            assertTrue(median < 10.0, "median $median ms, the old poll floor was ~20 ms")
            assertTrue(p95 < 50.0, "p95 $p95 ms")
        }

    @Test
    fun `a request after a long idle spell is read at once`() =
        runBlocking(Dispatchers.IO) {
            startServer()
            val client = connect()
            assertEquals(STATUS_REPLY, roundTrip(client))
            delay(1_500) // the reader is asleep on the selector
            val t0 = System.nanoTime()
            assertEquals(STATUS_REPLY, roundTrip(client))
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            assertTrue(ms < 100.0, "answered in $ms ms")
        }

    @Test
    fun `a request split over two writes is answered once the line is complete`() =
        runBlocking(Dispatchers.IO) {
            startServer()
            val client = connect()
            client.write(ByteBuffer.wrap(STATUS_REQUEST.substring(0, 10).toByteArray()))
            delay(100)
            client.write(ByteBuffer.wrap(STATUS_REQUEST.substring(10).toByteArray()))
            val buf = ByteBuffer.allocate(256)
            val n = client.read(buf)
            assertEquals(STATUS_REPLY + "\n", String(buf.array(), 0, n))
        }

    @Test
    fun `cancelling the server scope ends readers that sleep on idle connections`() =
        runBlocking(Dispatchers.IO) {
            val srv = startServer()
            val idle = List(5) { connect() }
            idle.forEach { roundTrip(it) } // every reader has read once and sleeps again
            waitUntil("all registered") { srv.clientCount == 5 }
            delay(100)

            serverScope.cancel()

            idle.forEach { assertTrue(readsEof(it, 3_000), "a cancelled reader closes its connection") }
            waitUntil("all removed") { srv.clientCount == 0 }
        }

    @Test
    fun `closing the server returns promptly and ends sleeping readers`() =
        runBlocking(Dispatchers.IO) {
            val srv = startServer()
            val idle = List(5) { connect() }
            idle.forEach { roundTrip(it) }
            waitUntil("all registered") { srv.clientCount == 5 }
            delay(100)

            val t0 = System.nanoTime()
            srv.close()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 2_000, "close() took $ms ms")

            idle.forEach { assertTrue(readsEof(it, 3_000), "closing the server closes the connection") }
        }

    @Test
    fun `a client that closes its end is removed at once`() =
        runBlocking(Dispatchers.IO) {
            val srv = startServer()
            val client = connect()
            roundTrip(client)
            waitUntil("registered") { srv.clientCount == 1 }
            client.close()
            waitUntil("removed") { srv.clientCount == 0 }
        }

    private companion object {
        const val STATUS_REQUEST = "{\"verb\":\"daemon.status\"}\n"
        const val STATUS_REPLY = "{\"ok\":true,\"state\":\"idle\"}"
    }
}
