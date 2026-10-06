package org.krost.unidrive.hydration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.PermanentDownloadFailureException
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
