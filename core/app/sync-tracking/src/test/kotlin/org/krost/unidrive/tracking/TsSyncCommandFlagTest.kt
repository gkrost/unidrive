package org.krost.unidrive.tracking

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parse-level tests for the `ts sync` subcommand.
 *
 * These tests exercise the picocli option declarations only — no engine,
 * no provider, no filesystem. They confirm:
 *
 *  - `--auto-match` is registered on `ts sync`.
 *  - Accepted values (`off`, `size`, `name`) parse without error.
 *
 * Engine-level behaviour is covered by [TrackingEngineIntegrationTest] and
 * [TrackingReconcilerTest].
 */
class TsSyncCommandFlagTest {
    private fun tsSyncSpec(): CommandLine.Model.CommandSpec {
        val ts = TsCommand()
        val cmd = CommandLine(ts)
        return cmd.subcommands["sync"]!!.commandSpec
    }

    @Test
    fun `ts sync has --auto-match flag`() {
        val options = tsSyncSpec().options().map { it.longestName() }
        assertTrue("--auto-match" in options, "ts sync must expose --auto-match; options=$options")
    }

    @Test
    fun `ts sync has --dry-run flag`() {
        val options = tsSyncSpec().options().map { it.longestName() }
        assertTrue("--dry-run" in options, "ts sync must expose --dry-run; options=$options")
    }

    @Test
    fun `ts sync auto-match option has a description`() {
        val opt = tsSyncSpec().findOption("--auto-match")
        assertNotNull(opt, "--auto-match option must be registered")
        assertTrue(
            opt.description().isNotEmpty(),
            "--auto-match must have a non-empty description",
        )
    }

    private fun parseExitCode(vararg args: String): Int {
        val ts = TsCommand()
        val cmd = CommandLine(ts)
        cmd.executionStrategy = CommandLine.IExecutionStrategy { 0 }
        return cmd.execute("sync", *args)
    }

    @Test
    fun `ts sync --auto-match off parses cleanly`() {
        val exit = parseExitCode("--auto-match", "off")
        assertTrue(exit == 0, "--auto-match=off should parse cleanly, got exit $exit")
    }

    @Test
    fun `ts sync --auto-match size parses cleanly`() {
        val exit = parseExitCode("--auto-match", "size")
        assertTrue(exit == 0, "--auto-match=size should parse cleanly, got exit $exit")
    }

    @Test
    fun `ts sync --auto-match name parses cleanly`() {
        val exit = parseExitCode("--auto-match", "name")
        assertTrue(exit == 0, "--auto-match=name should parse cleanly, got exit $exit")
    }
}
