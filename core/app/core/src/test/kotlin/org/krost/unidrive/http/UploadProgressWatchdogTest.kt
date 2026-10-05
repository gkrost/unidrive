package org.krost.unidrive.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #571: the verdicts of the write-progress watchdog on a clock the test drives, and the cut / cancellation shape of
 * [withUploadWatchdog] on a block without a network. The loopback tests of the Internxt provider drive it through a
 * real engine.
 */
class UploadProgressWatchdogTest {
    private val clockNanos = AtomicLong(0)

    private fun advanceMs(ms: Long) {
        clockNanos.addAndGet(ms * 1_000_000)
    }

    private fun progress(total: Long) = UploadProgress(total) { clockNanos.get() }

    @Test
    fun `a body that keeps writing is never stalled, however long it takes`() {
        val p = progress(1_000_000_000)
        p.bodyStarted()
        repeat(10_000) {
            advanceMs(100_000) // a chunk every 100 s, below the 120 s window: 11.5 days in all
            p.wrote(64 * 1024)
            assertNull(p.check(idleWindowMs = 120_000, responseWaitMs = 300_000, what = "Upload"))
        }
    }

    @Test
    fun `no write for the idle window is a stall, with the bytes and the rate in the text`() {
        val p = progress(1_361_366_128)
        p.bodyStarted()
        advanceMs(500_000)
        p.wrote(990_000_000) // 1.98 MB/s over 500 s
        advanceMs(119_999)
        assertNull(p.check(120_000, 300_000, "Shard upload"))
        advanceMs(1)
        val cut = p.check(120_000, 300_000, "Shard upload")!!
        assertEquals(UploadWatchdogException.Reason.STALLED, cut.reason)
        assertEquals(
            "Shard upload stalled: no upload progress for 120 s; cut after 990,000,000 of 1,361,366,128 bytes sent " +
                "in 620 s (average 1.98 MB/s)",
            cut.message,
        )
    }

    @Test
    fun `the idle window also covers the time before the first body byte`() {
        val p = progress(10)
        advanceMs(120_000)
        assertEquals(UploadWatchdogException.Reason.STALLED, p.check(120_000, 300_000, "Upload")!!.reason)
    }

    @Test
    fun `after the last byte only the response wait counts`() {
        val p = progress(2_000_000)
        p.bodyStarted()
        advanceMs(1_000)
        p.wrote(2_000_000)
        p.bodyComplete()
        advanceMs(299_999)
        assertNull(p.check(120_000, 300_000, "Upload"), "the idle window no longer applies")
        advanceMs(1)
        val cut = p.check(120_000, 300_000, "Upload")!!
        assertEquals(UploadWatchdogException.Reason.NO_RESPONSE, cut.reason)
        assertEquals(
            "Upload: the server did not answer within 300 s after the body was sent; " +
                "2,000,000 of 2,000,000 bytes sent in 301 s (average 2.00 MB/s)",
            cut.message,
        )
    }

    @Test
    fun `a second write of the body starts over`() {
        val p = progress(100)
        p.bodyStarted()
        p.wrote(100)
        p.bodyComplete()
        p.bodyStarted()
        assertEquals(0, p.bytesWritten)
        assertTrue(!p.bodySent)
    }

    @Test
    fun `rates and durations read plainly`() {
        assertEquals("1.98 MB/s", UploadProgress.formatRate(1_980_000))
        assertEquals("512 KB/s", UploadProgress.formatRate(512_000))
        assertEquals("300 B/s", UploadProgress.formatRate(300))
        assertEquals("650 s", UploadProgress.formatSeconds(650_409))
        assertEquals("0.4 s", UploadProgress.formatSeconds(400))
    }

    @Test
    fun `the watchdog cuts a block that makes no progress`() =
        runBlocking {
            val p = UploadProgress(100)
            val e =
                assertFailsWith<UploadWatchdogException> {
                    withUploadWatchdog(p, idleWindowMs = 200, responseWaitMs = 200, tickMs = 20, what = "Upload") {
                        p.bodyStarted()
                        awaitCancellation()
                    }
                }
            assertEquals(UploadWatchdogException.Reason.STALLED, e.reason)
        }

    // #572 review: an HTTP engine may turn the watchdog's cancellation into an IOException of its own ("socket closed").
    // The user must still see the verdict (stalled), not the engine's generic text.
    @Test
    fun `the verdict wins when the engine turns the cut into an IOException of its own`() =
        runBlocking {
            val p = UploadProgress(100)
            val e =
                assertFailsWith<UploadWatchdogException> {
                    withUploadWatchdog(p, idleWindowMs = 200, responseWaitMs = 200, tickMs = 20, what = "Upload") {
                        p.bodyStarted()
                        try {
                            awaitCancellation()
                        } catch (c: CancellationException) {
                            throw java.io.IOException("Socket closed", c)
                        }
                    }
                }
            assertEquals(UploadWatchdogException.Reason.STALLED, e.reason)
        }

    @Test
    fun `a block that finishes in time returns its value`() =
        runBlocking {
            val p = UploadProgress(100)
            val value =
                withUploadWatchdog(p, idleWindowMs = 200, responseWaitMs = 200, tickMs = 20) {
                    p.bodyStarted()
                    repeat(10) {
                        delay(50)
                        p.wrote(10) // 500 ms in all, never 200 ms without a write
                    }
                    p.bodyComplete()
                    "answer"
                }
            assertEquals("answer", value)
        }

    @Test
    fun `cancelling the caller propagates as cancellation, not as a verdict`() =
        runBlocking {
            val p = UploadProgress(100)
            val started = CompletableDeferred<Unit>()
            val outcome = AtomicReference<Throwable?>(null)
            val job =
                launch(Dispatchers.Default) {
                    try {
                        withUploadWatchdog(p, idleWindowMs = 60_000, responseWaitMs = 60_000, tickMs = 20) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                    } catch (t: Throwable) {
                        outcome.set(t)
                        throw t
                    }
                }
            started.await()
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(outcome.get() is CancellationException, "${outcome.get()}")
            assertTrue(outcome.get() !is UploadWatchdogException)
        }
}
