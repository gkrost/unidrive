package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.krost.unidrive.engine.CachePaths
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * Inspect and safely drop stranded pending upload rows (#753): rows the daemon counts as pending whose
 * cache content is empty or missing, so folder listings hide their paths behind "still uploading" for
 * ever. `list` shows the rows with their cache state; `drop` removes them — never a row with a real
 * remote id (the database guard only deletes `local:` rows), never deleting non-empty cache data (a
 * copy is moved aside, and its new place is reported). The command refuses while a daemon holds the
 * profile: the rows are its working set.
 */
@Command(
    name = "pending",
    description = ["Inspect and safely drop stranded pending upload rows"],
    subcommands = [PendingCommand.ListCommand::class, PendingCommand.DropCommand::class],
)
class PendingCommand {
    @ParentCommand
    lateinit var parent: Main

    @Command(name = "list", description = ["Show the pending upload rows and their cache state"])
    class ListCommand : java.util.concurrent.Callable<Int> {
        @ParentCommand
        lateinit var parent: PendingCommand

        @Option(names = ["--json"], description = ["Print the result as one JSON object"])
        var json: Boolean = false

        override fun call(): Int =
            try {
                val env = PendingEnv(parent.parent)
                val rows = env.readRows()
                if (rows.isEmpty()) {
                    if (json) emitJson(buildJsonObject { put("ok", true); put("count", 0) }) else println("No pending upload rows.")
                } else if (json) {
                    emitJson(rowsJson(rows, env.now))
                } else {
                    println("Pending upload rows (${rows.size}):")
                    for (row in rows) {
                        val age = Duration.between(row.lastSynced, env.now).toString().lowercase().removePrefix("pt")
                        println(
                            "  ${row.path}  size=${row.localSize ?: "?"}  age=$age" +
                                "  cache=${env.cacheState(row.path)}  failed=${row.lastErrorAt != null}",
                        )
                    }
                }
                0
            } catch (e: LifecycleError) {
                if (json) emitJson(lifecycleErrorJson(e)) else System.err.println("Error: ${e.message}")
                1
            }
    }

    @Command(name = "drop", description = ["Drop stranded pending upload rows after a preview"])
    class DropCommand : java.util.concurrent.Callable<Int> {
        @ParentCommand
        lateinit var parent: PendingCommand

        @Option(names = ["--json"], description = ["Print the result as one JSON object"])
        var json: Boolean = false

        @Option(names = ["--yes"], description = ["Drop without asking"])
        var yes: Boolean = false

        @Option(names = ["--zero-byte"], description = ["Select only rows whose local size is zero and whose cache file is missing or empty"])
        var zeroByte: Boolean = false

        @Parameters(index = "0", arity = "0..1", description = ["Drop only rows whose path starts with this prefix"])
        var prefix: String? = null

        override fun call(): Int =
            try {
                if (!zeroByte && prefix == null) throw LifecycleError("missing_selection", "give a path prefix or --zero-byte")
                val env = PendingEnv(parent.parent)
                val selected =
                    env.readRows().filter { row ->
                        (prefix?.let { row.path.startsWith(it) } ?: true) &&
                            (!zeroByte || (row.localSize == 0L && !env.cacheHasContent(row.path)))
                    }
                if (selected.isEmpty()) {
                    if (json) emitJson(dropJson(emptyList(), emptyList(), null)) else println("Nothing to drop.")
                    return 0
                }
                println("Would drop ${selected.size} row(s):")
                selected.forEach { println("  ${it.path}  size=${it.localSize ?: "?"}") }
                if (!yes) {
                    if (json) throw LifecycleError("confirmation_required", "--yes is required with --json; the command never prompts")
                    print("Drop these rows? [y/N] ")
                    val answer = readlnOrNull()?.trim()?.lowercase()
                    if (answer != "y" && answer != "yes") {
                        println("Aborted; nothing changed.")
                        return 0
                    }
                }
                val movedTo = if (selected.any { env.cacheHasContent(it.path) }) env.reserveAsideFolder() else null
                val dropped = mutableListOf<String>()
                val moved = mutableListOf<Pair<String, Path>>()
                for (row in selected) {
                    val hasContent = env.cacheHasContent(row.path)
                    if (hasContent && movedTo != null) {
                        val target = movedTo.resolve(row.path.removePrefix("/"))
                        Files.createDirectories(target.parent)
                        // The move happens BEFORE the row goes: a failure here leaves the row intact.
                        Files.move(env.cachePath(row.path), target, StandardCopyOption.REPLACE_EXISTING)
                        moved += row.path to target
                    }
                    if (env.deleteLocalRow(row.path)) dropped += row.path
                }
                movedTo?.let { aside -> if (moved.isEmpty() && aside.toFile().list().isNullOrEmpty()) Files.deleteIfExists(aside) }
                println("Dropped ${dropped.size} row(s).")
                moved.forEach { (path, target) -> println("  cache of $path moved to $target") }
                println("Changed rows are logged above; the command is safe to run again.")
                if (json) emitJson(dropJson(dropped, moved.map { it.first }, movedTo))
                0
            } catch (e: LifecycleError) {
                if (json) emitJson(lifecycleErrorJson(e)) else System.err.println("Error: ${e.message}")
                1
            }
    }

    /** Everything the subcommands need about the profile: the rows, their cache paths, the guards. */
    internal class PendingEnv(
        main: Main,
    ) {
        val profile = main.resolveCurrentProfile()
        private val profileDir = main.providerConfigDir()
        private val socketPath = IpcServer.defaultSocketPath(profile.name)

        init {
            // The rows are a running daemon's working set: the command refuses rather than racing it
            // (the same detection `profile remove` uses).
            val holder = ProfileRemover.holderOf(profileDir, socketPath)
            if (holder == ProfileRemover.Holder.DAEMON) {
                throw LifecycleError("daemon_running", "a daemon is running for profile '${profile.name}'; stop it first")
            }
            if (holder == ProfileRemover.Holder.OTHER) {
                throw LifecycleError("profile_in_use", "profile '${profile.name}' is in use by another unidrive process; stop it first")
            }
        }

        val now: Instant = Instant.now()
        private val cacheRoot: Path =
            SyncEngine.hydrationCacheRoot(SyncEngine.defaultHydrationCacheRoot(), profile.name)

        private fun open(): StateDatabase =
            try {
                StateDatabase(profileDir.resolve("state.db"), readOnly = true).also { it.initialize() }
            } catch (e: Exception) {
                throw LifecycleError("state_unreadable", "the state of profile '${profile.name}' could not be read: ${e.message}")
            }

        fun readRows(): List<StateDatabase.PendingRowDetail> {
            val db = open()
            try {
                return db.pendingRowDetails()
            } catch (e: Exception) {
                throw LifecycleError("state_unreadable", "the state of profile '${profile.name}' could not be read: ${e.message}")
            } finally {
                db.close()
            }
        }

        /** The cache file of a logical path, or the cache root itself when the path does not resolve inside it. */
        fun cachePath(path: String): Path =
            runCatching { CachePaths.resolveInside(cacheRoot, path) }.getOrDefault(cacheRoot)

        fun cacheHasContent(path: String): Boolean {
            val file = cachePath(path)
            return file.exists() && file.fileSize() > 0
        }

        fun cacheState(path: String): String {
            val file = cachePath(path)
            return when {
                !file.exists() -> "missing"
                file.fileSize() == 0L -> "empty"
                else -> "content(${file.fileSize()}B)"
            }
        }

        /** The folder a dropped row's non-empty cache copy is moved to: a sibling of the cache, never the sync root. */
        fun reserveAsideFolder(): Path =
            Files.createDirectories(
                cacheRoot.resolveSibling("dropped-pending-" + now.toString().replace(':', '-')),
            )

        /** Deletes the row only while it is still a `local:` pending row; the database re-checks. */
        fun deleteLocalRow(path: String): Boolean {
            val db = StateDatabase(profileDir.resolve("state.db"))
            try {
                db.initialize()
                return db.deleteLocalPendingRow(path)
            } finally {
                db.close()
            }
        }
    }

    internal companion object {
        fun rowsJson(
            rows: List<StateDatabase.PendingRowDetail>,
            now: Instant,
        ): JsonObject =
            buildJsonObject {
                put("ok", true)
                put("count", rows.size)
                putJsonArray("rows") {
                    for (row in rows) {
                        add(
                            buildJsonObject {
                                put("path", row.path)
                                put("remote_id", row.remoteId)
                                put("local_size", row.localSize)
                                put("age_ms", Duration.between(row.lastSynced, now).toMillis())
                                put("last_error_at", row.lastErrorAt?.toString())
                            },
                        )
                    }
                }
            }

        fun dropJson(
            dropped: List<String>,
            moved: List<String>,
            asideFolder: Path?,
        ): JsonObject =
            buildJsonObject {
                put("ok", true)
                put("dropped", dropped.size)
                putJsonArray("paths") { dropped.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("moved_aside") { moved.forEach { add(JsonPrimitive(it)) } }
                asideFolder?.let { put("aside_folder", it.toString()) }
                putJsonObject("changed") { put("count", dropped.size) }
            }
    }
}
