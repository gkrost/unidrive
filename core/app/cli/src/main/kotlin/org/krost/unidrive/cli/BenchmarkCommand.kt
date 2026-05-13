package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.BenchmarkDatabase
import org.krost.unidrive.sync.BenchmarkPoint
import org.krost.unidrive.sync.BenchmarkRun
import org.krost.unidrive.sync.BenchmarkRunner
import org.krost.unidrive.sync.SyncConfig
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Command(
    name = "benchmark",
    description = ["Benchmark upload/download performance for a provider profile"],
)
class BenchmarkCommand : Runnable {

    @ParentCommand
    lateinit var main: Main

    @Option(names = ["--all"], description = ["Run benchmark for all authenticated profiles"])
    var all: Boolean = false

    @Option(names = ["--iterations"], description = ["Number of iterations per file size (default: 5)"], defaultValue = "5")
    var iterations: Int = BenchmarkRunner.DEFAULT_ITERATIONS

    @Option(names = ["--sizes"], description = ["Comma-separated file sizes to test, e.g. 1KB,10MB (default: 1KB,100KB,1MB,10MB,100MB)"])
    var sizes: String? = null

    @Option(names = ["--ipv4"], description = ["Force IPv4"])
    var ipv4: Boolean = false

    @Option(names = ["--ipv6"], description = ["Force IPv6"])
    var ipv6: Boolean = false

    @Option(names = ["--keep-files"], description = ["Do not delete benchmark files after run"])
    var keepFiles: Boolean = false

    @Option(names = ["--filter-provider"], description = ["Filter results by provider profile name"])
    var filterProvider: String? = null

    @Option(names = ["--results"], description = ["Show results table instead of running a benchmark"])
    var results: Boolean = false

    @Option(names = ["--delete-run"], description = ["Delete a benchmark run by ID"])
    var deleteRunId: String? = null

    @Option(names = ["--json"], description = ["With --results: export as JSON"])
    var json: Boolean = false

    @Option(names = ["--compare"], description = ["With --results: show ranked comparison"])
    var compare: Boolean = false

    private fun dbPath() = run {
        val xdgData = System.getenv("XDG_DATA_HOME")
        val dataHome = if (!xdgData.isNullOrBlank()) Paths.get(xdgData)
        else Paths.get(System.getProperty("user.home"), ".local", "share")
        dataHome.resolve("unidrive").resolve("benchmarks.db")
    }

    override fun run() {
        if (ipv4 && ipv6) {
            System.err.println("Error: --ipv4 and --ipv6 are mutually exclusive")
            return
        }
        val fileSizes = if (sizes != null) BenchmarkRunner.parseSizes(sizes!!) else BenchmarkRunner.DEFAULT_SIZES
        val ipStack = when {
            ipv4 -> "ipv4"
            ipv6 -> "ipv6"
            else -> "auto"
        }

        val db = BenchmarkDatabase(dbPath())
        db.initialize()
        db.use {
            if (deleteRunId != null) {
                if (db.deleteRun(deleteRunId!!)) {
                    println("Deleted benchmark run: $deleteRunId")
                } else {
                    System.err.println("Run not found: $deleteRunId")
                }
                return
            }
            if (results) {
                showResults(db)
            } else if (all) {
                runAllProfiles(db, fileSizes, ipStack)
            } else {
                // UD-252 follow-up: resolve the same way the rest of :app:cli does
                // (Main.resolveCurrentProfile honours `-p` then `[general] default_profile`).
                // main.provider is String? because picocli leaves it null when -p is omitted.
                val profileName = main.provider ?: main.resolveCurrentProfile().name
                runSingleProfile(db, profileName, fileSizes, ipStack)
            }
        }
    }

    private fun runSingleProfile(db: BenchmarkDatabase, profileName: String, fileSizes: List<Long>, ipStack: String) {
        // Resolve profile — sets main.provider temporarily
        val savedProvider = main.provider
        main.provider = profileName
        try {
            val profile = main.resolveCurrentProfile()
            val syncConfig = main.loadSyncConfig()
            val provider = main.createProvider()
            try {
                runBlocking { provider.authenticate() }

                val rp = profile.rawProvider
                val endpoint = when (profile.type) {
                    "s3"     -> rp?.endpoint ?: System.getenv("S3_ENDPOINT") ?: "https://s3.amazonaws.com"
                    "webdav" -> rp?.url ?: System.getenv("WEBDAV_URL")
                    "sftp"   -> rp?.host ?: System.getenv("SFTP_HOST")
                    "onedrive" -> "https://graph.microsoft.com"
                    else -> null
                }

                val runner = BenchmarkRunner(
                    provider = provider,
                    db = db,
                    providerId = profile.name,
                    providerType = profile.type,
                    protocol = profile.type,
                    endpoint = endpoint,
                    ipStack = ipStack,
                    clientLocation = syncConfig.clientLocation,
                    clientNetwork = syncConfig.clientNetwork,
                    unidriveVersion = BuildInfo.versionString(),
                    iterations = iterations,
                    fileSizes = fileSizes,
                    keepFiles = keepFiles,
                )

                println("Benchmarking profile '${profile.name}' (${profile.type})...")
                println("  Sizes: ${fileSizes.joinToString(", ") { formatSize(it) }}, iterations: $iterations")
                println()

                val run = runBlocking { runner.run { msg -> println("  $msg") } }
                println()
                println("Done. Run ID: ${run.runId}")
                println()
                printResultsTable(db, listOf(run.providerId))
            } finally {
                provider.close()
            }
        } finally {
            main.provider = savedProvider
        }
    }

