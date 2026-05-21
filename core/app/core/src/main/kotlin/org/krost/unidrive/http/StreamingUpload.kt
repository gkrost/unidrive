package org.krost.unidrive.http

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * UD-342 (UD-287 lineage): shared streaming-upload body for Ktor PUTs.
 *
 * **Why a shared helper.** Pre-UD-342, four providers each constructed
 * the same anonymous `OutgoingContent.WriteChannelContent` block —
 * `Files.newInputStream + 64 KiB ring buffer + writeFully` — to stream
 * a file body without buffering the whole thing into memory. Three of
 * the four (HiDrive, Internxt, S3) **lacked the UD-287 finally-flushAndClose
 * guard** that WebDAV had — a silent bug class: when the IO loop throws
 * (cancellation, file-read failure, mid-write OOM), the
 * `OutgoingContent.WriteChannelContent` exits without closing the
 * channel; the PUT never gets its terminating chunk frame; the server
 * drops the connection; Apache5 returns the broken socket to the pool;
 * the next request inherits a WSAECONNABORTED cascade.
 *
 * Lifting the body construction here gives every adopter the UD-287
 * fix automatically. Future providers that need streaming uploads
 * call this helper instead of pasting the block.
 *
 * **The flushAndClose contract.** The `runCatching { channel.flushAndClose() }`
 * lives in a `finally` block specifically so that a secondary close-
 * failure (e.g. the channel was already broken when we tried to write)
 * doesn't shadow the original IO-loop cause. The original exception
 * propagates; the close failure is silently swallowed (it would
 * otherwise mask the real problem from the caller's catch).
 *
 * **Backpressure.** Ktor's `ByteWriteChannel.writeFully(buf, 0, n)`
 * suspends when the underlying transport is slow — the producer
 * (this loop) blocks until bytes are flushed to the network. So the
 * 64 KiB ring buffer is the maximum in-flight memory regardless of
 * file size; the policy doesn't need a separate flow-control layer.
 *
 * Use:
 * ```kotlin
 * httpClient.put(url) {
 *     timeout {
 *         requestTimeoutMillis = UploadTimeoutPolicy.computeRequestTimeoutMs(fileSize)
 *     }
 *     setBody(streamingFileBody(localPath, fileSize))
 * }
 * ```
 */
public fun streamingFileBody(
    localPath: Path,
    fileSize: Long,
    contentType: ContentType = ContentType.Application.OctetStream,
): OutgoingContent =
    object : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = fileSize
        override val contentType: ContentType = contentType

        override suspend fun writeTo(channel: ByteWriteChannel) {
            try {
                withContext(Dispatchers.IO) {
                    Files.newInputStream(localPath).use { input ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            channel.writeFully(buf, 0, n)
                        }
                    }
                }
            } finally {
                // UD-287: secondary close-failure must not shadow the
                // original IO-loop cause. runCatching swallows close
                // errors so the caller's catch sees the real problem.
                runCatching { channel.flushAndClose() }
            }
        }
    }
