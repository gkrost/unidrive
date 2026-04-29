package org.krost.unidrive.cli.ext.internal

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.krost.unidrive.cli.Main
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliServicesImplTest {
    @JvmField @Rule
    val tmp = TemporaryFolder()

    private fun freshMain(configDir: java.nio.file.Path): Main {
        val main = Main()
        main.configDir = configDir.toString()
        return main
    }

    @Test
    fun `configBaseDir returns the configured directory`() {
        val dir = tmp.newFolder("cfg").toPath()
        val services = CliServicesImpl(freshMain(dir))
        assertEquals(dir, services.configBaseDir())
    }

    @Test
    fun `unidriveVersion returns BuildInfo versionString`() {
        val dir = tmp.newFolder("cfg").toPath()
        val services = CliServicesImpl(freshMain(dir))
        assertTrue(services.unidriveVersion.isNotBlank())
    }

    @Test
    fun `listProfileNames returns empty list when no config`() {
        val dir = tmp.newFolder("cfg").toPath()
        val services = CliServicesImpl(freshMain(dir))
        assertEquals(emptyList<String>(), services.listProfileNames())
    }

    @Test
    fun `listProfileNames returns declared profile names`() {
        val dir = tmp.newFolder("cfg").toPath()
        Files.writeString(
            dir.resolve("config.toml"),
            """
            [providers.myprofile]
            type = "localfs"
            local_root = "/tmp/nowhere"
            """.trimIndent(),
        )
        val services = CliServicesImpl(freshMain(dir))
        assertEquals(listOf("myprofile"), services.listProfileNames())
    }

    @Test
    fun `formatter is non-null and delegates to AnsiHelper adapter`() {
        val dir = tmp.newFolder("cfg").toPath()
        val services = CliServicesImpl(freshMain(dir))
        assertTrue(services.formatter is AnsiHelperFormatter)
    }

    @Test
    fun `withProfile invalidates caches across back-to-back calls (UD-211)`() {
        val dir = tmp.newFolder("cfg").toPath()
        Files.writeString(
            dir.resolve("config.toml"),
            """
            [providers.alpha]
            type = "localfs"
            local_root = "/tmp/alpha"

            [providers.beta]
            type = "localfs"
            local_root = "/tmp/beta"
            """.trimIndent(),
        )
        val services = CliServicesImpl(freshMain(dir))

        // Before UD-211, the second resolveProfile call observed Main._profile
        // memoised from the first call and returned "alpha" for both lookups.
        val first = services.resolveProfile("alpha")
        val second = services.resolveProfile("beta")

        assertEquals("alpha", first.name)
        assertEquals("beta", second.name)
    }
}
