package org.krost.unidrive.sync

import java.nio.file.Path

/**
 * UD-216 — pure TOML helpers that both the CLI ProfileCommand and the MCP
 * profile tools share. Moved here so neither caller has to depend on the other.
 *
 * Design notes
 *  - Kept line-oriented (vs. a round-trip through ktoml) so the operation
 *    preserves user formatting, comments, and ordering. ktoml would rewrite
 *    the whole file and drop comments, which is hostile to a user who
 *    hand-edits config.toml.
 *  - Every string write funnels through [escapeTomlValue] so a path with a
 *    backslash or quote cannot break the TOML grammar.
 */

/** Validate profile name: TOML bare key (letters, digits, hyphens, underscores). */
fun isValidProfileName(name: String): Boolean = name.isNotBlank() && name.matches(Regex("[a-zA-Z0-9_-]+"))

/** Escape characters that are special inside TOML basic strings. */
fun escapeTomlValue(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

/**
 * Generate a TOML section string for a new profile.
 * Returns the full `[providers.<name>]` block ready to append.
 */
fun generateProfileToml(
    type: String,
    name: String,
    syncRoot: String,
    credentials: Map<String, String>,
): String =
    buildString {
        append("\n[providers.$name]\n")
        append("type = \"${escapeTomlValue(type)}\"\n")
        append("sync_root = \"${escapeTomlValue(syncRoot)}\"\n")
        for ((key, value) in credentials) {
            if (value.isNotBlank()) {
                append("$key = \"${escapeTomlValue(value)}\"\n")
            }
        }
    }

/**
 * Remove a `[providers.<name>]` section from config lines.
 * Returns the remaining lines with the section excised.
 */
fun removeProfileSection(
    lines: List<String>,
    name: String,
): List<String> {
    val header = "[providers.$name]"
    val result = mutableListOf<String>()
    var skipping = false

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed == header) {
            skipping = true
            // Remove preceding blank line if it exists
            while (result.isNotEmpty() && result.last().isBlank()) {
                result.removeLast()
            }
            continue
        }
        if (skipping) {
            // Next top-level section ends the skip
            if (trimmed.startsWith("[") && !trimmed.startsWith("[providers.$name.")) {
                skipping = false
                result.add(line)
            }
            continue
        }
        result.add(line)
    }
    return result
}

/**
 * UD-216: replace `<key> = ...` inside `[providers.<name>]` if present,
 * otherwise append the line to the end of that section. Returns null if the
 * section header is not found so the caller can surface a clean error.
 *
 * Kept line-based for the same reason as [removeProfileSection] — we want to
 * preserve surrounding comments and formatting.
 */
fun updateProfileKey(
    lines: List<String>,
    profileName: String,
    key: String,
    replacementLine: String,
): List<String>? {
    val header = "[providers.$profileName]"
    val out = mutableListOf<String>()
    var inSection = false
    var headerSeen = false
    var insertBeforeIdx = -1
    var replaced = false

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed == header) {
            inSection = true
            headerSeen = true
            out.add(line)
            insertBeforeIdx = out.size
            continue
        }
        if (inSection && trimmed.startsWith("[") && !trimmed.startsWith("[providers.$profileName.")) {
            // New section starts — anchor the insertion point at the
            // previous line (just before the new section header).
            inSection = false
        }
        val isOurKey =
            inSection &&
                (
                    trimmed.startsWith("$key ") ||
                        trimmed.startsWith("$key=") ||
                        trimmed.startsWith("$key\t") ||
                        trimmed == key
                )
        if (isOurKey && !replaced) {
            out.add(replacementLine)
            replaced = true
        } else {
            out.add(line)
        }
        if (inSection) {
            insertBeforeIdx = out.size
        }
    }

    if (!headerSeen) return null
    if (!replaced) {
        out.add(insertBeforeIdx.coerceAtLeast(0), replacementLine)
    }
    return out
}

/**
 * Check auth status for a profile without constructing a full ProfileContext.
 * Delegates to the SPI ProviderFactory.isAuthenticated() when available; on an
 * unknown provider type or any exception, returns false.
 *
 * Moved here from CLI so MCP can use it without dragging :app:cli in.
 */
fun isProfileAuthenticated(
    type: String,
    profileName: String,
    rp: RawProvider,
    configDir: Path,
): Boolean {
    val profileDir = configDir.resolve(profileName)
    return try {
        val factory =
            org.krost.unidrive.ProviderRegistry
                .get(type)
        if (factory != null) {
            val properties =
                mapOf(
                    "bucket" to rp.bucket,
                    "access_key_id" to rp.access_key_id,
                    "secret_access_key" to rp.secret_access_key,
                    "host" to rp.host,
                    "user" to rp.user,
                    "password" to rp.password,
                    "url" to rp.url,
                    "client_id" to rp.client_id,
                    "client_secret" to rp.client_secret,
                    "rclone_remote" to rp.rclone_remote,
                )
            factory.isAuthenticated(properties, profileDir)
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }
}
