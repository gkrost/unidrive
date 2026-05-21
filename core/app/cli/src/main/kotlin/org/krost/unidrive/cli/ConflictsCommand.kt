package org.krost.unidrive.cli

import org.krost.unidrive.sync.ConflictLog
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.nio.file.Paths

@Command(
    name = "conflicts",
    description = ["View and restore conflict history"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ConflictsListCommand::class,
        ConflictsRestoreCommand::class,
        ConflictsClearCommand::class,
    ],
)
class ConflictsCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    fun conflictLog(): ConflictLog {
        val profile = parent.resolveCurrentProfile()
        val os = System.getProperty("os.name", "").lowercase()
        val dataDir =
            if (os.contains("win")) {
                val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
                Paths.get(localAppData, "unidrive", profile.name)
            } else {
                Paths.get(
                    System.getenv("HOME") ?: System.getProperty("user.home"),
                    ".local",
                    "share",
                    "unidrive",
                    profile.name,
                )
            }
        return ConflictLog(
            logFile = parent.providerConfigDir().resolve("conflicts.jsonl"),
            backupDir = dataDir.resolve("conflict-backups"),
        )
    }

    override fun run() {
        // Default: show recent conflicts
        ConflictsListCommand()
            .also {
                it.conflictsCmd = this
            }.run()
    }
}

// ── conflicts list ───────────────────────────────────────────────────────────

@Command(name = "list", description = ["Show recent conflicts"], mixinStandardHelpOptions = true)
class ConflictsListCommand : Runnable {
    @ParentCommand
    lateinit var conflictsCmd: ConflictsCommand

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["-n", "--limit"], description = ["Number of entries to show"], defaultValue = "20")
    var limit: Int = 20

    override fun run() {
        if (limit <= 0) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--limit must be > 0 (got $limit)",
            )
        }
        val log = conflictsCmd.conflictLog()
        val entries = log.recent(limit)

        if (entries.isEmpty()) {
            println("No conflicts recorded.")
            return
        }

        println("%-24s %-40s %-16s %s".format("TIMESTAMP", "PATH", "POLICY", "BACKUP"))
        println(GlyphRenderer.boxHorizontal().repeat(90))
        val ellipsis = GlyphRenderer.ellipsis()
        val dash = GlyphRenderer.dash()
        for (entry in entries) {
            val ts = entry.timestamp.take(19).replace('T', ' ')
            val path = if (entry.path.length > 38) ellipsis + entry.path.takeLast(37) else entry.path
            val backup = if (entry.backupFile != null) AnsiHelper.green(GlyphRenderer.tick()) else AnsiHelper.dim(dash)
            println("%-24s %-40s %-16s %s".format(ts, path, entry.policy, backup))
        }
    }
}

// ── conflicts restore ────────────────────────────────────────────────────────

@Command(name = "restore", description = ["Restore a conflict backup"], mixinStandardHelpOptions = true)
class ConflictsRestoreCommand : Runnable {
    @ParentCommand
    lateinit var conflictsCmd: ConflictsCommand

    @Parameters(index = "0", description = ["Path of the file to restore"])
    lateinit var path: String

    override fun run() {
        val log = conflictsCmd.conflictLog()
        val backups = log.findBackups(path)

        if (backups.isEmpty()) {
            System.err.println("No backups found for: $path")
            System.exit(1)
        }

        val entry = backups.first() // most recent
        val profile = conflictsCmd.parent.resolveCurrentProfile()
        val target = log.restore(entry, profile.syncRoot)
        println("Restored: $path")
        println("  From backup: ${entry.backupFile} (${entry.timestamp.take(19)})")
        println("  Written to: $target")
    }
}

// ── conflicts clear ──────────────────────────────────────────────────────────

@Command(name = "clear", description = ["Delete all conflict backups and log"], mixinStandardHelpOptions = true)
class ConflictsClearCommand : Runnable {
    @ParentCommand
    lateinit var conflictsCmd: ConflictsCommand

    @Option(names = ["-y", "--yes"], description = ["Skip confirmation"])
    var yes: Boolean = false

    override fun run() {
        if (!yes) {
            val console = System.console()
            if (console == null) {
                System.err.println("Error: interactive terminal required. Use -y to skip.")
                System.exit(1)
            }
            print("Delete all conflict backups and log? [y/N] ")
            val answer = console.readLine()?.trim()?.lowercase()
            if (answer != "y") {
                println("Cancelled.")
                return
            }
        }

        conflictsCmd.conflictLog().clear()
        println("Conflict history cleared.")
    }
}
