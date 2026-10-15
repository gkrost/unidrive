package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.krost.unidrive.engine.CachePaths
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
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
    mixinStandardHelpOptions = true,
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
                    emitJson(rowsJson(rows, env.now, env::cacheSize))
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
                if (!json) {
                    println("Would drop ${selected.size} row(s):")
                    selected.forEach { println("  ${it.path}  size=${it.localSize ?: "?"}") }
                }
                if (!yes) {
                    if (json) throw LifecycleError("confirmation_required", "--yes is required with --json; the command never prompts")
                    print("Drop these rows? [y/N] ")
                    val answer = readlnOrNull()?.trim()?.lowercase()
                    if (answer != "y" && answer != "yes") {
                        println("Aborted; nothing changed.")
                        return 0
                    }
                }
                val dropped = mutableListOf<String>()
                val moved = mutableListOf<Pair<String, Path>>()
                var movedTo: Path? = null
                env.withDropLock {
                    // A prompt may have been open while another CLI ran. Recheck every row under the lock.
                    val pending = env.readRows().mapTo(mutableSetOf()) { it.path }
                    for (row in selected) {
                        if (row.path !in pending) continue
                        val hasContent = env.cacheHasContent(row.path)
                        if (hasContent) {
                            val aside = movedTo ?: env.reserveAsideFolder().also { movedTo = it }
                            val target = CachePaths.resolveInside(aside, row.path)
                            Files.createDirectories(target.parent)
                            // The move happens BEFORE the row goes: a failure here leaves the row intact.
                            Files.move(env.cachePath(row.path), target)
                            moved += row.path to target
                        }
                        if (env.deleteLocalRow(row.path)) dropped += row.path
                    }
                }
                if (!json) {
                    println("Dropped ${dropped.size} row(s).")
                    moved.forEach { (path, target) -> println("  cache of $path moved to $target") }
                    println("Changed rows are logged above; the command is safe to run again.")
                }
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

        fun <T> withDropLock(block: () -> T): T {
            val lock = ProcessLock(profileDir.resolve(".lock"))
            if (!lock.tryLock(ProcessLock.Mode.SYNC)) {
                val token = if (ProfileRemover.holderOf(profileDir, socketPath) == ProfileRemover.Holder.DAEMON) "daemon_running" else "profile_in_use"
                throw LifecycleError(token, "profile '${profile.name}' became busy; nothing was dropped")
            }
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }

        /** The read-only state.db, or null when it cannot be opened or initialised (locked, corrupt, unexpected schema) —
         * the same trio the read-only reads of `profile remove` accept as "nothing can be said about it". */
        private fun openReadOnly(): StateDatabase? =
            try {
                StateDatabase(profileDir.resolve("state.db"), readOnly = true).also { it.initialize() }
            } catch (_: java.sql.SQLException) {
                null
            } catch (_: IllegalStateException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        fun readRows(): List<StateDatabase.PendingRowDetail> {
            val db =
                openReadOnly()
                    ?: throw LifecycleError("state_unreadable", "the state of profile '${profile.name}' could not be read")
            try {
                return db.pendingRowDetails()
            } catch (e: java.sql.SQLException) {
                throw LifecycleError("state_unreadable", "the state of profile '${profile.name}' could not be read: ${e.message}", e)
            } finally {
                db.close()
            }
        }

        /** Refuse malformed row paths: the cache root is never a file to move aside. */
        fun cachePath(path: String): Path =
            try {
                CachePaths.resolveInside(cacheRoot, path).also {
                    if (it.toAbsolutePath().normalize() == cacheRoot.toAbsolutePath().normalize()) {
                        throw SecurityException("path resolves to the cache root")
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw LifecycleError("cache_path_invalid", "pending row has an invalid cache path", e)
            } catch (e: SecurityException) {
                throw LifecycleError("cache_path_invalid", "pending row has an invalid cache path", e)
            }

        fun cacheSize(path: String): Long? {
            val file = cachePath(path)
            if (Files.notExists(file)) return null
            if (!Files.isRegularFile(file)) throw LifecycleError("cache_unreadable", "cache path of '$path' is not a regular file")
            return try {
                file.fileSize()
            } catch (e: java.io.IOException) {
                throw LifecycleError("cache_unreadable", "cache path of '$path' could not be read", e)
            }
        }

        fun cacheHasContent(path: String): Boolean {
            return (cacheSize(path) ?: 0) > 0
        }

        fun cacheState(path: String): String {
            val size = cacheSize(path)
            return when (size) {
                null -> "missing"
                0L -> "empty"
                else -> "content(${size}B)"
            }
        }

        /** The folder a dropped row's non-empty cache copy is moved to: a sibling of the cache, never the sync root. */
        fun reserveAsideFolder(): Path =
            Files.createTempDirectory(cacheRoot.parent, "dropped-pending-")

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
            cacheSize: (String) -> Long?,
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
                                val size = cacheSize(row.path)
                                put("cache_present", size != null)
                                put("cache_size", size)
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
