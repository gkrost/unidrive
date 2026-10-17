package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reader of an idle IPC connection sleeps in [IpcReadSelector.awaitReadable] and wakes at its deadline to re-run
 * its timeout checks. That deadline is the selector thread's own: a coroutine timeout cancelled the waiter with an
 * exception, and a daemon with two idle connections built one stack trace per second and connection, all day.
 */
class IpcReadSelectorTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    // A connected pair on the loopback: the non-blocking server side the selector waits on, and the client that writes to it.
    private fun pair(): Pair<SocketChannel, SocketChannel> {
        val server = ServerSocketChannel.open().also { closeables += it }
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val client = SocketChannel.open(server.localAddress).also { closeables += it }
        val accepted = server.accept().also { closeables += it }
        accepted.configureBlocking(false)
        return accepted to client
    }

    private fun selector() = IpcReadSelector().also { closeables += it }

    private fun SocketChannel.send() {
        write(ByteBuffer.wrap(byteArrayOf(1)))
    }

    // A wait that never ends fails the test instead of hanging it.
    private fun <T> within(block: suspend CoroutineScope.() -> T): T = runBlocking(Dispatchers.IO) { withTimeout(20_000) { block() } }

    private fun elapsedMs(block: suspend CoroutineScope.() -> Unit): Long {
        val t0 = System.nanoTime()
        within(block)
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `a timed wait with no data returns at its deadline`() {
        val (server, _) = pair()
        val selector = selector()

        val ms = elapsedMs { selector.awaitReadable(server, timeoutMs = 150) }

        assertTrue(ms >= 120, "it waited for the deadline, not less: $ms ms")
        assertTrue(ms < 5_000, "and not for ever: $ms ms")
    }

    @Test
    fun `a timed wait returns at once when data arrives`() {
        val (server, client) = pair()
        val selector = selector()

        val ms =
            within {
                val writer =
                    async {
                        delay(100)
                        client.send()
                    }
                val t0 = System.nanoTime()
                selector.awaitReadable(server, timeoutMs = 30_000)
                writer.await()
                (System.nanoTime() - t0) / 1_000_000
            }

        assertTrue(ms < 10_000, "the data woke it, not the 30 s deadline: $ms ms")
    }

    @Test
    fun `a wait without a deadline still sleeps until there is data`() {
        val (server, client) = pair()
        val selector = selector()

        within {
            val wait = async(start = CoroutineStart.UNDISPATCHED) { selector.awaitReadable(server) }
            delay(300)
            assertFalse(wait.isCompleted, "no deadline, no data: it sleeps")
            client.send()
            wait.await()
        }
    }

    @Test
    fun `waiters with different deadlines are resumed at their own`() {
        val (first, _) = pair()
        val (second, _) = pair()
        val selector = selector()

        within {
            val near = async { selector.awaitReadable(first, timeoutMs = 100) }
            val far = async { selector.awaitReadable(second, timeoutMs = 20_000) }
            near.await()
            assertFalse(far.isCompleted, "the later deadline has not passed")
            far.cancel()
        }
    }

    @Test
    fun `a wait that was cancelled leaves no deadline behind for the next one`() {
        val (server, _) = pair()
        val selector = selector()

        within {
            // Cancelled by the caller well before its deadline.
            withTimeoutOrNull(100) { selector.awaitReadable(server, timeoutMs = 60_000) }
            val t0 = System.nanoTime()
            selector.awaitReadable(server, timeoutMs = 300)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms >= 250, "the next wait is its own: a leftover deadline would have ended it early ($ms ms)")
        }
    }
}
