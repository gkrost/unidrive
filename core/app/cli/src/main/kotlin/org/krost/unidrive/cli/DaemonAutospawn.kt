package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * #142: auto-spawn `unidrive daemon run` on the first client connection. The daemon-design spec used to
 * require the operator to start the daemon explicitly; a client that finds nothing at the socket now
 * starts it (mode-agnostic: whichever mode the profile has, that profile's daemon starts — one process
 * lock per profile stays, so a double spawn is refused by the lock, never doubled).
 *
 * The daemon must outlive the client that started it (mount exits, daemon stays), so the child is
 * detached: its output goes to a file under the profile's config folder, never to the client's
 * terminal, and nobody waits for it. The spawner polls for the socket file (the daemon binds it LAST,
 * after authentication and every handler are registered — its appearance is the ready signal) and
 * gives up after [DEFAULT_WAIT_TIMEOUT_MS], leaving the client's own error path to speak.
 *
 * Nothing here is mount- or mirror-specific: the daemon reads the profile's mode and serves it.
 */
object DaemonAutospawn {
    private val log = LoggerFactory.getLogger(DaemonAutospawn::class.java)

    private val LOCALE_SHAPE = Regex("^([A-Za-z]{2,3})(?:[_-]([A-Za-z]{2}))?$")

    const val DEFAULT_WAIT_TIMEOUT_MS: Long = 60_000

