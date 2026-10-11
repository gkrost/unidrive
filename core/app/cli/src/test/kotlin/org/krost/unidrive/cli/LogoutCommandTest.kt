package org.krost.unidrive.cli

import picocli.CommandLine
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogoutCommandTest {
    private val configDir = Files.createTempDirectory("unidrive-logout-")

    @AfterTest
    fun tearDown() {
        configDir.toFile().deleteRecursively()
    }

    @Test
    fun `logout removes pending device flow file`() {
        val root = Files.createDirectory(configDir.resolve("root"))
        Files.writeString(
            configDir.resolve("config.toml"),
            "[providers.work]\ntype = \"localfs\"\nroot_path = \"$root\"\n",
        )
        val profileDir = Files.createDirectory(configDir.resolve("work"))
        val pendingFlow = Files.writeString(profileDir.resolve("device-flow.json"), "pending")

        val exitCode = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "work", "logout")

        assertEquals(0, exitCode)
        assertFalse(Files.exists(pendingFlow))
    }
}
