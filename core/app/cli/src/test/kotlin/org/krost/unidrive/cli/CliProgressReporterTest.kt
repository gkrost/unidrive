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

    // UD-742 / UD-757 — scan-phase heartbeat shape & commitInline newline.
    // The line format is "Scanning <phase-label>... <N> items · <M:SS>[ · ETA <M:SS>]".
    // Phase labels per UD-757: remote → "remote changes", local → "local files".

    @Test
    fun `UD-742 UD-757 scan progress with count gt 0 shows count and M_SS elapsed`() {
        val reporter = CliProgressReporter()
        reporter.onScanProgress("local", 0)
        Thread.sleep(30) // small jitter; elapsed will format as 0:00 here
        reporter.onScanProgress("local", 1234)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        // UD-757 phase label: local → "local files".
        assertTrue(
            output.contains("Scanning local files...") && output.contains("1,234 items"),
            "expected scan line with count, got: $output",
        )
        // M:SS elapsed segment — at minimum the literal "0:00" from a fast tick.
        assertTrue(
            output.contains("· 0:") || output.contains("· 1:") || output.contains("· 2:"),
            "expected M:SS elapsed marker; got: $output",
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
        val dryRunIdx = output.indexOf("Dry-run:")
        assertTrue(dryRunIdx >= 0, "expected Dry-run output, got: $output")
        // The key invariant: NO scan-line characters glued directly to "Dry-run:".
        assertFalse(
            output.contains("itemsDry-run:") || output.contains(" 0:00Dry-run:"),
            "scan and Dry-run lines must not glue together; got: $output",
        )
    }

    @Test
    fun `UD-240g reconcile progress renders processed of total items and elapsed`() {
        val reporter = CliProgressReporter()
        reporter.onReconcileProgress(0, 86_000)
        reporter.onReconcileProgress(43_000, 86_000)
        reporter.onReconcileProgress(86_000, 86_000)
        reporter.onActionCount(12)
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(
            output.contains("Reconciling...") && output.contains("43,000 / 86,000 items"),
            "expected mid-pass reconcile line with processed/total; got: $output",
        )
        // M:SS elapsed marker must be present on the mid-pass tick.
        assertTrue(
            output.contains("· 0:") || output.contains("· 1:"),
            "expected M:SS elapsed marker on reconcile line; got: $output",
        )
        // commitInline must run before "Reconciled: N" so the two don't glue.
        assertFalse(
            output.contains("itemsReconciled:"),
            "reconcile-progress and Reconciled lines must not glue together; got: $output",
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

    // UD-747 / UD-748 / UD-757 — ETA seconds from historical scan timings.
    // computeEtaSecs returns Long? (null = no ETA segment); the renderer
    // formats with formatTime as M:SS / H:MM:SS.

    @Test
    fun `UD-747 computeEtaSecs returns null when no historical data`() {
        val reporter = CliProgressReporter()
        assertEquals(null, reporter.computeEtaSecs(null, 10L))
    }

    @Test
    fun `UD-747 computeEtaSecs returns null when historical data is zero or negative`() {
        val reporter = CliProgressReporter()
        assertEquals(null, reporter.computeEtaSecs(0L, 10L))
        assertEquals(null, reporter.computeEtaSecs(-5L, 10L))
    }

    @Test
    fun `UD-747 computeEtaSecs returns null when elapsed exceeds historical estimate`() {
        // Previous scan took 60s; current run is already at 90s. Don't lie
        // with a positive ETA — the previous estimate is stale.
        val reporter = CliProgressReporter()
        assertEquals(null, reporter.computeEtaSecs(60L, 90L))
        // Edge: exactly equal — we have no remaining time to suggest.
        assertEquals(null, reporter.computeEtaSecs(60L, 60L))
    }

    @Test
    fun `UD-747 computeEtaSecs returns wall-clock remaining seconds`() {
        val reporter = CliProgressReporter()
        // 100s budget, 0s elapsed → 100s remaining.
        assertEquals(100L, reporter.computeEtaSecs(100L, 0L))
        // 300s budget, 1s elapsed → 299s remaining.
        assertEquals(299L, reporter.computeEtaSecs(300L, 1L))
        // 3600s budget, 0s elapsed → 3600s.
        assertEquals(3600L, reporter.computeEtaSecs(3600L, 0L))
    }

    // UD-757 — formatTime renders M:SS for under 1h, H:MM:SS above.

    @Test
    fun `UD-757 formatTime under 1 hour renders M_SS`() {
        val reporter = CliProgressReporter()
        assertEquals("0:00", reporter.formatTime(0L))
        assertEquals("0:18", reporter.formatTime(18L))
        assertEquals("1:02", reporter.formatTime(62L))
        assertEquals("9:59", reporter.formatTime(599L))
        assertEquals("59:59", reporter.formatTime(3599L))
    }

    @Test
    fun `UD-757 formatTime at 1 hour and above renders H_MM_SS`() {
        val reporter = CliProgressReporter()
        assertEquals("1:00:00", reporter.formatTime(3600L))
        assertEquals("1:23:45", reporter.formatTime(5025L))
        assertEquals("10:00:00", reporter.formatTime(36000L))
    }

    @Test
    fun `UD-757 formatTime negative input clamps to 0_00`() {
        val reporter = CliProgressReporter()
        assertEquals("0:00", reporter.formatTime(-7L))
    }

    // UD-757 — formatCount comma-grouped under Locale.ROOT (locale-independent).

    @Test
    fun `UD-757 formatCount uses comma thousands separator regardless of locale`() {
        Locale.setDefault(Locale.GERMAN) // would normally render '12.450'
        val reporter = CliProgressReporter()
        assertEquals("0", reporter.formatCount(0))
        assertEquals("999", reporter.formatCount(999))
        assertEquals("1,000", reporter.formatCount(1000))
        assertEquals("12,450", reporter.formatCount(12_450))
        assertEquals("63,658", reporter.formatCount(63_658))
    }

    // UD-757 — end-to-end scan-progress line shape with a fake clock so the
    // M:SS elapsed and ETA strings are deterministic.

    private class FakeClock(
        var nowMs: Long = 0L,
    ) {
        fun advanceSecs(s: Long) {
            nowMs += s * 1000L
        }
    }

    @Test
    fun `UD-757 scan progress count_zero renders bare label`() {
        val reporter = CliProgressReporter()
        reporter.onScanProgress("remote", 0)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(output.contains("Scanning remote changes..."), "expected bare label; got: $output")
        assertFalse(output.contains("items"), "no items segment when count == 0; got: $output")
        assertFalse(output.contains("· "), "no separator when count == 0; got: $output")
    }

    @Test
    fun `UD-757 scan progress no history renders count and elapsed but no ETA`() {
        val clock = FakeClock()
        val reporter = CliProgressReporter(clock = { clock.nowMs })
        reporter.onScanProgress("remote", 0)
        clock.advanceSecs(18)
        reporter.onScanProgress("remote", 12_450)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        // Comma-grouped count + M:SS elapsed.
        assertTrue(output.contains("12,450 items"), "expected '12,450 items'; got: $output")
        assertTrue(output.contains(" · 0:18"), "expected ' · 0:18' elapsed; got: $output")
        assertFalse(output.contains("ETA"), "no ETA without historical hints; got: $output")
    }

    @Test
    fun `UD-757 scan progress with history on track renders count, elapsed, and ETA`() {
        val clock = FakeClock()
        val reporter = CliProgressReporter(clock = { clock.nowMs })
        // Last run: 100k items in 80s. Count-aware: at 12,450 (12.45%)
        // after 18s, estimatedTotal = 18 / 0.1245 ≈ 144s; remaining ≈ 126s
        // → ETA ≈ 2:06. Wall-clock: 80 - 18 = 62s → "1:02". Sanity clamp:
        // ratio = 126/62 ≈ 2.03 → within 0.25..4.0 → count-aware wins.
        // Pin the count-aware result.
        reporter.onScanHistoricalHint("remote", lastSecs = 80L)
        reporter.onScanCountHint("remote", lastCount = 100_000)
        reporter.onScanProgress("remote", 0)
        clock.advanceSecs(18)
        reporter.onScanProgress("remote", 12_450)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(output.contains("12,450 items"), "expected count; got: $output")
        assertTrue(output.contains(" · 0:18"), "expected elapsed 0:18; got: $output")
        assertTrue(output.contains(" · ETA "), "expected ETA segment; got: $output")
        // Count-aware estimate: 144 - 18 = 126s → 2:06.
        assertTrue(output.contains("ETA 2:06"), "expected ETA 2:06 (count-aware); got: $output")
    }

    @Test
    fun `UD-757 scan progress with history overrun clamps ETA to absent`() {
        // Last run took 60s; this run is at 90s and well past 5% of last
        // count (so the count-aware path also fires). Both produce
        // negative remaining → segment must be absent rather than "ETA 0:00".
        val clock = FakeClock()
        val reporter = CliProgressReporter(clock = { clock.nowMs })
        reporter.onScanHistoricalHint("remote", lastSecs = 60L)
        reporter.onScanCountHint("remote", lastCount = 1_000)
        reporter.onScanProgress("remote", 0)
        clock.advanceSecs(90)
        reporter.onScanProgress("remote", 1_500) // 150% of last → overrun
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        assertTrue(output.contains("1,500 items"), "expected count; got: $output")
        assertTrue(output.contains(" · 1:30"), "expected elapsed 1:30; got: $output")
        assertFalse(output.contains("ETA"), "ETA must be absent on overrun; got: $output")
    }

    @Test
    fun `UD-757 phase transition resets phaseStartMs so elapsed restarts at zero`() {
        val clock = FakeClock()
        val reporter = CliProgressReporter(clock = { clock.nowMs })
        // Remote phase: 0 → 30s → emit count.
        reporter.onScanProgress("remote", 0)
        clock.advanceSecs(30)
        reporter.onScanProgress("remote", 1_000)
        // Local phase starts now — must NOT inherit the remote phase's start time.
        reporter.onScanProgress("local", 0)
        clock.advanceSecs(5)
        reporter.onScanProgress("local", 200)
        reporter.onSyncComplete(0, 0, 0, 100L, emptyMap())
        val output = captured.toString(Charsets.UTF_8)
        // Remote line shows 0:30 elapsed, local line shows 0:05 elapsed.
        assertTrue(output.contains("Scanning remote changes...") && output.contains(" · 0:30"))
        assertTrue(output.contains("Scanning local files...") && output.contains(" · 0:05"))
    }

    @Test
    fun `UD-748 computeEtaSecs uses count-aware path when within sanity range`() {
        // Last run: 1000 items in 600s. This run: 500 items in 250s.
        // Count-aware: estimatedTotal = 250 / 0.5 = 500s; remaining = 250s.
        // Wall-clock: 600 - 250 = 350s. Ratio 250/350 ≈ 0.71 → count-aware wins.
        val reporter = CliProgressReporter()
        val result =
            reporter.computeEtaSecs(
                lastSecs = 600L,
                elapsedSecs = 250L,
                lastCount = 1000,
                currentCount = 500,
            )
        assertEquals(250L, result)
    }

    @Test
    fun `UD-748 computeEtaSecs falls back to wall-clock when count-aware diverges 4x`() {
        // Last run: 1000 items in 600s. This run: 500 items in 100s
        // (very fast pace). Count-aware: 100/0.5 = 200; remaining 100s.
        // Wall-clock: 600 - 100 = 500s. Ratio 100/500 = 0.2 → below 0.25
        // sanity floor → wall-clock wins.
        val reporter = CliProgressReporter()
        val result =
            reporter.computeEtaSecs(
                lastSecs = 600L,
                elapsedSecs = 100L,
                lastCount = 1000,
                currentCount = 500,
            )
        assertEquals(500L, result, "expected wall-clock fallback when count-aware diverges 4x+")
    }

    @Test
    fun `UD-748 computeEtaSecs suppresses count-aware path when progress under 5 percent`() {
        // Only 4% of last run's count → too unstable; fall back to
        // wall-clock-only ETA. lastSecs=600, elapsed=10 → 590s remaining.
        val reporter = CliProgressReporter()
        val result =
            reporter.computeEtaSecs(
                lastSecs = 600L,
                elapsedSecs = 10L,
                lastCount = 1000,
                currentCount = 40,
            )
        assertEquals(590L, result)
    }
}
