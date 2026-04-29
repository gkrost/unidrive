package org.krost.unidrive.cli

import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand

@Command(name = "log", description = ["Show recent sync activity"], mixinStandardHelpOptions = true)
class LogCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        val dbPath = parent.providerConfigDir().resolve("state.db")
        val db = StateDatabase(dbPath)
        db.initialize()

        val entries = db.getAllEntries().sortedByDescending { it.lastSynced }
        val recent = entries.take(20)

        if (recent.isEmpty()) {
            println("No sync history.")
            db.close()
            return
        }

        val profile = parent.resolveCurrentProfile()
        // UD-212: identify both the profile key AND the provider type so multi-account setups
        // (two OneDrive profiles, three SFTP profiles, …) show which account we're reading.
        // Identity (UPN/email) and quota are deferred — they require network or per-provider
        // token parsing; track via UD-214 (offline-friendly quota cache) and UD-111 (token
        // telemetry).
        val displayName =
            ProviderRegistry.getMetadata(profile.type)?.displayName
                ?: profile.type.replaceFirstChar { it.uppercase() }
        println("Recent sync entries for ${AnsiHelper.bold(profile.name)} · $displayName:")
        for (entry in recent) {
            val status =
                when {
                    entry.isFolder -> "DIR "
                    entry.isHydrated -> "FILE"
                    else -> "STUB"
                }
            val size = if (!entry.isFolder) " ${CliProgressReporter.formatSize(entry.remoteSize)}" else ""
            println("  [$status] ${entry.path}$size  (synced: ${entry.lastSynced})")
        }

        db.close()
    }
}
