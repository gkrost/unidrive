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

    /**
     * UD-748 (UD-744 slice 2): companion to [onScanHistoricalHint] —
     * the previous run's *final item count* for this phase. With both
     * hints, the reporter can compute a progress-fraction-based ETA
     * (`elapsed / (count / lastCount)`) that adapts to how fast THIS
     * run is going, not just last run's wall-clock. Falls back to the
     * UD-747 time-only path when count data is missing or progress
     * fraction is too low to be stable.
     *
     * Default no-op for parity with [onScanHistoricalHint].
     */
    fun onScanCountHint(
        phase: String,
        lastCount: Int,
    ) {}

    /**
     * UD-240g: progress heartbeat for the reconcile phase. Fired by
     * [Reconciler.reconcile] every 5k iterations / 10s wall-clock (see
     * [ScanHeartbeat]) so the CLI / IPC clients show movement instead of
     * looking hung. On a 67k-local + 19k-remote first-sync the pre-fix
     * reconcile was many seconds of total silence; this event closes the
     * UX gap symmetric to [onScanProgress].
     *
     * `processed` is the number of (remote ∪ local) paths walked so far;
     * `total` is the final size of that union (set once at start, stable
     * for the rest of the pass). Both maxed out signals the phase is done.
     *
     * Default no-op so reporters that don't render reconcile progress
     * (Notify, Silent, test shells) need no update.
     */
    fun onReconcileProgress(
        processed: Int,
        total: Int,
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
