package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client's own socket timeout is not the server closing the connection, although over TLS the two read the same
 * ("the server prematurely closed the connection"). These tests run the service on the real CIO engine against real
 * loopback sockets, plain and TLS, because only a real socket has a timer that fires: a MockEngine answers or throws at
 * once. Timeouts are a few hundred milliseconds instead of the production minute.
 */
class InternxtOwnTimeoutTest {
    private val ktorPlainTimer = "Socket timeout has expired"

    // ---- the heavy listings get the time they need ---------------------------------------------------------------------

    @Test
    fun `a heavy listing that needs longer than the default socket timeout completes on the listing timeouts`() {
        LoopbackServer(tls = false) { exchange ->
            exchange.readHead()
            Thread.sleep(1_500)
            exchange.respond(200, "OK", "[]")
        }.use { server ->
            loopbackService(loopbackClient(server, socketTimeoutMs = 500), socketMs = 500, listingSocketMs = 8_000, listingRequestMs = 10_000).use { api ->
                runBlocking {
                    assertEquals(emptyList(), api.listFiles(), "the answer came after 3 default socket timeouts")
                    assertEquals(1, server.connections)
                }
            }
        }
    }

    @Test
    fun `the same listing is cut by the timer when it only has the default socket timeout`() {
        LoopbackServer(tls = false) { exchange ->
            exchange.readHead()
            Thread.sleep(5_000)
            exchange.respond(200, "OK", "[]")
        }.use { server ->
            loopbackService(loopbackClient(server, socketTimeoutMs = 500), socketMs = 500, listingSocketMs = 500).use { api ->
                runBlocking {
                    val failure = assertFailsWith<InternxtApiException> { api.listFiles() }
                    assertTrue(failure.timedOutLocally, failure.message)
                    assertEquals(1, server.connections, "a timer failure is not retried")
                }
            }
        }
    }

    @Test
    fun `a cheap call keeps the default socket timeout while the listing gets the long one`() {
        LoopbackServer(tls = false) { exchange ->
            exchange.readHead()
            Thread.sleep(5_000)
            exchange.respond(200, "OK", "[]")
        }.use { server ->
            loopbackService(loopbackClient(server, socketTimeoutMs = 500), socketMs = 500, listingSocketMs = 8_000, listingRequestMs = 10_000).use { api ->
                runBlocking {
                    val failure = assertFailsWith<InternxtApiException> { api.getFileMeta("some-file") }
                    assertTrue(failure.timedOutLocally, "a /meta call still runs on the default timeout: ${failure.message}")
                }
            }
        }
    }

    // ---- a timer failure is not retried, a real close still goes through the ladder ----------------------------------

    private fun silentServerEndsByTheTimerAfterOneAttempt(
        tls: Boolean,
        socketMs: Long,
    ) {
        LoopbackServer(tls) { exchange ->
            exchange.readHead()
            exchange.awaitClientClose()
        }.use { server ->
            loopbackService(loopbackClient(server, socketTimeoutMs = 60_000), socketMs = socketMs).use { api ->
                ServiceWarnings().use { warnings ->
                    runBlocking {
                        val started = System.nanoTime()
                        val failure = assertFailsWith<InternxtApiException> { api.getFileMeta("some-file") }
                        val tookMs = (System.nanoTime() - started) / 1_000_000

                        assertEquals(503, failure.statusCode, "the status the walk fallback and the folder skip act on")
                        assertTrue(failure.timedOutLocally, failure.message)
                        val message = failure.message.orEmpty()
                        assertTrue(message.startsWith("timed out after "), message)
                        assertTrue(message.contains("waiting for the response of GET https://gateway.internxt.com/drive/files/some-file/meta;"), message)
                        assertTrue(message.contains("this is the engine's own limit"), message)
                        assertFalse(message.contains("?"), "no query string in a message: $message")
                        assertFalse(message.contains("prematurely closed"), "this is not what a close reads: $message")
                        assertEquals(1, server.connections, "exactly one attempt: the same request meets the same timer")
                        assertTrue(tookMs < socketMs + 4_000, "one timeout, not three with the 2 s and 4 s pauses between: $tookMs ms")
                        val chain = generateSequence(failure.cause) { it.cause }.toList()
                        if (tls) {
                            // The shape the whole classification rests on: over TLS the timer reads like a close by the peer.
                            assertTrue(
                                chain.any { it is EOFException && it.message.orEmpty().contains("prematurely closed") },
                                "TLS reports the client's own timer as a close: $chain",
                            )
                        } else {
                            assertTrue(chain.any { it is SocketTimeoutException }, "plain HTTP reports the timer itself: $chain")
                            assertTrue(chain.any { it.message.orEmpty().contains(ktorPlainTimer) }, "$chain")
                        }
                        // The WARN carries what is needed to tell the timer from a close: how long it took and the timeout in force.
                        val logged = warnings.messages.single()
                        assertTrue(logged.startsWith("GET https://gateway.internxt.com/drive/files/some-file/meta timed out after "), logged)
                        assertTrue(logged.contains("the engine's own timer fired (socket timeout $socketMs ms)"), logged)
                        assertFalse(logged.contains("?"), logged)
                    }
                }
            }
        }
    }

