package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.ProfileKeyEdit
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncScope
import org.krost.unidrive.sync.editProfileKey
import org.krost.unidrive.sync.escapeTomlValue
import org.krost.unidrive.sync.isValidProfileName
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable

/**
 * A refusal of a profile command, machine-readable: [token] is the stable identifier a front-end
 * switches on, [message] is for a human. Neither ever carries a secret.
 */
internal class ProfileCommandError(
    val token: String,
    message: String,
) : Exception(message)

/**
 * The profile keys `profile set` may change: the documented, non-secret ones. Everything else in a
 * profile section is either fixed at creation (`type`, `mode`), moves data (`sync_root`) or is a
 * credential, which never travels through an argument list. The registry is the single list; a
 * key that is not here is refused by name.
 */
internal object ProfileSettings {
    const val LABEL = "label"
    const val SYNC_PATH = "sync_path"
    const val HYDRATION_CACHE_MAX_BYTES = "hydration_cache_max_bytes"
    const val DAEMON_POLL_SECONDS = "daemon_poll_seconds"

    class Spec(
        val key: String,
        /** One line for the `settable` list of the JSON replies. */
        val description: String,
        /** Whether a running daemon has to be restarted for the change to count. */
        val needsDaemonRestart: Boolean,
        /** The TOML value literal for the raw argument; throws [ProfileCommandError] `invalid_value`. */
        val toToml: (profile: String, raw: String) -> String,
        /** The key's current value in the parsed config; JSON null when unset. */
        val read: (RawProvider) -> JsonElement,
    )

    val SPECS: List<Spec> =
        listOf(
            Spec(
                key = LABEL,
                description = "free display text (stored as typed; the profile name stays the identity)",
                needsDaemonRestart = false,
                toToml = { _, raw -> "\"${escapeTomlValue(raw)}\"" },
                read = { it.label?.let(::JsonPrimitive) ?: JsonNull },
            ),
            Spec(
                key = SYNC_PATH,
                description = "remote subtree(s) to sync: one absolute path, or a JSON array of them",
                needsDaemonRestart = true,
                toToml = { profile, raw -> syncPathToml(profile, raw) },
                read = { rp -> rp.sync_path?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull },
            ),
            Spec(
                key = HYDRATION_CACHE_MAX_BYTES,
                description = "hydration cache budget in bytes; 0 = unlimited",
                needsDaemonRestart = true,
                toToml = { _, raw -> nonNegative(HYDRATION_CACHE_MAX_BYTES, raw, Long.MAX_VALUE).toString() },
                read = { rp -> rp.hydration_cache_max_bytes?.let(::JsonPrimitive) ?: JsonNull },
            ),
            Spec(
                key = DAEMON_POLL_SECONDS,
                description = "seconds between cloud polls of the daemon; 0 = off",
                needsDaemonRestart = true,
                toToml = { _, raw -> nonNegative(DAEMON_POLL_SECONDS, raw, Int.MAX_VALUE.toLong()).toString() },
                read = { rp -> rp.daemon_poll_seconds?.let(::JsonPrimitive) ?: JsonNull },
            ),
        )

    private val byKey = SPECS.associateBy { it.key }

    /** Every key a profile section can carry, from the config model itself. */
    private val knownKeys: Set<String> =
        RawProvider.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) }.toSet() }

    /**
     * The spec for [key], or the refusal: `unknown_key` for a name the config does not have at all,
     * `key_not_settable` for one it has but this command will not touch.
     */
    fun specFor(key: String): Spec {
        byKey[key]?.let { return it }
        val settable = byKey.keys.joinToString(", ")
        if (key in knownKeys) {
            throw ProfileCommandError("key_not_settable", "'$key' cannot be changed with 'profile set'. Settable keys: $settable")
        }
        throw ProfileCommandError("unknown_key", "'$key' is not a profile key. Settable keys: $settable")
    }

    private fun nonNegative(
        key: String,
        raw: String,
        max: Long,
    ): Long {
        val n = raw.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
        if (n == null || n > max) {
            throw ProfileCommandError("invalid_value", "$key must be a whole number from 0 to $max, got '$raw'")
        }
        return n
    }

    private fun syncPathToml(
        profile: String,
        raw: String,
    ): String {
        val isList = raw.trimStart().startsWith("[")
        val entries =
            if (isList) {
                try {
                    Json.parseToJsonElement(raw).jsonArray.map {
                        val p = it.jsonPrimitive
                        require(p.isString)
                        p.content
                    }
                } catch (_: Exception) {
                    throw ProfileCommandError("invalid_value", "$SYNC_PATH: a list must be a JSON array of strings, e.g. [\"/a\",\"/b\"]")
                }
            } else {
                listOf(raw)
            }
        if (entries.isEmpty()) {
            throw ProfileCommandError("invalid_value", "$SYNC_PATH: the list is empty; use --unset to sync the whole drive")
        }
        try {
            SyncScope.fromConfig(entries, profile)
        } catch (e: IllegalArgumentException) {
            throw ProfileCommandError("invalid_value", e.message ?: "$SYNC_PATH is invalid")
        }
        val quoted = entries.map { "\"${escapeTomlValue(it)}\"" }
        return if (isList) quoted.joinToString(", ", "[", "]") else quoted.single()
    }
}

