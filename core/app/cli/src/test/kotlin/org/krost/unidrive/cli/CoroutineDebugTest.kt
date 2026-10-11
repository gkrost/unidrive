package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.cli.CoroutineDebug.DUMP_FILE_NAME
import org.krost.unidrive.cli.CoroutineDebug.TRIGGER_FILE_NAME
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
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
        CoroutineDebug.onFailure = { _, _ -> }
        runCatching { DebugProbes.uninstall() }
    }

    private companion object {
        /** How long the watcher test waits for a dump: the poll is 2 s, the rest is slack for a loaded machine. */
        const val DUMP_BUDGET_MS = 20_000L
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
        // install() and the dump swallow their errors (best effort in the daemon); the hook is how this test sees them
        // instead of waiting out the budget below with no reason (#713).
        val failures = CopyOnWriteArrayList<String>()
        CoroutineDebug.onFailure = { what, cause -> failures.add("$what: ${cause::class.simpleName}: ${cause.message}") }
        CoroutineDebug.install()
        assertTrue(
            DebugProbes.isInstalled,
            "step \"install the probes\": install() returned with the probes not installed; swallowed errors: $failures",
        )
        val dir = Files.createTempDirectory("unidrive-coroutine-watch")
        // The watcher gets a thread of its own: on the shared Dispatchers.Default a busy pool could delay its 2 s poll.
        val watcherThread = Executors.newSingleThreadExecutor { Thread(it, "coroutine-dump-watcher-test").apply { isDaemon = true } }
        val scope = CoroutineScope(watcherThread.asCoroutineDispatcher())
        try {
            CoroutineDebug.watchForDumpRequests(dir, scope)
            val trigger = dir.resolve(TRIGGER_FILE_NAME)
            Files.writeString(trigger, "now")
            val dump = dir.resolve(DUMP_FILE_NAME)
            val startedAt = System.nanoTime()
            fun waitedMs() = (System.nanoTime() - startedAt) / 1_000_000
            // A working watcher answers within one poll (2 s); the rest is slack for a busy machine.
            // An install error alone is not fatal here (the probes can fail to arm and still dump); a failed dump is.
            while (!Files.exists(dump) && failures.none { it.startsWith("dump:") } && waitedMs() < DUMP_BUDGET_MS) {
                delay(100)
            }
            assertTrue(
                Files.exists(dump),
                "step \"the watcher answers the trigger with a dump\": no dump after ${waitedMs()} ms (budget $DUMP_BUDGET_MS ms); " +
                    "swallowed errors: $failures; trigger still there: ${Files.exists(trigger)}",
            )
            assertTrue(Files.readString(dump).startsWith("unidrive coroutine dump: "))
            // The watcher deletes the trigger right after the dump; allow it that moment.
            val consumeBy = System.nanoTime() + 5_000_000_000L
            while (Files.exists(trigger) && System.nanoTime() < consumeBy) delay(50)
            assertFalse(Files.exists(trigger), "the trigger is consumed")
        } finally {
            scope.cancel()
            watcherThread.shutdownNow()
        }
    }

    @Test
    fun `a dump that cannot be written reaches the failure hook instead of vanishing`() {
        val failures = CopyOnWriteArrayList<Pair<String, Throwable>>()
        CoroutineDebug.onFailure = { what, cause -> failures.add(what to cause) }
        val notADirectory = Files.createTempFile("unidrive-coroutine-not-a-dir", ".tmp")
        try {
            CoroutineDebug.dumpCoroutines(notADirectory.resolve("sub")) // the folder cannot be created under a file

            assertTrue(failures.size == 1 && failures[0].first == "dump", "one swallowed dump error reached the hook: $failures")
        } finally {
            Files.deleteIfExists(notADirectory)
        }
    }

    @Test
    fun `a hook that throws does not turn a swallowed dump error into a thrown one`() {
        CoroutineDebug.onFailure = { _, _ -> throw IllegalStateException("the hook itself failed") }
        val notADirectory = Files.createTempFile("unidrive-coroutine-not-a-dir", ".tmp")
        try {
            CoroutineDebug.dumpCoroutines(notADirectory.resolve("sub")) // must return normally
        } finally {
            Files.deleteIfExists(notADirectory)
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