    @Test
    fun `a silent server ends a GET by the timer after exactly one attempt (plain HTTP)`() =
        silentServerEndsByTheTimerAfterOneAttempt(tls = false, socketMs = 500)

    @Test
    fun `a silent server ends a GET by the timer after exactly one attempt (TLS)`() =
        silentServerEndsByTheTimerAfterOneAttempt(tls = true, socketMs = 1_500)

    // A close by the peer after 150 ms is not the timer (the socket timeout is 20 s), over TLS it reads the same: the
    // ladder of three attempts runs, with the pauses of 2 s and 4 s (virtual time here).
    private fun serverThatClosesGoesThroughTheLadder(tls: Boolean) =
        runTest {
            LoopbackServer(tls) { exchange ->
                exchange.readHead()
                Thread.sleep(150)
            }.use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 60_000), socketMs = 20_000).use { api ->
                    ServiceWarnings().use { warnings ->
                        val failure = assertFailsWith<InternxtApiException> { api.getFileMeta("some-file") }

                        assertEquals(503, failure.statusCode)
                        assertFalse(failure.timedOutLocally, "a quick close is the peer, not the timer: ${failure.message}")
                        assertTrue(failure.message.orEmpty().startsWith("Server closed connection for GET"), failure.message)
                        assertEquals(3, server.connections, "three attempts")
                        assertEquals(2_000L + 4_000L, currentTime, "2 s, then 4 s between them, and no 8 s after the last one")
                        val logged = warnings.messages
                        assertEquals(3, logged.size, "one WARN per failed attempt: $logged")
                        assertTrue(logged.all { it.contains("failed after ") && it.contains("(socket timeout 20000 ms)") }, "$logged")
                        assertTrue(logged.none { it.contains("the engine's own timer") }, "$logged")
                    }
                }
            }
        }

    @Test
    fun `a server that closes the connection quickly still goes through the whole ladder (plain HTTP)`() = serverThatClosesGoesThroughTheLadder(tls = false)

    @Test
    fun `a server that closes the connection quickly still goes through the whole ladder (TLS)`() = serverThatClosesGoesThroughTheLadder(tls = true)

    // ---- the classification itself ----------------------------------------------------------------------------------

    private fun ktorClose() =
        io.ktor.utils.io.ClosedReadChannelException(EOFException("Failed to parse HTTP response: the server prematurely closed the connection"))

    @Test
    fun `a closed-before-response failure after 90 percent of the socket timeout is the engine's own timer`() {
        assertTrue(InternxtApiService.isOwnTimeout(ktorClose(), elapsedMs = 60_000, socketTimeoutMs = 60_000))
        assertTrue(InternxtApiService.isOwnTimeout(ktorClose(), elapsedMs = 54_000, socketTimeoutMs = 60_000), "90 % is the line")
        assertFalse(InternxtApiService.isOwnTimeout(ktorClose(), elapsedMs = 53_999, socketTimeoutMs = 60_000))
        assertFalse(InternxtApiService.isOwnTimeout(ktorClose(), elapsedMs = 1_000, socketTimeoutMs = 60_000), "a quick close is the peer")
        assertTrue(InternxtApiService.isOwnTimeout(ktorClose(), elapsedMs = 61_480, socketTimeoutMs = 60_000), "the timer may fire a little late")
        // the listing timeouts: the line moves with the timeout in force
        assertFalse(InternxtApiService.isOwnTimeout(ktorClose(), elapsedMs = 60_100, socketTimeoutMs = 330_000))
        assertTrue(InternxtApiService.isOwnTimeout(ktorClose(), elapsedMs = 297_000, socketTimeoutMs = 330_000))
    }

    @Test
    fun `plain HTTP reports the timer as a SocketTimeoutException and that counts too`() {
        val plain = SocketTimeoutException("Socket timeout has expired [url=https://gateway.internxt.com/drive/files, socket_timeout=60000] ms")
        assertTrue(InternxtApiService.isOwnTimeout(plain, elapsedMs = 60_050, socketTimeoutMs = 60_000))
        assertFalse(InternxtApiService.isOwnTimeout(plain, elapsedMs = 100, socketTimeoutMs = 60_000))
    }

    @Test
    fun `other failures are never taken for the timer, however long they took`() {
        assertFalse(InternxtApiService.isOwnTimeout(ConnectException("Connection refused"), elapsedMs = 600_000, socketTimeoutMs = 60_000))
        assertFalse(InternxtApiService.isOwnTimeout(java.io.IOException("Connection reset"), elapsedMs = 600_000, socketTimeoutMs = 60_000))
    }

    @Test
    fun `a message never carries a query string`() {
        assertEquals("https://host/drive/files", InternxtApiService.withoutQuery("https://host/drive/files?status=EXISTS&offset=0"))
        assertEquals("https://host/p", InternxtApiService.withoutQuery("https://host/p?X-Amz-Signature=secret#frag"))
        assertEquals("https://host/p", InternxtApiService.withoutQuery("https://host/p"))
        val message = InternxtApiService.ownTimeoutMessage("PUT", "https://host/p?X-Amz-Signature=secret", 61_234, 60_000)
        assertEquals(
            "timed out after 61.2 s waiting for the response of PUT https://host/p; " +
                "this is the engine's own limit (socket timeout 60.0 s), not a server close",
            message,
        )
    }
}
