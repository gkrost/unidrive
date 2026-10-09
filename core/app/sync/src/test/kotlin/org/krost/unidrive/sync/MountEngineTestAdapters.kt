package org.krost.unidrive.sync

import org.krost.unidrive.hydration.MountEngine
import java.nio.file.Path

// #560 U3: the mount operations moved from SyncEngine to MountEngine (:app:hydration). The tests of
// this module that pin them, the U1 parity tests (#567) among them, call them on a SyncEngine and stay
// unchanged: these test-only adapters forward to the engine's one mount front-end
// (MountEngine.over), which runs on the engine's own guard, gather, enumeration, cache layout and
// sync root. Main code reaches MountEngine directly; :app:sync does not depend on :app:hydration.

internal val SyncEngine.mount: MountEngine get() = MountEngine.over(this)

internal suspend fun SyncEngine.ensureHydrated(path: String): Path = mount.ensureHydrated(path)

internal suspend fun SyncEngine.uploadFromCache(
    path: String,
    cachePath: Path,
    ifMatchETag: String? = null,
    onProgress: ((Long, Long) -> Unit)? = null,
) = mount.uploadFromCache(path, cachePath, ifMatchETag, onProgress)

internal suspend fun SyncEngine.uploadMountWriteFromCache(
    path: String,
    cachePath: Path,
    baseToken: String?,
    onProgress: ((Long, Long) -> Unit)? = null,
) = mount.uploadMountWriteFromCache(path, cachePath, baseToken, onProgress)

internal suspend fun SyncEngine.createRemoteFolder(path: String) = mount.createRemoteFolder(path)

internal suspend fun SyncEngine.deleteRemote(path: String) = mount.deleteRemote(path)

internal suspend fun SyncEngine.discardStagedUpload(logicalPath: String) = mount.discardStagedUpload(logicalPath)

internal suspend fun SyncEngine.renameRemote(
    oldPath: String,
    newPath: String,
) = mount.renameRemote(oldPath, newPath)

internal suspend fun SyncEngine.remoteItemOrNull(path: String) = mount.remoteItemOrNull(path)

internal fun SyncEngine.enumerationStatus() = mount.enumerationStatus()

internal fun SyncEngine.isOutOfScope(path: String) = mount.isOutOfScope(path)

internal fun SyncEngine.isExcludedPath(path: String) = mount.isExcludedPath(path)

internal suspend fun <T> SyncEngine.withTransferPermit(block: suspend () -> T): T = mount.withTransferPermit(block)
