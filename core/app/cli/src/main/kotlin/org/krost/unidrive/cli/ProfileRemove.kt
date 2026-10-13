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
import org.krost.unidrive.sync.StateDatabase
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
 * time and proceeds only once the profile is free. A stopped profile whose state.db still holds uploads
 * on their way (pending, failed ones apart) is refused unless the caller passes the override. It removes the config section, the profile
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

    /** The config of [configDir] as text and parsed, or a [LifecycleError] naming why it cannot be read. */
    private fun readConfig(configDir: Path): Pair<String, org.krost.unidrive.sync.RawSyncConfig> {
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
        return content to raw
    }

    /** The profile entry to remove, or a [LifecycleError] naming why there is none. */
    private fun lookup(
        raw: org.krost.unidrive.sync.RawSyncConfig,
        name: String,
    ): org.krost.unidrive.sync.RawProvider {
        if (!isValidProfileName(name)) throw LifecycleError("invalid_profile_name", "'$name' is not a valid profile name")
        return raw.providers[name] ?: throw LifecycleError("profile_not_found", "profile '$name' is not configured")
    }

    /** Refuses while another process holds the profile; a daemon is only tolerated when [stop] will deal with it. */
    private fun refuseIfHeld(
        holder: Holder,
        name: String,
        stop: Boolean,
    ) {
        if (holder == Holder.OTHER) {
            throw LifecycleError("profile_in_use", "profile '$name' is in use by another unidrive process; stop it first")
        }
        if (holder == Holder.DAEMON && !stop) {
            throw LifecycleError("daemon_running", "a daemon is running for profile '$name'; stop it first, or run again with --stop")
        }
    }

    /**
     * Refuses a profile whose state.db still holds uploads that are on their way. The state.db is opened read-only and
     * the rows are those the daemon reports as `uploads.pending` (same query); `failed` is the subset with a failed
     * attempt on record. Failed uploads alone do not block: the user decides about those. A missing state.db has
     * nothing pending; one that exists but cannot be read is refused, since nothing can be said about it.
     */
    private fun refuseIfUploadsPending(
        profileDir: Path,
        name: String,
    ) {
        val stateDb = profileDir.resolve("state.db")
        if (!Files.exists(stateDb)) return
        val rows =
            try {
                val db = StateDatabase(stateDb, readOnly = true)
                try {
                    db.initialize()
                    db.pendingUploadRows()
                } finally {
                    db.close()
                }
            } catch (e: Exception) {
                throw LifecycleError(
                    "state_unreadable",
                    "the state of profile '$name' could not be read: ${e.javaClass.simpleName}; nothing was removed",
                    e,
                )
            }
        val pending = rows.size
        val failed = rows.count { it.lastErrorAt != null }
        if (pending - failed > 0) {
            throw LifecycleError(
                "uploads_pending",
                "profile '$name' still has uploads that never reached the cloud ($pending pending, $failed of them failed); " +
                    "let them finish, or run again with --force-pending",
                details =
                    buildJsonObject {
                        put("pending", pending)
                        put("failed", failed)
                    },
            )
        }
    }

    /** Asks the daemon to stop over IPC and waits a bounded time for the profile to be free; never kills it. */
    private fun stopDaemon(
        profileDir: Path,
        socket: Path,
        name: String,
    ) {
        val accepted = ProfileRemoveHooks.requestShutdown(IpcEndpoint(socket, profileDir, name))
        if (!accepted) {
            throw LifecycleError("daemon_stop_failed", "the daemon of profile '$name' did not accept the stop request; nothing was removed")
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
    }

    private fun writeConfig(
        configPath: Path,
        text: String,
    ) {
        try {
            Files.writeString(configPath, text)
        } catch (e: IOException) {
            throw LifecycleError("config_write_failed", "config.toml could not be written: ${e.javaClass.simpleName}; nothing was removed", e)
        }
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
        forcePending: Boolean = false,
        confirm: () -> Boolean = { true },
    ): Result? {
        val configPath = configDir.resolve("config.toml")
        val (content, raw) = readConfig(configDir)
        val rp = lookup(raw, name)
        val profileDir = configDir.resolve(name)
        val socket = ProfileRemoveHooks.socketPath(name)

        val holder = holderOf(profileDir, socket)
        refuseIfHeld(holder, name, stop)
        if (holder == Holder.NONE && !forcePending) refuseIfUploadsPending(profileDir, name)
        if (!confirm()) return null

        var daemonStopped = false
        if (holderOf(profileDir, socket) == Holder.DAEMON) {
            stopDaemon(profileDir, socket, name)
            daemonStopped = true
        }

        // From here on a racing start is refused by the lock this process holds.
        val guard = ProcessLock(profileDir.resolve(".lock"))
        val dirExists = Files.isDirectory(profileDir)
        val guarded = dirExists && guard.tryLock(ProcessLock.Mode.SYNC)
        if (dirExists && !guarded) {
            throw LifecycleError("profile_in_use", "profile '$name' was taken by another unidrive process; nothing was removed")
        }

        val type = rp.type ?: name
        val syncRoot = rp.sync_root ?: SyncConfig.defaultSyncRoot(type).toString()
        val wasDefault = raw.general.default_profile == name
        val warnings = mutableListOf<String>()
        val existed = dirExists
        var credentialsRemoved = false
        var credentialsKept = false
        var tokensRemoved = false
        var socketRemoved = false
        try {
            writeConfig(configPath, removeProfileSection(content.lines(), name).joinToString("\n"))

            // The IPC tokens and the socket belong to a daemon that is gone: always removed, also with --keep-data.
            for (scope in IpcAuth.Scope.entries) {
                if (ProfilePurge.deleteQuietly(IpcAuth.tokenFile(profileDir, scope), warnings)) tokensRemoved = true
            }
            if (ProfilePurge.deleteQuietly(socket, warnings)) socketRemoved = true
            ProfilePurge.deleteQuietly(socket.resolveSibling("${socket.fileName}.meta"), warnings)

            val present = StoragePermissions.CREDENTIAL_FILES.filter { Files.exists(profileDir.resolve(it), LinkOption.NOFOLLOW_LINKS) }
            if (keepData) {
                credentialsKept = present.isNotEmpty()
            } else {
                for (file in present) if (ProfilePurge.deleteQuietly(profileDir.resolve(file), warnings)) credentialsRemoved = true
                credentialsKept = present.any { Files.exists(profileDir.resolve(it), LinkOption.NOFOLLOW_LINKS) }
                if (existed) ProfilePurge.purge(profileDir, ProfilePurge.ProtectedRoot.of(syncRoot), warnings, isTop = true)
            }
        } finally {
            if (guarded) guard.unlock()
        }
        if (!keepData && Files.isDirectory(profileDir)) {
            ProfilePurge.deleteQuietly(profileDir.resolve(".lock"), warnings)
            ProfilePurge.deleteQuietly(profileDir.resolve(".lock.pid"), warnings)
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
                        // Credentials held outside the profile folder (an OS secret store) are not touched.
                        put("secret_store", "not touched")
                    },
                )
                put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
            }
        return Result(json, profileDir, profileDirGone)
    }
}
