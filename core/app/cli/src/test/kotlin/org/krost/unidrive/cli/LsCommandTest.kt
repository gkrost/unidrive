package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import org.krost.unidrive.localfs.LocalFsConfig
import org.krost.unidrive.localfs.LocalFsProvider
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UD-224: CLI `ls` subcommand.
 *
 * Formatting + sort invariants are covered by exercising the private
 * `printChildren` helper via reflection (sibling tests — SweepCommandTest,
 * RelocateCommandTest — follow the same pattern). The provider-integration
 * check uses [LocalFsProvider] against a temp dir so no OAuth is needed.
 * Registration + param defaults go through a fresh [CommandLine] instance,
 * matching StatusCommandTest / SyncCommandTest.
 */
class LsCommandTest {
    private lateinit var rootDir: Path
    private lateinit var tokenDir: Path
    private lateinit var provider: LocalFsProvider

    @BeforeTest
    fun setUp() {
        rootDir = Files.createTempDirectory("unidrive-ls-test")
        tokenDir = Files.createTempDirectory("unidrive-ls-token")
        provider = LocalFsProvider(LocalFsConfig(rootPath = rootDir, tokenPath = tokenDir))
    }

    @AfterTest
    fun tearDown() {
        provider.close()
        if (Files.exists(rootDir)) {
            Files.walk(rootDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        if (Files.exists(tokenDir)) {
            Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // -- Registration -----------------------------------------------------------

    @Test
    fun `ls command is registered`() {
        val cmd = CommandLine(Main())
        assertNotNull(cmd.subcommands["ls"], "ls subcommand should be registered")
    }

    @Test
    fun `ls command accepts an optional path parameter`() {
        val cmd = CommandLine(Main())
        val lsCmd = cmd.subcommands["ls"]!!
        val params = lsCmd.commandSpec.positionalParameters()
        assertEquals(1, params.size, "ls should have exactly one positional parameter")
        assertEquals("/", params[0].defaultValue(), "default path must be '/' so bare 'ls' works")
    }

    // -- Formatting + sort (against LocalFsProvider) ---------------------------

    @Test
    fun `output lists children — folders before files, alphabetical within group, no recursion`() {
        // Layout:
        //   /beta.txt
        //   /alpha.txt
        //   /zulu/            (folder)
        //   /alphadir/        (folder)
        //   /zulu/nested.txt  (should NOT appear — one level only)
        Files.writeString(rootDir.resolve("beta.txt"), "beta")
        Files.writeString(rootDir.resolve("alpha.txt"), "alpha")
        Files.createDirectories(rootDir.resolve("zulu"))
        Files.createDirectories(rootDir.resolve("alphadir"))
        Files.writeString(rootDir.resolve("zulu/nested.txt"), "nested")

        val children =
            runBlocking {
                provider.authenticate()
                provider.listChildren("/")
            }
        val output = invokePrintChildren(children)

        val lines = output.lines().filter { it.isNotBlank() }
        // 4 entries, no recursion — nested.txt must not be listed
        assertEquals(4, lines.size, "should list 4 top-level entries, got: $lines")
        assertTrue(lines.none { it.contains("nested.txt") }, "no recursion — nested.txt must not appear")

        // Folders first (alphadir, zulu), then files (alpha, beta)
        assertTrue(lines[0].startsWith("alphadir/"), "first line should be alphadir/, was: ${lines[0]}")
        assertTrue(lines[1].startsWith("zulu/"), "second line should be zulu/, was: ${lines[1]}")
        assertTrue(lines[2].startsWith("alpha.txt"), "third line should be alpha.txt, was: ${lines[2]}")
        assertTrue(lines[3].startsWith("beta.txt"), "fourth line should be beta.txt, was: ${lines[3]}")
    }

    @Test
    fun `output is empty (not an error) for an empty directory`() {
        // Cold, empty root — the canonical "cold profile" case from UD-224.
        val children =
            runBlocking {
                provider.authenticate()
                provider.listChildren("/")
            }
        val output = invokePrintChildren(children)
        assertEquals("", output.trim(), "empty dir must produce empty output, got: '$output'")
    }

    // -- Reflection helper -----------------------------------------------------

    /**
     * Drive the private formatter by capturing stdout. Kept package-private-by-
     * reflection so the production API doesn't grow a test-only surface — same
     * approach SweepCommandTest uses for `findNullByteStubs`.
     */
    private fun invokePrintChildren(children: List<CloudItem>): String {
        val cmd = LsCommand()
        val method: Method = LsCommand::class.java.getDeclaredMethod("printChildren", List::class.java)
        method.isAccessible = true
        val buffer = ByteArrayOutputStream()
        val originalOut = System.out
        try {
            System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
            method.invoke(cmd, children)
        } finally {
            System.setOut(originalOut)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    // -- Guard: I/O format contains ISO-8601 mtime -----------------------------

    @Test
    fun `file line contains ISO-8601 mtime and formatted size`() {
        val now = Instant.parse("2026-04-19T12:34:56Z")
        val items =
            listOf(
                CloudItem(
                    id = "/hello.txt",
                    name = "hello.txt",
                    path = "/hello.txt",
                    size = 2048L,
                    isFolder = false,
                    modified = now,
                    created = null,
                    hash = null,
                    mimeType = null,
                ),
            )
        val output = invokePrintChildren(items)
        assertTrue(output.contains("hello.txt"), "filename missing: $output")
        assertTrue(output.contains("2 KiB"), "size missing: $output")
        assertTrue(output.contains("2026-04-19T12:34:56Z"), "ISO-8601 mtime missing: $output")
    }
}
