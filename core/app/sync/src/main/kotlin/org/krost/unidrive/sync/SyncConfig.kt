package org.krost.unidrive.sync

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.model.ConflictPolicy
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// ── ktoml-serializable intermediates ─────────────────────────────────────────

private val ktoml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))
private val configLog = LoggerFactory.getLogger("org.krost.unidrive.sync.SyncConfig")

/** Top-level so the @Serializable-generated companion is in scope at the call site. */
internal fun decodeRawSyncConfig(content: String): RawSyncConfig = ktoml.decodeFromString(RawSyncConfig.serializer(), content)

/**
 * UD-282: a TOML section header in `config.toml` that doesn't match the
 * schema (`[general]` or `[providers.X]`). Surfaced via [validateTomlSections]
 * so users get a loud signal when they typo'd `[profiles.X]` instead of the
 * silent ktoml drop.
 */
data class UnknownTomlSection(
    val section: String,
    val lineNumber: Int,
    /** Suggested correction from the common-typos table or Levenshtein search. */
    val suggestion: String?,
)

private val knownTopLevelSections = setOf("general", "providers")

/**
 * Hand-typed common section-header typos. Higher-precision than Levenshtein
 * for the patterns we've seen in real config.toml files.
 */
private val commonSectionTypos =
    mapOf(
        "profiles" to "providers",
        "profile" to "providers",
        "provider" to "providers",
        "general_settings" to "general",
        "globals" to "general",
        "global" to "general",
        "settings" to "general",
    )

/**
 * UD-282: scan a config.toml content string for top-level section headers
 * that don't match the schema. ktoml's [TomlInputConfig.ignoreUnknownNames]
 * is `true` by default for forward-compat, but it also silently drops
 * `[profiles.X]` (the most common user typo for `[providers.X]`) without
 * any signal — the user sees `Unknown profile: X` minutes later from the
 * resolver, not at config-load time.
 *
 * Returns a list of unknown sections with line numbers and "did you mean"
 * suggestions. Empty list = clean config.
 *
 * Pure function — no logging, no side effects. The caller ([parseRaw])
 * decides whether to log a warning or throw under strict mode.
 */
internal fun validateTomlSections(content: String): List<UnknownTomlSection> {
    val unknowns = mutableListOf<UnknownTomlSection>()
    val sectionRegex = Regex("""^\s*\[([^\]]+)]\s*(#.*)?$""")
    content.lines().forEachIndexed { idx, line ->
        // Skip comments without breaking on inline `#` after the header.
        if (line.trimStart().startsWith("#")) return@forEachIndexed
        val m = sectionRegex.matchEntire(line) ?: return@forEachIndexed
        val section = m.groupValues[1].trim()
        // Tables-of-tables like `[providers.foo.pin_patterns]` are nested
        // under the top-level (`providers`), so we only check the prefix.
        val topLevel = section.substringBefore('.')
        if (topLevel !in knownTopLevelSections) {
            unknowns.add(
                UnknownTomlSection(
                    section = section,
                    lineNumber = idx + 1,
                    suggestion = suggestKnownSection(section, topLevel),
                ),
            )
        }
    }
    return unknowns
}

private fun suggestKnownSection(
    fullSection: String,
    topLevel: String,
): String? {
    // Common-typo lookup first — beats Levenshtein for the patterns we've seen.
    commonSectionTypos[topLevel.lowercase()]?.let { fixed ->
        // Preserve the trailing `.X.Y` segments (e.g. `profiles.foo.pin_patterns`
        // → `providers.foo.pin_patterns`).
        val rest = fullSection.substringAfter('.', "")
        return if (rest.isEmpty()) fixed else "$fixed.$rest"
    }
    // Fallback: Levenshtein distance ≤ 3 against the known top-level set.
    val best =
        knownTopLevelSections.minByOrNull { levenshteinDistance(topLevel.lowercase(), it.lowercase()) }
            ?: return null
    val distance = levenshteinDistance(topLevel.lowercase(), best.lowercase())
    if (distance > 3) return null
    val rest = fullSection.substringAfter('.', "")
    return if (rest.isEmpty()) best else "$best.$rest"
}

