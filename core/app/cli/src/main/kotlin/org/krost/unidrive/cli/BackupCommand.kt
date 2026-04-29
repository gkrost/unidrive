package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.net.InetAddress
import java.nio.file.Files

@Command(
    name = "backup",
    description = ["Manage backup profiles"],
    mixinStandardHelpOptions = true,
    subcommands = [
        BackupAddCommand::class,
        BackupListCommand::class,
    ],
)
class BackupCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive backup <add|list>")
    }
}

@Command(name = "add", description = ["Create a backup profile"], mixinStandardHelpOptions = true)
class BackupAddCommand : Runnable {
    @ParentCommand
    lateinit var backupCmd: BackupCommand

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--name"], description = ["Backup profile name"], required = true)
    var backupName: String = ""

    @Option(names = ["--provider"], description = ["Provider type (onedrive, s3, etc)"], required = true)
    var providerType: String = ""

    @Option(names = ["--target"], description = ["Target remote path (default: /backups/<hostname>)"])
    var remotePath: String = ""

    override fun run() {
        if (backupName.isEmpty()) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--name must not be empty",
            )
        }
        if ('/' in backupName || '\\' in backupName) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--name must not contain '/' or '\\\\' (got '$backupName')",
            )
        }
        val configBase = backupCmd.parent.configBaseDir()
        val configFile = configBase.resolve("config.toml")

        if (!Files.exists(configFile)) {
            backupCmd.parent.reportConfigMissingAndExit(configFile)
        }

        val effectivePath = if (remotePath.isNotEmpty()) remotePath else defaultBackupTarget()

        val backupSection = "[providers.$backupName]"
        val config =
            """
            |$backupSection
            |  type = "$providerType"
            |  sync_direction = "upload"
            |  sync_root = "~/unidrive-backup-$backupName"
            |  remote_path = "$effectivePath"
            |  file_versioning = true
            |  max_versions = 5
            |  trash_emulation = true
            |  trash_retention_days = 30
            """.trimMargin()

        val existing = if (Files.exists(configFile)) Files.readString(configFile) else ""
        val hasSection = existing.contains("$backupSection")

        if (hasSection) {
            System.err.println("Warning: profile '$backupName' already exists. Updating.")
        }

        val newContent =
            if (hasSection) {
                existing
            } else if (existing.isNotEmpty()) {
                existing + "\n\n" + config
            } else {
                """
            |[general]
            |
            $config
                """.trimMargin()
            }

        Files.writeString(configFile, newContent)
        println("Backup profile '$backupName' created:")
        println("  Provider:    $providerType")
        println("  Direction:  upload only")
        println("  Target:     $effectivePath")
        println("  Versioning:  5 versions, 90 days")
        println("  Trash:      30 days")
        println()
        println("Next steps:")
        println("  unidrive -p $backupName auth")
        println("  unidrive -p $backupName sync --watch")
    }
}

@Command(name = "list", description = ["List backup profiles"], mixinStandardHelpOptions = true)
class BackupListCommand : Runnable {
    @ParentCommand
    lateinit var backupCmd: BackupCommand

    override fun run() {
        val configBase = backupCmd.parent.configBaseDir()
        val configFile = configBase.resolve("config.toml")

        if (!Files.exists(configFile)) {
            println("No backup profiles found.")
            return
        }

        val configText = Files.readString(configFile)
        val raw = SyncConfig.parseRaw(configText)
        val profiles = raw.providers

        if (profiles.isEmpty()) {
            println("No profiles found.")
            return
        }

        // Extract per-section fields that ktoml drops (sync_direction, file_versioning)
        val sectionFields = parseSectionFields(configText)

        println("%-20s  %-12s  %-10s  %-24s  %s".format("Profile", "Provider", "Direction", "Target", "Sync Root"))
        println("-".repeat(92))
        for ((name, cfg) in profiles) {
            val fields = sectionFields[name] ?: emptyMap()
            val direction = fields["sync_direction"] ?: "bidi"
            val versioning = fields["file_versioning"] == "true"
            val target = cfg?.remote_path ?: "-"
            println(
                "%-20s  %-12s  %-10s  %-24s  %s%s".format(
                    name,
                    cfg?.type ?: "?",
                    direction,
                    target,
                    cfg?.sync_root ?: "~/",
                    if (versioning) "  [versioned]" else "",
                ),
            )
        }
        println()
        println("${profiles.size} profile(s)")
    }
}

/** Parse TOML text to extract key=value pairs per [providers.X] section. */
internal fun parseSectionFields(toml: String): Map<String, Map<String, String>> {
    val result = mutableMapOf<String, MutableMap<String, String>>()
    var currentSection: String? = null
    val sectionRegex = Regex("""\[providers\.(\S+)]""")
    val kvRegex = Regex("""^\s*(\w+)\s*=\s*"?([^"]*)"?\s*$""")
    for (line in toml.lines()) {
        val sectionMatch = sectionRegex.find(line)
        if (sectionMatch != null) {
            currentSection = sectionMatch.groupValues[1]
            result.getOrPut(currentSection) { mutableMapOf() }
            continue
        }
        if (line.trimStart().startsWith("[")) {
            currentSection = null
            continue
        }
        if (currentSection != null) {
            val kvMatch = kvRegex.find(line)
            if (kvMatch != null) {
                result[currentSection]!![kvMatch.groupValues[1]] = kvMatch.groupValues[2]
            }
        }
    }
    return result
}

/** Compute default backup target path using device hostname. */
internal fun defaultBackupTarget(): String {
    val hostname =
        try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "unknown"
        }
    return "/backups/$hostname"
}
