package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcAuthException
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Callable

/**
 * `unidrive [-p <profile>] ls [<path>]` — list children of a remote folder (no recursion).
 *
 * Source of truth: when a daemon is running for the profile (its IPC socket
 * exists), `ls` queries the daemon's `state.db` view via `hydration.list` — the
 * SAME view the FUSE mount serves. This keeps the two surfaces consistent: a
 * remote change is reflected in `ls` exactly when (and only when) it is reflected
 * in the mount, so the two can never contradict each other during the stale
 * window. To pull fresh remote state into that view, run `unidrive refresh`.
 *
 * When no daemon is running (cold profile, no prior `sync`/`mount`), `ls` falls
 * back to a live `provider.listChildren(path)` query — the canonical way to
 * verify "did my upload land?" without a daemon. With no daemon there is no
 * mount view to disagree with, so the consistency concern does not apply.
 */
@Command(name = "ls", description = ["List children of a remote folder (no recursion)"], mixinStandardHelpOptions = true)
class LsCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    @Parameters(index = "0", arity = "0..1", defaultValue = "/", description = ["Remote path to list (default: /)"])
    lateinit var path: String

    @Option(
        names = ["--live"],
        description = [
            "Force a live provider query even when a daemon is running. By default " +
                "ls reads the daemon's state.db view (the same source the mount serves) " +
                "so the two surfaces stay consistent.",
        ],
    )
    var live: Boolean = false

    override fun call(): Int {
        val normalized = normalizeLsPath(path)
        val profile = parent.resolveCurrentProfile()
        val socketPath = IpcServer.defaultSocketPath(profile.name)

        // Single source of truth: when a DAEMON serves this profile, read its
        // state.db view so ls agrees with the mount. --live opts out. A plain
        // `unidrive sync` watcher binds the same socket + hydration verbs, but its
        // snapshot is not the mount view — identify the daemon positively via the
        // lock-pid modeToken (the same signal `daemon status` uses) and otherwise
        // fall through to the documented live provider query.
        if (!live && daemonHoldsLock(parent.providerConfigDir()) && Files.exists(socketPath)) {
            val endpoint = IpcEndpoint(socketPath, parent.providerConfigDir(), profile.name)
            val entries = queryDaemonView(endpoint, normalized)
            if (entries != null) {
                when (val view = resolveDaemonView(normalized, entries) { queryDaemonView(endpoint, it) }) {
                    is DaemonLsView.Listing -> {
                        printDaemonEntries(view.entries)
                        return 0
                    }
                    is DaemonLsView.SelfFile -> {
                        printDaemonEntries(listOf(view.entry))
                        return 0
                    }
                    DaemonLsView.NoSuchPath -> return noSuchPath(normalized)
                    DaemonLsView.Undecidable -> {
                        // Parent lookup failed mid-way (daemon shut down between
                        // the two IPC round-trips) — presence is undecidable from
                        // the view; the live query below answers authoritatively.
                    }
                }
            }
            // Daemon socket exists but the query failed (mid-shutdown / stale
            // socket). Fall through to the live query rather than failing.
        }

        val provider = parent.createProvider()
        val listing =
            try {
                runBlocking {
                    provider.authenticateAndLog()
                    listLive(provider, normalized)
                }
            } catch (e: AuthenticationException) {
                parent.handleAuthError(e, provider)
                return 1
            } finally {
                provider.close()
            }
        if (listing == null) return noSuchPath(normalized)
        printChildren(listing)
        return 0
    }

    private fun noSuchPath(normalized: String): Int {
        System.err.println("ls: no such remote path: $normalized")
        return 1
    }

    /**
     * Query the daemon's state.db view for the direct children of [normalized].
     * Returns the parsed entries, or null if the daemon could not be reached or
     * replied with an error (caller falls back to the live provider query).
     * `internal` so the ls-agrees-with-mount-view test can assert it returns the
     * same view `hydration.list` serves.
     */
    internal fun queryDaemonView(endpoint: IpcEndpoint, normalized: String): List<ViewEntryParsed>? {
        // hydration.list uses "prefix"; "/" and "" both mean root.
        val prefix = if (normalized == "/") "" else normalized
        return try {
            // A read-scope connection: ls only lists (docs/dev/specs/ipc-authentication.md).
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ).use { channel ->
                val req = """{"verb":"hydration.list","prefix":${jsonStr(prefix)}}""" + "\n"
                channel.write(ByteBuffer.wrap(req.toByteArray()))
                val reply = readOneJsonReply(channel)
                if (!reply.contains("\"ok\":true")) return null
                parseListEntries(reply)
            }
        } catch (e: IpcAuthException) {
            // Not answered without authentication; the live query below still answers.
            System.err.println("ls: ${e.message}; listing live instead")
            null
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun printDaemonEntries(entries: List<ViewEntryParsed>) {
        if (entries.isEmpty()) return
        val sorted =
            entries.sortedWith(
                compareByDescending<ViewEntryParsed> { it.isFolder }.thenBy { it.basename().lowercase() },
            )
        val nameWidth = (sorted.maxOf { it.basename().length + if (it.isFolder) 1 else 0 }).coerceAtMost(60)
        for (item in sorted) {
            val displayName = if (item.isFolder) item.basename() + "/" else item.basename()
            val size = if (item.isFolder) "" else CliProgressReporter.formatSize(item.size)
            // Render mtime as ISO-8601, matching the live path's CloudItem.modified.toString().
            val mtime = if (item.mtimeMs > 0) Instant.ofEpochMilli(item.mtimeMs).toString() else "-"
            println("%-${nameWidth}s  %10s  %s".format(displayName, size, mtime))
        }
    }

    private fun ViewEntryParsed.basename(): String = path.substringAfterLast('/')

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

    /** Read a single JSON reply terminated by newline. Returns the line without the newline. */
    private fun readOneJsonReply(channel: SocketChannel): String {
        val collected = StringBuilder()
        while (!collected.contains('\n')) {
            val buf = ByteBuffer.allocate(4096)
            val n = channel.read(buf)
            if (n <= 0) return collected.toString()
            buf.flip()
            collected.append(String(buf.array(), 0, buf.limit()))
        }
        return collected.toString().substringBefore('\n')
    }

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /**
         * Root-absolute, trailing-slash-free form of the user-supplied [path].
         * `hydration.list` and state.db paths carry no trailing slash — without
         * trimming, `ls foo/` would compare `/foo/` against the stored `/foo`
         * and report an existing (empty) folder as missing.
         */
        internal fun normalizeLsPath(path: String): String =
            (if (path.startsWith("/")) path else "/$path").trimEnd('/').ifEmpty { "/" }

        /** What `ls` should print when the daemon's state.db view is reachable. */
        internal sealed interface DaemonLsView {
            /** Direct children of the queried path (possibly empty — an existing folder). */
            data class Listing(val entries: List<ViewEntryParsed>) : DaemonLsView

            /** The queried path is a file: list that single entry, like `ls <file>`. */
            data class SelfFile(val entry: ViewEntryParsed) : DaemonLsView

            /** The parent view is enumerated and does not contain the path. */
            data object NoSuchPath : DaemonLsView

            /**
             * The parent query failed (daemon shut down between the two IPC
             * round-trips) — presence cannot be decided from the view; the
             * caller falls through to the live provider query.
             */
            data object Undecidable : DaemonLsView
        }

        /**
         * Resolve what `ls` should print for [normalized] given its [children]
         * from the daemon view. `hydration.list` answers a missing path with an
         * empty list, same as an empty folder — an empty non-root listing is
         * therefore resolved against the parent's view: absent there means the
         * path does not exist (ls stays consistent with the mount view), a file
         * there lists as that single entry. One caveat accepted for that
         * consistency: during a first enumeration the view can be incomplete,
         * so a not-yet-enumerated existing path reports as missing — `--live`
         * is the escape hatch.
         */
        internal fun resolveDaemonView(
            normalized: String,
            children: List<ViewEntryParsed>,
            parentQuery: (String) -> List<ViewEntryParsed>?,
        ): DaemonLsView =
            when {
                children.isNotEmpty() || normalized == "/" -> DaemonLsView.Listing(children)
                else -> {
                    val parent = parentQuery(normalized.substringBeforeLast('/').ifEmpty { "/" })
                    val self = parent?.find { it.path == normalized }
                    when {
                        parent == null -> DaemonLsView.Undecidable
                        self == null -> DaemonLsView.NoSuchPath
                        self.isFolder -> DaemonLsView.Listing(children)
                        else -> DaemonLsView.SelfFile(self)
                    }
                }
            }

        /**
         * Live listing of [normalized], or null when the path does not exist. Some
         * providers (localfs) return an empty list for a missing path instead of
         * throwing, which made `ls /typo` indistinguishable from an empty folder, so
         * an empty non-root listing is confirmed with [CloudProvider.getMetadata].
         * A file path lists as that single file, like `ls <file>`.
         */
        internal suspend fun listLive(
            provider: CloudProvider,
            normalized: String,
        ): List<CloudItem>? {
            val children = provider.listChildren(normalized)
            if (children.isNotEmpty() || normalized == "/") return children
            val self =
                try {
                    provider.getMetadata(normalized)
                } catch (_: java.io.FileNotFoundException) {
                    return null
                }
            return if (self.isFolder) children else listOf(self)
        }

        // hydration.list reply entry shape (serialiseListEntries in HydrationIpcHandler):
        //   {"path":"...","size":N,"mtime_ms":N,"hydrated":bool,"folder":bool[,<additive fields>]}
        // The fields after `folder` (remote_modified_ms, remote_id, etag,
        // pending_upload, error) are additive and skipped by the tail group, so
        // old/new daemon replies both parse.
        private val ENTRY_REGEX =
            Regex(
                """\{"path":"((?:[^"\\]|\\.)*)","size":(-?\d+),"mtime_ms":(-?\d+),"hydrated":(?:true|false),"folder":(true|false)(?:,[^}]*)?\}""",
            )

        /**
         * Parse the `entries` array out of a `hydration.list` ok reply. Mirrors
         * the wire shape emitted by [org.krost.unidrive.hydration.HydrationIpcHandler]'s
         * serialiseListEntries; kept regex-based to avoid a serialization dep on the
         * cli module just for this read-only listing.
         */
        fun parseListEntries(reply: String): List<ViewEntryParsed> =
            ENTRY_REGEX.findAll(reply).map { m ->
                ViewEntryParsed(
                    path = unescapeJson(m.groupValues[1]),
                    size = m.groupValues[2].toLong(),
                    mtimeMs = m.groupValues[3].toLong(),
                    isFolder = m.groupValues[4].toBoolean(),
                )
            }.toList()

        private fun unescapeJson(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1]); i += 2
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }

        /** Public parse-result type so the parser is unit-testable without an IPC round-trip. */
        data class ViewEntryParsed(
            val path: String,
            val size: Long,
            val mtimeMs: Long,
            val isFolder: Boolean,
        )
    }
}

/**
 * True only when a DAEMON holds the profile lock — read from the lock-pid `modeToken`,
 * the same signal `daemon status` uses. A plain `unidrive sync` watcher binds the same
 * socket and hydration verbs but is NOT the mount view, so `ls` must mirror the daemon's
 * state.db view only in this case (otherwise it does the documented live provider query).
 */
internal fun daemonHoldsLock(configDir: java.nio.file.Path): Boolean {
    val lockFile = configDir.resolve(".lock")
    val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
    return (readLockPid(pidFile) as? LockPidReadResult.Present)?.contents?.modeToken == "daemon"
}
