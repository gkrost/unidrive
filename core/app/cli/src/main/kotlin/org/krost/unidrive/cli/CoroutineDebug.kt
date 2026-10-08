package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.debug.DebugProbes
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The daemon's window into its own coroutines (gkrost/unidrive#613, ask 1). A hung upload
 * suspends silently: a thread dump shows no thread in any `org.krost` frame, so nothing names
 * the wait. The coroutines debug probes capture each coroutine's suspension stack, turning a
 * hang into one dump that names the suspension point.
 *
 * Entirely opt-in — the probes intercept every suspension, which is a debugging-session cost,
 * not an everyday one:
 *
 *  - `UNIDRIVE_COROUTINE_DEBUG=1` in the daemon's environment turns the facility on. Arming the
 *    capture needs the probe jar as a `-javaagent` at JVM start (a running JVM cannot be armed:
 *    locked-down HotSpots refuse the runtime self-attach), so the launcher templates and
 *    [DaemonAutospawn] add it — the deploy places the standalone probe jar beside the fat jar.
 *  - At start, [install] wires the probes in and self-checks the capture, so a JVM started
 *    without the agent says so instead of producing silent empty dumps.
 *  - While on, a watcher polls the profile folder for [TRIGGER_FILE_NAME]
 *    (`coroutine-dump.trigger`). Its contents do not matter; creating it makes the daemon dump
 *    every live coroutine — creation and suspension stacks — to [DUMP_FILE_NAME]
 *    (`coroutine-dump.txt`) next to it and log the path. Remove the flag or restart without it
 *    to turn the facility off.
 *
 * Dumping is best-effort: a failure is logged and the watcher keeps polling. The daemon is never
 * failed or slowed by this facility beyond the poll and the one-off arming check.
 */
object CoroutineDebug {
    private val log = LoggerFactory.getLogger(CoroutineDebug::class.java)

    /** Environment flag that turns the facility on. Any non-blank value other than `0`. */
    const val ENV_FLAG = "UNIDRIVE_COROUTINE_DEBUG"

    /** File the watcher looks for in the profile folder; its creation requests a dump. */
    const val TRIGGER_FILE_NAME = "coroutine-dump.trigger"

    /** Where the dump lands: next to the trigger, overwritten on every request. */
    const val DUMP_FILE_NAME = "coroutine-dump.txt"

    private const val POLL_INTERVAL_MS = 2_000L

    /** How long the arming self-check waits for its probe coroutine to be captured. */
    private const val ARMING_CHECK_MS = 500L

    /** File-name prefix of the standalone probe jar the deploy places beside the fat jar. */
    const val AGENT_JAR_PREFIX = "kotlinx-coroutines-core-jvm-"

    fun enabled(): Boolean {
        val value = System.getenv(ENV_FLAG) ?: return false
        return value.isNotBlank() && value != "0"
    }

    /**
     * The standalone probe jar next to the fat jar [fatJar] — the -javaagent the launcher
     * templates and [DaemonAutospawn] hand to a daemon JVM when [ENV_FLAG] asks for the facility.
     * The probes cannot arm on a JVM that is already running (the runtime self-attach is refused
     * on locked-down HotSpots), so a JVM start is the only moment. Null when no probe jar was
     * deployed or the folder cannot be read; the caller then starts without the agent and
     * [install]'s self-check tells the operator.
     */
    fun agentJarBeside(fatJar: Path): Path? =
        runCatching {
            Files.list(fatJar.parent).use { stream ->
                stream
                    .filter {
                        val name = it.fileName.toString()
                        name.startsWith(AGENT_JAR_PREFIX) && name.endsWith(".jar")
                    }.findFirst()
                    .orElse(null)
            }
        }.getOrNull()

    /**
     * Installs the coroutines debug probes, then verifies they actually capture. Arming works only
     * when this JVM was started with the probe jar as `-javaagent` (the launcher templates and
     * [DaemonAutospawn] add it when [ENV_FLAG] is set); on a JVM started without it the runtime
     * self-attach is refused on locked-down HotSpots, which reports the probes as installed while
     * they capture nothing. The self-check turns that into one warning naming the remedy.
     * Best-effort throughout: the daemon is never failed or delayed beyond the check.
     */
    suspend fun install() {
        runCatching {
            if (!DebugProbes.isInstalled) DebugProbes.install()
            log.info(
                "coroutine debug probes installed ({}=1); create {} in the profile folder to dump all coroutines",
                ENV_FLAG,
                TRIGGER_FILE_NAME,
            )
            if (captureArmed()) {
                log.info("coroutine debug probes capture; dumps will name every suspension")
            } else {
                log.warn(
                    "coroutine debug probes capture NOTHING: this JVM was started without -javaagent:{}…jar, and " +
                        "the probes cannot arm on a running JVM. Restart via the launcher (or auto-spawn) with {}=1, " +
                        "which adds the agent; until then dumps come out empty",
                    AGENT_JAR_PREFIX,
                    ENV_FLAG,
                )
            }
        }.onFailure {
            log.warn("could not install the coroutine debug probes; dumps are unavailable: {}", it.message)
        }
    }

    /** Whether the probes really capture: one probe coroutine that hangs, one check. */
    private suspend fun captureArmed(): Boolean =
        coroutineScope {
            val probe = launch(Dispatchers.Default) {
                CompletableDeferred<Unit>().await()
            }
            delay(ARMING_CHECK_MS)
            val armed = DebugProbes.dumpCoroutinesInfo().isNotEmpty()
            probe.cancel()
            armed
        }

    /**
     * Watches [dir] for [TRIGGER_FILE_NAME] and dumps on each appearance, until [scope] is
     * cancelled at daemon shutdown. Only ever launched when [enabled].
     */
    fun watchForDumpRequests(
        dir: Path,
        scope: CoroutineScope,
    ) {
        scope.launch {
            val trigger = dir.resolve(TRIGGER_FILE_NAME)
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!Files.exists(trigger)) continue
                dumpCoroutines(dir)
                runCatching { Files.deleteIfExists(trigger) }
                    .onFailure { log.warn("could not delete the dump trigger {}: {}", trigger, it.message) }
            }
        }
    }

    /** Writes every live coroutine with its creation and suspension stacks to [dir]/coroutine-dump.txt. */
    fun dumpCoroutines(dir: Path) {
        try {
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, "coroutine-dump-", ".tmp")
            try {
                val count = DebugProbes.dumpCoroutinesInfo().size
                Files.newOutputStream(tmp).use { out ->
                    PrintStream(out, false, Charsets.UTF_8).use { printer ->
                        printer.println("unidrive coroutine dump: $count coroutine(s)")
                        DebugProbes.dumpCoroutines(printer)
                    }
                }
                val dump = dir.resolve(DUMP_FILE_NAME)
                runCatching {
                    Files.move(tmp, dump, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    Files.move(tmp, dump, StandardCopyOption.REPLACE_EXISTING)
                }
                log.info("wrote the coroutine dump ($count coroutine(s)) to {}", dump)
            } finally {
                Files.deleteIfExists(tmp)
            }
        } catch (e: Exception) {
            log.warn("coroutine dump failed: {}", e.message)
        }
    }
}
