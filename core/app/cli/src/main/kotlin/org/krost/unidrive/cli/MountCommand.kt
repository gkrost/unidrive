package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcServer
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
        val socketPath = IpcServer.defaultSocketPath(profile.name)
        val cacheRoot = SyncEngine.hydrationCacheRoot(
            SyncEngine.defaultHydrationCacheRoot(),
            profile.type,
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

        Files.createDirectories(cacheRoot)
        val argv = buildArgv(binary, mountPath, socketPath, cacheRoot)
        val exit = superviseProcess(argv)
        System.exit(exit)
    }

    companion object {
        const val EX_CONFIG: Int = 78
        const val SIGTERM_GRACE_MS: Long = 10_000

        fun defaultBinaryPath(): Path =
            Paths.get(
                System.getProperty("user.home"),
                ".local",
                "lib",
                "unidrive",
                "unidrive-mount",
            )

        fun hydrationCacheRoot(cacheRoot: Path, providerId: String): Path =
            SyncEngine.hydrationCacheRoot(cacheRoot, providerId)

        fun buildArgv(
            binary: Path,
            mountPath: Path,
            socketPath: Path,
            cacheRoot: Path,
        ): List<String> =
            listOf(
                binary.toString(),
                "--mount",
                mountPath.toString(),
                "--ipc",
                socketPath.toString(),
                "--cache",
                cacheRoot.toString(),
            )

        fun checkBinaryExists(binary: Path): Int =
            if (Files.isExecutable(binary) || Files.exists(binary)) 0 else EX_CONFIG

        fun superviseProcess(argv: List<String>): Int {
            val pb = ProcessBuilder(argv).inheritIO()
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
