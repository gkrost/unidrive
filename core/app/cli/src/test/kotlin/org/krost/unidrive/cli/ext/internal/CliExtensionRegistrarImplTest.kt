package org.krost.unidrive.cli.ext.internal

import org.junit.Before
import org.junit.Test
import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliExtensionRegistrarImplTest {
    @Command(name = "root")
    private class Root : Runnable {
        override fun run() {}
    }

    @Command(name = "parent")
    private class Parent : Runnable {
        override fun run() {}
    }

    @Command(name = "child")
    private class Child : Runnable {
        override fun run() {}
    }

    @Command(name = "child")
    private class OtherChild : Runnable {
        override fun run() {}
    }

    @Before
    fun resetOwnership() {
        CliExtensionRegistrarImpl.resetForTesting()
    }

    private fun freshRoot(): CommandLine = CommandLine(Root()).addSubcommand("parent", Parent())

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

    @Test
    fun `registers subcommand under named parent`() {
        val root = freshRoot()
        val reg = CliExtensionRegistrarImpl(root, fakeServices, "ext-a")
        reg.addSubcommand("parent", Child())
        val parent = root.subcommands["parent"]!!
        assertTrue(parent.subcommands.containsKey("child"))
    }

    @Test
    fun `registers subcommand at root when parent is empty string`() {
        val root = freshRoot()
        val reg = CliExtensionRegistrarImpl(root, fakeServices, "ext-a")
        reg.addSubcommand("", Child())
        assertTrue(root.subcommands.containsKey("child"))
    }

    @Test
    fun `unknown parent throws IllegalStateException`() {
        val root = freshRoot()
        val reg = CliExtensionRegistrarImpl(root, fakeServices, "ext-a")
        val ex =
            assertFailsWith<IllegalStateException> {
                reg.addSubcommand("nonexistent", Child())
            }
        assertTrue(ex.message!!.contains("nonexistent"))
        assertTrue(ex.message!!.contains("ext-a"))
    }

    @Test
    fun `collision across two extensions throws naming both`() {
        val root = freshRoot()
        val regA = CliExtensionRegistrarImpl(root, fakeServices, "ext-a")
        regA.addSubcommand("parent", Child())
        val regB = CliExtensionRegistrarImpl(root, fakeServices, "ext-b")
        val ex =
            assertFailsWith<IllegalStateException> {
                regB.addSubcommand("parent", OtherChild())
            }
        assertTrue(ex.message!!.contains("ext-a"))
        assertTrue(ex.message!!.contains("ext-b"))
        assertTrue(ex.message!!.contains("child"))
    }

    @Test
    fun `services property returns injected services`() {
        val root = freshRoot()
        val reg = CliExtensionRegistrarImpl(root, fakeServices, "ext-a")
        assertEquals(fakeServices, reg.services)
    }
}
