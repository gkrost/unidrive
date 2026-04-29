package org.krost.unidrive.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SyncCommandTest {
    private val cmd = CommandLine(Main())

    @Test
    fun `sync command is registered`() {
        val syncCmd = cmd.subcommands["sync"]
        assertNotNull(syncCmd, "sync subcommand should be registered")
    }

    @Test
    fun `sync command has --dry-run flag`() {
        val syncCmd = cmd.subcommands["sync"]!!
        val options = syncCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--dry-run" in options)
    }

    @Test
    fun `sync command has --watch flag`() {
        val syncCmd = cmd.subcommands["sync"]!!
        val options = syncCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--watch" in options)
    }

    @Test
    fun `sync command has --reset flag`() {
        val syncCmd = cmd.subcommands["sync"]!!
        val options = syncCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--reset" in options)
    }

    @Test
    fun `sync command has --sync-path flag`() {
        val syncCmd = cmd.subcommands["sync"]!!
        val options = syncCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--sync-path" in options)
    }

    @Test
    fun `sync command --watch has -w short alias`() {
        val syncCmd = cmd.subcommands["sync"]!!
        val allNames = syncCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-w" in allNames)
    }

    @Test
    fun `sync command has --exclude flag`() {
        val syncCmd = cmd.subcommands["sync"]!!
        val options = syncCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--exclude" in options)
    }

    // ── UD-243: mutually-exclusive flag validation at parse time ────────────

    /** Parse the given sync args and return the CommandLine exit code. */
    private fun parseExitCode(vararg args: String): Int {
        val cli = CommandLine(Main())
        // Use a no-op execution strategy so we never actually run() the
        // command — we're only interested in parse-time behaviour.
        cli.executionStrategy = CommandLine.IExecutionStrategy { 0 }
        return cli.execute("sync", *args)
    }

    /** Parse the given sync args and return what picocli wrote to stderr. */
    private fun parseStderr(vararg args: String): String {
        val cli = CommandLine(Main())
        cli.executionStrategy = CommandLine.IExecutionStrategy { 0 }
        val errBuf = java.io.StringWriter()
        cli.err = java.io.PrintWriter(errBuf)
        cli.execute("sync", *args)
        return errBuf.toString()
    }

    @Test
    fun `UD-243 upload-only and download-only are mutually exclusive at parse time`() {
        val exit = parseExitCode("--upload-only", "--download-only")
        assertEquals(2, exit, "picocli should reject mutually-exclusive flags with exit code 2")
    }

    @Test
    fun `UD-243 upload-only-download-only error message names both flags`() {
        val stderr = parseStderr("--upload-only", "--download-only")
        assertTrue(
            stderr.contains("--upload-only") && stderr.contains("--download-only"),
            "error message should mention both conflicting flags, got: $stderr",
        )
    }

    @Test
    fun `UD-243 upload-only alone is accepted`() {
        val exit = parseExitCode("--upload-only")
        assertEquals(0, exit, "--upload-only alone should parse cleanly")
    }

    @Test
    fun `UD-243 download-only alone is accepted`() {
        val exit = parseExitCode("--download-only")
        assertEquals(0, exit, "--download-only alone should parse cleanly")
    }

    @Test
    fun `UD-243 neither direction flag is accepted`() {
        val exit = parseExitCode()
        assertEquals(0, exit, "no direction flag should parse cleanly")
    }

    // Run the command for real so ParameterException-in-run() paths are
    // exercised. We do NOT want to touch config or engine, so we rely on
    // the ParameterException being thrown before the first line of setup.
    private fun runAndExpectExit2(vararg args: String): String {
        val cli = CommandLine(Main())
        val errBuf = java.io.StringWriter()
        cli.err = java.io.PrintWriter(errBuf)
        val exit = cli.execute("sync", *args)
        if (exit != 2) {
            fail("expected exit 2 for args ${args.toList()}, got $exit. stderr=$errBuf")
        }
        return errBuf.toString()
    }

    @Test
    fun `UD-243 dry-run and force-delete are mutually exclusive at parse time`() {
        val stderr = runAndExpectExit2("--dry-run", "--force-delete")
        assertTrue(
            stderr.contains("--dry-run") && stderr.contains("--force-delete"),
            "error should mention both flags, got: $stderr",
        )
    }

    @Test
    fun `UD-243 reset and dry-run are mutually exclusive at parse time`() {
        val stderr = runAndExpectExit2("--reset", "--dry-run")
        assertTrue(
            stderr.contains("--reset") && stderr.contains("--dry-run"),
            "error should mention both flags, got: $stderr",
        )
    }
}