    private fun runAllProfiles(db: BenchmarkDatabase, fileSizes: List<Long>, ipStack: String) {
        val configFile = main.configBaseDir().resolve("config.toml")
        // UD-252 follow-up: main.provider is String?; resolve once via the shared
        // Main.resolveCurrentProfile() so the fallback list is List<String>, not
        // List<String?>. Same resolution rule the rest of :app:cli uses.
        val resolvedProvider = main.provider ?: main.resolveCurrentProfile().name
        val profileNames: List<String> = if (Files.exists(configFile)) {
            val raw = SyncConfig.parseRaw(Files.readString(configFile))
            val names = raw.providers.keys.toMutableList()
            // Also add bare type names if not already in config
            names.ifEmpty { listOf(resolvedProvider) }
        } else {
            listOf(resolvedProvider)
        }

        val savedProvider = main.provider
        val ran = mutableListOf<String>()
        try {
            for (profileName in profileNames) {
                main.provider = profileName
                val authenticated = try {
                    main.isProviderAuthenticated()
                } catch (_: Exception) {
                    false
                }
                if (!authenticated) {
                    println("Skipping '$profileName': not authenticated.")
                    continue
                }
                try {
                    runSingleProfile(db, profileName, fileSizes, ipStack)
                    ran.add(profileName)
                } catch (e: Exception) {
                    System.err.println("Warning: benchmark failed for '$profileName': ${e.message}")
                }
            }
        } finally {
            main.provider = savedProvider
        }

        if (ran.size > 1) {
            println()
            println(AnsiHelper.bold("=== Comparison ==="))
            printResultsTable(db, ran, ranked = true)
        }
    }

    private fun showResults(db: BenchmarkDatabase) {
        val runs = if (filterProvider != null)
            db.latestRuns(filterProvider)
        else
            db.latestRuns()
        if (runs.isEmpty()) {
            println("No benchmark results found. Run 'unidrive provider benchmark' first.")
            return
        }
        if (json) {
            printJson(db, runs)
        } else {
            printResultsTable(db, runs.map { it.providerId }, ranked = compare)
        }
    }

    private fun printResultsTable(db: BenchmarkDatabase, providerIds: List<String>, ranked: Boolean = false) {
        data class Row(
            val profileId: String,
            val providerType: String,
            val upMbps: Double?,
            val dnMbps: Double?,
            val ttfbMs: Double?,
            val delMs: Double?,
            val errPct: Double,
            val when_: String,
        )

        val rows = providerIds.mapNotNull { pid ->
            val run = db.latestRuns(pid).firstOrNull() ?: return@mapNotNull null
            val points = db.getPoints(run.runId)
            Row(
                profileId = run.providerId,
                providerType = run.providerType,
                upMbps = medianThroughputMbps(points, "upload"),
                dnMbps = medianThroughputMbps(points, "download"),
                ttfbMs = medianDurationMs(points, "ttfb"),
                delMs = medianDurationMs(points, "delete"),
                errPct = errorPct(points),
                when_ = relativeTime(run.finishedAt),
            )
        }

        val sorted = if (ranked) rows.sortedByDescending { it.dnMbps ?: -1.0 } else rows

        println("%-20s %-10s %8s  %8s  %8s  %8s  %6s  %s".format(
            "PROFILE", "TYPE", "UP MB/s", "DN MB/s", "TTFB ms", "DEL ms", "ERR %", "WHEN"
        ))
        println("─".repeat(85))
        for (row in sorted) {
            val up   = row.upMbps?.let  { "%.1f".format(it) } ?: "n/a"
            val dn   = row.dnMbps?.let  { "%.1f".format(it) } ?: "n/a"
            val ttfb = row.ttfbMs?.let  { "%.0f".format(it) } ?: "n/a"
            val del  = row.delMs?.let   { "%.0f".format(it) } ?: "n/a"
            val err  = "%.1f%%".format(row.errPct)
            println("%-20s %-10s %8s  %8s  %8s  %8s  %6s  %s".format(
                row.profileId.take(20), row.providerType.take(10),
                up, dn, ttfb, del, err, row.when_
            ))
        }
        val largestSize = providerIds.flatMap { pid ->
            val run = db.latestRuns(pid).firstOrNull() ?: return@flatMap emptyList()
            db.getPoints(run.runId).filter { it.operation == "upload" && it.status == "ok" }.map { it.fileSize }
        }.maxOrNull()
        if (largestSize != null) {
            println("Median values, ${formatSize(largestSize)} file. Sorted by download throughput.")
        }
    }

