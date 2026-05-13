package org.krost.unidrive.e2e.scenarios

import org.krost.unidrive.e2e.DaemonManager
import org.krost.unidrive.e2e.GoldenStructureBuilder
import org.krost.unidrive.e2e.RunContext
import org.krost.unidrive.e2e.UnidriveRunner
import org.krost.unidrive.e2e.verify.HashVerifier
import org.krost.unidrive.e2e.verify.ManifestEntry
import org.krost.unidrive.e2e.verify.ManifestReader
import org.krost.unidrive.e2e.verify.ManifestWriter
import org.krost.unidrive.e2e.verify.ReportEntry
import org.krost.unidrive.e2e.verify.ReportFailure
import org.krost.unidrive.e2e.verify.ReportSkip
import org.krost.unidrive.e2e.verify.ReportWriter
import org.krost.unidrive.e2e.verify.VerifyOutcome
import org.krost.unidrive.e2e.verify.VerifyResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Implements the fully automated 7-phase GroundTruth test sequence:
 * 1. Setup (daemon stop, rogue process check, temp config, golden structure)
 * 2. Upload (sync local → cloud)
 * 3. Delete + Reset (delete local, clear state.db)
 * 4. Re-sync (placeholders)
 * 5. Hydrate sample
 * 6. Verify (hash/size check)
 * 7. Cleanup + Report
 */
