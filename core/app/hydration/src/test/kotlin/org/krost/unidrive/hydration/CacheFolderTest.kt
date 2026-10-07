package org.krost.unidrive.hydration

import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheFolderTest {
    private lateinit var base: Path
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-cache-folder")
        dir = Files.createDirectories(base.resolve("cache").resolve("profile"))
    }

    @AfterTest
    fun tearDown() {
        base.toFile().deleteRecursively()
    }

    private fun write(file: Path): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, "x")
        return file
    }

    @Test
    fun `files inside the folder count, existing or not, in any spelling`() {
        val file = write(dir.resolve("a").resolve("b.txt"))

        assertTrue(isInsideCacheFolder(dir, file))
        assertTrue(isInsideCacheFolder(dir, dir.resolve("a").resolve(".").resolve("b.txt")))
        assertTrue(isInsideCacheFolder(dir, dir.resolve("x").resolve("..").resolve("a").resolve("b.txt")))
        assertTrue(isInsideCacheFolder(dir, dir.resolve("new").resolve("c.txt")), "a file that does not exist yet")
    }

    @Test
    fun `the folder itself, a sibling and anything outside do not count`() {
        val sibling = write(dir.resolveSibling("profile-other").resolve("b.txt"))
        val elsewhere = write(base.resolve("elsewhere.txt"))

        assertFalse(isInsideCacheFolder(dir, dir), "the folder itself")
        assertFalse(isInsideCacheFolder(dir, sibling), "a folder whose name starts like the cache folder")
        assertFalse(isInsideCacheFolder(dir, elsewhere))
        assertFalse(isInsideCacheFolder(dir, dir.resolve("..").resolve("profile-other").resolve("b.txt")))
        assertFalse(isInsideCacheFolder(dir, dir.resolve("..").resolve("..").resolve("elsewhere.txt")))
    }

    @Test
    fun `a folder that does not exist yet is compared on the spelling alone`() {
        val missing = base.resolve("not-yet")

        assertTrue(isInsideCacheFolder(missing, missing.resolve("a.txt")))
        assertFalse(isInsideCacheFolder(missing, missing.resolve("..").resolve("a.txt")))
        assertFalse(isInsideCacheFolder(missing, base.resolve("elsewhere.txt")))
    }

    @Test
    fun `links that lead out of the folder do not count`() {
        val elsewhere = write(base.resolve("elsewhere.txt"))
        val outDir = Files.createDirectories(base.resolve("outdir"))
        write(outDir.resolve("b.txt"))
        val fileLink = dir.resolve("link.txt")
        val dirLink = dir.resolve("linked")
        try {
            Files.createSymbolicLink(fileLink, elsewhere)
            Files.createSymbolicLink(dirLink, outDir)
        } catch (e: Exception) {
            Assume.assumeTrue("symbolic links are not available here: ${e.message}", false)
        }

        assertFalse(isInsideCacheFolder(dir, fileLink), "a file link")
        assertFalse(isInsideCacheFolder(dir, dirLink.resolve("b.txt")), "a file below a folder link")
        assertFalse(isInsideCacheFolder(dir, dirLink.resolve("new.txt")), "a new file below a folder link")
        // Windows normalizes dot-dot before following a link; Unix follows the link first.
        if (java.io.File.separatorChar != '\\') {
            assertFalse(isInsideCacheFolder(dir, dirLink.resolve("..").resolve("elsewhere.txt")), "dot-dot after a link resolves outside")
            assertFalse(isInsideCacheFolder(dir, dirLink.resolve("..").resolve("missing.txt")), "a new file after dot-dot outside")
        }
    }

    @Test
    fun `a link that stays inside the folder counts`() {
        val target = write(dir.resolve("real.txt"))
        val link = dir.resolve("alias.txt")
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: Exception) {
            Assume.assumeTrue("symbolic links are not available here: ${e.message}", false)
        }

        assertTrue(isInsideCacheFolder(dir, link))
    }

    @Test
    fun `a path for a log line carries no control characters and stays short`() {
        val shown = forLogLine("/a" + Char(10) + "b" + Char(0) + "c")
        assertFalse(shown.any { it.code < 0x20 }, shown)
        assertTrue(shown.startsWith("/a"), shown)
        assertTrue(forLogLine("/" + "n".repeat(1000)).length < 300)
    }
}
