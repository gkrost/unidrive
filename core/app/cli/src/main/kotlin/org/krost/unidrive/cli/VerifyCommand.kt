package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.RemoteObjectAuditReport
import org.krost.unidrive.RemoteObjectStatus
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.Reconciler
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncScope
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable

/**
 * Independent convergence audit across the three sources of truth: the local
 * sync_root walk, the `state.db` rows, and a live provider listing. `status`
 * trusts `state.db`; this command exists precisely because the DB can lie, so
 * none of the three views is trusted over the others — each divergence class
 * in [VerifyReport] is one source contradicting another. Strictly read-only:
 * no row is written, no remote call mutates anything.
 *
 * Exit codes are the machine contract for soak crons: 0 converged, 1
 * divergence found, 2 could not audit (missing state.db / sync root,
 * authentication failure, provider listing error).
 */
@Command(
    name = "verify",
    description = [
        "Audit convergence of the local sync_root, state.db, and a live remote listing (report-only).",
        "Exit codes: 0 converged, 1 divergence found, 2 could not audit.",
    ],
    mixinStandardHelpOptions = true,
)
class VerifyCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    @Option(
        names = ["--deep"],
        description = [
            "Also hash local content with the provider's algorithm and compare against the remote hash " +
                "(skipped for providers without a verifiable hash)",
        ],
    )
    var deep: Boolean = false

    @Option(
        names = ["--sync-path"],
        description = [
            "Audit only a subtree, e.g. --sync-path /Documents. Repeatable. The remote walk starts " +
                "inside the scope instead of listing the whole drive (#532: 170 folder listings in 150 s).",
        ],
    )
    var syncPaths: MutableList<String> = mutableListOf()

    @Option(
        names = ["--remote-objects"],
        description = ["Audit provider-side object metadata and shard availability without downloading content (currently Internxt)."],
    )
    var remoteObjects: Boolean = false

    @Option(
        names = ["--json-report"],
        paramLabel = "FILE",
        description = ["Write the --remote-objects audit as an atomic JSON report to this path."],
    )
    var jsonReport: Path? = null

    override fun call(): Int {
        val profile = parent.resolveCurrentProfile()
        if (remoteObjects) return auditRemoteObjects(profile.name, jsonReport)
        if (jsonReport != null) {
            System.err.println("verify: --json-report requires --remote-objects")
            return EXIT_UNAUDITABLE
        }
        val configDir = parent.configBaseDir().resolve(profile.name)
        val dbPath = configDir.resolve("state.db")
        if (!Files.exists(dbPath)) {
            System.err.println("verify: no state.db for profile '${profile.name}' — run a sync first, then audit it.")
            return EXIT_UNAUDITABLE
        }
        val syncRoot = profile.syncRoot
        if (!Files.isDirectory(syncRoot)) {
            System.err.println("verify: sync root '$syncRoot' does not exist — cannot audit.")
            return EXIT_UNAUDITABLE
        }
        // The same exclude set the engine syncs with: anything the engine would
        // never transfer (sidecars, OS junk, user patterns) must not count as
        // divergence in any of the three views.
        val excludes = parent.loadSyncConfig().effectiveExcludePatterns(profile.name)
        // #532: all three views must be cut to the same boundary, or the audit reports
        // out-of-scope rows as db-only/remote-only divergences instead of ignoring them.
        val scope = SyncScope.normalize(syncPaths)

        // Read-only snapshot of the rows; closed before any other source is walked.
        val db = StateDatabase(dbPath)
        db.initialize()
        val dbEntries =
            try {
                db.getAllEntries().filter { scope.isEmpty() || SyncScope.contains(it.path, scope) }
            } finally {
                db.close()
            }

        val provider = parent.createProvider()
        val algorithm = if (deep) provider.hashAlgorithm() else null
        val remoteFiles: Map<String, CloudItem> =
            try {
                runBlocking {
                    provider.authenticateAndLog()
                    listRemoteFiles(provider, excludes, scope)
                }
            } catch (e: AuthenticationException) {
                System.err.print(
                    parent.renderAuthError(
                        e,
                        provider.id,
                        provider.displayName,
                        parent.verbose,
                        runCatching { parent.resolveCurrentProfile().name }.getOrNull(),
                    ),
                )
                return EXIT_UNAUDITABLE
            } catch (e: Exception) {
                System.err.println("verify: provider listing failed: ${e.message ?: e.javaClass.simpleName}")
                return EXIT_UNAUDITABLE
            } finally {
                provider.close()
            }
        if (deep && algorithm == null) {
            System.err.println(
                "verify: provider '${provider.displayName}' has no verifiable hash algorithm — " +
                    "--deep degrades to the size comparison.",
            )
        }

        val report =
            VerifyAudit.audit(
                localFiles =
                    walkLocalFiles(syncRoot, excludes).filterKeys { path ->
                        scope.isEmpty() || SyncScope.contains(path, scope)
                    },
                dbEntries = dbEntries.filterNot { isExcluded(it.path, excludes) },
                remoteFiles = remoteFiles.mapValues { it.value.size },
                hashMismatch = { path ->
                    val remoteHash = remoteFiles[path]?.hash
                    algorithm != null &&
                        !remoteHash.isNullOrEmpty() &&
                        !HashVerifier.verify(syncRoot.resolve(path.removePrefix("/")), remoteHash, algorithm)
                },
            )
        println(VerifyAudit.formatReport(profile.name, report))
        return if (report.converged) EXIT_CONVERGED else EXIT_DIVERGED
    }

    private fun auditRemoteObjects(
        profileName: String,
        reportPath: Path?,
    ): Int {
        if (reportPath == null) {
            System.err.println("verify: --remote-objects requires --json-report FILE so the audit is preserved")
            return EXIT_UNAUDITABLE
        }
        // A drive-long audit must not end at "could not write the report": prove the destination usable first.
        try {
            checkReportWritable(reportPath)
        } catch (e: Exception) {
            System.err.println("verify: cannot write the JSON report: ${e.message ?: e.javaClass.simpleName}")
            return EXIT_UNAUDITABLE
        }
        val excludes = parent.loadSyncConfig().effectiveExcludePatterns(profileName)
        val scope = SyncScope.normalize(syncPaths)
        val provider = parent.createProvider()
        val auditor = provider.remoteObjectAuditor
        if (auditor == null) {
            provider.close()
            System.err.println("verify: provider '${provider.id}' does not support read-only remote object audits")
            return EXIT_UNAUDITABLE
        }
        val report =
            try {
                runBlocking {
                    provider.authenticateAndLog()
                    val remoteFiles = listRemoteFiles(provider, excludes, scope).values.sortedBy { it.path }
                    auditor.auditRemoteObjects(remoteFiles)
                }
            } catch (e: AuthenticationException) {
                System.err.print(
                    parent.renderAuthError(
                        e,
                        provider.id,
                        provider.displayName,
                        parent.verbose,
                        profileName,
                    ),
                )
                return EXIT_UNAUDITABLE
            } catch (e: Exception) {
                System.err.println("verify: remote object audit failed: ${e.message ?: e.javaClass.simpleName}")
                return EXIT_UNAUDITABLE
            } finally {
                provider.close()
            }
        try {
            writeRemoteAuditReport(reportPath, profileName, provider.id, SyncScope.normalize(syncPaths), report)
        } catch (e: Exception) {
            System.err.println("verify: could not write JSON report: ${e.message ?: e.javaClass.simpleName}")
            return EXIT_UNAUDITABLE
        }
        val findings = report.objects.filter { it.status != RemoteObjectStatus.HEALTHY }
        println("Remote objects checked: ${report.checkedCount}")
        println("Findings:              ${findings.size}")
        println("JSON report:           ${reportPath.toAbsolutePath().normalize()}")
        for (finding in findings) {
            println("${finding.status.name.lowercase()} ${finding.path} drive=${finding.driveSize} bridge=${finding.bridgeSize} shards=${finding.shardBytes}")
        }
        return when {
            findings.any { it.status == RemoteObjectStatus.UNVERIFIED } -> EXIT_UNAUDITABLE
            findings.isNotEmpty() -> EXIT_DIVERGED
            else -> EXIT_CONVERGED
        }
    }

    /** Fails now, with the reason, when [reportPath] cannot take a report; leaves nothing behind but the folder. */
    private fun checkReportWritable(reportPath: Path) {
        val destination = reportPath.toAbsolutePath().normalize()
        if (Files.isDirectory(destination)) throw IOException("$destination is a directory")
        val parentDir = destination.parent ?: throw IOException("report path has no parent directory")
        Files.createDirectories(parentDir)
        Files.deleteIfExists(Files.createTempFile(parentDir, REPORT_TEMP_PREFIX, REPORT_TEMP_SUFFIX))
    }

    internal fun writeRemoteAuditReport(
        reportPath: Path,
        profileName: String,
        providerId: String,
        scope: List<String>,
        report: RemoteObjectAuditReport,
    ) {
        val destination = reportPath.toAbsolutePath().normalize()
        val parentDir = destination.parent ?: throw IOException("report path has no parent directory")
        Files.createDirectories(parentDir)
        val json = Json { prettyPrint = true }
        val body =
            buildJsonObject {
                put("schemaVersion", 1)
                put("profile", profileName)
                put("provider", providerId)
                putJsonArray("scope") {
                    (if (scope.isEmpty()) listOf("/") else scope).forEach { add(it) }
                }
                put("checkedCount", report.checkedCount)
                putJsonObject("summary") {
                    RemoteObjectStatus.entries.forEach { status ->
                        put(status.name.lowercase(), report.objects.count { it.status == status })
                    }
                }
                putJsonArray("objects") {
                    report.objects.forEach { item ->
                        add(
                            buildJsonObject {
                                put("path", item.path)
                                put("status", item.status.name.lowercase())
                                put("driveSize", item.driveSize)
                                item.bridgeSize?.let { put("bridgeSize", it) }
                                item.shardCount?.let { put("shardCount", it) }
                                item.availableShardCount?.let { put("availableShardCount", it) }
                                item.shardBytes?.let { put("shardBytes", it) }
                                item.detail?.let { put("detail", it) }
                            },
                        )
                    }
                }
            }
        val temporary = Files.createTempFile(parentDir, REPORT_TEMP_PREFIX, REPORT_TEMP_SUFFIX)
        try {
            // The report lists remote paths and sizes, which the engine keeps owner-only everywhere else
            // (StoragePermissions): restrict the file before it holds anything. The move keeps the permissions.
            val restricted = OwnerOnly.restrictFile(temporary)
            if (!restricted.restricted) {
                System.err.println("verify: warning: could not restrict the report to the current user ($restricted)")
            }
            Files.writeString(temporary, json.encodeToString(JsonObject.serializer(), body))
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun isExcluded(
        path: String,
        excludes: List<String>,
    ): Boolean = excludes.any { Reconciler.matchesGlob(path, it) }

    /**
     * Full remote enumeration via the provider's list API — a breadth-first
     * walk over [CloudProvider.listChildren], one call per
     * folder (each call returns that folder's complete child list; providers
     * paginate internally). Deliberately NOT the delta feed: the audit wants
     * the provider's *current* answer, independent of any cursor state the
     * engine maintains.
     *
     * With a non-empty [scope] the walk starts at the scope roots instead of
     * the drive root, so the call count follows the audited subtree rather
     * than the whole drive (#532).
     */
    private suspend fun listRemoteFiles(
        provider: CloudProvider,
        excludes: List<String>,
        scope: List<String>,
    ): Map<String, CloudItem> {
        val files = mutableMapOf<String, CloudItem>()
        val pending = ArrayDeque<String>()
        if (scope.isEmpty()) pending.add("/") else scope.forEach { pending.add(it) }
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            for (item in provider.listChildren(dir)) {
                val path = PathNormalizer.nfc(item.path)
                if (isExcluded(path, excludes)) continue
                if (item.isFolder) pending.add(item.path) else files[path] = item
            }
        }
        return files
    }

    /** Files-only walk keyed like state.db rows: NFC, '/'-separated, leading slash. */
    private fun walkLocalFiles(
        syncRoot: Path,
        excludes: List<String>,
    ): Map<String, Long> {
        val files = mutableMapOf<String, Long>()
        Files.walkFileTree(
            syncRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val relativePath = PathNormalizer.nfc("/" + syncRoot.relativize(file).toString().replace('\\', '/'))
                    if (!isExcluded(relativePath, excludes)) {
                        files[relativePath] = attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir == syncRoot) return FileVisitResult.CONTINUE
                    val relativePath = PathNormalizer.nfc("/" + syncRoot.relativize(dir).toString().replace('\\', '/'))
                    return if (isExcluded(relativePath, excludes)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                // Unreadable entries must not abort the audit; the path simply
                // isn't in the local view (same stance as LocalScanner UD-736).
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return files
    }

    companion object {
        const val EXIT_CONVERGED = 0
        const val EXIT_DIVERGED = 1
        const val EXIT_UNAUDITABLE = 2
        private const val REPORT_TEMP_PREFIX = ".unidrive-verify-"
        private const val REPORT_TEMP_SUFFIX = ".json.tmp"
    }
}
