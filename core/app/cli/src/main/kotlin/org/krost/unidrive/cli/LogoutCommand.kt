package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.auth.CredentialProtectionException
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

            val deleted = logoutAndDeleteLocalFiles(profileDir, keepState) { provider.logout() }

            val profileLabel = if (profile.name != profile.type) "${provider.displayName} (${profile.name})" else provider.displayName
            println("Logged out of $profileLabel.")
            for (f in deleted) println("  Removed: $f")
            if (keepState) println("  Sync state preserved (--keep-state).")
        } finally {
            lock.unlock()
        }
    }
}

internal fun logoutAndDeleteLocalFiles(
    profileDir: java.nio.file.Path,
    keepState: Boolean,
    logout: suspend () -> Unit,
): List<String> {
    try {
        runBlocking { logout() }
    } catch (_: AuthenticationException) {
        // Already not authenticated — proceed with local cleanup.
    } catch (e: CredentialProtectionException) {
        // Preserve the marker file if its Secret Service item could not be removed.
        throw e
    } catch (_: Exception) {
        // Token revocation failed (network error etc.) — still clean up locally.
    }

    val deleted = mutableListOf<String>()
    for (name in StoragePermissions.CREDENTIAL_FILES) {
        val file = profileDir.resolve(name)
        if (Files.deleteIfExists(file)) deleted.add(file.toString())
    }
    if (!keepState) {
        for (name in listOf("state.db", "failures.jsonl")) {
            val file = profileDir.resolve(name)
            if (Files.deleteIfExists(file)) deleted.add(file.toString())
        }
    }
    return deleted
}
