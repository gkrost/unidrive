package org.krost.unidrive.http

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UD-342: pin the contract of the shared streaming-upload body. The
 * load-bearing detail is the UD-287 finally-flushAndClose — verify
 * the channel is closed even when the IO loop throws.
 */
class StreamingUploadTest {
    @Test
    fun `streamingFileBody contentLength matches fileSize argument`() =
        runTest {
            val tmp = createTempFile(content = ByteArray(1024) { it.toByte() })
            try {
                val body = streamingFileBody(tmp, 1024L)
                assertEquals(1024L, body.contentLength)
                assertEquals(ContentType.Application.OctetStream, body.contentType)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

    @Test
    fun `streamingFileBody overrideable contentType`() =
        runTest {
            val tmp = createTempFile(content = ByteArray(0))
            try {
                val body = streamingFileBody(tmp, 0L, ContentType.Application.Json)
                assertEquals(ContentType.Application.Json, body.contentType)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

    @Test
    fun `streamingFileBody flushes and closes the channel after a clean write`() =
        runTest {
            val payload = ByteArray(1024) { it.toByte() }
            val tmp = createTempFile(payload)
            try {
                val body = streamingFileBody(tmp, payload.size.toLong()) as OutgoingContent.WriteChannelContent
                val channel = ByteChannel(autoFlush = true)
                coroutineScope {
                    val drain =
                        async {
                            channel.readByteArray(payload.size)
                        }
                    body.writeTo(channel)
                    drain.await()
                }
                // After writeTo returns, the channel must be closed.
                assertTrue(channel.isClosedForWrite, "channel should be closed for write after a clean upload")
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

    @Test
    fun `streamingFileBody closes channel even when source file is missing`() =
        runTest {
            // UD-287: the close-in-finally guard. Even when the IO loop
            // throws (file vanished mid-upload, etc.), the channel must
            // be closed so the connection pool can recover. Pre-fix,
            // a mid-write throw skipped flushAndClose and the PUT never
            // got its terminating chunk frame.
            val nonexistent = Path.of(System.getProperty("java.io.tmpdir"), "ud342-no-such-file-${System.nanoTime()}")
            val body = streamingFileBody(nonexistent, 1024L) as OutgoingContent.WriteChannelContent
            val channel = ByteChannel(autoFlush = true)
            try {
                body.writeTo(channel)
                fail("expected IOException for missing file")
            } catch (_: java.nio.file.NoSuchFileException) {
                // Expected — but the channel must still be closed.
            } catch (_: IOException) {
                // Expected — but the channel must still be closed.
            }
            assertTrue(
                channel.isClosedForWrite,
                "UD-287: channel must be closed-for-write even after IO loop threw — pre-UD-342 inline bodies leaked here",
            )
        }

    private fun createTempFile(content: ByteArray): Path {
        val p = Files.createTempFile("ud342-upload", ".bin")
        Files.write(p, content)
        return p
    }
}
