package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.escapeTomlValue
import org.krost.unidrive.sync.generateProfileToml
import org.krost.unidrive.sync.isValidProfileName
import org.krost.unidrive.sync.setDefaultProfile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** What `profile add --type ...` was asked to create. */
internal class NewProfile(
    val type: String,
    val name: String?,
    val mode: String?,
    val syncRoot: String?,
    val label: String?,
    /** `key=value` strings for the provider's non-secret prompts. */
    val options: List<String>,
    val makeDefault: Boolean,
)

/**
 * The default sync root the wizard suggests for a mirror profile of [type]: the canonical one,
 * except when that is one of the official Internxt Desktop client's folders, which the owner
 * reserves for that client.
 */
internal fun mirrorSyncRootDefault(type: String): String {
    val computed = SyncConfig.defaultSyncRoot(type)
    return if (isOfficialInternxtClientFolder(computed.fileName.toString())) {
        computed.parent.resolve("InternxtSync").toString()
    } else {
        computed.toString()
    }
}

/**
 * Non-interactive profile creation, the shared body behind `profile add --type ... --json`. No
 * console, no prompt, no daemon. Credentials never come from the argument list: only the provider's
 * non-secret prompts can be given (`--option key=value`); a secret prompt is refused by name and the
 * secret is entered through the provider's own flow (`auth begin`/`auth login`, or the wizard).
 */
internal object ProfileCreator {
    fun create(
        configDir: Path,
        req: NewProfile,
    ): JsonObject {
        val configPath = configDir.resolve("config.toml")
        val type = req.type
        val factory =
            LifecycleHooks.factoryFor(type)
                ?: throw LifecycleError(
                    "unknown_type",
                    "'$type' is not a provider type. Known: ${SyncConfig.KNOWN_TYPES.sorted().joinToString(", ")}",
                )
        val name = req.name?.takeIf { it.isNotBlank() } ?: type
        if (!isValidProfileName(name)) {
            throw LifecycleError("invalid_profile_name", "'$name' is invalid: use only letters, digits, hyphens and underscores")
        }
        val mode =
            when (req.mode?.trim()?.lowercase()) {
                null, "" -> throw LifecycleError("missing_mode", "--mode mount|mirror is required; there is no default")
                ProfileMode.MOUNT.wireName -> ProfileMode.MOUNT
                ProfileMode.MIRROR.wireName -> ProfileMode.MIRROR
                else -> throw LifecycleError("invalid_mode", "--mode must be 'mount' or 'mirror'")
            }

        val raw =
            try {
                if (Files.exists(configPath)) {
                    SyncConfig.parseRaw(Files.readString(configPath), configPath.toString())
                } else {
                    SyncConfig.parseRaw("[general]\n")
                }
            } catch (_: Exception) {
                // The parser's message can quote the offending config line, which may hold a credential.
                throw LifecycleError("config_invalid", "config.toml could not be parsed")
            }
        if (name in raw.providers) {
            throw LifecycleError("profile_exists", "profile '$name' already exists")
        }

        val warnings = mutableListOf<String>()
        val syncRoot =
            when (mode) {
                ProfileMode.MOUNT -> {
                    if (req.syncRoot != null) {
                        throw LifecycleError("sync_root_not_allowed", "a mount profile has no sync root; it works on the mount root")
                    }
                    configDir.resolve(name).resolve("sync-root").toString()
                }
                ProfileMode.MIRROR -> {
                    val root = req.syncRoot?.takeIf { it.isNotBlank() } ?: mirrorSyncRootDefault(type)
                    val leaf = runCatching { Path.of(root).fileName?.toString() }.getOrNull()
                    if (leaf != null && isOfficialInternxtClientFolder(leaf)) {
                        warnings += "'$root' is the official Internxt Desktop client's folder; the two clients will fight over it"
                    }
                    root
                }
            }
        val conflict =
            SyncConfig.detectRootIsolationConflicts(
                raw.copy(providers = raw.providers + (name to RawProvider(type = type, mode = mode.wireName, sync_root = syncRoot))),
            )
        if (conflict != null) throw LifecycleError("sync_root_conflict", conflict)

        val creds = credentialsFromOptions(factory.credentialPrompts(), req.options)

        val profileDir = configDir.resolve(name)
        val createdDir = !Files.exists(profileDir)
        try {
            Files.createDirectories(profileDir)
            StoragePermissions.restrictProfile(profileDir)
        } catch (e: Exception) {
            if (createdDir) profileDir.toFile().deleteRecursively()
            throw LifecycleError("permissions_failed", "the profile folder could not be made owner-only: ${e.javaClass.simpleName}")
        }
        try {
            appendProfile(configPath, generateProfileToml(type, name, syncRoot, creds, mode), req.label, if (req.makeDefault) name else null)
        } catch (e: IOException) {
            if (createdDir) profileDir.toFile().deleteRecursively()
            throw LifecycleError("write_failed", "config.toml could not be written: ${e.javaClass.simpleName}")
        }

        val defaultProfile =
            try {
                SyncConfig.parseRaw(Files.readString(configPath), configPath.toString()).general.default_profile
            } catch (_: Exception) {
                null
            }
        return buildJsonObject {
            put("ok", true)
            put("profile", name)
            put("type", type)
            put("mode", mode.wireName)
            put("label", req.label)
            put("sync_root", syncRoot)
            put("default_profile", defaultProfile?.takeIf { it.isNotBlank() })
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
            put(
                "next",
                when {
                    factory.supportsInteractiveAuth() -> "auth_begin"
                    type == "internxt" -> "auth_login"
                    else -> "none"
                },
            )
        }
    }

