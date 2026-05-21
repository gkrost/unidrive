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
     * Hand the reporter the cursor that THIS scan will be using as its
     * "modified since" filter, plus a flag indicating whether the prior
     * scan that produced it ran to completion. Surface this in the
     * scan-start label so the user can see at a glance what window of
     * remote state is being polled, and whether the cursor is fresher
     * (last run complete) or pinned (last run incomplete — typically
     * because a folder 503'd into the fallback path).
     *
     * `cursor` is the raw ISO-8601 string written to `delta_cursor`;
     * null means no prior cursor exists (first sync / `--reset`).
     * `complete` mirrors `pending_cursor_complete` — true when the last
     * gather pass that wrote a cursor finished cleanly; false when it
     * ended on a `complete=false` page and the active cursor wasn't
     * refreshed.
     *
     * Default no-op — reporters that don't surface scan labels (IPC,
     * Notify, Silent, test shells) need no update.
     */
    fun onScanCursorHint(
        phase: String,
        cursor: String?,
        complete: Boolean = true,
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

    /**
     * UD-201: post-reconcile action count, with optional pre-filter total
     * and a filter-reason label.
     *
     * - [total] — actions the executor will actually run (post directional
     *   filter, post `--upload-only` / `--download-only` drop).
     * - [preFilterTotal] — actions the reconciler decided on (pre-filter).
     *   Defaults to [total] for source compatibility with reporters that
     *   don't care about the divergence.
     * - [filterReason] — short human label naming the filter that dropped
     *   actions (e.g. `--upload-only`). Null when no filter ran or when
     *   the filter dropped zero actions.
     *
     * Reporters that surface the summary (CLI) should render a divergent
     * "reconciled vs executed" split when `preFilterTotal != total`, and
     * a single-line summary when they're equal.
     */
    fun onActionCount(
        total: Int,
        preFilterTotal: Int = total,
        filterReason: String? = null,
    )

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

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {}

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
