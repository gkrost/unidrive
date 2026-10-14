package org.krost.unidrive.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import com.sun.nio.file.ExtendedOpenOption
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #802: a file another process holds open retried a whole poll cycle before. The helper retries a
 * sharing violation / access denial a few times with short delays, rethrows anything else at once,
 * and names the lock when it gives up. The delays are injected: no test sleeps unless it means to.
 */
class LocalIoRetryTest {
    private class LockFailure(file: Path, reason: String?) : FileSystemException(file.toString(), null, reason)

    // The lock gate only retries a file that is still there, so the fixtures write one first.
    private fun existingFile(): Path = Files.createTempFile("local-io-retry", ".bin")

    @Test
    fun `a sharing violation is retried and a later attempt succeeds`() =
        runBlocking {
            val delays = mutableListOf<Long>()
            val attempts = AtomicInteger(0)
            val file = existingFile()
            val result =
                withLocalIoRetry(delayMs = { delays += it }) {
                    if (attempts.incrementAndGet() < 3) throw LockFailure(file, "being used by another process")
                    "done"
                }
            assertEquals("done", result)
            assertEquals(listOf(100L, 300L), delays, "the third attempt carries the two earlier delays")
        }

    @Test
    fun `the attempts run out and the failure names the lock`() =
        runBlocking {
            val delays = mutableListOf<Long>()
            val file = existingFile()
            val failure =
                assertFailsWith<FileSystemException> {
                    withLocalIoRetry(delayMs = { delays += it }) {
                        throw LockFailure(file, "being used by another process")
                    }
                }
            assertEquals(listOf(100L, 300L, 900L), delays, "three retries after the first failure")
            assertTrue("locked by another process" in (failure.reason ?: ""), "the message names the lock: ${failure.reason}")
        }

    @Test
    fun `anything else is rethrown at once`() =
        runBlocking {
            val delays = mutableListOf<Long>()
            val failure =
                assertFailsWith<FileSystemException> {
                    withLocalIoRetry(delayMs = { delays += it }) {
                        throw LockFailure(Path.of("gone.bin"), "being used by another process")
                    }
                }
            assertEquals(0, delays.size, "a file that is gone is not locked: no retry")
            assertEquals("being used by another process", failure.reason, "today's failure, unchanged")
        }

    @Test
    fun `a non-lock failure is rethrown without a retry`() =
        runBlocking {
            val delays = mutableListOf<Long>()
            val failure =
                assertFailsWith<IllegalStateException> {
                    withLocalIoRetry(delayMs = { delays += it }) { error("different failure") }
                }
            assertEquals("different failure", failure.message)
            assertEquals(0, delays.size, "no sleep, no retry")
        }

    @Test
    fun `no sleep on a first-attempt success`() =
        runBlocking {
            val delays = mutableListOf<Long>()
            assertEquals(1, withLocalIoRetry(delayMs = { delays += it }) { 1 })
            assertEquals(0, delays.size)
        }

    @Test
    fun `an access denial on an existing file is retried`() =
        runBlocking {
            val delays = mutableListOf<Long>()
            val attempts = AtomicInteger(0)
            val file = existingFile()
            withLocalIoRetry(delayMs = { delays += it }) {
                if (attempts.incrementAndGet() == 1) throw AccessDeniedException(file.toString())
                1
            }
            assertEquals(listOf(100L), delays)
        }

    // The real shape on Windows: a handle held open with no sharing, released after ~300 ms, and the
    // engine's read succeeds inside the retry window. Windows only — other platforms have no sharing.
    @Test
    fun `an exclusively held file is read once the other process lets go`() =
        runBlocking {
            assumeWindows()
            val file = Files.createTempFile("local-io-retry", ".bin")
            file.writeText("bytes")
            val channel =
                FileChannel.open(
                    file,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    ExtendedOpenOption.NOSHARE_READ,
                    ExtendedOpenOption.NOSHARE_WRITE,
                )
            val releaser =
                Thread {
                    Thread.sleep(300)
                    channel.close()
                }
            releaser.start()
            try {
                val started = System.nanoTime()
                val content = withLocalIoRetry { Files.readAllBytes(file) }
                assertTrue((System.nanoTime() - started) / 1_000_000 >= 100, "at least one retry waited")
                assertEquals("bytes", content.decodeToString())
            } finally {
                releaser.join(2_000)
                runCatching { channel.close() }
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun `a cancellation during the retry sleep propagates`() =
        runBlocking {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
            val file = existingFile()
            val deferred =
                scope.async {
                    withLocalIoRetry(delayMs = {
                        scope.cancel()
                        kotlinx.coroutines.delay(1) // a delay in a cancelled scope throws CancellationException
                    }) {
                        throw LockFailure(file, "being used by another process")
                    }
                }
            val result = runCatching { deferred.await() }
            assertTrue(result.exceptionOrNull() is CancellationException, "the cancellation came through the sleep: ${result.exceptionOrNull()}")
        }

    private fun assumeWindows() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").lowercase().contains("windows"))
    }
}
