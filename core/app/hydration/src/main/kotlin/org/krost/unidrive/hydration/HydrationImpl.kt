package org.krost.unidrive.hydration

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.FolderNotEmptyException
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.engine.MountHost
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HydrationImpl(
    // #560 U3: the mount operations (hydrate, upload, remote create/delete/rename) run on the mount
    // front-end; this class owns the upload queue, the open set and the cache budget on top of it.
    private val mount: MountEngine,
    private val stateDb: StateDatabase,
    private val recoveryUploadScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    // Upload-queue tuning. The waiting depth bounds how many submitted-but-
    // not-yet-running uploads the daemon holds; a burst beyond it suspends
    // the open_write callers (back-pressure towards the client) instead of
    // growing the queue. A failed upload is retried up to [maxUploadAttempts]
    // times, with [uploadRetryDelaysMs][i] preceding retry i+1; the transfer
    // permit is released during the wait so a backing-off path never starves
    // the daemon-wide budget.
    val uploadQueueDepth: Int = DEFAULT_UPLOAD_QUEUE_DEPTH,
    val maxUploadAttempts: Int = DEFAULT_MAX_UPLOAD_ATTEMPTS,
    val uploadRetryDelaysMs: List<Long> = DEFAULT_UPLOAD_RETRY_DELAYS_MS,
    // Stall watchdog (the mount-upload hang, gkrost/unidrive#613): one transfer
    // attempt that has made no progress — no byte progress from the provider —
    // for this long is cancelled and runs the normal failed-attempt path (the
    // row is stamped, `failed` with retry_scheduled goes out, the retry ladder
    // runs, a `completed` ends it), so the path's slot is freed and later
    // hand-overs of the same path queue behind a bounded wait, not a lost
    // wakeup. 0 or less disables the watchdog.
    val uploadStallTimeoutMs: Long = DEFAULT_UPLOAD_STALL_TIMEOUT_MS,
    // Minimum wall-clock gap between `uploading` progress events per attempt;
    // coalesces provider progress callbacks to at most a few per second per
    // file. 0 emits every callback (tests).
    val uploadProgressMinIntervalMs: Long = DEFAULT_UPLOAD_PROGRESS_MIN_INTERVAL_MS,
    // #450: per-profile budget of the hydration cache in bytes (`hydration_cache_max_bytes`);
    // 0 or less = unlimited. See [evictCache].
    private val cacheMaxBytes: Long = DEFAULT_CACHE_MAX_BYTES,
    // #450: a cache file used within this window is never evicted: a read that has just been served
    // is not in the open-set until the handle is registered, and a client that is about to open it
    // would otherwise be handed a path that vanishes.
    private val cacheAccessGraceMs: Long = CACHE_ACCESS_GRACE_MS,
    // #450: after a close or an upload completion the eviction pass runs once this much later, one
    // pass for any number of triggers (it walks the whole cache directory). 0 = immediately.
    private val evictionDelayMs: Long = EVICTION_DELAY_MS,
    // #493: a row whose last upload attempt failed is replayed this much after the start instead of at once, so work
    // that can succeed (and the client's fresh writes) goes first. 0 = replay at once, as before.
    private val failedReplayDelayMs: Long = DEFAULT_FAILED_REPLAY_DELAY_MS,
) : Hydration {

    /** Test seam to hold a startup cache scan in flight and verify cancellation between entries. */
    internal var cacheScanCheckpoint: () -> Unit = {}

    /** Cancel and join all background work owned by this hydration layer before its state DB closes. */
    suspend fun shutdownUploads() {
        recoveryUploadScope.coroutineContext[Job]?.cancelAndJoin()
    }

    /**
     * #560 U3 compatibility adapter: hydration over the mount front-end of [syncEngine] (`MountEngine.over`,
     * the one instance per engine the daemon wiring uses too). It keeps the call sites that build hydration on
     * a `SyncEngine` (`unidrive sync`, the tests) as they were until mount profiles get their own host (U4, U6).
     */
    constructor(
        syncEngine: MountHost,
        stateDb: StateDatabase,
        recoveryUploadScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        uploadQueueDepth: Int = DEFAULT_UPLOAD_QUEUE_DEPTH,
        maxUploadAttempts: Int = DEFAULT_MAX_UPLOAD_ATTEMPTS,
        uploadRetryDelaysMs: List<Long> = DEFAULT_UPLOAD_RETRY_DELAYS_MS,
        uploadStallTimeoutMs: Long = DEFAULT_UPLOAD_STALL_TIMEOUT_MS,
        uploadProgressMinIntervalMs: Long = DEFAULT_UPLOAD_PROGRESS_MIN_INTERVAL_MS,
        cacheMaxBytes: Long = DEFAULT_CACHE_MAX_BYTES,
        cacheAccessGraceMs: Long = CACHE_ACCESS_GRACE_MS,
        evictionDelayMs: Long = EVICTION_DELAY_MS,
        failedReplayDelayMs: Long = DEFAULT_FAILED_REPLAY_DELAY_MS,
    ) : this(
        MountEngine.over(syncEngine),
        stateDb,
        recoveryUploadScope,
            uploadQueueDepth,
            maxUploadAttempts,
            uploadRetryDelaysMs,
            uploadStallTimeoutMs,
            uploadProgressMinIntervalMs,
        cacheMaxBytes,
        cacheAccessGraceMs,
        evictionDelayMs,
        failedReplayDelayMs,
    )

    private val log = LoggerFactory.getLogger(HydrationImpl::class.java)

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
    private data class UploadSlot(
        val mutex: Mutex,
        val pending: AtomicInteger,
        // Live worker coroutines of this slot, for cancelUpload. Registered on
        // launch; pruned by cancelUpload (completed jobs report false on
        // cancel). Dies with the slot when pending drops to zero.
        val jobs: ConcurrentLinkedQueue<Job> = ConcurrentLinkedQueue(),
        // #658: when the slot's first outstanding upload was submitted (epoch ms): how long a dirty
        // overwrite, which has no never-uploaded row to carry the stamp, has been waiting.
        val enqueuedAtMs: Long = System.currentTimeMillis(),
    )
    private val uploadSlots = ConcurrentHashMap<String, UploadSlot>()

    // #658: transfer attempts that hold a transfer permit right now (the rest of the slots wait for
    // their path's turn, the daemon-wide budget, or a retry backoff).
    private val uploadsInFlight = AtomicInteger(0)

    // #658: the last measured size of the cache directory (epoch ms of the measurement), null = never
    // measured. Written by every pass that walks the cache; read by [cacheHealth].
    @Volatile private var cacheMeasuredBytes: Long? = null
    @Volatile private var cacheMeasuredAtMs: Long = 0L
    private val cacheMeasuring = AtomicBoolean(false)

    // Waiting-slot budget for the upload queue. Acquired by the submitting
    // caller (open_write / replay) and released only when the job actually
    // acquires a transfer permit — so the bound covers uploads waiting either
    // for their per-path turn or for the daemon-wide budget. A burst beyond
    // [uploadQueueDepth] suspends the submitter: back-pressure towards the
    // client instead of unbounded queue growth.
    private val queueSlots = Semaphore(uploadQueueDepth)

    // #450: when each path was last opened, created or written through this instance (epoch ms), for
    // the least-recently-used order. After a restart a path has no entry and the file's own access and
    // modification times stand in.
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val evictionMutex = Mutex()
    private val evictionRequested = AtomicBoolean(false)

    companion object {
        // Wire token for "an upload of the path is still in flight, retry"; the same literal
        // dehydrate's Busy reply puts on the wire.
        private const val BUSY_TOKEN = "busy"

        /** Default bound on uploads waiting to run (see [uploadQueueDepth]). */
        const val DEFAULT_UPLOAD_QUEUE_DEPTH = 256

        /** Default attempts per queued upload (see [maxUploadAttempts]). */
        const val DEFAULT_MAX_UPLOAD_ATTEMPTS = 3

        /** Delays preceding retries 2..N of a queued upload (see [uploadRetryDelaysMs]). */
        val DEFAULT_UPLOAD_RETRY_DELAYS_MS = listOf(2_000L, 10_000L)

        /**
         * Default idle window of the per-attempt stall watchdog (see [uploadStallTimeoutMs]).
         * Deliberately shorter than the client's 15-minute settle timeout, so the failed-attempt
         * path (and its `completed`) reaches a live client within one settle window.
         */
        const val DEFAULT_UPLOAD_STALL_TIMEOUT_MS: Long = 10L * 60 * 1000

        /** Default coalescing gap for `uploading` progress events (see [uploadProgressMinIntervalMs]). */
        const val DEFAULT_UPLOAD_PROGRESS_MIN_INTERVAL_MS = 400L

        /** #450: default hydration cache budget per profile, 20 GiB. */
        // The value of SyncConfig.DEFAULT_HYDRATION_CACHE_MAX_BYTES (:app:sync, which this module no longer
        // depends on, #560 U3); HydrationCacheDefaultTest in :app:cli pins that the two agree.
        const val DEFAULT_CACHE_MAX_BYTES: Long = 20L * 1024 * 1024 * 1024
        const val CACHE_ACCESS_GRACE_MS: Long = 60_000
        const val EVICTION_DELAY_MS: Long = 5_000

        /** #658: a status request re-walks the cache directory when the last measurement is older than this. */
        const val CACHE_MEASUREMENT_TTL_MS: Long = 10_000

        /** #658: a status request re-reads the upload queue from state.db when the last reading is older than this. */
        const val UPLOAD_SNAPSHOT_TTL_MS: Long = 1_000

        /** #658: how long a status request waits for the very first reading (it has nothing to serve until then). */
        const val UPLOAD_SNAPSHOT_WAIT_MS: Long = 1_000

        /** #658: how long a status request waits for a re-reading when it has a previous reading to serve. */
        const val UPLOAD_SNAPSHOT_REFRESH_WAIT_MS: Long = 100

        /** #493: default delay of the replay of rows whose last upload attempt failed (see [failedReplayDelayMs]). */
        const val DEFAULT_FAILED_REPLAY_DELAY_MS: Long = 10L * 60 * 1000

        // Temp files the engine stages beside their destination (`<name>.hydrating-<uuid>` for a
        // download); a crash leaves them behind.
        private const val STALE_TEMP_AGE_MS = 60L * 60 * 1000
    }

    private fun touch(path: String) {
        lastAccess[path] = System.currentTimeMillis()
    }

    // Whether the cache file of [path] lies inside the profile's cache folder: the engine's resolution refuses one
    // that would not (SecurityException). A verb that would read, write or delete that file answers invalid_path
    // instead, before anything is touched. A name the platform cannot hold at all (InvalidPathException) is left to
    // the verb, which reports it as before.
    private fun resolvesInsideCache(path: String): Boolean =
        try {
            mount.resolveCachePath(path)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: java.nio.file.InvalidPathException) {
            true
        }

    // A name this engine will not create: one the host's file system cannot hold (the cache keeps a file or folder of
    // that name; on Windows the Win32 rules), or one whose cache file would lie outside the cache folder.
    private fun refusedNewName(path: String): Boolean = mount.localNameIssue(path) != null || !resolvesInsideCache(path)

    override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult {
        if (!resolvesInsideCache(path)) return OpenResult.Failed(HydrationError.InvalidPath)
        val entry = stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.UnknownPath)
        touch(path)

        val cachePath = try {
            // Always emit Hydrating + Hydrated, even when MountEngine returns a warm cache
            // without downloading: subscribers should see a consistent event stream
            // regardless of cache state; the cache layer is an implementation detail of
            // MountEngine, not part of the Hydration SPI contract.
            _events.emit(HydrationEvent.Hydrating(path))
            val p = mount.ensureHydrated(path)
            val bytes = java.nio.file.Files.size(p)
            // Re-read the row after hydration: ensureHydrated persists the freshly
            // downloaded size as remoteSize (and a concurrent enumeration may also have
            // refreshed it), so comparing the cache against the pre-hydration snapshot
            // would spuriously trip the size guard when the remote changed size since the
            // last enumeration. Fall back to the snapshot if the row vanished.
            val current = stateDb.getEntry(path) ?: entry
            // Never hand the co-daemon a short/incomplete cache for a known-sized
            // file: a partial hydration would surface to the mount as a silent
            // 0-byte/short read, which a file-manager copy turns into a corrupt
            // 0-byte destination. Fail loudly (→ EIO, retryable) instead of serving
            // truncated content as success.
            val remoteExpectsFullFile = current.remoteId != null && !current.isFolder && current.remoteSize > 0
            if (remoteExpectsFullFile && bytes != current.remoteSize) {
                throw IllegalStateException(
                    "incomplete hydration for $path: cached $bytes of ${current.remoteSize} bytes",
                )
            }
            _events.emit(HydrationEvent.Hydrated(path, bytes))
            _events.emit(
                HydrationEvent.Completed(
                    path = path,
                    handleId = handleId,
                    direction = HydrationEvent.Completed.Direction.DOWNLOAD,
                    ok = true,
                ),
            )
            p
        } catch (e: Exception) {
            // A genuinely-gone read (provider download still not-found after the
            // #176 re-resolve) must surface a typed not_found token so the mount
            // maps it to ENOENT, not the catch-all EIO. A stored object shorter
            // than the size the drive reports (#536) is not a gone file: its own
            // token carries the numbers. Any other failure stays Generic (→ EIO).
            val err: HydrationError = downloadFailureOf(e, fallback = "download failed")
            _events.emit(HydrationEvent.Failed(path, err))
            _events.emit(
                HydrationEvent.Completed(
                    path = path,
                    handleId = handleId,
                    direction = HydrationEvent.Completed.Direction.DOWNLOAD,
                    ok = false,
                    error = err,
                ),
            )
            return OpenResult.Failed(err)
        }

        openSets.computeIfAbsent(connectionId) { ConcurrentHashMap() }[handleId] = path
        touch(path)
        return OpenResult.Ok(cachePath)
    }

    override suspend fun openForWrite(
        connectionId: String,
        handleId: String,
        path: String,
        cachePath: Path,
        baseEtag: String?,
    ): OpenResult {
        if (!resolvesInsideCache(path)) return OpenResult.Failed(HydrationError.InvalidPath)
        val entry = stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.UnknownPath)
        // The client hands the cache path back. Only a file inside the profile's cache folder is uploaded: the path
        // create / open_write_begin / open_read handed out, or another spelling of a file there (compared on real
        // paths, so a link cannot lead out). Anything else is refused before any state is touched or upload queued.
        if (!isInsideCacheFolder(mount.hydrationCacheDir(), cachePath)) {
            log.warn(
                "open_write of '{}' refused: its cache path '{}' is not inside the profile's hydration cache",
                forLogLine(path),
                forLogLine(cachePath.toString()),
            )
            return OpenResult.Failed(HydrationError.InvalidPath)
        }
        touch(path)

        // Excluded paths (exclude_patterns) are keep-local: the write is
        // accepted — the editor's bytes are real local content — but the
        // upload never runs. Refusing would break the editors and tools that
        // legitimately create *.tmp / ~$ scratch files through the mount;
        // running the upload would hit the engine's keep-local guard anyway
        // and answer hydrated, marking a file in sync that is not in the
        // cloud. The skipped event (not hydrating/hydrated) plus a Completed
        // carrying the excluded token tell the client both facts.
        if (mount.isExcludedPath(path)) {
            // The engine's keep-local branch uploads nothing but advances the row's
            // local watermark (last_synced); without it the co-daemon's recovery
            // scanner replays this file's open_write on every mount, forever.
            runCatching { mount.uploadFromCache(path, cachePath) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    log.warn("keep-local watermark update failed for {}: {}", path, e.message)
                }
            _events.emit(HydrationEvent.Skipped(path))
            _events.emit(
                HydrationEvent.Completed(
                    path = path,
                    handleId = handleId,
                    direction = HydrationEvent.Completed.Direction.UPLOAD,
                    ok = false,
                    error = HydrationError.Excluded,
                ),
            )
            return OpenResult.Ok(cachePath, excluded = true)
        }

        // Optimistic-concurrency guard (#434): refuse a write whose base etag no
        // longer matches the row's change-detection token BEFORE any upload runs —
        // the point is to not silently overwrite a newer remote version, so the
        // refusal must happen before the background upload can clobber it. The
        // comparison is against the ENGINE's row: a client holding a stale view
        // (its base etag predates the last enumeration) gets `conflict` and keeps
        // both copies. A row with no token (never uploaded, or a provider that
        // exposes none) cannot be guarded — there is no remote version to lose.
        //
        // The token is the provider's content hash, NOT a conditional-write etag:
        // OneDrive's If-Match requires the Graph eTag (which the engine does not
        // persist), and Internxt has no conditional PUT at all — so the token is
        // deliberately NOT forwarded to provider.upload. Forwarding a quickXor/sha256
        // hash as If-Match would 412 every guarded OneDrive replace and land the
        // keep-both rename path on every mount save.
        if (baseEtag != null && entry.remoteHash != null && entry.remoteHash != baseEtag) {
            return OpenResult.Failed(HydrationError.Conflict)
        }

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
            launchSerializedUpload(path, cachePath, handleId, baseEtag)
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
        launchSerializedUpload(path, cachePath, handleId, baseEtag)
        return OpenResult.Ok(cachePath)
    }

    // Submits a background upload for [path] from [cachePath] to the daemon's
    // upload queue. Same-path uploads queue FIFO (the per-path mutex in
    // [uploadSlots] is fair); different-path uploads run up to the daemon-wide
    // per-provider transfer budget shared with the sync engine
    // ([MountEngine.withTransferPermit]) — an Explorer copy burst can never
    // exceed the provider's cap, whatever path the transfers come from.
    //
    // Back-pressure: while [uploadQueueDepth] uploads are waiting (per-path
    // turn or transfer permit), this coroutine suspends in queueSlots.acquire
    // — the open_write caller waits instead of the queue growing unboundedly.
    //
    // Retry: a failed attempt is retried up to [maxUploadAttempts] with the
    // delays in [uploadRetryDelaysMs] between attempts (the permit is released
    // during the wait). Every failed attempt stamps the row's last_error_at
    // and emits failed with retry_scheduled, so the client can distinguish
    // "the daemon will retry" from "this upload is done failing"; the final
    // failure additionally WARN-logs. Durability across daemon restarts is
    // [replayPendingUploads] (the state.db row is the durable queue).
    //
    // Map cleanup: ConcurrentHashMap.compute() is used for BOTH the
    // increment-or-create (on launch) and the decrement-and-remove (in the
    // finally block) — atomic under the map's bin lock, so a slot is never
    // removed while another submitter is bumping its pending count.
    private suspend fun launchSerializedUpload(
        path: String,
        cachePath: Path,
        handleId: String,
        baseEtag: String?,
    ) {
        _events.emit(HydrationEvent.Queued(path))
        queueSlots.acquire()
        // Atomically create-or-get the slot and bump its pending count. The bin lock
        // held by compute() ensures that no concurrent finally-block can remove the
        // slot between the moment we decide to reuse it and the moment we increment.
        val slot = uploadSlots.compute(path) { _, s ->
            (s ?: UploadSlot(Mutex(), AtomicInteger(0))).also { it.pending.incrementAndGet() }
        }!!
        // ATOMIC start: a plain launch cancelled before its first dispatch never runs its
        // body, so the slot/permit bookkeeping in the finally blocks below would be skipped
        // (slot stuck busy, queue permit lost, no Completed for the handle). ATOMIC
        // guarantees the body is entered; the ensureActive() below then turns a pending
        // cancel into the normal cancelled path. ATOMIC is a delicate API; this is the case it exists for.
        @OptIn(DelicateCoroutinesApi::class)
        val worker = recoveryUploadScope.launch(start = CoroutineStart.ATOMIC) {
            // Emitted only after the slot is released (below): a client that reacts to
            // Completed by re-listing must already see pending_upload settled, not still
            // raised by the slot of the upload it was just told about.
            var completed: HydrationEvent.Completed? = null
            // Exactly-once release of this job's waiting slot: handed over to the
            // transfer-permit block inside runUploadWithRetries, or released here if
            // the job never got that far. A plain Boolean is safe — only this
            // coroutine touches it.
            var waitingSlotHeld = true
            // Coalesced progress: at most one `uploading` event per interval per
            // attempt. tryEmit — a progress callback must never suspend the
            // transfer on the event buffer.
            var lastProgressEmitNanos = 0L
            val minIntervalNanos = uploadProgressMinIntervalMs.coerceAtLeast(0) * 1_000_000
            val onProgress: (Long, Long) -> Unit = { done, total ->
                val now = System.nanoTime()
                if (minIntervalNanos == 0L || now - lastProgressEmitNanos >= minIntervalNanos) {
                    lastProgressEmitNanos = now
                    _events.tryEmit(HydrationEvent.Uploading(path, handleId, done, total))
                }
            }
            try {
                try {
                    slot.mutex.withLock {
                        try {
                            ensureActive()
                            completed = runUploadWithRetries(path, cachePath, handleId, baseEtag, onProgress) {
                                if (waitingSlotHeld) {
                                    queueSlots.release()
                                    waitingSlotHeld = false
                                }
                            }
                        } finally {
                            if (waitingSlotHeld) {
                                queueSlots.release()
                                waitingSlotHeld = false
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // cancelUpload (or daemon shutdown) aborted this submission
                    // while queued, mid-transfer, or in a retry backoff: the
                    // handle's correlation must end with a cause, not hang.
                    // tryEmit — a cancelled coroutine can no longer suspend.
                    _events.tryEmit(
                        HydrationEvent.Completed(
                            path = path,
                            handleId = handleId,
                            direction = HydrationEvent.Completed.Direction.UPLOAD,
                            ok = false,
                            error = HydrationError.Cancelled,
                        ),
                    )
                    throw e
                }
            } finally {
                // #319: decrement the CAPTURED slot — a concurrent rename may have
                // re-keyed it under the destination path (the running coroutine's
                // captured path is stale). Removal is by slot IDENTITY, not by path:
                // when this coroutine's upload was the last pending one, every map
                // entry still pointing at this slot is removed, wherever a rename
                // moved it; if a concurrent submitter re-raised the count (its
                // compute() found this slot before our decrement), the entry stays
                // and that submitter's own finally removes it at zero.
                val remaining = slot.pending.decrementAndGet()
                if (remaining == 0) {
                    uploadSlots.entries.removeIf { it.value === slot }
                }
            }
            // The path is no longer pinned by its upload: the budget may have been waiting for that.
            requestEviction()
            completed?.let { _events.emit(it) }
        }
        slot.jobs.add(worker)
    }

    // Runs one queued upload to completion: up to [maxUploadAttempts] transfer
    // attempts under the daemon-wide permit, emitting hydrating/hydrated (or
    // failed per attempt) and coalesced uploading progress. [baseEtag] is
    // forwarded to uploadFromCache for the upload-time convergence guard.
    // [onPermitAcquired] fires inside the permit block — the queue's waiting
    // slot is handed over exactly when the transfer actually starts. Each
    // attempt runs under the stall watchdog (runWatchedTransferAttempt): an
    // attempt with no provider progress for [uploadStallTimeoutMs] fails like
    // any transient failure, so even a lost wakeup cannot hold a path's slot
    // past the ladder (gkrost/unidrive#613).
    private suspend fun runUploadWithRetries(
        path: String,
        cachePath: Path,
        handleId: String,
        baseEtag: String?,
        onProgress: (Long, Long) -> Unit,
        onPermitAcquired: () -> Unit,
    ): HydrationEvent.Completed {
        // #493: the provider refused exactly these bytes before; asking again would be refused again.
        refusedEarlier(path, cachePath)?.let { reason ->
            val err = HydrationError.Generic("refused earlier: $reason")
            _events.emit(HydrationEvent.Failed(path, err, retryScheduled = false))
            log.info("upload of {} not attempted: the provider refused this content before ({})", path, reason)
            return HydrationEvent.Completed(path = path, handleId = handleId, direction = HydrationEvent.Completed.Direction.UPLOAD, ok = false, error = err)
        }
        var lastError: HydrationError = HydrationError.Generic("upload failed")
        for (attempt in 1..maxUploadAttempts) {
            try {
                runWatchedTransferAttempt(path, cachePath, baseEtag, onProgress, onPermitAcquired)
                val bytes = Files.size(cachePath)
                _events.emit(HydrationEvent.Hydrated(path, bytes))
                return HydrationEvent.Completed(
                    path = path,
                    handleId = handleId,
                    direction = HydrationEvent.Completed.Direction.UPLOAD,
                    ok = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: org.krost.unidrive.RemoteConflictException) {
                // The cloud copy changed between the row's token and the
                // transfer (upload-time convergence): not transient — a retry
                // would collide again. The client keeps both copies; surface
                // the conflict and stop.
                runCatching { stateDb.markUploadFailed(path, java.time.Instant.now()) }
                _events.emit(HydrationEvent.Failed(path, HydrationError.Conflict, retryScheduled = false))
                log.warn("upload of {} hit a remote conflict, not retrying: {}", path, e.message ?: "conflict")
                return HydrationEvent.Completed(
                    path = path,
                    handleId = handleId,
                    direction = HydrationEvent.Completed.Direction.UPLOAD,
                    ok = false,
                    error = HydrationError.Conflict,
                )
            } catch (e: org.krost.unidrive.PermanentUploadFailureException) {
                // #493: the provider refused the request itself; the same bytes and the same call would be refused again,
                // so the retry ladder would only hold a transfer slot through its whole backoff schedule.
                runCatching { stateDb.markUploadFailed(path, java.time.Instant.now()) }
                // Persisted with the content's stamp: the replay at the next start and a resubmission of the same bytes skip the
                // provider (#493). New content, or any rewrite of the row, clears it.
                refusalStamp(cachePath)?.let { stamp -> runCatching { stateDb.markUploadRefused(path, "$stamp|${e.message ?: "refused"}") } }
                val err = HydrationError.Generic(e.message ?: "upload refused")
                _events.emit(HydrationEvent.Failed(path, err, retryScheduled = false))
                log.warn("upload of {} refused by the provider, not retrying: {}", path, e.message ?: "upload refused")
                return HydrationEvent.Completed(
                    path = path,
                    handleId = handleId,
                    direction = HydrationEvent.Completed.Direction.UPLOAD,
                    ok = false,
                    error = err,
                )
            } catch (e: Exception) {
                runCatching { stateDb.markUploadFailed(path, java.time.Instant.now()) }
                // A vanished row (renamed away, unlinked, or reaped while queued) or a vanished cache copy: nothing a
                // later attempt can change. Retrying would only hold the path's slot (busy to dehydrate and
                // replace-rename) through the whole backoff schedule before reporting the same failure. (A remote
                // conflict, #434/#470, never reaches here: it has its own terminal branch above.)
                val gone = runCatching { stateDb.getEntry(path) }.getOrNull() == null || !Files.exists(cachePath)
                val err = HydrationError.Generic(e.message ?: "upload failed")
                lastError = err
                val retryScheduled = !gone && attempt < maxUploadAttempts
                _events.emit(HydrationEvent.Failed(path, err, retryScheduled = retryScheduled))
                if (retryScheduled) {
                    log.info(
                        "upload attempt {}/{} failed for {}: {}; retrying",
                        attempt, maxUploadAttempts, path, e.message,
                    )
                    delay(retryDelayMs(attempt))
                } else if (gone) {
                    log.warn("upload of {} abandoned: its row or cache copy is gone ({}), not retrying", path, e.message)
                    break
                } else {
                    log.warn(
                        "upload failed for {} after {} attempts, leaving the row failed (a daemon restart replays it): {}",
                        path, maxUploadAttempts, e.message ?: "upload failed",
                    )
                }
            }
        }
        return HydrationEvent.Completed(
            path = path,
            handleId = handleId,
            direction = HydrationEvent.Completed.Direction.UPLOAD,
            ok = false,
            error = lastError,
        )
    }

    /**
     * One transfer attempt under the stall watchdog (gkrost/unidrive#613): runs the
     * transfer-permit wait and the transfer itself, and cancels both when [uploadStallTimeoutMs]
     * elapses with no byte progress from the provider. The wait for the permit, the provider's
     * pre-transfer work (its identical-bytes check, its resume bookkeeping) and the transfer all
     * suspend with no timeout of their own — a single lost wakeup there held the path's slot for
     * the daemon's lifetime, with no provider request, no failure, no event. Every attempt is now
     * bounded: the watchdog fails the attempt with [UploadStalledException], a plain failure that
     * [runUploadWithRetries]' normal failed-attempt path handles (row stamped, `failed` with
     * retry_scheduled, the retry ladder, a `completed` at the end). Progress is what the provider
     * reports through [onProgress]; a slow transfer that keeps reporting bytes never trips it.
     * [uploadStallTimeoutMs] of 0 or less runs the attempt unwrapped (tests).
     */
    private suspend fun runWatchedTransferAttempt(
        path: String,
        cachePath: Path,
        baseEtag: String?,
        onProgress: (Long, Long) -> Unit,
        onPermitAcquired: () -> Unit,
    ) {
        if (uploadStallTimeoutMs <= 0) {
            mount.withTransferPermit {
                onPermitAcquired()
                holdingPermit {
                    _events.emit(HydrationEvent.Hydrating(path))
                    mount.uploadMountWriteFromCache(path, cachePath, baseEtag, onProgress)
                }
            }
            return
        }
        // Every provider progress callback kicks the watchdog; conflated so a
        // kick landing while the watcher is between receives is never lost. The
        // caller's own coalescing stays downstream of the kick.
        val kicks = Channel<Unit>(Channel.CONFLATED)
        val kicked: (Long, Long) -> Unit = { done, total ->
            kicks.trySend(Unit)
            onProgress(done, total)
        }
        coroutineScope {
            val watcher = launch {
                while (true) {
                    try {
                        withTimeout(uploadStallTimeoutMs) { kicks.receive() }
                    } catch (e: TimeoutCancellationException) {
                        throw UploadStalledException(
                            "no provider request and no byte progress for ${uploadStallTimeoutMs / 1000} s (stall watchdog)",
                        )
                    }
                }
            }
            try {
                mount.withTransferPermit {
                    onPermitAcquired()
                    holdingPermit {
                        _events.emit(HydrationEvent.Hydrating(path))
                        mount.uploadMountWriteFromCache(path, cachePath, baseEtag, kicked)
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    // #658: [block] runs inside a transfer permit; it counts as one upload in flight.
    private suspend fun <T> holdingPermit(block: suspend () -> T): T {
        uploadsInFlight.incrementAndGet()
        try {
            return block()
        } finally {
            uploadsInFlight.decrementAndGet()
        }
    }

    // The failure the stall watchdog raises. Deliberately NOT a CancellationException:
    // it must fail the attempt (and run the failed-attempt path), not cancel the worker.
    private class UploadStalledException(message: String) : RuntimeException(message)

    private fun retryDelayMs(afterAttempt: Int): Long =
        uploadRetryDelaysMs.getOrNull(afterAttempt - 1)
            ?: uploadRetryDelaysMs.lastOrNull()
            ?: 0L

    /**
     * Re-enqueue uploads for every hydrated file row whose content has never
     * reached the cloud (`local:` rows with a live cache copy). Called once at
     * daemon start: the engine-side complement of the co-daemon's recovery-<n>
     * scanner, and the durability net for uploads that were queued or failing
     * when the daemon last stopped — a restart alone drains the backlog even
     * with no client connected. Skips excluded (keep-local) and out-of-scope
     * rows, and rows whose cache copy is gone. Returns the number of uploads
     * enqueued at once; every enqueued upload goes through the same per-path
     * serialization and transfer budget as client-submitted ones.
     *
     * #493: a row whose last attempt failed (last_error_at set) is replayed
     * [failedReplayDelayMs] later instead: replayed at once, two uploads that
     * keep failing held both transfer slots for their whole retry ladder
     * (about 10 minutes) after every start, and nothing else uploaded. When its
     * turn comes the row is replayed only if it is still pending, still
     * replayable and no upload of it is under way (a client may have written
     * it meanwhile).
     */
    suspend fun replayPendingUploads(): Int {
        var queued = 0
        var deferred = 0
        var refused = 0
        for (path in stateDb.pendingUploadPaths()) {
            // #678: the walk between records is blocking file-system work with no suspension
            // point, so a stop is only honoured if the loop itself checks for it.
            coroutineContext.ensureActive()
            if (!replayable(path)) continue
            val cachePath = mount.resolveCachePath(path)
            if (refusedEarlier(path, cachePath) != null) { refused++; continue } // #493: not replayed at every start
            if (failedReplayDelayMs > 0 && stateDb.getEntry(path)?.lastErrorAt != null) {
                deferred++
                val handleId = "engine-replay-late-$deferred"
                recoveryUploadScope.launch {
                    delay(failedReplayDelayMs)
                    val entry = stateDb.getEntry(path)
                    if (entry == null || entry.remoteId != null || !entry.isHydrated) return@launch // gone or uploaded
                    if (uploadSlots.containsKey(path) || !replayable(path)) return@launch
                    if (refusedEarlier(path, mount.resolveCachePath(path)) != null) return@launch // refused meanwhile
                    launchSerializedUpload(path, mount.resolveCachePath(path), handleId, baseEtag = null)
                }
                continue
            }
            launchSerializedUpload(path, cachePath, "engine-replay-${queued + 1}", baseEtag = null)
            queued++
        }
        // #605 (U6 cutover): dirty overwrites. A row whose bytes reached the cloud at least once is
        // replayed when its cache copy no longer matches the row's baseline — a write that never
        // reached its upload before the daemon stopped. A mount profile's cache copy is the row's
        // only local file, so without this the edit would sit in the cache until a mount client's
        // recovery scanner happened to replay it (and never, if none came). The #493 rules are the
        // same as for pending rows: a failed row waits, and a refusal for exactly the content the
        // cache holds now is not retried at every start.
        var dirty = 0
        var dirtyDeferred = 0
        for (path in stateDb.uploadedMountRows()) {
            coroutineContext.ensureActive() // #678: see the pending loop above
            if (!replayable(path)) continue
            if (uploadSlots.containsKey(path)) continue
            val entry = stateDb.getEntry(path) ?: continue
            if (entry.remoteId == null || !entry.isHydrated) continue
            val cachePath = mount.resolveCachePath(path)
            val stamp = refusalStamp(cachePath) ?: continue
            val baselineMtime = entry.localMtime ?: continue
            val baselineSize = entry.localSize ?: continue
            if (stamp == "$baselineMtime|$baselineSize") continue // clean: the cache copy is the baseline
            if (refusedEarlier(path, cachePath) != null) { refused++; continue }
            if (failedReplayDelayMs > 0 && entry.lastErrorAt != null) {
                dirtyDeferred++
                val handleId = "engine-dirty-late-$dirtyDeferred"
                recoveryUploadScope.launch {
                    delay(failedReplayDelayMs)
                    val now = stateDb.getEntry(path) ?: return@launch
                    if (now.remoteId == null || !now.isHydrated) return@launch
                    if (uploadSlots.containsKey(path) || !replayable(path)) return@launch
                    val cp = mount.resolveCachePath(path)
                    if (refusedEarlier(path, cp) != null) return@launch
                    launchSerializedUpload(path, cp, handleId, baseEtag = now.remoteHash)
                }
                continue
            }
            launchSerializedUpload(path, cachePath, "engine-dirty-${dirty + 1}", baseEtag = entry.remoteHash)
            dirty++
        }
        if (deferred > 0) {
            log.info(
                "replay of {} upload(s) whose last attempt failed deferred by {} s (#493)",
                deferred,
                failedReplayDelayMs / 1000,
            )
        }
        if (refused > 0) log.info("not replaying {} upload(s) the provider refused for their current content (#493)", refused)
        if (dirty > 0) log.info("replayed {} dirty overwrite(s) the daemon missed before it stopped (#605)", dirty)
        if (dirtyDeferred > 0) log.info("replay of {} dirty overwrite(s) whose last attempt failed deferred by {} s (#493)", dirtyDeferred, failedReplayDelayMs / 1000)
        return queued + dirty
    }

    private fun replayable(path: String): Boolean =
        !mount.isExcludedPath(path) &&
            !mount.isOutOfScope(path) &&
            runCatching { Files.exists(mount.resolveCachePath(path)) }
                .getOrElse {
                    log.warn("#526: not replaying pending upload with an invalid local name: {}", path)
                    false
                }

    // #493: <cache mtime ms>|<cache size> of the bytes an upload sends; null when the cache copy is gone.
    private fun refusalStamp(cachePath: Path): String? =
        runCatching { "${Files.getLastModifiedTime(cachePath).toMillis()}|${Files.size(cachePath)}" }.getOrNull()

    // The reason the provider refused this path's upload, when the refusal was for exactly the content the cache holds now.
    private fun refusedEarlier(path: String, cachePath: Path): String? {
        val refusal = runCatching { stateDb.uploadRefusal(path) }.getOrNull() ?: return null
        val stamp = refusalStamp(cachePath) ?: return null
        return if (refusal.startsWith("$stamp|")) refusal.substring(stamp.length + 1) else null
    }

    override suspend fun cancelUpload(path: String): Boolean {
        val slot = uploadSlots[path] ?: return false
        // Prune finished workers first so a stale entry can never make the
        // reply claim an abort that already completed on its own.
        slot.jobs.removeIf { it.isCompleted }
        var aborted = false
        for (job in slot.jobs) {
            if (!job.isCompleted) {
                job.cancel()
                aborted = true
            }
        }
        return aborted
    }

    override suspend fun closeHandle(connectionId: String, handleId: String) {
        openSets[connectionId]?.remove(handleId)
        requestEviction()
    }

    // ── #450 cache budget ───────────────────────────────────────────────────────────────────────

    /** What one [evictCache] pass found and did. */
    data class CacheEvictionReport(
        val budgetBytes: Long,
        val bytesBefore: Long,
        val bytesAfter: Long,
        val evictedFiles: Int,
    )

    // One pass per burst of triggers, scheduled [evictionDelayMs] after the first. A no-op without a budget.
    private fun requestEviction() {
        if (cacheMaxBytes <= 0) return
        if (!evictionRequested.compareAndSet(false, true)) return
        recoveryUploadScope.launch {
            try {
                if (evictionDelayMs > 0) delay(evictionDelayMs)
                // Re-arm before the walk: a trigger that arrives during it must schedule another pass.
                evictionRequested.set(false)
                evictCache()
            } catch (_: Exception) {
                // Best effort: a failed pass leaves the cache over budget until the next trigger.
            } finally {
                evictionRequested.set(false)
            }
        }
    }

    /**
     * Daemon start: drop what a stopped daemon left behind (staging temp files of an interrupted
     * download or copy, older than an hour) and bring the cache under its budget, which also clears the
     * cache copies of synced files that were read through the mount in earlier runs.
     */
    suspend fun sweepCache(): CacheEvictionReport {
        val job = coroutineContext[Job]
        val dir = mount.hydrationCacheDir()
        if (Files.isDirectory(dir)) {
            val cutoff = System.currentTimeMillis() - STALE_TEMP_AGE_MS
            try {
                Files.walk(dir).use { stream ->
                    val paths = stream.iterator()
                    while (paths.hasNext()) {
                        job?.ensureActive()
                        val path = paths.next()
                        cacheScanCheckpoint()
                        if (!Files.isRegularFile(path) || !isStagingTemp(path.fileName.toString())) continue
                        val stale = runCatching { Files.getLastModifiedTime(path).toMillis() < cutoff }.getOrDefault(false)
                        if (stale) runCatching { Files.deleteIfExists(path) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort startup cleanup; inaccessible cache entries are left in place.
            }
        }
        return evictCache()
    }

    private fun isStagingTemp(name: String): Boolean = name.contains(".hydrating-") || (name.startsWith(".ud-serve-") && name.endsWith(".tmp"))

    /** Current size of the cache directory in bytes (every regular file, protected or not). */
    fun cacheSizeBytes(): Long = listCacheFiles().sumOf { it.size }

    private class CacheFile(val path: String, val file: Path, val size: Long, val lastUsed: Long)

    private fun listCacheFiles(checkCancelled: () -> Unit = {}): List<CacheFile> {
        val dir = mount.hydrationCacheDir()
        if (!Files.isDirectory(dir)) return emptyList()
        val result = mutableListOf<CacheFile>()
        try {
            Files.walk(dir).use { stream ->
                val paths = stream.iterator()
                while (paths.hasNext()) {
                    checkCancelled()
                    val f = paths.next()
                    if (!Files.isRegularFile(f)) continue
                    try {
                        val attrs = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes::class.java)
                        val path = "/" + dir.relativize(f).toString().replace('\\', '/')
                        val fileTime = maxOf(attrs.lastModifiedTime().toMillis(), attrs.lastAccessTime().toMillis())
                        result += CacheFile(path, f, attrs.size(), lastAccess[path] ?: fileTime)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A disappearing or unreadable cache file is skipped, as before.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Preserve best-effort cache accounting when the directory cannot be read.
        }
        return result
    }

    // The checks only this layer can make: an open handle, a queued or in-flight upload (#301, #318),
    // and the access grace window.
    private fun inUse(path: String): Boolean =
        openSets.values.any { it.containsValue(path) } ||
            uploadSlots.containsKey(path) ||
            (lastAccess[path]?.let { System.currentTimeMillis() - it < cacheAccessGraceMs } ?: false)

    /**
     * #450: bring the cache directory under [cacheMaxBytes], least recently used first, copies that are
     * cheapest first. A file is evicted only if the engine
     * vouches for it ([MountEngine.cacheDisposition]: it holds nothing the cloud
     * has no local copy) and nothing here uses it: no open handle, no queued or in-flight upload
     * (also re-checked by the engine's own lock at deletion), not accessed within the grace window.
     * Files without a row (an upload target that was renamed away, #319), unfinished creates, failed
     * uploads and modified copies are never touched, so the cache can stay over budget.
     */
    suspend fun evictCache(): CacheEvictionReport =
        evictionMutex.withLock {
            val job = coroutineContext[Job]
            val files = listCacheFiles { job?.ensureActive() }
            val before = files.sumOf { it.size }
            recordCacheSize(before)
            if (cacheMaxBytes <= 0 || before <= cacheMaxBytes) return@withLock CacheEvictionReport(cacheMaxBytes, before, before, 0)
            var total = before
            var evicted = 0
            val disposable = mutableListOf<CacheFile>()

            suspend fun evict(f: CacheFile) {
                if (inUse(f.path)) return
                val freed = mount.evictCacheCopy(f.path) { _events.tryEmit(HydrationEvent.Dehydrated(it)) }
                if (freed != null) {
                    total -= freed
                    evicted++
                }
            }

            for (f in files.sortedBy { it.lastUsed }) {
                job?.ensureActive()
                if (total <= cacheMaxBytes) break
                if (inUse(f.path)) continue
                when (mount.cacheDisposition(f.path)) {
                    CacheDisposition.DISPOSABLE -> disposable += f
                    CacheDisposition.PROTECTED -> {}
                }
            }
            for (f in disposable) {
                job?.ensureActive()
                if (total <= cacheMaxBytes) break
                evict(f)
            }
            recordCacheSize(total)
            CacheEvictionReport(cacheMaxBytes, before, total, evicted)
        }

    // ── #658 health numbers for daemon.status ───────────────────────────────────────────────────

    /**
     * The upload queue as `daemon.status.uploads` reports it.
     *  - [pending]: paths whose bytes are not in the cloud yet and that an upload will take: the
     *    never-uploaded (`local:`) rows plus every path holding an upload slot (a dirty overwrite of an
     *    uploaded row has none of the former). Queued, in flight and failed-awaiting-replay all count,
     *    and so do the start-up replay's own uploads. Keep-local (excluded) and out-of-scope paths do
     *    not: no upload will ever take them.
     *  - [inFlight]: transfer attempts holding a transfer permit right now (a subset of [pending]).
     *  - [failed]: pending never-uploaded rows with a failed attempt on record (a subset of [pending]).
     *  - [oldestPendingAgeMs]: how long the oldest pending path has waited, null when nothing is pending.
     *    A never-uploaded row waits since it was written; a dirty overwrite since its upload was submitted.
     */
    data class UploadHealth(
        val pending: Int,
        val inFlight: Int,
        val failed: Int,
        val oldestPendingAgeMs: Long?,
    )

    /** One read of the rows and slots; [oldestSinceMs] is the stamp [UploadHealth.oldestPendingAgeMs] is measured from. */
    private class UploadSnapshot(
        val pending: Int,
        val inFlight: Int,
        val failed: Int,
        val oldestSinceMs: Long?,
        val takenAtMs: Long,
    ) {
        fun health(nowMs: Long) =
            UploadHealth(pending, inFlight, failed, oldestSinceMs?.let { (nowMs - it).coerceAtLeast(0) })
    }

    private fun takeUploadSnapshot(): UploadSnapshot {
        val pendingPaths = HashSet<String>()
        var failed = 0
        var oldestSinceMs: Long? = null

        fun since(atMs: Long) {
            oldestSinceMs = oldestSinceMs?.let { minOf(it, atMs) } ?: atMs
        }
        for (row in stateDb.pendingUploadRows()) {
            if (mount.isExcludedPath(row.path) || mount.isOutOfScope(row.path)) continue
            pendingPaths += PathNormalizer.nfc(row.path)
            if (row.lastErrorAt != null) failed++
            since(row.lastSynced.toEpochMilli())
        }
        // Slots without such a row: dirty overwrites. A slot of a row counted above adds nothing.
        for ((path, slot) in uploadSlots) {
            if (mount.isExcludedPath(path) || mount.isOutOfScope(path)) continue
            if (pendingPaths.add(PathNormalizer.nfc(path))) since(slot.enqueuedAtMs)
        }
        return UploadSnapshot(pendingPaths.size, uploadsInFlight.get(), failed, oldestSinceMs, System.currentTimeMillis())
    }

    /**
     * Reads state.db now and blocks while another thread holds it (an enumeration saving its result
     * does, for as long as the save takes). The daemon's status request uses [uploadHealthSnapshot]
     * instead.
     *
     * @param nowMs the clock the age is measured on (a seam for tests).
     */
    fun uploadHealth(nowMs: Long = System.currentTimeMillis()): UploadHealth = takeUploadSnapshot().health(nowMs)

    private val uploadSnapshot = java.util.concurrent.atomic.AtomicReference<UploadSnapshot?>(null)
    private val uploadSnapshotRefresh = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    // Single flight: the running refresh, or a new one on the IO dispatcher.
    private fun refreshUploadSnapshot(): Job {
        while (true) {
            val running = uploadSnapshotRefresh.get()
            if (running != null && running.isActive) return running
            val job = recoveryUploadScope.launch(start = CoroutineStart.LAZY) {
                try {
                    uploadSnapshot.set(withContext(Dispatchers.IO) { takeUploadSnapshot() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("upload health unavailable: {}", e.message)
                }
            }
            if (uploadSnapshotRefresh.compareAndSet(running, job)) {
                job.start()
                return job
            }
            job.cancel()
        }
    }

    /**
     * The upload queue for `daemon.status`, without ever waiting on state.db for long: a status reply
     * must not stall behind an enumeration's save (which holds the database for as long as it takes).
     * Reads at most once per [UPLOAD_SNAPSHOT_TTL_MS]; the first reading is waited for up to [waitMs], a
     * later one only briefly ([UPLOAD_SNAPSHOT_REFRESH_WAIT_MS]), after which the previous reading is
     * served with its age carried forward to [nowMs]. Null while there has
     * never been a reading: unknown, not "nothing pending".
     */
    suspend fun uploadHealthSnapshot(
        nowMs: Long = System.currentTimeMillis(),
        waitMs: Long = UPLOAD_SNAPSHOT_WAIT_MS,
    ): UploadHealth? {
        val last = uploadSnapshot.get()
        if (last == null || System.currentTimeMillis() - last.takenAtMs >= UPLOAD_SNAPSHOT_TTL_MS) {
            // With a previous reading to serve there is little to wait for; without one, wait the full time.
            kotlinx.coroutines.withTimeoutOrNull(if (last == null) waitMs else minOf(waitMs, UPLOAD_SNAPSHOT_REFRESH_WAIT_MS)) {
                refreshUploadSnapshot().join()
            }
        }
        return uploadSnapshot.get()?.health(nowMs)
    }

    /**
     * The cache as `daemon.status.cache` reports it: [bytes] is the size the last walk of the cache
     * directory found (every regular file, protected or not: what [evictCache] counts), null until one
     * has run; [budgetBytes] is the configured budget, null when there is none (unlimited).
     */
    data class CacheHealth(
        val bytes: Long?,
        val budgetBytes: Long?,
    )

    /** What is known now, without touching the file system. See [requestCacheMeasurement]. */
    fun cacheHealth(): CacheHealth = CacheHealth(cacheMeasuredBytes, cacheMaxBytes.takeIf { it > 0 })

    private fun recordCacheSize(bytes: Long) {
        cacheMeasuredBytes = bytes
        cacheMeasuredAtMs = System.currentTimeMillis()
    }

    /**
     * Walks the cache directory off the caller's thread to refresh [cacheHealth], unless a walk is
     * already running or the last measurement is younger than [staleAfterMs]. Fire and forget: a
     * status request calls this and answers with what it has, so a large cache never delays a reply.
     */
    fun requestCacheMeasurement(staleAfterMs: Long = CACHE_MEASUREMENT_TTL_MS) {
        if (System.currentTimeMillis() - cacheMeasuredAtMs < staleAfterMs) return
        if (!cacheMeasuring.compareAndSet(false, true)) return
        recoveryUploadScope.launch {
            try {
                recordCacheSize(withContext(Dispatchers.IO) { listCacheFiles().sumOf { it.size } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("cache measurement failed: {}", e.message)
            } finally {
                cacheMeasuring.set(false)
            }
        }
    }

    // #301: whether a background upload of [path] is queued or in flight. The
    // engine's enumerate-reap consults this (via the engine's uploadInFlight hook,
    // wired by app:cli) before evicting a hydration-cache file, so a queued edit's
    // only copy is never reaped out from under its upload.
    fun hasUploadSlot(path: String): Boolean = uploadSlots.containsKey(path)

    override suspend fun hydrate(path: String): HydrateResult {
        if (!resolvesInsideCache(path)) return HydrateResult.Failed(HydrationError.InvalidPath)
        touch(path)
        return try {
            _events.emit(HydrationEvent.Hydrating(path))
            val cachePath = mount.ensureHydrated(path)
            val bytes = java.nio.file.Files.size(cachePath)
            _events.emit(HydrationEvent.Hydrated(path, bytes))
            HydrateResult.Ok
        } catch (e: Exception) {
            val err = downloadFailureOf(e, fallback = "hydrate failed")
            _events.emit(HydrationEvent.Failed(path, err))
            HydrateResult.Failed(err)
        }
    }
    override suspend fun dehydrate(path: String): DehydrateResult {
        if (!resolvesInsideCache(path)) return DehydrateResult.Failed(HydrationError.InvalidPath)
        val entry = stateDb.getEntry(path)
            ?: return DehydrateResult.Failed(HydrationError.UnknownPath)

        // Check the open-set across ALL connections
        val anyOpen = openSets.values.any { perConn -> perConn.containsValue(path) }
        if (anyOpen) return DehydrateResult.Busy

        // #301: refuse while an upload of this path is queued or in flight — the
        // FUSE handle is closed (so the open-set is empty) but the bytes have not
        // landed on the remote yet; deleting the cache here destroyed them
        // everywhere. Busy tells the client to retry once the upload's completed
        // event has arrived.
        if (uploadSlots.containsKey(path)) return DehydrateResult.Busy

        // #301/#136: a pending upload (remoteId == null && isHydrated) has NO remote
        // copy at all — the cache is the only copy of the file. Dehydrate is
        // meaningless for it until the upload lands (which flips remoteId), so
        // refuse rather than destroy the bytes.
        if (entry.isPendingUpload) return DehydrateResult.Busy

        return try {
            val cachePath = mount.resolveCachePath(path)
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
                    // #524: while the upload is pending the cache file is the only copy
                    // and the upload sends ITS size — the row (written when the copy
                    // began) often still records 0 for both remote and local size, and a
                    // 0-byte file on the wire reads as data loss and invites a delete.
                    // One stat per pending row; a resolve/stat failure (unreadable name,
                    // no cache file) falls back to the recorded sizes.
                    val pendingUpload = e.remoteId == null || uploadSlots.containsKey(e.path)
                    val size = reportedSize(e, pendingUpload)
                    ListResult.Entry(
                        path = e.path,
                        size = size,
                        mtimeEpochMillis = e.localMtime ?: e.lastSynced.toEpochMilli(),
                        isHydrated = e.isHydrated,
                        isFolder = e.isFolder,
                        remoteModifiedEpochMillis = e.remoteModified?.toEpochMilli(),
                        remoteId = e.remoteId,
                        etag = e.remoteHash,
                        // toSyncEntry surfaces a `local:` synthetic remote_id as null,
                        // so a null here means "never uploaded". An edit of a file that
                        // already has a remote id is owed to the cloud from open_write until
                        // its upload lands: that window is exactly the upload slot's lifetime.
                        // last_error_at marks the last attempt as failed (cleared by a later
                        // successful upload).
                        // #136: deliberately BROADER than SyncEntry.isPendingUpload (the
                        // UD-901 predicate) — this wire flag must also cover a remote-backed
                        // file whose cached edit is still queued in an upload slot, which
                        // the predicate (remoteId == null) cannot see.
                        pendingUpload = pendingUpload,
                        hasError = e.lastErrorAt != null,
                        excluded = mount.isExcludedPath(e.path),
                    )
                },
            )
        } catch (e: Exception) {
            ListResult.Failed(HydrationError.Generic(e.message ?: "list failed"))
        }
    }

    /**
     * The wire size of a listed row: the recorded sizes, except for a pending
     * upload whose cache file holds the bytes (a folder never has a cache file).
     * The recorded size is the fallback for every stat failure — [list] must not
     * fail a whole directory because one row's name will not resolve (#526 class).
     */
    private fun reportedSize(
        e: org.krost.unidrive.sync.model.SyncEntry,
        pendingUpload: Boolean,
    ): Long {
        val recorded = (if (e.isHydrated) (e.localSize ?: e.remoteSize) else e.remoteSize).coerceAtLeast(0L)
        if (!pendingUpload || e.isFolder) return recorded
        return runCatching {
            Files.size(mount.resolveCachePath(e.path)).coerceAtLeast(0L)
        }.getOrDefault(recorded)
    }

    override suspend fun mkdir(path: String): MkdirResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        if (refusedNewName(normalised)) return MkdirResult.Failed(HydrationError.InvalidPath)
        // Scope guard: a folder created outside the profile's sync_path set
        // would land in the cloud but never show in the mounted view (the view
        // only lists the scope). Refuse before touching the provider.
        if (mount.isOutOfScope(normalised)) return MkdirResult.Failed(HydrationError.OutOfScope)
        return runCatching {
            _events.emit(HydrationEvent.Hydrating(normalised))
            mount.createRemoteFolder(normalised)
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
            ?: return UnlinkResult.Failed(HydrationError.UnknownPath)
        if (entry.isFolder) return UnlinkResult.PathIsFolder

        // WB-3 (#87): the file's own upload is queued or in flight — deleting the row and the cache
        // copy under it would orphan the cloud copy the upload is about to create (the row is gone
        // when the upload lands). Answer busy, the same refusal dehydrate has given since #301; the
        // client cancels the upload (cancelUpload) and retries, or retries after the completed event.
        if (hasUploadSlot(normalised)) return UnlinkResult.Busy

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
            val ghost = try {
                mount.remoteItemOrNull(normalised)
            } catch (e: Exception) {
                // Transient remote-probe failure: fail the unlink rather than
                // hard-delete the row and orphan a ghost's cloud copy.
                return UnlinkResult.Failed(HydrationError.Generic(e.message ?: "remote probe failed"))
            }
            if (ghost != null && !ghost.isFolder) {
                return runCatching {
                    mount.deleteRemote(normalised)
                    discardStagedUploadBestEffort(normalised)
                    evictCacheFile(normalised)
                    UnlinkResult.Ok
                }.getOrElse { e ->
                    UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
                }
            }
            return runCatching {
                runCatching {
                    java.nio.file.Files.deleteIfExists(mount.resolveCachePath(normalised))
                }
                // WB-3 (#87): the staged encrypted copy of a failed upload is the only other copy of
                // the content — the user deleted the file, so it goes too, instead of staying in the
                // resume directory for days.
                discardStagedUploadBestEffort(normalised)
                stateDb.deleteEntry(normalised)
                UnlinkResult.Ok
            }.getOrElse { e ->
                UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
            }
        }

        return runCatching {
            mount.deleteRemote(normalised)
            // A tracked file whose last edit's upload failed has a staged copy too.
            discardStagedUploadBestEffort(normalised)
            evictCacheFile(normalised)
            UnlinkResult.Ok
        }.getOrElse { e ->
            UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
        }
    }

    // WB-3 (#87): the staged copy goes with a delete, but failing to remove it must not fail a
    // delete that already happened (the cloud copy is trashed, or the row is about to go): the
    // resume store's TTL GC is the backstop.
    private suspend fun discardStagedUploadBestEffort(path: String) {
        try {
            mount.discardStagedUpload(path)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("#87: could not discard the staged upload copy of {}: {}: {}", path, e.javaClass.simpleName, e.message)
        }
    }

    override suspend fun rmdir(path: String): RmdirResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        val entry = stateDb.getEntry(normalised)
            ?: return RmdirResult.Failed(HydrationError.UnknownPath)
        if (!entry.isFolder) return RmdirResult.PathIsFile

        // WB-3 (#87): the delete tombstones every row below the folder and evicts its cache tree.
        // An upload queued or in flight for a file below it would read its cache copy while it is
        // evicted and land its cloud copy into a trashed folder. Answer busy, as unlink does.
        val below = if (normalised == "/") "/" else "$normalised/"
        if (uploadSlots.keys.any { it.startsWith(below) }) return RmdirResult.Busy

        return runCatching {
            mount.deleteRemote(normalised)
            evictCacheTree(normalised)
            RmdirResult.Ok
        }.getOrElse { e ->
            // Typed signal first: a provider that raises FolderNotEmptyException
            // (directly or as the cause of a wrapping exception) maps to ENOTEMPTY
            // without depending on the provider's message text. The substring
            // fallback covers providers that have not yet been wired to throw the
            // typed exception — their current wordings stay pinned by
            // rmdir_detects_provider_not_empty_substring.
            if (e is FolderNotEmptyException || e.cause is FolderNotEmptyException) {
                return@getOrElse RmdirResult.NotEmpty
            }
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
        val oldCache = mount.resolveCachePath(oldNorm)
        val newCache = mount.resolveCachePath(newNorm)
        try {
            Files.createDirectories(newCache.parent)
            Files.move(oldCache, newCache, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: NoSuchFileException) {
            // Cache file absent — nothing to move; the state.db repath suffices.
        }
    }

    // Evict a single file's hydration-cache copy after a successful unlink.
    // Idempotent: a missing cache file (never hydrated) is a no-op, and any
    // IO failure is swallowed — the cloud delete already committed, so a stale
    // cache byte is a disk-space concern, never a reason to fail the unlink.
    private fun evictCacheFile(path: String) {
        runCatching { Files.deleteIfExists(mount.resolveCachePath(path)) }
    }

    // Recursively evict a folder's hydration-cache subtree after a successful
    // rmdir. Same idempotency contract as evictCacheFile: a missing subtree is
    // a no-op and a partial failure must not abort the op (the cloud delete
    // already succeeded). Deletes children before parents so the directory
    // empties before it is removed.
    private fun evictCacheTree(path: String) {
        runCatching {
            val root = mount.resolveCachePath(path)
            if (!Files.exists(root)) return
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p ->
                    runCatching { Files.deleteIfExists(p) }
                }
            }
        }
    }

    private fun prepareEmptyCache(path: String): java.nio.file.Path {
        val cachePath = mount.resolveCachePath(path)
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
        if (!resolvesInsideCache(normalised)) return OpenResult.Failed(HydrationError.InvalidPath)
        // Scope guard: same rationale as mkdir — a truncate outside the
        // profile's sync_path set would touch cloud data the view never shows.
        if (mount.isOutOfScope(normalised)) return OpenResult.Failed(HydrationError.OutOfScope)
        val entry = stateDb.getEntry(normalised)
            ?: return OpenResult.Failed(HydrationError.UnknownPath)
        if (entry.isFolder) return OpenResult.Failed(HydrationError.Generic("path_is_folder"))
        // #450: a bare truncate (handleId null) registers no open-set entry, so the
        // access-grace window of the eviction pass is the only thing standing between
        // this cache file and a concurrent budget eviction while the write runs.
        touch(normalised)
        return try {
            val cachePath = prepareEmptyCache(normalised)
            // When a live handle id is provided (O_TRUNC open), register it in
            // the connection's open-set so dehydrate/busy-checks see the file as
            // open.  One-shot callers (setattr bare-truncate) pass null → no
            // registration, matching the existing no-spurious-close_handle contract.
            if (handleId != null) {
                openSets.computeIfAbsent(connectionId) { ConcurrentHashMap() }[handleId] = normalised
            }
            OpenResult.Ok(cachePath, excluded = mount.isExcludedPath(normalised))
        } catch (e: Exception) {
            OpenResult.Failed(HydrationError.Generic(e.message ?: "open_write_begin failed"))
        }
    }

    override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        if (refusedNewName(normalised)) return CreateResult.Failed(HydrationError.InvalidPath)
        val mutex = createMutexes.computeIfAbsent(normalised) { Mutex() }
        return mutex.withLock {
            // Scope guard: a file created outside the profile's sync_path set
            // would upload to the cloud but never show in the mounted view.
            if (mount.isOutOfScope(normalised)) return@withLock CreateResult.Failed(HydrationError.OutOfScope)

            if (stateDb.getEntry(normalised) != null) return@withLock CreateResult.PathExists

            // Parent must exist as a folder row (root "/" / "" is implicit and
            // always considered present).
            val parent = normalised.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotEmpty()) {
                val parentEntry = stateDb.getEntry(parent)
                    ?: return@withLock CreateResult.ParentNotFound
                if (!parentEntry.isFolder) return@withLock CreateResult.ParentNotFound
            }

            // Excluded names are keep-local: the row and cache file are still
            // created (the file exists locally and must be served), but the
            // reply carries excluded so the client knows the content will never
            // reach the cloud.
            val excluded = mount.isExcludedPath(normalised)

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
                // #450: the bytes that follow are the only copy until the upload lands.
                touch(normalised)
                CreateResult.Ok(cachePath = cachePath, handleId = handleId, excluded = excluded)
            } catch (e: Exception) {
                CreateResult.Failed(HydrationError.Generic(e.message ?: "create failed"))
            }
        }
    }

    override suspend fun rename(
        oldPath: String,
        newPath: String,
        replace: Boolean,
    ): RenameResult {
        val oldNorm = oldPath.trimEnd('/').let { if (it == "") "/" else it }
        val newNorm = newPath.trimEnd('/').let { if (it == "") "/" else it }

        // Both cache files must lie inside the cache folder, and the destination is a name this engine creates.
        // Decided before anything is moved, in the cloud or locally.
        if (!resolvesInsideCache(oldNorm) || refusedNewName(newNorm)) {
            return RenameResult.Failed(HydrationError.InvalidPath)
        }

        // POSIX rename(2) onto itself is a no-op success. Decided BEFORE the
        // destination-deletion step below: with replace=true the source would
        // otherwise be deleted as its own destination.
        if (oldNorm == newNorm) return RenameResult.Ok

        // Scope guard: a mounted profile shows its scope as the whole drive, so
        // only moves whose BOTH ends lie inside the sync_path set stay visible
        // in the view. A destination outside it would strand the row in cloud
        // data the mount never shows; a source outside it is data the profile
        // does not own. Either end out of scope → refuse, nothing is moved.
        if (mount.isOutOfScope(oldNorm) || mount.isOutOfScope(newNorm)) {
            return RenameResult.Failed(HydrationError.OutOfScope)
        }

        // Pre-flight: source must exist in state.db.
        val sourceEntry = stateDb.getEntry(oldNorm)
            ?: return RenameResult.OldPathNotFound

        // Excluded destination (#461 route guard): a rename MOVES the remote
        // object. Exclusion means the sync engine never plans an action for the
        // name — the Reconciler and LocalScanner skip it on every pass — so a
        // synced file moved onto an excluded name silently leaves every sync
        // action forever while its cloud copy keeps aging there: no re-download
        // after eviction via enumeration, no conflict handling, no reaping, and
        // a later edit through the mount is keep-local (never uploaded). The
        // row itself would still list (flagged excluded) — the harm is the
        // silent, one-way exit from sync, not a vanishing view. Refused with
        // the typed `excluded` token; the row and the remote are untouched.
        // (Creating an excluded name is the other case: create/open_write_begin
        // accept it as keep-local — nothing exists in the cloud to strand. For
        // the same reason a never-uploaded source (remoteId == null) may be
        // renamed onto an excluded name: the move is purely local.) Placed after
        // the source lookup so a missing source still answers old_path_not_found,
        // and before the replace-destination deletion so a refusal destroys nothing.
        if (sourceEntry.remoteId != null && mount.isExcludedPath(newNorm)) {
            return RenameResult.Failed(HydrationError.Excluded)
        }

        // Pre-flight: destination parent must exist (or destination is at root).
        val newParent = newNorm.substringBeforeLast('/', missingDelimiterValue = "")
        if (newParent.isNotEmpty()) {
            val parentEntry = stateDb.getEntry(newParent)
                ?: return RenameResult.NewParentNotFound
            if (!parentEntry.isFolder) return RenameResult.NewParentNotFound
        }

        // Pre-flight: destination must not exist — unless replace was asked for
        // (POSIX overwrite-if-exists, the editors' safe-save: write a temp file,
        // rename it over the target). Without replace the refusal stands so
        // userland does the unlink-then-rename dance. Replace overwrites a FILE
        // destination only: no provider offers atomic folder replace, and
        // deleting a folder to move a file (or another folder) over it is not a
        // safe-save shape.
        val destEntry = stateDb.getEntry(newNorm)
        if (destEntry != null) {
            if (!replace) return RenameResult.NewPathExists
            if (destEntry.isFolder || sourceEntry.isFolder) return RenameResult.NewPathExists
            // A background upload still queued or running for the source or the destination
            // races the destination delete and the move: the temp file's upload would run
            // against a path that no longer exists (its client is told it failed) while the
            // row now at the target is never-uploaded and owned by nothing, or a stale
            // upload of the destination could land over the renamed content. Safe-save does
            // close-then-rename, so this window is the normal case, not an edge: refuse
            // with the same `busy` token dehydrate uses, and the client retries once the
            // `completed` event for its handle has arrived. Plain rename (no replace) keeps
            // its behaviour — nothing is deleted there.
            if (uploadSlots.containsKey(oldNorm) || uploadSlots.containsKey(newNorm)) {
                return RenameResult.Failed(HydrationError.Generic(BUSY_TOKEN))
            }
            deleteReplaceDestination(destEntry, newNorm)?.let { return it }
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
            val ghost = try {
                mount.remoteItemOrNull(oldNorm)
            } catch (e: Exception) {
                // Transient remote-probe failure: fail the rename rather than risk a
                // local-only rename that would silently skip a ghost's cloud move.
                return RenameResult.Failed(HydrationError.Generic(e.message ?: "remote probe failed"))
            }
            if (ghost != null && !ghost.isFolder) {
                return runCatching {
                    mount.renameRemote(oldNorm, newNorm)
                    moveCacheFile(oldNorm, newNorm)
                    rekeyUploadSlot(oldNorm, newNorm)
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
                rekeyUploadSlot(oldNorm, newNorm)
                stateDb.renamePrefix(oldNorm, newNorm)
                RenameResult.Ok
            }.getOrElse { e ->
                RenameResult.Failed(HydrationError.Generic(e.message ?: "rename failed"))
            }
        }

        // Cloud-backed source: move on the provider, then bring the local state
        // along. #319: the cache file moves too (it previously stayed behind under
        // the old path), and a live upload slot is re-keyed so busy-checks and the
        // list pending flag keep tracking the queued upload. A queued upload for
        // the old path will find its row gone (renamePrefix repathed it) and
        // uploadFromCache now refuses to recreate rows — the bytes survive in the
        // moved cache and the co-daemon's recovery scanner replays them at the new
        // path, instead of the upload resurrecting the old remote path.
        return runCatching {
            mount.renameRemote(oldNorm, newNorm)
            moveCacheFile(oldNorm, newNorm)
            rekeyUploadSlot(oldNorm, newNorm)
            RenameResult.Ok
        }.getOrElse { e ->
            RenameResult.Failed(HydrationError.Generic(e.message ?: "rename failed"))
        }
    }

    // #319: move a live upload slot from the old to the new path across a rename,
    // so dehydrate's busy check, the replace-rename refusal, and list's
    // pendingUpload flag keep tracking the queued upload under its new key. The
    // running coroutine captured the old path and old cache path: its upload fails
    // on the vanished row (uploadFromCache refuses to recreate rows) and its
    // cleanup removes the slot by identity, not by path, so the re-key cannot leak
    // it. A submitter that lands between the remove and the put creates a fresh
    // slot at the old path and cleans it up itself.
    private fun rekeyUploadSlot(oldPath: String, newPath: String) {
        if (oldPath == newPath) return
        val slot = uploadSlots.remove(oldPath) ?: return
        uploadSlots[newPath] = slot
    }

    // Deletes the existing destination before a replace-rename, through the same
    // path `unlink` takes — the destination gets the delete's trash/undo
    // semantics, not a silent destroy. Ghost-aware (mirrors unlink): a `local:`
    // row whose content actually landed on the cloud must be deleted remotely,
    // not just dropped locally. Returns null when the destination is gone and
    // the caller may proceed with the move, or the RenameResult to fail with
    // (the source row is untouched in every failure case).
    private suspend fun deleteReplaceDestination(
        destEntry: org.krost.unidrive.sync.model.SyncEntry,
        destNorm: String,
    ): RenameResult? {
        if (destEntry.remoteId != null) {
            return try {
                mount.deleteRemote(destNorm)
                evictCacheFile(destNorm)
                null
            } catch (e: Exception) {
                RenameResult.Failed(HydrationError.Generic(e.message ?: "rename replace failed"))
            }
        }
        val ghost = try {
            mount.remoteItemOrNull(destNorm)
        } catch (e: Exception) {
            // Transient remote-probe failure: fail the rename rather than
            // hard-delete the row and orphan a ghost's cloud copy.
            return RenameResult.Failed(HydrationError.Generic(e.message ?: "remote probe failed"))
        }
        if (ghost != null && !ghost.isFolder) {
            return try {
                mount.deleteRemote(destNorm)
                evictCacheFile(destNorm)
                null
            } catch (e: Exception) {
                RenameResult.Failed(HydrationError.Generic(e.message ?: "rename replace failed"))
            }
        }
        // Genuinely-local destination (nothing cloud-side): hard-delete the row —
        // a tombstone carries no reconciliation value for a never-uploaded file —
        // and evict its cache copy. Cache eviction failure is non-fatal (the row
        // is the truth); the row delete is not.
        return try {
            runCatching { Files.deleteIfExists(mount.resolveCachePath(destNorm)) }
            stateDb.deleteEntry(destNorm)
            null
        } catch (e: Exception) {
            RenameResult.Failed(HydrationError.Generic(e.message ?: "rename replace failed"))
        }
    }

    override fun onConnectionClosed(connectionId: String) {
        openSets.remove(connectionId)
    }

    // One classification for a failed download, shared by the open_read path and the hydrate verb:
    // a stored object shorter than the size the drive reports (#536) carries its own token with the
    // numbers, a genuinely-gone read the not_found token, everything else stays Generic (→ EIO).
    private fun downloadFailureOf(e: Exception, fallback: String): HydrationError = when {
        e is RemoteIncompleteDownloadException -> HydrationError.RemoteIncomplete(e.storedBytes, e.declaredBytes)
        isNotFound(e) -> HydrationError.NotFound
        else -> HydrationError.Generic(e.message ?: fallback)
    }

    // Classify a download failure as genuinely-not-found versus any other error.
    // Two provider-agnostic signals, since this module cannot reference a concrete
    // provider's exception type:
    //  - PermanentDownloadFailureException — the typed "remote object is gone
    //    (stable 404)" signal (Internxt raises it directly).
    //  - An exception carrying an Int `statusCode` == 404 — covers OneDrive's
    //    GraphApiException, read reflectively to avoid a provider classpath dep.
    private fun isNotFound(e: Throwable): Boolean {
        // A short stored object (#536) IS a PermanentDownloadFailureException — quarantined like
        // one — but it is not a gone file: it keeps its own remote_incomplete token.
        if (e is RemoteIncompleteDownloadException) return false
        if (e is PermanentDownloadFailureException) return true
        return statusCodeOf(e) == 404 || (e.cause?.let { statusCodeOf(it) } == 404)
    }

    private fun statusCodeOf(e: Throwable): Int? =
        runCatching {
            val getter = e.javaClass.methods.firstOrNull { it.name == "getStatusCode" && it.parameterCount == 0 }
            (getter?.invoke(e) as? Int)
        }.getOrNull()
}
