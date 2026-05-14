package org.krost.unidrive.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

data class RunContext(
    val config:    RawConfig,
    val runId:     String,
    val profile:   ResolvedProfile,
    val goldenDir: Path,
    val syncRoot:  Path,
    val provider:  String,
) {
    val sandboxRelative: String
        get() = "${config.general.base_folder}/$runId"
    
    val baseConfigDir: Path
        get() {
            val appData = System.getenv("APPDATA")
            return if (appData != null) Path.of(appData, "unidrive")
            else Path.of(System.getenv("HOME") ?: System.getProperty("user.home"), ".config", "unidrive")
        }

    companion object {
        fun create(
            config:   RawConfig,
            syncRoot: Path,
            provider: String,
        ): RunContext {
            val resolvedRunId = config.run.run_id.ifBlank {
                "run-${Instant.now().epochSecond}"
            }
            val profile = ConfigLoader.resolveProfile(config)
            val goldenDir = Files.createTempDirectory("unidrive-360-golden-$resolvedRunId")

            return RunContext(
                config    = config,
                runId     = resolvedRunId,
                profile   = profile,
                goldenDir = goldenDir,
                syncRoot  = syncRoot,
                provider  = provider,
            )
        }
    }
}
