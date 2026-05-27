package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object StaleMountDetector {
    private val procMounts: Path = Paths.get("/proc/self/mounts")
    private val procRoot: Path = Paths.get("/proc")

    /** NUL byte that separates argv tokens in `/proc/<pid>/cmdline`. */
    private const val CMDLINE_SEP: Char = '\u0000'

    /**
     * Probe whether a live Rust `unidrive-mount` co-daemon is currently serving
     * a given FUSE mountpoint. A unidrive FUSE mount whose backing co-daemon is
     * still alive is NOT stale — the previous daemon's client is still serving
     * it (or will transparently reconnect after a daemon restart). Only mounts
     * with no live co-daemon are genuinely stale.
     *
     * Injected (rather than called inline) so tests exercise the stale/not-stale
     * partition logic without touching the real `/proc` tree.
     */
    fun interface CoDaemonLivenessProbe {
        fun isServing(mountpoint: String): Boolean
    }

    /**
     * Production entry point. Reads `/proc/self/mounts`, parses the unidrive
     * FUSE mountpoints, and excludes any whose backing co-daemon process is
     * still alive. Only TRULY stale mounts (no live co-daemon) are returned.
     */
    fun detectStaleFuseUnidriveMounts(): List<String> {
        if (!Files.isReadable(procMounts)) return emptyList()
        val mountLines = Files.readAllLines(procMounts)
        return detectStaleFuseUnidriveMounts(mountLines, procCoDaemonLivenessProbe)
    }

    /**
     * Testable core. Parses the supplied mount lines and filters out any
     * mountpoint whose backing co-daemon the [probe] reports as alive. Pure with
     * respect to the injected [probe] — no real `/proc` or mount access.
     */
    fun detectStaleFuseUnidriveMounts(
        mountLines: List<String>,
        probe: CoDaemonLivenessProbe,
    ): List<String> =
        mountLines
            .mapNotNull { parseUnidriveFuseMountpoint(it) }
            .filterNot { probe.isServing(it) }

    internal fun parseUnidriveFuseMountpoint(line: String): String? {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val device = parts[0]
        val mountpoint = parts[1]
        val fstype = parts[2]
        if (fstype != "fuse" && !fstype.startsWith("fuse.")) return null
        if (device != "unidrive") return null
        return mountpoint
    }

    /**
     * Production liveness probe: scans `/proc/<pid>/cmdline` for a live
     * `unidrive-mount` co-daemon whose `--mount <mountpoint>` argument matches.
     * MountCommand spawns the co-daemon as
     * `unidrive-mount --mount <mountPath> --ipc <socket> --cache <root>`, so a
     * NUL-separated cmdline containing both the `unidrive-mount` binary token
     * and the `--mount <mountpoint>` pair identifies the serving process.
     */
    private val procCoDaemonLivenessProbe = CoDaemonLivenessProbe { mountpoint ->
        if (!Files.isReadable(procRoot)) return@CoDaemonLivenessProbe false
        Files.newDirectoryStream(procRoot).use { stream ->
            stream.any { entry ->
                val name = entry.fileName.toString()
                if (name.isEmpty() || !name.all { it.isDigit() }) return@any false
                val cmdline = entry.resolve("cmdline")
                if (!Files.isReadable(cmdline)) return@any false
                val args =
                    runCatching {
                        String(Files.readAllBytes(cmdline))
                            .split(CMDLINE_SEP)
                            .filter { it.isNotEmpty() }
                    }.getOrDefault(emptyList())
                cmdlineServesMount(args, mountpoint)
            }
        }
    }

    /**
     * True when [args] is a `unidrive-mount` co-daemon invocation serving
     * [mountpoint] (i.e. `--mount <mountpoint>` appears in the argv).
     */
    internal fun cmdlineServesMount(args: List<String>, mountpoint: String): Boolean {
        if (args.isEmpty()) return false
        val isUnidriveMount = args[0].substringAfterLast('/') == "unidrive-mount"
        if (!isUnidriveMount) return false
        val mountIdx = args.indexOf("--mount")
        return mountIdx >= 0 && mountIdx + 1 < args.size && args[mountIdx + 1] == mountpoint
    }
}
