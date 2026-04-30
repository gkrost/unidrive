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

    // UD-742 — scan-phase heartbeat with elapsed time + commitInline newline

    @Test
    fun `UD-742 scan progress with count gt 0 includes elapsed time text`() {
        val reporter = CliProgressReporter()
        reporter.onScanProgress("local", 0)
        Thread.sleep(30) // ensure elapsed >= 0; format will say "0s" but rendering is the point
        reporter.onScanProgress("local", 1234)
        // Force the inline state to commit so anything pending is in the buffer.
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(
            output.contains("Scanning local changes...") && output.contains("1234 items"),
            "expected scan line with count, got: $output",
        )
        assertTrue(
            output.contains("elapsed"),
            "expected 'elapsed' marker in heartbeat output, got: $output",
        )
    }

    @Test
    fun `UD-742 onSyncComplete after inline-active scan emits newline first`() {
        val reporter = CliProgressReporter(dryRun = true)
        // Trigger an inline scan progress that leaves the cursor mid-line in TTY mode.
        reporter.onScanProgress("local", 0)
        reporter.onScanProgress("local", 100)
        // Now finish — commitInline must emit a \n before the dry-run summary so
        // the two lines don't glue together (the user's reported `.mp4Dry-run:`
        // bug from 2026-04-30).
        reporter.onSyncComplete(0, 0, 0, 1500L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        // Output should contain BOTH the scan-progress text AND the dry-run line,
        // with a newline between them. We don't care about exact byte positions
        // (TTY/non-TTY paths differ), only that they're not directly concatenated.
        val dryRunIdx = output.indexOf("Dry-run:")
        assertTrue(dryRunIdx >= 0, "expected Dry-run output, got: $output")
        // The character immediately preceding "Dry-run:" must be a newline OR the
        // scan output must have used println (non-TTY path), in which case the
        // line just before is a newline anyway. The key invariant: NO scan-line
        // characters glued directly to "Dry-run:".
        assertFalse(
            output.contains("itemsDry-run:") || output.contains("100 items elapsed)Dry-run:"),
            "scan and Dry-run lines must not glue together; got: $output",
        )
    }

    @Test
    fun `UD-742 onActionCount after inline-active scan emits newline first`() {
        val reporter = CliProgressReporter()
        reporter.onScanProgress("remote", 0)
        reporter.onScanProgress("remote", 50)
        reporter.onActionCount(7)
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(output.contains("Reconciled: 7 actions"), "expected reconcile line; got: $output")
        // No glue — "Reconciled:" should not be touching scan-progress text
        assertFalse(
            output.contains("itemsReconciled:"),
            "scan and Reconciled lines must not glue together; got: $output",
        )
    }

    // UD-713 — throughput suffix once enough signal has accumulated.

    @Test
    fun `UD-713 scan heartbeat omits throughput when too few items`() {
        val reporter = CliProgressReporter()
        reporter.onScanProgress("local", 0)
        // count below 100 items threshold — no items/min line yet.
        reporter.onScanProgress("local", 50)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        assertFalse(
            output.contains("items/min"),
            "throughput suffix must not appear with <100 items; got: $output",
        )
    }

    @Test
    fun `UD-713 scan heartbeat omits throughput when elapsed too short`() {
        val reporter = CliProgressReporter()
        reporter.onScanProgress("local", 0)
        // 200 items but virtually no time elapsed — throughput would be wildly
        // unstable. Should be suppressed by the 5s elapsed gate.
        reporter.onScanProgress("local", 200)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        assertFalse(
            output.contains("items/min"),
            "throughput suffix must not appear before 5s elapsed; got: $output",
        )
    }
}
