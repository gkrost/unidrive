package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger

class HydrationImpl(
    private val syncEngine: SyncEngine,
    private val stateDb: StateDatabase,
    private val recoveryUploadScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : Hydration {

    private val _events = MutableSharedFlow<HydrationEvent>(extraBufferCapacity = 64)
    override val events: Flow<HydrationEvent> = _events.asSharedFlow()

    // connectionId -> handleId -> path
    // The inner map is a ConcurrentHashMap: dehydrate scans every connection's
    // inner map with containsValue while other connections' IO-dispatched
    // handlers concurrently put/remove their own handles. A plain HashMap there
    // would corrupt under that race (torn read missing a live handle, or worker
    // crash), letting dehydrate delete the cache of a path open for write.
    private val openSets =
        ConcurrentHashMap<String, ConcurrentMap<String, String>>()

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

    // Per-path serialization for background uploads.
    //
    // When the same file is saved multiple times in quick succession,
    // each dirty close launches a background upload against the same
    // cachePath. Without serialization an older upload can finish after
    // a newer one, overwriting the remote with stale bytes and marking
    // the row synced with the wrong mtime.
    //
    // A single ConcurrentHashMap<String, UploadSlot> replaces the former
    // two-map design (uploadMutexes + uploadPendingCounts). Using
    // ConcurrentHashMap.compute() for both the increment-or-create and the
    // decrement-and-remove operations makes each of those steps atomic under
    // the map's bin lock. This closes the race in the old design where
    // coroutine A's decrementAndGet()→0 and its subsequent remove() were NOT
    // atomic: a concurrent submitter B could bump the count and computeIfAbsent
    // the same mutex between A's decrement and A's remove, leaving B holding a
    // mutex that A then deleted, causing a later submitter C to create a fresh
    // mutex — so B and C would run concurrently for the same path.
    private data class UploadSlot(val mutex: Mutex, val pending: AtomicInteger)
    private val uploadSlots = ConcurrentHashMap<String, UploadSlot>()

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
            // A genuinely-gone read (provider download still not-found after the
            // #176 re-resolve) must surface a typed not_found token so the mount
            // maps it to ENOENT, not the catch-all EIO. Any other failure stays
            // Generic (→ EIO).
            val err: HydrationError =
                if (isNotFound(e)) HydrationError.NotFound else HydrationError.Generic(e.message ?: "download failed")
            _events.emit(HydrationEvent.Failed(path, err))
            return OpenResult.Failed(err)
        }

        openSets.computeIfAbsent(connectionId) { ConcurrentHashMap() }[handleId] = path
        return OpenResult.Ok(cachePath)
    }

    override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path): OpenResult {
        stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.Generic("Unknown path: $path"))

        // Crash-recovery replay: the co-daemon's cache_scanner fires open_write with
        // handle_id = "recovery-<n>" for each cache file whose mtime exceeds the
        // last_synced watermark. These calls happen BEFORE the FUSE mount goes live,
        // and a synchronous upload of a large file (e.g. 650 MB) would block the
        // co-daemon indefinitely — the mountpoint would never appear.
        //
        // For recovery handles, launch the upload on recoveryUploadScope and return Ok
        // immediately so the co-daemon can proceed to mount(). The upload still runs;
        // on failure, markUploadFailed stamps the row so `unidrive doctor` can surface
        // the gap. The connection is already registered in openSets so closeHandle
        // from the co-daemon's paired close_handle call cleans it up normally.
        if (handleId.startsWith("recovery-")) {
            openSets.computeIfAbsent(connectionId) { ConcurrentHashMap() }[handleId] = path
            launchSerializedUpload(path, cachePath)
            return OpenResult.Ok(cachePath)
        }

        // Normal dirty close (FUSE release): the co-daemon fires open_write after the
        // user's close() already returned 0. Uploading synchronously here blocks the
        // FUSE release for the entire cloud upload — freezing the file manager (Dolphin,
        // Nautilus) for minutes on large files. Background the upload the same way the
        // recovery- path does: register the handle immediately, return Ok, and let the
        // upload run on recoveryUploadScope. Durability across crashes is provided by the
        // co-daemon's cache_scanner replay (which fires recovery- handles on next mount).
        // On failure: stamp the row so `unidrive doctor` can surface the unsynced gap.
        openSets.computeIfAbsent(connectionId) { ConcurrentHashMap() }[handleId] = path
        launchSerializedUpload(path, cachePath)
        return OpenResult.Ok(cachePath)
    }

    // Launches a background upload for [path] from [cachePath], serialized per-path
    // via the mutex in [uploadSlots]. Same-path uploads queue FIFO; different-path
    // uploads run concurrently. The cache file is read after acquiring the lock so
    // the last submitted upload always reads the latest content.
    //
    // Map cleanup: ConcurrentHashMap.compute() is used for BOTH the
    // increment-or-create (on launch) and the decrement-and-remove (in the finally
    // block). Because compute() holds the map's bin lock for the duration of the
    // lambda, a concurrent launch's compute() cannot interleave between the
    // decrement and the removal. This guarantees a slot is never removed while
    // another submitter is in the process of bumping its pending count.
    private fun launchSerializedUpload(path: String, cachePath: Path) {
        // Atomically create-or-get the slot and bump its pending count. The bin lock
        // held by compute() ensures that no concurrent finally-block can remove the
        // slot between the moment we decide to reuse it and the moment we increment.
        val slot = uploadSlots.compute(path) { _, s ->
            (s ?: UploadSlot(Mutex(), AtomicInteger(0))).also { it.pending.incrementAndGet() }
        }!!
        recoveryUploadScope.launch {
            try {
                slot.mutex.withLock {
                    try {
                        _events.emit(HydrationEvent.Hydrating(path))
                        syncEngine.uploadFromCache(path, cachePath)
                        val bytes = java.nio.file.Files.size(cachePath)
                        _events.emit(HydrationEvent.Hydrated(path, bytes))
                    } catch (e: Exception) {
                        runCatching { stateDb.markUploadFailed(path, java.time.Instant.now()) }
                        val err = HydrationError.Generic(e.message ?: "upload failed")
                        _events.emit(HydrationEvent.Failed(path, err))
                    }
                }
            } finally {
                // Atomically decrement and remove-when-zero. The bin lock held by
                // compute() ensures no concurrent launch's compute() can observe the
                // slot between the decrement and the null-return (removal). If
                // pending reaches zero the lambda returns null → ConcurrentHashMap
                // removes the entry; otherwise the existing slot is kept.
                uploadSlots.compute(path) { _, s ->
                    if (s == null || s.pending.decrementAndGet() == 0) null else s
                }
            }
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
                    // Clamp to >= 0: a stale / i32-overflowed remoteSize (e.g. a multi-GB
                    // folder size wrapped past Int.MAX to a negative) must never reach the
                    // wire — a negative size breaks strict u64 list parsers and EIO'd the
                    // FUSE co-daemon's whole directory listing.
                    val size = (if (e.isHydrated) (e.localSize ?: e.remoteSize) else e.remoteSize).coerceAtLeast(0L)
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
            // Ghost check (see rename): a local: row whose content actually landed
            // on the cloud must be deleted remotely, not just dropped locally —
            // otherwise the cloud copy is orphaned. Probe the remote; on a hit,
            // delete it cloud-side, else hard-delete the genuinely-local row.
            val ghost = syncEngine.remoteItemOrNull(normalised)
            if (ghost != null && !ghost.isFolder) {
                return runCatching {
                    syncEngine.deleteRemote(normalised)
                    UnlinkResult.Ok
                }.getOrElse { e ->
                    UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
                }
            }
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

    // Follow a renamed file's hydration cache from old to new path. A missing
    // cache file is tolerated (a zero-byte temp that was never written, or an
    // unhydrated placeholder). Shared by the genuinely-local and ghost rename paths.
    private fun moveCacheFile(oldNorm: String, newNorm: String) {
        val oldCache = syncEngine.resolveCachePath(oldNorm)
        val newCache = syncEngine.resolveCachePath(newNorm)
        try {
            Files.createDirectories(newCache.parent)
            Files.move(oldCache, newCache, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: NoSuchFileException) {
            // Cache file absent — nothing to move; the state.db repath suffices.
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

    override suspend fun openWriteBegin(connectionId: String, path: String, handleId: String?): OpenResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        val entry = stateDb.getEntry(normalised)
            ?: return OpenResult.Failed(HydrationError.Generic("unknown_path"))
        if (entry.isFolder) return OpenResult.Failed(HydrationError.Generic("path_is_folder"))
        return try {
            val cachePath = prepareEmptyCache(normalised)
            // When a live handle id is provided (O_TRUNC open), register it in
            // the connection's open-set so dehydrate/busy-checks see the file as
            // open.  One-shot callers (setattr bare-truncate) pass null → no
            // registration, matching the existing no-spurious-close_handle contract.
            if (handleId != null) {
                openSets.computeIfAbsent(connectionId) { ConcurrentHashMap() }[handleId] = normalised
            }
            OpenResult.Ok(cachePath)
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
                openSets.computeIfAbsent(connectionId) { ConcurrentHashMap() }[handleId] = normalised
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
        val sourceEntry = stateDb.getEntry(oldNorm)
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

        // Never-uploaded file (remoteId == null): the file only ever existed
        // locally — created through the mount, upload not yet done. Calling
        // provider.move would 404 (nothing exists cloud-side) and surface as
        // EIO on `mv`. Perform a purely local rename instead:
        //  1. Move the cache file from the old path to the new path. A missing
        //     cache file is tolerated — a zero-byte temp that was never written
        //     may have no cache entry yet.
        //  2. Repath the state.db row via renamePrefix (which rewrites both the
        //     root row and any descendants). remoteId stays null so the pending
        //     upload picks up the new path on the next sync pass.
        //  3. Return Ok without touching the provider.
        //
        // Mirrors the unlink remoteId==null branch above. Same rationale for
        // not leaving a tombstone: the row never reached the cloud, so there
        // is nothing to reconcile.
        //
        // Folder case: renamePrefix naturally covers a never-uploaded folder —
        // it rewrites the root row AND all descendant rows in one UPDATE, so
        // a never-uploaded folder rename is handled correctly here too. Cache
        // dirs follow from the per-file cache move logic (each child's cache
        // file is at resolveCachePath(childPath)); the folder itself has no
        // cache file. The overall folder case is safe as long as every
        // descendant also has remoteId==null (a mixed folder — some children
        // uploaded, some not — would need to be split). For now we treat the
        // whole subtree as local when the root's remoteId is null; a mixed
        // subtree in practice only occurs during an in-progress upload burst,
        // which is an unlikely race with a folder rename.
        if (sourceEntry.remoteId == null) {
            // A local: row is normally a genuinely-never-uploaded file. But a
            // "ghost" — an upload that committed cloud-side but lost its response —
            // also presents as local:, and a local-only rename would silently skip
            // the remote move (leaving the cloud copy under its old name and risking
            // a duplicate re-upload). Probe the remote: if the file is actually
            // there, treat it as a ghost — move it on the cloud and adopt its real
            // id — otherwise fall through to the genuinely-local rename.
            val ghost = syncEngine.remoteItemOrNull(oldNorm)
            if (ghost != null && !ghost.isFolder) {
                return runCatching {
                    syncEngine.renameRemote(oldNorm, newNorm)
                    moveCacheFile(oldNorm, newNorm)
                    stateDb.getEntry(newNorm)?.let { moved ->
                        stateDb.upsertEntry(
                            moved.copy(
                                remoteId = ghost.id,
                                remoteHash = ghost.hash,
                                remoteSize = ghost.size,
                                remoteModified = ghost.modified,
                            ),
                        )
                    }
                    RenameResult.Ok
                }.getOrElse { e ->
                    RenameResult.Failed(HydrationError.Generic(e.message ?: "rename failed"))
                }
            }
            return runCatching {
                moveCacheFile(oldNorm, newNorm)
                stateDb.renamePrefix(oldNorm, newNorm)
                RenameResult.Ok
            }.getOrElse { e ->
                RenameResult.Failed(HydrationError.Generic(e.message ?: "rename failed"))
            }
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

    // Classify a download failure as genuinely-not-found versus any other error.
    // Two provider-agnostic signals, since this module cannot reference a concrete
    // provider's exception type:
    //  - PermanentDownloadFailureException — the typed "remote object is gone
    //    (stable 404)" signal (Internxt raises it directly).
    //  - An exception carrying an Int `statusCode` == 404 — covers OneDrive's
    //    GraphApiException, read reflectively to avoid a provider classpath dep.
    private fun isNotFound(e: Throwable): Boolean {
        if (e is PermanentDownloadFailureException) return true
        return statusCodeOf(e) == 404 || (e.cause?.let { statusCodeOf(it) } == 404)
    }

    private fun statusCodeOf(e: Throwable): Int? =
        runCatching {
            val getter = e.javaClass.methods.firstOrNull { it.name == "getStatusCode" && it.parameterCount == 0 }
            (getter?.invoke(e) as? Int)
        }.getOrNull()
}
