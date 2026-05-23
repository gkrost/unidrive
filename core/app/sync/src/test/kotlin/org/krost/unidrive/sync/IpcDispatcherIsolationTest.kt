package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Protects the invariants from `docs/superpowers/specs/ipc-transport-dispatcher-isolation-design.md`.
 *
 * If any of these tests are deleted or loosened, the corresponding invariant
 * silently regresses and the Phase 2 IPC write-timeout bug returns under
 * Dispatchers.IO saturation.
 */
class IpcDispatcherIsolationTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-isolation-test")
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

    private suspend fun readOneLine(client: SocketChannel, timeoutMs: Long): String? {
        val buf = ByteBuffer.allocate(4096)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                buf.flip()
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                sb.append(String(bytes, Charsets.UTF_8))
                val nl = sb.indexOf('\n')
                if (nl >= 0) return sb.substring(0, nl)
            }
            delay(20)
        }
        return null
    }

    private fun sendVerb(client: SocketChannel, verb: String) {
        val line = """{"verb":"$verb"}""" + "\n"
        val buf = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
        while (buf.hasRemaining()) client.write(buf)
    }

    /**
     * The contract test for the structural fix. Saturate the handler dispatcher
     * with N slow handlers, then verify a fast verb on a fresh client still
     * gets its reply within 1 second. Failure means handler saturation is
     * blocking IPC transport writes — i.e. the structural fix didn't take.
     */
    @Test
    fun fast_client_is_not_blocked_by_saturated_handler_dispatcher() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val slowEntered = AtomicInteger(0)
                val s = IpcServer(socketPath)
                server = s
                s.registerHandler("slow") { _, _ ->
                    slowEntered.incrementAndGet()
                    delay(10_000)
                    """{"event":"slow_done"}"""
                }
                s.registerHandler("ping") { _, _ -> """{"event":"pong"}""" }
                s.start(serverScope)

                // Saturate the handler dispatcher: 8 clients each issuing a slow verb.
                // Each slow handler suspends in delay(10_000), occupying a handler-dispatcher
                // slot. With the fix in place, handlers run on handlerDispatcher (defaults to
                // Dispatchers.IO), while transport writes run on the dedicated 4-thread pool,
                // so handler saturation cannot block transport writes. Without the fix, everything
                // runs on Dispatchers.IO; whether the test fails depends on the IO pool size.
                // N=8 chosen to stay under MAX_CLIENTS=10 (8 slow + 1 fast = 9 total), leaving
                // room for the accept loop and other overhead.
                val slowClients = (1..8).map { connectClient() }
                for (c in slowClients) sendVerb(c, "slow")

                // Wait until all 8 handlers have actually entered the body.
                val deadline = System.currentTimeMillis() + 5_000
                while (slowEntered.get() < 8 && System.currentTimeMillis() < deadline) {
                    delay(20)
                }
                check(slowEntered.get() == 8) {
                    "Pre-condition failed: only ${slowEntered.get()}/8 slow handlers entered. " +
                        "Test cannot prove the invariant under partial saturation."
                }

                // Fast client should get its reply quickly despite the saturation.
                val fast = connectClient()
                val t0 = System.currentTimeMillis()
                sendVerb(fast, "ping")
                val line = readOneLine(fast, timeoutMs = 1_000)
                val elapsed = System.currentTimeMillis() - t0
                if (line == null || !line.contains("pong")) {
                    fail(
                        "Fast client did not receive 'pong' within 1000ms despite isolated " +
                            "transport. Saturated handlers blocked the transport — structural " +
                            "fix didn't take. Got: $line (elapsed=${elapsed}ms)",
                    )
                }
                assertTrue(elapsed < 1_000, "Fast client took ${elapsed}ms (expected <1000ms)")
                slowClients.forEach { runCatching { it.close() } }
                fast.close()
            } finally {
                serverScope.cancel()
            }
        }

    /**
     * Proves the handler offload mechanism: handler invocation runs on a
     * different thread than the transport. Without coupling to
     * kotlinx.coroutines internal naming.
     */
    @Test
    fun handler_invocation_is_offloaded_off_transport_pool() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val handlerThreadName = java.util.concurrent.atomic.AtomicReference<String?>()
                val s = IpcServer(socketPath)
                server = s
                s.registerHandler("probe") { _, _ ->
                    handlerThreadName.set(Thread.currentThread().name)
                    """{"event":"probe_done"}"""
                }
                s.start(serverScope)

                val client = connectClient()
                sendVerb(client, "probe")
                val reply = readOneLine(client, timeoutMs = 2_000)
                assertTrue(reply != null && reply.contains("probe_done"), "probe never replied: $reply")

                val name = handlerThreadName.get()
                    ?: fail("Handler did not record its thread name")
                assertTrue(
                    !name.startsWith("ipc-io-"),
                    "Handler ran on transport pool thread '$name'; expected off-pool. " +
                        "withContext(handlerDispatcher) likely missing from dispatchRequest.",
                )
                client.close()
            } finally {
                serverScope.cancel()
            }
        }
}
