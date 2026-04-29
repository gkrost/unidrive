package org.krost.unidrive.sync

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class LocalWatcherTest {
    private lateinit var syncRoot: Path
    private lateinit var watcher: LocalWatcher

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-watch-test")
        watcher = LocalWatcher(syncRoot)
    }

    @AfterTest
    fun tearDown() {
        watcher.stop()
    }

    @Test
    fun `detects new file`() {
        watcher.start()
        Files.writeString(syncRoot.resolve("new.txt"), "hello")
        Thread.sleep(500)
        val changes = watcher.drainChanges()
        assertTrue(changes.any { it.contains("new.txt") })
    }

    @Test
    fun `suppresses echoed paths`() {
        watcher.start()
        watcher.suppress("/echoed.txt")
        Files.writeString(syncRoot.resolve("echoed.txt"), "sync write")
        Thread.sleep(500)
        val changes = watcher.drainChanges()
        assertFalse(changes.any { it.contains("echoed.txt") })
        watcher.unsuppress("/echoed.txt")
    }

    @Test
    fun `detects changes after unsuppress`() {
        watcher.start()
        watcher.suppress("/file.txt")
        Files.writeString(syncRoot.resolve("file.txt"), "first")
        Thread.sleep(300)
        watcher.unsuppress("/file.txt")
        watcher.drainChanges() // clear

        Files.writeString(syncRoot.resolve("file.txt"), "second")
        Thread.sleep(500)
        val changes = watcher.drainChanges()
        assertTrue(changes.any { it.contains("file.txt") })
    }

    @Test
    fun `drainChanges clears accumulated events`() {
        watcher.start()
        repeat(50) { i ->
            Files.writeString(syncRoot.resolve("file$i.txt"), "content$i")
        }
        Thread.sleep(1000)
        val first = watcher.drainChanges()
        assertTrue(first.isNotEmpty())

        val second = watcher.drainChanges()
        assertTrue(second.isEmpty())
    }

    @Test
    fun `repeated drain prevents unbounded growth`() {
        watcher.start()
        repeat(3) { round ->
            repeat(10) { i ->
                Files.writeString(syncRoot.resolve("r${round}_f$i.txt"), "data")
            }
            Thread.sleep(500)
            watcher.drainChanges()
        }
        // After draining, no accumulated events
        val remaining = watcher.drainChanges()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `awaitChange returns true on file change`() =
        runBlocking {
            watcher.start()
            Thread {
                Thread.sleep(200)
                Files.writeString(syncRoot.resolve("trigger.txt"), "change")
            }.start()
            val result = watcher.awaitChange(5.seconds)
            assertTrue(result, "awaitChange should return true when file created")
        }

    @Test
    fun `awaitChange returns false on timeout`() =
        runBlocking {
            watcher.start()
            val result = watcher.awaitChange(1.seconds)
            assertFalse(result, "awaitChange should return false when no changes within timeout")
        }
}
