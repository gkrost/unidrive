package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-241: every non-top-level subcommand accepts `--help` / `-h`, exits 0, and does not
 * emit "Unknown option" to stderr.
 *
 * UD-242: the unified "no unidrive config found" wording renders correctly in both
 * compact (default) and verbose modes. Covered by [renderConfigMissingMessage] — the
 * pure renderer — rather than by spawning a full CLI process so we can run under any
 * JVM without trapping System.exit.
 */
class HelpAndConfigErrorTest {
    // ─────────────────────────────────────────────────────────────────────────
    // UD-241 — mixinStandardHelpOptions everywhere
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `every top-level subcommand registers --help`() {
        val cmd = CommandLine(Main())
        for ((name, sub) in cmd.subcommands) {
            val hasHelpOpt =
                sub.commandSpec.options().any { opt ->
                    opt.names().any { it == "--help" || it == "-h" }
                }
            assertTrue(hasHelpOpt, "Subcommand '$name' must register --help via mixinStandardHelpOptions")
        }
    }

    @Test
    fun `every sub-subcommand registers --help`() {
        val cmd = CommandLine(Main())
        val nested =
            listOf("backup", "profile", "vault", "provider", "conflicts", "trash", "versions")
        for (parent in nested) {
            val parentCmd =
                cmd.subcommands[parent]
                    ?: error("Expected parent subcommand '$parent' to be registered")
            for ((leafName, leaf) in parentCmd.subcommands) {
                val hasHelpOpt =
                    leaf.commandSpec.options().any { opt ->
                        opt.names().any { it == "--help" || it == "-h" }
                    }
                assertTrue(
                    hasHelpOpt,
                    "Sub-subcommand '$parent $leafName' must register --help via mixinStandardHelpOptions",
                )
            }
        }
    }

    @Test
    fun `--help on leaf subcommand exits zero with no Unknown option error`() {
        // picocli's built-in help handler returns 0 and prints to stdout. It does NOT
        // call System.exit, so we can run it in-process safely.
        val stderrBuf = ByteArrayOutputStream()
        val origErr = System.err
        System.setErr(PrintStream(stderrBuf))
        val exitCode =
            try {
                CommandLine(Main()).execute("status", "--help")
            } finally {
                System.setErr(origErr)
            }
        assertEquals(0, exitCode, "status --help must exit 0")
        assertFalse(
            stderrBuf.toString().contains("Unknown option"),
            "stderr for 'status --help' must not contain 'Unknown option'",
        )
    }

    @Test
    fun `--help on sub-subcommand exits zero`() {
        val stderrBuf = ByteArrayOutputStream()
        val origErr = System.err
        System.setErr(PrintStream(stderrBuf))
        val exitCode =
            try {
                CommandLine(Main()).execute("profile", "add", "--help")
            } finally {
                System.setErr(origErr)
            }
        assertEquals(0, exitCode)
        assertFalse(stderrBuf.toString().contains("Unknown option"))
    }

    @Test
    fun `-h short form works on leaf`() {
        val exitCode = CommandLine(Main()).execute("vault", "init", "-h")
        assertEquals(0, exitCode)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UD-242 — unified config-missing wording
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `config-missing renders one actionable first line`() {
        val sandbox = Files.createTempDirectory("ud242-")
        try {
            val msg = renderConfigMissingMessage(sandbox.resolve("config.toml"), verbose = false)
            val firstLine = msg.lineSequence().first()
            assertEquals(
                "Error: no unidrive config found. Run 'unidrive profile add <type> <name>' " +
                    "to create your first profile.",
                firstLine,
            )
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `config-missing non-verbose hides diagnostic block`() {
        val sandbox = Files.createTempDirectory("ud242-")
        try {
            val msg = renderConfigMissingMessage(sandbox.resolve("config.toml"), verbose = false)
            assertFalse(msg.contains("APPDATA env"), "no diagnostic dump without -v")
            assertFalse(msg.contains("Files.exists"), "no diagnostic dump without -v")
            assertFalse(msg.contains("[verbose]"), "no verbose-section header without -v")
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `config-missing non-verbose includes the searched path hint`() {
        val sandbox = Files.createTempDirectory("ud242-")
        try {
            val configFile = sandbox.resolve("config.toml")
            val msg = renderConfigMissingMessage(configFile, verbose = false)
            assertTrue(msg.contains("Searched: $configFile"), "path hint must be present")
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `config-missing verbose shows diagnostic block`() {
        val sandbox = Files.createTempDirectory("ud242-")
        try {
            val msg = renderConfigMissingMessage(sandbox.resolve("config.toml"), verbose = true)
            assertTrue(msg.contains("[verbose]"), "verbose diagnostic header expected")
            assertTrue(msg.contains("Files.exists"), "Files.exists line expected under -v")
            assertTrue(msg.contains("APPDATA env"), "APPDATA env line expected under -v")
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }

    @Test
    fun `config-missing verbose flags MSIX-sandbox style filtered-FS case`() {
        // When config.toml exists and has content but parseRaw returns zero providers,
        // the diagnostic hints at filtered-FS views. We simulate by creating a
        // non-empty file; the renderer just reports what Files.exists/size show.
        val sandbox = Files.createTempDirectory("ud242-")
        try {
            val configFile = sandbox.resolve("config.toml")
            Files.writeString(configFile, "[general]\n# content\n")
            val msg = renderConfigMissingMessage(configFile, verbose = true)
            assertTrue(
                msg.contains("filtered filesystem view"),
                "non-empty config should produce the MSIX-sandbox hint",
            )
            Files.deleteIfExists(configFile)
        } finally {
            Files.deleteIfExists(sandbox)
        }
    }
}
