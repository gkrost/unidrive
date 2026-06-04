package org.krost.unidrive.tracking

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.cli.ext.CliExtension
import org.krost.unidrive.cli.ext.CliExtensionRegistrar
import org.krost.unidrive.cli.ext.CliServices
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path

/**
 * CLI surface for the tracking-set engine.
 *
 * Settled on `unidrive ts <sub>` (sync, claim, unclaim, status) rather
 * than overloading `unidrive sync --tracking-set` because:
 *
 *  - A top-level `ts` namespace keeps the two engines clearly separable
 *    in `unidrive --help`, so operators A/B testing them aren't
 *    surprised by a flag that quietly switches engines.
 *  - The non-sync verbs (`claim`, `unclaim`, `status`) need their own
 *    subcommand slots regardless; piggybacking on `sync` would force
 *    weird flag combinations.
 *
 * Wired via the existing [CliExtension] / ServiceLoader machinery — no
 * edits to `Main.kt`. The service registration lives in
 * `META-INF/services/org.krost.unidrive.cli.ext.CliExtension`.
 */
class TrackingCliExtension : CliExtension {
    override val id = "tracking-set"

    override fun register(registrar: CliExtensionRegistrar) {
        val ts = TsCommand()
        ts.servicesRef = registrar.services
        registrar.addSubcommand("", ts)
    }
}

/**
 * The `unidrive ts` parent command. Subcommands handle the actual work;
 * a bare `ts` prints help.
 */
@Command(
    name = "ts",
    description = [
        "Tracking-set sync engine (experimental — not yet the default engine).",
        "Subcommands: sync, claim, unclaim, status.",
    ],
    mixinStandardHelpOptions = true,
    subcommands = [TsSyncCommand::class, TsClaimCommand::class, TsUnclaimCommand::class, TsStatusCommand::class],
)
class TsCommand : Runnable {
    /**
     * The extension passes [CliServices] in via [TrackingCliExtension.register].
     * Picocli does not pass arbitrary objects to @ParentCommand children, so
     * each subcommand reaches back through `@ParentCommand` to get this.
     */
    @JvmField
    var servicesRef: CliServices? = null

    override fun run() {
        println("usage: unidrive ts <sync|claim|unclaim|status> [options]")
        println("  See 'unidrive ts --help' for full output.")
    }
}

/**
 * Resolve the per-profile config dir, profile name, sync_root, and
 * `tracking.db` path. Kept in one place so every subcommand uses the
 * same paths.
 */
internal data class TsContext(
    val services: CliServices,
    val profileName: String,
    val configDir: Path,
    val syncRoot: Path,
    val trackingDb: Path,
    val excludePatterns: List<String>,
) {
    companion object {
        fun build(
            servicesRef: CliServices?,
            profileOpt: String?,
        ): TsContext {
            val services =
                servicesRef
                    ?: error("Tracking-set extension was not initialised with CliServices — internal wiring bug")
            val profileName =
                profileOpt
                    ?: services.listProfileNames().firstOrNull()
                    ?: error("No profiles configured. Run `unidrive profile add <type> <name>` first.")
            val configDir = services.configBaseDir().resolve(profileName)
            val syncConfig = services.loadSyncConfig(profileName)
            val syncRoot = syncConfig.syncRoot
            // tracking.db lives next to (but distinct from) the existing state.db.
            val trackingDb = configDir.resolve("tracking.db")
            return TsContext(
                services,
                profileName,
                configDir,
                syncRoot,
                trackingDb,
                syncConfig.effectiveExcludePatterns(profileName),
            )
        }
    }
}

@Command(name = "sync", description = ["Run one tracking-set sync pass."])
class TsSyncCommand : Runnable {
    @ParentCommand
    lateinit var parent: TsCommand

    @Option(names = ["-p", "--profile"], description = ["Profile name (defaults to the first configured profile)"])
    var profile: String? = null

    @Option(names = ["--dry-run"], description = ["Print the plan but do not apply"])
    var dryRun: Boolean = false

    @Option(
        names = ["--max-delete-ratio"],
        description = ["Batch-guard max delete fraction (0.0..1.0, default 0.5)"],
    )
    var maxDeleteRatio: Double = 0.5

    @Option(
        names = ["--max-delete-absolute"],
        description = ["Batch-guard max absolute delete count (default 50)"],
    )
    var maxDeleteAbsolute: Int = 50

    @Option(
        names = ["--auto-match"],
        description = [
            "Opt-in adoption strategy for hashless providers (e.g. Internxt).",
            "When the remote returns no content hash, adoption is normally",
            "suppressed and a collision is surfaced instead — the safe default.",
            "Use this flag only when you are confident the local and remote",
            "copies are identical and the noise from collisions is not useful.",
            "  off  (default) — always surface a collision when hashes are absent.",
            "  size — adopt when both hashes are null and file sizes are equal.",
            "  name — adopt when both hashes are null and the path matches",
            "          (the weakest match; use only on a clean first-scan).",
        ],
    )
    var autoMatchCli: String = "off"

