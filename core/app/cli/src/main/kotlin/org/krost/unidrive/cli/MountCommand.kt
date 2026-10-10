package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.SyncEngine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@Command(
    name = "mount",
    description = ["Mount a unidrive profile as a FUSE filesystem (Linux co-daemon)"],
    mixinStandardHelpOptions = true,
)
class MountCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Parameters(
        index = "0",
        description = ["Local mount point path (must be an empty directory)"],
    )
    lateinit var mountPath: Path

    @Option(
        names = ["--profile"],
        description = ["Profile name (default: resolved via Main -p / default_profile)"],
    )
    var profileNameOverride: String? = null

    override fun run() {
        if (profileNameOverride != null) {
            parent.provider = profileNameOverride
            parent.invalidateProfileCaches()
        }
        val profile = parent.resolveCurrentProfile()
        // #603 (U4): the hosting contract, before anything mutates — `mount` is the mount-mode command.
        parent.requireProfileMode(profile, ProfileMode.MOUNT, "mount")

        // Per spec unidrive-daemon-design.md §3.3: mount no longer acquires
        // the profile lock. The daemon (which holds Mode.DAEMON) is the
        // authoritative IPC server for the profile. If the daemon is not
        // running, the co-daemon connection refusal below produces a clear
        // operator-facing error pointing at `unidrive daemon run`.
        val socketPath = IpcServer.defaultSocketPath(profile.name)
        // Cache namespace MUST match the daemon's SyncEngine cacheKey
        // (profile.name) so the co-daemon's eviction + crash-recovery scanner
        // walk the same subtree the JVM hydrates into, and so two accounts of
        // the same provider type don't collide on identical remote paths.
        val cacheRoot = SyncEngine.hydrationCacheRoot(
            SyncEngine.defaultHydrationCacheRoot(),
            profile.name,
        )
        val binary = defaultBinaryPath()

        val existsExit = checkBinaryExists(binary)
        if (existsExit != 0) {
            System.err.println(
                "unidrive mount: co-daemon binary not found at $binary",
            )
            System.err.println(
                "Build manually: cd ../unidrive-mount-linux && cargo build --release " +
                    "&& cp target/release/unidrive-mount ~/.local/lib/unidrive/",
            )
            System.exit(existsExit)
            return
        }

        // #142: the first client connection starts the profile's daemon — a fresh machine's
        // `unidrive mount /path` works without the operator having seen the word "daemon". When the
        // spawn is not possible the co-daemon's own error below still speaks, unchanged.
        DaemonAutospawn.ensureDaemonRunning(
            profileName = profile.name,
            configDir = parent.providerConfigDir(),
        )

        Files.createDirectories(cacheRoot)
        // IPC protocol 2 (#632): the co-daemon authenticates every connection with the profile's
        // full-scope token, which the daemon rewrites at each start; the co-daemon re-reads it on
        // every connect. Without these two arguments it cannot connect to a protocol-2 daemon.
        val tokenFile = IpcAuth.tokenFile(parent.providerConfigDir(), IpcAuth.Scope.FULL)
        val argv = buildArgv(binary, mountPath, socketPath, cacheRoot, tokenFile, profile.name)
        val exit = superviseProcess(argv)
        if (exit == EX_USAGE) {
            // A co-daemon older than IPC protocol 2 rejects --ipc-token-file as an unknown argument.
            System.err.println(
                "unidrive mount: co-daemon at $binary rejected its arguments (exit $EX_USAGE). " +
                    "It is likely older than IPC protocol 2: rebuild unidrive-mount-linux and " +
                    "reinstall it (`unidrive-mount --version` must report ipc-protocol 2).",
            )
        } else if (exit != 0) {
            // The co-daemon's inherited stderr already says why it stopped. Ask the daemon before
            // blaming it: only a failed status check may claim it is not running (#202).
            val daemonStatus =
                resolveDaemonStatus(
                    answers = DaemonAutospawn.daemonAnswers(profile.name, socketPath, parent.providerConfigDir()),
                    socketExists = Files.exists(socketPath),
                )
            System.err.println(coDaemonExitMessage(exit, profile.name, daemonStatus))
        }
        System.exit(exit)
    }

    /** What the daemon's own status check found after the co-daemon exited. */
    enum class DaemonStatus { ANSWERING, SOCKET_NOT_ANSWERING, NOT_RUNNING }

    companion object {
        const val EX_CONFIG: Int = 78
        const val EX_USAGE: Int = 64
        const val SIGTERM_GRACE_MS: Long = 10_000

        fun defaultBinaryPath(): Path =
            Paths.get(
                System.getProperty("user.home"),
                ".local",
                "lib",
                "unidrive",
                "unidrive-mount",
            )

        fun hydrationCacheRoot(cacheRoot: Path, cacheKey: String): Path =
            SyncEngine.hydrationCacheRoot(cacheRoot, cacheKey)

        fun buildArgv(
            binary: Path,
            mountPath: Path,
            socketPath: Path,
            cacheRoot: Path,
            tokenFile: Path,
            profileName: String,
        ): List<String> =
            listOf(
                binary.toString(),
                "--mount",
                mountPath.toString(),
                "--ipc",
                socketPath.toString(),
                "--cache",
                cacheRoot.toString(),
                "--ipc-token-file",
                tokenFile.toString(),
                // Exactly as in config.toml: the profile name is part of the HMAC message.
                "--profile",
                profileName,
            )

        /**
         * The co-daemon's log filter when the operator set none or a blank one (#150). Its start and
         * teardown breadcrumbs ("co-daemon starting", "co-daemon shutting down (signal|session ended)")
         * are info-level, so a `warn` default would drop exactly the lines a teardown needs; `info` is
         * also the co-daemon's own default when RUST_LOG is unset.
         */
        const val DEFAULT_RUST_LOG: String = "info"

        fun applyDefaultCoDaemonLogLevel(env: MutableMap<String, String>) {
            if (env["RUST_LOG"].isNullOrBlank()) env["RUST_LOG"] = DEFAULT_RUST_LOG
        }

        /** Maps the status check onto the exit hint's wording: only a missing socket claims "not running". */
        internal fun resolveDaemonStatus(
            answers: Boolean,
            socketExists: Boolean,
        ): DaemonStatus =
            when {
                answers -> DaemonStatus.ANSWERING
                socketExists -> DaemonStatus.SOCKET_NOT_ANSWERING
                else -> DaemonStatus.NOT_RUNNING
            }

        /** The operator hint for a non-zero co-daemon exit; claims "not running" only when the status check said so. */
        fun coDaemonExitMessage(
            exit: Int,
            profileName: String,
            daemonStatus: DaemonStatus,
        ): String {
            val head = "unidrive mount: co-daemon exited with code $exit."
            return when (daemonStatus) {
                DaemonStatus.ANSWERING ->
                    "$head The daemon for profile '$profileName' is running and answering, so the cause is the " +
                        "co-daemon's own (see its output above; run again with RUST_LOG=debug for more)."
                DaemonStatus.SOCKET_NOT_ANSWERING ->
                    "$head The daemon for profile '$profileName' has a socket but did not answer: it may still be " +
                        "starting (run the mount again in a moment) or a killed daemon left a stale socket " +
                        "(check `unidrive -p $profileName daemon status`)."
                DaemonStatus.NOT_RUNNING ->
                    "$head The daemon for profile '$profileName' is not running. " +
                        "Start it with: `unidrive -p $profileName daemon run`."
            }
        }

        fun checkBinaryExists(binary: Path): Int =
            if (Files.isExecutable(binary) || Files.exists(binary)) 0 else EX_CONFIG

        fun superviseProcess(argv: List<String>): Int {
            val pb = ProcessBuilder(argv).inheritIO()
            applyDefaultCoDaemonLogLevel(pb.environment())
            val proc = pb.start()
            val hook = Thread {
                if (proc.isAlive) {
                    sendSigtermAndWait(proc, SIGTERM_GRACE_MS)
                }
            }
            Runtime.getRuntime().addShutdownHook(hook)
            return try {
                proc.waitFor()
            } finally {
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }

        fun sendSigtermAndWait(proc: Process, timeoutMillis: Long): Int {
            proc.destroy() // SIGTERM on POSIX per JDK contract
            val exited = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!exited) {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
            return runCatching { proc.exitValue() }.getOrDefault(143)
        }
    }
}
