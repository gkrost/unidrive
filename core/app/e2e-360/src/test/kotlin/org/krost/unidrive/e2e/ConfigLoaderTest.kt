package org.krost.unidrive.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ConfigLoaderTest {

    @Test
    fun `parse minimal config`() {
        val toml = """
            [general]
            profile = "dev"

            [run]
            run_id = "test-001"
        """.trimIndent()

        val config = ConfigLoader.parse(toml)

        assertEquals("dev", config.general.profile)
        assertEquals("test-001", config.run.run_id)
    }

    @Test
    fun `dev profile resolves correct defaults`() {
        val toml = """
            [general]
            profile = "dev"
        """.trimIndent()

        val config = ConfigLoader.parse(toml)
        val profile = ConfigLoader.resolveProfile(config)

        assertEquals(2, profile.maxDepth)
        assertEquals(3, profile.filesPerFolder)
    }

    @Test
    fun `custom profile uses explicit values`() {
        val toml = """
            [general]
            profile          = "custom"
            max_depth        = 6
            files_per_folder = 15
        """.trimIndent()

        val config = ConfigLoader.parse(toml)
        val profile = ConfigLoader.resolveProfile(config)

        assertEquals(6, profile.maxDepth)
        assertEquals(15, profile.filesPerFolder)
    }

    @Test
    fun `provider enabled flags parsed`() {
        val toml = """
            [general]
            profile = "dev"

            [providers.onedrive]
            enabled = true
            phase   = "1a"

            [providers.hidrive]
            enabled = false
            phase   = "backlog"
        """.trimIndent()

        val config = ConfigLoader.parse(toml)

        assertTrue(config.providers["onedrive"]?.enabled ?: false)
        assertFalse(config.providers["hidrive"]?.enabled ?: true)
    }
}
