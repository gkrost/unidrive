package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.CloudRelocator
import org.krost.unidrive.sync.MigrateEvent
import org.krost.unidrive.sync.RelocateMdc
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.Locale
import kotlin.math.max

@Command(name = "relocate", description = ["Migrate data between cloud providers"], mixinStandardHelpOptions = true)
class RelocateCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--from", "-f"], description = ["Source provider (profile name)"], required = true)
    var fromProvider: String = ""

    @Option(names = ["--to", "-t"], description = ["Target provider (profile name)"], required = true)
    var toProvider: String = ""

    @Option(names = ["--source-path"], description = ["Source subtree to migrate (default: root)"])
    var sourcePath: String = "/"

    @Option(names = ["--target-path"], description = ["Target subtree (default: root)"])
    var targetPath: String = "/"

    @Option(names = ["--delete-source"], description = ["Delete source files after successful migration (requires confirmation)"])
    var deleteSource: Boolean = false

    @Option(names = ["--buffer-mb"], description = ["Streaming buffer size in MB (default: 8)"])
    var bufferMb: Int = 8

    @Option(
        names = ["--force"],
        description = [
            "Transfer every file even if the target already has an equivalent copy " +
                "(default: skip files whose size or hash matches)",
        ],
    )
    var force: Boolean = false

    @Option(
        names = ["--confirm-transport"],
        description = [
            "Acknowledge any transport-fitness warnings (UD-279) and proceed " +
                "without surfacing them. Use in scripts / CI where the warning is " +
                "informational and would otherwise clutter logs.",
        ],
    )
    var confirmTransport: Boolean = false

    override fun run() {
        if (fromProvider == toProvider) {
            System.err.println("Error: source and target providers must be different")
            throw IllegalArgumentException("Same provider for source and target")
        }

        // UD-294: relocate touches TWO profiles, so Main.resolveCurrentProfile (sync's
        // profile-MDC anchor) doesn't apply here. Seed the `profile` and `scan` MDC keys
        // explicitly so the runBlocking(MDCContext()) snapshots below capture a meaningful
        // [<sha>] [<from>+<to>] [<migId>] triplet. Without this every relocate log line
        // rendered [<sha>] [*] [-------] (132 k unfilterable lines on 2026-04-29 — see
        // docs/dev/log-feedback-loop-proposal.md). UD-284 fixed the relocate flow's
        // MDCContext propagation; this fills in the snapshot it propagates.
        RelocateMdc.withRelocateMdc(fromProvider, toProvider, RelocateMdc.newMigId()) {
            runRelocate()
        }
    }

    private fun runRelocate() {
        // UD-269: GC orphan tempDirs from previous aborted relocates (kill -9
        // / OS force-terminate / power loss) before starting a new migration.
        // 24h cutoff so a legitimate concurrent relocate doesn't get its
        // tempDir deleted out from under it; silent — caller doesn't care.
        val gcCount = CloudRelocator.cleanStaleTempDirs()
        if (gcCount > 0) {
            org.slf4j.LoggerFactory
                .getLogger(RelocateCommand::class.java)
                .info("UD-269: garbage-collected {} stale relocate tempdir(s)", gcCount)
        }

        val fromProfile =
            parent.resolveProfile(fromProvider)
                ?: throw IllegalArgumentException("Unknown source provider: $fromProvider")
        val toProfile =
            parent.resolveProfile(toProvider)
                ?: throw IllegalArgumentException("Unknown target provider: $toProvider")

        val fromConfigDir = parent.configBaseDir().resolve(fromProvider)
        val toConfigDir = parent.configBaseDir().resolve(toProvider)

        val fromProviderObj = parent.createProviderFor(fromProfile, fromConfigDir)
        val toProviderObj = parent.createProviderFor(toProfile, toConfigDir)

        // Authenticate both providers unconditionally
        // UD-284: every runBlocking in this command propagates MDC via
        // MDCContext() so that the `build`, `profile`, and `scan` MDC keys
        // set by `Main.main` / `Main.resolveCurrentProfile` reach the
        // DefaultDispatcher / IO worker threads. Without it, log lines from
        // CloudRelocator's coroutines render as `[???????] [*] [-------]`
        // (the logback fallback values from logback.xml line 31), making
        // postmortem against unidrive.log impossible.
        runBlocking(MDCContext()) { fromProviderObj.authenticate() }
        runBlocking(MDCContext()) { toProviderObj.authenticate() }

        if (!fromProviderObj.isAuthenticated) {
            System.err.println("Error: source provider '$fromProvider' not authenticated")
            throw IllegalStateException("Run 'unidrive -p $fromProvider auth' first")
        }
        if (!toProviderObj.isAuthenticated) {
            System.err.println("Error: target provider '$toProvider' not authenticated")
            throw IllegalStateException("Run 'unidrive -p $toProvider auth' first")
        }

        val relocator =
            CloudRelocator(
                source = fromProviderObj,
                target = toProviderObj,
                bufferSize = bufferMb.toLong() * 1024 * 1024,
                skipExisting = !force,
            )

        println("Pre-flight checks...")

        val sourceQuota: QuotaInfo = runBlocking(MDCContext()) { fromProviderObj.quota() }

        print("Scanning source".padEnd(72))
        System.out.flush()
        val (sourceSize, sourceCount) =
            runBlocking(MDCContext()) {
                relocator.preFlightCheck(sourcePath) { count, path ->
                    val short = if (path.length > 45) "…${path.takeLast(44)}" else path
                    val countFmt = String.format(Locale.ROOT, "%,d", count).replace(',', ' ')
                    print("\r${"Scanning source  $countFmt files  $short".padEnd(72)}")
                    System.out.flush()
                }
            }
        println()

        val targetQuota: QuotaInfo = runBlocking(MDCContext()) { toProviderObj.quota() }

        // Guard: fail fast when target quota is known and insufficient
        if (targetQuota.total > 0 && targetQuota.remaining < sourceSize) {
            val missing = sourceSize - targetQuota.remaining
            System.err.println(
                "Error: target has ${formatSize(targetQuota.remaining)} available, " +
                    "missing ${formatSize(missing)}",
            )
            throw IllegalStateException("Insufficient target quota")
        }

        printSummaryBox(
            fromProvider,
            fromProviderObj.displayName,
            sourceQuota,
            sourceSize,
            sourceCount,
            toProvider,
            toProviderObj.displayName,
            targetQuota,
            deleteSource,
        )

        if (sourceSize == 0L) {
            println("Nothing to migrate")
            return
        }

        // UD-279: planner-time transport-fitness warning. WebDAV is a poor fit
        // for bulk media migration even on the LAN — DSM's nginx-bound
        // throughput ceiling, no-chunked-PUT, and the no-resume risk
        // (UD-327 / UD-328 territory) make any WebDAV plan > 50 GiB
        // worth flagging at plan time. The user can pick a better-fit
        // sibling profile on the same NAS (sftp / rclone) before burning
        // the upload time. --confirm-transport suppresses for scripts / CI.
        if (!confirmTransport && toProviderObj.id == "webdav") {
            val fiftyGiB = 50L * 1024 * 1024 * 1024
            val warnings = mutableListOf<String>()
            if (sourceSize > fiftyGiB) {
                warnings.add(
                    "Plan size ${formatSize(sourceSize)} exceeds 50 GiB on a WebDAV target. " +
                        "Throughput ceiling on nginx-mod_dav is typically < 30 MiB/s LAN; " +
                        "expect this to take many hours. Consider sftp / rclone-native if the " +
                        "same NAS exposes them.",
                )
            }
            if (warnings.isNotEmpty()) {
                System.err.println()
                System.err.println("Transport-fitness warning (UD-279):")
                warnings.forEach { System.err.println("  - $it") }
                System.err.println("  Pass --confirm-transport to acknowledge and proceed silently.")
                System.err.println()
            }
        }

        // UD-327: warn early about files exceeding the target's per-file size
        // cap (Synology DSM WebDAV defaults to 4 GiB). Pre-fix, oversized
        // PUTs failed at minute 10 of a wasted transfer with a 409 / silent
        // connection reset. The cap surfaces only when the user configured
        // `max_file_size_bytes` in the profile TOML; generic WebDAV servers
        // (Box / Nextcloud) without an explicit cap remain unaffected.
        val oversized = runBlocking(MDCContext()) { relocator.preflightOversized(sourcePath) }
        if (oversized.isNotEmpty()) {
            val cap = toProviderObj.maxFileSizeBytes() ?: 0L
            System.err.println()
            System.err.println("Warning: ${oversized.size} file(s) exceed target's max file size (${formatSize(cap)}):")
            for ((path, size) in oversized.take(10)) {
                System.err.println("  ${formatSize(size).padStart(10)}  $path")
            }
            if (oversized.size > 10) {
                System.err.println("  ... and ${oversized.size - 10} more")
            }
            System.err.println(
                "These files will fail mid-transfer. Raise the server's cap, " +
                    "use a different provider for these files, or remove them.",
            )
            System.err.println()
        }

        println("\nStarting migration...")
        println("  from: $fromProvider:$sourcePath")
        println("  to:   $toProvider:$targetPath")
        println("  buffer: $bufferMb MB")
        println()

        // UD-269: register a shutdown hook so CTRL-C / SIGTERM produces a
        // visible abort summary, a logged INFO line, and tempDir cleanup —
        // instead of the pre-fix silent JVM exit that left
        // `%TEMP%\unidrive-relocate-<random>\` orphans on disk and zero
        // record in unidrive.log of what was already transferred.
        val abortLog = org.slf4j.LoggerFactory.getLogger(RelocateCommand::class.java)
        val abortFiles =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val abortSize =
            java.util.concurrent.atomic
                .AtomicLong(0L)
        val abortSkipped =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val totalsForAbort = sourceSize
        val totalCountForAbort = sourceCount
        val shutdownHook =
            Thread {
                val files = abortFiles.get()
                val bytes = abortSize.get()
                val skipped = abortSkipped.get()
                System.err.println()
                System.err.println("Migration aborted:")
                System.err.println("  transferred: ${files - skipped} files (${formatSize(bytes)})")
                if (skipped > 0) System.err.println("  skipped:     $skipped files")
                System.err.println("  remaining:   ${totalCountForAbort - files} files (${formatSize(totalsForAbort - bytes)})")
                abortLog.info(
                    "Migration aborted — transferred={} files, bytes={}, skipped={} files, remaining={} files",
                    files - skipped,
                    bytes,
                    skipped,
                    totalCountForAbort - files,
                )
                runCatching { relocator.currentTempDir?.toFile()?.deleteRecursively() }
            }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            runBlocking(MDCContext()) {
                relocator.migrate(sourcePath, targetPath, sourceSize, sourceCount).collect { event ->
                    when (event) {
                        is MigrateEvent.Started -> {
                            println("Migrating ${event.totalFiles} files (${formatSize(event.totalSize)})...")
                        }
                        is MigrateEvent.FileProgressEvent -> {
                            // UD-269: surface running totals so the shutdown hook
                            // has a useful number to print on abort.
                            abortFiles.set(event.doneFiles)
                            abortSize.set(event.doneSize)
                            abortSkipped.set(event.skippedFiles)
                            val pct = if (event.totalSize > 0) (event.doneSize * 100 / event.totalSize) else 0
                            val filesFmt = String.format(Locale.ROOT, "%,d", event.doneFiles).replace(',', ' ')
                            val totalFilesFmt = String.format(Locale.ROOT, "%,d", event.totalFiles).replace(',', ' ')
                            print(
                                "\r  $filesFmt / $totalFilesFmt files  " +
                                    "${formatSize(event.doneSize)} / ${formatSize(event.totalSize)} ($pct%)".padEnd(20),
                            )
                            System.out.flush()
                        }
                        is MigrateEvent.Completed -> {
                            val transferredFiles = event.doneFiles - event.skippedFiles
                            val transferredSize = event.doneSize - event.skippedSize
                            println("\n\nMigration complete:")
                            println("  transferred: $transferredFiles files (${formatSize(transferredSize)})")
                            if (event.skippedFiles > 0) {
                                println("  skipped:     ${event.skippedFiles} files (${formatSize(event.skippedSize)})")
                            }
                            println("  errors: ${event.errorCount}")
                            println("  time:   ${event.durationMs / 1000}s")
                        }
                        is MigrateEvent.Error -> {
                            System.err.println("\nError: ${event.message}")
                        }
                    }
                }
            }
        } finally {
            // UD-269: only remove the hook on normal completion; abort
            // path goes through the hook itself.
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }

        if (deleteSource) {
            print("\nDelete source files? Type 'yes' to confirm: ")
            val confirm = readLine()
            if (confirm != "yes") {
                println("Aborted")
                return
            }
            println("Deleting source files...")
            runBlocking(MDCContext()) {
                val deletedFiles = deleteSourceRecursive(fromProviderObj, sourcePath)
                println("Deleted $deletedFiles files")
            }
        }
    }

    private suspend fun deleteSourceRecursive(
        provider: CloudProvider,
        prefix: String,
    ): Int {
        var deleted = 0
        var page = provider.delta(null)
        while (true) {
            for (item in page.items) {
                if (item.path.startsWith(prefix) && !item.isFolder) {
                    try {
                        provider.delete(item.path)
                        deleted++
                    } catch (e: Exception) {
                        System.err.println("Warning: could not delete ${item.path}: ${e.message}")
                    }
                }
            }
            if (page.cursor == null) break
            page = provider.delta(page.cursor)
        }
        // Delete folders bottom-up
        page = provider.delta(null)
        val folders =
            page.items
                .filter { it.isFolder && it.path.startsWith(prefix) }
                .sortedByDescending { it.path.count { c -> c == '/' } }
        for (folder in folders) {
            try {
                provider.delete(folder.path)
            } catch (e: Exception) {
                // Ignore - probably not empty
            }
        }
        return deleted
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var size = bytes.toDouble() / 1024
        var idx = 0
        while (size >= 1024 && idx < units.lastIndex) {
            size /= 1024
            idx++
        }
        return String.format(Locale.ROOT, "%.1f %s", size, units[idx])
    }

    @Suppress("LongParameterList")
    private fun printSummaryBox(
        fromProfile: String,
        fromDisplayName: String,
        sourceQuota: QuotaInfo,
        sourceSize: Long,
        sourceCount: Int,
        toProfile: String,
        toDisplayName: String,
        targetQuota: QuotaInfo,
        doDeleteSource: Boolean,
    ) {
        val h = GlyphRenderer.boxHorizontal()
        val v = GlyphRenderer.boxVertical()
        val tl = GlyphRenderer.boxTopLeft()
        val tr = GlyphRenderer.boxTopRight()
        val bl = GlyphRenderer.boxBottomLeft()
        val br = GlyphRenderer.boxBottomRight()
        val teeD = GlyphRenderer.boxTeeDown()
        val teeU = GlyphRenderer.boxTeeUp()
        val teeR = GlyphRenderer.boxTeeRight()
        val teeL = GlyphRenderer.boxTeeLeft()
        val cross = GlyphRenderer.boxCross()
        val arrow = if (GlyphRenderer.isDingbatsSafe()) "►" else ">"

        // Box: total width 63 = │ + 30 + │ + 30 + │
        val colWidth = 30 // column width (content + padding)
        val innerWidth = colWidth - 2 // inner content width = 28

        fun hLine(n: Int) = h.repeat(n)

        fun cell(s: String) = " ${s.take(innerWidth).padEnd(innerWidth)} "

        fun row(
            l: String,
            r: String,
        ) = "$v${cell(l)}$v${cell(r)}$v"

        fun fullRow(s: String) = "$v ${s.take(colWidth * 2 - 1).padEnd(colWidth * 2 - 1)} $v"

        val topBorder = "$tl${hLine(colWidth)}$teeD${hLine(colWidth)}$tr"
        val colBorder = "$teeR${hLine(colWidth)}$cross${hLine(colWidth)}$teeL"
        val mergeBorder = "$teeR${hLine(colWidth)}$teeU${hLine(colWidth)}$teeL"
        val botBorder = "$bl${hLine(colWidth * 2 + 1)}$br"

        val fromUsed =
            if (sourceQuota.total > 0) {
                "${formatSize(sourceQuota.used)} / ${formatSize(sourceQuota.total)}"
            } else {
                "unknown"
            }
        val toAvail =
            if (targetQuota.total > 0) {
                "${formatSize(targetQuota.remaining)} available"
            } else {
                "unknown"
            }
        val countFmt = String.format(Locale.ROOT, "%,d", sourceCount).replace(',', ' ')
        val arrowPad = hLine(max(2, colWidth * 2 - 15 - formatSize(sourceSize).length))
        val transferLine = "Transfer  ${formatSize(sourceSize)}  $arrowPad$arrow"
        val afterFrom =
            if (sourceQuota.total > 0) {
                "~${formatSize(max(0L, sourceQuota.remaining - sourceSize))} free"
            } else {
                "unknown"
            }
        val afterTo =
            if (targetQuota.total > 0) {
                "~${formatSize(max(0L, targetQuota.remaining - sourceSize))} free"
            } else {
                "unknown"
            }
        val afterLine = "After     from: $afterFrom  ·  to: $afterTo"

        println()
        println(topBorder)
        println(row("  FROM  $fromProfile", "  TO  $toProfile"))
        println(colBorder)
        println(row("  $fromDisplayName", "  $toDisplayName"))
        println(row("  used: $fromUsed", "  avail: $toAvail"))
        println(row("  migrate: $countFmt files", "  ${formatSize(sourceSize)} total"))
        if (doDeleteSource) {
            println(mergeBorder)
            println(fullRow("  DELETE source after transfer:  YES ⚠"))
        }
        println(mergeBorder)
        println(fullRow("  $transferLine"))
        println(fullRow("  $afterLine"))
        println(botBorder)
        println()
    }
}
