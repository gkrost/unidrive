package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.generateProfileToml
import org.krost.unidrive.sync.isProfileAuthenticated
import org.krost.unidrive.sync.isValidProfileName
import org.krost.unidrive.sync.removeProfileSection
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Command(
    name = "profile",
    description = ["Manage provider profiles"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ProfileAddCommand::class,
        ProfileListCommand::class,
        ProfileRemoveCommand::class,
    ],
)
class ProfileCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive profile <add|list|remove>")
    }
}

// ── profile add ──────────────────────────────────────────────────────────────

@Command(name = "add", description = ["Add a new provider profile (interactive wizard)"], mixinStandardHelpOptions = true)
class ProfileAddCommand : Runnable {
    @ParentCommand
    lateinit var profileCmd: ProfileCommand

    override fun run() {
        val console = System.console()
        if (console == null) {
            System.err.println("Error: 'profile add' requires an interactive terminal.")
            System.exit(1)
        }

        val main = profileCmd.parent
        val configPath = main.configBaseDir().resolve("config.toml")

        // Step 1: Pick provider type
        val types = SyncConfig.KNOWN_TYPES.toList().sorted()
        println("Select provider type:")
        types.forEachIndexed { i, t -> println("  ${i + 1}) $t") }
        print("Choice [1-${types.size}]: ")
        val choice = console.readLine()?.trim()?.toIntOrNull()
        if (choice == null || choice !in 1..types.size) {
            System.err.println("Invalid choice.")
            System.exit(1)
        }
        val type = types[choice!! - 1]

        // Step 2: Profile name
        print("Profile name [$type]: ")
        val nameInput = console.readLine()?.trim()
        val name = if (nameInput.isNullOrBlank()) type else nameInput

        // Validate profile name is a valid TOML bare key (no dots, spaces, brackets)
        if (!isValidProfileName(name)) {
            System.err.println("Error: Profile name '$name' is invalid.")
            System.err.println("Use only letters, digits, hyphens, and underscores.")
            System.exit(1)
        }

        // Validate no duplicate profile name
        val raw =
            if (Files.exists(configPath)) {
                SyncConfig.parseRaw(Files.readString(configPath))
            } else {
                SyncConfig.parseRaw("[general]\n")
            }
        if (name in raw.providers) {
            System.err.println("Error: Profile '$name' already exists.")
            System.exit(1)
        }

        // Step 3: Sync root
        val home = System.getenv("HOME") ?: System.getProperty("user.home")
        val defaultRoot = "$home/${type.replaceFirstChar { it.uppercase() }}"
        print("Sync root [$defaultRoot]: ")
        val rootInput = console.readLine()?.trim()
        val syncRoot = if (rootInput.isNullOrBlank()) defaultRoot else rootInput

        // Validate no duplicate sync root
        val dupCheck =
            SyncConfig.detectDuplicateSyncRoots(
                raw.copy(
                    providers =
                        raw.providers + (
                            name to
                                org.krost.unidrive.sync.RawProvider(
                                    type = type,
                                    sync_root = syncRoot,
                                )
                        ),
                ),
            )
        if (dupCheck != null) {
            System.err.println("Error: $dupCheck")
            System.exit(1)
        }

        // Step 4: Credential prompts per type — driven by SPI capability
        val creds = mutableMapOf<String, String>()
        val factory = org.krost.unidrive.ProviderRegistry.get(type)
            ?: error("ProviderRegistry returned null for type=$type after resolveId; impossible state.")
        for (prompt in factory.credentialPrompts()) {
            val default = prompt.default
            val value = when {
                prompt.isMasked -> String(console.readPassword("${prompt.label}: ") ?: charArrayOf())
                default != null -> promptOptional(console, prompt.label, default)
                prompt.required -> promptRequired(console, prompt.label)
                else -> promptOptional(console, prompt.label, "")
            }
            if (prompt.required && value.isBlank()) {
                System.err.println("Error: ${prompt.label} is required.")
                System.exit(1)
            }
            // Don't write empty optional values into config
            if (value.isNotBlank()) {
                creds[prompt.key] = value
            }
        }

        // Step 5: Generate and append TOML
        val toml = generateProfileToml(type, name, syncRoot, creds)

        if (!Files.exists(configPath)) {
            Files.createDirectories(configPath.parent)
            Files.writeString(configPath, "[general]\n\n")
        }
        Files.writeString(configPath, toml, StandardOpenOption.APPEND)

        println()
        println("Profile '${AnsiHelper.bold(name)}' added.")
        println("  Config: $configPath")
        if (factory.supportsInteractiveAuth()) {
            println("  Next: run ${AnsiHelper.bold("unidrive -p $name auth")}")
        }
    }

