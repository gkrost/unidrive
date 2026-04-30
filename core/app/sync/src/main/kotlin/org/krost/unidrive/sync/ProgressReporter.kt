package org.krost.unidrive.sync

interface ProgressReporter {
    fun onScanProgress(
        phase: String,
        count: Int,
    )

    /**
     * UD-747 (UD-744 slice): hand the reporter the wall-clock seconds the
     * matching phase took on the previous run, if known. Reporters that
     * surface ETA (CliProgressReporter) use this to compute
     * `etaSecs = max(0, lastSecs - elapsedSecs)` and bucket it for the
     * heartbeat. First-run / `--reset` scans don't fire this method, so
     * the ETA suffix is silently absent until persistence catches up.
     *
     * Default no-op so reporters that don't render ETA (IPC, Notify,
     * Silent, test shells) need no update.
     */
    fun onScanHistoricalHint(
        phase: String,
        lastSecs: Long,
    ) {}

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
