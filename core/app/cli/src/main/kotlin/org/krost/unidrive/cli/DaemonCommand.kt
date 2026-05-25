package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

@Command(
    name = "daemon",
    description = ["Manage the per-profile daemon (IPC server, hydration, refresh)"],
    mixinStandardHelpOptions = true,
    subcommands = [
        DaemonRunCommand::class,
        DaemonStatusCommand::class,
        DaemonStopCommand::class,
    ],
)
class DaemonCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive daemon <run|status|stop>")
    }
}

// ── daemon run ───────────────────────────────────────────────────────────────

@Command(name = "run", description = ["Run the daemon in the foreground until SIGTERM"], mixinStandardHelpOptions = true)
class DaemonRunCommand : Runnable {
    @ParentCommand
    lateinit var daemonCmd: DaemonCommand

    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "<profile>",
        description = ["Profile name (alternative to the global -p option)"],
    )
    var profilePositional: String? = null

    override fun run() {
        val parent = daemonCmd.parent
        applyPositionalProfile(parent, profilePositional)
        val profile = parent.resolveCurrentProfile()
        val config = parent.loadSyncConfig()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val dbPath = parent.providerConfigDir().resolve("state.db")
        val socketPath = IpcServer.defaultSocketPath(profile.name)

        val runtime = DaemonRuntime(
            profileName = profile.name,
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = config.syncRoot,
            socketPath = socketPath,
            providerFactory = { parent.createProvider() },
        )

        // Install SIGTERM handler that signals graceful shutdown.
        Runtime.getRuntime().addShutdownHook(Thread {
            runtime.close()
        })

        runBlocking { runtime.start() }
    }
}

// ── daemon status ────────────────────────────────────────────────────────────

/**
 * Read-only status for the running daemon.
 *
 * Two-stage output per spec unidrive-daemon-design.md §3.3:
 *   1. `.lock.pid` (file-derived): PID + mode — always printed when present.
 *   2. `daemon.status` RPC reply (uptime_ms, clients_connected,
 *      refresh_in_flight, refresh_job_id) — printed when the socket is
 *      reachable.
 *
 * If `.lock.pid` exists but the socket is gone (daemon mid-shutdown or
 * kill -9'd), prints the file-derived line and a "socket unreachable" note.
 * Honors the chicken-and-egg constraint: never need the daemon to be up
 * just to know whether it's up.
 */
@Command(name = "status", description = ["Show daemon status for the current profile"], mixinStandardHelpOptions = true)
class DaemonStatusCommand : Runnable {
    @ParentCommand
    lateinit var daemonCmd: DaemonCommand

    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "<profile>",
        description = ["Profile name (alternative to the global -p option)"],
    )
    var profilePositional: String? = null

    override fun run() {
        val parent = daemonCmd.parent
        applyPositionalProfile(parent, profilePositional)
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")

        when (val result = readLockPid(pidFile)) {
            is LockPidReadResult.Absent -> {
                System.err.println("no daemon running for profile '${profile.name}'")
                System.exit(1)
                return
            }
            is LockPidReadResult.Malformed -> {
                System.err.println("malformed lock-pid file at $pidFile: '${result.raw}'")
                System.exit(1)
                return
            }
            is LockPidReadResult.Present -> {
                val pid = result.contents.pid
                val modeToken = result.contents.modeToken ?: "(no-mode)"
                println("pid $pid, mode $modeToken")

                // RPC enrichment per spec §4.3 — file-derived data above,
                // RPC-derived below. The chicken-and-egg case is honored: a
                // missing socket file means we can't reach the daemon, but
                // we've already printed the file-derived line.
                val socketPath = org.krost.unidrive.sync.IpcServer.defaultSocketPath(profile.name)
                if (!java.nio.file.Files.exists(socketPath)) {
                    System.err.println("daemon socket not found at $socketPath (daemon may be mid-shutdown)")
                    return
                }
                try {
                    java.nio.channels.SocketChannel.open(
                        java.net.UnixDomainSocketAddress.of(socketPath),
                    ).use { channel ->
                        channel.write(
                            java.nio.ByteBuffer.wrap(
                                ("""{"verb":"daemon.status"}""" + "\n").toByteArray(),
                            ),
                        )
                        val buf = java.nio.ByteBuffer.allocate(1024)
                        channel.read(buf)
                        buf.flip()
                        val reply = String(buf.array(), 0, buf.limit()).substringBefore('\n')
                        println(reply)
                    }
                } catch (e: java.io.IOException) {
                    System.err.println("daemon socket unreachable: ${e.message}")
                }
            }
        }
    }
}

// ── daemon stop ──────────────────────────────────────────────────────────────

