package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine.Command
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

    override fun run() {
        val parent = daemonCmd.parent
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val dbPath = parent.providerConfigDir().resolve("state.db")
        val socketPath = IpcServer.defaultSocketPath(profile.name)

        val runtime = DaemonRuntime(
            profileName = profile.name,
            lockFile = lockFile,
            dbPath = dbPath,
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

@Command(name = "status", description = ["Show daemon status for the current profile"], mixinStandardHelpOptions = true)
class DaemonStatusCommand : Runnable {
    @ParentCommand
    lateinit var daemonCmd: DaemonCommand

    override fun run() {
        val parent = daemonCmd.parent
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")

        if (!Files.exists(pidFile)) {
            System.err.println("no daemon running for profile '${profile.name}'")
            System.exit(1)
            return
        }

        val raw = Files.readString(pidFile).trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val pid = parts.getOrNull(0)?.toLongOrNull()
        val modeToken = parts.getOrNull(1) ?: "(no-mode)"
        if (pid == null) {
            System.err.println("malformed lock-pid file at $pidFile: '$raw'")
            System.exit(1)
            return
        }
        println("pid $pid, mode $modeToken")

        // Phase 3 will add the RPC call to daemon.status here.
        // For Phase 2, status is file-derived only.
    }
}

// ── daemon stop ──────────────────────────────────────────────────────────────

@Command(name = "stop", description = ["Send SIGTERM to the running daemon and wait for it to exit"], mixinStandardHelpOptions = true)
class DaemonStopCommand : Runnable {
    @ParentCommand
    lateinit var daemonCmd: DaemonCommand

    companion object {
        const val STOP_DEADLINE_MS: Long = 12_000  // 10s graceful + 2s buffer
    }

    override fun run() {
        val parent = daemonCmd.parent
        val profile = parent.resolveCurrentProfile()
        val lockFile = parent.providerConfigDir().resolve(".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")

        if (!Files.exists(pidFile)) {
            println("no daemon running for profile '${profile.name}'")
            return  // idempotent stop; exit 0
        }

        val raw = Files.readString(pidFile).trim()
        val parts = raw.split(Regex("\\s+"), limit = 2)
        val pid = parts.getOrNull(0)?.toLongOrNull()
        val modeToken = parts.getOrNull(1)
        if (pid == null) {
            System.err.println("malformed lock-pid file at $pidFile: '$raw'")
            System.exit(1)
            return
        }
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
