package org.krost.unidrive.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
// UD-704: use JUnit Assume instead of bare `return` so skipped runs are visible.
import org.junit.Assume.assumeTrue

class CliSmokeTest {
    // Version-agnostic jar lookup — picks up whatever the current build produced.
    // Avoids silent no-op'ing after a version bump.
    private val jarPath: File =
        run {
            val libs = File("cli/build/libs")
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

    private fun runJar(vararg args: String): ProcessBuilder =
        ProcessBuilder(
            "java",
            "--enable-native-access=ALL-UNNAMED",
            "-jar",
            jarPath.absolutePath,
            *args,
        ).redirectErrorStream(true)

    @Test
    fun `version output format`() {
        assumeTrue("requires :app:cli:shadowJar — run ./gradlew :app:cli:shadowJar first", jarPath.exists())
        val pb = runJar("--version")
        val output =
            pb
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        assertTrue(output.contains("unidrive"), "Version output should contain 'unidrive'")
        assertTrue(output.matches(Regex("unidrive \\d+\\.\\d+\\.\\d+.*")), "Version should match x.y.z format")
    }

    @Test
    fun `version shows commit`() {
        assumeTrue("requires :app:cli:shadowJar — run ./gradlew :app:cli:shadowJar first", jarPath.exists())
        val pb = runJar("--version")
        val output =
            pb
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        assertTrue(output.contains("(") && output.contains(")"), "Version should include commit in parentheses")
    }

    @Test
    fun `provider info sftp succeeds`() {
        assumeTrue("requires :app:cli:shadowJar — run ./gradlew :app:cli:shadowJar first", jarPath.exists())
        val pb = runJar("provider", "info", "sftp")
        val output =
            pb
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        assertTrue(output.isNotEmpty(), "provider info should return output")
        assertTrue(output.contains("SFTP"), "Output should mention SFTP")
    }

    @Test
    fun `provider list shows providers`() {
        assumeTrue("requires :app:cli:shadowJar — run ./gradlew :app:cli:shadowJar first", jarPath.exists())
        val pb = runJar("provider", "list")
        val output =
            pb
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        assertTrue(output.contains("SFTP"), "Provider list should show SFTP")
        assertTrue(output.contains("OneDrive"), "Provider list should show OneDrive")
    }

    @Test
    fun `help shows UniDrive`() {
        assumeTrue("requires :app:cli:shadowJar — run ./gradlew :app:cli:shadowJar first", jarPath.exists())
        val pb = runJar("--help")
        val output =
            pb
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        assertTrue(output.contains("UniDrive"), "Help should mention UniDrive")
        assertTrue(output.contains("sync"), "Help should list sync command")
    }

    @Test
    fun `invalid command shows error`() {
        assumeTrue("requires :app:cli:shadowJar — run ./gradlew :app:cli:shadowJar first", jarPath.exists())
        val pb = runJar("nonexistent-command")
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        assertTrue(exitCode != 0 || output.contains("Unmatched") || output.contains("error"), "Invalid command should show error")
    }
}