/** Iterative Levenshtein distance — small input sizes; no need for memo optimization. */
private fun levenshteinDistance(
    a: String,
    b: String,
): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
}

/**
 * UD-282: opt-in strict mode that turns every unknown TOML section into a
 * fatal `IllegalArgumentException`. Useful for CI and for users who'd
 * rather see config typos halt the run than surface as `Unknown profile:`
 * minutes later.
 *
 * Reads `UNIDRIVE_STRICT_CONFIG` env var. Truthy values: `1`, `true`,
 * `yes` (case-insensitive).
 */
internal fun isStrictConfigMode(): Boolean = System.getenv("UNIDRIVE_STRICT_CONFIG")?.lowercase() in setOf("1", "true", "yes")

@Serializable
data class RawSyncConfig(
    val general: RawGeneral = RawGeneral(),
    val providers: Map<String, RawProvider> = emptyMap(),
)

@Serializable
data class RawGeneral(
    // UD-252: explicit opt-in to pin "the default profile" when invoked without -p.
    // Honoured by both the CLI and the MCP jar via [SyncConfig.resolveDefaultProfile].
    // When unset, both jars fall back to `ProviderRegistry.defaultProvider()`
    // (UD-012; "localfs" when discovered, else the first SPI-discovered factory).
    val default_profile: String? = null,
    val sync_root: String? = null,
    val poll_interval: Int? = null,
    val min_poll_interval: Int? = null,
    val max_poll_interval: Int? = null,
    val conflict_policy: String? = null,
    val log_file: String? = null,
    val max_bandwidth_kbps: Int? = null,
    val max_delete_percentage: Int? = null,
    // UD-265: two-axis reframing of the deletion safeguard. The existing
    // max_delete_percentage stays as-is (back-compat); these add an absolute
    // cap that defends against wide-blast on large inventories where any
    // sane percentage is still a catastrophe, and a per-subtree percentage
    // that catches "delete 100 % of one top-level" runs which are tiny
    // fractions of the whole drive (the 2026-05-16 incident shape: 405
    // deletes = 0.14 % of 280k entries, well under the old 50 % brake).
    val max_delete_absolute: Int? = null,
    val max_delete_per_subtree_percent: Int? = null,
    val sync_direction: String? = null,
    val client_location: String? = null,
    val client_network: String? = null,
    val exclude_patterns: List<String>? = null,
    val desktop_notifications: Boolean? = null,
    val verify_integrity: Boolean? = null,
    val use_trash: Boolean? = null,
    val trash_emulation: Boolean? = null,
    val trash_retention_days: Int? = null,
    val file_versioning: Boolean? = null,
    val max_versions: Int? = null,
    val version_retention_days: Int? = null,
    // Streaming reconciliation: process delta pages as they arrive instead
    // of accumulating the entire remote inventory before reconciling.
    // Per-page resolveSlice fires safe-now actions (downloads, uploads,
    // placeholder creates) immediately and defers deletion-bearing actions
    // to scan-end so the detectMissingAfterFullSync guard still gates them.
    //
    // Three-valued: true forces on, false forces off, null leaves it to
    // the migration default — on for new installs, off for upgrades, with
    // an auto-flip to on after the first successful streaming scan
    // (sync_state key `streaming_reconciliation_enabled`). CLI flag wins
    // over this key.
    val streaming_reconciliation: Boolean? = null,
)

