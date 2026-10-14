package org.krost.unidrive.cli

import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.Reconciler
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.ignore.CacheIgnoreSource
import org.krost.unidrive.sync.ignore.IgnoreSettings
import org.krost.unidrive.sync.ignore.UploadIgnore
import java.nio.file.Path

/**
 * The exclusion guard the daemon applies, built for a one-shot command (`get`, `ls`): the profile's exclude patterns
 * and, for a mount profile, its ignore rules, read from the profile's hydration cache. [inCloud] says whether state.db
 * holds a path as a cloud item, so a rule file only the cloud has holds its folder back, as it does in the daemon.
 */
internal fun cliExclusionGuard(
    config: SyncConfig,
    profileName: String,
    mode: ProfileMode?,
    inCloud: (path: String) -> Boolean,
): RemoteOperationGuard =
    exclusionGuard(
        patterns = config.effectiveExcludePatterns(profileName),
        ignoreSettings = config.ignoreSettings(profileName).takeIf { mode == ProfileMode.MOUNT },
        cacheDir = SyncEngine.hydrationCacheRoot(SyncEngine.defaultHydrationCacheRoot(), profileName),
        inCloud = inCloud,
    )

internal fun exclusionGuard(
    patterns: List<String>,
    ignoreSettings: IgnoreSettings?,
    cacheDir: Path,
    inCloud: (path: String) -> Boolean,
): RemoteOperationGuard {
    val ignore = ignoreSettings?.let { UploadIgnore(CacheIgnoreSource(cacheDir, inCloud), it) }
    return RemoteOperationGuard(
        standingScope = emptyList(),
        syncPaths = emptyList(),
        // With the ignore rules on, the built-in defaults are theirs (overridable); same split as the engine's.
        excludePatterns = if (ignore == null) patterns else patterns.filterNot { it in SyncConfig.DEFAULT_EXCLUDE_PATTERNS },
        maxConcurrentTransfers = 1,
        matchesGlob = { path, pattern -> Reconciler.matchesGlob(path, pattern) },
        ignore = ignore,
    )
}
