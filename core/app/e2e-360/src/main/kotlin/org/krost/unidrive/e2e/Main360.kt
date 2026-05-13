package org.krost.unidrive.e2e

import org.krost.unidrive.e2e.scenarios.GroundTruthRunner
import org.krost.unidrive.e2e.verify.ReportEntry
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Paths

@Command(
    name = "unidrive-360",
    description = ["UniDrive 360° RoundTrip Test Framework"],
    mixinStandardHelpOptions = true,
    subcommands = [GenerateCommand::class, CloudForgeCommand::class, GroundTruthCommand::class],
)
class Main360 : Runnable {
    override fun run() {
        println("Usage: unidrive-360 <generate|cloudforge|groundtruth>")
    }
}

@Command(name = "generate", description = ["Generate Golden Structure only (no cloud interaction)"])
class GenerateCommand : Runnable {
    @Option(names = ["--profile"], description = ["Profile preset (dev|ci|full|stress)"], defaultValue = "dev")
    var profile: String = "dev"

    @Option(names = ["--output"], description = ["Output directory"], required = true)
    lateinit var output: String

    @Option(names = ["--run-id"], description = ["Specific run ID for reproducibility"]) var runId: String? = null

    override fun run() {
        val configPath = findConfigPath()
        val config = if (java.nio.file.Files.exists(configPath)) {
            ConfigLoader.load(configPath).copy(
                general = ConfigLoader.load(configPath).general.copy(profile = profile),
                run = ConfigLoader.load(configPath).run.copy(run_id = runId.orEmpty())
            )
        } else {
            ConfigLoader.parse("[general]\nprofile = \"$profile\"\n[run]\nrun_id = \"${runId.orEmpty()}\"\n")
        }
        val ctx = RunContext.create(config, Paths.get(output), "none")
        val outputDir = Paths.get(output)
        val entries = GoldenStructureBuilder(ctx).build(outputDir)
        println("Generated ${entries.size} files in $output")
        println("Manifest: $output/manifest.sha3-512.jsonl")
    }
}

@Command(name = "cloudforge", description = ["Run CloudForge scenario (Cloud → Local)"])
class CloudForgeCommand : Runnable {
    @Option(names = ["-p", "--provider"], required = true, description = ["Provider name"]) lateinit var provider: String
    @Option(names = ["--profile"], defaultValue = "dev", description = ["Profile preset"]) var profile: String = "dev"
    @Option(names = ["--sync-root"], required = true, description = ["UniDrive sync root path"]) lateinit var syncRoot: String
    @Option(names = ["--headed"], description = ["Run browser in headed mode"]) var headed: Boolean = false
    @Option(names = ["--run-id"], description = ["Specific run ID for reproducibility"]) var runId: String? = null

    override fun run() {
        val configPath = findConfigPath()
        val baseConfig = if (java.nio.file.Files.exists(configPath)) {
            ConfigLoader.load(configPath)
        } else {
            ConfigLoader.parse("[general]\nprofile = \"$profile\"\n[run]\n")
        }
        val config = baseConfig.copy(
            general = baseConfig.general.copy(profile = profile),
            run = baseConfig.run.copy(run_id = runId.orEmpty())
        )
        val ctx = RunContext.create(config, Paths.get(syncRoot), provider)
        println("CloudForge: provider=$provider, profile=$profile, run_id=${ctx.runId}")
        println("TODO: Wire to CloudForgeRunner with Playwright browser (use --headed for visible browser)")
    }
}

@Command(name = "groundtruth", description = ["Run GroundTruth scenario (Local → Cloud)"])
class GroundTruthCommand : Runnable {
    @Option(names = ["-p", "--provider"], required = true, description = ["Provider name"]) lateinit var provider: String
    @Option(names = ["--profile"], defaultValue = "dev", description = ["Profile preset"]) var profile: String = "dev"
    @Option(names = ["--sync-root"], required = true, description = ["UniDrive sync root path"]) lateinit var syncRoot: String
    @Option(names = ["--run-id"], description = ["Specific run ID for reproducibility"]) var runId: String? = null

    override fun run() {
        val bareProviderTypes = setOf("onedrive", "hidrive", "internxt", "rclone", "s3", "sftp", "webdav")
        if (provider in bareProviderTypes) {
            System.err.println("WARNING: Using bare provider type '$provider'. " +
                "Consider a named test profile (e.g. '$provider-test') to avoid overwriting primary account credentials.")
        }

        val configPath = findConfigPath()
        val baseConfig = if (java.nio.file.Files.exists(configPath)) {
            ConfigLoader.load(configPath)
        } else {
            ConfigLoader.parse("[general]\nprofile = \"$profile\"\n[run]\n")
        }
        val config = baseConfig.copy(
            general = baseConfig.general.copy(profile = profile),
            run = baseConfig.run.copy(run_id = runId.orEmpty())
        )
        val ctx = RunContext.create(config, Paths.get(syncRoot), provider)

        val unidriveRunner = UnidriveRunner()
        val groundTruthRunner = GroundTruthRunner(ctx, unidriveRunner)
        
        try {
            val reports = groundTruthRunner.run()
            printlnReportSummary(reports, ctx.runId, provider)
        } catch (e: Exception) {
            System.err.println("GroundTruth test failed: ${e.message}")
            System.exit(1)
        }
    }

    private fun printlnReportSummary(reports: List<ReportEntry>, runId: String, provider: String) {
        val verifyReport = reports.firstOrNull { it.phase == "verify" } ?: 
            reports.lastOrNull { it.phase == "cleanup_report" } ?: 
            reports.last()
        
        println("\n═══════════════════════════════════════")
        println("   360° GroundTruth: $provider")
        println("   Run ID: $runId")
        println("═══════════════════════════════════════")
        println("   PASS (hash verified):  ${verifyReport.pass}")
        println("   SKIP (placeholder ok): ${verifyReport.skip}")
        println("   FAIL:                  ${verifyReport.fail}")
        println("   Total:                 ${verifyReport.total}")
        println("   Duration:              ${verifyReport.duration_ms / 1000}s")
        println("═══════════════════════════════════════")
        val status = if (verifyReport.fail == 0) "ALL CLEAR" else "FAILURES DETECTED"
        println("   STATUS: $status")
        println()
    }
}

fun findConfigPath(): java.nio.file.Path = listOf(
    Paths.get("e2e-360/config/360-config.toml"),
    Paths.get("config/360-config.toml"),
    Paths.get("360-config.toml"),
).firstOrNull { java.nio.file.Files.exists(it) } ?: Paths.get("e2e-360/config/360-config.toml")

fun main(args: Array<String>) {
    val exitCode = CommandLine(Main360()).execute(*args)
    System.exit(exitCode)
}