@Serializable
data class RawProvider(
    // Profile metadata
    val type: String? = null,
    val sync_root: String? = null,
    val root_path: String? = null, // localfs alternative
    // Sync settings
    val pin_patterns: RawPinPatterns? = null,
    val conflict_overrides: Map<String, String>? = null,
    val exclude_patterns: List<String>? = null,
    // S3 credentials
    val bucket: String? = null,
    val region: String? = null,
    val endpoint: String? = null,
    val access_key_id: String? = null,
    val secret_access_key: String? = null,
    // SFTP credentials
    val host: String? = null,
    val port: Int? = null,
    val user: String? = null,
    val remote_path: String? = null,
    val identity: String? = null,
    val password: String? = null,
    // WebDAV credentials
    val url: String? = null,
    // WebDAV reuses `user` and `password` from above
    // HiDrive credentials
    val client_id: String? = null,
    val client_secret: String? = null,
    // OneDrive credentials
    val authority_url: String? = null,
    // OneDrive shared folders
    val include_shared: Boolean? = null,
    // OneDrive webhook
    val webhook: Boolean? = null,
    val webhook_port: Int? = null,
    val webhook_url: String? = null,
    // TLS
    val trust_all_certs: Boolean? = null,
    // Rclone
    val rclone_remote: String? = null,
    val rclone_path: String? = null,
    val rclone_binary: String? = null,
    val rclone_config: String? = null,
    // UD-223: skip first-sync enumeration by adopting the provider's current cursor.
    // OneDrive / Graph: `?token=latest`. Other providers: flag is a warn+no-op.
    // Persists in config.toml so a user who opts in at profile creation doesn't need
    // to pass `--fast-bootstrap` every time. CLI flag takes precedence.
    val fast_bootstrap: Boolean? = null,
    // Internxt destructive-overwrite guard. When set per profile, every
    // `replaceFile` first renames the prior cloud content to
    // `${plainName}.unidrive-prev-${utcStamp}` before creating the new
    // version. Opt-in recovery insurance; default off. Threaded into
    // InternxtConfig.keepOverwritten by InternxtProviderFactory.
    val keep_overwritten: Boolean? = null,
)

