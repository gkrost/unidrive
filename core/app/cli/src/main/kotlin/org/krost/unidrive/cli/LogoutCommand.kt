package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking

@Command(
    name = "logout",
    description = [
        "Sign out: revoke the provider's token and remove the stored credentials. The sync state is kept unless " +
            "--delete-state is given (the state and the failed-upload record exist only on this machine). " +
            "Works while the profile's daemon runs: the sign-out is handed to the daemon over IPC, which refuses " +
            "while uploads are pending (without credentials they cannot finish) and keeps running unauthenticated.",
    ],
    mixinStandardHelpOptions = true,
)
class LogoutCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    // The state-deleting form is opt-in (--delete-state): the destructive part is the one that has to be asked
    // for. --keep-state stays accepted for compatibility; it is what the command does by default now.
    @Option(names = ["--keep-state"], description = ["Keep the sync state (the default; accepted for compatibility)"])
    var keepState: Boolean = false

    @Option(
        names = ["--delete-state"],
        description = ["Also delete the sync state (state.db, failures.jsonl); refused while the profile's daemon runs"],
    )
    var deleteState: Boolean = false

    @Option(
        names = ["--force-pending"],
        description = ["Sign out even though uploads are still pending (refusal token uploads_pending; failed uploads alone do not block)"],
    )
    var forcePending: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int {
        // --keep-state is the compatibility form of the default; it wins over --delete-state.
        val delete = deleteState && !keepState
        if (json) {
            return try {
                emitJson(logout(parent, profile, delete, forcePending).json)
                0
            } catch (e: LifecycleError) {
                emitJson(lifecycleErrorJson(e))
                1
            } catch (e: CredentialProtectionException) {
                emitJson(
                    lifecycleErrorJson(
                        LifecycleError("credential_protection_unavailable", e.message ?: "unlock the native credential store and retry", e),
                    ),
                )
                1
            } catch (e: Exception) {
                // A front-end parses stdout: whatever else goes wrong is still one JSON refusal, with the
                // exception's type only (its message could quote a response or a path).
                emitJson(lifecycleErrorJson(LifecycleError("internal_error", "unexpected failure: ${e.javaClass.simpleName}")))
                1
            }
        }

        val configPath = parent.configBaseDir().resolve("config.toml")
        if (!Files.exists(configPath)) parent.reportConfigMissingAndExit(configPath)
        return try {
            val target = resolveProfileTarget(parent, profile)
            val result = logout(parent, profile, delete, forcePending)
            println("Logged out of ${profileLabel(target)}.")
            for (f in result.removedFiles) println("  Removed: $f")
            if (!delete) println("  Sync state preserved.")
            if (result.viaDaemon) println("  The daemon keeps running without credentials (unauthenticated).")
            0
        } catch (e: LifecycleError) {
            System.err.println("Error: ${e.message}")
            1
        } catch (e: CredentialProtectionException) {
            System.err.println("Error: ${e.message ?: "the credential marker could not be removed from the OS secret store"}")
            1
        }
    }
}

/** The display name a sign-out names: the provider's, with the profile in brackets when it is not the type. */
internal fun profileLabel(target: ProfileTarget): String {
    val displayName = ProviderRegistry.getMetadata(target.type)?.displayName ?: target.type
    return if (target.name != target.type) "$displayName (${target.name})" else displayName
}

/** The outcome of asking the provider to revoke its token: confirmed, failed, or nothing to revoke. */
internal enum class Revocation { REVOKED, FAILED, NOT_AUTHENTICATED }

/**
 * Asks [logout] to revoke the token and maps the outcome. A failure (the provider could not be reached, the
 * revocation was refused) still signs out locally; a [CredentialProtectionException] passes through — the
 * marker file must survive when its secret-store item could not be removed.
 */
internal fun revokeToken(logout: suspend () -> Unit): Revocation =
    try {
        runBlocking { logout() }
        Revocation.REVOKED
    } catch (_: AuthenticationException) {
        Revocation.NOT_AUTHENTICATED
    } catch (e: CredentialProtectionException) {
        throw e
    } catch (_: Exception) {
        Revocation.FAILED
    }

internal fun deleteCredentialFiles(profileDir: Path): List<String> =
    StoragePermissions.CREDENTIAL_FILES.mapNotNull { name ->
        val file = profileDir.resolve(name)
        if (Files.deleteIfExists(file)) file.toString() else null
    }

