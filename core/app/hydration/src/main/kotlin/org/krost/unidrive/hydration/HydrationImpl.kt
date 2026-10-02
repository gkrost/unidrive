package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.FolderNotEmptyException
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger

class HydrationImpl(
    private val syncEngine: SyncEngine,
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
    // Minimum wall-clock gap between `uploading` progress events per attempt;
    // coalesces provider progress callbacks to at most a few per second per
    // file. 0 emits every callback (tests).
    val uploadProgressMinIntervalMs: Long = DEFAULT_UPLOAD_PROGRESS_MIN_INTERVAL_MS,
) : Hydration {

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
    )
    private val uploadSlots = ConcurrentHashMap<String, UploadSlot>()

    // Waiting-slot budget for the upload queue. Acquired by the submitting
    // caller (open_write / replay) and released only when the job actually
    // acquires a transfer permit — so the bound covers uploads waiting either
    // for their per-path turn or for the daemon-wide budget. A burst beyond
    // [uploadQueueDepth] suspends the submitter: back-pressure towards the
    // client instead of unbounded queue growth.
    private val queueSlots = Semaphore(uploadQueueDepth)

    private companion object {
        // Wire token for "an upload of the path is still in flight, retry"; the same literal
        // dehydrate's Busy reply puts on the wire.
        const val BUSY_TOKEN = "busy"

        /** Default bound on uploads waiting to run (see [uploadQueueDepth]). */
        const val DEFAULT_UPLOAD_QUEUE_DEPTH = 256

        /** Default attempts per queued upload (see [maxUploadAttempts]). */
        const val DEFAULT_MAX_UPLOAD_ATTEMPTS = 3

        /** Delays preceding retries 2..N of a queued upload (see [uploadRetryDelaysMs]). */
        val DEFAULT_UPLOAD_RETRY_DELAYS_MS = listOf(2_000L, 10_000L)

        /** Default coalescing gap for `uploading` progress events (see [uploadProgressMinIntervalMs]). */
        const val DEFAULT_UPLOAD_PROGRESS_MIN_INTERVAL_MS = 400L
    }

    override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult {
        val entry = stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.UnknownPath)

        val cachePath = try {
            // Always emit Hydrating + Hydrated, even when SyncEngine returns a warm cache
            // without downloading: subscribers should see a consistent event stream
            // regardless of cache state; the cache layer is an implementation detail of
            // SyncEngine, not part of the Hydration SPI contract.
            _events.emit(HydrationEvent.Hydrating(path))
            val p = syncEngine.ensureHydrated(path)
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
            if (current.remoteId != null && !current.isFolder && current.remoteSize > 0 && bytes != current.remoteSize) {
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
            // maps it to ENOENT, not the catch-all EIO. Any other failure stays
            // Generic (→ EIO).
            val err: HydrationError =
                if (isNotFound(e)) HydrationError.NotFound else HydrationError.Generic(e.message ?: "download failed")
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
        return OpenResult.Ok(cachePath)
    }

    override suspend fun openForWrite(
        connectionId: String,
        handleId: String,
        path: String,
        cachePath: Path,
        baseEtag: String?,
    ): OpenResult {
        val entry = stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.UnknownPath)

        // Excluded paths (exclude_patterns) are keep-local: the write is
        // accepted — the editor's bytes are real local content — but the
        // upload never runs. Refusing would break the editors and tools that
        // legitimately create *.tmp / ~$ scratch files through the mount;
        // running the upload would hit the engine's keep-local guard anyway
        // and answer hydrated, marking a file in sync that is not in the
        // cloud. The skipped event (not hydrating/hydrated) plus a Completed
        // carrying the excluded token tell the client both facts.
        if (syncEngine.isExcludedPath(path)) {
            // The engine's keep-local branch uploads nothing but advances the row's
            // local watermark (last_synced); without it the co-daemon's recovery
            // scanner replays this file's open_write on every mount, forever.
            runCatching { syncEngine.uploadFromCache(path, cachePath) }
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
    // ([SyncEngine.withTransferPermit]) — an Explorer copy burst can never
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
        // cancel into the normal cancelled path.
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
            completed?.let { _events.emit(it) }
        }
        slot.jobs.add(worker)
    }

    // Runs one queued upload to completion: up to [maxUploadAttempts] transfer
    // attempts under the daemon-wide permit, emitting hydrating/hydrated (or
    // failed per attempt) and coalesced uploading progress. [baseEtag] is
    // forwarded to uploadFromCache for the upload-time convergence guard.
    // [onPermitAcquired] fires inside the permit block — the queue's waiting
    // slot is handed over exactly when the transfer actually starts.
    private suspend fun runUploadWithRetries(
        path: String,
        cachePath: Path,
        handleId: String,
        baseEtag: String?,
        onProgress: (Long, Long) -> Unit,
        onPermitAcquired: () -> Unit,
    ): HydrationEvent.Completed {
        var lastError: HydrationError = HydrationError.Generic("upload failed")
        for (attempt in 1..maxUploadAttempts) {
            try {
                syncEngine.withTransferPermit {
                    onPermitAcquired()
                    _events.emit(HydrationEvent.Hydrating(path))
                    syncEngine.uploadFromCache(path, cachePath, baseEtag, onProgress)
                }
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
            } catch (e: Exception) {
                runCatching { stateDb.markUploadFailed(path, java.time.Instant.now()) }
                val err = HydrationError.Generic(e.message ?: "upload failed")
                lastError = err
                val retryScheduled = attempt < maxUploadAttempts
                _events.emit(HydrationEvent.Failed(path, err, retryScheduled = retryScheduled))
                if (retryScheduled) {
                    log.info(
                        "upload attempt {}/{} failed for {}: {}; retrying",
                        attempt, maxUploadAttempts, path, e.message,
                    )
                    delay(retryDelayMs(attempt))
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
     * enqueued; every enqueued upload goes through the same per-path
     * serialization and transfer budget as client-submitted ones.
     */
    suspend fun replayPendingUploads(): Int {
        var queued = 0
        for (path in stateDb.pendingUploadPaths()) {
            if (syncEngine.isExcludedPath(path)) continue
            if (syncEngine.isOutOfScope(path)) continue
            val cachePath = syncEngine.resolveCachePath(path)
            if (!Files.exists(cachePath)) continue
            launchSerializedUpload(path, cachePath, "engine-replay-${queued + 1}", baseEtag = null)
            queued++
        }
        return queued
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
    }

    // #301: whether a background upload of [path] is queued or in flight. The
    // engine's enumerate-reap consults this (via the engine's uploadInFlight hook,
    // wired by app:cli) before evicting a hydration-cache file, so a queued edit's
    // only copy is never reaped out from under its upload.
    fun hasUploadSlot(path: String): Boolean = uploadSlots.containsKey(path)

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
                        pendingUpload = e.remoteId == null || uploadSlots.containsKey(e.path),
                        hasError = e.lastErrorAt != null,
                        excluded = syncEngine.isExcludedPath(e.path),
                    )
                },
            )
        } catch (e: Exception) {
            ListResult.Failed(HydrationError.Generic(e.message ?: "list failed"))
        }
    }

    override suspend fun mkdir(path: String): MkdirResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        // Scope guard: a folder created outside the profile's sync_path set
        // would land in the cloud but never show in the mounted view (the view
        // only lists the scope). Refuse before touching the provider.
        if (syncEngine.isOutOfScope(normalised)) return MkdirResult.Failed(HydrationError.OutOfScope)
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
            ?: return UnlinkResult.Failed(HydrationError.UnknownPath)
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
            val ghost = try {
                syncEngine.remoteItemOrNull(normalised)
            } catch (e: Exception) {
                // Transient remote-probe failure: fail the unlink rather than
                // hard-delete the row and orphan a ghost's cloud copy.
                return UnlinkResult.Failed(HydrationError.Generic(e.message ?: "remote probe failed"))
            }
            if (ghost != null && !ghost.isFolder) {
                return runCatching {
                    syncEngine.deleteRemote(normalised)
                    evictCacheFile(normalised)
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
            evictCacheFile(normalised)
            UnlinkResult.Ok
        }.getOrElse { e ->
            UnlinkResult.Failed(HydrationError.Generic(e.message ?: "unlink failed"))
        }
    }

    override suspend fun rmdir(path: String): RmdirResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        val entry = stateDb.getEntry(normalised)
            ?: return RmdirResult.Failed(HydrationError.UnknownPath)
        if (!entry.isFolder) return RmdirResult.PathIsFile

        return runCatching {
            syncEngine.deleteRemote(normalised)
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
        val oldCache = syncEngine.resolveCachePath(oldNorm)
        val newCache = syncEngine.resolveCachePath(newNorm)
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
        runCatching { Files.deleteIfExists(syncEngine.resolveCachePath(path)) }
    }

    // Recursively evict a folder's hydration-cache subtree after a successful
    // rmdir. Same idempotency contract as evictCacheFile: a missing subtree is
    // a no-op and a partial failure must not abort the op (the cloud delete
    // already succeeded). Deletes children before parents so the directory
    // empties before it is removed.
    private fun evictCacheTree(path: String) {
        val root = syncEngine.resolveCachePath(path)
        runCatching {
            if (!Files.exists(root)) return
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p ->
                    runCatching { Files.deleteIfExists(p) }
                }
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

    override suspend fun openWriteBegin(connectionId: String, path: String, handleId: String?): OpenResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        // Scope guard: same rationale as mkdir — a truncate outside the
        // profile's sync_path set would touch cloud data the view never shows.
        if (syncEngine.isOutOfScope(normalised)) return OpenResult.Failed(HydrationError.OutOfScope)
        val entry = stateDb.getEntry(normalised)
            ?: return OpenResult.Failed(HydrationError.UnknownPath)
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
            OpenResult.Ok(cachePath, excluded = syncEngine.isExcludedPath(normalised))
        } catch (e: Exception) {
            OpenResult.Failed(HydrationError.Generic(e.message ?: "open_write_begin failed"))
        }
    }

    override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult {
        val normalised = path.trimEnd('/').let { if (it == "") "/" else it }
        val mutex = createMutexes.computeIfAbsent(normalised) { Mutex() }
        return mutex.withLock {
            // Scope guard: a file created outside the profile's sync_path set
            // would upload to the cloud but never show in the mounted view.
            if (syncEngine.isOutOfScope(normalised)) return@withLock CreateResult.Failed(HydrationError.OutOfScope)

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
            val excluded = syncEngine.isExcludedPath(normalised)

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

        // POSIX rename(2) onto itself is a no-op success. Decided BEFORE the
        // destination-deletion step below: with replace=true the source would
        // otherwise be deleted as its own destination.
        if (oldNorm == newNorm) return RenameResult.Ok

        // Scope guard: a mounted profile shows its scope as the whole drive, so
        // only moves whose BOTH ends lie inside the sync_path set stay visible
        // in the view. A destination outside it would strand the row in cloud
        // data the mount never shows; a source outside it is data the profile
        // does not own. Either end out of scope → refuse, nothing is moved.
        if (syncEngine.isOutOfScope(oldNorm) || syncEngine.isOutOfScope(newNorm)) {
            return RenameResult.Failed(HydrationError.OutOfScope)
        }

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
                syncEngine.remoteItemOrNull(oldNorm)
            } catch (e: Exception) {
                // Transient remote-probe failure: fail the rename rather than risk a
                // local-only rename that would silently skip a ghost's cloud move.
                return RenameResult.Failed(HydrationError.Generic(e.message ?: "remote probe failed"))
            }
            if (ghost != null && !ghost.isFolder) {
                return runCatching {
                    syncEngine.renameRemote(oldNorm, newNorm)
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
            syncEngine.renameRemote(oldNorm, newNorm)
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
                syncEngine.deleteRemote(destNorm)
                evictCacheFile(destNorm)
                null
            } catch (e: Exception) {
                RenameResult.Failed(HydrationError.Generic(e.message ?: "rename replace failed"))
            }
        }
        val ghost = try {
            syncEngine.remoteItemOrNull(destNorm)
        } catch (e: Exception) {
            // Transient remote-probe failure: fail the rename rather than
            // hard-delete the row and orphan a ghost's cloud copy.
            return RenameResult.Failed(HydrationError.Generic(e.message ?: "remote probe failed"))
        }
        if (ghost != null && !ghost.isFolder) {
            return try {
                syncEngine.deleteRemote(destNorm)
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
            runCatching { Files.deleteIfExists(syncEngine.resolveCachePath(destNorm)) }
            stateDb.deleteEntry(destNorm)
            null
        } catch (e: Exception) {
            RenameResult.Failed(HydrationError.Generic(e.message ?: "rename replace failed"))
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
