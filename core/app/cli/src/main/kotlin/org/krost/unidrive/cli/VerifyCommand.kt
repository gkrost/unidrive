package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.Reconciler
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncScope
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
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
        arity = "1..*",
    )
    var syncPaths: MutableList<String> = mutableListOf()

    override fun call(): Int {
        val profile = parent.resolveCurrentProfile()
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
    }
}