internal fun deleteStateFiles(profileDir: Path): List<String> =
    STATE_FILES.mapNotNull { name ->
        val file = profileDir.resolve(name)
        if (Files.deleteIfExists(file)) file.toString() else null
    }

private val STATE_FILES = listOf("state.db", "failures.jsonl")

internal fun logoutAndDeleteLocalFiles(
    profileDir: Path,
    keepState: Boolean,
    logout: suspend () -> Unit,
): List<String> {
    revokeToken(logout)
    val deleted = deleteCredentialFiles(profileDir).toMutableList()
    if (!keepState) deleted += deleteStateFiles(profileDir)
    return deleted
}

/** What a sign-out did: [json] is the `--json` reply, the rest is the console rendering. */
internal class LogoutResult(
    val json: JsonObject,
    /** The deleted files, for the console listing; empty when the daemon did the deletion. */
    val removedFiles: List<String>,
    /** True when a running daemon performed the sign-out over IPC and now serves the profile unauthenticated. */
    val viaDaemon: Boolean,
)

/** Test seams of the sign-out; production uses the defaults and never reassigns them. */
internal object LogoutHooks {
    /** The IPC socket of a profile. */
    var socketPath: (String) -> Path = { IpcServer.defaultSocketPath(it) }

    /** Sends `auth.logout` to the daemon on the endpoint; its parsed reply, or null when it could not be asked. */
    var requestLogout: (IpcEndpoint, Boolean) -> JsonObject? = { endpoint, force -> sendAuthLogout(endpoint, force) }
}

/**
 * Signs [profileOption] (else `-p`, else the default profile) out.
 *
 * A profile held by another unidrive process is refused (`profile_in_use`). A running daemon performs the
 * sign-out itself over IPC (`auth.logout`, §4.10 of the daemon design): it refuses while uploads are pending
 * and keeps its sync state, so `--delete-state` is refused against it. A stopped profile is signed out here,
 * under the profile lock, with the same refusals (`uploads_pending`, `state_unreadable`) as `profile remove`.
 * Never exits the JVM: every refusal is a [LifecycleError] the caller renders.
 */
internal fun logout(
    main: Main,
    profileOption: String?,
    deleteState: Boolean,
    forcePending: Boolean,
): LogoutResult {
    val target = resolveProfileTarget(main, profileOption)
    val socket = LogoutHooks.socketPath(target.name)
    when (ProfileRemover.holderOf(target.profileDir, socket)) {
        ProfileRemover.Holder.OTHER ->
            throw LifecycleError("profile_in_use", "profile '${target.name}' is in use by another unidrive process; stop it first")

        ProfileRemover.Holder.DAEMON -> {
            if (deleteState) {
                throw LifecycleError(
                    "daemon_running",
                    "the sync state of the running daemon of profile '${target.name}' cannot be deleted; " +
                        "stop it first (`unidrive -p ${target.name} daemon stop`), then run logout --delete-state",
                )
            }
            val reply = LogoutHooks.requestLogout(IpcEndpoint(socket, target.profileDir, target.name), forcePending)
            if (reply != null) return signedOutByDaemon(target, reply)
            // No answer — the daemon may have stopped while it was asked: only a profile that is free now
            // falls through to the local form, anything still holding it is a refusal.
            if (ProfileRemover.holderOf(target.profileDir, socket) != ProfileRemover.Holder.NONE) {
                throw LifecycleError(
                    "daemon_running",
                    "the daemon of profile '${target.name}' did not answer the sign-out; " +
                        "stop it first (`unidrive -p ${target.name} daemon stop`)",
                )
            }
        }

        ProfileRemover.Holder.NONE -> {}
    }
    return withProfileLock(target) { logoutStoppedProfile(main, target, deleteState, forcePending) }
}