    private fun printJson(db: BenchmarkDatabase, runs: List<BenchmarkRun>) {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"exported_at\": ${jsonStr(Instant.now().toString())},\n")
        sb.append("  \"unidrive_version\": ${jsonStr(BuildInfo.versionString())},\n")
        sb.append("  \"runs\": [\n")
        runs.forEachIndexed { i, run ->
            val points = db.getPoints(run.runId)
            sb.append("    {\n")
            sb.append("      \"run_id\": ${jsonStr(run.runId)},\n")
            sb.append("      \"started_at\": ${jsonStr(run.startedAt)},\n")
            sb.append("      \"finished_at\": ${run.finishedAt?.let { jsonStr(it) } ?: "null"},\n")
            sb.append("      \"provider_id\": ${jsonStr(run.providerId)},\n")
            sb.append("      \"provider_type\": ${jsonStr(run.providerType)},\n")
            sb.append("      \"protocol\": ${jsonStr(run.protocol)},\n")
            sb.append("      \"endpoint\": ${run.endpoint?.let { jsonStr(it) } ?: "null"},\n")
            sb.append("      \"ip_stack\": ${jsonStr(run.ipStack)},\n")
            sb.append("      \"resolved_ip\": ${run.resolvedIp?.let { jsonStr(it) } ?: "null"},\n")
            sb.append("      \"client_location\": ${run.clientLocation?.let { jsonStr(it) } ?: "null"},\n")
            sb.append("      \"client_network\": ${run.clientNetwork?.let { jsonStr(it) } ?: "null"},\n")
            sb.append("      \"unidrive_version\": ${jsonStr(run.unidriveVersion)},\n")
            sb.append("      \"iterations\": ${run.iterations},\n")
            sb.append("      \"file_sizes\": ${jsonStr(run.fileSizes)},\n")
            sb.append("      \"throttled\": ${run.throttled},\n")
            sb.append("      \"points\": [\n")
            points.forEachIndexed { j, pt ->
                sb.append("        {\n")
                sb.append("          \"iteration\": ${pt.iteration},\n")
                sb.append("          \"operation\": ${jsonStr(pt.operation)},\n")
                sb.append("          \"file_size\": ${pt.fileSize},\n")
                sb.append("          \"duration_ms\": ${pt.durationMs},\n")
                sb.append("          \"throughput_bps\": ${pt.throughputBps ?: "null"},\n")
                sb.append("          \"status\": ${jsonStr(pt.status)},\n")
                sb.append("          \"error_code\": ${pt.errorCode ?: "null"},\n")
                sb.append("          \"error_message\": ${pt.errorMessage?.let { jsonStr(it) } ?: "null"},\n")
                sb.append("          \"ip_stack\": ${jsonStr(pt.ipStack)},\n")
                sb.append("          \"timestamp\": ${jsonStr(pt.timestamp)}\n")
                sb.append("        }${if (j < points.lastIndex) "," else ""}\n")
            }
            sb.append("      ]\n")
            sb.append("    }${if (i < runs.lastIndex) "," else ""}\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        print(sb)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun medianThroughputMbps(points: List<BenchmarkPoint>, op: String): Double? {
        val largestSize = points.filter { it.operation == op && it.status == "ok" }
            .maxOfOrNull { it.fileSize } ?: return null
        val bpsList = points.filter { it.operation == op && it.fileSize == largestSize && it.status == "ok" }
            .mapNotNull { it.throughputBps }
        return bpsList.median()?.let { it / 1_000_000.0 }
    }

    private fun medianDurationMs(points: List<BenchmarkPoint>, op: String): Double? {
        val durations = points.filter { it.operation == op && it.status == "ok" }.map { it.durationMs }
        return durations.median()
    }

    private fun errorPct(points: List<BenchmarkPoint>): Double {
        val relevant = points.filter { it.operation in setOf("upload", "download", "ttfb", "delete") && it.status != "skip" }
        if (relevant.isEmpty()) return 0.0
        val errors = relevant.count { it.status in setOf("error", "timeout") }
        return errors * 100.0 / relevant.size
    }

    private fun relativeTime(finishedAt: String?): String {
        if (finishedAt == null) return "in progress"
        return try {
            val then = Instant.parse(finishedAt)
            val now = Instant.now()
            val minutes = ChronoUnit.MINUTES.between(then, now)
            when {
                minutes < 1   -> "just now"
                minutes < 60  -> "${minutes}m ago"
                minutes < 1440 -> "${minutes / 60}h ago"
                else           -> "${minutes / 1440}d ago"
            }
        } catch (_: Exception) {
            finishedAt
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L * 1024L)}GB"
        bytes >= 1024L * 1024L         -> "${bytes / (1024L * 1024L)}MB"
        bytes >= 1024L                 -> "${bytes / 1024L}KB"
        else                           -> "${bytes}B"
    }

    private fun jsonStr(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }
}

private fun List<Long>.median(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
}
