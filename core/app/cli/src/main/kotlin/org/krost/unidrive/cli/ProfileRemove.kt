package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.isValidProfileName
import org.krost.unidrive.sync.removeProfileSection
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Test seams of `profile remove`; production uses the defaults and never reassigns them. */
internal object ProfileRemoveHooks {
    /** The IPC socket of a profile. */
    var socketPath: (String) -> Path = { IpcServer.defaultSocketPath(it) }

    /** Asks the daemon on the endpoint to shut down over IPC; true once it accepted the request. */
    var requestShutdown: (IpcEndpoint) -> Boolean = { requestDaemonShutdown(it) }

    /** How long `--stop` waits for the daemon to let go of the profile. */
    var stopWaitMs: Long = 15_000
}

/**
 * The safe `profile remove`, shared by the console and the `--json` form.
 *
 * It refuses while a process holds the profile (the process lock, or a socket that answers) and
 * never starts a daemon; `--stop` asks the running daemon to shut down over IPC, waits a bounded
 * time and proceeds only once the profile is free. It removes the config section, the profile
 * folder (state, credentials), the IPC token files and the socket. It never deletes the local
 * sync root or mount root, even when that folder lies inside the profile folder: it is reported
 * as kept. Nothing it returns or throws carries a secret, only names and counts.
 */
internal object ProfileRemover {
    private enum class Holder { NONE, DAEMON, OTHER }

    /** What [remove] did. */
    class Result(
        val json: JsonObject,
        val profileDir: Path,
        val deletedProfileDir: Boolean,
    )

    /** The config entry to remove, or a [LifecycleError] naming why it cannot be. */
    private fun load(
        configDir: Path,
        name: String,
    ): Triple<Path, String, org.krost.unidrive.sync.RawProvider> {
        if (!isValidProfileName(name)) throw LifecycleError("invalid_profile_name", "'$name' is not a valid profile name")
        val configPath = configDir.resolve("config.toml")
        if (!Files.exists(configPath)) throw LifecycleError("config_not_found", "no config.toml in $configDir")
        val content = Files.readString(configPath)
        val raw =
            try {
                SyncConfig.parseRaw(content, configPath.toString())
            } catch (_: Exception) {
                // The parser's message can quote the offending config line, which may hold a credential.
                throw LifecycleError("config_invalid", "config.toml could not be parsed")
            }
        val rp = raw.providers[name] ?: throw LifecycleError("profile_not_found", "profile '$name' is not configured")
        return Triple(configPath, content, rp)
    }

    private fun holderOf(
        profileDir: Path,
        socket: Path,
    ): Holder {
        val lock = ProcessLock(profileDir.resolve(".lock"))
        // Read-only: no file is created and no PID is stamped; null means somebody holds the lock.
        val hold = lock.tryHoldReadOnly()
        if (hold == null) {
            return if (lock.readLiveHolderInfo()?.mode == ProcessLock.Mode.DAEMON) Holder.DAEMON else Holder.OTHER
        }
        hold.close()
        return if (socketAnswers(socket)) Holder.DAEMON else Holder.NONE
    }

