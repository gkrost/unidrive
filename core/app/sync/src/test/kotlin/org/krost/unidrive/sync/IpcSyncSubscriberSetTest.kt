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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Protects the invariants from
 * `docs/dev/specs/sync-progress-subscriber-set-design.md`.
 *
 * If any of these tests are deleted or loosened, the corresponding
 * invariant silently regresses and the Phase 2 co-daemon Broken-pipe
 * cycle (BACKLOG entry 1de0cb3, refined in 013da46) returns.
 */
class IpcSyncSubscriberSetTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-sub-test")
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

    private suspend fun readAvailableBytes(
        client: SocketChannel,
        windowMs: Long,
    ): ByteArray {
        val buf = ByteBuffer.allocate(8192)
        val acc = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                buf.flip()
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                acc.write(bytes)
            }
            delay(20)
        }
        return acc.toByteArray()
    }

    /**
     * The Phase 2 contract test. A connected client that did NOT issue
     * `sync.subscribe` must receive zero bytes from `server.emit(...)`
     * fanout. Without the fix this fails — the broadcast loop fans to
     * all clients regardless. With the fix it passes deterministically.
     */
    @Test
    fun non_subscriber_receives_no_sync_progress_bytes() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.start(serverScope)

                val client = connectClient()
                // Wait briefly so accept loop has registered the client.
                delay(100)

                // Fire several broadcast events.
                repeat(5) { i ->
                    s.emit("""{"event":"test","seq":$i}""")
                }

                // Read with a 500ms window. Without the fix, we receive at
                // least one of the five lines. With the fix, zero bytes.
                val received = readAvailableBytes(client, windowMs = 500)
                assertEquals(
                    0, received.size,
                    "Non-subscriber received ${received.size} bytes of " +
                        "unsolicited broadcast traffic: ${String(received).take(200)}",
                )
                client.close()
            } finally {
                serverScope.cancel()
            }
        }
}