    private fun promptRequired(
        console: java.io.Console,
        label: String,
    ): String {
        print("$label: ")
        val value = console.readLine()?.trim()
        if (value.isNullOrBlank()) {
            System.err.println("Error: $label is required.")
            System.exit(1)
        }
        return value!!
    }

    private fun promptOptional(
        console: java.io.Console,
        label: String,
        default: String,
    ): String {
        print("$label [$default]: ")
        val value = console.readLine()?.trim()
        return if (value.isNullOrBlank()) default else value
    }
}

// ── profile list ─────────────────────────────────────────────────────────────

@Command(name = "list", description = ["List configured profiles"], mixinStandardHelpOptions = true)
class ProfileListCommand : Runnable {
    @ParentCommand
    lateinit var profileCmd: ProfileCommand

    override fun run() {
        val main = profileCmd.parent
        val configPath = main.configBaseDir().resolve("config.toml")
        val raw =
            if (Files.exists(configPath)) {
                SyncConfig.parseRaw(Files.readString(configPath))
            } else {
                SyncConfig.parseRaw("[general]\n")
            }

        if (raw.providers.isEmpty()) {
            println("No profiles configured.")
            println("Add one with: unidrive profile add")
            return
        }

        println("%-20s %-10s %-30s %s".format("PROFILE", "TYPE", "SYNC ROOT", "AUTH"))
        println(GlyphRenderer.boxHorizontal().repeat(75))
        val baseDir = main.configBaseDir()
        val dash = GlyphRenderer.dash()
        for ((name, rp) in raw.providers.toSortedMap()) {
            val type = rp.type ?: name
            val syncRoot = rp.sync_root ?: SyncConfig.defaultSyncRoot(type).toString()
            val authed = isProfileAuthenticated(type, name, rp, baseDir)
            val authLabel = if (authed) AnsiHelper.green(GlyphRenderer.tick()) else AnsiHelper.dim(dash)
            println("%-20s %-10s %-30s %s".format(name, type, syncRoot, authLabel))
        }
    }
}

// ── profile remove ───────────────────────────────────────────────────────────

@Command(name = "remove", description = ["Remove a provider profile"], mixinStandardHelpOptions = true)
class ProfileRemoveCommand : Runnable {
    @ParentCommand
    lateinit var profileCmd: ProfileCommand

    @Parameters(index = "0", description = ["Profile name to remove"])
    lateinit var name: String

    @Option(names = ["--keep-data"], description = ["Keep profile data (tokens, state.db)"])
    var keepData: Boolean = false

    @Option(names = ["-y", "--yes"], description = ["Skip confirmation prompt"])
    var yes: Boolean = false

    override fun run() {
        val main = profileCmd.parent
        val configPath = main.configBaseDir().resolve("config.toml")

        if (!Files.exists(configPath)) {
            main.reportConfigMissingAndExit(configPath)
        }

        val content = Files.readString(configPath)
        val raw = SyncConfig.parseRaw(content)
        if (name !in raw.providers) {
            System.err.println("Error: Profile '$name' not found.")
            System.err.println("Configured: ${raw.providers.keys.joinToString(", ")}")
            System.exit(1)
        }

        if (!yes) {
            val console = System.console()
            if (console == null) {
                System.err.println("Error: interactive terminal required for confirmation. Use -y to skip.")
                System.exit(1)
            }
            print("Remove profile '$name'? [y/N] ")
            val answer = console.readLine()?.trim()?.lowercase()
            if (answer != "y") {
                println("Cancelled.")
                return
            }
        }

        val newContent = removeProfileSection(content.lines(), name)
        Files.writeString(configPath, newContent.joinToString("\n"))

        if (!keepData) {
            val profileDir = main.configBaseDir().resolve(name)
            if (Files.isDirectory(profileDir)) {
                Files
                    .walk(profileDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
                println("  Deleted: $profileDir")
            }
        }

        println("Profile '$name' removed.")
    }
}

// ── Pure utility functions (testable) ────────────────────────────────────────
//
// UD-216: the helpers below used to live here but are now shared with the MCP
// server. They moved to org.krost.unidrive.sync.ProfileConfigEditor so
// :app:mcp can use them without dragging :app:cli into its classpath. Import
// site kept identical for test and source callers.
//
//   generateProfileToml, removeProfileSection, isValidProfileName,
//   escapeTomlValue, isProfileAuthenticated, updateProfileKey
