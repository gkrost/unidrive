package org.krost.unidrive.internxt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.http.UploadProgress
import org.krost.unidrive.http.UploadWatchdogException
import org.krost.unidrive.http.UploadWatchdogLimits
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #571: a shard PUT is cut only when it stops making progress, not when it is slow.
 *
 * The read-idle socket timeout sized for 2 MiB/s (#517 R1) cut a 1.36 GB upload at 650 s on a 1.98 MB/s link, on every
 * attempt. The write-progress watchdog cuts a body that wrote nothing for the idle window and a server that does not
 * answer after the whole body was sent; nothing else.
 *
 * Real CIO engine against a loopback TLS server that reads the body at the pace of a slow uplink (the client's writes
 * block once the socket buffers are full), with the limits scaled from 120 s / 300 s to a few seconds.
 *
 * The budgets are wall-clock, so they are sized for a loaded CI runner or a machine running several builds at once, not
 * for an idle one: a sub-second window flaked there (#726, #592). Every assertion on time names the step that went over
 * its budget and by how much, so a red run says what was slow.
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

    // The watchdog's limits in these tests, in wall-clock milliseconds.
    private val idleWindowMs = 3_000L
    private val responseWaitMs = 3_000L

    // The engine's own socket timer where the upload must outlive it: half the idle window.
    private val shortSocketTimeoutMs = idleWindowMs / 2

    // The engine's socket timer where only the watchdog may cut: far beyond every budget below.
    private val outerSocketTimeoutMs = 30_000L

    // What a step may take beyond its nominal duration, for a machine busy with other builds.
    private val stallCutSlackMs = 9_000L
    private val noResponseSlackMs = 5_000L
    private val uploadStartBudgetMs = 60_000L
    private val cancelSettleBudgetMs = 10_000L
    private val connectionEndBudgetMs = 15_000L

    private fun limits() = UploadWatchdogLimits(idleWindowMs = idleWindowMs, responseWaitFloorMs = responseWaitMs, tickMs = 50)

    private fun assertWithinBudget(
        step: String,
        elapsedMs: Long,
        budgetMs: Long,
    ) {
        assertTrue(elapsedMs <= budgetMs, "step \"$step\" took $elapsedMs ms, over its budget of $budgetMs ms by ${elapsedMs - budgetMs} ms")
    }

    private fun assertAtLeast(
        step: String,
        elapsedMs: Long,
        minimumMs: Long,
    ) {
        assertTrue(
            elapsedMs >= minimumMs,
            "step \"$step\" took only $elapsedMs ms, under its minimum of $minimumMs ms by ${minimumMs - elapsedMs} ms",
        )
    }

    // Both public shard PUTs: the streaming one the provider uses, and the in-memory one.
    private enum class Body { FILE, BYTES }

    // The payload is built BEFORE the upload (and before any timer starts): a 24 or 64 MiB fixture takes seconds to
    // build on a busy machine, and that time belongs to no step of the watchdog under test.
    private fun uploadOf(
        body: Body,
        size: Int,
    ): suspend (InternxtApiService) -> Unit {
        val path = if (body == Body.FILE) file(size) else null
        val data = if (body == Body.BYTES) bytes(size) else null
        return { api ->
            if (path != null) api.putEncryptedShardFromFile(url, path, size.toLong()) else api.putEncryptedShard(url, data!!)
        }
    }

    // 24 MiB read at ~3.5 MB/s takes ~7 s: more than twice the idle window, so the upload outlives every limit
    // the watchdog has — the shape of the live case, where the body needed longer than the size-derived timeout.
    private fun slowButSteady(body: Body) =
        runBlocking {
            val size = 24 * 1024 * 1024
            val upload = uploadOf(body, size)
            val received = AtomicLong(0)
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                received.set(exchange.readBody(size.toLong(), chunkBytes = 256 * 1024, pauseMs = 75))
                exchange.respond(200, "OK", "{}")
            }.use { server ->
                loopbackService(
                    loopbackClient(server, socketTimeoutMs = shortSocketTimeoutMs),
                    listingSocketMs = 8_000,
                    shardPutWatchdog = limits(),
                ).use { api ->
                    val start = System.nanoTime()
                    try {
                        upload(api)
                    } catch (e: UploadWatchdogException) {
                        val cutAfterMs = (System.nanoTime() - start) / 1_000_000
                        fail(
                            "step \"steady upload\": the watchdog cut a healthy upload after $cutAfterMs ms " +
                                "(idle window $idleWindowMs ms; the server had read ${received.get()} of $size bytes): ${e.message}",
                        )
                    }
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000

                    assertEquals(size.toLong(), received.get(), "the server got the whole body")
                    assertEquals(1, server.connections, "one attempt, not cut and retried")
                    assertAtLeast("steady upload outlives twice the idle window", elapsedMs, 2 * idleWindowMs)
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
            val upload = uploadOf(body, size)
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                exchange.readBody(64 * 1024)
                Thread.sleep(20_000)
            }.use { server ->
                loopbackService(
                    loopbackClient(server, socketTimeoutMs = outerSocketTimeoutMs),
                    listingSocketMs = 8_000,
                    shardPutWatchdog = limits(),
                ).use { api ->
                    val start = System.nanoTime()
                    val e = assertFailsWith<UploadWatchdogException> { upload(api) }
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000

                    assertEquals(UploadWatchdogException.Reason.STALLED, e.reason)
                    val text = e.message!!
                    val idleSeconds =
                        Regex("""^Shard upload stalled: no upload progress for (\d+(?:\.\d)?) s; """)
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                    assertTrue(idleSeconds != null, text)
                    assertAtLeast("idle time named in the cut message", (idleSeconds!!.toDouble() * 1000).toLong(), idleWindowMs)
                    assertTrue(text.contains(" of 67,108,864 bytes sent in "), text)
                    assertTrue(text.contains("(average "), text)
                    // Well before the engine's socket timer and the server's own sleep: only the watchdog could have cut.
                    assertWithinBudget("stalled body is cut after the idle window", elapsedMs, idleWindowMs + stallCutSlackMs)
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
            val upload = uploadOf(Body.FILE, size)
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                exchange.readBody(size.toLong())
                Thread.sleep(20_000)
            }.use { server ->
                loopbackService(
                    loopbackClient(server, socketTimeoutMs = outerSocketTimeoutMs),
                    listingSocketMs = 8_000,
                    shardPutWatchdog = limits(),
                ).use { api ->
                    val start = System.nanoTime()
                    val e = assertFailsWith<UploadWatchdogException> { upload(api) }
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000

                    assertEquals(UploadWatchdogException.Reason.NO_RESPONSE, e.reason)
                    val text = e.message!!
                    assertTrue(
                        text.startsWith(
                            "Shard upload: the server did not answer within ${UploadProgress.formatSeconds(responseWaitMs)} " +
                                "after the body was sent; ",
                        ),
                        text,
                    )
                    assertTrue(text.contains("1,048,576 of 1,048,576 bytes sent in "), text)
                    assertAtLeast("response wait before the cut", elapsedMs, responseWaitMs)
                    assertWithinBudget("no-response upload is cut by the response wait", elapsedMs, responseWaitMs + noResponseSlackMs)
                }
            }
        }

    @Test
    fun `a server that ends the connection mid-body is reported as the server's or the network's doing`() =
        runBlocking {
            val size = 64 * 1024 * 1024
            val upload = uploadOf(Body.FILE, size)
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                exchange.readBody(1024 * 1024)
                // The handler returns: the server closes the connection with most of the body still to come.
            }.use { server ->
                loopbackService(
                    loopbackClient(server, socketTimeoutMs = outerSocketTimeoutMs),
                    listingSocketMs = 8_000,
                    shardPutWatchdog = limits(),
                ).use { api ->
                    val e = assertFailsWith<java.io.IOException> { upload(api) }

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
            val upload = uploadOf(Body.FILE, size)
            val connectionEnded = CountDownLatch(1)
            val cancelled = AtomicBoolean(false)
            val serverReceived = AtomicLong(0)
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                try {
                    // A slow uplink (64 KiB per 20 ms) while the upload runs. Once the caller cancelled, the bytes that are
                    // still in flight (the client's channel and both socket buffers hold megabytes) are read at full speed:
                    // the connection's end is seen only after them, and at the slow pace that depends on the machine's load.
                    while (serverReceived.get() < size) {
                        val want = minOf(64 * 1024L, size - serverReceived.get())
                        val got = exchange.readBody(want, chunkBytes = 64 * 1024, pauseMs = if (cancelled.get()) 0 else 20)
                        serverReceived.addAndGet(got)
                        if (got < want) break // the client closed the connection
                    }
                } finally {
                    connectionEnded.countDown()
                }
            }.use { server ->
                loopbackService(
                    loopbackClient(server, socketTimeoutMs = outerSocketTimeoutMs),
                    listingSocketMs = 8_000,
                    shardPutWatchdog = limits(),
                ).use { api ->
                    val outcome = AtomicReference<Throwable?>(null)
                    val job =
                        launch(Dispatchers.Default) {
                            try {
                                upload(api)
                            } catch (t: Throwable) {
                                outcome.set(t)
                                throw t
                            }
                        }
                    // Cancel only once the body is really moving. A fixed delay here was the flake: on a busy machine the
                    // connection and its TLS handshake were not up yet when it ran out, the server never saw a connection,
                    // and "the server saw the connection end" could not come true whatever the client did.
                    val startedAt = System.nanoTime()
                    fun waitedMs() = (System.nanoTime() - startedAt) / 1_000_000
                    while (serverReceived.get() < FIRST_BYTES && job.isActive && waitedMs() <= uploadStartBudgetMs) delay(20)
                    val reachedMs = waitedMs()
                    assertTrue(
                        serverReceived.get() >= FIRST_BYTES,
                        "step \"upload reaches the server\": the upload ended after $reachedMs ms with the server at " +
                            "${serverReceived.get()} bytes: ${outcome.get()}",
                    )
                    assertWithinBudget("upload reaches the server", reachedMs, uploadStartBudgetMs)
                    // The watchdog now runs for longer than its idle window while the body keeps moving: it must not cut.
                    delay(idleWindowMs + 500)
                    cancelled.set(true)
                    val cancelAtMs = System.nanoTime()
                    job.cancel()
                    try {
                        withTimeout(cancelSettleBudgetMs) { job.join() }
                    } catch (_: TimeoutCancellationException) {
                        fail("step \"upload job ends after cancel\" is over its budget of $cancelSettleBudgetMs ms (outcome so far: ${outcome.get()})")
                    }

                    assertTrue(job.isCancelled)
                    val t = outcome.get()
                    assertTrue(t is CancellationException, "the caller's cancellation, not a watchdog verdict: $t")
                    assertTrue(t !is UploadWatchdogException && t?.cause !is UploadWatchdogException, "$t")
                    val ended = connectionEnded.await(connectionEndBudgetMs, TimeUnit.MILLISECONDS)
                    val waitedMs = (System.nanoTime() - cancelAtMs) / 1_000_000
                    assertTrue(
                        ended,
                        "step \"server sees the connection end after cancel\" took $waitedMs ms, over its budget of " +
                            "$connectionEndBudgetMs ms by ${waitedMs - connectionEndBudgetMs} ms " +
                            "(connections accepted: ${server.connections}; the server had read ${serverReceived.get()} of $size bytes)",
                    )
                    assertTrue(
                        serverReceived.get() < size,
                        "the upload kept sending after the cancel: the server read the whole body ($size bytes)",
                    )
                }
            }
        }

    private companion object {
        /** The body is moving once the server has read this much of it. */
        const val FIRST_BYTES = 1024L * 1024
    }
}
