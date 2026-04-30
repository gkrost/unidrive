package org.krost.unidrive.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Locale
import kotlin.test.*

class CliProgressReporterTest {
    private lateinit var originalOut: PrintStream
    private lateinit var originalLocale: Locale
    private lateinit var captured: ByteArrayOutputStream

    @BeforeTest
    fun setUp() {
        originalOut = System.out
        originalLocale = Locale.getDefault()
        captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        Locale.setDefault(originalLocale)
    }

    // UD-204: seconds rendering must be locale-neutral so "1.5s" is stable across
    // en-US, de-DE, fr-FR. Machine parsers reading CLI output break otherwise.
    @Test
    fun `sync complete duration uses locale-neutral decimal separator under German locale`() {
        Locale.setDefault(Locale.GERMAN)
        val reporter = CliProgressReporter()

        reporter.onSyncComplete(downloaded = 1, uploaded = 2, conflicts = 0, durationMs = 1500L, actionCounts = emptyMap())

        val output = captured.toString(Charsets.UTF_8)
        assertTrue(output.contains("(1.5s)"), "Expected '(1.5s)' in output; got: $output")
        assertFalse(output.contains("(1,5s)"), "Output must not carry comma decimal separator; got: $output")
    }

    @Test
    fun `dry-run duration uses locale-neutral decimal separator under German locale`() {
        Locale.setDefault(Locale.GERMAN)
        val reporter = CliProgressReporter(dryRun = true)

        reporter.onSyncComplete(downloaded = 1, uploaded = 2, conflicts = 0, durationMs = 2200L, actionCounts = emptyMap())

        val output = captured.toString(Charsets.UTF_8)
        assertTrue(output.contains("(2.2s)"), "Expected '(2.2s)' in output; got: $output")
    }

    // UD-238: the formatter's math is binary (divisors 2^10 / 2^20 / 2^30), so the
    // suffix must be IEC binary (KiB/MiB/GiB). Prior labelling as KB/MB/GB implied
    // decimal-SI and under-reported by ~7% against raw Graph / Internxt numbers.
    @Test
    fun `formatSize renders single-byte values with B suffix`() {
        assertEquals("0 B", CliProgressReporter.formatSize(0L))
        assertEquals("512 B", CliProgressReporter.formatSize(512L))
        assertEquals("1023 B", CliProgressReporter.formatSize(1023L))
    }

    @Test
    fun `formatSize crosses binary boundaries at 1024 exactly`() {
        assertEquals("1 KiB", CliProgressReporter.formatSize(1024L))
        assertEquals("1 MiB", CliProgressReporter.formatSize(1024L * 1024))
        assertEquals("1 GiB", CliProgressReporter.formatSize(1024L * 1024 * 1024))
    }

    @Test
    fun `formatSize uses binary divisor not decimal-SI`() {
        // 10^9 bytes is 953 MiB, NOT 1 GB. Guards against a regression to decimal math.
        assertEquals("953 MiB", CliProgressReporter.formatSize(1_000_000_000L))
    }

    // UD-735 — truncateForWidth must keep the [N/M] verb header intact and
    // shorten the path tail so each progress line stays on a single physical
    // console row.

    @Test
    fun `UD-735 truncateForWidth returns msg unchanged when within width`() {
        val msg = "[1/10] up /short/path.txt"
        assertEquals(msg, CliProgressReporter.truncateForWidth(msg, 80))
    }

    @Test
    fun `UD-735 truncateForWidth keeps prefix and tails the path with ellipsis`() {
        val msg = "[12345/65238] del-remote /dev/zvg/ZVG FileRepo/very_long_filename_that_overflows_the_console_width.dat"
        val cols = 80
        val out = CliProgressReporter.truncateForWidth(msg, cols)
        assertTrue(out.length <= cols, "result length ${out.length} exceeds cols=$cols: $out")
        assertTrue(out.startsWith("[12345/65238] del-remote "), "expected [N/M] verb prefix preserved, got: $out")
        assertTrue(out.contains("..."), "expected ellipsis marker, got: $out")
        assertTrue(out.endsWith(".dat"), "expected filename tail preserved, got: $out")
    }

    @Test
    fun `UD-735 truncateForWidth falls back to head-truncate when there is no path`() {
        val msg = "Scanning remote changes... but this status line happens to be way too long for the terminal"
        val out = CliProgressReporter.truncateForWidth(msg, 30)
        assertTrue(out.length <= 30, "result length ${out.length} exceeds 30: $out")
        assertTrue(out.endsWith("..."), "expected trailing ellipsis when no path is present, got: $out")
    }

    @Test
    fun `UD-735 truncateForWidth handles very narrow terminal`() {
        val msg = "[1/10] up /some/path/file.txt"
        val out = CliProgressReporter.truncateForWidth(msg, 10)
        assertTrue(out.length <= 10, "result length ${out.length} exceeds 10: $out")
    }

    @Test
    fun `UD-735 truncateForWidth returns empty when maxLen non-positive`() {
        assertEquals("", CliProgressReporter.truncateForWidth("anything", 0))
        assertEquals("", CliProgressReporter.truncateForWidth("anything", -5))
    }
}