    /** True when something accepts a connection on [socket]. Sends nothing, so no daemon is disturbed. */
    private fun socketAnswers(socket: Path): Boolean {
        if (!Files.exists(socket)) return false
        return try {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).close()
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Removes the profile [name] from the config in [configDir]. [confirm] is asked once all checks
     * have passed and before anything is stopped or deleted; false cancels and returns null.
     * Throws [LifecycleError] for every refusal.
     */
    fun remove(
        configDir: Path,
        name: String,
        keepData: Boolean,
        stop: Boolean,
        confirm: () -> Boolean = { true },
    ): Result? {
        val (configPath, content, rp) = load(configDir, name)
        val profileDir = configDir.resolve(name)
        val socket = ProfileRemoveHooks.socketPath(name)

        var daemonStopped = false
        when (holderOf(profileDir, socket)) {
            Holder.NONE -> {}
            Holder.OTHER ->
                throw LifecycleError("profile_in_use", "profile '$name' is in use by another unidrive process; stop it first")
            Holder.DAEMON ->
                if (!stop) {
                    throw LifecycleError(
                        "daemon_running",
                        "a daemon is running for profile '$name'; stop it first, or run again with --stop",
                    )
                }
        }
        if (!confirm()) return null

        if (holderOf(profileDir, socket) == Holder.DAEMON) {
            val accepted = ProfileRemoveHooks.requestShutdown(IpcEndpoint(socket, profileDir, name))
            if (!accepted) {
                throw LifecycleError(
                    "daemon_stop_failed",
                    "the daemon of profile '$name' did not accept the stop request; nothing was removed",
                )
            }
            val deadline = System.currentTimeMillis() + ProfileRemoveHooks.stopWaitMs
            while (holderOf(profileDir, socket) != Holder.NONE) {
                if (System.currentTimeMillis() >= deadline) {
                    throw LifecycleError(
                        "daemon_stop_failed",
                        "the daemon of profile '$name' did not stop within ${ProfileRemoveHooks.stopWaitMs / 1000} s; nothing was removed",
                    )
                }
                Thread.sleep(100)
            }
            daemonStopped = true
        }

        // From here on a racing start is refused by the lock this process holds.
        val guard = ProcessLock(profileDir.resolve(".lock"))
        var guarded = false
        if (Files.isDirectory(profileDir)) {
            guarded = guard.tryLock(ProcessLock.Mode.SYNC)
            if (!guarded) throw LifecycleError("profile_in_use", "profile '$name' was taken by another unidrive process; nothing was removed")
        }

        val type = rp.type ?: name
        val syncRoot = rp.sync_root ?: SyncConfig.defaultSyncRoot(type).toString()
        val wasDefault = SyncConfig.parseRaw(content, configPath.toString()).general.default_profile == name
        val warnings = mutableListOf<String>()
        val existed = Files.isDirectory(profileDir)
        var credentialsRemoved = false
        var credentialsKept = false
        var tokensRemoved = false
        var socketRemoved = false
        try {
            try {
                Files.writeString(configPath, removeProfileSection(content.lines(), name).joinToString("\n"))
            } catch (e: IOException) {
                throw LifecycleError("config_write_failed", "config.toml could not be written: ${e.javaClass.simpleName}; nothing was removed")
            }

            // The IPC tokens and the socket belong to a daemon that is gone: always removed, also with --keep-data.
            for (scope in IpcAuth.Scope.entries) {
                if (deleteQuietly(IpcAuth.tokenFile(profileDir, scope), warnings)) tokensRemoved = true
            }
            if (deleteQuietly(socket, warnings)) socketRemoved = true
            deleteQuietly(socket.resolveSibling("${socket.fileName}.meta"), warnings)

            val present = StoragePermissions.CREDENTIAL_FILES.filter { Files.exists(profileDir.resolve(it), LinkOption.NOFOLLOW_LINKS) }
            if (keepData) {
                credentialsKept = present.isNotEmpty()
            } else {
                for (file in present) if (deleteQuietly(profileDir.resolve(file), warnings)) credentialsRemoved = true
                credentialsKept = present.any { Files.exists(profileDir.resolve(it), LinkOption.NOFOLLOW_LINKS) }
                if (existed) purge(profileDir, ProtectedRoot.of(syncRoot), warnings, isTop = true)
            }
        } finally {
            if (guarded) guard.unlock()
        }
        if (!keepData && Files.isDirectory(profileDir)) {
            deleteQuietly(profileDir.resolve(".lock"), warnings)
            deleteQuietly(profileDir.resolve(".lock.pid"), warnings)
            // Fails quietly while the folder still holds the local root or an entry that could not be deleted.
            runCatching { Files.deleteIfExists(profileDir) }
        }
        val profileDirGone = existed && !Files.exists(profileDir)
        val stateRemoved = existed && !keepData && warnings.none { it.startsWith("state: ") }
        val json =
            buildJsonObject {
                put("ok", true)
                put("profile", name)
                put("mode", rp.mode)
                put("daemon_stopped", daemonStopped)
                put("was_default_profile", wasDefault)
                put(
                    "removed",
                    buildJsonObject {
                        put("config_section", true)
                        put("state_folder", stateRemoved)
                        put("credentials", credentialsRemoved)
                        put("ipc_tokens", tokensRemoved)
                        put("socket", socketRemoved)
                    },
                )
                put(
                    "kept",
                    buildJsonObject {
                        // The local root is never touched: for a mirror profile the sync root, for a mount profile the mount root.
                        put("sync_root", syncRoot)
                        put("state_folder", Files.isDirectory(profileDir))
                        put("credentials", credentialsKept)
                    },
                )
                put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
            }
        return Result(json, profileDir, profileDirGone)
    }

    private fun deleteQuietly(
        path: Path,
        warnings: MutableList<String>,
    ): Boolean =
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            warnings += "${path.fileName}: could not be deleted (${e.javaClass.simpleName})"
            false
        }

    /** The local root that must survive the removal, as a lexical and (when it exists) a resolved path. */
    private class ProtectedRoot(
        private val lexical: Path?,
        private val real: Path?,
    ) {
        /** [dir] is the root itself. */
        fun isRoot(dir: Path): Boolean = matches(dir) { it == lexical || it == real }

        /** [dir] holds the root somewhere below it (or is the root). */
        fun covers(dir: Path): Boolean =
            matches(dir) { d -> (lexical?.startsWith(d) ?: false) || (real?.startsWith(d) ?: false) }

        private fun matches(
            dir: Path,
            test: (Path) -> Boolean,
        ): Boolean {
            val lex = dir.toAbsolutePath().normalize()
            return test(lex) || runCatching { test(dir.toRealPath()) }.getOrDefault(false)
        }

        companion object {
            fun of(syncRoot: String): ProtectedRoot {
                val lex = runCatching { Path.of(syncRoot).toAbsolutePath().normalize() }.getOrNull()
                val real = lex?.let { runCatching { it.toRealPath() }.getOrNull() }
                return ProtectedRoot(lex, real)
            }
        }
    }

    /**
     * Deletes [path] and everything below it except the protected root and what holds it. Returns true when [path] is gone.
     * A failed delete is recorded in [warnings] and never aborts the rest. The process lock files of the top
     * folder are left for the caller, which still holds the lock.
     */
    private fun purge(
        path: Path,
        root: ProtectedRoot,
        warnings: MutableList<String>,
        isTop: Boolean = false,
    ): Boolean {
        val isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        if (isDir && root.isRoot(path)) return false
        if (isDir) {
            val children =
                try {
                    Files.list(path).use { it.toList() }
                } catch (e: IOException) {
                    warnings += "state: ${path.fileName}: could not be listed (${e.javaClass.simpleName})"
                    return false
                }
            var all = true
            for (child in children) {
                if (isTop && (child.fileName.toString() == ".lock" || child.fileName.toString() == ".lock.pid")) {
                    all = false
                    continue
                }
                if (!purge(child, root, warnings)) all = false
            }
            if (!all || root.covers(path)) return false
            if (isTop) return false // the caller deletes the folder once the lock is released
        }
        return try {
            Files.deleteIfExists(path)
            true
        } catch (e: IOException) {
            warnings += "state: ${path.fileName}: could not be deleted (${e.javaClass.simpleName})"
            false
        }
    }
}
