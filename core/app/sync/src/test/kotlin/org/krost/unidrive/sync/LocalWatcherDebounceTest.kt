package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.*

class LocalWatcherDebounceTest {
    private lateinit var syncRoot: Path
    private lateinit var watcher: LocalWatcher

    private val debounceMs = 100L
    private val postWindowSlackMs = 200L     // wait long enough that scheduled emits fire
    private val withinWindowMs = 30L         // safely inside the 100ms window

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-watch-debounce-test")
        watcher = LocalWatcher(syncRoot, debounceMs = debounceMs)
        watcher.start()
        // Give WatchService a moment to register before producing events.
        Thread.sleep(100)
    }

    @AfterTest
    fun tearDown() {
        watcher.stop()
    }

    private fun matches(changes: List<String>, name: String) = changes.count { it.endsWith("/$name") }

    @Test
    fun `vim atomic save emits exactly one path after debounce window`() {
        val target = syncRoot.resolve("doc.txt")
        Files.writeString(target, "v1")
        Thread.sleep(postWindowSlackMs)
        watcher.drainChanges() // clear initial create

        // Atomic save: write tmp, ATOMIC_MOVE onto target.
        val tmp = syncRoot.resolve(".doc.txt.swp")
        Files.writeString(tmp, "v2")
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        // Within window: no emit yet for the target.
        Thread.sleep(withinWindowMs)
        val mid = watcher.drainChanges()
        // Allow tmp create/delete to surface but doc.txt must not have leaked yet.
        assertEquals(0, matches(mid, "doc.txt"), "should not emit doc.txt before window closes; saw $mid")

        // After window: exactly one emit for doc.txt, file exists with new contents.
        Thread.sleep(postWindowSlackMs)
        val after = watcher.drainChanges()
        assertEquals(1, matches(after, "doc.txt"), "expected one doc.txt emit after window; saw $after")
        assertTrue(Files.exists(target))
        assertEquals("v2", Files.readString(target))
    }

    @Test
    fun `delete-only emits path once after debounce window`() {
        val target = syncRoot.resolve("gone.txt")
        Files.writeString(target, "x")
        Thread.sleep(postWindowSlackMs)
        watcher.drainChanges()

        Files.delete(target)

        // Within window: no emit yet.
        Thread.sleep(withinWindowMs)
        assertEquals(0, matches(watcher.drainChanges(), "gone.txt"))

        // After window: exactly one emit, file no longer exists.
        Thread.sleep(postWindowSlackMs)
        val after = watcher.drainChanges()
        assertEquals(1, matches(after, "gone.txt"))
        assertFalse(Files.exists(target))
    }

    @Test
    fun `delete-then-create within window emits one path and file exists`() {
        val target = syncRoot.resolve("flicker.txt")
        Files.writeString(target, "old")
        Thread.sleep(postWindowSlackMs)
        watcher.drainChanges()

        Files.delete(target)
        Thread.sleep(withinWindowMs) // still inside the 100ms window
        Files.writeString(target, "new")

        Thread.sleep(postWindowSlackMs)
        val after = watcher.drainChanges()
        assertEquals(1, matches(after, "flicker.txt"), "expected single emit, saw $after")
        assertTrue(Files.exists(target))
        assertEquals("new", Files.readString(target))
    }

    @Test
    fun `rapid 5x modify same path coalesces to one path emit`() {
        val target = syncRoot.resolve("hot.txt")
        Files.writeString(target, "0")
        Thread.sleep(postWindowSlackMs)
        watcher.drainChanges()

        repeat(5) { i ->
            Files.writeString(target, "v$i")
            Thread.sleep(10)
        }
        Thread.sleep(postWindowSlackMs)
        val after = watcher.drainChanges()
        assertEquals(1, matches(after, "hot.txt"), "expected one hot.txt emit, saw $after")
    }

    @Test
    fun `distinct paths do not interfere`() {
        val a = syncRoot.resolve("a.txt")
        val b = syncRoot.resolve("b.txt")
        Files.writeString(a, "a")
        Files.writeString(b, "b")
        Thread.sleep(postWindowSlackMs)
        watcher.drainChanges()

        // Delete A, modify B simultaneously.
        Files.delete(a)
        Files.writeString(b, "b2")

        Thread.sleep(postWindowSlackMs)
        val after = watcher.drainChanges()
        assertEquals(1, matches(after, "a.txt"), "expected one a.txt emit, saw $after")
        assertTrue(after.any { it.endsWith("/b.txt") }, "expected b.txt emit, saw $after")
        assertFalse(Files.exists(a))
        assertTrue(Files.exists(b))
    }

    @Test
    fun `events outside window emit separately across drains`() {
        val target = syncRoot.resolve("two.txt")

        // First emit (CREATE -- immediate, no debounce hold).
        Files.writeString(target, "first")
        Thread.sleep(postWindowSlackMs)
        val drain1 = watcher.drainChanges()
        assertEquals(1, matches(drain1, "two.txt"), "first drain should hold the create; saw $drain1")

        // Wait well past the window so the engine "moves on".
        Thread.sleep(debounceMs * 3)

        // Second emit, distinct in time.
        Files.writeString(target, "second")
        Thread.sleep(postWindowSlackMs)
        val drain2 = watcher.drainChanges()
        assertEquals(1, matches(drain2, "two.txt"), "second drain should hold the modify; saw $drain2")
    }
}
