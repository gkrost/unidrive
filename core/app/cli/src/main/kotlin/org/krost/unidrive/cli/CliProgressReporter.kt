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

    // UD-408: track per-path last-emit time so onTransferProgress doesn't spam
    // hundreds of lines for a multi-GB upload (now that printInline lands a
    // fresh scrollback line per call instead of overwriting in place).
    private val lastTransferEmit = mutableMapOf<String, Long>()

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
        println("Reconciled: $total actions")
    }

    override fun onActionProgress(
        index: Int,
        total: Int,
        action: String,
        path: String,
    ) {
        actionStartTime = System.currentTimeMillis()
        // UD-408: clear per-path throttle state on action start so the next
        // transfer-progress emission for this path lands immediately.
        lastTransferEmit.remove(path)
        printInline("[$index/$total] $action $path")
    }

    override fun onTransferProgress(
        path: String,
        bytesTransferred: Long,
        totalBytes: Long,
    ) {
        val now = System.currentTimeMillis()
        // 5-second initial silence — most uploads finish faster and don't need a progress line at all.
        if (now - actionStartTime < 5000) return
        // UD-408: throttle to one line per second per path. Without this,
        // a multi-GB upload would emit hundreds of progress lines (printInline
        // no longer overwrites in place, so each call is a fresh scrollback row).
        val last = lastTransferEmit[path] ?: 0L
        val isFinal = totalBytes > 0 && bytesTransferred >= totalBytes
        if (!isFinal && now - last < 1000) return
        lastTransferEmit[path] = now
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
        System.err.println("  WARN: $message")
    }

    // UD-408: commitInline is no longer needed — printInline now lands a real
    // line per call (no mid-line cursor state to commit). The companion-stub
    // is left so any external caller that referenced it still compiles, but
    // the body is a no-op.
    @Suppress("UNUSED")
    private fun commitInline() {
        // intentionally empty — see UD-408
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

    // UD-408: emit a real scrollback line per progress event. Was: in-place rewrite via
    // `\r\u001b[K` + `truncateForWidth(msg, cols-1)` (UD-735 / UD-742). The rewrite +
    // truncation kept the terminal "tidy" but ate the per-action history and clipped long
    // paths. Each call now lands a full untruncated line - tail -f-style. Throttling for
    // chatty paths (transfer progress) lives at the call site (see [onTransferProgress]).
    private fun printInline(msg: String) {
        println(msg)
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

        // UD-408: terminalWidth() + truncateForWidth() (UD-735) deleted with the in-place
        // rewrite they served. Per-line scrollback output makes width detection moot;
        // the terminal handles wrapping. If a future feature genuinely needs the column
        // count, restore from the UD-735 commit history.
    }
}
