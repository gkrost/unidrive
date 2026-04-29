package org.krost.unidrive.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StatusCommandTest {
    private val cmd = CommandLine(Main())

    @Test
    fun `status command is registered`() {
        val statusCmd = cmd.subcommands["status"]
        assertNotNull(statusCmd, "status subcommand should be registered")
    }

    @Test
    fun `status command has --all flag`() {
        val statusCmd = cmd.subcommands["status"]!!
        val options = statusCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--all" in options)
    }

    @Test
    fun `status command --all has -a short alias`() {
        val statusCmd = cmd.subcommands["status"]!!
        val allNames = statusCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-a" in allNames)
    }
}
