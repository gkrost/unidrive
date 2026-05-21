package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.io.path.*
import kotlin.test.*

class TrashManagerTest {
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("trash-manager-test")
    }

    @AfterTest
    fun tearDown() {
        if (::syncRoot.isInitialized && syncRoot.exists()) {
            syncRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `trash moves file to trash directory`() {
        val file = syncRoot.resolve("docs/test.txt")
        file.parent.createDirectories()
        file.writeText("hello")

        val manager = TrashManager(syncRoot)
        val dest = manager.trash("docs/test.txt")

        assertFalse(file.exists(), "Original file should be gone")
        assertTrue(dest.exists(), "Trashed file should exist")
        assertEquals("hello", dest.readText())
        assertTrue(dest.toString().contains(".unidrive-trash"))
    }

    @Test
    fun `trash handles leading slash in path`() {
        val file = syncRoot.resolve("test.txt")
        file.writeText("content")

        val manager = TrashManager(syncRoot)
        manager.trash("/test.txt")

        assertFalse(file.exists())
    }

    @Test
    fun `list returns trashed items`() {
        val file = syncRoot.resolve("report.txt")
        file.writeText("data")

        val manager = TrashManager(syncRoot)
        manager.trash("report.txt")

        val items = manager.list()
        assertEquals(1, items.size)
        assertEquals("report.txt", items[0].originalPath)
        assertEquals(4L, items[0].sizeBytes)
    }

    @Test
    fun `list returns empty when no trash`() {
        val manager = TrashManager(syncRoot)
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `restore moves file back`() {
        val file = syncRoot.resolve("restore-me.txt")
        file.writeText("precious")

        val manager = TrashManager(syncRoot)
        manager.trash("restore-me.txt")
        assertFalse(file.exists())

        val restored = manager.restore("restore-me.txt")
        assertTrue(restored)
        assertTrue(file.exists())
        assertEquals("precious", file.readText())
    }

    @Test
    fun `restore returns false for missing item`() {
        val manager = TrashManager(syncRoot)
        assertFalse(manager.restore("nonexistent.txt"))
    }

    @Test
    fun `restore picks latest version when multiple exist`() {
        val manager = TrashManager(syncRoot)

        val file = syncRoot.resolve("multi.txt")
        file.writeText("v1")
        manager.trash("multi.txt")

        Thread.sleep(1100)

        file.writeText("v2")
        manager.trash("multi.txt")

        manager.restore("multi.txt")
        assertEquals("v2", file.readText())
    }

    @Test
    fun `purge removes old entries`() {
        val manager = TrashManager(syncRoot)
        val trashDir = syncRoot.resolve(".unidrive-trash")

        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val oldTs = fmt.format(Instant.now().minus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS))
        val oldDir = trashDir.resolve(oldTs)
        oldDir.resolve("old-file.txt").apply {
            parent.createDirectories()
            writeText("old")
        }

        assertEquals(1, manager.list().size)
        manager.purge(30)
        assertEquals(0, manager.list().size)
    }

    @Test
    fun `purge keeps recent entries`() {
        val file = syncRoot.resolve("recent.txt")
        file.writeText("fresh")

        val manager = TrashManager(syncRoot)
        manager.trash("recent.txt")

        assertEquals(1, manager.list().size)
        manager.purge(30)
        assertEquals(1, manager.list().size)
    }

    @Test
    fun `purgeAll removes everything`() {
        val manager = TrashManager(syncRoot)

        syncRoot.resolve("a.txt").writeText("a")
        syncRoot.resolve("b.txt").writeText("b")
        manager.trash("a.txt")
        manager.trash("b.txt")

        assertEquals(2, manager.list().size)
        manager.purgeAll()
        assertEquals(0, manager.list().size)
        assertFalse(syncRoot.resolve(".unidrive-trash").exists())
    }

    @Test
    fun `trash handles non-existent file gracefully`() {
        val manager = TrashManager(syncRoot)
        val dest = manager.trash("missing.txt")
        assertFalse(dest.exists())
    }

    @Test
    fun `trash preserves subdirectory structure`() {
        val file = syncRoot.resolve("deep/nested/dir/file.txt")
        file.parent.createDirectories()
        file.writeText("nested")

        val manager = TrashManager(syncRoot)
        manager.trash("deep/nested/dir/file.txt")

        val items = manager.list()
        assertEquals(1, items.size)
        // Normalize platform separators before substring match (\ on Windows, / elsewhere).
        assertTrue(items[0].originalPath.replace('\\', '/').contains("deep/nested/dir/file.txt"))
    }
}
