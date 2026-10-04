package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `refresh` against a profile with a persisted delta cursor returns only the delta
 * since that cursor — correct per the engine, but an operator reads "refresh" as
 * "re-enumerate everything". The help text says so, and names `--reset` as the way
 * to a full enumeration.
 */
class RefreshCommandHelpTest {
    @Test
    fun `refresh help documents the cursor resume and names --reset`() {
        val refresh = CommandLine(Main()).subcommands["refresh"]!!
        val out = StringWriter()
        refresh.usage(PrintWriter(out), CommandLine.Help.Ansi.OFF)
        val help = out.toString()

        assertTrue(
            help.contains("Resumes from the last sync cursor"),
            "the description must state the delta-cursor reuse; got:\n$help",
        )
        assertTrue(
            help.contains("--reset"),
            "the description must point at --reset for a full enumeration; got:\n$help",
        )
    }
}
