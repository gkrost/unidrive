package org.krost.unidrive.e2e

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

// ── ktoml-serializable raw types ─────────────────────────────────────────────

@Serializable
data class RawConfig(
    val general:   GeneralConfig              = GeneralConfig(),
    val run:       RunConfig                  = RunConfig(),
    val encodings: EncodingsConfig            = EncodingsConfig(),
    val media:     MediaConfig                = MediaConfig(),
    val filesystem_limits: FsLimitsConfig     = FsLimitsConfig(),
    val verify:    VerifyConfig               = VerifyConfig(),
    val providers: Map<String, ProviderConfig> = emptyMap(),
)

@Serializable
data class GeneralConfig(
    val profile:               String = "dev",
    val hash_algorithm:        String = "SHA3-512",
    val sample_percentage:     Int    = 10,
    val min_sample:            Int    = 10,
    val max_sample:            Int    = 200,
    val max_depth:             Int    = 4,
    val files_per_folder:      Int    = 8,
    val ascii_name_ratio:      Int    = 50,
    val unicode_normalization: String = "NFC",
    val upload_delay_ms:       Long   = 500,
    val max_path_length:       Int    = 0,
    val base_folder:           String = "unidrive-360",
)

@Serializable
data class RunConfig(
    val run_id:                  String  = "",
    val cleanup_local_after_run: Boolean = true,
    val preserve_remote_golden:  Boolean = true,
)

@Serializable
data class EncodingsConfig(
    val variants: List<String> = emptyList(),
)

@Serializable
data class MediaConfig(
    val images: List<String> = emptyList(),
    val video:  List<String> = emptyList(),
    val audio:  List<String> = emptyList(),
    val docs:   List<String> = emptyList(),
)

@Serializable
data class FsLimitsConfig(
    val ntfs_max_name_chars: Int = 255,
    val ext4_max_name_bytes: Int = 255,
    val ntfs_max_path_chars: Int = 32767,
    val ext4_max_path_bytes: Int = 4096,
)

@Serializable
data class VerifyConfig(
    val heic_verify_mode:    String = "size_only",
    val internxt_verify_via: String = "unidrive",
)

@Serializable
data class ProviderConfig(
    val enabled: Boolean = false,
    val phase:   String  = "backlog",
)

// ── Resolved profile ──────────────────────────────────────────────────────────

data class ResolvedProfile(
    val name:          String,
    val maxDepth:      Int,
    val filesPerFolder: Int,
)

private val PROFILE_PRESETS: Map<String, Pair<Int, Int>> = mapOf(
    "dev"    to (2 to 3),
    "ci"     to (3 to 5),
    "full"   to (4 to 8),
    "stress" to (5 to 12),
)

// ── ConfigLoader ──────────────────────────────────────────────────────────────

private val ktoml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

object ConfigLoader {

    fun parse(content: String): RawConfig =
        ktoml.decodeFromString(RawConfig.serializer(), content)

    fun load(path: Path): RawConfig =
        parse(Files.readString(path))

    fun resolveProfile(config: RawConfig): ResolvedProfile {
        val name = config.general.profile
        val preset = PROFILE_PRESETS[name]
        return if (preset != null) {
            ResolvedProfile(
                name           = name,
                maxDepth       = preset.first,
                filesPerFolder = preset.second,
            )
        } else {
            ResolvedProfile(
                name           = name,
                maxDepth       = config.general.max_depth,
                filesPerFolder = config.general.files_per_folder,
            )
        }
    }
}
