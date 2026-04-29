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

    override fun onScanProgress(
        phase: String,
        count: Int,
    ) {
        if (count == 0) {
            printInline("Scanning $phase changes...")
        } else {
            println("Scanning $phase changes... $count items")
        }
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
            println("Sync complete: $downloaded downloaded, $uploaded uploaded, $conflicts conflicts (${secs}s)")
        }
    }

    override fun onWarning(message: String) {
        System.err.println("  WARN: $message")
    }

    private fun printInline(msg: String) {
        if (isTty) {
            print("\r\u001b[K$msg")
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
    }
}
