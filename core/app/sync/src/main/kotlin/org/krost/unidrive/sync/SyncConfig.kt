package org.krost.unidrive.sync

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.Serializable
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// ── ktoml-serializable intermediates ─────────────────────────────────────────

private val ktoml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

/** Top-level so the @Serializable-generated companion is in scope at the call site. */
internal fun decodeRawSyncConfig(content: String): RawSyncConfig = ktoml.decodeFromString(RawSyncConfig.serializer(), content)

@Serializable
data class RawSyncConfig(
    val general: RawGeneral = RawGeneral(),
    val providers: Map<String, RawProvider> = emptyMap(),
)

@Serializable
data class RawGeneral(
    // UD-252: explicit opt-in to pin "the default profile" when invoked without -p.
    // Honoured by both the CLI and the MCP jar via [SyncConfig.resolveDefaultProfile].
    // When unset, both jars fall back to "onedrive" (the historical CLI default).
    val default_profile: String? = null,
    val sync_root: String? = null,
    val poll_interval: Int? = null,
    val min_poll_interval: Int? = null,
    val max_poll_interval: Int? = null,
    val conflict_policy: String? = null,
    val log_file: String? = null,
    val max_bandwidth_kbps: Int? = null,
    val max_delete_percentage: Int? = null,
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
    // X-tra encryption
    val xtra_encryption: Boolean? = null,
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
    private val providers: Map<String, ProviderConfig>,
) {
    fun providerPinIncludes(providerId: String): List<String> = providers[providerId]?.pinIncludes ?: emptyList()

    fun providerPinExcludes(providerId: String): List<String> = providers[providerId]?.pinExcludes ?: emptyList()

    fun providerConflictOverrides(providerId: String): Map<String, ConflictPolicy> = providers[providerId]?.conflictOverrides ?: emptyMap()

    fun providerExcludePatterns(providerId: String): List<String> = providers[providerId]?.excludePatterns ?: emptyList()

    fun effectiveExcludePatterns(providerId: String): List<String> =
        globalExcludePatterns + (providers[providerId]?.excludePatterns ?: emptyList())

    data class ProviderConfig(
        val pinIncludes: List<String> = emptyList(),
        val pinExcludes: List<String> = emptyList(),
        val conflictOverrides: Map<String, ConflictPolicy> = emptyMap(),
        val excludePatterns: List<String> = emptyList(),
    )

    companion object {
        private val home: String = System.getenv("HOME") ?: System.getProperty("user.home")

        fun defaultConfigDir(): Path {
            val appData = System.getenv("APPDATA")
            return if (appData != null) {
                Paths.get(appData, "unidrive")
            } else {
                Paths.get(home, ".config", "unidrive")
            }
        }

        fun defaultConfigPath(): Path = defaultConfigDir().resolve("config.toml")

        fun load(
            configFile: Path,
            providerId: String = "onedrive",
        ): SyncConfig {
            if (!Files.exists(configFile)) return defaults(providerId)
            val content = Files.readString(configFile)
            return parse(content, providerId)
        }

        private val defaultSyncRoots =
            mapOf(
                "onedrive" to "OneDrive",
            )

        fun defaultSyncRoot(providerId: String): Path =
            Paths.get(home, defaultSyncRoots[providerId] ?: providerId.replaceFirstChar { it.uppercase() })

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

        fun defaults(providerId: String = "onedrive") =
            SyncConfig(
                syncRoot = defaultSyncRoot(providerId),
                pollInterval = 60,
                minPollInterval = 10,
                maxPollInterval = 300,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                logFile = null,
                maxBandwidthKbps = null,
                maxDeletePercentage = 50,
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

        fun parseRaw(content: String): RawSyncConfig = decodeRawSyncConfig(content)

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
         *   2. `"onedrive"` — the historical CLI default, used unconditionally when
         *      the config is missing, empty, or doesn't pin a default_profile. Still
         *      returned even if no such profile exists in the file; the caller is
         *      responsible for handing the result to [resolveProfile], which throws
         *      a readable "Unknown profile" error listing the configured alternatives.
         */
        fun resolveDefaultProfile(configDir: Path): String {
            val configFile = configDir.resolve("config.toml")
            if (!Files.exists(configFile)) return "onedrive"
            return try {
                val raw = parseRaw(Files.readString(configFile))
                raw.general.default_profile?.takeIf { it.isNotBlank() } ?: "onedrive"
            } catch (_: Exception) {
                // Corrupt / unparseable config: fall back to the CLI default rather
                // than crashing at startup. Downstream resolveProfile() will surface
                // the real parse error with full context.
                "onedrive"
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
            profileName: String = "onedrive",
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
                providers = providerConfigs,
            )
        }
    }
}
