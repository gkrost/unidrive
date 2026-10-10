package org.krost.unidrive.hydration

import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path

// #560 U3: the mount operations moved from SyncEngine to MountEngine. Tests here that call them on the
// SyncEngine they built stay unchanged: these test-only adapters forward to the engine's one mount
// front-end (MountEngine.over), the same instance its HydrationImpl uses. SyncEngine is the only
// MountHost in production (the daemon builds MountEngine.over(engine)), so this module's tests keep
// a test-scope dependency on :app:sync; :app:sync's tests do not depend on this module.

internal val SyncEngine.mount: MountEngine get() = MountEngine.over(this)

internal suspend fun SyncEngine.ensureHydrated(path: String): Path = mount.ensureHydrated(path)

internal suspend fun SyncEngine.uploadFromCache(
    path: String,
    cachePath: Path,
    ifMatchETag: String? = null,
    onProgress: ((Long, Long) -> Unit)? = null,
) = mount.uploadFromCache(path, cachePath, ifMatchETag, onProgress)

internal suspend fun SyncEngine.createRemoteFolder(path: String) = mount.createRemoteFolder(path)

internal suspend fun SyncEngine.deleteRemote(path: String) = mount.deleteRemote(path)

internal suspend fun SyncEngine.renameRemote(
    oldPath: String,
    newPath: String,
) = mount.renameRemote(oldPath, newPath)

internal suspend fun SyncEngine.remoteItemOrNull(path: String) = mount.remoteItemOrNull(path)

// The one audit file an AuditLog of a test wrote into [dir] (`AuditLog.pathForToday` is internal to :app:sync).
internal fun auditFileIn(dir: Path): Path =
    Files.list(dir).use { files -> files.filter { it.fileName.toString().let { n -> n.startsWith("audit-") && n.endsWith(".jsonl") } }.toList() }.single()
