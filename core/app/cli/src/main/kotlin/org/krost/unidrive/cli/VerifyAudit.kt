package org.krost.unidrive.cli

import org.krost.unidrive.sync.model.SyncEntry

/**
 * Pure result of one convergence audit. Kept separate from [VerifyCommand]
 * (mirroring the [StatusAudit] pattern) so the classification is trivially
 * unit-testable against synthesised inputs — no DB, no provider, no walk.
 *
 * All paths are sync_root-relative with a leading slash, the same key the
 * three sources are reduced to before classification. v1 audits files only;
 * folders are structural and contribute nothing here.
 */
data class VerifyReport(
    val localFileCount: Int,
    val dbFileRowCount: Int,
    val remoteFileCount: Int,
    /** Present locally, absent from the remote listing. */
    val localOnly: List<String>,
    /** Present on the remote, absent from the local tree. */
    val remoteOnly: List<String>,
    /** DB row with neither a local file nor a remote item — an orphan row. */
    val rowOnly: List<String>,
    /**
     * Present on both sides but with differing size (or hash, under --deep) —
     * only for paths expected to hold real bytes (untracked, or isHydrated=true).
     * A non-hydrated placeholder is smaller on disk by design and is excluded.
     */
    val contentMismatch: List<String>,
    /** Row claims isHydrated=true but the local file is missing or zero bytes. */
    val hydrationMismatch: List<String>,
) {
    val converged: Boolean
        get() =
            localOnly.isEmpty() && remoteOnly.isEmpty() && rowOnly.isEmpty() &&
                contentMismatch.isEmpty() && hydrationMismatch.isEmpty()
}

object VerifyAudit {
    /**
     * Classify the divergence between the three independently-gathered views.
     * None of them is trusted over the others — every class is a claim one
     * source makes that another contradicts.
     *
     * [hashMismatch] is the --deep probe: given a path present in BOTH
     * [localFiles] and [remoteFiles], return true when the local content hash
     * does not match the remote hash. It is only consulted where local content
     * is expected to be real bytes (untracked path pairs, or rows claiming
     * isHydrated=true) — a sparse placeholder's content is NULs by design and
     * must not fail the probe. The default never reports a mismatch, which is
     * also the correct degradation when the provider has no verifiable hash.
     */
    fun audit(
        localFiles: Map<String, Long>,
        dbEntries: List<SyncEntry>,
        remoteFiles: Map<String, Long>,
        hashMismatch: (String) -> Boolean = { false },
    ): VerifyReport {
        val dbFiles = dbEntries.filter { !it.isFolder }.associateBy { it.path }
        val contentMismatch =
            (localFiles.keys intersect remoteFiles.keys)
                .filter { path ->
                    val row = dbFiles[path]
                    // A non-hydrated placeholder is a 0-byte (or sparse) stub on
                    // disk by design — its size and content legitimately differ
                    // from the remote, so neither the size nor the hash probe may
                    // fire. The "claims hydrated but isn't" case is hydrationMismatch.
                    val realBytesExpected = row == null || row.isHydrated
                    realBytesExpected &&
                        (localFiles.getValue(path) != remoteFiles.getValue(path) || hashMismatch(path))
                }.sorted()
        val hydrationMismatch =
            dbFiles.values
                .filter { it.isHydrated }
                .filter { row ->
                    val localSize = localFiles[row.path]
                    localSize == null || (localSize == 0L && row.remoteSize > 0L)
                }.map { it.path }
                .sorted()
        return VerifyReport(
            localFileCount = localFiles.size,
            dbFileRowCount = dbFiles.size,
            remoteFileCount = remoteFiles.size,
            localOnly = localFiles.keys.filter { it !in remoteFiles }.sorted(),
            remoteOnly = remoteFiles.keys.filter { it !in localFiles }.sorted(),
            rowOnly = dbFiles.keys.filter { it !in localFiles && it !in remoteFiles }.sorted(),
            contentMismatch = contentMismatch,
            hydrationMismatch = hydrationMismatch,
        )
    }

    /**
     * One line per divergence, then a fixed-shape summary block. Layout follows
     * [StatusAudit.formatAuditReport]: label column padded to a constant width
     * so the path column lines up for grep/awk consumers in a soak cron.
     */
    fun formatReport(
        profileName: String,
        report: VerifyReport,
    ): String {
        val classes =
            listOf(
                "local-only" to report.localOnly,
                "remote-only" to report.remoteOnly,
                "row-only" to report.rowOnly,
                "content-mismatch" to report.contentMismatch,
                "hydration-claim" to report.hydrationMismatch,
            )
        val lines = mutableListOf<String>()
        for ((label, paths) in classes) {
            for (path in paths) lines.add("%-17s %s".format(label, path))
        }
        if (lines.isNotEmpty()) lines.add("")
        lines.add("Profile:          $profileName")
        lines.add("Local files:      ${report.localFileCount}")
        lines.add("DB file rows:     ${report.dbFileRowCount}")
        lines.add("Remote files:     ${report.remoteFileCount}")
        lines.add(
            if (report.converged) {
                "Result:           converged — local tree, state.db, and remote listing agree"
            } else {
                "Result:           divergence found (" +
                    classes.joinToString(", ") { (label, paths) -> "$label ${paths.size}" } + ")"
            },
        )
        return lines.joinToString("\n")
    }
}
