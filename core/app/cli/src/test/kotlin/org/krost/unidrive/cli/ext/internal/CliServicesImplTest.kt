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
            type = "onedrive"
            client_id = "test"
            """.trimIndent(),
        )
        val services = CliServicesImpl(freshMain(dir))
        assertEquals(listOf("myprofile"), services.listProfileNames())
    }

    @Test
    fun `resolvedGlobalProfile returns the global -p value when set`() {
        // The global `-p` is held by Main.provider; an extension subcommand
        // must be able to read it so `unidrive -p X <ext>` resolves to X.
        val dir = tmp.newFolder("cfg").toPath()
        val main = freshMain(dir)
        main.provider = "chosen_profile"
        val services = CliServicesImpl(main)
        assertEquals("chosen_profile", services.resolvedGlobalProfile())
    }

    @Test
    fun `resolvedGlobalProfile is null when no global -p was given`() {
        val dir = tmp.newFolder("cfg").toPath()
        val services = CliServicesImpl(freshMain(dir))
        assertEquals(null, services.resolvedGlobalProfile())
    }

    @Test
    fun `formatter is non-null and delegates to AnsiHelper adapter`() {
        val dir = tmp.newFolder("cfg").toPath()
        val services = CliServicesImpl(freshMain(dir))
        assertTrue(services.formatter is AnsiHelperFormatter)
    }

    @Test
    fun `withProfile invalidates caches across back-to-back calls (UD-213)`() {
        val dir = tmp.newFolder("cfg").toPath()
        Files.writeString(
            dir.resolve("config.toml"),
            """
            [providers.alpha]
            type = "onedrive"
            client_id = "alpha-client"

            [providers.beta]
            type = "onedrive"
            client_id = "beta-client"
            """.trimIndent(),
        )
        val services = CliServicesImpl(freshMain(dir))

        // Before UD-213, the second resolveProfile call observed Main._profile
        // memoised from the first call and returned "alpha" for both lookups.
        val first = services.resolveProfile("alpha")
        val second = services.resolveProfile("beta")

        assertEquals("alpha", first.name)
        assertEquals("beta", second.name)
    }
}
