package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetCommandExclusionTest {
    private class Run(val exit: Int, val out: String, val err: String)

    private fun get(vararg args: String, setup: (remoteRoot: Path) -> Unit): Pair<Run, Path> {
        val configDir = Files.createTempDirectory("get-excluded-config")
        val localRoot = Files.createDirectories(configDir.resolve("local"))
        val remoteRoot = Files.createDirectories(configDir.resolve("remote"))
        setup(remoteRoot)
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            [providers.test]
            type = "localfs"
            sync_root = "${tomlPath(localRoot)}"
            root_path = "${tomlPath(remoteRoot)}"
            exclude_patterns = ["*.tmp"]
            """.trimIndent() + "\n",
        )
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8))
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            val exit = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "test", "get", *args)
            return Run(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8)) to configDir
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `get skips an excluded remote path and exits non-zero`() {
        val (run, configDir) = get("/scratch.tmp") { Files.writeString(it.resolve("scratch.tmp"), "remote bytes") }
        try {
            assertEquals("get: skipped excluded item: /scratch.tmp", run.err.trim())
            assertEquals(GetCommand.EXCLUDED_EXIT, run.exit)
            assertTrue(GetCommand.EXCLUDED_EXIT != 0)
            assertFalse(run.out.contains("Downloading"))
            assertFalse(Files.exists(configDir.resolve("local/scratch.tmp")), "get must not download an excluded item")
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `get on a folder holding only excluded files skips them and exits zero`() {
        val (run, configDir) = get("/docs") {
            Files.createDirectories(it.resolve("docs"))
            Files.writeString(it.resolve("docs/a.tmp"), "x")
            Files.writeString(it.resolve("docs/b.tmp"), "y")
        }
        try {
            assertEquals(0, run.exit)
            assertTrue(run.out.contains("Skipped"), run.out)
            assertFalse(Files.exists(configDir.resolve("local/docs/a.tmp")))
            assertFalse(Files.exists(configDir.resolve("local/docs/b.tmp")))
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    private fun tomlPath(path: Path): String = path.toString().replace('\', '/')
}
