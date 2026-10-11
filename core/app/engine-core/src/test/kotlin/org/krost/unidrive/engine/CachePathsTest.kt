package org.krost.unidrive.engine

import org.junit.Assume
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachePathsTest {
    private val log = LoggerFactory.getLogger(CachePathsTest::class.java)
    private lateinit var base: Path
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-cache-paths")
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

    // ---- resolveInside: a logical path below the cache folder ----

    @Test
    fun `a logical path resolves below the folder, normalised`() {
        assertEquals(dir.resolve("a").resolve("b.txt"), CachePaths.resolveInside(dir, "/a/b.txt"))
        assertEquals(dir.resolve("b.txt"), CachePaths.resolveInside(dir, "/a/../b.txt"))
        assertEquals(dir, CachePaths.resolveInside(dir, "/"))
    }

    @Test
    fun `a logical path that climbs out of the folder is refused`() {
        for (p in listOf("/..", "/../x", "/a/../../x", "../x")) {
            assertFailsWith<SecurityException>(p) { CachePaths.resolveInside(dir, p) }
        }
    }

    // ---- isInside: a cache path a client hands back ----

    @Test
    fun `files inside the folder count, existing or not, in any spelling`() {
        val file = write(dir.resolve("a").resolve("b.txt"))

        assertTrue(CachePaths.isInside(dir, file))
        assertTrue(CachePaths.isInside(dir, dir.resolve("a").resolve(".").resolve("b.txt")))
        assertTrue(CachePaths.isInside(dir, dir.resolve("x").resolve("..").resolve("a").resolve("b.txt")))
        assertTrue(CachePaths.isInside(dir, dir.resolve("new").resolve("c.txt")), "a file that does not exist yet")
    }

    @Test
    fun `the folder itself, a sibling and anything outside do not count`() {
        val sibling = write(dir.resolveSibling("profile-other").resolve("b.txt"))
        val elsewhere = write(base.resolve("elsewhere.txt"))

        assertFalse(CachePaths.isInside(dir, dir), "the folder itself")
        assertFalse(CachePaths.isInside(dir, sibling), "a folder whose name starts like the cache folder")
        assertFalse(CachePaths.isInside(dir, elsewhere))
        assertFalse(CachePaths.isInside(dir, dir.resolve("..").resolve("profile-other").resolve("b.txt")))
        assertFalse(CachePaths.isInside(dir, dir.resolve("..").resolve("..").resolve("elsewhere.txt")))
    }

    @Test
    fun `a folder that does not exist yet is compared on the spelling alone`() {
        val missing = base.resolve("not-yet")

        assertTrue(CachePaths.isInside(missing, missing.resolve("a.txt")))
        assertFalse(CachePaths.isInside(missing, missing.resolve("..").resolve("a.txt")))
        assertFalse(CachePaths.isInside(missing, base.resolve("elsewhere.txt")))
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

        assertFalse(CachePaths.isInside(dir, fileLink), "a file link")
        assertFalse(CachePaths.isInside(dir, dirLink.resolve("b.txt")), "a file below a folder link")
        assertFalse(CachePaths.isInside(dir, dirLink.resolve("new.txt")), "a new file below a folder link")
        // Windows normalizes dot-dot before following a link; Unix follows the link first.
        if (java.io.File.separatorChar != '\\') {
            assertFalse(CachePaths.isInside(dir, dirLink.resolve("..").resolve("elsewhere.txt")), "dot-dot after a link resolves outside")
            assertFalse(CachePaths.isInside(dir, dirLink.resolve("..").resolve("missing.txt")), "a new file after dot-dot outside")
        }
        for (logical in listOf("/link.txt", "/linked/b.txt", "/linked/new.txt")) {
            assertFailsWith<SecurityException>(logical) { CachePaths.resolveInside(dir, logical) }
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

        assertTrue(CachePaths.isInside(dir, link))
        assertEquals(link, CachePaths.resolveInside(dir, "/alias.txt"))
    }

    // ---- Pass: one containment memo for a pass over many rows (#729) ----

    @Test
    fun `a pass answers what isInside answers, for paths that exist and paths that do not`() {
        val file = write(dir.resolve("a").resolve("b.txt"))
        val sibling = write(dir.resolveSibling("profile-other").resolve("b.txt"))
        val elsewhere = write(base.resolve("elsewhere.txt"))
        val missing = base.resolve("not-yet")
        val pass = CachePaths.Pass(dir)
        val rootless = CachePaths.Pass(missing)

        val candidates =
            listOf(
                file,
                dir.resolve("a"),
                dir.resolve("a").resolve(".").resolve("b.txt"),
                dir.resolve("x").resolve("..").resolve("a").resolve("b.txt"),
                dir.resolve("new").resolve("c.txt"),
                dir,
                sibling,
                elsewhere,
                dir.resolve("..").resolve("profile-other").resolve("b.txt"),
                dir.resolve("..").resolve("..").resolve("elsewhere.txt"),
            )
        for (candidate in candidates) {
            assertEquals(CachePaths.isInside(dir, candidate), pass.isInside(candidate), "for $candidate")
        }
        assertTrue(rootless.isInside(missing.resolve("a.txt")))
        assertFalse(rootless.isInside(missing.resolve("..").resolve("a.txt")))
        assertFalse(rootless.isInside(base.resolve("elsewhere.txt")))
    }

    @Test
    fun `a pass answers from the probes it made, not from a tree that changed under it`() {
        val outDir = Files.createDirectories(base.resolve("outdir"))
        write(outDir.resolve("b.txt"))
        val link = dir.resolve("linked")
        val pass = CachePaths.Pass(dir)

        assertTrue(pass.isInside(link.resolve("new.txt")), "nothing is there yet: the spelling decides")
        try {
            Files.createSymbolicLink(link, outDir)
        } catch (e: Exception) {
            Assume.assumeTrue("symbolic links are not available here: ${e.message}", false)
        }

        assertFalse(CachePaths.isInside(dir, link.resolve("other.txt")), "a fresh answer follows the link out")
        assertTrue(pass.isInside(link.resolve("other.txt")), "the pass answers from the absent ancestor it probed")
    }

    // ---- forRow: passes over rows skip a path that does not resolve inside ----

    @Test
    fun `a row path that does not resolve inside the cache is skipped, not thrown`() {
        assertEquals(dir.resolve("a.txt"), CachePaths.forRow("/a.txt", log) { CachePaths.resolveInside(dir, it) })
        assertNull(CachePaths.forRow("/../a.txt", log) { CachePaths.resolveInside(dir, it) })
        assertNull(CachePaths.forRow("/bad", log) { throw InvalidPathException(it, "not a name here") })
    }

    // ---- forLog: what a log line shows of a client-supplied path ----

    @Test
    fun `a path for a log line carries no control characters and stays short`() {
        val shown = CachePaths.forLog("/a" + Char(10) + "b" + Char(0) + "c")
        assertFalse(shown.any { it.code < 0x20 }, shown)
        assertTrue(shown.startsWith("/a"), shown)

        val long = CachePaths.forLog("/" + "n".repeat(1000))
        assertTrue(long.length < 300, "length ${long.length}")
    }
}
