package org.krost.unidrive.cli.ext.internal

import org.junit.Before
import org.junit.Test
import org.krost.unidrive.cli.ext.fixtures.DummyExtension
import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.test.assertTrue

class CliExtensionLoaderTest {
    @Command(name = "root")
    private class Root : Runnable {
        override fun run() {}
    }

    private val fakeServices =
        object : org.krost.unidrive.cli.ext.CliServices {
            override fun resolveProfile(name: String) = error("unused")

            override fun createProvider(profileName: String) = error("unused")

            override fun loadSyncConfig(profileName: String) = error("unused")

            override fun configBaseDir() = error("unused")

            override fun isProviderAuthenticated(profileName: String) = error("unused")

            override fun listProfileNames() = emptyList<String>()

            override val unidriveVersion = "test"
            override val formatter =
                object : org.krost.unidrive.cli.ext.Formatter {
                    override fun bold(s: String) = s

                    override fun dim(s: String) = s

                    override fun underline(s: String) = s
                }
        }

    @Before
    fun reset() {
        CliExtensionRegistrarImpl.resetForTesting()
        DummyExtension.registerCalls = 0
    }

    @Test
    fun `loads and registers discovered extensions`() {
        val root = CommandLine(Root())
        CliExtensionLoader.loadInto(root, fakeServices)
        assertTrue(root.subcommands.containsKey("dummy"))
    }

    @Test
    fun `calls register exactly once per extension`() {
        val root = CommandLine(Root())
        CliExtensionLoader.loadInto(root, fakeServices)
        assertTrue(
            DummyExtension.registerCalls == 1,
            "register() called ${DummyExtension.registerCalls} times",
        )
    }

    @Test
    fun `does not crash if an extension throws`() {
        val root = CommandLine(Root())
        val throwing =
            object : org.krost.unidrive.cli.ext.CliExtension {
                override val id = "boom"

                override fun register(registrar: org.krost.unidrive.cli.ext.CliExtensionRegistrar): Unit =
                    throw RuntimeException("intentional")
            }
        // Must not throw.
        CliExtensionLoader.registerAll(root, fakeServices, listOf(throwing))
        // root unchanged (no subcommand was registered by the throwing ext)
        assertTrue(!root.subcommands.containsKey("boom"))
    }
}
