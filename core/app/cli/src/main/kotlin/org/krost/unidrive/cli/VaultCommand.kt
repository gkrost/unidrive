package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.sync.escapeTomlValue
import org.krost.unidrive.xtra.XtraKeyManager
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.nio.file.Files

@Command(
    name = "vault",
    description = ["Manage encrypted credential vault"],
    mixinStandardHelpOptions = true,
    subcommands = [
        VaultInitCommand::class,
        VaultEncryptCommand::class,
        VaultDecryptCommand::class,
        VaultChangePassphraseCommand::class,
        VaultXtraInitCommand::class,
        VaultXtraStatusCommand::class,
    ],
)
class VaultCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive vault <init|encrypt|decrypt|change-passphrase>")
    }
}

// ── vault init ───────────────────────────────────────────────────────────────

@Command(name = "init", description = ["Initialize a new credential vault"], mixinStandardHelpOptions = true)
class VaultInitCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    override fun run() {
        val vault = Vault(vaultCmd.parent.configBaseDir().resolve("vault.enc"))
        if (vault.exists()) {
            System.err.println("Vault already exists. Use 'vault change-passphrase' to update.")
            System.exit(1)
        }

        val console = System.console()
        if (console == null) {
            System.err.println("Error: interactive terminal required.")
            System.exit(1)
        }

        val pass1 = console.readPassword("New vault passphrase (min ${Vault.MIN_PASSPHRASE_LENGTH} chars): ")
        val pass2 = console.readPassword("Confirm passphrase: ")

        if (!pass1.contentEquals(pass2)) {
            System.err.println("Passphrases do not match.")
            System.exit(1)
        }
        if (pass1.size < Vault.MIN_PASSPHRASE_LENGTH) {
            System.err.println("Passphrase must be at least ${Vault.MIN_PASSPHRASE_LENGTH} characters.")
            System.exit(1)
        }

        vault.init(pass1)
        println("Vault initialized at ${vaultCmd.parent.configBaseDir().resolve("vault.enc")}")
    }
}

// ── vault encrypt ────────────────────────────────────────────────────────────

@Command(name = "encrypt", description = ["Move config credentials into the vault"], mixinStandardHelpOptions = true)
class VaultEncryptCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    override fun run() {
        val baseDir = vaultCmd.parent.configBaseDir()
        val vault = Vault(baseDir.resolve("vault.enc"))
        if (!vault.exists()) {
            System.err.println("No vault found. Run 'unidrive vault init' first.")
            System.exit(1)
        }

        val passphrase = readPassphrase("Vault passphrase: ")
        val data = vault.read(passphrase)
        val mutableData = data.toMutableMap()

        val configPath = baseDir.resolve("config.toml")
        if (!Files.exists(configPath)) {
            println("No config.toml found — nothing to encrypt.")
            return
        }

        val configLines = Files.readAllLines(configPath)
        val raw = SyncConfig.parseRaw(configLines.joinToString("\n"))
        val linesToRemove = mutableSetOf<Int>()
        var encryptedCount = 0

        // Build section ranges: [providers.<name>] → line indices
        val sectionRanges = mutableMapOf<String, IntRange>()
        val sectionPattern = Regex("""^\[providers\.(.+)]$""")
        var currentSection: String? = null
        var currentStart = -1
        for ((idx, line) in configLines.withIndex()) {
            val match = sectionPattern.matchEntire(line.trim())
            if (match != null) {
                if (currentSection != null) {
                    sectionRanges[currentSection!!] = currentStart until idx
                }
                currentSection = match.groupValues[1]
                currentStart = idx
            }
        }
        if (currentSection != null) {
            sectionRanges[currentSection!!] = currentStart until configLines.size
        }

        for ((profileName, rp) in raw.providers) {
            val creds = mutableMapOf<String, String>()
            extractIfPresent(rp.access_key_id, "access_key_id", creds)
            extractIfPresent(rp.secret_access_key, "secret_access_key", creds)
            extractIfPresent(rp.password, "password", creds)
            extractIfPresent(rp.client_id, "client_id", creds)
            extractIfPresent(rp.client_secret, "client_secret", creds)

            if (creds.isNotEmpty()) {
                mutableData[profileName] = (mutableData[profileName] ?: emptyMap()) + creds
                encryptedCount++
                // Mark lines for removal — scoped to this profile's section only
                val range = sectionRanges[profileName] ?: continue
                for (key in creds.keys) {
                    for (idx in range) {
                        val trimmed = configLines[idx].trim()
                        if (trimmed.startsWith("$key ") || trimmed.startsWith("$key=")) {
                            linesToRemove.add(idx)
                        }
                    }
                }
            }
        }

        if (encryptedCount == 0) {
            println("No sensitive credentials found in config.toml.")
            return
        }

        vault.write(passphrase, mutableData)

        val filteredLines = configLines.filterIndexed { idx, _ -> idx !in linesToRemove }
        Files.writeString(configPath, filteredLines.joinToString("\n"))

        println("Encrypted credentials for $encryptedCount profile(s).")
    }

    private fun extractIfPresent(
        value: String?,
        key: String,
        target: MutableMap<String, String>,
    ) {
        if (!value.isNullOrBlank()) target[key] = value
    }
}

// ── vault decrypt ────────────────────────────────────────────────────────────

