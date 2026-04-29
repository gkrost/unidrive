package org.krost.unidrive.cli

import org.krost.unidrive.sync.VersionManager
import picocli.CommandLine
import picocli.CommandLine.*
import picocli.CommandLine.Model.CommandSpec
import java.time.Instant

@Command(
    name = "versions",
    description = ["Manage file version snapshots"],
    mixinStandardHelpOptions = true,
    subcommands = [VersionsListCommand::class, VersionsRestoreCommand::class, VersionsPurgeCommand::class],
)
class VersionsCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        parent.spec
            .commandLine()
            .subcommands["versions"]!!
            .usage(System.out)
    }
}

@Command(name = "list", description = ["List versioned files"], mixinStandardHelpOptions = true)
class VersionsListCommand : Runnable {
    @ParentCommand
    lateinit var parent: VersionsCommand

    @Parameters(index = "0", description = ["File path to list versions for"], arity = "0..1")
    var path: String? = null

    override fun run() {
        val config = parent.parent.loadSyncConfig()
        val manager = VersionManager(config.syncRoot)
        val items = if (path != null) manager.listVersions(path!!) else manager.listAll()
        if (items.isEmpty()) {
            println("No versions found.")
            return
        }
        println("%-25s  %-10s  %s".format("Snapshot", "Size", "Path"))
        println("-".repeat(70))
        for (item in items) {
            println("%-25s  %-10s  %s".format(item.timestamp, formatSize(item.sizeBytes), item.originalPath))
        }
        println("\n${items.size} version(s), ${formatSize(items.sumOf { it.sizeBytes })} total")
    }

    // UD-238: binary math → IEC binary labels (KiB/MiB/GiB). See TrashCommand.formatSize
    // for the same rationale; divisor is 2^10/2^20/2^30 throughout.
    private fun formatSize(bytes: Long): String =
        when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KiB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MiB"
            else -> "${bytes / (1024 * 1024 * 1024)}GiB"
        }
}

@Command(name = "restore", description = ["Restore a specific file version"], mixinStandardHelpOptions = true)
class VersionsRestoreCommand : Runnable {
    @ParentCommand
    lateinit var parent: VersionsCommand

    @Parameters(index = "0", description = ["Original file path"])
    lateinit var path: String

    @Parameters(index = "1", description = ["Timestamp of version to restore (ISO format, e.g. 2026-04-13T18:43:12Z)"])
    lateinit var timestamp: String

    override fun run() {
        val config = parent.parent.loadSyncConfig()
        val manager = VersionManager(config.syncRoot)
        val instant = Instant.parse(timestamp)
        if (manager.restore(path, instant)) {
            println("Restored: $path (version $timestamp)")
        } else {
            System.err.println("Version not found: $path @ $timestamp")
        }
    }
}

@Command(name = "purge", description = ["Purge old version snapshots"], mixinStandardHelpOptions = true)
class VersionsPurgeCommand : Runnable {
    @ParentCommand
    lateinit var parent: VersionsCommand

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--all"], description = ["Purge all versions, ignoring retention"])
    var purgeAll: Boolean = false

    @Option(names = ["--retention-days"], description = ["Override retention days"], defaultValue = "90")
    var retentionDays: Int = 90

    override fun run() {
        if (retentionDays < 0) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--retention-days must be >= 0 (got $retentionDays)",
            )
        }
        val config = parent.parent.loadSyncConfig()
        val manager = VersionManager(config.syncRoot)
        if (purgeAll) {
            manager.pruneAll()
            println("All versions purged.")
        } else {
            manager.pruneByAge(retentionDays)
            println("Purged versions older than $retentionDays days.")
        }
    }
}