@Command(name = "stop", description = ["Send SIGTERM to the running daemon and wait for it to exit"], mixinStandardHelpOptions = true)
class DaemonStopCommand : Runnable {
    @ParentCommand
    lateinit var daemonCmd: DaemonCommand

    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "<profile>",
        description = ["Profile name (alternative to the global -p option)"],
    )
    var profilePositional: String? = null

    companion object {
        const val STOP_DEADLINE_MS: Long = 12_000  // 10s graceful + 2s buffer
    }

    override fun run() {
        val parent = daemonCmd.parent
        applyPositionalProfile(parent, profilePositional)
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")

        val contents = when (val result = readLockPid(pidFile)) {
            is LockPidReadResult.Absent -> {
                println("no daemon running for profile '${profile.name}'")
                return  // idempotent stop; exit 0
            }
            is LockPidReadResult.Malformed -> {
                System.err.println("malformed lock-pid file at $pidFile: '${result.raw}'")
                System.exit(1)
                return
            }
            is LockPidReadResult.Present -> result.contents
        }
        val pid = contents.pid
        val modeToken = contents.modeToken
        if (modeToken != "daemon") {
            System.err.println(
                "lock for profile '${profile.name}' is held by mode '$modeToken', not 'daemon'. " +
                    "Use the appropriate stop mechanism (e.g. `kill $pid` for sync).",
            )
            System.exit(1)
            return
        }

        // Send SIGTERM via ProcessHandle.
        val handle = ProcessHandle.of(pid).orElse(null)
        if (handle == null || !handle.isAlive) {
            println("daemon for profile '${profile.name}' (PID $pid) is not running (stale .lock.pid); cleaning up")
            runCatching { Files.deleteIfExists(pidFile) }
            return
        }
        handle.destroy()  // SIGTERM
        handle.onExit().orTimeout(STOP_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .handle { _, _ -> true }.get()
        if (!handle.isAlive) {
            println("daemon for profile '${profile.name}' stopped")
        } else {
            System.err.println(
                "daemon for profile '${profile.name}' did not exit within ${STOP_DEADLINE_MS}ms; " +
                    "send SIGKILL manually if needed (`kill -9 $pid`).",
            )
            System.exit(1)
        }
    }
}

// ── .lock.pid parsing helper ────────────────────────────────────────────────

/**
 * Parsed contents of a `.lock.pid` sidecar file.
 *
 * Per spec mount-sync-mode-mutex-design.md §3.1, the wire format is
 * `<pid> <mode>\n`. This data class exposes the two fields; consumers
 * that need to interpret the mode (e.g. distinguish `daemon` from `sync`)
 * use [LockPidContents.modeToken] directly.
 *
 * @property pid the holder's process ID.
 * @property modeToken the wire-format mode token (e.g. "daemon", "sync"),
 *   or null when the sidecar carries no mode field (legacy pid-only format).
 */
internal data class LockPidContents(val pid: Long, val modeToken: String?)

/**
 * Result of trying to read a `.lock.pid` file.
 */
internal sealed class LockPidReadResult {
    object Absent : LockPidReadResult()
    data class Malformed(val raw: String) : LockPidReadResult()
    data class Present(val contents: LockPidContents) : LockPidReadResult()
}

/**
 * Read the `.lock.pid` sidecar at [pidFile]. Returns one of three states:
 *   - [LockPidReadResult.Absent] — file doesn't exist
 *   - [LockPidReadResult.Malformed] — file exists but pid field is unparseable
 *   - [LockPidReadResult.Present] — file parsed cleanly
 *
 * Used by both DaemonStatusCommand and DaemonStopCommand to avoid
 * duplicating the parse logic. If a third consumer arrives (e.g. doctor
 * health check), extract this further.
 */
internal fun readLockPid(pidFile: java.nio.file.Path): LockPidReadResult {
    if (!java.nio.file.Files.exists(pidFile)) return LockPidReadResult.Absent
    val raw = java.nio.file.Files.readString(pidFile).trim()
    val parts = raw.split(Regex("\\s+"), limit = 2)
    val pid = parts.getOrNull(0)?.toLongOrNull() ?: return LockPidReadResult.Malformed(raw)
    val modeToken = parts.getOrNull(1)
    return LockPidReadResult.Present(LockPidContents(pid, modeToken))
}

/**
 * Apply a positional `<profile>` argument to [parent] before
 * [Main.resolveCurrentProfile] runs. Mirrors `MountCommand.profileNameOverride`
 * (sets `parent.provider` + invalidates the memoised profile cache). Lets
 * `unidrive daemon run <profile>` / `unidrive sync <profile>` /
 * `unidrive refresh <profile>` work alongside the global `-p <profile>` form,
 * matching `unidrive mount`'s precedent. No-op when [positional] is null.
 */
internal fun applyPositionalProfile(parent: Main, positional: String?) {
    if (positional != null) {
        parent.provider = positional
        parent.invalidateProfileCaches()
    }
}