    override fun run() {
        System.err.println(
            "EXPERIMENTAL: tracking-set engine is not yet the default sync engine. " +
                "Use --dry-run first.",
        )
        val autoMatch =
            when (autoMatchCli.lowercase()) {
                "off", "" -> AutoMatchMode.OFF
                "size" -> AutoMatchMode.SIZE
                "name" -> AutoMatchMode.NAME
                else ->
                    throw picocli.CommandLine.ParameterException(
                        picocli.CommandLine(TsSyncCommand()),
                        "--auto-match must be one of off|size|name; got '$autoMatchCli'",
                    )
            }
        val ctx = TsContext.build(parent.servicesRef, profile)
        Files.createDirectories(ctx.configDir)
        val tracking = SqliteTrackingSet(ctx.trackingDb)
        tracking.initialize()
        try {
            val provider = ctx.services.createProvider(ctx.profileName)
            runBlocking { provider.authenticateAndLog() }
            val engine =
                TrackingEngine(
                    provider = provider,
                    trackingSet = tracking,
                    syncRoot = ctx.syncRoot,
                    batchGuard = BatchGuard(maxDeleteRatio, maxDeleteAbsolute),
                    dryRun = dryRun,
                    excludePatterns = ctx.excludePatterns,
                    autoMatch = autoMatch,
                )
            val report = engine.syncOnce()
            renderReport(report, dryRun)
        } finally {
            tracking.close()
        }
    }

    private fun renderReport(
        report: PassReport,
        dryRun: Boolean,
    ) {
        val mode = if (dryRun) "[dry-run] " else ""
        println("${mode}Tracking-set sync pass:")
        println("  adopted:    ${report.adopted.size}")
        println("  plan:       ${report.plan.size} action(s)")
        for (a in report.plan) {
            println("    - ${labelOf(a)} ${a.path}")
        }
        if (report.collisions.isNotEmpty()) {
            println("  collisions: ${report.collisions.size}")
            for (c in report.collisions) {
                println("    ! ${c.path}: ${c.reason}")
            }
            println("  Resolve with: unidrive ts claim <path>")
        }
        val verdict = report.guardVerdict
        if (verdict is BatchGuard.Verdict.Deny) {
            println("  ${verdict.describe()}")
        }
        if (!dryRun) {
            val success = report.applied.count { it.outcome == ApplyOutcome.SUCCESS }
            val failed = report.applied.count { it.outcome == ApplyOutcome.FAILED }
            val skipped = report.applied.count { it.outcome == ApplyOutcome.SKIPPED }
            println("  applied:    $success ok, $failed failed, $skipped skipped")
        }
    }

    private fun labelOf(a: ReconcileAction): String =
        when (a) {
            is ReconcileAction.NoOp -> "noop"
            is ReconcileAction.DownloadRemote -> "download"
            is ReconcileAction.UploadLocal -> "upload"
            is ReconcileAction.PropagateLocalDelete -> "del-remote"
            is ReconcileAction.PropagateRemoteDelete -> "del-local"
            is ReconcileAction.ReportCollision -> "collision"
        }
}

@Command(name = "claim", description = ["Adopt a path into the tracking set using current observations."])
class TsClaimCommand : Runnable {
    @ParentCommand
    lateinit var parent: TsCommand

    @Option(names = ["-p", "--profile"], description = ["Profile name"])
    var profile: String? = null

    @Parameters(index = "0", description = ["Path to claim, e.g. /Documents/foo.txt"])
    lateinit var path: String

    override fun run() {
        val ctx = TsContext.build(parent.servicesRef, profile)
        val tracking = SqliteTrackingSet(ctx.trackingDb)
        tracking.initialize()
        try {
            val provider = ctx.services.createProvider(ctx.profileName)
            runBlocking { provider.authenticateAndLog() }
            val engine = TrackingEngine(provider, tracking, ctx.syncRoot)
            engine.claim(path)
            println("Claimed: $path")
        } finally {
            tracking.close()
        }
    }
}

@Command(name = "unclaim", description = ["Remove a path from the tracking set (does NOT delete files)."])
class TsUnclaimCommand : Runnable {
    @ParentCommand
    lateinit var parent: TsCommand

    @Option(names = ["-p", "--profile"], description = ["Profile name"])
    var profile: String? = null

    @Parameters(index = "0", description = ["Path to unclaim"])
    lateinit var path: String

    override fun run() {
        val ctx = TsContext.build(parent.servicesRef, profile)
        val tracking = SqliteTrackingSet(ctx.trackingDb)
        tracking.initialize()
        try {
            tracking.remove(path)
            println("Unclaimed: $path")
        } finally {
            tracking.close()
        }
    }
}

@Command(name = "status", description = ["Show tracking-set state counts."])
class TsStatusCommand : Runnable {
    @ParentCommand
    lateinit var parent: TsCommand

    @Option(names = ["-p", "--profile"], description = ["Profile name"])
    var profile: String? = null

    override fun run() {
        val ctx = TsContext.build(parent.servicesRef, profile)
        val tracking = SqliteTrackingSet(ctx.trackingDb)
        tracking.initialize()
        try {
            val counts = tracking.countsByState()
            val total = counts.values.sum()
            println("Tracking-set status for profile '${ctx.profileName}':")
            println("  db:         ${ctx.trackingDb}")
            println("  sync_root:  ${ctx.syncRoot}")
            println("  total:      $total entries")
            if (counts.isEmpty()) {
                println("  (empty — nothing has crossed the sync boundary yet)")
            } else {
                for ((state, count) in counts.entries.sortedBy { it.key.name }) {
                    println("  ${state.name.padEnd(20)} $count")
                }
            }
        } finally {
            tracking.close()
        }
    }
}
