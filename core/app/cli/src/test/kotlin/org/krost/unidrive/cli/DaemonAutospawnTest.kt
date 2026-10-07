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
        assertEquals("-jar", command[1])
        assertEquals(Path.of("/opt/unidrive/unidrive.jar").toString(), command[2])
        assertEquals(listOf("--config-dir", tempDir.toAbsolutePath().parent.toString(), "daemon", "run", "work_mount"), command.drop(3), "the daemon uses the client's configuration root")
        assertTrue(command[0].endsWith("java") || command[0].endsWith("java.exe"), "the child runs this JVM's java")
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
