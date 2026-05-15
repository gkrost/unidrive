package org.krost.unidrive.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UD: graceful shutdown ordering for LocalWatcher.stop().
 *
 * The previous stop() called shutdownNow() on the coalescer, interrupting
 * in-flight debounce schedules. New ordering: shutdown() + awaitTermination,
 * then shutdownNow() as fallback. Plus try/catch around watchService.close().
 *
 * Test: open and close 100 watchers in a loop. On Linux, additionally check
 * that /proc/self/fd doesn't grow without bound across the loop.
 *
 * Note: spec called for JUnit 5 (@TempDir), but this module is configured for
 * JUnit 4 (`useJUnit()` in build.gradle.kts). Uses Files.createTempDirectory()
 * with explicit teardown, matching the convention used by sibling tests
 * (LocalWatcherTest, LocalScannerTest, etc.).
 */
class LocalWatcherShutdownTest {

    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-watcher-shutdown-test")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `100 sequential start-stop cycles do not throw`() {
        repeat(100) {
            val w = LocalWatcher(tmp)
            w.start()
            w.stop()
        }
    }

    @Test
    fun `100 sequential start-stop cycles do not leak file descriptors on Linux`() {
        val isLinux = System.getProperty("os.name").lowercase().contains("linux")
        if (!isLinux) {
            return // FD-leak check is Linux-only
        }

        val fdDir = File("/proc/self/fd")
        val before = fdDir.listFiles()?.size ?: error("Cannot read /proc/self/fd")

        repeat(100) {
            val w = LocalWatcher(tmp)
            w.start()
            w.stop()
        }

        val after = fdDir.listFiles()?.size ?: error("Cannot read /proc/self/fd")
        assertTrue(
            after - before <= 16,
            "FD count grew from $before to $after across 100 cycles (delta ${after - before}); expected <= 16",
        )
    }
}