@Command(name = "decrypt", description = ["Restore credentials from vault to config.toml"], mixinStandardHelpOptions = true)
class VaultDecryptCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    override fun run() {
        val baseDir = vaultCmd.parent.configBaseDir()
        val vault = Vault(baseDir.resolve("vault.enc"))
        if (!vault.exists()) {
            System.err.println("No vault found.")
            System.exit(1)
        }

        val passphrase = readPassphrase("Vault passphrase: ")
        val data = vault.read(passphrase)

        if (data.isEmpty()) {
            println("Vault is empty — nothing to restore.")
            vault.delete()
            return
        }

        val configPath = baseDir.resolve("config.toml")
        val configContent = if (Files.exists(configPath)) Files.readString(configPath) else "[general]\n"
        val lines = configContent.lines().toMutableList()

        for ((profileName, creds) in data) {
            val sectionHeader = "[providers.$profileName]"
            val sectionIdx = lines.indexOfFirst { it.trim() == sectionHeader }
            if (sectionIdx == -1) continue

            // Find insertion point: after last key in section, before next section
            var insertIdx = sectionIdx + 1
            while (insertIdx < lines.size) {
                val trimmed = lines[insertIdx].trim()
                if (trimmed.startsWith("[") && trimmed != sectionHeader) break
                insertIdx++
            }
            // Insert before the blank line preceding next section
            if (insertIdx > 0 && lines[insertIdx - 1].isBlank()) insertIdx--

            val credLines = creds.entries.map { (k, v) -> "$k = \"${escapeTomlValue(v)}\"" }
            lines.addAll(insertIdx, credLines)
        }

        Files.writeString(configPath, lines.joinToString("\n"))
        vault.delete()
        println("Credentials restored to config.toml. Vault deleted.")
    }
}

// ── vault change-passphrase ──────────────────────────────────────────────────

@Command(name = "change-passphrase", description = ["Change the vault passphrase"], mixinStandardHelpOptions = true)
class VaultChangePassphraseCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    override fun run() {
        val vault = Vault(vaultCmd.parent.configBaseDir().resolve("vault.enc"))
        if (!vault.exists()) {
            System.err.println("No vault found. Run 'unidrive vault init' first.")
            System.exit(1)
        }

        val console = System.console()
        if (console == null) {
            System.err.println("Error: interactive terminal required.")
            System.exit(1)
        }

        val oldPass = console.readPassword("Current passphrase: ")
        val data = vault.read(oldPass)

        val newPass1 = console.readPassword("New passphrase (min ${Vault.MIN_PASSPHRASE_LENGTH} chars): ")
        val newPass2 = console.readPassword("Confirm new passphrase: ")

        if (!newPass1.contentEquals(newPass2)) {
            System.err.println("Passphrases do not match.")
            System.exit(1)
        }
        if (newPass1.size < Vault.MIN_PASSPHRASE_LENGTH) {
            System.err.println("Passphrase must be at least ${Vault.MIN_PASSPHRASE_LENGTH} characters.")
            System.exit(1)
        }

        vault.write(newPass1, data)
        println("Vault passphrase changed.")
    }
}

// ── vault xtra init ──────────────────────────────────────────────────────────

@Command(name = "xtra-init", description = ["Initialize Xtra encryption keys for E2EE"], mixinStandardHelpOptions = true)
class VaultXtraInitCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    override fun run() {
        val keyDir = vaultCmd.parent.providerConfigDir().resolve("xtra")
        val keyPath = keyDir.resolve("key")

        if (Files.exists(keyPath)) {
            System.err.println("Xtra keys already exist. Use 'vault xtra-status' to view.")
            System.err.println("To re-key, move ${keyDir.resolve("key")} and re-run.")
            System.exit(1)
        }

        val console = System.console()
        if (console == null) {
            System.err.println("Error: interactive terminal required.")
            System.exit(1)
        }

        println("Xtra Encryption - End-to-End Encryption for your files")
        println("Your data is encrypted locally before upload. Only you can decrypt it.")
        println()

        val pass = console.readPassword("Set encryption passphrase (min 8 chars): ")
        if (pass.size < 8) {
            System.err.println("Passphrase must be at least 8 characters.")
            System.exit(1)
        }

        val manager = XtraKeyManager(keyPath)
        Files.createDirectories(keyDir)
        manager.generate(pass)
        println()
        println("Xtra keys initialized at $keyPath")
        println("IMPORTANT: Back up this file. Loss = data loss.")
    }
}

// ── vault xtra status ──────────────────────────────────────────────────────────

@Command(name = "xtra-status", description = ["Show Xtra encryption key status"], mixinStandardHelpOptions = true)
class VaultXtraStatusCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    override fun run() {
        val keyDir = vaultCmd.parent.providerConfigDir().resolve("xtra")
        val keyPath = keyDir.resolve("key")

        if (!Files.exists(keyPath)) {
            println("Xtra: Not initialized")
            println("Run 'unidrive vault xtra-init' to enable E2EE")
            return
        }

        println("Xtra: Initialized")
        println("Key file: $keyPath")
    }
}

// ── Shared helper ────────────────────────────────────────────────────────────

private fun readPassphrase(prompt: String): CharArray {
    val console = System.console()
    if (console == null) {
        System.err.println("Error: interactive terminal required.")
        System.exit(1)
    }
    return console!!.readPassword(prompt) ?: run {
        System.err.println("Error: could not read passphrase.")
        System.exit(1)
        throw IllegalStateException("unreachable")
    }
}