@Serializable
data class RawPinPatterns(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

// ── Public API ────────────────────────────────────────────────────────────────

enum class SyncDirection { BIDIRECTIONAL, UPLOAD, DOWNLOAD }

data class ProfileInfo(
    val name: String,
    val type: String,
    val syncRoot: Path,
    val rawProvider: RawProvider?,
    /**
     * True when this profile was discovered from a directory on disk that has
     * no matching `[providers.<name>]` section in config.toml. An orphan profile
     * has no declared type, credentials, or sync_root — treat it as read-only
     * diagnostic data. The profile dir may contain a state.db from a prior
     * `unidrive auth` run against a type-resolved name.
     */
    val isOrphan: Boolean = false,
)

data class SyncConfig(
    val syncRoot: Path,
    val pollInterval: Int,
    val minPollInterval: Int = 10,
    val maxPollInterval: Int = 300,
    val conflictPolicy: ConflictPolicy,
    val logFile: Path?,
    val maxBandwidthKbps: Int? = null,
    val maxDeletePercentage: Int = 50,
    // UD-265: defaults chosen to trip on the 2026-05-16 incident shape
    // (405 deletes of a never-locally-hydrated subtree) while staying out
    // of the way of routine sync activity (single-folder cleanups, small
    // local-rm batches).
    val maxDeleteAbsolute: Int = 50,
    val maxDeletePerSubtreePercent: Int = 80,
    val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
    val clientLocation: String? = null,
    val clientNetwork: String? = null,
    val globalExcludePatterns: List<String> = emptyList(),
    val desktopNotifications: Boolean = false,
    val verifyIntegrity: Boolean = false,
    val useTrash: Boolean = true,
    val trashEmulation: Boolean = false,
    val trashRetentionDays: Int = 30,
    val fileVersioning: Boolean = false,
    val maxVersions: Int = 5,
    val versionRetentionDays: Int = 90,
    // Raw TOML value of `[general] streaming_reconciliation`. Null when the
    // key is absent. Final resolution (CLI > TOML > sync_state sentinel >
    // hard default) happens in [resolveStreamingReconciliation] so the CLI
    // can pass the same constant set without re-implementing the precedence.
    val streamingReconciliation: Boolean? = null,
    private val providers: Map<String, ProviderConfig>,
) {
    fun providerPinIncludes(providerId: String): List<String> = providers[providerId]?.pinIncludes ?: emptyList()

    fun providerPinExcludes(providerId: String): List<String> = providers[providerId]?.pinExcludes ?: emptyList()

    fun providerConflictOverrides(providerId: String): Map<String, ConflictPolicy> = providers[providerId]?.conflictOverrides ?: emptyMap()

    fun providerExcludePatterns(providerId: String): List<String> = providers[providerId]?.excludePatterns ?: emptyList()

    fun effectiveExcludePatterns(providerId: String): List<String> =
        DEFAULT_EXCLUDE_PATTERNS + globalExcludePatterns + (providers[providerId]?.excludePatterns ?: emptyList())

    data class ProviderConfig(
        val pinIncludes: List<String> = emptyList(),
        val pinExcludes: List<String> = emptyList(),
        val conflictOverrides: Map<String, ConflictPolicy> = emptyMap(),
        val excludePatterns: List<String> = emptyList(),
    )

    companion object {
        /**
         * Patterns excluded from sync for every profile, before any user
         * (TOML global / per-provider / CLI --exclude) patterns. Consolidates
         * the unidrive-internal excludes (formerly hardcoded in SyncEngine)
         * with desktop/OS/editor junk that must never reach the cloud.
         * Matched by Reconciler.matchesGlob (handles **&#47;, *, ?, and
         * escapes other chars incl. dollar-sign).
         */
        val DEFAULT_EXCLUDE_PATTERNS: List<String> = listOf(
            // unidrive-internal
            "/.unidrive-trash/**",
            "/.unidrive-versions/**",
            // version-control metadata directories (whole subtree, at any depth).
            // The `**/.git/**` form matches both the directory itself (preVisit
            // SKIP_SUBTREE) and every nested object, so a checked-out repo under
            // the sync root never enters a plan (issue: .git uploaded in full).
            "**/.git/**",  // Git
            "**/.svn/**",  // Subversion
            "**/.hg/**",   // Mercurial
            "**/.bzr/**",  // Bazaar
            // desktop / OS / editor junk
            "**/.directory.lock", // KDE Dolphin
            "**/.DS_Store",       // macOS
            "**/._*",             // macOS AppleDouble
            "**/Thumbs.db",       // Windows
            "**/ehthumbs.db",     // Windows
            "**/desktop.ini",     // Windows
            "**/~\$*",            // MS Office lock files
            "**/*.part",          // partial downloads
            "**/*.crdownload",    // Chrome partial download
            "**/*.swp",           // vim swap
            "**/*.swx",           // vim swap
            "**/*.tmp",           // generic temp
            "**/*~",              // generic backup
        )

        private val home: String = System.getenv("HOME") ?: System.getProperty("user.home")

        /**
         * UD-407: canonical config-dir resolver, used by both the CLI and MCP
         * entry points so the same `unidrive` invocation lands on the same
         * config file regardless of how it was launched.
         *
         * Precedence (highest first):
         *  1. [explicitOverride] — the CLI's `-c/--config-dir` flag, when set.
         *  2. `UNIDRIVE_CONFIG_DIR` env var. Documented at [Main.kt:reportConfigMissing]
         *     as the workaround for MSIX-virtualised `%APPDATA%`; before
         *     UD-407 the CLI advertised but ignored it.
         *  3. `~/.config/unidrive/` if `config.toml` exists there. Lets users
         *     opt out of `%APPDATA%` per-machine without setting an env var,
         *     and converges Linux/Mac/Windows-MSIX users on the same default.
         *  4. `%APPDATA%\unidrive\` on Windows.
         *  5. `~/.config/unidrive/` as the cross-platform fallback.
         *
         * The MSIX angle: Claude Desktop and other MSIX-packaged hosts
         * redirect `%APPDATA%` reads/writes to a per-package overlay
         * (memory: project_msix_sandbox.md). A subprocess spawned from such
         * a host sees a different on-disk file at the same nominal path
         * than a native PowerShell does. Setting `UNIDRIVE_CONFIG_DIR` to a
         * non-`%APPDATA%` path (or letting `~/.config/unidrive/` win)
         * makes both views converge on the same file.
         */
        /**
         * Resolved effective value for streaming reconciliation, in
         * precedence order: CLI override > TOML key ([SyncConfig.streamingReconciliation])
         * > sync_state sentinel (`streaming_reconciliation_enabled`, written
         * by the engine after a successful streaming scan) > hard default
         * of `false`. The hard default is conservative — upgrades stay on
         * the established single-shot path until either the user explicitly
         * opts in (`--streaming-reconciliation=on` or TOML) or has at least
         * one successful streaming scan (after which the sentinel makes
         * subsequent runs default-on without re-opting).
         *
         * Takes raw inputs rather than a `SyncConfig` + DB pair so it stays
         * testable as a pure function.
         */
        fun resolveStreamingReconciliation(
            cliOverride: Boolean?,
            tomlValue: Boolean?,
            sentinel: Boolean?,
        ): Boolean = cliOverride ?: tomlValue ?: sentinel ?: false

        /** Sync-state key for the auto-flip sentinel. Written by [SyncEngine] after a successful streaming scan. */
        const val STREAMING_RECONCILIATION_SENTINEL_KEY: String = "streaming_reconciliation_enabled"

        fun defaultConfigDir(explicitOverride: Path? = null): Path =
            resolveConfigDir(
                explicitOverride = explicitOverride,
                envConfigDir = System.getenv("UNIDRIVE_CONFIG_DIR"),
                appData = System.getenv("APPDATA"),
                home = home,
                xdgConfigExists = { Files.exists(it) },
            )

        /**
         * UD-407: pure resolver, exposed for unit-testing the precedence
         * order without env-var mutation. The OS-bound [defaultConfigDir]
         * delegates here. See its doc-comment for the precedence rationale.
         */
        internal fun resolveConfigDir(
            explicitOverride: Path?,
            envConfigDir: String?,
            appData: String?,
            home: String,
            xdgConfigExists: (Path) -> Boolean,
        ): Path {
            if (explicitOverride != null) return explicitOverride
            if (envConfigDir != null) return Paths.get(envConfigDir)
            val xdg = Paths.get(home, ".config", "unidrive")
            if (xdgConfigExists(xdg.resolve("config.toml"))) return xdg
            return if (appData != null) Paths.get(appData, "unidrive") else xdg
        }

        fun defaultConfigPath(): Path = defaultConfigDir().resolve("config.toml")

        fun load(
            configFile: Path,
            providerId: String = org.krost.unidrive.ProviderRegistry.defaultProvider(),
        ): SyncConfig {
            if (!Files.exists(configFile)) return defaults(providerId)
            val content = Files.readString(configFile)
            return parse(content, providerId)
        }

        // The default sync-root directory name comes from the provider's own
        // metadata (`ProviderMetadata.syncRootDirName`), so a provider
        // self-declares any name that differs from the title-cased id. A
        // provider that doesn't set it — and any id with no discovered
        // metadata — defaults to a title-cased id (e.g. "sftp" → ~/Sftp).
        // OneDrive declares "OneDrive" to preserve existing users' ~/OneDrive
        // layout; without that, title-casing would resolve to ~/Onedrive.
        // This is the last provider-name dispatch site removed from SyncConfig.
        fun defaultSyncRoot(providerId: String): Path {
            val dirName =
                org.krost.unidrive.ProviderRegistry.getMetadata(providerId)?.syncRootDirName
                    ?: providerId.replaceFirstChar { it.uppercase() }
            return Paths.get(home, dirName)
        }

        val KNOWN_TYPES: Set<String> get() = org.krost.unidrive.ProviderRegistry.knownTypes

        private fun expandTilde(path: String): String = if (path.startsWith("~")) home + path.substring(1) else path

        fun resolveProfile(
            name: String,
            raw: RawSyncConfig,
        ): ProfileInfo {
            val section = raw.providers[name]
            if (section != null) {
                val type = section.type ?: name
                if (type !in KNOWN_TYPES) {
                    throw IllegalArgumentException(
                        "Unknown provider type '$type' in profile '$name'. Supported: ${org.krost.unidrive.ProviderRegistry.knownTypes.joinToString(
                            ", ",
                        )}",
                    )
                }
                val syncRoot =
                    section.sync_root?.let { Paths.get(expandTilde(it)) }
                        ?: raw.general.sync_root?.let { Paths.get(expandTilde(it)) }
                        ?: defaultSyncRoot(type)
                return ProfileInfo(name = name, type = type, syncRoot = syncRoot, rawProvider = section)
            } else if (name in KNOWN_TYPES) {
                val syncRoot =
                    raw.general.sync_root?.let { Paths.get(expandTilde(it)) }
                        ?: defaultSyncRoot(name)
                return ProfileInfo(name = name, type = name, syncRoot = syncRoot, rawProvider = null)
            } else {
                val configured = raw.providers.keys
                throw IllegalArgumentException(
                    buildString {
                        append("Unknown profile: $name\n")
                        if (configured.isNotEmpty()) append("Configured profiles: ${configured.joinToString(", ")}\n")
                        append("Supported provider types: ${KNOWN_TYPES.joinToString(", ")}")
                    },
                )
            }
        }

        fun defaults(providerId: String = org.krost.unidrive.ProviderRegistry.defaultProvider()) =
            SyncConfig(
                syncRoot = defaultSyncRoot(providerId),
                pollInterval = 60,
                minPollInterval = 10,
                maxPollInterval = 300,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                logFile = null,
                maxBandwidthKbps = null,
                maxDeletePercentage = 50,
                maxDeleteAbsolute = 50,
                maxDeletePerSubtreePercent = 80,
                syncDirection = SyncDirection.BIDIRECTIONAL,
                clientLocation = null,
                clientNetwork = null,
                globalExcludePatterns = emptyList(),
                desktopNotifications = false,
                verifyIntegrity = false,
                useTrash = true,
                trashEmulation = false,
                trashRetentionDays = 30,
                fileVersioning = false,
                maxVersions = 5,
                versionRetentionDays = 90,
                providers = emptyMap(),
            )

        fun parseRaw(content: String): RawSyncConfig {
            // UD-282: surface ignored TOML sections (most commonly the
            // `[profiles.X]` → `[providers.X]` typo) instead of letting
            // ktoml drop them silently.
            val unknowns = validateTomlSections(content)
            if (unknowns.isNotEmpty()) {
                val strict = isStrictConfigMode()
                unknowns.forEach { u ->
                    val msg =
                        buildString {
                            append("config.toml line ${u.lineNumber}: ignored unknown section '[${u.section}]'")
                            if (u.suggestion != null) {
                                append(" — did you mean '[${u.suggestion}]'?")
                            }
                        }
                    if (strict) {
                        throw IllegalArgumentException(
                            msg + " (UNIDRIVE_STRICT_CONFIG is set; remove the typo or unset the env var)",
                        )
                    } else {
                        configLog.warn(msg)
                    }
                }
            }
            return decodeRawSyncConfig(content)
        }

        /**
         * UD-252: the single source of truth for "which profile does unidrive use
         * when the user did not pass `-p`?".
         *
         * Both the CLI entry point (`core/app/cli/.../Main.kt`) and the MCP jar
         * (`core/app/mcp/.../Main.kt`) must call this function so they stay
         * byte-identical. Previously the MCP picked `raw.providers.keys.firstOrNull()`
         * (first-in-file, alphabetical under ktoml's map ordering) while the CLI
         * hardcoded `"onedrive"` via picocli's `defaultValue`. Users with multi-profile
         * configs saw the MCP report status for a cold profile while the real daemon
         * was elsewhere.
         *
         * Resolution order:
         *   1. `[general] default_profile = "..."` in config.toml, if present.
         *   2. `ProviderRegistry.defaultProvider()` (UD-012) — `"localfs"` when
         *      discovered, else the first SPI-discovered factory id. Used
         *      unconditionally when the config is missing, empty, or doesn't
         *      pin a default_profile. Still returned even if no such profile
         *      exists in the file; the caller is responsible for handing the
         *      result to [resolveProfile], which throws a readable "Unknown
         *      profile" error listing the configured alternatives.
         */
        fun resolveDefaultProfile(configDir: Path): String {
            val configFile = configDir.resolve("config.toml")
            if (!Files.exists(configFile)) return org.krost.unidrive.ProviderRegistry.defaultProvider()
            return try {
                val raw = parseRaw(Files.readString(configFile))
                raw.general.default_profile?.takeIf { it.isNotBlank() } ?: org.krost.unidrive.ProviderRegistry.defaultProvider()
            } catch (_: Exception) {
                // Corrupt / unparseable config: fall back to the CLI default rather
                // than crashing at startup. Downstream resolveProfile() will surface
                // the real parse error with full context.
                org.krost.unidrive.ProviderRegistry.defaultProvider()
            }
        }

        fun detectDuplicateSyncRoots(raw: RawSyncConfig): String? {
            val roots = mutableMapOf<String, String>() // normalized path → profile name
            for ((name, rp) in raw.providers) {
                val root = rp.sync_root?.let { expandTilde(it) } ?: continue
                val normalized = Paths.get(root).normalize().toString()
                val existing = roots[normalized]
                if (existing != null) {
                    return "Profiles '$existing' and '$name' share the same sync root $root. Each profile needs a unique sync root."
                }
                roots[normalized] = name
            }
            return null
        }

        fun parse(
            content: String,
            profileName: String = org.krost.unidrive.ProviderRegistry.defaultProvider(),
        ): SyncConfig {
            val raw = decodeRawSyncConfig(content)
            return raw.toSyncConfig(profileName)
        }

        private fun parseDirection(value: String): SyncDirection =
            when (value.lowercase()) {
                "upload" -> SyncDirection.UPLOAD
                "download" -> SyncDirection.DOWNLOAD
                else -> SyncDirection.BIDIRECTIONAL
            }

        private fun parsePolicy(value: String): ConflictPolicy =
            when (value.lowercase()) {
                "last_writer_wins" -> ConflictPolicy.LAST_WRITER_WINS
                else -> ConflictPolicy.KEEP_BOTH
            }

        private fun RawSyncConfig.toSyncConfig(profileName: String): SyncConfig {
            val profile = providers[profileName]
            val providerType = profile?.type ?: profileName
            val syncRoot =
                profile?.sync_root?.let { Paths.get(expandTilde(it)) }
                    ?: general.sync_root?.let { Paths.get(expandTilde(it)) }
                    ?: defaultSyncRoot(providerType)
            val logFile = general.log_file?.let { Paths.get(expandTilde(it)) }
            val providerConfigs =
                providers.mapValues { (_, rp) ->
                    ProviderConfig(
                        pinIncludes = rp.pin_patterns?.include ?: emptyList(),
                        pinExcludes = rp.pin_patterns?.exclude ?: emptyList(),
                        conflictOverrides =
                            (rp.conflict_overrides ?: emptyMap())
                                .mapKeys { (k, _) -> k.removeSurrounding("\"") }
                                .mapValues { (_, v) -> parsePolicy(v) },
                        excludePatterns = rp.exclude_patterns ?: emptyList(),
                    )
                }
            val pollInt = general.poll_interval ?: 60
            val rawMin = general.min_poll_interval ?: 10
            val rawMax = general.max_poll_interval ?: 300
            return SyncConfig(
                syncRoot = syncRoot,
                pollInterval = pollInt,
                minPollInterval = rawMin.coerceIn(5, pollInt),
                maxPollInterval = rawMax.coerceAtLeast(pollInt),
                conflictPolicy = general.conflict_policy?.let { parsePolicy(it) } ?: ConflictPolicy.KEEP_BOTH,
                logFile = logFile,
                maxBandwidthKbps = general.max_bandwidth_kbps,
                maxDeletePercentage = (general.max_delete_percentage ?: 50).coerceIn(0, 100),
                maxDeleteAbsolute = (general.max_delete_absolute ?: 50).coerceAtLeast(0),
                maxDeletePerSubtreePercent = (general.max_delete_per_subtree_percent ?: 80).coerceIn(0, 100),
                syncDirection = general.sync_direction?.let { parseDirection(it) } ?: SyncDirection.BIDIRECTIONAL,
                clientLocation = general.client_location,
                clientNetwork = general.client_network,
                globalExcludePatterns = general.exclude_patterns ?: emptyList(),
                desktopNotifications = general.desktop_notifications ?: false,
                verifyIntegrity = general.verify_integrity ?: false,
                useTrash = general.use_trash ?: true,
                trashEmulation = general.trash_emulation ?: false,
                trashRetentionDays = (general.trash_retention_days ?: 30).coerceAtLeast(1),
                fileVersioning = general.file_versioning ?: false,
                maxVersions = (general.max_versions ?: 5).coerceAtLeast(1),
                versionRetentionDays = (general.version_retention_days ?: 90).coerceAtLeast(1),
                streamingReconciliation = general.streaming_reconciliation,
                providers = providerConfigs,
            )
        }
    }
}