    /**
     * Makes sure the profile's daemon is running: probes the socket, spawns the daemon when nothing
     * answers, and waits for the socket to appear. Returns true when a daemon is serving (pre-existing
     * or freshly spawned), false when the spawn was not possible — the caller then falls through to
     * its own operator-facing error. Never throws.
     *
     * [probe] and [spawn] are the tests' seams; [locateJar] stands in for "the jar this CLI runs from",
     * which does not exist when running from loose classes (tests, IDE).
     */
    fun ensureDaemonRunning(
        profileName: String,
        configDir: Path,
        socketPath: Path = IpcServer.defaultSocketPath(profileName),
        waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
        probe: (String, Path) -> Boolean = { name, socket -> daemonAnswers(name, socket, configDir) },
        locateJar: () -> Path? = ::thisJar,
        spawn: (List<String>, Path) -> Unit = ::defaultSpawn,
    ): Boolean {
        if (probe(profileName, socketPath)) return true
        val jar =
            runCatching { locateJar() }.getOrNull().also {
                if (it == null) {
                    System.err.println(
                        "unidrive: the daemon for profile '$profileName' is not running, and this command cannot start it " +
                            "(not running from a jar). Start it, then run this again: `unidrive -p $profileName daemon run`.",
                    )
                }
            } ?: return false
        val javaHome = Path.of(System.getProperty("java.home"))
        val javaBin = javaHome.resolve("bin").resolve(if (isWindows) "java.exe" else "java")
        val logFile = configDir.resolve("daemon-spawn.log")
        if (!Files.exists(jar.resolveSibling("jvm-flags.txt"))) {
            System.err.println(
                "unidrive: no jvm-flags.txt beside ${jar.fileName}; the spawned daemon starts without the standard static JVM flags (redeploy to restore them).",
            )
        }
        System.err.println(
            "unidrive: the daemon for profile '$profileName' is not running; starting it (output: $logFile) and waiting for it ...",
        )
        try {
            spawn(
                spawnCommand(javaBin, jar, configDir.toAbsolutePath().parent, profileName),
                logFile,
            )
        } catch (e: Exception) {
            log.warn("auto-spawn of the daemon failed", e)
            System.err.println(
                "unidrive: the daemon could not be started (${e.javaClass.simpleName}). " +
                    "Start it manually: `unidrive -p $profileName daemon run`.",
            )
            return false
        }
        val deadline = System.currentTimeMillis() + waitTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe(profileName, socketPath)) return true
            Thread.sleep(200)
        }
        System.err.println(
            "unidrive: the daemon for profile '$profileName' did not come up within ${waitTimeoutMs / 1000} s. " +
                "Its output: $logFile. Start it in a terminal to see the error: `unidrive -p $profileName daemon run`.",
        )
        return false
    }

    /**
     * Whether a daemon is actually serving the socket: the file alone lies (a kill -9'd daemon leaves
     * it behind), so one real authenticated round-trip decides. Anything that fails to connect,
     * authenticate or reply reads as "not running" — the lock sorts a live daemon from a stale socket
     * for the (re)start either way.
     */
    fun daemonAnswers(
        profileName: String,
        socketPath: Path,
        configDir: Path,
        replyTimeoutMs: Long = IpcAuthClient.HANDSHAKE_TIMEOUT_MS,
    ): Boolean {
        if (!Files.exists(socketPath)) return false
        return try {
            val channel =
                IpcAuthClient.connect(
                    IpcEndpoint(socketPath, configDir, profileName),
                    IpcAuth.Scope.FULL,
                )
            try {
                channel.configureBlocking(false)
                val deadline = System.nanoTime() + replyTimeoutMs * 1_000_000
                val request = ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray())
                val buffer = ByteBuffer.allocate(64 * 1024)
                while (System.nanoTime() < deadline) {
                    if (request.hasRemaining()) channel.write(request)
                    if (!request.hasRemaining()) {
                        val read = channel.read(buffer)
                        if (read > 0) return true
                        if (read < 0) return false
                    }
                    Thread.sleep(10)
                }
                false
            } finally {
                channel.close()
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * The command that starts the profile's daemon child, carrying the same flag set every launcher
     * passes: the heap cap (UNIDRIVE_XMX, default 2g), the static flags from `jvm-flags.txt` beside
     * the jar (the launchers' single source; a missing file degrades to no static flags, never to a
     * failed spawn), the locale (UNIDRIVE_LOCALE, else the parent's own user.language/user.country),
     * the pinned unix-domain temp dir, the coroutine-debug agent when armed, and the post-mortem
     * diagnostics flags into the diagnostics dir — always daemon work here, so the bounded GC log
     * rides unconditioned.
     *
     * [env], [props] and [windows] are the tests' seams for the process environment, the system
     * properties and the OS family.
     */
    internal fun spawnCommand(
        javaBin: Path,
        jar: Path,
        configRoot: Path,
        profileName: String,
        env: (String) -> String? = System::getenv,
        props: (String) -> String? = { System.getProperty(it) },
        windows: Boolean = isWindows,
    ): List<String> =
        buildList {
            add(javaBin.toString())
            add("-Xmx" + (env("UNIDRIVE_XMX")?.takeIf { it.isNotBlank() } ?: "2g"))
            addAll(staticJvmFlags(jar.resolveSibling("jvm-flags.txt")))
            addAll(localeArgs(env, props))
            props("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { add("-Djdk.net.unixdomain.tmpdir=$it") }
            if (CoroutineDebug.enabled()) {
                CoroutineDebug.agentJarBeside(jar)?.let { add("-javaagent=$it") }
            }
            addAll(diagArgs(diagDir(env, props, windows)))
            add("-jar")
            add(jar.toString())
            add("--config-dir")
            add(configRoot.toString())
            add("daemon")
            add("run")
            add(profileName)
        }

    /** The static flags from the launchers' single source beside the jar; a missing or unreadable
     *  file means no static flags (the deploy ships the file, jvm-flags.txt names this reader). */
    private fun staticJvmFlags(flagsFile: Path): List<String> =
        runCatching { Files.readAllLines(flagsFile) }.getOrNull()
            ?.map { it.substringBefore('#').trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** The child inherits the parent's locale: UNIDRIVE_LOCALE (xx / xx_YY) wins, else the parent
     *  JVM's own user.language/user.country — which its launcher derived from --locale/UNIDRIVE_LOCALE. */
    private fun localeArgs(
        env: (String) -> String?,
        props: (String) -> String?,
    ): List<String> {
        val parsed = env("UNIDRIVE_LOCALE")?.takeIf { it.isNotBlank() }?.let(LOCALE_SHAPE::matchEntire)
        val language = parsed?.groupValues?.get(1)?.lowercase() ?: props("user.language")
        if (language.isNullOrBlank()) return emptyList()
        val country =
            (
                parsed?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.uppercase()
                    ?: props("user.country")
            )?.takeIf { it.isNotBlank() }
        return buildList {
            add("-Duser.language=$language")
            if (country != null) add("-Duser.country=$country")
        }
    }

    /** The diagnostics dir: UNIDRIVE_DIAG_DIR overrides; the default sits under the OS data root
     *  (%LOCALAPPDATA%\unidrive\diagnostics, ~/.local/share/unidrive/diagnostics) — dumps are
     *  multi-GB transients, so never the roaming profile data. */
    internal fun diagDir(
        env: (String) -> String?,
        props: (String) -> String?,
        windows: Boolean,
    ): Path {
        env("UNIDRIVE_DIAG_DIR")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        return if (windows) {
            val localAppData =
                env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                    ?: Path.of(props("user.home"), "AppData", "Local").toString()
            Path.of(localAppData, "unidrive", "diagnostics")
        } else {
            Path.of(props("user.home"), ".local", "share", "unidrive", "diagnostics")
        }
    }

    /** The child daemon's post-mortem flags into [dir]: where fatal-crash hs_err logs and the OOM
     *  heap dump land (the dump's -XX:+HeapDumpOnOutOfMemoryError rides jvm-flags.txt), plus the
     *  bounded GC log. The JVM silently skips a dump into a missing directory and treats a path
     *  without a trailing separator as a FILE name: the directory is created here, the paths carry
     *  the trailing separator. A directory that cannot be created degrades to the JVM's silent
     *  skip — the spawn itself never fails on diagnostics. */
    private fun diagArgs(dir: Path): List<String> {
        runCatching { Files.createDirectories(dir) }
        val sep = dir.fileSystem.separator
        val diag = dir.toString().removeSuffix(sep) + sep
        return listOf(
            "-XX:ErrorFile=${diag}hs_err_pid%p.log",
            "-XX:HeapDumpPath=$diag",
            "-Xlog:gc*:file=${diag}gc.log:time,uptime:filecount=5,filesize=10m",
        )
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("windows")

    /** The jar this CLI class was loaded from; null when running from loose classes (tests, IDE). */
    fun thisJar(): Path? {
        val location = DaemonAutospawn::class.java.protectionDomain.codeSource.location ?: return null
        val path =
            runCatching { Path.of(location.toURI()) }.getOrNull()
                ?: return null
        return if (path.toString().endsWith(".jar")) path else null
    }

    /** Detached start: the child outlives this client; its output goes to [logFile], not the terminal. */
    private fun defaultSpawn(
        command: List<String>,
        logFile: Path,
    ) {
        Files.createDirectories(logFile.parent)
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
        pb.start() // deliberately not waited for: the daemon outlives the client that started it
    }
}
