package org.krost.unidrive.e2e.scenarios

import org.krost.unidrive.e2e.RunContext
import org.krost.unidrive.e2e.UnidriveRunner
import org.krost.unidrive.e2e.playwright.CloudProviderPage
import org.krost.unidrive.e2e.verify.ReportEntry
import org.krost.unidrive.e2e.verify.ReportFailure
import java.nio.file.Files

class MirrorDriftRunner(
    private val ctx: RunContext,
    private val cloudPage: CloudProviderPage,
    private val unidrive: UnidriveRunner,
) {
    fun run(): List<ReportEntry> {
        val reports = mutableListOf<ReportEntry>()
        val localBase = ctx.syncRoot.resolve(ctx.sandboxRelative)

        // Phase 1: Baseline sync (assumes golden structure already exists remotely)
        val baseStart = System.currentTimeMillis()
        unidrive.sync(ctx.provider)
        val baseDuration = System.currentTimeMillis() - baseStart
        reports.add(makeReport("sync_baseline", "PASS", baseDuration))

        // Phase 2: Apply local changes
        val localConflict = localBase.resolve("Documents").let { d ->
            Files.list(d).use { s -> s.filter { Files.isRegularFile(it) }.findFirst().orElse(null) }
        }
        val localNew = localBase.resolve("local_only_new_file.txt")
        val localDeleteTarget = localBase.resolve("Documents").let { d ->
            Files.list(d).use { s -> s.filter { Files.isRegularFile(it) }.skip(1).findFirst().orElse(null) }
        }
        val localRenameFolder = localBase.resolve("Documents")
        val localRenamedFolder = localBase.resolve("Documents_renamed")

        if (localConflict != null) {
            Files.writeString(localConflict, "local conflict edit — ${ctx.runId}\n")
        }
        Files.writeString(localNew, "local-only new file — ${ctx.runId}\n")
        if (localDeleteTarget != null) {
            Files.deleteIfExists(localDeleteTarget)
        }
        if (Files.exists(localRenameFolder)) {
            Files.move(localRenameFolder, localRenamedFolder)
        }

        // Phase 3: Apply remote changes via Playwright
        cloudPage.login()
        val remoteConflictPath = localConflict?.let {
            "${ctx.sandboxRelative}/Documents/${it.fileName}"
        }
        val remoteNewPath = "${ctx.sandboxRelative}/remote_only_new_file.txt"
        val remoteDeletePath = localDeleteTarget?.let {
            "${ctx.sandboxRelative}/Documents/${it.fileName}"
        }

        if (remoteConflictPath != null) {
            val folder = remoteConflictPath.substringBeforeLast('/')
            cloudPage.navigateToFolder(folder)
            // Overwrite by uploading a temp file with the same name
            val tmp = Files.createTempFile("mirror-drift-conflict", ".txt")
            Files.writeString(tmp, "remote conflict edit — ${ctx.runId}\n")
            try {
                cloudPage.uploadFile(tmp)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

        val remoteNewTmp = Files.createTempFile("mirror-drift-remote-new", ".txt")
        Files.writeString(remoteNewTmp, "remote-only new file — ${ctx.runId}\n")
        try {
            cloudPage.navigateToFolder(ctx.sandboxRelative)
            cloudPage.uploadFile(remoteNewTmp)
        } finally {
            Files.deleteIfExists(remoteNewTmp)
        }

        if (remoteDeletePath != null) {
            // The file was already deleted locally; skip remote delete to avoid double-action
        }

        // Phase 4: Sync (bidirectional reconcile)
        val syncStart = System.currentTimeMillis()
        unidrive.sync(ctx.provider)
        val syncDuration = System.currentTimeMillis() - syncStart
        reports.add(makeReport("sync_reconcile", "PASS", syncDuration))

        // Phase 5: Verify expected post-conflict state
        val verifyStart = System.currentTimeMillis()
        val failures = mutableListOf<ReportFailure>()

        // local-only new file should still exist (or be uploaded)
        if (!Files.exists(localNew)) {
            failures.add(ReportFailure("local_only_new_file.txt", "missing after sync"))
        }

        // renamed folder should persist
        if (!Files.exists(localRenamedFolder)) {
            failures.add(ReportFailure("Documents_renamed", "renamed folder missing after sync"))
        }

        // remote-only new file should have been downloaded
        val remoteNewLocal = localBase.resolve("remote_only_new_file.txt")
        if (!Files.exists(remoteNewLocal)) {
            failures.add(ReportFailure("remote_only_new_file.txt", "remote-only file not synced locally"))
        }

        val verifyDuration = System.currentTimeMillis() - verifyStart
        val verifyStatus = if (failures.isEmpty()) "PASS" else "FAIL"
        reports.add(ReportEntry(
            run_id = ctx.runId, scenario = "MirrorDrift", provider = ctx.provider,
            os = System.getProperty("os.name"), phase = "verify_conflicts", status = verifyStatus,
            duration_ms = verifyDuration, total = 3, pass = 3 - failures.size, fail = failures.size,
            failures = failures,
        ))

        return reports
    }

    private fun makeReport(phase: String, status: String, durationMs: Long) = ReportEntry(
        run_id = ctx.runId, scenario = "MirrorDrift", provider = ctx.provider,
        os = System.getProperty("os.name"), phase = phase, status = status,
        duration_ms = durationMs,
    )
}
