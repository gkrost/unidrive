package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class HydrationImpl(
    private val syncEngine: SyncEngine,
    private val stateDb: StateDatabase,
) : Hydration {

    private val _events = MutableSharedFlow<HydrationEvent>(extraBufferCapacity = 64)
    override val events: Flow<HydrationEvent> = _events.asSharedFlow()

    // connectionId -> handleId -> path
    // TODO(Task 8): when closeHandle and openForWrite land, two coroutines for
    // the same connectionId may race on the inner map. Switch to
    // `ConcurrentHashMap<String, ConcurrentMap<String, String>>` (or guard inner
    // mutations with a connection-scoped lock) before this becomes load-bearing.
    private val openSets =
        ConcurrentHashMap<String, MutableMap<String, String>>()

    // Per-path mutex for `create`. Without this, two concurrent
    // `hydration.create(samePath)` callers can both pass the
    // `stateDb.getEntry == null` existence check before either has
    // upserted its row, then both proceed to TRUNCATE_EXISTING the
    // same cache file (clobbering one writer's content) and both
    // return Ok. The mutex serialises the check+materialise+upsert
    // tuple per-path; the second caller wakes after the first's
    // upsert and correctly returns PathExists. Entries stay in the
    // map for the daemon's lifetime — bounded by the number of
    // distinct paths ever created, which is small per-session.
    private val createMutexes = ConcurrentHashMap<String, Mutex>()

    override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult {
        val entry = stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.Generic("Unknown path: $path"))

        val cachePath = try {
            // Always emit Hydrating + Hydrated, even when SyncEngine returns a warm cache
            // without downloading: subscribers should see a consistent event stream
            // regardless of cache state; the cache layer is an implementation detail of
            // SyncEngine, not part of the Hydration SPI contract.
            _events.emit(HydrationEvent.Hydrating(path))
            val p = syncEngine.ensureHydrated(path)
            val bytes = java.nio.file.Files.size(p)
            _events.emit(HydrationEvent.Hydrated(path, bytes))
            p
        } catch (e: Exception) {
            val err = HydrationError.Generic(e.message ?: "download failed")
            _events.emit(HydrationEvent.Failed(path, err))
            return OpenResult.Failed(err)
        }

        openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = path
        return OpenResult.Ok(cachePath)
    }

    override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path): OpenResult {
        stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.Generic("Unknown path: $path"))

        return try {
            _events.emit(HydrationEvent.Hydrating(path))
            syncEngine.uploadFromCache(path, cachePath)
            val bytes = java.nio.file.Files.size(cachePath)
            _events.emit(HydrationEvent.Hydrated(path, bytes))
            openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = path
            OpenResult.Ok(cachePath)
        } catch (e: Exception) {
            // The co-daemon fires open_write at FUSE release; the user's
            // close() already returned 0. Make the durability gap
            // operator-visible by stamping the row before we surface the
            // failure, so `unidrive doctor` can report "written locally but
            // not uploaded." Best-effort — a stamp failure must not mask the
            // original upload error.
            runCatching { stateDb.markUploadFailed(path, java.time.Instant.now()) }
            val err = HydrationError.Generic(e.message ?: "upload failed")
            _events.emit(HydrationEvent.Failed(path, err))
            OpenResult.Failed(err)
        }
    }

    override suspend fun closeHandle(connectionId: String, handleId: String) {
        openSets[connectionId]?.remove(handleId)
    }
    override suspend fun hydrate(path: String): HydrateResult {
        return try {
            _events.emit(HydrationEvent.Hydrating(path))
            val cachePath = syncEngine.ensureHydrated(path)
            val bytes = java.nio.file.Files.size(cachePath)
            _events.emit(HydrationEvent.Hydrated(path, bytes))
            HydrateResult.Ok
        } catch (e: Exception) {
            val err = HydrationError.Generic(e.message ?: "hydrate failed")
            _events.emit(HydrationEvent.Failed(path, err))
            HydrateResult.Failed(err)
        }
    }
    override suspend fun dehydrate(path: String): DehydrateResult {
        stateDb.getEntry(path)
            ?: return DehydrateResult.Failed(HydrationError.Generic("Unknown path: $path"))

        // Check the open-set across ALL connections
        val anyOpen = openSets.values.any { perConn -> perConn.containsValue(path) }
        if (anyOpen) return DehydrateResult.Busy

        return try {
            val cachePath = syncEngine.resolveCachePath(path)
            java.nio.file.Files.deleteIfExists(cachePath)
            stateDb.markUnhydrated(path)
            _events.emit(HydrationEvent.Dehydrated(path))
            DehydrateResult.Ok
        } catch (e: Exception) {
            val err = HydrationError.Generic(e.message ?: "dehydrate failed")
            _events.emit(HydrationEvent.Failed(path, err))
            DehydrateResult.Failed(err)
        }
    }
    override suspend fun lastSynced(path: String): LastSyncedResult {
        val entry = stateDb.getEntry(path) ?: return LastSyncedResult.Unknown("unknown_path")
        val mtime = entry.localMtime ?: return LastSyncedResult.Unknown("no_mtime")
        return LastSyncedResult.Ok(mtime)
    }
    override suspend fun list(prefix: String): ListResult {
        // Normalise: "/" and "" both mean root; trailing slash equiv to no trailing slash.
        val normalised = prefix.trimEnd('/').let { if (it == "") "" else it }
        return try {
            val rows = stateDb.listDirectChildren(normalised)
            ListResult.Ok(
                rows.map { e ->
                    val size = if (e.isHydrated) (e.localSize ?: e.remoteSize) else e.remoteSize
                    ListResult.Entry(
                        path = e.path,
                        size = size,
                        mtimeEpochMillis = e.localMtime ?: e.lastSynced.toEpochMilli(),
                        isHydrated = e.isHydrated,
                        isFolder = e.isFolder,
                    )
                },
            )
        } catch (e: Exception) {
            ListResult.Failed(HydrationError.Generic(e.message ?: "list failed"))
        }
    }

    override suspend fun mkdir(path: String): MkdirResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        return runCatching {
            _events.emit(HydrationEvent.Hydrating(normalised))
            syncEngine.createRemoteFolder(normalised)
            _events.emit(HydrationEvent.Hydrated(normalised, bytes = 0L))
            MkdirResult.Ok
        }.getOrElse { e ->
            val msg = e.message ?: ""
            // Provider-side parent-missing detection by substring. Different
            // providers word it differently:
            // - OneDrive: GraphApiException("Create folder failed: 404 ...")
            // - Internxt: ProviderException("Folder not found: <seg> in <path>")
            // Both contain "not found" case-insensitively; OneDrive also
            // carries the literal "404". Match any of those signals.
            if (msg.contains("not found", ignoreCase = true) ||
                msg.contains("404", ignoreCase = true)
            ) {
                _events.emit(HydrationEvent.Failed(normalised, HydrationError.Generic(msg)))
                MkdirResult.ParentNotFound
            } else {
                val err = HydrationError.Generic(msg.ifBlank { "mkdir failed" })
                _events.emit(HydrationEvent.Failed(normalised, err))
                MkdirResult.Failed(err)
            }
        }
    }

    override suspend fun unlink(path: String): UnlinkResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        val entry = stateDb.getEntry(normalised)
            ?: return UnlinkResult.Failed(HydrationError.Generic("Unknown path: $normalised"))
        if (entry.isFolder) return UnlinkResult.PathIsFolder

        // Never-uploaded file (remote_id is null): the file only ever existed
        // locally — created through the mount, upload not yet done. There is
        // nothing to delete cloud-side, so calling provider.delete would 404
        // and surface as EIO on `rm`. Skip the provider call entirely; drop
        // the local cache file and HARD-DELETE the row.
        //
        // Hard-delete, not markDeleted: a tombstone preserves deletion-history
        // so the reconciler can tell "existed-on-cloud-then-deleted" from
        // "never-existed". A never-uploaded row has no cloud counterpart, so a
        // tombstone carries no reconciliation value — and create/delete temp-
        // file churn through the mount (editor swap files, build artifacts)
        // would grow sync_entries unboundedly with dead tombstones. deleteEntry
        // removes the row outright, matching the pending-upload cleanup path.
        if (entry.remoteId == null) {
            return runCatching {
                runCatching {
                    java.nio.file.Files.deleteIfExists(syncEngine.resolveCachePath(normalised))
                }
                stateDb.deleteEntry(normalised)
                UnlinkResult.Ok
            }.getOrElse { e ->
                UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
            }
        }

        return runCatching {
            syncEngine.deleteRemote(normalised)
            UnlinkResult.Ok
        }.getOrElse { e ->
            UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
        }
    }

    override suspend fun rmdir(path: String): RmdirResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        val entry = stateDb.getEntry(normalised)
            ?: return RmdirResult.Failed(HydrationError.Generic("Unknown path: $normalised"))
        if (!entry.isFolder) return RmdirResult.PathIsFile

        return runCatching {
            syncEngine.deleteRemote(normalised)
            RmdirResult.Ok
        }.getOrElse { e ->
            val msg = e.message ?: ""
            if (msg.contains("not empty", ignoreCase = true) ||
                msg.contains("non-empty", ignoreCase = true)
            ) {
                RmdirResult.NotEmpty
            } else {
                RmdirResult.Failed(HydrationError.Generic(msg.ifBlank { "rmdir failed" }))
            }
        }
    }

    private fun prepareEmptyCache(path: String): java.nio.file.Path {
        val cachePath = syncEngine.resolveCachePath(path)
        java.nio.file.Files.createDirectories(cachePath.parent)
        java.nio.file.Files.newByteChannel(
            cachePath,
            java.util.EnumSet.of(
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
            ),
        ).close()
        return cachePath
    }

    override suspend fun openWriteBegin(path: String): OpenResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        val entry = stateDb.getEntry(normalised)
            ?: return OpenResult.Failed(HydrationError.Generic("unknown_path"))
        if (entry.isFolder) return OpenResult.Failed(HydrationError.Generic("path_is_folder"))
        return try {
            OpenResult.Ok(prepareEmptyCache(normalised))
        } catch (e: Exception) {
            OpenResult.Failed(HydrationError.Generic(e.message ?: "open_write_begin failed"))
        }
    }

    override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        val mutex = createMutexes.computeIfAbsent(normalised) { Mutex() }
        return mutex.withLock {
            if (stateDb.getEntry(normalised) != null) return@withLock CreateResult.PathExists

            // Parent must exist as a folder row (root "/" / "" is implicit and
            // always considered present).
            val parent = normalised.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotEmpty()) {
                val parentEntry = stateDb.getEntry(parent)
                    ?: return@withLock CreateResult.ParentNotFound
                if (!parentEntry.isFolder) return@withLock CreateResult.ParentNotFound
            }

            try {
                val cachePath = prepareEmptyCache(normalised)
                val now = java.time.Instant.now()
                stateDb.upsertEntry(
                    org.krost.unidrive.sync.model.SyncEntry(
                        path = normalised,
                        remoteId = null,
                        remoteHash = null,
                        remoteSize = 0L,
                        remoteModified = null,
                        localMtime = now.toEpochMilli(),
                        localSize = 0L,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = now,
                    ),
                )
                openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = normalised
                CreateResult.Ok(cachePath = cachePath, handleId = handleId)
            } catch (e: Exception) {
                CreateResult.Failed(HydrationError.Generic(e.message ?: "create failed"))
            }
        }
    }

    override suspend fun rename(oldPath: String, newPath: String): RenameResult {
        val oldNorm = oldPath.trimEnd('/').let { if (it == "") "/" else it }
        val newNorm = newPath.trimEnd('/').let { if (it == "") "/" else it }

        // Pre-flight: source must exist in state.db.
        stateDb.getEntry(oldNorm)
            ?: return RenameResult.OldPathNotFound

        // Pre-flight: destination parent must exist (or destination is at root).
        val newParent = newNorm.substringBeforeLast('/', missingDelimiterValue = "")
        if (newParent.isNotEmpty()) {
            val parentEntry = stateDb.getEntry(newParent)
                ?: return RenameResult.NewParentNotFound
            if (!parentEntry.isFolder) return RenameResult.NewParentNotFound
        }

        // Pre-flight: destination must not exist. POSIX rename(2) atomically
        // replaces the destination if it exists; neither OneDrive's PATCH nor
        // Internxt's move endpoint supports atomic replace, and emulating it
        // via delete-then-rename leaves a window where the destination is
        // missing. Refuse with NewPathExists and let userland do the
        // unlink-then-rename dance (editors handle this gracefully).
        if (stateDb.getEntry(newNorm) != null) {
            return RenameResult.NewPathExists
        }

        return runCatching {
            syncEngine.renameRemote(oldNorm, newNorm)
            RenameResult.Ok
        }.getOrElse { e ->
            RenameResult.Failed(HydrationError.Generic(e.message ?: "rename failed"))
        }
    }

    override fun onConnectionClosed(connectionId: String) {
        openSets.remove(connectionId)
    }
}