/** The daemon's answer to `auth.logout`: a success reply becomes the command's, a refusal passes through. */
private fun signedOutByDaemon(
    target: ProfileTarget,
    reply: JsonObject,
): LogoutResult {
    if ((reply["ok"] as? JsonPrimitive)?.content == "true") {
        val json =
            buildJsonObject {
                put("ok", true)
                put("profile", target.name)
                put("credentials_removed", reply.optBoolean("credentials_removed") ?: false)
                put("revoked", reply.optBoolean("revoked")?.let { JsonPrimitive(it) } ?: JsonNull)
                put("state_kept", true)
                put("daemon_unauthenticated", true)
            }
        return LogoutResult(json, emptyList(), viaDaemon = true)
    }
    when (val token = (reply["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "uploads_pending" -> {
            val pending = (reply["pending"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val failed = (reply["failed"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            throw LifecycleError(
                "uploads_pending",
                "profile '${target.name}' still has uploads that never reached the cloud ($pending pending, $failed of them failed); " +
                    "let them finish, or run again with --force-pending",
                details =
                    buildJsonObject {
                        put("pending", pending)
                        put("failed", failed)
                    },
            )
        }
        "credential_protection_unavailable" ->
            throw LifecycleError(
                token,
                "the credential marker of profile '${target.name}' could not be removed from the OS secret store; unlock it and retry",
            )
        "unknown_verb" ->
            throw LifecycleError(
                "daemon_running",
                "the running daemon of profile '${target.name}' is older than this command and cannot sign out over IPC; " +
                    "stop it first (`unidrive -p ${target.name} daemon stop`)",
            )
        else ->
            throw LifecycleError(
                token ?: "daemon_logout_failed",
                "the daemon of profile '${target.name}' refused the sign-out${token?.let { " ($it)" } ?: ""}",
            )
    }
}

/** The sign-out of a stopped profile, under the profile lock. The destructive form deletes the sync state. */
private fun logoutStoppedProfile(
    main: Main,
    target: ProfileTarget,
    delete: Boolean,
    forcePending: Boolean,
): LogoutResult {
    if (!forcePending) ProfileRemover.refuseIfUploadsPending(target.profileDir, target.name)
    val provider = main.createProviderForName(target.name)
    val revoked: Revocation =
        try {
            revokeToken { provider.logout() }
        } finally {
            runCatching { provider.close() }
        }
    val removed = deleteCredentialFiles(target.profileDir).toMutableList()
    var stateKept = true
    if (delete) {
        removed += deleteStateFiles(target.profileDir)
        stateKept = false
    }
    val json =
        buildJsonObject {
            put("ok", true)
            put("profile", target.name)
            put("credentials_removed", removed.isNotEmpty())
            put("revoked", when (revoked) {
                Revocation.REVOKED -> JsonPrimitive(true)
                Revocation.FAILED -> JsonPrimitive(false)
                Revocation.NOT_AUTHENTICATED -> JsonNull
            })
            put("state_kept", stateKept)
            put("daemon_unauthenticated", false)
        }
    return LogoutResult(json, removed, viaDaemon = false)
}

/**
 * Sends `auth.logout` to the daemon listening on [endpoint] and reads its one reply line. Null when the
 * socket is absent or unreachable, the IPC handshake fails (an admin verb needs a full-scope connection)
 * or no reply arrives in time; never throws. A missing reply means "cannot be asked", never a refusal.
 */
internal fun sendAuthLogout(
    endpoint: IpcEndpoint,
    force: Boolean,
    timeoutMs: Long = 5_000,
): JsonObject? {
    if (!Files.exists(endpoint.socketPath)) return null
    return try {
        IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).use { channel ->
            channel.configureBlocking(false)
            val request = if (force) """{"verb":"auth.logout","force":true}""" else """{"verb":"auth.logout"}"""
            val out = ByteBuffer.wrap((request + "\n").toByteArray())
            val deadline = System.currentTimeMillis() + timeoutMs
            while (out.hasRemaining()) {
                channel.write(out)
                if (out.hasRemaining()) {
                    if (System.currentTimeMillis() >= deadline) return null
                    Thread.sleep(10)
                }
            }
            val collected = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val buf = ByteBuffer.allocate(4096)
                val n = channel.read(buf)
                if (n < 0) break
                if (n > 0) {
                    buf.flip()
                    collected.append(String(buf.array(), 0, buf.limit()))
                    val newline = collected.indexOf('\n')
                    if (newline >= 0) {
                        val line = collected.substring(0, newline)
                        return runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    }
                } else {
                    Thread.sleep(10)
                }
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}

/** A JSON boolean member, or null when it is absent or not a boolean literal. */
private fun JsonObject.optBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
