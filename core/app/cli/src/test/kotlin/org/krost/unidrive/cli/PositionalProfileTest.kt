package org.krost.unidrive.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Positional `<profile>` argument on daemon run|status|stop, sync, and refresh.
 *
 * Invariant under test: the positional profile value parses onto the
 * subcommand's `profilePositional` field and `applyPositionalProfile` copies it
 * onto `Main.provider` — so `unidrive daemon run posteo_onedrive` resolves the
 * same profile as `unidrive -p posteo_onedrive daemon run`. Mirrors the
 * existing `mount`-takes-positional precedent.
 *
 * These tests drive picocli's parser (parseArgs) rather than execute(), so
 * run() — which would touch config.toml and System.exit — never fires.
 */
class PositionalProfileTest {

    // ── parse-level: the positional binds onto each command's field ──────────

    @Test
    fun `daemon run accepts positional profile`() {
        val cli = CommandLine(Main())
        val parsed = cli.parseArgs("daemon", "run", "posteo_onedrive")
        val cmd = parsed.subcommand()?.subcommand()
            ?.commandSpec()?.userObject() as? DaemonRunCommand
        assertNotNull(cmd, "daemon run should parse")
        assertEquals("posteo_onedrive", cmd.profilePositional)
    }

    @Test
    fun `daemon status accepts positional profile`() {
        val cli = CommandLine(Main())
        val parsed = cli.parseArgs("daemon", "status", "posteo_onedrive")
        val cmd = parsed.subcommand()?.subcommand()
            ?.commandSpec()?.userObject() as? DaemonStatusCommand
        assertNotNull(cmd, "daemon status should parse")
        assertEquals("posteo_onedrive", cmd.profilePositional)
    }

    @Test
    fun `daemon stop accepts positional profile`() {
        val cli = CommandLine(Main())
        val parsed = cli.parseArgs("daemon", "stop", "posteo_onedrive")
        val cmd = parsed.subcommand()?.subcommand()
            ?.commandSpec()?.userObject() as? DaemonStopCommand
        assertNotNull(cmd, "daemon stop should parse")
        assertEquals("posteo_onedrive", cmd.profilePositional)
    }

    @Test
    fun `sync accepts positional profile`() {
        val cli = CommandLine(Main())
        val parsed = cli.parseArgs("sync", "posteo_onedrive")
        val cmd = parsed.subcommand()?.commandSpec()?.userObject() as? SyncCommand
        assertNotNull(cmd, "sync should parse")
        assertEquals("posteo_onedrive", cmd.profilePositional)
    }

    @Test
    fun `refresh accepts positional profile`() {
        val cli = CommandLine(Main())
        val parsed = cli.parseArgs("refresh", "posteo_onedrive")
        val cmd = parsed.subcommand()?.commandSpec()?.userObject() as? RefreshCommand
        assertNotNull(cmd, "refresh should parse")
        assertEquals("posteo_onedrive", cmd.profilePositional)
    }

    // ── the positional is optional (arity 0..1) — bare form still parses ──────

    @Test
    fun `daemon run without positional parses with null profile`() {
        val cli = CommandLine(Main())
        val parsed = cli.parseArgs("daemon", "run")
        val cmd = parsed.subcommand()?.subcommand()
            ?.commandSpec()?.userObject() as? DaemonRunCommand
        assertNotNull(cmd)
        assertEquals(null, cmd.profilePositional)
    }

    // ── behaviour-level: applyPositionalProfile copies onto Main.provider ─────
    // This is the load-bearing half — it proves the positional actually drives
    // profile resolution, not merely that it parses.

    @Test
    fun `applyPositionalProfile sets Main provider from positional`() {
        val main = Main()
        assertEquals(null, main.provider)
        applyPositionalProfile(main, "posteo_onedrive")
        assertEquals("posteo_onedrive", main.provider)
    }

    @Test
    fun `applyPositionalProfile leaves -p form untouched when positional absent`() {
        val main = Main()
        main.provider = "internxt" // simulates global -p internxt
        applyPositionalProfile(main, null)
        assertEquals("internxt", main.provider, "null positional must not clobber -p")
    }

    @Test
    fun `both positional and -p forms are accepted by the parser`() {
        // The two forms are interchangeable at the parse layer; -p binds on Main,
        // the positional binds on the subcommand. Neither is rejected.
        val cli = CommandLine(Main())
        val viaOption = cli.parseArgs("-p", "posteo_onedrive", "daemon", "run")
        assertEquals("posteo_onedrive", (viaOption.commandSpec().userObject() as Main).provider)

        val cli2 = CommandLine(Main())
        val viaPositional = cli2.parseArgs("daemon", "run", "posteo_onedrive")
        val cmd = viaPositional.subcommand()?.subcommand()
            ?.commandSpec()?.userObject() as? DaemonRunCommand
        assertNotNull(cmd)
        assertEquals("posteo_onedrive", cmd.profilePositional)
    }

    // ── the daemon subcommands expose the positional parameter on their spec ──

    @Test
    fun `daemon subcommands declare a positional parameter`() {
        val cli = CommandLine(Main())
        val daemon = cli.subcommands["daemon"]!!
        for (sub in listOf("run", "status", "stop")) {
            val positionals = daemon.subcommands[sub]!!.commandSpec.positionalParameters()
            assertTrue(
                positionals.isNotEmpty(),
                "daemon $sub must accept a positional <profile>",
            )
        }
    }
}
