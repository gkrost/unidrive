package org.krost.unidrive.e2e.scenarios

import org.krost.unidrive.e2e.RunContext
import org.krost.unidrive.e2e.UnidriveRunner
import org.krost.unidrive.e2e.verify.HashVerifier
import org.krost.unidrive.e2e.verify.ManifestReader
import org.krost.unidrive.e2e.verify.ReportEntry
import org.krost.unidrive.e2e.verify.ReportFailure
import org.krost.unidrive.e2e.verify.ReportSkip
import org.krost.unidrive.e2e.verify.VerifyResult
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer

class CrossBridgeRunner(
    private val ctx: RunContext,
    private val unidrive: UnidriveRunner,
    private val manifestPath: Path,
) {
    fun run(): List<ReportEntry> {
        val reports = mutableListOf<ReportEntry>()

        // Phase 1: Sync existing remote structure via UniDrive
        val syncStart = System.currentTimeMillis()
        unidrive.sync(ctx.provider)
        val syncDuration = System.currentTimeMillis() - syncStart
        reports.add(makeReport("sync", "PASS", syncDuration))

        // Phase 2: Full hash verify against manifest (from previous Win11 CloudForge run)
        val verifyStart = System.currentTimeMillis()
        val manifestEntries = ManifestReader.read(manifestPath)
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
            run_id = ctx.runId, scenario = "CrossBridge", provider = ctx.provider,
            os = System.getProperty("os.name"), phase = "verify_full", status = verifyStatus,
            duration_ms = verifyDuration, total = outcomes.size, pass = passed, fail = failed, skip = skipped,
            failures = outcomes.filter { it.status == VerifyResult.FAIL }
                .map { ReportFailure(it.path, it.reason ?: "") },
            skips = outcomes.filter { it.status == VerifyResult.SKIP || it.status == VerifyResult.MISSING }
                .map { ReportSkip(it.path, it.reason ?: "") },
        ))

        // Phase 3: NFC/NFD checks and byte-length checks
        val normStart = System.currentTimeMillis()
        val normFailures = mutableListOf<ReportFailure>()
        var normChecked = 0

        manifestEntries.forEach { entry ->
            val localFile = ctx.syncRoot.resolve(ctx.sandboxRelative).resolve(entry.path)
            if (!Files.exists(localFile)) return@forEach
            normChecked++

            val fileName = localFile.fileName.toString()

            // Verify filename is in NFC form (ext4 stores as-is; verify unidrive normalises)
            val nfc = Normalizer.normalize(fileName, Normalizer.Form.NFC)
            if (fileName != nfc) {
                normFailures.add(ReportFailure(entry.path, "filename not NFC: got $fileName, expected $nfc"))
            }

            // Byte-length check: ext4 limit is 255 bytes per component
            val byteLen = fileName.toByteArray(Charsets.UTF_8).size
            if (byteLen > 255) {
                normFailures.add(ReportFailure(entry.path, "filename byte length $byteLen exceeds 255"))
            }
        }

        val normDuration = System.currentTimeMillis() - normStart
        val normStatus = if (normFailures.isEmpty()) "PASS" else "FAIL"
        reports.add(ReportEntry(
            run_id = ctx.runId, scenario = "CrossBridge", provider = ctx.provider,
            os = System.getProperty("os.name"), phase = "verify_encoding", status = normStatus,
            duration_ms = normDuration, total = normChecked, pass = normChecked - normFailures.size,
            fail = normFailures.size, failures = normFailures,
        ))

        return reports
    }

    private fun makeReport(phase: String, status: String, durationMs: Long) = ReportEntry(
        run_id = ctx.runId, scenario = "CrossBridge", provider = ctx.provider,
        os = System.getProperty("os.name"), phase = phase, status = status,
        duration_ms = durationMs,
    )
}
