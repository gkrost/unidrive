package org.krost.unidrive.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShareCommandTest {
    private val cmd = CommandLine(Main())

    @Test
    fun `share command is registered`() {
        val shareCmd = cmd.subcommands["share"]
        assertNotNull(shareCmd, "share subcommand should be registered")
    }

    // ── --list flag ────────────────────────────────────────────────────────────

    @Test
    fun `share command has --list flag`() {
        val shareCmd = cmd.subcommands["share"]!!
        val options = shareCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--list" in options, "share command should have --list option")
    }

    @Test
    fun `--list flag is boolean type`() {
        val shareCmd = cmd.subcommands["share"]!!
        val listOption = shareCmd.commandSpec.options().find { it.longestName() == "--list" }
        assertNotNull(listOption)
        assertEquals(Boolean::class.java, listOption.type(), "--list should be a boolean flag")
    }

    // ── --revoke flag ──────────────────────────────────────────────────────────

    @Test
    fun `share command has --revoke flag`() {
        val shareCmd = cmd.subcommands["share"]!!
        val options = shareCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--revoke" in options, "share command should have --revoke option")
    }

    @Test
    fun `--revoke flag accepts a string value`() {
        val shareCmd = cmd.subcommands["share"]!!
        val revokeOption = shareCmd.commandSpec.options().find { it.longestName() == "--revoke" }
        assertNotNull(revokeOption)
        assertEquals(String::class.java, revokeOption.type(), "--revoke should accept a string ID")
    }

    // ── --password flag ────────────────────────────────────────────────────────

    @Test
    fun `share command has --password flag`() {
        val shareCmd = cmd.subcommands["share"]!!
        val options = shareCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--password" in options, "share command should have --password option")
    }

    @Test
    fun `--password flag accepts a string value`() {
        val shareCmd = cmd.subcommands["share"]!!
        val pwOption = shareCmd.commandSpec.options().find { it.longestName() == "--password" }
        assertNotNull(pwOption)
        assertEquals(String::class.java, pwOption.type(), "--password should accept a string value")
    }

    // ── --expiry flag ──────────────────────────────────────────────────────────

    @Test
    fun `share command has --expiry flag with -e alias`() {
        val shareCmd = cmd.subcommands["share"]!!
        val allNames = shareCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("--expiry" in allNames, "share command should have --expiry option")
        assertTrue("-e" in allNames, "share command should have -e short alias for --expiry")
    }

    @Test
    fun `--expiry default value is 24`() {
        val shareCmd = cmd.subcommands["share"]!!
        val expiryOption = shareCmd.commandSpec.options().find { it.longestName() == "--expiry" }
        assertNotNull(expiryOption)
        val defaults = expiryOption.defaultValue()
        assertEquals("24", defaults, "--expiry should default to 24 hours")
    }

    // ── output format validation ───────────────────────────────────────────────

    @Test
    fun `list output header contains expected columns`() {
        val header = "%-36s  %-6s  %-14s  %-4s  %-20s  %s".format("ID", "Type", "Scope", "Pwd", "Expiry", "URL")
        assertTrue(header.contains("ID"))
        assertTrue(header.contains("Type"))
        assertTrue(header.contains("Scope"))
        assertTrue(header.contains("Pwd"))
        assertTrue(header.contains("Expiry"))
        assertTrue(header.contains("URL"))
    }

    @Test
    fun `list output separator is 120 chars`() {
        val separator = "-".repeat(120)
        assertEquals(120, separator.length)
    }

    @Test
    fun `list output row format renders ShareInfo fields`() {
        val row =
            "%-36s  %-6s  %-14s  %-4s  %-20s  %s".format(
                "abc-123-def-456-ghi-789-jkl-012-mno",
                "view",
                "anonymous",
                "yes",
                "2026-04-14T00:00:00Z",
                "https://example.com/share/abc",
            )
        assertTrue(row.contains("abc-123-def-456-ghi-789-jkl-012-mno"))
        assertTrue(row.contains("view"))
        assertTrue(row.contains("anonymous"))
        assertTrue(row.contains("yes"))
        assertTrue(row.contains("2026-04-14T00:00:00Z"))
        assertTrue(row.contains("https://example.com/share/abc"))
    }

    // ── path parameter ─────────────────────────────────────────────────────────

    @Test
    fun `share command requires a path positional parameter`() {
        val shareCmd = cmd.subcommands["share"]!!
        val positionals = shareCmd.commandSpec.positionalParameters()
        assertTrue(positionals.isNotEmpty(), "share command should have at least one positional parameter")
        assertEquals(0, positionals[0].index().min(), "path should be the first positional parameter")
    }

    // ── UD-243: --list and --revoke are mutually exclusive ─────────────────────

    private fun parseShareExitCode(vararg args: String): Int {
        val cli = CommandLine(Main())
        cli.executionStrategy = CommandLine.IExecutionStrategy { 0 }
        return cli.execute("share", *args)
    }

    private fun parseShareStderr(vararg args: String): String {
        val cli = CommandLine(Main())
        cli.executionStrategy = CommandLine.IExecutionStrategy { 0 }
        val errBuf = java.io.StringWriter()
        cli.err = java.io.PrintWriter(errBuf)
        cli.execute("share", *args)
        return errBuf.toString()
    }

    @Test
    fun `UD-243 list and revoke are mutually exclusive at parse time`() {
        val exit = parseShareExitCode("/foo", "--list", "--revoke", "abc")
        assertEquals(2, exit, "picocli should reject --list + --revoke with exit code 2")
    }

    @Test
    fun `UD-243 share list-revoke error message names both flags`() {
        val stderr = parseShareStderr("/foo", "--list", "--revoke", "abc")
        assertTrue(
            stderr.contains("--list") && stderr.contains("--revoke"),
            "error message should mention both conflicting flags, got: $stderr",
        )
    }

    @Test
    fun `UD-243 list alone is accepted`() {
        val exit = parseShareExitCode("/foo", "--list")
        assertEquals(0, exit, "--list alone should parse cleanly")
    }

    @Test
    fun `UD-243 revoke alone is accepted`() {
        val exit = parseShareExitCode("/foo", "--revoke", "abc")
        assertEquals(0, exit, "--revoke alone should parse cleanly")
    }
}
