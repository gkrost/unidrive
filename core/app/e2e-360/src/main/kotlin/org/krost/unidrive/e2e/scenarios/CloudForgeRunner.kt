package org.krost.unidrive.e2e.scenarios

import org.krost.unidrive.e2e.GoldenStructureBuilder
import org.krost.unidrive.e2e.RunContext
import org.krost.unidrive.e2e.UnidriveRunner
import org.krost.unidrive.e2e.playwright.CloudProviderPage
import org.krost.unidrive.e2e.playwright.SpotChecker
import org.krost.unidrive.e2e.verify.HashVerifier
import org.krost.unidrive.e2e.verify.ManifestReader
import org.krost.unidrive.e2e.verify.ReportEntry
import org.krost.unidrive.e2e.verify.ReportFailure
import org.krost.unidrive.e2e.verify.ReportSkip
import org.krost.unidrive.e2e.verify.VerifyResult
import java.nio.file.Files
import java.nio.file.Path

class CloudForgeRunner(
    private val ctx: RunContext,
    private val cloudPage: CloudProviderPage,
    private val unidrive: UnidriveRunner,
    private val headed: Boolean = false,
) {
    fun run(): List<ReportEntry> {
        val reports = mutableListOf<ReportEntry>()

        // Phase 1: Generate Golden Structure locally (temp dir)
        val goldenDir = ctx.goldenDir
        val builder = GoldenStructureBuilder(ctx)
        val manifest = builder.build(goldenDir)

        // Phase 2: Upload via Playwright
        val uploadStart = System.currentTimeMillis()
        cloudPage.login()
        uploadToCloud(cloudPage, goldenDir, ctx.sandboxRelative)
        val uploadDuration = System.currentTimeMillis() - uploadStart
        reports.add(makeReport("upload", "PASS", uploadDuration, manifest.size))

        // Phase 3: Spot check via Playwright
        val spotStart = System.currentTimeMillis()
        val spotChecker = SpotChecker(
            cloudPage,
            ctx.config.general.sample_percentage,
            ctx.config.general.min_sample,
            ctx.config.general.max_sample,
        )
        val spotResult = spotChecker.check(manifest, ctx.sandboxRelative)
        val spotDuration = System.currentTimeMillis() - spotStart
        val spotStatus = if (spotResult.missing.isEmpty()) "PASS" else "FAIL"
        reports.add(makeReport("verify_spot", spotStatus, spotDuration, spotResult.checked))

        // Phase 4: UniDrive sync
        val syncStart = System.currentTimeMillis()
        unidrive.sync(ctx.provider)
        val syncDuration = System.currentTimeMillis() - syncStart
        reports.add(makeReport("sync", "PASS", syncDuration, manifest.size))

        // Phase 5: Full hash verification
        val verifyStart = System.currentTimeMillis()
        val manifestEntries = ManifestReader.read(goldenDir.resolve("manifest.sha3-512.jsonl"))
        val outcomes = manifestEntries.map { entry ->
            val localFile = ctx.syncRoot.resolve(ctx.sandboxRelative).resolve(entry.path)
            HashVerifier.verifyFile(localFile, entry)
        }
        val verifyDuration = System.currentTimeMillis() - verifyStart
        val passed = outcomes.count { it.status == VerifyResult.PASS }
        val failed = outcomes.count { it.status == VerifyResult.FAIL }
        val skipped = outcomes.count { it.status == VerifyResult.SKIP || it.status == VerifyResult.MISSING }
        val verifyStatus = if (failed == 0) "PASS" else "FAIL"
        reports.add(ReportEntry(
            run_id = ctx.runId, scenario = "CloudForge", provider = ctx.provider,
            os = System.getProperty("os.name"), phase = "verify_full", status = verifyStatus,
            duration_ms = verifyDuration, total = outcomes.size, pass = passed, fail = failed, skip = skipped,
            failures = outcomes.filter { it.status == VerifyResult.FAIL }
                .map { ReportFailure(it.path, it.reason ?: "") },
            skips = outcomes.filter { it.status == VerifyResult.SKIP || it.status == VerifyResult.MISSING }
                .map { ReportSkip(it.path, it.reason ?: "") },
        ))

        return reports
    }

    private fun uploadToCloud(page: CloudProviderPage, sourceDir: Path, remotePath: String) {
        page.navigateToFolder("")
        for (segment in remotePath.split("/").filter { it.isNotBlank() }) {
            page.createFolder(segment)
            page.navigateToFolder(segment)
        }
        uploadDir(page, sourceDir, remotePath)
    }

    private fun uploadDir(page: CloudProviderPage, localDir: Path, remotePath: String) {
        Files.list(localDir).use { stream ->
            for (entry in stream.sorted()) {
                if (entry.fileName.toString() == "manifest.sha3-512.jsonl") continue
                if (Files.isDirectory(entry)) {
                    val folderName = entry.fileName.toString()
                    page.navigateToFolder(remotePath)
                    page.createFolder(folderName)
                    uploadDir(page, entry, "$remotePath/$folderName")
                } else {
                    page.navigateToFolder(remotePath)
                    page.uploadFile(entry)
                    Thread.sleep(ctx.config.general.upload_delay_ms)
                }
            }
        }
    }

    private fun makeReport(phase: String, status: String, durationMs: Long, total: Int) = ReportEntry(
        run_id = ctx.runId, scenario = "CloudForge", provider = ctx.provider,
        os = System.getProperty("os.name"), phase = phase, status = status,
        duration_ms = durationMs, total = total,
    )
}
