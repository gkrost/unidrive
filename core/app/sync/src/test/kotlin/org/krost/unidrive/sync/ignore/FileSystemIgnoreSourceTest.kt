package org.krost.unidrive.sync.ignore

import org.junit.Assume
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rule files on a real file system, in a temp directory. */
class FileSystemIgnoreSourceTest {
    private val root: Path = Files.createTempDirectory("ignore-fs-source")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun write(
        rel: String,
        text: String,
        modified: Long? = null,
    ) {
        val p = root.resolve(rel)
        p.parent.createDirectories()
        p.writeText(text)
        if (modified != null) Files.setLastModifiedTime(p, FileTime.fromMillis(modified))
    }

    @Test
    fun probesStampsAndReads() {
        write("a/.unidrive.ignore", "*.log\n")
        val src = FileSystemIgnoreSource(root)
        val probe = src.probe("a/.unidrive.ignore")
        assertIs<RuleFileProbe.Present>(probe)
        assertEquals(6L, probe.size)
        assertEquals("*.log\n", String(src.read("a/.unidrive.ignore", 1024)!!, Charsets.UTF_8))
        assertNull(src.read("a/.unidrive.ignore", 3), "over the cap")
        assertEquals(RuleFileProbe.Absent, src.probe("b/.unidrive.ignore"))
        assertEquals(RuleFileProbe.Absent, src.probe("a/.unidrive.ignore/.unidrive.ignore"), "below a file")
    }

    @Test
    fun aDirectoryWithTheRuleFileNameIsNoRuleFile() {
        root.resolve("a/.unidrive.ignore").createDirectories()
        assertEquals(RuleFileProbe.Absent, FileSystemIgnoreSource(root).probe("a/.unidrive.ignore"))
    }

    @Test
    fun aSymbolicLinkIsNotFollowed() {
        write("target.txt", "*\n")
        val link = root.resolve("a/.unidrive.ignore")
        link.parent.createDirectories()
        try {
            Files.createSymbolicLink(link, root.resolve("target.txt"))
        } catch (e: IOException) {
            Assume.assumeTrue("symbolic links need privileges here: ${e.message}", false)
        } catch (e: UnsupportedOperationException) {
            Assume.assumeTrue("no symbolic links here: ${e.message}", false)
        }
        assertIs<RuleFileProbe.NotFollowed>(FileSystemIgnoreSource(root).probe("a/.unidrive.ignore"))
    }

    @Test
    fun aRuleFileReachedThroughALinkedDirectoryOutsideTheRootIsNotFollowed() {
        val outside = Files.createTempDirectory("ignore-fs-outside")
        try {
            outside.resolve(".unidrive.ignore").writeText("*\n")
            try {
                Files.createSymbolicLink(root.resolve("link"), outside)
            } catch (e: IOException) {
                Assume.assumeTrue("symbolic links need privileges here: ${e.message}", false)
            } catch (e: UnsupportedOperationException) {
                Assume.assumeTrue("no symbolic links here: ${e.message}", false)
            }
            val probe = FileSystemIgnoreSource(root).probe("link/.unidrive.ignore")
            assertIs<RuleFileProbe.NotFollowed>(probe)
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun aTreeOnDiskSeesEditsAndRemovalsAtTheNextScan() {
        write(".unidrive.ignore", "build/\n", modified = 1_000_000)
        write("p/.unidrive.ignore", "*.tmp\n", modified = 1_000_000)
        val tree = IgnoreTree(FileSystemIgnoreSource(root))
        assertTrue(tree.isIgnored("p/build/out.jar"))
        assertTrue(tree.isIgnored("p/x.tmp"))
        assertTrue(tree.isIgnored("p/.git/HEAD"))

        write("p/.unidrive.ignore", "*.bak\n!.git\n", modified = 2_000_000)
        Files.delete(root.resolve(".unidrive.ignore"))
        tree.revalidate()
        assertFalse(tree.isIgnored("p/build/out.jar"), "the removed file no longer applies")
        assertTrue(tree.isIgnored("p/x.tmp"), "the content default is back for *.tmp")
        assertTrue(tree.isIgnored("p/x.bak"))
        assertFalse(tree.isIgnored("p/.git/HEAD"))
        assertEquals("p/.unidrive.ignore", tree.explain("p/x.bak").rule?.source)
    }
}