    /**
     * The non-secret prompt values: a secret prompt is refused whether it was supplied (it would sit in
     * the argument list) or required (it cannot be satisfied here); an option that is no prompt of the
     * type is unknown; a required prompt without a value or default is missing. The refusals name keys,
     * never values.
     */
    private fun credentialsFromOptions(
        prompts: List<org.krost.unidrive.PromptSpec>,
        options: List<String>,
    ): Map<String, String> {
        val given = linkedMapOf<String, String>()
        for (opt in options) {
            val eq = opt.indexOf('=')
            if (eq <= 0) throw LifecycleError("invalid_option", "--option takes key=value")
            val key = opt.substring(0, eq)
            if (key in given) throw LifecycleError("duplicate_option", "option '$key' was given twice")
            given[key] = opt.substring(eq + 1)
        }
        val byKey = prompts.associateBy { it.key }
        for (key in given.keys) {
            val prompt = byKey[key] ?: throw LifecycleError("unknown_option", "'$key' is not a setting of this provider type")
            if (prompt.isMasked) {
                throw LifecycleError("secret_not_allowed_in_argv", "'$key' is a secret and cannot be passed on the command line")
            }
        }
        val creds = linkedMapOf<String, String>()
        for (prompt in prompts) {
            if (prompt.isMasked) {
                if (prompt.required) {
                    throw LifecycleError(
                        "secret_prompt_unsupported",
                        "this provider type needs a secret ('${prompt.key}'); create the profile with the interactive wizard",
                    )
                }
                continue
            }
            val value = given[prompt.key]?.takeIf { it.isNotBlank() } ?: prompt.default
            if (value == null || value.isBlank()) {
                if (prompt.required) throw LifecycleError("missing_option", "--option ${prompt.key}=<value> is required")
                continue
            }
            creds[prompt.key] = value
        }
        return creds
    }

    private fun appendProfile(
        configPath: Path,
        toml: String,
        label: String?,
        makeDefault: String?,
    ) {
        if (!Files.exists(configPath)) {
            Files.createDirectories(configPath.parent)
            Files.writeString(configPath, "[general]\n\n")
        }
        val section =
            if (label != null) toml.trimEnd('\n') + "\nlabel = \"${escapeTomlValue(label)}\"\n" else toml
        Files.writeString(configPath, section, StandardOpenOption.APPEND)
        if (makeDefault != null) {
            Files.writeString(configPath, setDefaultProfile(Files.readString(configPath), makeDefault))
        }
    }
}
