package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProgressReporter
import java.util.Locale

class CliProgressReporter(
    private val verbose: Boolean = false,
    private val dryRun: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProgressReporter {
    var lastDownloaded = 0
        private set
    var lastUploaded = 0
        private set
    var lastConflicts = 0
        private set

    private var actionStartTime = 0L
    private val isTty = System.console() != null

    // UD-742: track when each scan phase started so heartbeat lines can show
    // elapsed wall-clock. Cleared as each phase begins (count == 0).
    private val scanStartTimes = mutableMapOf<String, Long>()

    // UD-747 (UD-744 slice): historical wall-clock seconds for each scan
    // phase, populated by SyncEngine via onScanHistoricalHint at phase
    // start when state.db has a previous timing recorded. Empty on first
    // sync / `--reset`; the ETA suffix is suppressed in that case.
    private val historicalScanSecs = mutableMapOf<String, Long>()

    // UD-748 (UD-744 slice 2): historical *item count* for each scan
    // phase from the previous run, populated via onScanCountHint. Used
    // jointly with historicalScanSecs to compute a progress-fraction-
    // based ETA that adapts to actual run speed. Optional — the
    // wall-clock-only path (UD-747) is the fallback.
    private val historicalScanCount = mutableMapOf<String, Int>()

    // UD-742 / UD-735: when printInline emitted text and left the cursor
    // mid-line (no trailing newline), any subsequent println-using method
    // would glue its output to the inline tail (e.g. observed
    // ".mp4Dry-run: ..." in the user's terminal). Track the inline-active
    // state and commit a newline before the next non-printInline output.
    private var inlineActive = false

    override fun onScanProgress(
        phase: String,
        count: Int,
    ) {
        val now = clock()
        if (count == 0) {
            scanStartTimes[phase] = now
            printInline("Scanning ${phaseLabel(phase)}...")
        } else {
            val start = scanStartTimes[phase] ?: now
            val elapsedSecs = (now - start) / 1000
            // UD-757: render `<count> items · <elapsed> · ETA <eta>`.
            // Comma-grouped count, M:SS / H:MM:SS times. ETA is suppressed
            // when historical hints are absent (first sync / `--reset`),
            // when the math says the run has already overrun last time, or
            // when count == 0 (handled above as the bare-label baseline).
            //
            // The ETA math is the bucket helper from UD-747/UD-748 — count-
            // aware extrapolation when both hints exist and progress >= 5%
            // of last run, otherwise wall-clock subtraction. The bucket
            // boundaries from the previous renderer are dropped: UD-757
            // prefers a concrete time string over `<5m / 5-15m / 15-60m /
            // >1h` so the user can read the trend run-over-run.
            val countStr = formatCount(count)
            val elapsedStr = formatTime(elapsedSecs)
            val etaSecs =
                computeEtaSecs(
                    lastSecs = historicalScanSecs[phase],
                    elapsedSecs = elapsedSecs,
                    lastCount = historicalScanCount[phase],
                    currentCount = count,
                )
            val etaSuffix = if (etaSecs != null && etaSecs > 0) " · ETA ${formatTime(etaSecs)}" else ""
            printInline("Scanning ${phaseLabel(phase)}... $countStr items · $elapsedStr$etaSuffix")
        }
    }

    // UD-240g: track reconcile-phase start to render `0:XX` elapsed in the
    // heartbeat line, matching the [onScanProgress] visual. Set on the first
    // (count == 0) tick from [Reconciler.reconcile].
    private var reconcileStart: Long = 0

    override fun onReconcileProgress(
        processed: Int,
        total: Int,
    ) {
        val now = clock()
        if (processed == 0) {
            reconcileStart = now
            printInline("Reconciling... 0 / $total items")
            return
        }
        val elapsedSecs = (now - reconcileStart) / 1000
        val processedStr = formatCount(processed)
        val totalStr = formatCount(total)
        val elapsedStr = formatTime(elapsedSecs)
        printInline("Reconciling... $processedStr / $totalStr items · $elapsedStr")
    }

    private fun phaseLabel(phase: String): String =
        when (phase) {
            "remote" -> "remote changes"
            "local" -> "local files"
            else -> "$phase changes"
        }

    override fun onScanHistoricalHint(
        phase: String,
        lastSecs: Long,
    ) {
        if (lastSecs > 0) historicalScanSecs[phase] = lastSecs
    }

    override fun onScanCountHint(
        phase: String,
        lastCount: Int,
    ) {
        if (lastCount > 0) historicalScanCount[phase] = lastCount
    }

    override fun onActionCount(total: Int) {
        commitInline()
        println("Reconciled: $total actions")
    }

    override fun onActionProgress(
        index: Int,
        total: Int,
        action: String,
        path: String,
    ) {
        actionStartTime = System.currentTimeMillis()
        printInline("[$index/$total] $action $path")
    }

    override fun onTransferProgress(
        path: String,
        bytesTransferred: Long,
        totalBytes: Long,
    ) {
        if (System.currentTimeMillis() - actionStartTime < 5000) return
        val pct = if (totalBytes > 0) (bytesTransferred * 100 / totalBytes) else 0
        val transferred = formatSize(bytesTransferred)
        val total = formatSize(totalBytes)
        printInline("  $transferred / $total  ($pct%)")
    }

    override fun onSyncComplete(
        downloaded: Int,
        uploaded: Int,
        conflicts: Int,
        durationMs: Long,
        actionCounts: Map<String, Int>,
        failed: Int,
    ) {
        lastDownloaded = downloaded
        lastUploaded = uploaded
        lastConflicts = conflicts
        // UD-204: Locale.ROOT so "1.5s" stays stable across locales.
        val secs = String.format(Locale.ROOT, "%.1f", durationMs / 1000.0)
        commitInline()
        if (dryRun) {
            val parts = mutableListOf("download $downloaded", "upload $uploaded")
            val extras =
                actionCounts
                    .filterKeys { it !in setOf("down", "up", "CONFLICT") }
                    .filter { it.value > 0 }
            for ((label, count) in extras) {
                parts.add("$count $label")
            }
            parts.add("$conflicts conflicts")
            println("Dry-run: would ${parts.joinToString(", ")} (${secs}s)")
        } else {
            // UD-745: include failed count when non-zero so users see "230
            // failed" and know to re-run rather than walk away assuming
            // the sync completed cleanly. Suppressed at zero for clean-run
            // brevity.
            val failedSegment = if (failed > 0) ", $failed failed" else ""
            println(
                "Sync complete: $downloaded downloaded, $uploaded uploaded, $conflicts conflicts$failedSegment (${secs}s)",
            )
        }
    }

    override fun onWarning(message: String) {
        commitInline()
        System.err.println("  WARN: $message")
    }

    // UD-742: emit a newline iff a printInline call is mid-flight, so the
    // next println starts on a fresh line instead of gluing onto the
    // overwrite buffer.
    private fun commitInline() {
        if (inlineActive) {
            println()
            inlineActive = false
        }
    }

    // UD-757: format M:SS for under one hour, H:MM:SS above. Shared by both
    // the elapsed and ETA segments of the scan-progress line so the two
    // numbers are visually parallel ("0:18 · ETA 1:02").
    internal fun formatTime(totalSecs: Long): String {
        val s = if (totalSecs < 0) 0L else totalSecs
        val secs = s % 60
        val mins = (s / 60) % 60
        val hours = s / 3600
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d", mins, secs)
        }
    }

    // UD-757: comma-grouped item count ("12,450 items"). Locale.ROOT so the
    // separator is stable across en-US / de-DE / fr-FR (de uses '.' as
    // thousands separator, fr uses NBSP — neither is parser-friendly).
    internal fun formatCount(count: Int): String = String.format(Locale.ROOT, "%,d", count)

    /**
     * UD-747 / UD-748 (UD-744 slices) → UD-757: ETA-in-seconds for the
     * scan-progress line. Renderer formats this with [formatTime].
     *
     * Two paths:
     *
     * - **Wall-clock-only (UD-747).** When only [lastSecs] is known,
     *   `remainingSecs = lastSecs - elapsedSecs`. Robust but blind to
     *   the actual progress speed of THIS run.
     *
     * - **Count-aware (UD-748).** When both [lastSecs] and [lastCount]
     *   exist AND the current run has accumulated >= 5% of last run's
     *   count, derive the ETA from progress fraction:
     *       progressFraction  = currentCount / lastCount
     *       estimatedTotalSec = elapsedSecs / progressFraction
     *       remainingSecs     = estimatedTotalSec - elapsedSecs
     *   Sanity clamp: if the count-based estimate diverges by > 4x from
     *   `lastSecs - elapsedSecs`, fall back to the wall-clock path —
     *   the run isn't following last run's shape and we shouldn't
     *   overcommit either way.
     *
     * Returns `null` (rendered as "no ETA segment") when:
     *   - [lastSecs] is null/zero/negative (first run, no foundation), or
     *   - the chosen estimate's remainingSecs <= 0 (overrun — don't lie).
     */
    internal fun computeEtaSecs(
        lastSecs: Long?,
        elapsedSecs: Long,
        lastCount: Int? = null,
        currentCount: Int = 0,
    ): Long? {
        if (lastSecs == null || lastSecs <= 0) return null
        val wallClockRemaining = lastSecs - elapsedSecs

        val countAwareRemaining: Long? =
            if (
                lastCount != null &&
                lastCount > 0 &&
                currentCount > 0 &&
                currentCount.toDouble() / lastCount >= 0.05 &&
                elapsedSecs >= 1
            ) {
                val progressFraction = currentCount.toDouble() / lastCount
                val estimatedTotalSec = (elapsedSecs / progressFraction).toLong()
                (estimatedTotalSec - elapsedSecs).coerceAtLeast(0)
            } else {
                null
            }

        val remainingSecs =
            if (countAwareRemaining != null && wallClockRemaining > 0) {
                val ratio = countAwareRemaining.toDouble() / wallClockRemaining
                if (ratio in 0.25..4.0) countAwareRemaining else wallClockRemaining
            } else {
                countAwareRemaining ?: wallClockRemaining
            }

        return if (remainingSecs <= 0) null else remainingSecs
    }

    private val cols: Int = terminalWidth()

    private fun printInline(msg: String) {
        if (isTty) {
            // UD-735: ANSI clear-to-end only clears the current console line.
            // When a long line wraps to a new row, the previous (longer) tail
            // stays visible above. Truncate to fit before printing so each
            // overwrite stays on a single physical row.
            val displayed = truncateForWidth(msg, cols - 1)
            print("\r\u001b[K$displayed")
            inlineActive = true
        } else {
            println(msg)
        }
    }

    companion object {
        // UD-238: math is binary (divisor 2^30), so label binary (IEC 80000-13: KiB/MiB/GiB).
        // Labelling these "GB" under-reported decimal-SI values by ~7%; see UD-238 for the
        // cross-channel evidence against OneDrive + Internxt.
        fun formatSize(bytes: Long): String =
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
                else -> "${bytes / (1024 * 1024 * 1024)} GiB"
            }

        // UD-735: detect terminal width. Java 21 has no public API for it; fall
        // back to the COLUMNS env var (set by most Unix shells and PowerShell 7+)
        // and default to 80 cols if neither is available.
        internal fun terminalWidth(): Int = System.getenv("COLUMNS")?.toIntOrNull()?.coerceAtLeast(20) ?: 80

        // UD-735: truncate "[N/M] verb /path/to/file" lines so that the whole
        // line fits in [maxLen] characters. Strategy: keep the [N/M] verb
        // prefix intact, then "..." + tail of the path — the filename matters
        // most. We anchor on " /" (space-slash) so [N/M]'s internal slash in
        // the index doesn't get mistaken for the path start. Falls back to
        // head-truncate when there's no path or the prefix exceeds the budget.
        internal fun truncateForWidth(
            msg: String,
            maxLen: Int,
        ): String {
            if (maxLen <= 0) return ""
            if (msg.length <= maxLen) return msg
            val ellipsis = "..."
            val pathStart = msg.indexOf(" /").let { if (it >= 0) it + 1 else -1 }
            if (pathStart <= 0 || pathStart >= maxLen - ellipsis.length - 1) {
                return msg.take(maxLen - ellipsis.length) + ellipsis
            }
            val prefix = msg.substring(0, pathStart)
            val tailLen = maxLen - prefix.length - ellipsis.length
            return prefix + ellipsis + msg.takeLast(tailLen)
        }
    }
}
