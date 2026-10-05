package org.krost.unidrive.http

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * #571: a write-progress watchdog for data-plane PUTs.
 *
 * **Why.** The engine's socket timeout is read-idle: it fires when nothing was RECEIVED for the interval. During a PUT
 * the server says nothing until the whole body has arrived, so that timer measures the whole upload and cannot tell
 * "slow but moving" from "stuck". Sized for an assumed floor throughput (#517 R1), it cut every upload on a link below
 * that floor at the same point, and every retry restarted at byte 0 (a 1.36 GB file at 1.98 MB/s, cut at 650 s on each
 * attempt).
 *
 * **What.** The request body is sent in chunks by [progressTrackingBody] / [progressTrackingFileBody], which record
 * every chunk the transport accepted in an [UploadProgress]. [withUploadWatchdog] runs the request and cuts it in two
 * cases only:
 * - while the body is being sent: when no body byte was written for the idle window. A slow upload keeps writing and is
 *   never cut, however long it takes. A stuck one stops writing as soon as the socket buffers are full (the writes
 *   suspend on backpressure), so it is still caught.
 * - after the last body byte was written: when the server did not answer within the response wait (it still has to
 *   receive what sits in the socket buffers and to store the object).
 *
 * The caller raises the engine's own socket and request timeouts above both bounds for this request, so that they are
 * only an outer safety net (see [UploadTimeoutPolicy.computeWatchedOuterLimitMs]).
 */
public class UploadProgress(
    /** The size of the body, for the failure text. */
    public val totalBytes: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val startedAt = nanoTime()
    private val bodyStartedAt = AtomicLong(NOT_YET)
    private val lastProgressAt = AtomicLong(startedAt)
    private val written = AtomicLong(0)
    private val bodySentAt = AtomicLong(NOT_YET)

    /** Body bytes the transport accepted so far. */
    public val bytesWritten: Long get() = written.get()

    /** True once the last body byte was written. */
    public val bodySent: Boolean get() = bodySentAt.get() != NOT_YET

    /** Milliseconds since the request started. */
    public fun elapsedMs(): Long = (nanoTime() - startedAt) / NANOS_PER_MS

    // The engine may write a body more than once (a redirect); each write starts over.
    internal fun bodyStarted() {
        val now = nanoTime()
        written.set(0)
        bodySentAt.set(NOT_YET)
        lastProgressAt.set(now)
        bodyStartedAt.set(now)
    }

    internal fun wrote(bytes: Int) {
        written.addAndGet(bytes.toLong())
        lastProgressAt.set(nanoTime())
    }

    internal fun bodyComplete() {
        bodySentAt.set(nanoTime())
    }

    /**
     * Average body rate in bytes per second, from the first body byte to the last one written; 0 before the body
     * started.
     */
    public fun averageBytesPerSecond(): Long {
        val start = bodyStartedAt.get()
        if (start == NOT_YET) return 0
        val nanos = lastProgressAt.get() - start
        if (nanos <= 0) return 0
        return (written.get().toDouble() * 1_000_000_000.0 / nanos).toLong()
    }

    /** "X of Y bytes sent in N s (average R)", the numbers every failure text carries. */
    public fun describe(): String =
        "${formatBytes(bytesWritten)} of ${formatBytes(totalBytes)} bytes sent in ${formatSeconds(elapsedMs())} " +
            "(average ${formatRate(averageBytesPerSecond())})"

    /** The verdict of the watchdog at this moment: null while the upload is fine. */
    internal fun check(
        idleWindowMs: Long,
        responseWaitMs: Long,
        what: String,
    ): UploadWatchdogException? {
        val now = nanoTime()
        val sent = bodySentAt.get()
        if (sent != NOT_YET) {
            val waitedMs = (now - sent) / NANOS_PER_MS
            if (waitedMs < responseWaitMs) return null
            return UploadWatchdogException(
                UploadWatchdogException.Reason.NO_RESPONSE,
                "$what: the server did not answer within ${formatSeconds(responseWaitMs)} after the body was sent; " +
                    describe(),
            )
        }
        val idleMs = (now - lastProgressAt.get()) / NANOS_PER_MS
        if (idleMs < idleWindowMs) return null
        return UploadWatchdogException(
            UploadWatchdogException.Reason.STALLED,
            "$what stalled: no upload progress for ${formatSeconds(idleMs)}; cut after ${describe()}",
        )
    }

    public companion object {
        private const val NOT_YET = Long.MIN_VALUE
        private const val NANOS_PER_MS = 1_000_000L

        /** "1,361,366,128" — grouped so a size reads at a glance. */
        public fun formatBytes(bytes: Long): String = String.format(Locale.ROOT, "%,d", bytes)

        /** "650 s", or "0.4 s" below ten seconds. */
        public fun formatSeconds(ms: Long): String =
            if (ms < 10_000) String.format(Locale.ROOT, "%.1f s", ms / 1000.0) else "${ms / 1000} s"

        /** "1.98 MB/s", "512 KB/s" or "300 B/s" (decimal units, the ones a network monitor shows). */
        public fun formatRate(bytesPerSecond: Long): String =
            when {
                bytesPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.2f MB/s", bytesPerSecond / 1_000_000.0)
                bytesPerSecond >= 1_000 -> String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1_000.0)
                else -> "$bytesPerSecond B/s"
            }
    }
}

