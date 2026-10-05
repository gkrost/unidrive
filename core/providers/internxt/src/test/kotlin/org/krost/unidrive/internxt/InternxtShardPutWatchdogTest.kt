package org.krost.unidrive.internxt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.http.UploadWatchdogException
import org.krost.unidrive.http.UploadWatchdogLimits
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #571: a shard PUT is cut only when it stops making progress, not when it is slow.
 *
 * The read-idle socket timeout sized for 2 MiB/s (#517 R1) cut a 1.36 GB upload at 650 s on a 1.98 MB/s link, on every
 * attempt. The write-progress watchdog cuts a body that wrote nothing for the idle window and a server that does not
 * answer after the whole body was sent; nothing else.
 *
 * Real CIO engine against a loopback TLS server that reads the body at the pace of a slow uplink (the client's writes
 * block once the socket buffers are full), with the limits scaled from 120 s / 300 s to a second.
 */
class InternxtShardPutWatchdogTest {
    private val url = "https://gateway.invalid/shard"
    private val files = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        files.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun file(size: Int): Path = Files.createTempFile("shard", ".bin").also { Files.write(it, bytes(size)); files.add(it) }

    private fun limits(
        idleWindowMs: Long = 1_000,
        responseWaitFloorMs: Long = 1_000,
    ) = UploadWatchdogLimits(idleWindowMs = idleWindowMs, responseWaitFloorMs = responseWaitFloorMs, tickMs = 50)

    // Both public shard PUTs: the streaming one the provider uses, and the in-memory one.
    private enum class Body { FILE, BYTES }

    private suspend fun InternxtApiService.put(
        body: Body,
        size: Int,
    ) = when (body) {
        Body.FILE -> putEncryptedShardFromFile(url, file(size), size.toLong())
        Body.BYTES -> putEncryptedShard(url, bytes(size))
    }

    // 24 MiB read at ~10 MB/s takes ~2.5 s: more than twice the idle window, so the upload outlives every limit
    // the watchdog has — the shape of the live case, where the body needed longer than the size-derived timeout.
    private fun slowButSteady(body: Body) =
        runBlocking {
            val size = 24 * 1024 * 1024
            val received = AtomicLong(0)
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                received.set(exchange.readBody(size.toLong(), chunkBytes = 256 * 1024, pauseMs = 25))
                exchange.respond(200, "OK", "{}")
            }.use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 500), listingSocketMs = 8_000, shardPutWatchdog = limits())
                    .use { api ->
                        val start = System.nanoTime()
                        api.put(body, size)
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000

                        assertEquals(size.toLong(), received.get(), "the server got the whole body")
                        assertEquals(1, server.connections, "one attempt, not cut and retried")
                        assertTrue(elapsedMs > 2_000, "the upload outlived twice the idle window (took $elapsedMs ms)")
                    }
            }
        }

    @Test
    fun `a slow but steady upload that outlives every limit succeeds - file body`() = slowButSteady(Body.FILE)

    @Test
    fun `a slow but steady upload that outlives every limit succeeds - in-memory body`() = slowButSteady(Body.BYTES)

    // The server reads the first 64 KiB and then nothing: the client's writes block once the buffers are full.
    private fun stalled(body: Body) =
        runBlocking {
            val size = 64 * 1024 * 1024
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                exchange.readBody(64 * 1024)
                Thread.sleep(20_000)
            }.use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 30_000), listingSocketMs = 8_000, shardPutWatchdog = limits())
                    .use { api ->
                        val start = System.nanoTime()
                        val e = assertFailsWith<UploadWatchdogException> { api.put(body, size) }
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000

                        assertEquals(UploadWatchdogException.Reason.STALLED, e.reason)
                        val text = e.message!!
                        assertTrue(text.startsWith("Shard upload stalled: no upload progress for 1."), text)
                        assertTrue(text.contains(" of 67,108,864 bytes sent in "), text)
                        assertTrue(text.contains("(average "), text)
                        assertTrue(elapsedMs < 10_000, "cut soon after the idle window, not by a socket timer ($elapsedMs ms)")
                    }
            }
        }

    @Test
    fun `a body that stops moving is cut as stalled after the idle window - file body`() = stalled(Body.FILE)

    @Test
    fun `a body that stops moving is cut as stalled after the idle window - in-memory body`() = stalled(Body.BYTES)

    @Test
    fun `a server that never answers after the whole body is cut after the response wait`() =
        runBlocking {
            val size = 1024 * 1024
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                exchange.readBody(size.toLong())
                Thread.sleep(20_000)
            }.use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 30_000), listingSocketMs = 8_000, shardPutWatchdog = limits())
                    .use { api ->
                        val start = System.nanoTime()
                        val e = assertFailsWith<UploadWatchdogException> { api.put(Body.FILE, size) }
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000

                        assertEquals(UploadWatchdogException.Reason.NO_RESPONSE, e.reason)
                        val text = e.message!!
                        assertTrue(
                            text.startsWith("Shard upload: the server did not answer within 1.0 s after the body was sent; "),
                            text,
                        )
                        assertTrue(text.contains("1,048,576 of 1,048,576 bytes sent in "), text)
                        assertTrue(elapsedMs in 1_000..6_000, "cut by the response wait ($elapsedMs ms)")
                    }
            }
        }

    @Test
    fun `a server that ends the connection mid-body is reported as the server's or the network's doing`() =
        runBlocking {
            val size = 64 * 1024 * 1024
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                exchange.readBody(1024 * 1024)
                // The handler returns: the server closes the connection with most of the body still to come.
            }.use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 30_000), listingSocketMs = 8_000, shardPutWatchdog = limits())
                    .use { api ->
                        val e = assertFailsWith<java.io.IOException> { api.put(Body.FILE, size) }

                        assertTrue(e !is UploadWatchdogException, "not a watchdog cut: ${e.message}")
                        val text = e.message!!
                        assertTrue(
                            text.startsWith("Shard upload failed: the server or the network ended the connection; "),
                            text,
                        )
                        assertTrue(text.contains(" of 67,108,864 bytes sent in "), text)
                    }
            }
        }

    @Test
    fun `cancelling the caller cancels the upload and closes its connection`() =
        runBlocking {
            val size = 64 * 1024 * 1024
            val connectionEnded = CountDownLatch(1)
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                try {
                    exchange.readBody(size.toLong(), chunkBytes = 64 * 1024, pauseMs = 20)
                } finally {
                    connectionEnded.countDown()
                }
            }.use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 30_000), listingSocketMs = 8_000, shardPutWatchdog = limits())
                    .use { api ->
                        val outcome = AtomicReference<Throwable?>(null)
                        val upload =
                            launch(Dispatchers.Default) {
                                try {
                                    api.put(Body.FILE, size)
                                } catch (t: Throwable) {
                                    outcome.set(t)
                                    throw t
                                }
                            }
                        delay(1_500) // longer than the idle window: the watchdog was running, and the body still moves
                        upload.cancel()
                        withTimeout(5_000) { upload.join() }

                        assertTrue(upload.isCancelled)
                        val t = outcome.get()
                        assertTrue(t is CancellationException, "the caller's cancellation, not a watchdog verdict: $t")
                        assertTrue(t !is UploadWatchdogException && t?.cause !is UploadWatchdogException, "$t")
                        assertTrue(connectionEnded.await(5, TimeUnit.SECONDS), "the server saw the connection end")
                    }
            }
        }
}
