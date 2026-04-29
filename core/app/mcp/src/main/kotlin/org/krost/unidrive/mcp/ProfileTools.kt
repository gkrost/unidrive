package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.escapeTomlValue
import org.krost.unidrive.sync.generateProfileToml
import org.krost.unidrive.sync.isProfileAuthenticated
import org.krost.unidrive.sync.isValidProfileName
import org.krost.unidrive.sync.removeProfileSection
import org.krost.unidrive.sync.updateProfileKey
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * UD-216 — MCP parity with the CLI `profile` family.
 *
 * Four tools (`_list`, `_add`, `_remove`, `_set`) reuse the pure helpers
 * exported from [org.krost.unidrive.cli.ProfileCommand] — generateProfileToml,
 * removeProfileSection, isValidProfileName, escapeTomlValue — so the TOML
 * grammar the MCP writes is byte-identical to what `unidrive profile add`
 * produces on the CLI. If the CLI's serialisation ever changes, both paths
 * update in lock-step.
 */

val profileListTool =
    ToolDef(
        name = "unidrive_profile_list",
        description = "List all configured profiles with type, sync root, and auth status. Read-only.",
        inputSchema = objectSchema(),
        handler = ::handleProfileList,
    )

val profileAddTool =
    ToolDef(
        name = "unidrive_profile_add",
        description =
            "Add a new profile to config.toml. Args: name (TOML bare key), type (e.g. 'onedrive', " +
                "'localfs', 's3'), syncRoot, options (provider-specific map: bucket, region, access_key_id, " +
                "secret_access_key, host, port, user, remote_path, identity, password, url, client_id, " +
                "client_secret, authority_url, root_path, rclone_remote, rclone_path, rclone_binary, rclone_config).",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("name", stringProp("Profile name (TOML bare key: letters, digits, hyphens, underscores)."))
                        put("type", stringProp("Provider type."))
                        put("syncRoot", stringProp("Local sync root directory."))
                        put(
                            "options",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "description",
                                    "Provider-specific credentials / settings as a JSON object of string values.",
                                )
                            },
                        )
                    },
                required = listOf("name", "type", "syncRoot"),
            ),
        handler = ::handleProfileAdd,
    )

val profileRemoveTool =
    ToolDef(
        name = "unidrive_profile_remove",
        description =
            "Remove a profile from config.toml. REQUIRES confirm=true. Pass keepData=true to keep " +
                "the profile's directory (state.db, tokens).",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("name", stringProp("Profile name to remove."))
                        put("confirm", booleanProp("Must be true — safety rail for a destructive op."))
                        put("keepData", booleanProp("If true, keep the profile's data directory (default: false)."))
                    },
                required = listOf("name", "confirm"),
            ),
        handler = ::handleProfileRemove,
    )

val profileSetTool =
    ToolDef(
        name = "unidrive_profile_set",
        description =
            "Edit a single key in a profile's config.toml section. Supported keys: syncRoot, " +
                "pollInterval, maxBandwidthKbps, include_shared, webhook, webhook_port, webhook_url, " +
                "trust_all_certs, fast_bootstrap, and the credential keys accepted by profile_add. " +
                "Unknown keys are rejected — no silent writes.",
        inputSchema =
            objectSchema(
                properties =
                    buildJsonObject {
                        put("name", stringProp("Profile name."))
                        put("key", stringProp("Config key to update."))
                        put("value", stringProp("New value (string, boolean, or integer encoded as string)."))
                    },
                required = listOf("name", "key", "value"),
            ),
        handler = ::handleProfileSet,
    )

// ── profile_list ───────────────────────────────────────────────────────────

private fun handleProfileList(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val configFile = ctx.configDir.resolve("config.toml")
    val raw =
        if (Files.exists(configFile)) {
            SyncConfig.parseRaw(Files.readString(configFile))
        } else {
            SyncConfig.parseRaw("[general]\n")
        }

    val profiles =
        raw.providers.entries.sortedBy { it.key }.map { (name, rp) ->
            val type = rp.type ?: name
            val syncRoot = rp.sync_root ?: rp.root_path ?: SyncConfig.defaultSyncRoot(type).toString()
            val authed =
                try {
                    isProfileAuthenticated(type, name, rp, ctx.configDir)
                } catch (_: Exception) {
                    false
                }
            buildJsonObject {
                put("name", name)
                put("type", type)
                put("syncRoot", syncRoot)
                put("authenticated", authed)
            }
        }

    return buildToolResult(JsonArray(profiles).toString())
}

