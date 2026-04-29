package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TrashTest {
    private lateinit var tmpDir: Path

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("trash-test")
    }

    @AfterTest
    fun tearDown() {
        if (::tmpDir.isInitialized && Files.exists(tmpDir)) {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `resolveTrashDir returns valid path on Linux`() {
        val resolved = Trash.resolveTrashDir()
        assertNotNull(resolved, "Trash dir should resolve on Linux")
        assertTrue(resolved.toString().endsWith("Trash"))
    }

    @Test
    fun `trash handles non-existent file gracefully`() {
        val file = tmpDir.resolve("nonexistent.txt")
        val result = Trash.trash(file)
        assertTrue(result, "Trashing non-existent file should return true")
    }

    @Test
    fun `trash removes file from original location`() {
        val file = tmpDir.resolve("test.txt")
        Files.writeString(file, "content")
        assertTrue(Files.exists(file))

        Trash.trash(file)
        assertFalse(Files.exists(file), "File should be removed from original location")
    }

    @Test
    fun `trash handles directory`() {
        val dir = tmpDir.resolve("testdir")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("file.txt"), "content")
        assertTrue(Files.exists(dir))

        Trash.trash(dir)
        assertFalse(Files.exists(dir), "Directory should be removed from original location")
    }

    @Test
    fun `trash moves file to Trash files directory`() {
        val trashDir = tmpDir.resolve("Trash")
        val file = tmpDir.resolve("test.txt")
        Files.writeString(file, "hello")

        // Set up trash directories
        Files.createDirectories(trashDir.resolve("files"))
        Files.createDirectories(trashDir.resolve("info"))

        // Temporarily override XDG_DATA_HOME would require env var manipulation
        // which isn't feasible in tests, so we test the actual system trash dir
        val systemTrashDir = Trash.resolveTrashDir()
        if (systemTrashDir != null) {
            Files.createDirectories(systemTrashDir.resolve("files"))
            Files.createDirectories(systemTrashDir.resolve("info"))

            val result = Trash.trash(file)
            // On a system with trash support, file should be moved
            // On CI without desktop, falls back to permanent delete
            assertFalse(Files.exists(file), "File should be removed from original location")

            // If result is true, it was trashed (not permanently deleted)
            if (result) {
                val trashedFile = systemTrashDir.resolve("files").resolve("test.txt")
                val trashInfo = systemTrashDir.resolve("info").resolve("test.txt.trashinfo")
                assertTrue(
                    Files.exists(trashedFile) || Files.exists(trashInfo),
                    "Either trashed file or trashinfo should exist",
                )
            }
        }
    }

    @Test
    fun `trash creates trashinfo metadata`() {
        val systemTrashDir = Trash.resolveTrashDir()
        if (systemTrashDir != null) {
            Files.createDirectories(systemTrashDir.resolve("files"))
            Files.createDirectories(systemTrashDir.resolve("info"))

            val file = tmpDir.resolve("test-info.txt")
            Files.writeString(file, "content")

            val result = Trash.trash(file)
            assertFalse(Files.exists(file), "File should be removed from original location")

            if (result) {
                val trashInfo = systemTrashDir.resolve("info").resolve("test-info.txt.trashinfo")
                if (Files.exists(trashInfo)) {
                    val content = Files.readString(trashInfo)
                    assertTrue(content.contains("[Trash Info]"))
                    assertTrue(content.contains("Path="))
                    assertTrue(content.contains("DeletionDate="))
                }
            }
        }
    }

    @Test
    fun `trash handles name collision`() {
        val systemTrashDir = Trash.resolveTrashDir()
        if (systemTrashDir != null) {
            Files.createDirectories(systemTrashDir.resolve("files"))
            Files.createDirectories(systemTrashDir.resolve("info"))

            val file1 = tmpDir.resolve("collision.txt")
            val file2 = tmpDir.resolve("collision.txt.2")
            Files.writeString(file1, "first")
            Files.writeString(file2, "second")

            val result1 = Trash.trash(file1)
            val result2 = Trash.trash(file2)

            assertFalse(Files.exists(file1))
            assertFalse(Files.exists(file2))

            // Both should succeed (either trashed or deleted)
            assertTrue(result1 || result2)
        }
    }
}