/**
 * The write-progress watchdog cut an upload (#571). An [IOException], so retry ladders that retry a broken connection
 * retry this too; the message is plain text for the user and says which of the two limits was hit.
 */
public class UploadWatchdogException(
    public val reason: Reason,
    message: String,
) : IOException(message) {
    public enum class Reason {
        /** No body byte was written for the idle window. */
        STALLED,

        /** The whole body was written and the server did not answer within the response wait. */
        NO_RESPONSE,
    }
}

/**
 * The limits of [withUploadWatchdog], a parameter of the services that use it so tests can run on a scale of
 * milliseconds. Production keeps [DEFAULT].
 */
public data class UploadWatchdogLimits(
    /** See [UploadTimeoutPolicy.UPLOAD_IDLE_WINDOW_MS]. */
    val idleWindowMs: Long = UploadTimeoutPolicy.UPLOAD_IDLE_WINDOW_MS,
    /** See [UploadTimeoutPolicy.RESPONSE_WAIT_FLOOR_MS]. */
    val responseWaitFloorMs: Long = UploadTimeoutPolicy.RESPONSE_WAIT_FLOOR_MS,
    /** How often the watchdog looks; the precision of both limits. */
    val tickMs: Long = 1_000,
) {
    public companion object {
        public val DEFAULT: UploadWatchdogLimits = UploadWatchdogLimits()
    }
}

/**
 * Runs [block] (the request whose body records into [progress]) and cuts it when the body made no progress for
 * [idleWindowMs], or when the server did not answer within [responseWaitMs] after the last body byte. A cut throws
 * [UploadWatchdogException]; [what] names the upload in its text ("Shard upload"). Cancelling the caller cancels the
 * request and the watchdog and throws the caller's CancellationException, never a watchdog verdict.
 */
public suspend fun <T> withUploadWatchdog(
    progress: UploadProgress,
    idleWindowMs: Long,
    responseWaitMs: Long,
    tickMs: Long = UploadWatchdogLimits.DEFAULT.tickMs,
    what: String = "Upload",
    block: suspend () -> T,
): T =
    coroutineScope {
        val verdict = AtomicReference<UploadWatchdogException?>(null)
        val work = async { block() }
        // On Dispatchers.Default: the ticks are wall-clock time even when the caller runs on a test dispatcher.
        val watchdog =
            launch(Dispatchers.Default) {
                while (true) {
                    delay(tickMs)
                    val cut = progress.check(idleWindowMs, responseWaitMs, what) ?: continue
                    verdict.set(cut)
                    work.cancel(CancellationException(cut.message, cut))
                    return@launch
                }
            }
        try {
            work.await()
        } catch (e: Throwable) {
            // The engine may turn our cancellation into an exception of its own; the verdict is what happened.
            val cut = verdict.get()
            if (cut != null) {
                if (e !is CancellationException) cut.addSuppressed(e)
                throw cut
            }
            throw e
        } finally {
            watchdog.cancel()
        }
    }

/** A request body of [data] that records its progress into [progress] (see [withUploadWatchdog]). */
public fun progressTrackingBody(
    data: ByteArray,
    progress: UploadProgress,
    contentType: ContentType = ContentType.Application.OctetStream,
): OutgoingContent = ProgressTrackingContent(data.size.toLong(), contentType, progress) { ByteArrayInputStream(data) }

/** A request body streamed from [localPath] ([fileSize] bytes) that records its progress into [progress]. */
public fun progressTrackingFileBody(
    localPath: Path,
    fileSize: Long,
    progress: UploadProgress,
    contentType: ContentType = ContentType.Application.OctetStream,
): OutgoingContent = ProgressTrackingContent(fileSize, contentType, progress) { Files.newInputStream(localPath) }

// The streamingFileBody loop (UD-342: 64 KiB chunks, backpressure through writeFully, the UD-287 close in finally), plus
// a progress record per chunk the channel accepted. writeFully suspends while the transport's buffers are full, so a
// chunk counts once it is in a bounded buffer on its way to the socket: the record stops moving when the network does.
// It moves in steps of up to the channel's 1 MiB buffer (see UploadTimeoutPolicy.UPLOAD_IDLE_WINDOW_MS).
private class ProgressTrackingContent(
    override val contentLength: Long,
    override val contentType: ContentType,
    private val progress: UploadProgress,
    private val open: () -> InputStream,
) : OutgoingContent.WriteChannelContent() {
    override suspend fun writeTo(channel: ByteWriteChannel) {
        progress.bodyStarted()
        var complete = false
        try {
            withContext(Dispatchers.IO) {
                open().use { input ->
                    val buf = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        channel.writeFully(buf, 0, n)
                        progress.wrote(n)
                    }
                }
            }
            channel.flushAndClose()
            complete = true
        } finally {
            // UD-287: a secondary close failure must not shadow the original cause.
            if (!complete) runCatching { channel.flushAndClose() }
        }
        progress.bodyComplete()
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
