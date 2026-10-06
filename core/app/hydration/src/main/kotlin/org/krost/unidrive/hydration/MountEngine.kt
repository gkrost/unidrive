package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.engine.AuditSink
import org.krost.unidrive.engine.EnumerationEntryPoint
import org.krost.unidrive.engine.MountHost
import org.krost.unidrive.engine.MountWiring
import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.engine.SyncRootBridge
import org.krost.unidrive.engine.Transfers
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.EnumerationStatus
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * #560 U3: the mount front-end. The operations the hydration layer ([HydrationImpl]) and the daemon
 * run against the cloud and state.db for a mount: hydrate a path into the cache, upload a cache copy,
 * create, delete and rename remote items, the remote enumeration, and the daemon's sync-root rescan
 * (#504). They used to live in the mirror engine (`SyncEngine`, :app:sync) and moved here unchanged;
 * this module no longer depends on :app:sync (`checkModuleEdges`).
 *
 * Built over a [MountWiring] ([over]): the shared core of the engine that owns it, today `SyncEngine`
 * (the compatibility adapter until mount profiles get their own host, #560 U4/U6). One front-end per
 * wiring.
 *
 * Who owns what:
 *  - **Queues.** The upload queue (per-path serialisation, waiting depth, retries, replay) is
 *    [HydrationImpl]'s. The transfer budget (UD-263) is the [RemoteOperationGuard] of the wiring: one
 *    semaphore per engine, shared by the mirror's sync pass, the hydration uploads
 *    ([withTransferPermit]) and the rescan. This class keeps no queue.
 *  - **Scopes.** The standing scope, the per-run sync paths and the exclude patterns are fixed in the
 *    guard by the host at construction; this class only reads them ([isOutOfScope], [isExcludedPath]).
 *    A scope transition is the gather's (`RemoteGather.applyScopeTransition`).
 *  - **Transactions.** Each operation writes its rows itself after the provider call succeeded (one
 *    upsert, or `markDeleted`/`renamePrefix` for a subtree). The enumeration's batch commit and the
 *    cursor belong to `RemoteEnumeration`/`RemoteGather`. The database connection is the host's.
 *  - **Cancellation.** The caller's coroutine is the unit: every operation is a suspend function that
 *    rethrows [kotlinx.coroutines.CancellationException]; a cancelled hydration deletes its staging
 *    file, a cancelled upload is audited as failed and leaves the row pending, a cancelled rescan
 *    releases its single-flight guard. Background work (the upload queue) runs in [HydrationImpl]'s
 *    scope, the rescan timer in the daemon's serve scope.
 *  - **Shutdown order.** This class holds no thread, file or connection to close. The daemon cancels
 *    its serve scope (the rescan timer, the poller, the IPC handlers), then closes the IPC server, the
 *    state database and the process lock, in that order (`DaemonRuntime.cleanup`).
 */
class MountEngine private constructor(
    private val wiring: MountWiring,
) : EnumerationEntryPoint {
    private val provider: CloudProvider = wiring.provider
    private val db: StateDatabase = wiring.db
    private val guard: RemoteOperationGuard = wiring.guard
    private val options: MountWiring.Options = wiring.options

    // #560 U3: the sync root of the coordinated model (#449, #500, #504, #568), see SyncRootBridge.
    private val mirror: SyncRootBridge = wiring.syncRoot

    // The sync root folder (it may not exist: a mount-only profile never creates it).
    private val syncRoot: Path get() = mirror.root

    // UD-113: the host's audit log of mutations, or null.
    private val auditLog: AuditSink? = wiring.auditLog

    // The host's logger: the moved log lines keep their logger name.
    private val log: Logger = wiring.log

    /** See [RemoteOperationGuard.isOutOfScope]. */
    fun isOutOfScope(path: String): Boolean = guard.isOutOfScope(path)

    /** See [RemoteOperationGuard.isExcludedPath]. */
    fun isExcludedPath(path: String): Boolean = guard.isExcludedPath(path)

    /** See [RemoteOperationGuard.withTransferPermit]. */
    suspend fun <T> withTransferPermit(block: suspend () -> T): T = guard.withTransferPermit(block)

    /** The hydration-cache file of a logical [path] (the host's layout). */
    fun resolveCachePath(path: String): Path = wiring.cachePathOf(path)

    /**
     * One-way remote→state.db refresh for view consumers (the FUSE mount). Reuses the remote
     * gather + state.db upsert, but NEVER scans sync_root, NEVER plans/executes a local→remote
     * delete, and NEVER evaluates the empty-sync_root / max_delete_* guards. Remote-observed
     * deletions flip state.db rows only on a COMPLETE enumeration. Single-flight across both
     * front-ends: an overlapping call returns `skipped = true`. See `RemoteEnumeration` (#560 U2b)
     * and docs/dev/specs/mount-view-refresh-design.md.
     */
    override suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult = wiring.enumeration.enumerate(reset)

    /**
     * What a client may be told about the enumeration right now. A running attempt is answered from
     * memory alone: state.db is held by the batch that saves the result, and a status request must
     * not wait for it. See `RemoteEnumeration.status`.
     */
    fun enumerationStatus(): EnumerationStatus = wiring.enumeration.status()

    /** The enumeration's tracker; the daemon's poller records its next attempt on it. */
    val enumerationTracker: EnumerationTracker get() = wiring.enumeration.tracker

    // #318: per-path serialization for ensureHydrated's warm-cache check + download.
    // Without it, a second open whose warm-cache size check fails re-downloads with
    // TRUNCATE_EXISTING into the cache file while a first handle is still reading it
    // (silent short/garbage reads on POSIX), and two concurrent cold opens
    // double-download the same file. Entries persist for the daemon session — the
    // same lifetime tradeoff as HydrationImpl.createMutexes, bounded by the number
    // of distinct paths ever hydrated.
    private val hydrateMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * Hydrate a single remote path into the local hydration cache. Idempotent —
     * if the path is already hydrated and the cache file exists, returns the
     * existing cache path without re-downloading. Throws on unrecoverable errors
     * (e.g. [PermanentDownloadFailureException] for a 404; IO errors; unknown
     * path).
     *
     * Cache layout: `<cacheRoot>/unidrive/hydration/<cacheKey>/<path>` where
     * `cacheRoot` is the host's cache root when set, otherwise `XDG_CACHE_HOME` or
     * `~/.cache`, and `cacheKey` is the per-account namespace (profile.name).
     *
     * Integrity failure throws (not warns) because FUSE-passthrough exposes the
     * cache directly to userspace reads — a silently accepted corrupt file would
     * be immediately visible to the user, unlike the mirror's `applyDownload`
     * local-placeholder path where a warning is recoverable on the next sync.
     *
     * #318: the whole check+download runs under a per-path mutex. Concurrent
     * opens of the same path must not double-download, and a re-download must
     * never truncate a cache file another handle is reading.
     */
    suspend fun ensureHydrated(path: String): Path =
        hydrateMutexes.computeIfAbsent(path) { Mutex() }.withLock { ensureHydratedLocked(path) }

    private suspend fun ensureHydratedLocked(path: String): Path {
        val entry = db.getEntry(path)
            ?: throw IllegalArgumentException("Unknown remote path: $path")
        val cachePath = resolveCachePath(path)
        // Trust the warm cache only if it is actually COMPLETE. The isHydrated flag
        // plus mere file-existence is not enough for a REMOTE-backed file: a stale
        // isHydrated over a 0-byte/truncated cache (crash mid-download, an
        // externally-cleared cache, a reset/enumeration window) would otherwise be
        // served as-is, silently yielding empty/short reads — which a file-manager
        // copy turns into a corrupt 0-byte destination. Re-download whenever the
        // cached size doesn't match the known remote size.
        //
        // Local-only rows (remoteId == null: created/edited through the mount, not
        // yet uploaded — remoteSize is 0 while the cache holds the just-written
        // bytes) have NO remote to compare against or re-download from; the cache is
        // the only copy, so always trust them on the warm path. #136: this
        // remoteId == null is NOT the UD-901 pending-upload predicate — it means "no
        // remote to compare against", and isHydrated here carries the warm-trust
        // meaning, so the split check is intentional.
        val warmCacheUsable =
            entry.remoteId == null ||
                runCatching { Files.size(cachePath) }.getOrDefault(-1L) == entry.remoteSize
        if (entry.isHydrated && Files.exists(cachePath) && warmCacheUsable) {
            // #449: a row from before cache_backed existed whose baseline still describes the sync-root file
            // is settled here, so a later delete of that file is not mistaken for a cache-only row.
            if (entry.cacheBacked == null && rowDescribesSyncRootFile(entry, path)) {
                db.upsertEntry(entry.copy(cacheBacked = false))
            }
            return cachePath
        }
        // #449: the sync root already holds these bytes: copy them instead of downloading again.
        if (copySyncRootCopyIntoCache(entry, path, cachePath)) {
            if (entry.cacheBacked != false) db.upsertEntry(entry.copy(cacheBacked = false))
            return cachePath
        }
        // Construct a minimal CloudItem so downloadByIdOrPath can route by id
        // (fast path) or fall back to path-based (when remoteId is null).
        val remoteItem = CloudItem(
            id = entry.remoteId ?: "",
            name = path.substringAfterLast("/"),
            path = path,
            size = entry.remoteSize,
            isFolder = false,
            modified = entry.remoteModified ?: Instant.now(),
            created = entry.remoteModified ?: Instant.now(),
            hash = entry.remoteHash,
            mimeType = null,
        )
        Files.createDirectories(cachePath.parent)
        // #318: download to a temp sibling and atomically swap it in, so a handle
        // already reading the old cache keeps its inode instead of having the file
        // truncated under it by TRUNCATE_EXISTING (silent short/garbage reads).
        val staged =
            cachePath.resolveSibling(
                // #529: a long cache name cannot take the full suffix — stage short when over the limit.
                org.krost.unidrive.io.stagingSiblingName(
                    cachePath.fileName.toString(),
                    ".hydrating-" + java.util.UUID.randomUUID(),
                ),
            )
        try {
            val downloadedSize = Transfers.downloadByIdOrPath(provider, remoteItem, path, staged)
            if (options.verifyIntegrity) {
                val verified = HashVerifier.verify(staged, entry.remoteHash, algorithm = provider.hashAlgorithm())
                if (!verified) {
                    // The surviving old cache may be corrupt-but-right-sized; removing
                    // it forces a clean re-download on the next open instead of
                    // serving it warm.
                    Files.deleteIfExists(cachePath)
                    throw IllegalStateException("Integrity check failed for hydration cache: $path")
                }
            }
            try {
                Files.move(staged, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, cachePath, StandardCopyOption.REPLACE_EXISTING)
            }
            // Persist the freshly-downloaded size as remoteSize. The provider validated the
            // download against the authoritative remote length (throwing on a short read), so
            // this is the current truth. Without it a remote that changed size since the last
            // enumeration leaves remoteSize stale, and the openForRead size guard would EIO a
            // perfectly valid re-download.
            val current = db.getEntry(path) ?: entry
            if (rowDescribesSyncRootFile(current, path)) {
                // #418: the download went to the cache, a different file from the one in the sync
                // root. localMtime/localSize are the baseline LocalScanner compares THAT file against,
                // and isHydrated says whether THAT file holds real bytes (a freed placeholder must not
                // start claiming it does). Rewriting them from the cache copy made the next scan read an
                // untouched sync-root file as modified and upload it (a zero-filled placeholder included).
                // localHash stays too: the sync-root bytes are unchanged, so the recorded hash still
                // describes them.
                db.upsertEntry(current.copy(remoteSize = downloadedSize, lastSynced = Instant.now(), cacheBacked = false))
            } else {
                // No sync-root file the row describes (mount mode, or a hydrated row whose file changed
                // since the row was written): the cache copy is the local file. HydrationImpl.lastSynced()
                // and the co-daemon's crash-recovery scanner use this localMtime as their watermark.
                val rebaselined = current.copy(
                    isHydrated = true,
                    remoteSize = downloadedSize,
                    localMtime = Files.getLastModifiedTime(cachePath).toMillis(),
                    localSize = Files.size(cachePath),
                    lastSynced = Instant.now(),
                    cacheBacked = true,
                )
                // The row just adopted the cache copy's stats, so the hash must describe the cache
                // copy's bytes: keeping a hash recorded for the previous contents would pair stale
                // bytes with a fresh mtime/size and let a later touch be absorbed as unchanged.
                db.upsertEntry(
                    withLocalHash(rebaselined, cachePath, rebaselined.localMtime!!, rebaselined.localSize!!),
                )
            }
            return cachePath
        } finally {
            runCatching { Files.deleteIfExists(staged) }
        }
    }

    // #449 read side. Copies the sync-root file into [cachePath] (never a hard link: the mount
    // writes the cache file in place, and a shared inode would change the sync-root bytes behind
    // the row's baseline) when that file is provably the row's current remote version; false means
    // "download as before". The proof, all of it needed:
    //  - the row is hydrated, has a remote and describes the sync-root file (mtime and size still
    //    match the baseline, so nobody edited it since), and the size is the remote size;
    //  - a provider with a content hash: the bytes hash to the remote hash; otherwise the
    //    baseline mtime is the remote modified time (the download stamps it, so an upload of a
    //    user's own file does not qualify) and a recorded local hash (#396) still matches the bytes.
    // The cache copy is written beside its destination and renamed into place, and dropped if the
    // source changed while it was being read.
    private fun copySyncRootCopyIntoCache(
        entry: SyncEntry,
        path: String,
        cachePath: Path,
    ): Boolean {
        if (entry.isFolder || !entry.isHydrated || entry.remoteId == null) return false
        var tmp: Path? = null
        return try {
            if (!rowDescribesSyncRootFile(entry, path)) return false
            val local = mirror.resolveLocal(path)
            val size = Files.size(local)
            if (size != entry.remoteSize) return false
            val mtime = Files.getLastModifiedTime(local).toMillis()
            val algorithm = provider.hashAlgorithm()
            val current =
                if (algorithm != null && !entry.remoteHash.isNullOrEmpty()) {
                    HashVerifier.verify(local, entry.remoteHash, algorithm)
                } else {
                    entry.remoteModified?.toEpochMilli() == mtime &&
                        (entry.localHash == null || HashVerifier.computeSha256Hex(local) == entry.localHash)
                }
            if (!current) return false
            Files.createDirectories(cachePath.parent)
            val copy = Files.createTempFile(cachePath.parent, ".ud-serve-", ".tmp").also { tmp = it }
            Files.copy(local, copy, StandardCopyOption.REPLACE_EXISTING)
            // Same footprint as a download: the copy's mtime is "now" on every platform (Windows
            // would carry the source's over), so the cache never looks like the sync-root file.
            Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.from(Instant.now()))
            if (Files.size(copy) != size || Files.size(local) != size || Files.getLastModifiedTime(local).toMillis() != mtime) return false
            try {
                Files.move(copy, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(copy, cachePath, StandardCopyOption.REPLACE_EXISTING)
            }
            log.debug("#449: served {} from the sync root, no download", path)
            true
        } catch (e: java.io.IOException) {
            log.debug("#449: cannot serve {} from the sync root ({}), downloading", path, e.message)
            false
        } finally {
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /**
     * The directory that holds this profile's hydration cache (every cache file lives below it, laid
     * out like the remote tree). #450: what the cache budget measures and evicts.
     */
    fun hydrationCacheDir(): Path = resolveCachePath("/")

    /**
     * #450: may the cache copy of [path] be deleted without losing anything? Answered from the bytes,
     * not from timestamps (enumeration refreshes a row's `last_synced`, so it cannot tell an edit
     * from a download). Never evictable ([CacheDisposition.PROTECTED]): no row, a folder, a row without a
     * remote (never uploaded: the cache is the only copy), a row marked failed, a copy the row's
     * baseline does not vouch for (modified since it was recorded), and a copy that differs from every
     * version the row knows. The caller still owes the open-handle and queued-upload checks, which
     * only the hydration layer can see.
     */
    fun cacheDisposition(path: String): CacheDisposition {
        val entry = db.getEntry(path) ?: return CacheDisposition.PROTECTED
        if (entry.isFolder || entry.remoteId == null || entry.lastErrorAt != null) return CacheDisposition.PROTECTED
        val cache = resolveCachePath(path)
        return try {
            if (!Files.isRegularFile(cache)) return CacheDisposition.PROTECTED
            val size = Files.size(cache)
            val mtime = Files.getLastModifiedTime(cache).toMillis()
            val cacheIsBaseline = entry.localMtime == mtime && entry.localSize == size
            if (entry.cacheBacked == true || (entry.cacheBacked == null && cacheIsBaseline)) {
                // The cache copy is the row's local file: clean only while it is exactly what was recorded.
                return if (cacheIsBaseline) CacheDisposition.DISPOSABLE else CacheDisposition.PROTECTED
            }
            if (entry.isHydrated && rowDescribesSyncRootFile(entry, path)) {
                val placeholderMatchesCache = Files.mismatch(mirror.resolveLocal(path), cache) == -1L
                if (placeholderMatchesCache) return CacheDisposition.REDUNDANT
            }
            if (cacheMatchesRecordedVersion(entry, cache, size)) CacheDisposition.DISPOSABLE else CacheDisposition.PROTECTED
        } catch (e: java.io.IOException) {
            CacheDisposition.PROTECTED
        }
    }

    // The bytes of [cache] are a version the row knows: the remote version (content-hash providers) or
    // the baseline bytes the row hashed when it recorded them (#396, hashless providers).
    private fun cacheMatchesRecordedVersion(
        entry: SyncEntry,
        cache: Path,
        size: Long,
    ): Boolean {
        val algorithm = provider.hashAlgorithm()
        return if (algorithm != null && !entry.remoteHash.isNullOrEmpty()) {
            size == entry.remoteSize && HashVerifier.verify(cache, entry.remoteHash, algorithm)
        } else {
            entry.localHash != null && HashVerifier.computeSha256Hex(cache) == entry.localHash
        }
    }

    /**
     * #450: delete the cache copy of [path] if [cacheDisposition] still allows it, under the same
     * per-path lock as [ensureHydrated] (no eviction in the middle of a hydration of the same path).
     * Returns the freed bytes, or null when the copy was protected or could not be removed. A row whose
     * local file WAS the cache copy is marked not hydrated (the local bytes are gone; the next sync or
     * open refills them from the remote); a row that describes the sync-root file is left alone.
     * [onUnhydrated] is invoked for the first kind, so the hydration layer can publish the event.
     */
    suspend fun evictCacheCopy(
        path: String,
        onUnhydrated: (String) -> Unit = {},
    ): Long? =
        hydrateMutexes.computeIfAbsent(path) { Mutex() }.withLock {
            if (cacheDisposition(path) == CacheDisposition.PROTECTED) return@withLock null
            val entry = db.getEntry(path) ?: return@withLock null
            val cache = resolveCachePath(path)
            val size = runCatching { Files.size(cache) }.getOrNull() ?: return@withLock null
            val mtime = runCatching { Files.getLastModifiedTime(cache).toMillis() }.getOrNull() ?: return@withLock null
            // The same test cacheDisposition used: the cache copy is the row's local file.
            val cacheWasLocalFile =
                entry.cacheBacked == true || (entry.cacheBacked == null && entry.localMtime == mtime && entry.localSize == size)
            try {
                Files.delete(cache)
            } catch (e: java.io.IOException) {
                log.debug("#450: cannot evict the cache copy of {}: {}", path, e.message)
                return@withLock null
            }
            if (cacheWasLocalFile) {
                db.markUnhydrated(path)
                onUnhydrated(path)
            } else if (entry.cacheBacked == null) {
                db.upsertEntry(entry.copy(cacheBacked = false))
            }
            size
        }

    // #418: true when [entry] is about the regular file in the sync root rather than about the
    // cache copy. Two cases:
    //  - a NOT hydrated row with a file there that has a placeholder shape (see
    //    [looksLikePlaceholder]): it holds no real bytes and must stay flagged as such until
    //    a sync fills it;
    //  - a hydrated row whose baseline still matches the file (same mtime and size).
    // No file, a hydrated row whose file changed, a not-hydrated row whose file does NOT look
    // like a placeholder (real user content: treat it as an edit, not as a placeholder — the
    // recovery download must never overwrite it), or an unresolvable name answer false and keep
    // the cache-as-local-file behaviour (mount mode).
    private fun rowDescribesSyncRootFile(
        entry: SyncEntry,
        path: String,
    ): Boolean =
        runCatching {
            val local = mirror.resolveLocal(path)
            when {
                !Files.isRegularFile(local) -> false
                !entry.isHydrated -> looksLikePlaceholder(local, entry)
                else ->
                    entry.localMtime != null &&
                        entry.localSize != null &&
                        Files.getLastModifiedTime(local).toMillis() == entry.localMtime &&
                        Files.size(local) == entry.localSize
            }
        }.getOrDefault(false)

    suspend fun uploadFromCache(
        path: String,
        cachePath: Path,
        ifMatchETag: String? = null,
        // Byte progress of the transfer, (transferred, total). Optional: the
        // hydration upload path threads it into `uploading` events; callers
        // that don't need it leave it null (the sync-progress reporter below
        // always runs).
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        require(Files.exists(cachePath)) { "Cache path missing: $cachePath" }
        if (isExcludedPath(path)) {
            log.info("Skipping upload of excluded path (keep-local): {}", path)
            // Keep-local files are never uploaded, but the local watermark must still
            // advance. HydrationImpl.lastSynced() reports localMtime as the watermark,
            // and the co-daemon's crash-recovery scanner replays open_write for any
            // cache file whose mtime exceeds it — so without this, every daemon restart
            // re-replays excluded files forever. remoteId stays null (nothing uploaded).
            val mtime = Files.getLastModifiedTime(cachePath).toMillis()
            val size = Files.size(cachePath)
            val existing = db.getEntry(path)
            db.upsertEntry(
                existing?.copy(
                    localMtime = mtime,
                    localSize = size,
                    isHydrated = true,
                    // #396: new bytes behind this mtime/size; a recorded hash of the old ones is stale.
                    localHash = null,
                    cacheBacked = true,
                ) ?: SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = mtime,
                    localSize = size,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                    cacheBacked = true,
                ),
            )
            return
        }
        val existingEntry = db.getEntry(path)
            // #319/#301: no row means the file was renamed away, unlinked, or reaped
            // (the remote vanished) while this upload sat queued. The old fallback
            // created a fresh row and uploaded anyway, which resurrected the old
            // remote path after a rename and let the reap race a queued edit.
            // Refuse: the caller's client gets a failed Completed event, the bytes
            // stay in the cache, and the recovery scanner re-targets them at the
            // row's current path.
            ?: throw IllegalStateException(
                "uploadFromCache: row for $path vanished while the upload was queued; refusing to recreate it",
            )
        val prevHash = existingEntry.remoteHash
        val existingRemoteId = existingEntry.remoteId
        // #115: if the existing row is a locale-aliased one, its content lives
        // at the canonical remote path; the FUSE write-back must upload there,
        // not at the alias `path`. Non-aliased rows have remotePath == null →
        // upload at `path`, byte-identical to pre-#115.
        val remotePath = existingEntry.remotePath ?: path
        val sizeForLog = Files.size(cachePath)
        val sent = statBeforeUpload(cachePath)
        // #583: bytes identical to the version the row records, and the cloud still holds that version: there is
        // nothing to send. The copy is adopted below exactly as an upload would rebaseline the row.
        val unchanged = unchangedCloudVersionOrNull(existingEntry, remotePath, cachePath, sizeForLog)
        if (unchanged != null) {
            log.info("uploadFromCache: {} has the bytes the cloud already holds ({} bytes); upload skipped", path, sizeForLog)
        }
        val result =
            unchanged
                ?: try {
                    provider.upload(cachePath, remotePath, existingRemoteId = existingRemoteId, ifMatchETag = ifMatchETag) { transferred, total ->
                        wiring.onTransferProgress(path, transferred, total)
                        onProgress?.invoke(transferred, total)
                    }
                } catch (e: Exception) {
                    auditLog?.emit(
                        action = "Upload",
                        path = path,
                        size = sizeForLog,
                        oldHash = prevHash,
                        result = "failed:${e.javaClass.simpleName}: ${e.message}",
                    )
                    throw e
                }
        val auditResult = if (unchanged != null) "skipped:unchanged" else "success"
        // Defer the absence sweep's deletion verdict while the delta feed catches up
        // to this just-written remote item (see markRecentlyUploaded). Keyed by the
        // REMOTE path so the absence sweep (remote namespace) matches. Nothing was written when the upload was skipped.
        if (unchanged == null) markRecentlyUploaded(remotePath)
        // #337/#148: the row records the cache copy's stats as they were BEFORE
        // the transfer (same rule as applyUpload). A write landing mid-upload
        // keeps a newer mtime than the recorded watermark, so a later re-upload
        // of those bytes is never absorbed into this baseline.
        val afterUpload = statBeforeUpload(cachePath)
        if (afterUpload != null && sent != null && afterUpload != sent) {
            log.debug(
                "uploadFromCache: {} changed while it was being uploaded; keeping the pre-upload watermark",
                path,
            )
        }
        val mtime = sent?.first ?: afterUpload?.first ?: Files.getLastModifiedTime(cachePath).toMillis()
        val size = sent?.second ?: afterUpload?.second ?: Files.size(cachePath)
        val existing = db.getEntry(path)
        if (existing == null) {
            // #319: the row vanished while the bytes were in flight (a delete or
            // reap raced the upload). The remote copy is current; writing a fresh
            // row here resurrected a deleted path in state.db. Skip the row write —
            // the next enumeration decides between adopting the remote item and
            // re-reaping it.
            log.warn(
                "uploadFromCache: row for {} vanished mid-upload; remote copy is current, skipping row write",
                path,
            )
            auditLog?.emit(
                action = "Upload",
                path = path,
                size = size,
                oldHash = prevHash,
                newHash = result.hash,
                result = auditResult,
            )
            return
        }
        if (rowDescribesSyncRootFile(existing, path)) {
            // The row describes the file in the sync root, not this cache copy (an open_read
            // hydrated the cache and the crash-recovery scanner replayed it as an open_write).
            //
            // #423 decision: the sync root should hold the bytes that were written
            // through the cache and just uploaded, so propagate them (echo-suppressed,
            // best-effort) and rebaseline the row from the sync-root copy. #427's
            // remote-fields-only write left the sync root holding stale bytes while the
            // row claimed it was in step with the new remote — a later sync-root edit
            // then uploaded over the newer remote content without a conflict.
            // Copying the SAME bytes that landed remotely cannot revert anything; it
            // converges all three copies (cache, sync root, remote) immediately, and
            // covers the replay-over-a-placeholder case by filling the placeholder
            // with exactly the remote's bytes.
            val localPath = mirror.resolveLocal(path)
            val converged =
                runCatching {
                    withEchoSuppression(path) {
                        Files.createDirectories(localPath.parent)
                        Files.copy(cachePath, localPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                }.onFailure { e ->
                    log.warn(
                        "uploadFromCache: could not propagate the cache write to the sync-root file {}: {}",
                        path,
                        e.message,
                    )
                }.isSuccess
            if (converged) {
                // Baseline the row against the sync-root copy it now describes, and
                // hash THOSE bytes (post-copy stats, same #337 pattern as the else
                // branch) so the touch shield covers the propagated write too.
                val sentLocal = statBeforeUpload(localPath)
                db.upsertEntry(
                    withSentHash(
                        existing.copy(
                            remoteId = result.id,
                            remoteHash = result.hash,
                            remoteSize = result.size,
                            remoteModified = result.modified,
                            localMtime = sentLocal?.first,
                            localSize = sentLocal?.second,
                            isHydrated = true,
                            lastSynced = Instant.now(),
                            lastErrorAt = existing.lastErrorAtAfterUpload(),
                            cacheBacked = false,
                        ),
                        localPath,
                        sentLocal,
                    ),
                )
            } else {
                // Propagation failed (locked file, ...): keep the row's remote fields
                // at their pre-upload values so the next delta still reports the
                // upload as a remote change and downloads the written bytes into the
                // sync root, instead of the row claiming the stale sync-root content
                // is in step with the new remote. lastSynced still moves so the
                // recovery scanner does not replay this upload loop-wise.
                db.upsertEntry(
                    existing.copy(
                        lastSynced = Instant.now(),
                        lastErrorAt = existing.lastErrorAtAfterUpload(),
                    ),
                )
            }
        } else {
            // #319: `existing` is non-null here (the vanished-row case returns above),
            // so the upload rebaselines the row that owns the bytes instead of
            // minting an ownerless one.
            val uploaded = existing.copy(
                remoteId = result.id,
                remoteHash = result.hash,
                remoteSize = result.size,
                remoteModified = result.modified,
                localMtime = mtime,
                localSize = size,
                isHydrated = true,
                lastSynced = Instant.now(),
                lastErrorAt = existing.lastErrorAtAfterUpload(),
                cacheBacked = true,
            )
            // The row just adopted the cache copy's stats, and these are exactly the bytes the
            // write-back sent: hash the cache copy (guarded against a writer landing mid-hash)
            // so the touch shield covers mount-edited files too.
            val hashed = withSentHash(uploaded, cachePath, sent)
            // #449 write side: a file made or edited through the mount has no sync-root file the row
            // describes, so a later plain `sync` would read the missing file as a local delete (#459).
            // Now that the provider has the bytes, put the same bytes there; the row's baseline is then
            // that file (the hash just taken, of the same bytes, stays) and no longer the cache copy.
            val mirrored = mirrorIntoSyncRoot(path, cachePath, sent)
            db.upsertEntry(
                if (mirrored != null) {
                    hashed.copy(localMtime = mirrored.first, localSize = mirrored.second, cacheBacked = false)
                } else {
                    hashed
                },
            )
        }
        auditLog?.emit(
            action = "Upload",
            path = path,
            size = size,
            oldHash = prevHash,
            newHash = result.hash,
            result = auditResult,
        )
    }

    /**
     * #583: the cloud item the cache copy is already identical to, or null when the copy has to be uploaded.
     * Skipping needs both: the bytes equal the version the row records ([cacheMatchesRecordedVersion]: the provider's
     * content hash, or the SHA-256 the engine recorded for hashless providers), AND the cloud still holds that version
     * (same item, size and modified time as the row; the provider's version token when both sides have one). Anything
     * else (a pending `local:` row, no recorded hash, a cloud copy that moved on, a lookup that fails) returns null and
     * the upload runs as before, so a changed cloud copy still meets the provider's write-token and conflict handling.
     */
    private suspend fun unchangedCloudVersionOrNull(
        entry: SyncEntry,
        remotePath: String,
        cachePath: Path,
        size: Long,
    ): CloudItem? {
        val remoteId = entry.remoteId ?: return null
        if (entry.isFolder || remoteId.startsWith("local:")) return null
        if (size != entry.remoteSize) return null
        if (!cacheMatchesRecordedVersion(entry, cachePath, size)) return null
        val current =
            try {
                provider.getMetadata(remotePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("uploadFromCache: cloud lookup for {} failed ({}); not skipping", remotePath, e.message)
                return null
            }
        if (current.isFolder || current.id != remoteId || current.size != entry.remoteSize) return null
        val recordedModified = entry.remoteModified
        if (recordedModified != null && current.modified != recordedModified) return null
        val recordedToken = entry.remoteHash
        if (!recordedToken.isNullOrEmpty() && !current.hash.isNullOrEmpty() && current.hash != recordedToken) return null
        return current
    }

    /**
     * A mount's base token comes from [SyncEntry.remoteHash]. OneDrive stores a
     * content hash there, not Graph's opaque eTag, while Internxt stores its
     * replace-version token there. Keep the latter guard, but do not hand the
     * former to Graph as an If-Match value.
     */
    suspend fun uploadMountWriteFromCache(
        path: String,
        cachePath: Path,
        baseToken: String?,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) = uploadFromCache(
        path = path,
        cachePath = cachePath,
        ifMatchETag = if (provider.id == "onedrive") null else baseToken,
        onProgress = onProgress,
    )

    // #449 write side, for a row that describes no sync-root file. Places the bytes just uploaded from
    // [cachePath] at the row's path in the sync root and returns the (mtime, size) of that file, which
    // become the row's baseline; null means nothing was written and the row keeps recording the cache
    // copy (cache_backed = true), which the Reconciler guard keeps from being read as a local delete.
    // Skipped, never forced, when:
    //  - the path is out of scope or not representable locally, or the sync root itself does not
    //    exist (a mount-only profile must not grow a folder tree it never asked for);
    //  - a file (or a directory) is already there: the row does not describe it, so it changed since
    //    the baseline or was never tracked, and the next sync decides, exactly as without the mirror;
    //  - the cache file changed since the stats taken before the upload: a newer write is queued
    //    behind this one and mirrors itself.
    // The file is copied to a temp file in the destination directory (`*.tmp`, a default exclude, so
    // a scan never sees it) and renamed in, with the watcher's echo suppressed. A copy, not a hard
    // link, for the same reason as in [copySyncRootCopyIntoCache]. Any I/O failure is logged and
    // swallowed: the upload already succeeded and must not be reported as failed.
    private fun mirrorIntoSyncRoot(
        path: String,
        cachePath: Path,
        sent: Pair<Long, Long>?,
    ): Pair<Long, Long>? {
        if (sent == null) return null
        if (!isTracked(path) || localNameIssue(path) != null || !Files.isDirectory(syncRoot)) return null
        var tmp: Path? = null
        return try {
            val target = mirror.resolveLocal(path)
            val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
            if (Files.exists(target, noFollow)) {
                log.info("#449: not mirroring {} into the sync root: a file is already there that the row does not describe, the next sync decides", path)
                return null
            }
            if (statBeforeUpload(cachePath) != sent) return null
            Files.createDirectories(target.parent)
            val copy = Files.createTempFile(target.parent, ".ud-mirror-", ".tmp").also { tmp = it }
            Files.copy(cachePath, copy, StandardCopyOption.REPLACE_EXISTING)
            Files.setLastModifiedTime(copy, java.nio.file.attribute.FileTime.fromMillis(sent.first))
            if (statBeforeUpload(cachePath) != sent || Files.size(copy) != sent.second) return null
            withEchoSuppression(path) {
                // No REPLACE_EXISTING: a file that appeared in the meantime is not ours to replace.
                Files.move(copy, target)
            }
            Files.getLastModifiedTime(target).toMillis() to Files.size(target)
        } catch (e: Exception) {
            log.warn("#449: could not mirror {} into the sync root, the row keeps the cache copy as its local file: {}", path, e.message)
            null
        } finally {
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    // A landed upload settles an earlier failed attempt: markUploadFailed stamps last_error_at
    // on the row, and an `existing.copy(...)` would otherwise carry that stamp into the
    // successful row for good (hydration.list then reports `error` for a file that is in the
    // cloud). A download quarantine shares the column and keeps its own stamp.
    private fun SyncEntry.lastErrorAtAfterUpload(): Instant? = if (downloadQuarantined) lastErrorAt else null

    /**
     * Create a folder on the remote provider and record it in state.db.
     * Used by the hydration SPI (HydrationImpl.mkdir) to back FUSE mkdir
     * requests. Separate code path from the legacy applyActions loop.
     *
     * Throws ProviderException on cloud-side failure. state.db is only
     * updated after the provider call succeeds.
     */
    suspend fun createRemoteFolder(path: String): CloudItem {
        val item = provider.createFolder(path)
        db.insertFolder(path = path, remoteId = item.id, mtime = item.modified ?: Instant.now())
        mirrorFolderIntoSyncRoot(path)
        return item
    }

    // #500: the sync root is the same tree as the mount (#449), empty folders included. A file's upload creates its
    // parents there ([mirrorIntoSyncRoot]); a folder made through the mount that stays empty had no such step, so the
    // mirror missed every empty folder. Same guards as for a file; a failure is logged, the folder exists in the cloud.
    private fun mirrorFolderIntoSyncRoot(path: String) {
        if (!isTracked(path) || localNameIssue(path) != null || !Files.isDirectory(syncRoot)) return
        try {
            val target = mirror.resolveLocal(path)
            val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
            if (Files.isDirectory(target, noFollow)) return
            if (Files.exists(target, noFollow)) {
                log.info("#500: not mirroring the folder {} into the sync root: a file is already there, the next sync decides", path)
                return
            }
            withEchoSuppression(path) { Files.createDirectories(target) }
        } catch (e: Exception) {
            log.warn("#500: could not mirror the folder {} into the sync root: {}", path, e.message)
        }
    }

    /**
     * Delete a path on the remote provider and update state.db.
     * Handles both files and folders — provider distinguishes by
     * remoteId/path. Caller (HydrationImpl.unlink or .rmdir) is
     * responsible for type-checking.
     *
     * Idempotent on "remote already gone": if [provider.delete] throws a
     * [ProviderException] that [isAlreadyGone] recognises as a typed not-found
     * signal, the deletion is treated as already complete — the exception is
     * swallowed and markDeleted still runs, since the postcondition "path no
     * longer on cloud" is satisfied. Every other exception (auth, 5xx, network,
     * throttle) is re-thrown unchanged so real failures surface as EIO rather
     * than being silently eaten.
     *
     * The idempotency gate is type-gated, not free-text: only the two specific
     * provider-originated not-found shapes (path-resolution failure, direct
     * metadata miss) are recognised. A 5xx/proxy error whose body happens to
     * contain "404" or "not found" does NOT satisfy [isAlreadyGone] and will
     * re-throw as expected.
     *
     * state.db is only updated after the provider call succeeds (or is
     * determined to be a no-op because the remote is already gone).
     */
    suspend fun deleteRemote(path: String) {
        // #449 review: read before the delete — once the row is tombstoned the alive
        // view no longer answers "did this row's baseline live in the sync root?".
        val entryBefore = db.getEntry(path)
        try {
            provider.delete(path)
        } catch (e: Exception) {
            if (isAlreadyGone(e)) {
                // Remote is already gone; fall through to markDeleted below.
            } else {
                throw e
            }
        }
        db.markDeleted(path)
        // #87: a folder's rows below it are part of the same user delete — leave none of
        // them EXISTS, or the next fresh mount plans their re-download (the live 133k case).
        if (entryBefore?.isFolder == true) db.markDescendantsDeleted(path)
        dropSyncRootCopy(path, entryBefore)
    }

    /**
     * WB-3 (#87): the delete of a never-uploaded file discards the provider's staged upload copy —
     * the encrypted ciphertext (and its resume sidecar) is the only other copy of the content, the
     * user deleted the file, so it goes with the row and the cache copy instead of sitting in the
     * tombstone directory until the resume TTL passes. The path is the logical one; the engine
     * resolves it to the local cache path the uploader staged, which is what the tombstone is keyed
     * by. A provider without staged uploads ignores this (the default is a no-op).
     */
    suspend fun discardStagedUpload(logicalPath: String) {
        provider.discardStagedUpload(resolveCachePath(logicalPath).toAbsolutePath().toString())
    }

    // #449 review fix: the remote path is gone and its row tombstoned — the sync-root
    // mirror must not survive them, or the next scan reads the orphan file as NEW and
    // re-uploads the path the user just deleted (a resurrection through the mirror).
    // A file goes only when it is the copy the row describes (#568): a row whose
    // baseline is the cache copy (cacheBacked == true) got no mirror because the sync
    // root held a DIFFERENT, unsynced version (#423/#427), and a mirrored file the user
    // edited since no longer matches its baseline. Either is the only copy of an edit:
    // it stays, and the next sync uploads it as new — the deleted path coming back is
    // the price of never losing that edit. A folder's empty mirror directory goes with
    // it (a non-empty one holds files of rows that are not deleted — deleteIfExists
    // refuses it, and that is correct). Files the caller removed already (a mount
    // unlink evicts its own copies, a sync-root-side delete is what triggered this)
    // make this a no-op. Best effort either way: the delete already succeeded, and the
    // sweep of a later session can still reclaim.
    private fun dropSyncRootCopy(
        path: String,
        entryBefore: SyncEntry?,
    ) {
        if (entryBefore == null) return
        if (!entryBefore.isFolder && (entryBefore.cacheBacked == true || !rowDescribesSyncRootFile(entryBefore, path))) {
            if (runCatching { Files.isRegularFile(mirror.resolveLocal(path)) }.getOrDefault(false)) {
                log.warn("#568: kept the sync-root file of the deleted {}: it is not the synced copy (an unsynced edit)", path)
            }
            return
        }
        runCatching {
            val target = mirror.resolveLocal(path)
            withEchoSuppression(path) {
                Files.deleteIfExists(target)
            }
        }.onFailure { e ->
            log.warn("#449: could not remove the sync-root copy of the deleted {}: {}", path, e.message)
        }
    }

    // #560 U2: the classification lives in :app:engine-core (RemoteErrors), shared with the mount.
    // See [org.krost.unidrive.engine.RemoteErrors.isAlreadyGone] for the two shapes it accepts.
    private fun isAlreadyGone(e: Throwable): Boolean = org.krost.unidrive.engine.RemoteErrors.isAlreadyGone(e)

    /**
     * Rename a remote item from [oldPath] to [newPath] and update state.db.
     * Used by the hydration SPI (HydrationImpl.rename) to back FUSE rename
     * requests. Pre-flight checks (source-exists, destination-doesn't-exist,
     * destination-parent-exists) live in HydrationImpl; this entry point
     * trusts its caller and performs the remote move plus the state.db
     * row update unconditionally.
     *
     * Throws ProviderException on cloud-side failure. state.db is only
     * updated after the provider call succeeds. For folders, the path
     * rewrite also moves all descendant rows under the new prefix
     * (db.renamePrefix), matching the rename's recursive semantics on
     * both OneDrive and Internxt.
     */
    suspend fun renameRemote(oldPath: String, newPath: String) {
        // #449 review: read before the move — renamePrefix repaths the rows, and the
        // alive view then answers for the NEW path only.
        val entryBefore = db.getEntry(oldPath)
        provider.move(oldPath, newPath)
        db.renamePrefix(oldPath, newPath)
        moveSyncRootCopy(oldPath, newPath, entryBefore)
    }

    // #449 review fix: a mount rename moves the remote item, the row(s) and the cache
    // file, but until now left the sync-root mirror at the old path — an orphan file
    // with no row, which the next scan read as NEW and re-uploaded under the old name
    // (the #319 resurrection shape, reintroduced through the mirror). The mirror
    // follows the rename; rows whose baseline is the cache copy (cacheBacked == true)
    // and legacy rows (null) have no sync-root file to move. Best effort: a file that
    // appeared at the destination in the meantime is not ours to replace — the old
    // copy stays and the next sync decides, exactly as the mirror-skip rule does.
    private fun moveSyncRootCopy(
        oldPath: String,
        newPath: String,
        entryBefore: SyncEntry?,
    ) {
        if (entryBefore?.cacheBacked != false) return
        runCatching {
            val from = mirror.resolveLocal(oldPath)
            if (!Files.exists(from)) return
            val to = mirror.resolveLocal(newPath)
            withEchoSuppression(newPath) {
                withEchoSuppression(oldPath) {
                    Files.createDirectories(to.parent)
                    // No REPLACE_EXISTING: a file at the destination is not ours to replace.
                    Files.move(from, to)
                }
            }
        }.onFailure { e ->
            log.warn(
                "#449: could not move the sync-root copy of {} to {}: {}",
                oldPath,
                newPath,
                e.message,
            )
        }
    }

    // The remote item at [path], or null only when the provider proves it ABSENT.
    // The mount's rename/unlink use this to tell a genuinely-never-uploaded local:
    // row from a "ghost" — a local: row whose content actually landed on the cloud
    // (an upload whose response was lost) and must therefore be moved/deleted
    // remotely, not handled locally. A genuine not-found maps to null; transient
    // failures (auth expiry, throttling, 5xx, network) are PROPAGATED, never read
    // as absence — otherwise a ghost probed during a blip would fall to the
    // local-only path and silently skip the cloud move/delete (orphan/duplicate).
    // isAlreadyGone covers the typed Internxt "not found"; statusCode 404 covers
    // OneDrive's GraphApiException.
    suspend fun remoteItemOrNull(path: String): CloudItem? =
        try {
            provider.getMetadata(path)
        } catch (e: Exception) {
            if (isAlreadyGone(e) || statusCodeOf(e) == 404 || (e.cause?.let { statusCodeOf(it) } == 404)) {
                null
            } else {
                throw e
            }
        }

    // Reflectively read a `getStatusCode(): Int` off a provider exception without a
    // provider-module classpath dependency (mirrors the hydration SPI helper). SyncEngine keeps its
    // own copy for the mirror pass (#560 U2 kept the probe beside its callers).
    private fun statusCodeOf(e: Throwable): Int? =
        runCatching {
            val getter = e.javaClass.methods.firstOrNull { it.name == "getStatusCode" && it.parameterCount == 0 }
            getter?.invoke(e) as? Int
        }.getOrNull()

    private fun statBeforeUpload(local: Path): Pair<Long, Long>? = Transfers.statBeforeUpload(local)

    // #337/#148: hash only the bytes that were sent, see Transfers.withSentHash.
    private fun withSentHash(
        entry: SyncEntry,
        local: Path,
        sent: Pair<Long, Long>?,
    ): SyncEntry = Transfers.withSentHash(entry, local, sent, provider.hashAlgorithm(), log)

    private fun <T> withEchoSuppression(
        path: String,
        block: () -> T,
    ): T = mirror.withEchoSuppression(path, block)

    private fun isTracked(remotePath: String): Boolean = guard.isTracked(remotePath)

    private fun localNameIssue(path: String): String? = mirror.localNameIssue(path)

    // Defer the absence sweep's deletion verdict for a path this process just wrote (the gather's state).
    private fun markRecentlyUploaded(path: String) = wiring.gather.markRecentlyUploaded(path)

    // The bytes are hashed as the host's transfers do (#396), see Transfers.withLocalHash.
    private fun withLocalHash(
        entry: SyncEntry,
        local: Path,
        mtime: Long,
        size: Long,
    ): SyncEntry = Transfers.withLocalHash(entry, local, mtime, size, provider.hashAlgorithm(), log)

    private fun looksLikePlaceholder(
        local: Path,
        entry: SyncEntry,
    ): Boolean = mirror.looksLikePlaceholder(local, entry)

    companion object {
        /** The mount front-end of [host]: one per host, built on the first call. */
        fun over(host: MountHost): MountEngine = host.mountWiring.frontEnd { MountEngine(it) }
    }
}

/**
 * #450: what [MountEngine.cacheDisposition] says about a hydration-cache copy.
 * [PROTECTED] must never be deleted; [REDUNDANT] is byte-identical to the file in the sync root (the
 * cheapest to evict); [DISPOSABLE] holds only bytes the row already knows (the remote version or the
 * recorded baseline).
 */
enum class CacheDisposition { PROTECTED, REDUNDANT, DISPOSABLE }
