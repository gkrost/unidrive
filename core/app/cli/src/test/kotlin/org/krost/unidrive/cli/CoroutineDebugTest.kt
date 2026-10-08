package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.cli.CoroutineDebug.DUMP_FILE_NAME
import org.krost.unidrive.cli.CoroutineDebug.TRIGGER_FILE_NAME
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The daemon's coroutine-dump facility (gkrost/unidrive#613 ask 1): with the probes installed,
 * a coroutine suspended forever — the shape the hung mount uploads presented as — names its
 * suspension point in the dump, and the trigger file in the profile folder is what requests it.
 *
 * The end-to-end capture test forks a JVM armed with the probe jar as -javaagent, exactly how
 * the launcher templates and DaemonAutospawn arm a daemon: a -javaagent reaches a JVM only at
 * start, and a fork is the only way a test owns a JVM start. (Arming inside the gradle test JVM
 * itself is blocked by its coverage agent — a known kotlinx-coroutines/JaCoCo conflict — so the
 * in-process assertions here stay content-free.)
 */
class CoroutineDebugTest {
    @AfterTest
    fun uninstallProbes() {
        runCatching { DebugProbes.uninstall() }
    }

    @Test
    fun `a dump names the suspension point of a coroutine that hangs forever`() {
        val dir = Files.createTempDirectory("unidrive-coroutine-fork")
        val javaBin =
            Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
        val agentJar = Path.of(Job::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(agentJar.fileName.toString().startsWith(CoroutineDebug.AGENT_JAR_PREFIX), "the core JAR is the javaagent on the test classpath")
        val process =
            ProcessBuilder(
                javaBin.toString(),
                "-javaagent:$agentJar",
                "-cp",
                System.getProperty("java.class.path"),
                CoroutineDebugDumpMain::class.java.name,
                dir.toString(),
            ).redirectErrorStream(true)
        val output = process.start().inputStream.readBytes().decodeToString()
        val dump = dir.resolve(DUMP_FILE_NAME)
        assertTrue(Files.exists(dump), "the forked JVM wrote a dump; its output was:\n$output")
        val text = Files.readString(dump)
        assertTrue(text.startsWith("unidrive coroutine dump: "), "the dump carries a header")
        assertTrue(
            text.contains("state: SUSPENDED"),
            "the dump shows the hung coroutine and where it suspends; dump was:\n$text\nfork output was:\n$output",
        )
    }

    @Test
    fun `creating the trigger file in the watched folder produces a dump`() = runBlocking {
        CoroutineDebug.install()
        val dir = Files.createTempDirectory("unidrive-coroutine-watch")
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        try {
            CoroutineDebug.watchForDumpRequests(dir, scope)
            Files.writeString(dir.resolve(TRIGGER_FILE_NAME), "now")
            val dump = dir.resolve(DUMP_FILE_NAME)
            val deadline = System.currentTimeMillis() + 10_000
            while (!Files.exists(dump) && System.currentTimeMillis() < deadline) {
                delay(200)
            }
            assertTrue(Files.exists(dump), "the watcher answered the trigger with a dump")
            assertTrue(Files.readString(dump).startsWith("unidrive coroutine dump: "))
            assertFalse(Files.exists(dir.resolve(TRIGGER_FILE_NAME)), "the trigger is consumed")
        } finally {
            scope.cancel()
        }
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("windows")
}

/** The forked JVM of the capture test: hang one coroutine, dump, exit. */
object CoroutineDebugDumpMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        runBlocking {
            CoroutineDebug.install()
            val hung = launch(Dispatchers.Default) {
                CompletableDeferred<Unit>().await() // the shape of the #613 hang: a silent, forever wait
            }
            delay(500) // let it reach the suspension
            CoroutineDebug.dumpCoroutines(dir)
            hung.cancel()
        }
    }
}