/** One compact JSON object on stdout: the shared reply shape of the machine-readable profile commands. */
internal fun printJson(obj: JsonObject) = println(obj.toString())

/** The `{"ok":false,"error":token,"message":...}` reply. */
internal fun errorJson(e: ProfileCommandError): JsonObject =
    buildJsonObject {
        put("ok", false)
        put("error", e.token)
        put("message", e.message ?: e.token)
    }

/** Writes [text] to [target] through a sibling temp file, so a reader never sees half a config. */
internal fun writeConfigAtomically(
    target: Path,
    text: String,
) {
    val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
    try {
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}

// -- profile set ---------------------------------------------------------------

@Command(
    name = "set",
    description = [
        "Change one documented key of a profile in config.toml (label, sync_path, hydration_cache_max_bytes, " +
            "daemon_poll_seconds). Credentials and fixed keys (type, mode, sync_root) are refused. " +
            "A running daemon does not reload the config: restart it.",
    ],
    mixinStandardHelpOptions = true,
)
class ProfileSetCommand : Callable<Int> {
    @ParentCommand
    lateinit var profileCmd: ProfileCommand

    @Parameters(index = "0", description = ["Profile name (the immutable id)"])
    lateinit var name: String

    @Parameters(index = "1", description = ["Key to change"])
    lateinit var key: String

    @Parameters(index = "2", arity = "0..1", description = ["New value (omit with --unset)"])
    var value: String? = null

    @Option(names = ["--unset"], description = ["Remove the key instead of setting it"])
    var unset: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        try {
            val result = applyChange()
            if (json) {
                printJson(result)
            } else {
                println("Profile '$name': ${if (unset) "unset" else "set"} $key. ${result.getValue("message").jsonPrimitive.content}")
            }
            0
        } catch (e: ProfileCommandError) {
            if (json) printJson(errorJson(e)) else System.err.println("Error: ${e.message}")
            1
        }

    private fun applyChange(): JsonObject {
        val configPath = profileCmd.parent.configBaseDir().resolve("config.toml")
        val spec = ProfileSettings.specFor(key)
        if (!isValidProfileName(name)) {
            throw ProfileCommandError("invalid_profile_name", "'$name' is not a valid profile name")
        }
        if (!unset && value == null) {
            throw ProfileCommandError("missing_value", "a value is required for $key (or pass --unset)")
        }
        if (unset && value != null) {
            throw ProfileCommandError("invalid_value", "--unset takes no value")
        }
        if (!Files.exists(configPath)) {
            throw ProfileCommandError("config_not_found", "no config.toml in ${configPath.parent}")
        }
        val text = Files.readString(configPath)
        val raw =
            try {
                SyncConfig.parseRaw(text, configPath.toString())
            } catch (_: Exception) {
                // The parser's message can quote the offending config line, which may hold a credential.
                throw ProfileCommandError("config_invalid", "config.toml could not be parsed")
            }
        val current = raw.providers[name] ?: throw ProfileCommandError("profile_not_found", "profile '$name' is not configured")

        val line = value?.let { "$key = ${spec.toToml(name, it)}" }
        val edited =
            when (val r = editProfileKey(text, name, key, line)) {
                is ProfileKeyEdit.Edited -> r.text
                ProfileKeyEdit.NoSuchProfile -> throw ProfileCommandError("profile_not_found", "profile '$name' is not configured")
                ProfileKeyEdit.UnsupportedLayout ->
                    throw ProfileCommandError("unsupported_layout", "$key spans several lines in config.toml; edit it by hand")
            }
        // The edit must leave a config that still parses and reads back as asked.
        val reread =
            try {
                SyncConfig.parseRaw(edited, configPath.toString()).providers[name]
            } catch (_: Exception) {
                null
            } ?: throw ProfileCommandError("invalid_value", "the value for $key does not produce a valid config")
        try {
            writeConfigAtomically(configPath, edited)
        } catch (e: IOException) {
            throw ProfileCommandError("write_failed", "config.toml could not be written: ${e.javaClass.simpleName}")
        }

        val restart = spec.needsDaemonRestart
        return buildJsonObject {
            put("ok", true)
            put("profile", name)
            put("key", key)
            put("value", spec.read(reread))
            put("previous", spec.read(current))
            put("restart_required", restart)
            put(
                "message",
                if (restart) {
                    "Restart the daemon for the change to take effect (the config is not reloaded live)."
                } else {
                    "Saved. This key is display-only; no daemon restart is needed."
                },
            )
        }
    }
}
