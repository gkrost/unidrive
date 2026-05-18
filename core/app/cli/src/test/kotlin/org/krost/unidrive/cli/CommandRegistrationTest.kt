package org.krost.unidrive.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertTrue

class CommandRegistrationTest {
    private val cmd = CommandLine(Main())

    @Test
    fun `all top-level subcommands are registered`() {
        val names = cmd.subcommands.keys
        assertTrue("sync" in names)
        assertTrue("auth" in names)
        assertTrue("status" in names)
        assertTrue("get" in names)
        assertTrue("free" in names)
        assertTrue("quota" in names)
        assertTrue("logout" in names)
        assertTrue("log" in names)
        assertTrue("pin" in names)
        assertTrue("unpin" in names)
        assertTrue("vault" in names)
        assertTrue("conflicts" in names)
        assertTrue("profile" in names)
        assertTrue("relocate" in names)
        assertTrue("share" in names)
    }

    @Test
    fun `ServiceLoader discovers all in-tree providers`() {
        val types = org.krost.unidrive.ProviderRegistry.knownTypes
        assertTrue("onedrive" in types, "onedrive missing")
        assertTrue("internxt" in types, "internxt missing")
    }

    @Test
    fun `profile has add subcommand`() {
        val profileCmd = cmd.subcommands["profile"]!!
        assertTrue("add" in profileCmd.subcommands.keys)
    }

    @Test
    fun `profile has list subcommand`() {
        val profileCmd = cmd.subcommands["profile"]!!
        assertTrue("list" in profileCmd.subcommands.keys)
    }

    @Test
    fun `profile has remove subcommand`() {
        val profileCmd = cmd.subcommands["profile"]!!
        assertTrue("remove" in profileCmd.subcommands.keys)
    }

    @Test
    fun `conflicts has list subcommand`() {
        val conflictsCmd = cmd.subcommands["conflicts"]!!
        assertTrue("list" in conflictsCmd.subcommands.keys)
    }

    @Test
    fun `conflicts has restore subcommand`() {
        val conflictsCmd = cmd.subcommands["conflicts"]!!
        assertTrue("restore" in conflictsCmd.subcommands.keys)
    }

    @Test
    fun `vault has init subcommand`() {
        val vaultCmd = cmd.subcommands["vault"]!!
        assertTrue("init" in vaultCmd.subcommands.keys)
    }
}
