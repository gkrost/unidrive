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

    // UD-745 — failed count surfaces in summary when non-zero.

    @Test
    fun `UD-745 sync complete summary shows failed count when non-zero`() {
        val reporter = CliProgressReporter()
        reporter.onSyncComplete(downloaded = 0, uploaded = 100, conflicts = 0, durationMs = 1000L, actionCounts = emptyMap(), failed = 7)
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(output.contains("7 failed"), "expected '7 failed' in summary; got: $output")
    }

    @Test
    fun `UD-745 sync complete summary omits failed segment when zero`() {
        val reporter = CliProgressReporter()
        reporter.onSyncComplete(downloaded = 0, uploaded = 100, conflicts = 0, durationMs = 1000L, actionCounts = emptyMap(), failed = 0)
        val output = captured.toString(Charsets.UTF_8)
        assertFalse(output.contains("failed"), "expected no 'failed' marker on clean run; got: $output")
    }

    @Test
    fun `UD-745 dry-run summary unchanged regardless of failed`() {
        val reporter = CliProgressReporter(dryRun = true)
        // Dry-run produces no real failures; failed=0 is the realistic value,
        // but make sure passing it doesn't break the dry-run output shape.
        reporter.onSyncComplete(downloaded = 0, uploaded = 50, conflicts = 0, durationMs = 1000L, actionCounts = emptyMap(), failed = 0)
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(output.contains("Dry-run: would"), "expected dry-run header; got: $output")
        assertFalse(output.contains("failed"), "dry-run summary should not include failed segment; got: $output")
    }

    // UD-747 (UD-744 slice) — bucketed ETA from historical scan timings.
    // formatEtaBucket is internal so tests pin its bucket boundaries
    // directly; the integration assertion below exercises the end-to-end
    // heartbeat-string shape via the reporter.

    @Test
    fun `UD-747 formatEtaBucket returns empty when no historical data`() {
        val reporter = CliProgressReporter()
        assertEquals("", reporter.formatEtaBucket(null, 10L))
    }

    @Test
    fun `UD-747 formatEtaBucket returns empty when historical data is zero or negative`() {
        val reporter = CliProgressReporter()
        assertEquals("", reporter.formatEtaBucket(0L, 10L))
        assertEquals("", reporter.formatEtaBucket(-5L, 10L))
    }

    @Test
    fun `UD-747 formatEtaBucket returns empty when elapsed exceeds historical estimate`() {
        // Previous scan took 60s; current run is already at 90s. Don't lie
        // with "ETA: <5m" — the previous estimate is stale.
        val reporter = CliProgressReporter()
        assertEquals("", reporter.formatEtaBucket(60L, 90L))
        // Edge: exactly equal — we have no remaining time to suggest.
        assertEquals("", reporter.formatEtaBucket(60L, 60L))
    }

    @Test
    fun `UD-747 formatEtaBucket bucket boundaries`() {
        val reporter = CliProgressReporter()
        // <5m bucket: 1s..299s remaining
        assertEquals(", ETA: <5m", reporter.formatEtaBucket(100L, 0L))
        assertEquals(", ETA: <5m", reporter.formatEtaBucket(300L, 1L)) // 299s remaining
        // 5-15m bucket: 300s..899s remaining
        assertEquals(", ETA: 5-15m", reporter.formatEtaBucket(300L, 0L)) // exactly 5m
        assertEquals(", ETA: 5-15m", reporter.formatEtaBucket(900L, 1L)) // 899s
        // 15-60m bucket: 900s..3599s remaining
        assertEquals(", ETA: 15-60m", reporter.formatEtaBucket(900L, 0L))
        assertEquals(", ETA: 15-60m", reporter.formatEtaBucket(3600L, 1L)) // 3599s
        // >1h bucket: ≥3600s remaining
        assertEquals(", ETA: >1h", reporter.formatEtaBucket(3600L, 0L))
        assertEquals(", ETA: >1h", reporter.formatEtaBucket(7200L, 0L))
    }

    @Test
    fun `UD-747 scan heartbeat shows ETA bucket when historical hint provided`() {
        // Simulates the second sync run where state.db has a prior scan
        // timing. The hint is fed in BEFORE onScanProgress(_, 0) — the
        // typical SyncEngine call order.
        val reporter = CliProgressReporter()
        reporter.onScanHistoricalHint("local", lastSecs = 600L) // 10m last time
        reporter.onScanProgress("local", 0)
        // The heartbeat itself fires synchronously off onScanProgress;
        // elapsed will be ~0s, so remainingSecs ≈ 600s → 5-15m bucket.
        reporter.onScanProgress("local", 1234)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(
            output.contains("ETA: 5-15m"),
            "expected ETA bucket suffix in heartbeat; got: $output",
        )
    }

    @Test
    fun `UD-747 scan heartbeat omits ETA on first run`() {
        // No onScanHistoricalHint called — the reporter has no historical
        // data and must suppress the ETA segment cleanly.
        val reporter = CliProgressReporter()
        reporter.onScanProgress("local", 0)
        reporter.onScanProgress("local", 1234)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        assertFalse(
            output.contains("ETA:"),
            "first-run heartbeat must not include ETA segment; got: $output",
        )
    }
}
