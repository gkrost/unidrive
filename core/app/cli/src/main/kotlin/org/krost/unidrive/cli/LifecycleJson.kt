package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.internxt.InternxtConfig
import org.krost.unidrive.internxt.authenticateInternxt
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.isValidProfileName
import java.nio.file.Files
import java.nio.file.Path

/**
 * A refusal of a lifecycle command (`profile add`, `auth begin/complete/login`), machine-readable:
 * [token] is the stable identifier a front-end switches on, [message] is for a human. Neither ever
 * carries a secret, a password, a TOTP code or a token.
 */
internal class LifecycleError(
    val token: String,
    message: String,
) : Exception(message)

/** One compact JSON object on stdout. Anything else on stdout would break a front-end's parser. */
internal fun emitJson(obj: JsonObject) = println(obj.toString())

/** The `{"ok":false,"error":token,"message":...}` reply. */
internal fun lifecycleErrorJson(e: LifecycleError): JsonObject =
    buildJsonObject {
        put("ok", false)
        put("error", e.token)
        put("message", e.message ?: e.token)
    }

/** Test seams of the lifecycle commands; production uses the defaults and never reassigns them. */
internal object LifecycleHooks {
    /** The provider factory of a profile type. */
    var factoryFor: (String) -> ProviderFactory? = { ProviderRegistry.get(it) }

    /** The Internxt login (the library entry point, no console). */
    var internxtLogin: suspend (InternxtConfig, String, String, String?) -> InternxtCredentials =
        { config, email, password, tfa -> authenticateInternxt(config, email, password, tfa) }

    /** Waits between two polls of `auth complete --wait`. */
    var sleepSeconds: (Long) -> Unit = { Thread.sleep(it * 1000) }
}

/** The profile a lifecycle command acts on. */
internal class ProfileTarget(
    val name: String,
    val type: String,
    val profileDir: Path,
)

/**
 * Resolves the profile without ever exiting the JVM or prompting: the explicit [profileOption], else
 * the global `-p`, else `[general] default_profile`. A name that is not a configured profile is
 * refused; the type is the profile's `type`.
 */
internal fun resolveProfileTarget(
    main: Main,
    profileOption: String?,
): ProfileTarget {
    val baseDir = main.configBaseDir()
    val configPath = baseDir.resolve("config.toml")
    if (!Files.exists(configPath)) {
        throw LifecycleError("config_not_found", "no config.toml in $baseDir")
    }
    val raw =
        try {
            SyncConfig.parseRaw(Files.readString(configPath), configPath.toString())
        } catch (_: Exception) {
            // The parser's message can quote the offending config line, which may hold a credential.
            throw LifecycleError("config_invalid", "config.toml could not be parsed")
        }
    val name = profileOption ?: main.provider ?: SyncConfig.resolveDefaultProfile(baseDir)
    if (!isValidProfileName(name)) {
        throw LifecycleError("invalid_profile_name", "'$name' is not a valid profile name")
    }
    val rp = raw.providers[name] ?: throw LifecycleError("profile_not_found", "profile '$name' is not configured")
    return ProfileTarget(name, rp.type ?: name, baseDir.resolve(name))
}

/**
 * Runs [block] holding the profile's process lock, like the console `auth`, but a lock held by a
 * daemon or a sync is a `profile_in_use` reply instead of an exit. Creates the profile folder
 * owner-only when it does not exist yet.
 */
internal fun <T> withProfileLock(
    target: ProfileTarget,
    block: () -> T,
): T {
    try {
        Files.createDirectories(target.profileDir)
        org.krost.unidrive.io.OwnerOnly
            .requireDirectory(target.profileDir)
    } catch (e: java.io.IOException) {
        throw LifecycleError("permissions_failed", "the profile folder could not be made owner-only: ${e.javaClass.simpleName}")
    }
    val lock = ProcessLock(target.profileDir.resolve(".lock"))
    if (!lock.tryLock(ProcessLock.Mode.SYNC)) {
        val holder = lock.readLiveHolderInfo()
        val by =
            when (holder?.mode) {
                ProcessLock.Mode.DAEMON -> "a running daemon"
                ProcessLock.Mode.SYNC -> "a running sync"
                else -> "another unidrive process"
            }
        throw LifecycleError("profile_in_use", "profile '${target.name}' is in use by $by; stop it first")
    }
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