// ── profile_add ────────────────────────────────────────────────────────────

private val ADD_ALLOWED_OPTION_KEYS =
    setOf(
        "bucket",
        "region",
        "endpoint",
        "access_key_id",
        "secret_access_key",
        "host",
        "port",
        "user",
        "remote_path",
        "identity",
        "password",
        "url",
        "client_id",
        "client_secret",
        "authority_url",
        "root_path",
        "rclone_remote",
        "rclone_path",
        "rclone_binary",
        "rclone_config",
        "trust_all_certs",
        "include_shared",
        "webhook",
        "webhook_port",
        "webhook_url",
        "fast_bootstrap",
        "xtra_encryption",
    )

private fun handleProfileAdd(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val name =
        args["name"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'name' argument", isError = true)
    val type =
        args["type"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'type' argument", isError = true)
    val syncRoot =
        args["syncRoot"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'syncRoot' argument", isError = true)

    if (!isValidProfileName(name)) {
        return buildToolResult(
            "Invalid profile name '$name'. Use only letters, digits, hyphens, and underscores.",
            isError = true,
        )
    }
    if (type !in ProviderRegistry.knownTypes) {
        return buildToolResult(
            "Unknown provider type '$type'. Supported: ${ProviderRegistry.knownTypes.sorted().joinToString(", ")}.",
            isError = true,
        )
    }

    val options = args["options"]?.jsonObject
    val creds = mutableMapOf<String, String>()
    if (options != null) {
        for ((k, v) in options) {
            if (k !in ADD_ALLOWED_OPTION_KEYS) {
                return buildToolResult(
                    "Unknown option '$k'. Allowed: ${ADD_ALLOWED_OPTION_KEYS.sorted().joinToString(", ")}.",
                    isError = true,
                )
            }
            creds[k] = v.jsonPrimitive.content
        }
    }

    val configFile = ctx.configDir.resolve("config.toml")
    val existing =
        if (Files.exists(configFile)) {
            SyncConfig.parseRaw(Files.readString(configFile))
        } else {
            SyncConfig.parseRaw("[general]\n")
        }

    if (name in existing.providers) {
        return buildToolResult("Profile '$name' already exists.", isError = true)
    }

    // Build an updated RawSyncConfig for duplicate-sync-root detection. We reuse
    // the same helper the CLI wizard uses to keep the duplicate check logic in
    // one place.
    val dupCheck =
        SyncConfig.detectDuplicateSyncRoots(
            existing.copy(
                providers = existing.providers + (name to RawProvider(type = type, sync_root = syncRoot)),
            ),
        )
    if (dupCheck != null) {
        return buildToolResult(dupCheck, isError = true)
    }

    val toml = generateProfileToml(type, name, syncRoot, creds)
    if (!Files.exists(configFile)) {
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, "[general]\n\n")
    }
    Files.writeString(configFile, toml, StandardOpenOption.APPEND)

    return buildToolResult(
        buildJsonObject {
            put("name", name)
            put("type", type)
            put("syncRoot", syncRoot)
            put("configFile", configFile.toString())
        }.toString(),
    )
}

// ── profile_remove ─────────────────────────────────────────────────────────

private fun handleProfileRemove(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val confirm = args["confirm"]?.jsonPrimitive?.booleanOrNull ?: false
    if (!confirm) {
        return buildToolResult(
            "unidrive_profile_remove requires confirm=true (destructive: removes profile section and optionally its data).",
            isError = true,
        )
    }
    val name =
        args["name"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'name' argument", isError = true)
    val keepData = args["keepData"]?.jsonPrimitive?.booleanOrNull ?: false

    val configFile = ctx.configDir.resolve("config.toml")
    if (!Files.exists(configFile)) {
        return buildToolResult("No config.toml found at $configFile.", isError = true)
    }

    val content = Files.readString(configFile)
    val raw = SyncConfig.parseRaw(content)
    if (name !in raw.providers) {
        return buildToolResult(
            "Profile '$name' not found. Configured: ${raw.providers.keys.joinToString(", ")}.",
            isError = true,
        )
    }

    val newContent = removeProfileSection(content.lines(), name)
    Files.writeString(configFile, newContent.joinToString("\n"))

    val removed = mutableListOf<String>()
    if (!keepData) {
        val profileDir = ctx.configDir.resolve(name)
        if (Files.isDirectory(profileDir)) {
            Files
                .walk(profileDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
            removed.add(profileDir.toString())
        }
    }

    return buildToolResult(
        buildJsonObject {
            put("name", name)
            put("keepData", keepData)
            putJsonArray("removed") { removed.forEach { add(it) } }
        }.toString(),
    )
}

// ── profile_set ────────────────────────────────────────────────────────────

/**
 * Keys we know how to write into `[providers.<name>]`. Split into three
 * categories by type because TOML is strongly-typed and we don't want to
 * quote an integer. Unknown keys are rejected outright — the whole point of
 * this tool is to stop an LLM from writing garbage.
 */
private val STRING_KEYS =
    setOf(
        "syncRoot",
        "sync_root",
        "root_path",
        "remote_path",
        "bucket",
        "region",
        "endpoint",
        "access_key_id",
        "secret_access_key",
        "host",
        "user",
        "identity",
        "password",
        "url",
        "client_id",
        "client_secret",
        "authority_url",
        "rclone_remote",
        "rclone_path",
        "rclone_binary",
        "rclone_config",
        "webhook_url",
    )
private val INT_KEYS =
    setOf("pollInterval", "poll_interval", "maxBandwidthKbps", "max_bandwidth_kbps", "port", "webhook_port")
private val BOOL_KEYS =
    setOf(
        "trust_all_certs",
        "include_shared",
        "webhook",
        "fast_bootstrap",
        "xtra_encryption",
    )

private fun canonicalKey(key: String): String =
    when (key) {
        "syncRoot" -> "sync_root"
        "pollInterval" -> "poll_interval"
        "maxBandwidthKbps" -> "max_bandwidth_kbps"
        else -> key
    }

private fun handleProfileSet(
    args: JsonObject,
    ctx: ProfileContext,
): JsonElement {
    val name =
        args["name"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'name' argument", isError = true)
    val rawKey =
        args["key"]?.jsonPrimitive?.content
            ?: return buildToolResult("Missing 'key' argument", isError = true)
    val valueElem =
        args["value"]
            ?: return buildToolResult("Missing 'value' argument", isError = true)

    val allKnown = STRING_KEYS + INT_KEYS + BOOL_KEYS
    if (rawKey !in allKnown) {
        return buildToolResult(
            "Unknown config key '$rawKey'. Allowed: ${allKnown.sorted().joinToString(", ")}.",
            isError = true,
        )
    }

    val tomlKey = canonicalKey(rawKey)
    val valueStr = valueElem.jsonPrimitive.content

    val tomlLine: String =
        when (rawKey) {
            in INT_KEYS -> {
                val n =
                    valueStr.toIntOrNull()
                        ?: return buildToolResult("Value for '$rawKey' must be an integer, got '$valueStr'.", isError = true)
                "$tomlKey = $n"
            }
            in BOOL_KEYS -> {
                val b =
                    when (valueStr.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else ->
                            return buildToolResult(
                                "Value for '$rawKey' must be 'true' or 'false', got '$valueStr'.",
                                isError = true,
                            )
                    }
                "$tomlKey = $b"
            }
            else -> """$tomlKey = "${escapeTomlValue(valueStr)}""""
        }

    val configFile = ctx.configDir.resolve("config.toml")
    if (!Files.exists(configFile)) {
        return buildToolResult("No config.toml found at $configFile.", isError = true)
    }

    val content = Files.readString(configFile)
    val raw = SyncConfig.parseRaw(content)
    if (name !in raw.providers) {
        return buildToolResult(
            "Profile '$name' not found. Configured: ${raw.providers.keys.joinToString(", ")}.",
            isError = true,
        )
    }

    val updated = updateProfileKey(content.lines(), name, tomlKey, tomlLine)
    if (updated == null) {
        return buildToolResult("Could not locate [providers.$name] section in config.toml.", isError = true)
    }
    Files.writeString(configFile, updated.joinToString("\n"))

    return buildToolResult(
        buildJsonObject {
            put("name", name)
            put("key", tomlKey)
            put("value", valueStr)
        }.toString(),
    )
}
