package org.krost.unidrive.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ImplicitProfileTest {
    @Test
    fun `the only configured profile is used without -p or default_profile`() {
        val dir = Files.createTempDirectory("implicit-profile")
        Files.writeString(dir.resolve("config.toml"), "[providers.internxt-3]\ntype = \"internxt\"\nmode = \"mount\"\n")
        val main = Main().apply { configDir = dir.toString() }
        val profile = main.resolveCurrentProfile()
        assertEquals("internxt-3", profile.name)
        assertEquals("internxt", profile.type)
    }

    @Test
    fun `a configuration error names the profile and its type`() {
        assertEquals(
            "Configuration error: profile 'x' (localfs): missing required 'root_path'",
            configurationErrorLine("x", "localfs", "missing required 'root_path'"),
        )
    }
}