class GroundTruthRunner(
    private val ctx: RunContext,
    private val unidrive: UnidriveRunner,
    private val daemonManager: DaemonManager = DaemonManager(),
) {

    /**
     * Runs the complete 7-phase GroundTruth sequence.
     * @return list of report entries for each phase
     */
    fun run(): List<ReportEntry> {
        val reports = mutableListOf<ReportEntry>()
        var tempConfigDir: Path? = null
        var daemonWasRunning = false
        val startTime = System.currentTimeMillis()

        try {
            // PHASE 1: SETUP
            val phase1Start = System.currentTimeMillis()
            daemonWasRunning = daemonManager.stopDaemon()
            
            // Detect rogue processes
            val roguePids = daemonManager.detectRunningProcesses()
            if (roguePids.isNotEmpty()) {
                throw IllegalStateException(
                    "Rogue uniDrive processes detected: ${roguePids.joinToString(",")}. " +
                            "Please stop them manually before running the test."
                )
            }

            // Generate run ID
            val runId = ctx.runId

            // Create temp config dir
            tempConfigDir = Files.createTempDirectory("unidrive-360-state-$runId")
            val providerDir = tempConfigDir.resolve(ctx.provider)
            Files.createDirectories(providerDir)

            // Write a minimal config.toml with the correct sync root for this environment.
            // We don't symlink the host's config.toml because the sync_root may differ
            // (e.g. Docker container with /sync vs host's ~/Internxt-Test).
            val knownTypes = setOf("onedrive", "hidrive", "internxt", "rclone", "s3", "sftp", "webdav")
            val configContent = if (ctx.provider in knownTypes) {
                // UD-802: bare provider type — synthesize a minimal `[providers.<type>]`
                // block. `Main.resolveCurrentProfile()` exits with "config missing" if
                // it sees an empty providers map, so the previous "skip config.toml"
                // branch was unreachable past Phase 1. For env-var-credentialed
                // providers (s3/sftp/webdav/rclone) we inline the credentials from
                // env vars matching the factory's envVarMappings() — same shape as
                // core/docker/test-providers.sh's write_config().
                Companion.buildBareProviderConfig(ctx.provider, ctx.syncRoot)
            } else {
                // Named profile — extract full section from host config, override sync_root
                val realConfigToml = ctx.baseConfigDir.resolve("config.toml")
                val hostConfig = if (Files.exists(realConfigToml)) Files.readString(realConfigToml) else ""
                val sectionHeader = "[providers.${ctx.provider}]"
                val sectionStart = hostConfig.indexOf(sectionHeader)
                if (sectionStart < 0) throw IllegalStateException("Profile '${ctx.provider}' not found in config.toml")
                // Extract until next [section] or end of file
                val afterHeader = hostConfig.substring(sectionStart)
                val nextSection = afterHeader.indexOf("\n  [", 1).let { if (it < 0) afterHeader.indexOf("\n[", 1) else it }
                val section = if (nextSection > 0) afterHeader.substring(0, nextSection) else afterHeader
                // Replace sync_root with test sync root
                val fixedSection = section.replace(Regex("""sync_root\s*=\s*"[^"]*""""), "sync_root = \"${ctx.syncRoot}\"")
                fixedSection + "\n"
            }
            if (configContent.isNotEmpty()) {
                Files.writeString(tempConfigDir.resolve("config.toml"), configContent)
            }

            // Symlink xtra.key if it exists (for X-tra encrypted profiles)
            val realXtraKey = ctx.baseConfigDir.resolve("xtra.key")
            if (Files.exists(realXtraKey)) {
                Files.createSymbolicLink(tempConfigDir.resolve("xtra.key"), realXtraKey)
            }

            // Symlink credential files from real config to temp config
            val realProviderDir = ctx.baseConfigDir.resolve(ctx.provider)
            val credentialFiles = listOf("token.json", "credentials.json")
            var linkedAny = false
            for (credFile in credentialFiles) {
                val realPath = realProviderDir.resolve(credFile)
                if (Files.exists(realPath)) {
                    Files.createSymbolicLink(providerDir.resolve(credFile), realPath)
                    linkedAny = true
                }
            }
            // Providers like SFTP/S3/WebDAV store credentials in config.toml — no separate files needed

            // Generate golden structure into sync root
            val localBase = ctx.syncRoot.resolve(ctx.sandboxRelative)
            Files.createDirectories(localBase)
            val builder = GoldenStructureBuilder(ctx)
            val manifest = builder.build(localBase)

            // Backup manifest to temp dir
            val tempManifestPath = tempConfigDir.resolve("manifest.sha3-512.jsonl")
            ManifestWriter.write(tempManifestPath, manifest)

            reports.add(makeReport("setup", "PASS", System.currentTimeMillis() - phase1Start, manifest.size))

            // PHASE 2: UPLOAD (scoped to sandbox subtree)
            val phase2Start = System.currentTimeMillis()
            val syncResult = unidrive.run(
                "-c", tempConfigDir.toString(),
                "-p", ctx.provider,
                "sync", "--sync-path", "/${ctx.sandboxRelative}"
            )
            if (syncResult.exitCode != 0) {
                throw RuntimeException(
                    "Upload sync failed (exit ${syncResult.exitCode}): ${syncResult.stderr}"
                )
            }
            reports.add(makeReport("upload", "PASS", System.currentTimeMillis() - phase2Start, manifest.size))

            // PHASE 3: DELETE + RESET
            val phase3Start = System.currentTimeMillis()
            // Delete local golden structure
            Files.walk(localBase)
                .sorted(Comparator.reverseOrder())
                .filter { it != localBase }
                .forEach { Files.deleteIfExists(it) }
            
            // Delete temp state.db to force fresh delta
            val tempStateDb = providerDir.resolve("state.db")
            if (Files.exists(tempStateDb)) {
                Files.delete(tempStateDb)
            }
            
            reports.add(makeReport("delete_reset", "PASS", System.currentTimeMillis() - phase3Start, manifest.size))

            // PHASE 4: RE-SYNC (PLACEHOLDERS, scoped to sandbox)
            val phase4Start = System.currentTimeMillis()
            val syncDownResult = unidrive.run(
                "-c", tempConfigDir.toString(),
                "-p", ctx.provider,
                "sync", "--sync-path", "/${ctx.sandboxRelative}"
            )
            if (syncDownResult.exitCode != 0) {
                throw RuntimeException(
                    "Re-sync failed (exit ${syncDownResult.exitCode}): ${syncDownResult.stderr}"
                )
            }
            
            // Verify all manifest entries exist as sparse placeholders
            var placeholderOk = true
            val placeholderIssues = mutableListOf<String>()
            val placeholderSkips = mutableListOf<String>()
            for (entry in manifest) {
                val localFile = localBase.resolve(entry.path)
                if (!Files.exists(localFile)) {
                    if (entry.size == 0L) {
                        // Zero-byte files may not be supported by encrypted providers (e.g. Internxt)
                        placeholderSkips.add("zero-byte not on remote: ${entry.path}")
                    } else {
                        placeholderOk = false
                        placeholderIssues.add("missing: ${entry.path}")
                    }
                } else if (entry.size > 0 && !isSparse(localFile)) {
                    // Zero-byte files are trivially complete — skip sparse check
                    placeholderOk = false
                    val size = Files.size(localFile)
                    placeholderIssues.add("not sparse: ${entry.path} (size=$size, blocks!=0)")
                }
            }
            
            if (!placeholderOk) {
                throw IllegalStateException(
                    "Placeholder validation failed: ${placeholderIssues.joinToString("; ")}"
                )
            }
            if (placeholderSkips.isNotEmpty()) {
                System.err.println("Placeholder skips: ${placeholderSkips.joinToString("; ")}")
            }
            
            reports.add(makeReport("resync_placeholders", "PASS", System.currentTimeMillis() - phase4Start, manifest.size))

            // PHASE 5: HYDRATE SAMPLE
            val phase5Start = System.currentTimeMillis()
            val manifestEntries = ManifestReader.read(tempManifestPath)
            val hydratableEntries = manifestEntries.filter { it.size > 0 }
            val sampleSize = max(
                ctx.config.general.min_sample,
                min(hydratableEntries.size * ctx.config.general.sample_percentage / 100, ctx.config.general.max_sample),
            )
            val sample = hydratableEntries.shuffled(Random(ctx.runId.hashCode())).take(sampleSize)

            val hydrateResults = mutableListOf<VerifyOutcome>()
            for (entry in sample) {
                val remotePath = "${ctx.sandboxRelative}/${entry.path}"
                val getResult = unidrive.run(
                    "-c", tempConfigDir.toString(),
                    "-p", ctx.provider,
                    "get", remotePath
                )
                if (getResult.exitCode != 0) {
                    throw RuntimeException(
                        "Get failed for $remotePath (exit ${getResult.exitCode}): ${getResult.stderr}"
                    )
                }
                
                val localFile = localBase.resolve(entry.path)
                val outcome = HashVerifier.verifyFile(localFile, entry)
                hydrateResults.add(outcome)
            }

            reports.add(makeReport(
                "hydrate_sample",
                if (hydrateResults.none { it.status == VerifyResult.FAIL }) "PASS" else "FAIL",
                System.currentTimeMillis() - phase5Start,
                hydrateResults.size
            ))

            // PHASE 6: VERIFY
            val phase6Start = System.currentTimeMillis()
            val verifyResults = mutableListOf<VerifyOutcome>()
            
            // Check hydrated files
            for (outcome in hydrateResults) {
                verifyResults.add(outcome)
            }
            
            // Check placeholders (size only)
            for (entry in manifest) {
                if (!sample.contains(entry)) { // Not hydrated
                    val localFile = localBase.resolve(entry.path)
                    if (!Files.exists(localFile)) {
                        if (entry.size == 0L) {
                            // Zero-byte files may not exist on encrypted providers — skip
                            verifyResults.add(VerifyOutcome(entry.path, VerifyResult.SKIP,
                                "zero-byte file not on remote"))
                        } else {
                            verifyResults.add(VerifyOutcome(entry.path, VerifyResult.FAIL,
                                "file missing after re-sync"))
                        }
                    } else {
                        val localSize = Files.size(localFile)
                        // X-tra encrypted files have overhead (header + GCM tag), so remote size > plaintext size.
                        // Accept if sizes match exactly, or if remote is larger (encrypted overhead).
                        val sizeMatch = localSize == entry.size || localSize > entry.size
                        val result = if (sizeMatch) {
                            VerifyOutcome(entry.path, VerifyResult.PASS)
                        } else {
                            VerifyOutcome(entry.path, VerifyResult.FAIL,
                                "size mismatch: expected ${entry.size}, got $localSize")
                        }
                        verifyResults.add(result)
                    }
                }
            }

            reports.add(makeReport(
                "verify",
                if (verifyResults.none { it.status == VerifyResult.FAIL }) "PASS" else "FAIL",
                System.currentTimeMillis() - phase6Start,
                verifyResults.size
            ))

            // PHASE 7: CLEANUP + REPORT
            // UD-803: write the JSONL report to a sibling `<localBase>-reports/`
            // directory that the cleanup walk does not touch, so a successful
            // run with cleanup_local_after_run = true leaves the artifact
            // available for inspection at a predictable path.
            val phase7Start = System.currentTimeMillis()
            val reportsDir = localBase.resolveSibling("${localBase.fileName}-reports")
            Files.createDirectories(reportsDir)
            val jsonlReportPath = reportsDir.resolve("report.jsonl")

            // Add all phase reports to JSONL
            for (report in reports) {
                ReportWriter.append(jsonlReportPath, report.copy(
                    run_id = ctx.runId,
                    scenario = "GroundTruth",
                    provider = ctx.provider
                ))
            }

            // Cleanup local if configured. Bounded to localBase itself; the
            // sibling reports/ directory created above is intentionally out
            // of the walk root (UD-803).
            if (ctx.config.run.cleanup_local_after_run) {
                Files.walk(localBase)
                    .sorted(Comparator.reverseOrder())
                    .filter { it != localBase }
                    .forEach { Files.deleteIfExists(it) }
            }
            
            reports.add(makeReport(
                "cleanup_report",
                "PASS",
                System.currentTimeMillis() - phase7Start,
                reports.size
            ))
            
            return reports
            
        } catch (e: Exception) {
            // Error handling: skip to cleanup on any phase failure
            val errorPhase = when (reports.size) {
                0 -> "setup"
                1 -> "upload"
                2 -> "delete_reset"
                3 -> "resync_placeholders"
                4 -> "hydrate_sample"
                5 -> "verify"
                else -> "cleanup_report"
            }
            
            reports.add(makeReport(
                "$errorPhase (ERROR)",
                "FAIL",
                System.currentTimeMillis() - if (reports.isEmpty()) 0 else 
                    reports.last().duration_ms ?: 0,
                0,
                failures = listOf(ReportFailure("phase_$errorPhase", e.message ?: "Unknown error"))
            ))
            
            throw e  // Re-throw so caller knows test failed
        } finally {
            // Guaranteed cleanup: temp config dir and daemon restart
            if (tempConfigDir != null && Files.exists(tempConfigDir)) {
                // Remove symlinks first to avoid deleting real credentials
                val provDir = tempConfigDir.resolve(ctx.provider)
                if (Files.isDirectory(provDir)) {
                    Files.list(provDir).use { stream ->
                        stream.filter { Files.isSymbolicLink(it) }.forEach { Files.delete(it) }
                    }
                }
                deleteRecursively(tempConfigDir)
            }
            
            // Restart daemon if it was running originally
            if (daemonWasRunning) {
                daemonManager.startDaemon()
            }
        }
    }

    /**
     * Checks if a file is sparse (allocated blocks == 0) and has size > 0.
     * Uses stat --format=%b to get block count.
     */
    private fun isSparse(file: Path): Boolean {
        return try {
            val proc = ProcessBuilder("stat", "--format=%b", file.toString()).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val blocks = output.toLongOrNull() ?: return false
            val size = Files.size(file)
            return blocks == 0L && size > 0
        } catch (e: Exception) {
            false  // Assume not sparse on error
        }
    }

    /**
     * Recursively deletes a directory and its contents.
     */
    private fun deleteRecursively(dir: Path) {
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .forEach { 
                try { 
                    Files.deleteIfExists(it) 
                } catch (e: Exception) {
                    // Best effort cleanup
                }
            }
    }

    /**
     * Creates a report entry for a phase.
     */
    private fun makeReport(
        phase: String,
        status: String,
        durationMs: Long,
        total: Int,
        failures: List<ReportFailure> = emptyList(),
        skips: List<ReportSkip> = emptyList()
    ): ReportEntry {
        return ReportEntry(
            run_id = ctx.runId,
            scenario = "GroundTruth",
            provider = ctx.provider,
            os = System.getProperty("os.name"),
            phase = phase,
            status = status,
            duration_ms = durationMs,
            total = total,
            pass = if (status == "PASS") total else 0,
            fail = if (status == "FAIL") total else 0,
            skip = 0,
            failures = failures,
            skips = skips
        )
    }

    companion object {
        /**
         * UD-802: synthesize a minimal `config.toml` for a bare provider type
         * (e.g. `groundtruth -p s3`).
         *
         * The child unidrive process exits early with "config missing" if it
         * sees an empty `[providers.*]` map (see `Main.resolveCurrentProfile`
         * in core/app/cli). For env-var-credentialled providers
         * (s3/sftp/webdav/rclone) we also need to inline the credentials so
         * that the factory's `create()` call sees non-blank `properties`.
         * For OAuth-only providers (onedrive, hidrive, internxt) the
         * type+sync_root row is enough — the token is picked up from the
         * symlinked `token.json` later in Phase 1.
         *
         * Throws [IllegalStateException] when required env vars are missing,
         * so a stale invocation fails fast in Phase 1 instead of producing a
         * confusing downstream `ConfigurationException`.
         *
         * [env] is an indirection seam for tests; production callers pass
         * `System::getenv` (the default).
         */
        internal fun buildBareProviderConfig(
            providerType: String,
            syncRoot: Path,
            env: (String) -> String? = System::getenv,
        ): String {
            fun envOrFail(name: String): String =
                env(name)
                    ?: throw IllegalStateException(
                        "UD-802: groundtruth -p $providerType requires env var '$name'. " +
                            "Set it or pass a named profile (-p <profileName>) instead."
                    )
            fun envOr(name: String, default: String): String = env(name) ?: default
            val syncRootQuoted = syncRoot.toString().replace("\\", "\\\\")
            return when (providerType) {
                "s3" -> {
                    val bucket = envOrFail("S3_BUCKET")
                    val accessKey = envOrFail("AWS_ACCESS_KEY_ID")
                    val secretKey = envOrFail("AWS_SECRET_ACCESS_KEY")
                    val region = envOr("S3_REGION", "auto")
                    val endpoint = envOr("S3_ENDPOINT", "https://s3.amazonaws.com")
                    """
                    [providers.s3]
                    type = "s3"
                    sync_root = "$syncRootQuoted"
                    bucket = "$bucket"
                    region = "$region"
                    endpoint = "$endpoint"
                    access_key_id = "$accessKey"
                    secret_access_key = "$secretKey"
                    """.trimIndent() + "\n"
                }
                "sftp" -> {
                    val host = envOrFail("SFTP_HOST")
                    val user = envOr("SFTP_USER", System.getProperty("user.name") ?: "root")
                    val password = env("SFTP_PASSWORD")
                    buildString {
                        appendLine("[providers.sftp]")
                        appendLine("type = \"sftp\"")
                        appendLine("sync_root = \"$syncRootQuoted\"")
                        appendLine("host = \"$host\"")
                        appendLine("user = \"$user\"")
                        if (password != null) appendLine("password = \"$password\"")
                    }
                }
                "webdav" -> {
                    val url = envOrFail("WEBDAV_URL")
                    val user = envOrFail("WEBDAV_USER")
                    val password = envOrFail("WEBDAV_PASSWORD")
                    """
                    [providers.webdav]
                    type = "webdav"
                    sync_root = "$syncRootQuoted"
                    url = "$url"
                    user = "$user"
                    password = "$password"
                    """.trimIndent() + "\n"
                }
                "rclone" -> {
                    val remote = envOrFail("RCLONE_REMOTE")
                    """
                    [providers.rclone]
                    type = "rclone"
                    sync_root = "$syncRootQuoted"
                    rclone_remote = "$remote"
                    """.trimIndent() + "\n"
                }
                "onedrive", "hidrive", "internxt" -> {
                    // OAuth providers: credentials come from token.json
                    // symlinked into the per-profile dir in Phase 1, not from
                    // env vars.
                    """
                    [providers.$providerType]
                    type = "$providerType"
                    sync_root = "$syncRootQuoted"
                    """.trimIndent() + "\n"
                }
                else -> throw IllegalStateException(
                    "UD-802: bare provider type '$providerType' not recognised by " +
                        "buildBareProviderConfig. Pass a named profile (-p <profileName>) instead."
                )
            }
        }
    }

}