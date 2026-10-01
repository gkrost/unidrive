package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Thin verb-based SPI between the engine and platform-tier consumers
 * (Phase 2 FUSE co-daemon; future Phase 3 Dolphin extension; eventual
 * Windows / Android tiers). All paths are remote-namespace paths
 * (cloud-side), not local FS paths. The cache-path returned from
 * open_* is a local FS path inside ~/.cache/unidrive/hydration/.
 *
 * Handle IDs are caller-supplied opaque strings. The implementation
 * tracks them per IPC connection (see HydrationImpl) so a co-daemon
 * crash cleanly releases its open-set without explicit close calls.
 */
interface Hydration {
    suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult

    /**
     * Register a write handle for [path] and start a background upload of
     * [cachePath] (see [HydrationImpl.openForWrite]). [baseEtag] is the
     * optimistic-concurrency token the client observed via `hydration.list`
     * (`etag` field) when it last read the file; when it no longer matches the
     * row's token the write is refused with [HydrationError.Conflict] BEFORE
     * any upload starts — the remote is untouched and the client keeps both
     * copies. Null (or a row with no recorded token) skips the guard: a
     * never-uploaded row has no remote version to lose.
     */
    suspend fun openForWrite(
        connectionId: String,
        handleId: String,
        path: String,
        cachePath: Path,
        baseEtag: String? = null,
    ): OpenResult
    suspend fun closeHandle(connectionId: String, handleId: String)
    suspend fun hydrate(path: String): HydrateResult
    suspend fun dehydrate(path: String): DehydrateResult
    suspend fun lastSynced(path: String): LastSyncedResult
    suspend fun list(prefix: String): ListResult

    suspend fun mkdir(path: String): MkdirResult
    suspend fun unlink(path: String): UnlinkResult
    suspend fun rmdir(path: String): RmdirResult
    suspend fun create(connectionId: String, handleId: String, path: String): CreateResult
    suspend fun openWriteBegin(connectionId: String, path: String, handleId: String? = null): OpenResult
    suspend fun rename(oldPath: String, newPath: String): RenameResult

    val events: Flow<HydrationEvent>

    /** Called by IpcServer when an IPC connection closes. Clears that connection's open-set. */
    fun onConnectionClosed(connectionId: String)
}

sealed class OpenResult {
    data class Ok(val cachePath: Path) : OpenResult()
    data class Failed(val error: HydrationError) : OpenResult()
}

sealed class HydrateResult {
    data object Ok : HydrateResult()
    data class Failed(val error: HydrationError) : HydrateResult()
}

sealed class DehydrateResult {
    data object Ok : DehydrateResult()
    data object Busy : DehydrateResult()
    data class Failed(val error: HydrationError) : DehydrateResult()
}

sealed class LastSyncedResult {
    data class Ok(val mtimeEpochMillis: Long) : LastSyncedResult()
    data class Unknown(val reason: String) : LastSyncedResult()
}

sealed class ListResult {
    data class Ok(val entries: List<Entry>) : ListResult()
    data class Failed(val error: HydrationError) : ListResult()

    data class Entry(
        val path: String,
        val size: Long,
        val mtimeEpochMillis: Long,
        val isHydrated: Boolean,
        val isFolder: Boolean,
        // Remote modified time straight from the provider, unlike [mtimeEpochMillis]
        // which is the local watermark (enumeration time for cloud-only rows,
        // download time for hydrated ones). Null for rows the provider never
        // reported a modified time for (never-uploaded rows).
        val remoteModifiedEpochMillis: Long? = null,
        // Provider identity of the row; null while the upload is still pending
        // (a row created/edited through the mount that has not landed cloud-side
        // yet). Lets a mirroring client recognise a remote rename (old path reaped,
        // new path upserted) as the same item.
        val remoteId: String? = null,
        // Provider change-detection token (content hash where the provider offers
        // one). Null when the provider exposes none — NOT a conditional-write
        // etag; see the open_write base_etag docs.
        val etag: String? = null,
        // True when the row holds local bytes whose upload has not completed —
        // created/edited through the mount, not yet on the cloud.
        val pendingUpload: Boolean = false,
        // True when the last write-back upload attempt against this row failed
        // (state.db last_error_at stamped; `unidrive doctor` surfaces the same gap).
        val hasError: Boolean = false,
    )
}

sealed class MkdirResult {
    data object Ok : MkdirResult()
    data class Failed(val error: HydrationError) : MkdirResult()
    data object ParentNotFound : MkdirResult()
}

sealed class UnlinkResult {
    data object Ok : UnlinkResult()
    data class Failed(val error: HydrationError) : UnlinkResult()
    data object PathIsFolder : UnlinkResult()
}

sealed class RmdirResult {
    data object Ok : RmdirResult()
    data class Failed(val error: HydrationError) : RmdirResult()
    data object PathIsFile : RmdirResult()
    data object NotEmpty : RmdirResult()
}

sealed class CreateResult {
    data class Ok(val cachePath: Path, val handleId: String) : CreateResult()
    data class Failed(val error: HydrationError) : CreateResult()
    data object ParentNotFound : CreateResult()
    data object PathExists : CreateResult()
}

sealed class RenameResult {
    data object Ok : RenameResult()
    data class Failed(val error: HydrationError) : RenameResult()
    data object OldPathNotFound : RenameResult()
    data object NewParentNotFound : RenameResult()
    data object NewPathExists : RenameResult()
}
