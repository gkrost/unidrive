package org.krost.unidrive.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #142: auto-spawn of the daemon on the first client connection. The spawner probes before it
 * believes (a kill -9'd daemon leaves a stale socket file), builds `daemon run <profile>` for the
 * running java and jar, detaches, and waits for the socket; every failure degrades to the client's
 * own operator-facing error, never an exception out of [DaemonAutospawn.ensureDaemonRunning].
 */
class DaemonAutospawnTest {
    @Test
    fun `an authenticated daemon that never answers status is not ready`() =
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val dir = Files.createTempDirectory("autospawn-silent")
            val socket = dir.resolve("daemon.sock")
            val auth = org.krost.unidrive.sync.IpcAuth.issue(dir, "p", "test")
            val server = org.krost.unidrive.sync.IpcServer(socketPath = socket, auth = auth)
            val serverJob = kotlinx.coroutines.Job()
            val serverScope = kotlinx.coroutines.CoroutineScope(coroutineContext + serverJob)
            server.registerHandler("daemon.status") { _, _ ->
                kotlinx.coroutines.delay(60_000)
                """{"ok":true}"""
            }
            try {
                server.start(serverScope)
                assertFalse(DaemonAutospawn.daemonAnswers("p", socket, dir, replyTimeoutMs = 100))
            } finally {
                server.close()
                serverJob.cancel()
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a daemon that already answers is not spawned again`() {
        val spawnedCommands = mutableListOf<List<String>>()
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "p",
                configDir = Files.createTempDirectory("autospawn-alive"),
                probe = { _, _ -> true },
                locateJar = { error("a live daemon must not need the jar") },
                spawn = { command, _ -> spawnedCommands.add(command) },
            )
        assertTrue(ok)
        assertTrue(spawnedCommands.isEmpty(), "nothing is spawned over a live daemon")
    }

    @Test
    fun `a stopped daemon is spawned as java -jar daemon run profile and waited for`() {
        val tempDir = Files.createTempDirectory("autospawn-stopped")
        var spawnCalls = 0
        var seenCommand: List<String>? = null
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "work_mount",
                configDir = tempDir,
                probe = { name, _ ->
                    // Answers only after the first spawn: the probe is what the spawner waits on.
                    spawnCalls > 0 && name == "work_mount"
                },
                locateJar = { Path.of("/opt/unidrive/unidrive.jar") },
                spawn = { command, logFile ->
                    spawnCalls++
                    seenCommand = command
                    assertEquals(tempDir.resolve("daemon-spawn.log"), logFile, "the child's output goes under the profile's config folder")
                },
            )
        assertTrue(ok, "the spawner reports a serving daemon once the probe turns true")
        assertEquals(1, spawnCalls)
        val command = seenCommand!!
        assertTrue(command[0].endsWith("java") || command[0].endsWith("java.exe"), "the child runs this JVM's java")
        assertEquals(
            Path.of("/opt/unidrive/unidrive.jar").toString(),
            command[command.indexOf("-jar") + 1],
            "the child runs this jar",
        )
        assertEquals(
            listOf("--config-dir", tempDir.toAbsolutePath().parent.toString(), "daemon", "run", "work_mount"),
            command.takeLast(5),
            "the daemon uses the client's configuration root",
        )
        assertTrue(command.contains("-Xmx2g"), "the child carries the heap cap (no UNIDRIVE_XMX in the test environment)")
        assertTrue(command.any { it.startsWith("-Xlog:gc*:file=") }, "the spawned child is always a daemon: the bounded GC log rides")
    }

    @Test
    fun `the spawned daemon carries the launchers' flag set from the single source`() {
        val jarDir = Files.createTempDirectory("autospawn-flags")
        val diag = Files.createTempDirectory("autospawn-flags-diag")
        Files.write(
            jarDir.resolve("jvm-flags.txt"),
            listOf(
                "# comment lines and blanks are skipped",
                "-Dstdout.encoding=UTF-8",
                "",
                "-XX:+ExitOnOutOfMemoryError # a trailing comment too",
            ),
        )
        val command =
            DaemonAutospawn.spawnCommand(
                javaBin = Path.of("java"),
                jar = jarDir.resolve("unidrive.jar"),
                configRoot = Files.createTempDirectory("autospawn-flags-config"),
                profileName = "p",
                env = { name -> if (name == "UNIDRIVE_DIAG_DIR") diag.toString() else null },
                props = { name ->
                    mapOf("user.language" to "de", "user.country" to "LU", "java.io.tmpdir" to "/tmp/x")[name]
                },
            )
        val sep = diag.fileSystem.separator
        val diagPath = diag.toString().removeSuffix(sep) + sep
        assertTrue(command.containsAll(listOf("-Dstdout.encoding=UTF-8", "-XX:+ExitOnOutOfMemoryError")), "the static flags come from jvm-flags.txt beside the jar")
        assertTrue(command.contains("-Duser.language=de"))
        assertTrue(command.contains("-Duser.country=LU"))
        assertTrue(command.contains("-Djdk.net.unixdomain.tmpdir=/tmp/x"))
        assertTrue(command.contains("-XX:ErrorFile=${diagPath}hs_err_pid%p.log"), "fatal-crash logs are pinned into the diagnostics dir")
        assertTrue(command.contains("-XX:HeapDumpPath=$diagPath"), "the dump path carries the trailing separator: without it the JVM treats it as a file name")
        assertTrue(command.contains("-Xlog:gc*:file=${diagPath}gc.log:time,uptime:filecount=5,filesize=10m"))
        assertTrue(Files.isDirectory(diag), "the diagnostics dir exists before the child starts (the JVM skips dumps into missing dirs)")
    }

    @Test
    fun `UNIDRIVE_XMX and UNIDRIVE_LOCALE steer the spawned daemon`() {
        val diag = Files.createTempDirectory("autospawn-env-diag")
        val command =
            DaemonAutospawn.spawnCommand(
                javaBin = Path.of("java"),
                jar = Files.createTempDirectory("autospawn-env").resolve("unidrive.jar"),
                configRoot = Files.createTempDirectory("autospawn-env-config"),
                profileName = "p",
                env = { name ->
                    mapOf("UNIDRIVE_XMX" to "4g", "UNIDRIVE_LOCALE" to "de_lu", "UNIDRIVE_DIAG_DIR" to diag.toString())[name]
                },
                props = { name -> if (name == "java.io.tmpdir") "/t" else null },
            )
        assertTrue(command.contains("-Xmx4g"))
        assertTrue(command.contains("-Duser.language=de"))
        assertTrue(command.contains("-Duser.country=LU"), "UNIDRIVE_LOCALE's country part lands upper-cased")
        assertTrue(command.contains("-Djdk.net.unixdomain.tmpdir=/t"))
    }

    @Test
    fun `a jar without a sibling jvm-flags txt still spawns the heap and diagnostics flags`() {
        val diag = Files.createTempDirectory("autospawn-nostatic-diag")
        val command =
            DaemonAutospawn.spawnCommand(
                javaBin = Path.of("java"),
                jar = Files.createTempDirectory("autospawn-nostatic").resolve("unidrive.jar"),
                configRoot = Files.createTempDirectory("autospawn-nostatic-config"),
                profileName = "p",
                env = { name -> if (name == "UNIDRIVE_DIAG_DIR") diag.toString() else null },
                props = { null },
            )
        assertFalse(command.any { it.startsWith("-Dstdout.encoding") }, "no static flags without the file")
        assertTrue(command.contains("-Xmx2g"))
        assertTrue(command.any { it.startsWith("-XX:ErrorFile=") })
        assertTrue(command.any { it.startsWith("-XX:HeapDumpPath=") })
        assertTrue(command.any { it.startsWith("-Xlog:gc*:file=") })
    }

    @Test
    fun `the default diagnostics dir sits under the OS data root`() {
        val home = Files.createTempDirectory("autospawn-diaghome")
        val nothing: (String) -> String? = { null }
        val homeProps: (String) -> String? = { name -> if (name == "user.home") home.toString() else null }
        assertEquals(
            home.resolve(".local/share/unidrive/diagnostics"),
            DaemonAutospawn.diagDir(nothing, homeProps, windows = false),
            "Linux: under ~/.local/share/unidrive, the daemon's own data root",
        )
        assertEquals(
            home.resolve("unidrive/diagnostics"),
            DaemonAutospawn.diagDir({ name -> if (name == "LOCALAPPDATA") home.toString() else null }, homeProps, windows = true),
            "Windows: under %LOCALAPPDATA%\\unidrive — Local, never the Roaming data root",
        )
        assertEquals(
            home.resolve("AppData/Local/unidrive/diagnostics"),
            DaemonAutospawn.diagDir(nothing, homeProps, windows = true),
            "Windows without LOCALAPPDATA falls back to the profile's own AppData\\Local",
        )
    }

    @Test
    fun `a daemon that never comes up reports failure without throwing`() {
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "p",
                configDir = Files.createTempDirectory("autospawn-never"),
                waitTimeoutMs = 400,
                probe = { _, _ -> false },
                locateJar = { Path.of("/opt/unidrive/unidrive.jar") },
                spawn = { _, _ -> /* a daemon that starts but never binds */ },
            )
        assertFalse(ok, "the timeout is reported as false, to the caller's own error path")
    }

    @Test
    fun `a failed spawn degrades to false`() {
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "p",
                configDir = Files.createTempDirectory("autospawn-fail"),
                probe = { _, _ -> false },
                locateJar = { Path.of("/opt/unidrive/unidrive.jar") },
                spawn = { _, _ -> throw IOException("disk full") },
            )
        assertFalse(ok)
    }

    @Test
    fun `without a jar nothing is spawned and the failure is reported`() {
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "p",
                configDir = Files.createTempDirectory("autospawn-nojar"),
                probe = { _, _ -> false },
                locateJar = { null },
                spawn = { _, _ -> error("nothing can be spawned without a jar") },
            )
        assertFalse(ok)
    }

    @Test
    fun `thisJar names a jar or nothing`() {
        // In the test suite the classes are loose, so both outcomes are legal; the pinned contract is
        // that a non-null answer is always a .jar (the spawner's -jar argument needs exactly that).
        DaemonAutospawn.thisJar()?.let { assertTrue(it.toString().endsWith(".jar")) }
    }
}
