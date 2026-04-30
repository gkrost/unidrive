package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProgressReporter
import java.util.Locale

class CliProgressReporter(
    private val verbose: Boolean = false,
    private val dryRun: Boolean = false,
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
        val now = System.currentTimeMillis()
        if (count == 0) {
            scanStartTimes[phase] = now
            printInline("Scanning $phase changes...")
        } else {
            val start = scanStartTimes[phase] ?: now
            val elapsedSecs = (now - start) / 1000
            // UD-742: heartbeat overwrites in-place via printInline so a 113k
            // remote scan emits 2k+ updates without scrolling the terminal.
            // The final fire from SyncEngine after the loop has the same
            // shape; the next phase's println will commit a newline first.
            //
            // UD-713: append items/min throughput once we've collected enough
            // signal (>= 5s elapsed AND >= 100 items). Full bucketed ETA
            // depends on knowing the *total* upfront, which neither LocalScanner
            // nor most remote-delta APIs expose without a separate count probe;
            // throughput alone is the "should I go to lunch?" signal.
            val rateSuffix = formatThroughput(count, elapsedSecs)
            printInline("Scanning $phase changes... $count items (${formatElapsed(elapsedSecs)} elapsed$rateSuffix)")
        }
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

    // UD-742: compact human-readable elapsed seconds for scan heartbeats —
    // "47s", "5m 12s", "1h 23m". Drops the seconds field at hour granularity
    // since by then the user only cares about whole minutes.
    private fun formatElapsed(totalSecs: Long): String =
        when {
            totalSecs < 60 -> "${totalSecs}s"
            totalSecs < 3600 -> "${totalSecs / 60}m ${totalSecs % 60}s"
            else -> "${totalSecs / 3600}h ${(totalSecs / 60) % 60}m"
        }

    // UD-713: items-per-minute throughput suffix for scan heartbeats. Returns
    // ", ~N items/min" once we have a stable signal (>= 5s elapsed AND >= 100
    // items observed), or "" otherwise. The leading comma+space lets callers
    // append directly inside the existing parenthesised metadata.
    private fun formatThroughput(
        items: Int,
        elapsedSecs: Long,
    ): String {
        if (elapsedSecs < 5 || items < 100) return ""
        val perMin = (items.toLong() * 60) / elapsedSecs
        return ", ~$perMin items/min"
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
