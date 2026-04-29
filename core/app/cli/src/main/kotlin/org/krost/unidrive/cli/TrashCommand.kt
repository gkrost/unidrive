package org.krost.unidrive.cli

import org.krost.unidrive.sync.TrashManager
import picocli.CommandLine
import picocli.CommandLine.*
import picocli.CommandLine.Model.CommandSpec

@Command(
    name = "trash",
    description = ["Manage trashed files (trash emulation)"],
    mixinStandardHelpOptions = true,
    subcommands = [TrashListCommand::class, TrashRestoreCommand::class, TrashPurgeCommand::class],
)
class TrashCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        parent.spec
            .commandLine()
            .subcommands["trash"]!!
            .usage(System.out)
    }
}

@Command(name = "list", description = ["List trashed files"], mixinStandardHelpOptions = true)
class TrashListCommand : Runnable {
    @ParentCommand
    lateinit var parent: TrashCommand

    override fun run() {
        val config = parent.parent.loadSyncConfig()
        val manager = TrashManager(config.syncRoot)
        val items = manager.list()
        if (items.isEmpty()) {
            println("Trash is empty.")
            return
        }
        println("%-25s  %-10s  %s".format("Deleted", "Size", "Path"))
        println("-".repeat(70))
        for (item in items) {
            println("%-25s  %-10s  %s".format(item.timestamp, formatSize(item.sizeBytes), item.originalPath))
        }
        println("\n${items.size} item(s), ${formatSize(items.sumOf { it.sizeBytes })} total")
    }

    // UD-238: binary math → IEC binary labels (KiB/MiB/GiB). Previously bare K/M/G
    // was ambiguous but the divisor is 2^10/2^20/2^30, so we commit to the binary suffix.
    private fun formatSize(bytes: Long): String =
        when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KiB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MiB"
            else -> "${bytes / (1024 * 1024 * 1024)}GiB"
        }
}

@Command(name = "restore", description = ["Restore a trashed file"], mixinStandardHelpOptions = true)
class TrashRestoreCommand : Runnable {
    @ParentCommand
    lateinit var parent: TrashCommand

    @Parameters(index = "0", description = ["Original path to restore"])
    lateinit var path: String

    override fun run() {
        val config = parent.parent.loadSyncConfig()
        val manager = TrashManager(config.syncRoot)
        if (manager.restore(path)) {
            println("Restored: $path")
        } else {
            System.err.println("Not found in trash: $path")
        }
    }
}

@Command(name = "purge", description = ["Purge old trash entries"], mixinStandardHelpOptions = true)
class TrashPurgeCommand : Runnable {
    @ParentCommand
    lateinit var parent: TrashCommand

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--all"], description = ["Purge all trash, ignoring retention"])
    var purgeAll: Boolean = false

    @Option(names = ["--retention-days"], description = ["Override retention days"], defaultValue = "30")
    var retentionDays: Int = 30

    override fun run() {
        if (retentionDays < 0) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--retention-days must be >= 0 (got $retentionDays)",
            )
        }
        val config = parent.parent.loadSyncConfig()
        val manager = TrashManager(config.syncRoot)
        if (purgeAll) {
            manager.purgeAll()
            println("All trash purged.")
        } else {
            manager.purge(retentionDays)
            println("Purged entries older than $retentionDays days.")
        }
    }
}
