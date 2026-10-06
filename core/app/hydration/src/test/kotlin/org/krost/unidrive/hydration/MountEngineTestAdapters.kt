package org.krost.unidrive.hydration

import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Path

// #560 U3: the mount operations moved from SyncEngine to MountEngine. Tests here that call them on the
// SyncEngine they built stay unchanged: these test-only adapters forward to the engine's one mount
// front-end (MountEngine.over), the same instance its HydrationImpl uses.

internal val SyncEngine.mount: MountEngine get() = MountEngine.over(this)

internal suspend fun SyncEngine.ensureHydrated(path: String): Path = mount.ensureHydrated(path)

internal suspend fun SyncEngine.uploadFromCache(
    path: String,
    cachePath: Path,
    ifMatchETag: String? = null,
    onProgress: ((Long, Long) -> Unit)? = null,
) = mount.uploadFromCache(path, cachePath, ifMatchETag, onProgress)
