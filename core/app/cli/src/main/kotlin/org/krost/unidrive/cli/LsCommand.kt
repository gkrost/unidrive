package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.authenticateAndLog
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

/**
 * UD-224: `unidrive [-p <profile>] ls [<path>]` — surface `provider.listChildren(path)`
 * as a 1-level remote directory listing.
 *
 * Does NOT touch `state.db`. That makes it the canonical way to verify "did my upload
 * land?" on a cold profile (no prior `sync`) — contrast with `unidrive log`, which
 * reads the state database. The sibling tool `unidrive_ls` in the MCP is the
 * state-db-backed flavour; keeping the two data sources separate is intentional
 * (see ticket).
 */
@Command(name = "ls", description = ["List children of a remote folder (no recursion)"], mixinStandardHelpOptions = true)
class LsCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Parameters(index = "0", arity = "0..1", defaultValue = "/", description = ["Remote path to list (default: /)"])
    lateinit var path: String

    override fun run() {
        val provider = parent.createProvider()
        val normalized = if (path.startsWith("/")) path else "/$path"

        try {
            runBlocking {
                provider.authenticateAndLog()
                val children = provider.listChildren(normalized)
                printChildren(children)
            }
        } catch (e: AuthenticationException) {
            parent.handleAuthError(e, provider)
        } finally {
            provider.close()
        }
    }

    private fun printChildren(children: List<CloudItem>) {
        if (children.isEmpty()) return
        // Sort: folders first, then files, each alphabetically by name.
        // Matches the Reconciler's ordering so `ls` output lines up with what a
        // subsequent `sync` would walk.
        val sorted =
            children.sortedWith(
                compareByDescending<CloudItem> { it.isFolder }.thenBy { it.name.lowercase() },
            )
        val nameWidth = (sorted.maxOf { it.name.length + if (it.isFolder) 1 else 0 }).coerceAtMost(60)
        for (item in sorted) {
            // Folder suffix is the classic ls-style trailing '/'; mtime is ISO-8601
            // (matches CloudItem.modified.toString()).
            val displayName = if (item.isFolder) item.name + "/" else item.name
            val size = if (item.isFolder) "" else CliProgressReporter.formatSize(item.size)
            val mtime = item.modified?.toString() ?: "-"
            println("%-${nameWidth}s  %10s  %s".format(displayName, size, mtime))
        }
    }
}
