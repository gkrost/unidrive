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
        ) {}

        override fun onWarning(message: String) {}
    }
}
