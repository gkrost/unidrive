package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files

@Command(name = "logout", description = ["Log out and remove stored credentials"], mixinStandardHelpOptions = true)
class LogoutCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--keep-state"], description = ["Keep sync state database (only remove credentials)"])
    var keepState: Boolean = false

    override fun run() {
        val lock = parent.acquireProfileLock()
        try {
            val profile = parent.resolveCurrentProfile()
            val provider = parent.createProvider()
            val profileDir = parent.providerConfigDir()

            // Call provider.logout() to revoke tokens if supported
            try {
                runBlocking { provider.logout() }
            } catch (_: AuthenticationException) {
                // Already not authenticated — proceed with local cleanup
            } catch (_: Exception) {
                // Token revocation failed (network error etc.) — still clean up locally
            }

            // Clean up local files
            val deleted = mutableListOf<String>()

            for (name in StoragePermissions.CREDENTIAL_FILES) {
                val credentialFile = profileDir.resolve(name)
                if (Files.deleteIfExists(credentialFile)) deleted.add(credentialFile.toString())
            }

            if (!keepState) {
                val stateDb = profileDir.resolve("state.db")
                val failures = profileDir.resolve("failures.jsonl")
                if (Files.deleteIfExists(stateDb)) deleted.add(stateDb.toString())
                if (Files.deleteIfExists(failures)) deleted.add(failures.toString())
            }

            val profileLabel = if (profile.name != profile.type) "${provider.displayName} (${profile.name})" else provider.displayName
            println("Logged out of $profileLabel.")
            for (f in deleted) println("  Removed: $f")
            if (keepState) println("  Sync state preserved (--keep-state).")
        } finally {
            lock.unlock()
        }
    }
}
