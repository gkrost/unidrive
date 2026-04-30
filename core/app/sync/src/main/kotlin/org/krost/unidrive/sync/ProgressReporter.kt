package org.krost.unidrive.sync

interface ProgressReporter {
    fun onScanProgress(
        phase: String,
        count: Int,
    )

    fun onActionCount(total: Int)

    fun onActionProgress(
        index: Int,
        total: Int,
        action: String,
        path: String,
    )

    fun onTransferProgress(
        path: String,
        bytesTransferred: Long,
        totalBytes: Long,
    )

    fun onSyncComplete(
        downloaded: Int,
        uploaded: Int,
        conflicts: Int,
        durationMs: Long,
        actionCounts: Map<String, Int> = emptyMap(),
        // UD-745: count of actions that hit a non-recoverable failure during
        // the sync (Pass 1 + Pass 2 combined). Surfaced in the summary so
        // users know to re-run when work remains. Default 0 keeps existing
        // implementers / call sites source-compatible.
        failed: Int = 0,
    )

    fun onWarning(message: String)

    object Silent : ProgressReporter {
        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(total: Int) {}

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {}

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {}

        override fun onWarning(message: String) {}
    }
}
