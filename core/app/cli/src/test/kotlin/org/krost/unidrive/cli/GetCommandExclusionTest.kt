package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetCommandExclusionTest {
    @Test
    fun `get skips an excluded remote path`() {
        val configDir = Files.createTempDirectory("get-excluded-config")
        val localRoot = Files.createDirectories(configDir.resolve("local"))
        val remoteRoot = Files.createDirectories(configDir.resolve("remote"))
        Files.writeString(remoteRoot.resolve("scratch.tmp"), "remote bytes")
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

        val capturedError = ByteArrayOutputStream()
        val originalError = System.err
        try {
            System.setErr(PrintStream(capturedError, true, Charsets.UTF_8))

            CommandLine(Main()).execute("-c", configDir.toString(), "-p", "test", "get", "/scratch.tmp")

            assertTrue(capturedError.toString(Charsets.UTF_8).contains("excluded"))
            assertFalse(Files.exists(localRoot.resolve("scratch.tmp")), "get must not download an excluded item")
        } finally {
            System.setErr(originalError)
            configDir.toFile().deleteRecursively()
        }
    }

    private fun tomlPath(path: Path): String = path.toString().replace('\\', '/')
}
