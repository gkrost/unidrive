package org.krost.unidrive.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The --full-tree refusal of a scoped profile is an operator refusal, not a usage
 * error: thrown as a picocli ParameterException it printed the whole usage block
 * under the one-line message, burying it. The refusal exits the JVM, so this runs
 * the real jar (the CliSmokeTest pattern) and asserts the rendered output: the
 * message is there, the usage block is not, and the exit code is the operator
 * refusal's 1.
 */
class SyncFullTreeRefusalSmokeTest {
    // Version-agnostic jar lookup — picks up whatever the current build produced.
    // Relative to this module's directory (the test task's working dir); the jar
    // lands in build/libs, unlike CliSmokeTest's `cli/build/libs`, which never
    // resolves and always skips.
    private val jarPath: File =
        run {
            val libs = File("build/libs")
            libs
                .listFiles()
                ?.firstOrNull {
                    it.name.startsWith("unidrive-") &&
                        it.name.endsWith(".jar") &&
                        !it.name.contains("-sources") &&
                        !it.name.contains("-javadoc")
                }
                ?: File(libs, "unidrive.jar")
        }

    @Test
    fun `the full-tree refusal prints one line and no usage block`() {
        assumeTrue("requires :app:cli:shadowJar — run ./gradlew :app:cli:shadowJar first", jarPath.exists())
        val configDir = Files.createTempDirectory("full-tree-refusal").toFile()
        try {
            val syncRoot =
                Files.createDirectories(configDir.toPath().resolve("sync-root"))
                    .toAbsolutePath()
                    .toString()
                    .replace('\\', '/') // TOML: a backslash in a double-quoted string is an escape
            File(configDir, "config.toml").writeText(
                """
                [general]
                default_profile = "onedrive_test"

                [providers.onedrive_test]
                type = "onedrive"
                mode = "mirror"
                sync_root = "$syncRoot"
                client_id = "smoke-client"
                sync_path = ["/scoped"]
                """.trimIndent(),
            )
            val proc =
                ProcessBuilder(
                    "java",
                    "--enable-native-access=ALL-UNNAMED",
                    "-jar",
                    jarPath.absolutePath,
                    "-c",
                    configDir.absolutePath,
                    "sync",
                    "--full-tree",
                ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()

            assertTrue(
                output.contains("--full-tree conflicts with sync_path"),
                "the refusal message must be present: $output",
            )
            assertFalse(
                output.contains("Usage:"),
                "an operator refusal must not print the usage block: $output",
            )
            assertEquals(1, exit, "an operator refusal exits 1; output: $output")
        } finally {
            configDir.deleteRecursively()
        }
    }
}
