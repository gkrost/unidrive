package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed class MigrateEvent {
    abstract val doneFiles: Int
    abstract val doneSize: Long
    abstract val totalFiles: Int
    abstract val totalSize: Long

    data class Started(
        override val doneFiles: Int = 0,
        override val doneSize: Long = 0L,
        override val totalFiles: Int,
        override val totalSize: Long,
    ) : MigrateEvent()

    /**
     * Emitted after each file is processed — transferred or skipped.
     * [doneFiles] and [doneSize] include both transferred and skipped.
     * [skippedFiles] and [skippedSize] are the subset that were skipped
     * because the target already had an equivalent file.
     */
    data class FileProgressEvent(
        override val doneFiles: Int,
        override val doneSize: Long,
        override val totalFiles: Int,
        override val totalSize: Long,
        val skippedFiles: Int = 0,
        val skippedSize: Long = 0L,
    ) : MigrateEvent()

    data class Completed(
        override val doneFiles: Int,
        override val doneSize: Long,
        override val totalFiles: Int = doneFiles,
        override val totalSize: Long = doneSize,
        val errorCount: Int,
        val durationMs: Long,
        val skippedFiles: Int = 0,
        val skippedSize: Long = 0L,
    ) : MigrateEvent()

    data class Error(
        val message: String,
    ) : MigrateEvent() {
        override val doneFiles: Int = 0
        override val doneSize: Long = 0L
        override val totalFiles: Int = 0
        override val totalSize: Long = 0L
    }
}

class CloudRelocator(
    private val source: CloudProvider,
    private val target: CloudProvider,
    private val bufferSize: Long = 8 * 1024 * 1024,
    private val skipExisting: Boolean = true,
    /**
     * UD-289: maximum number of files transferred in parallel during the
     * walk. Pre-fix the walk processed one file at a time end-to-end —
     * a single slow upload (e.g. mid-stream connection-reset that triggered
     * UD-288's 5-attempt retry budget at ~22 min/attempt) blocked every
     * subsequent file behind it. Default 4 mirrors the SyncEngine Pass 2
     * cap (UD-263); per-target tuning happens via the same provider
     * metadata (`maxConcurrentTransfers`).
     */
    private val maxConcurrentTransfers: Int = 4,
) {
    private val log = LoggerFactory.getLogger(CloudRelocator::class.java)

    /**
     * UD-269: exposes the temp directory currently in use by [migrate] so the
     * RelocateCommand shutdown hook can clean it up on SIGINT / kill -9. The
     * normal-completion path of [migrate] already deletes it via the existing
     * `finally` block; this property only matters for the abort path.
     *
     * @Volatile because it's read by the JVM shutdown hook (a different
     * thread) while the migrate flow is running on Dispatchers.IO.
     */
    @Volatile
    var currentTempDir: java.nio.file.Path? = null
        private set

    suspend fun preFlightCheck(
        sourcePath: String,
        onProgress: ((fileCount: Int, currentPath: String) -> Unit)? = null,
    ): Pair<Long, Int> {
        var totalSize = 0L
        var fileCount = 0

        suspend fun walk(path: String) {
            val items = source.listChildren(path)
            for (item in items) {
                if (item.isFolder) {
                    walk(item.path)
                } else {
                    totalSize += item.size
                    fileCount++
                    onProgress?.invoke(fileCount, item.path)
                }
            }
        }

        walk(sourcePath)
        return totalSize to fileCount
    }

    /**
     * UD-327: scan the source tree for files that exceed the target's
     * per-file size cap. Synology DSM WebDAV defaults to 4 GiB unless the
     * admin reconfigured `/etc/nginx/app.d/server.webdav.conf`; without
     * this pre-flight, oversized PUTs fail at minute 10 of a wasted
     * transfer with a 409 / silent connection reset.
     *
     * Returns the list of (path, size) tuples for files exceeding the
     * cap, in walk order. Empty list when:
     *  - target.maxFileSizeBytes() returns null (provider has no cap, or
     *    user didn't configure one) — the check is a no-op.
     *  - all source files fit within the cap.
     *
     * Caller (RelocateCommand) is responsible for surfacing the list to
     * the user and deciding whether to proceed (warn-only / strict abort
     * via flag — left to the caller).
     */
    suspend fun preflightOversized(sourcePath: String): List<Pair<String, Long>> {
        val cap = target.maxFileSizeBytes() ?: return emptyList()
        val oversized = mutableListOf<Pair<String, Long>>()

        suspend fun walk(path: String) {
            for (item in source.listChildren(path)) {
                if (item.isFolder) {
                    walk(item.path)
                } else if (item.size > cap) {
                    oversized.add(item.path to item.size)
                }
            }
        }
        walk(sourcePath)
        return oversized
    }

    /**
     * Equivalence check between a source file and a candidate target file.
     * Hash match wins when both sides report a hash (OneDrive ↔ OneDrive,
     * Dropbox ↔ Dropbox). Falls back to size equality when either side has
     * no hash (Synology WebDAV has no ETag; cross-provider hashes rarely
     * agree on algorithm). Size-only has a small false-positive risk — two
     * differently-named files of identical byte length — but for relocate's
     * idempotent-resume use case it's the right trade-off.
     */
    internal fun isEquivalent(
        src: CloudItem,
        dst: CloudItem,
    ): Boolean {
        if (src.hash != null && dst.hash != null && src.hash == dst.hash) return true
        return src.size == dst.size
    }

    fun migrate(
        sourcePath: String,
        targetPath: String,
        knownTotalSize: Long? = null,
        knownTotalFiles: Int? = null,
    ): Flow<MigrateEvent> =
        // UD-289: channelFlow (vs flow) lets concurrent file-transfer
        // coroutines call send() safely. Pre-fix the walk processed
        // one file at a time end-to-end — a single slow upload blocked
        // every subsequent file behind it (a 22-minute connection-reset
        // retry on one file delayed all 4000 others on the WebDAV-DSM
        // run that motivated this ticket).
        channelFlow {
            val startTime = System.currentTimeMillis()
            val (totalSize, totalFiles) =
                if (knownTotalSize != null && knownTotalFiles != null) {
                    knownTotalSize to knownTotalFiles
                } else {
                    preFlightCheck(sourcePath)
                }
            send(MigrateEvent.Started(totalFiles = totalFiles, totalSize = totalSize))

            // UD-289: counters are atomic so launched coroutines don't
            // race when reading running totals for FileProgressEvent.
            // emitMutex serialises the FileProgressEvent + Error sends
            // so the (doneFiles, doneSize) tuple stays internally
            // consistent — without it a reader could see e.g.
            // doneFiles=5 with doneSize from when only 3 had completed.
            val migratedFiles = AtomicInteger(0)
            val migratedSize = AtomicLong(0L)
            val skippedFiles = AtomicInteger(0)
            val skippedSize = AtomicLong(0L)
            val errorCount = AtomicInteger(0)
            val emitMutex = Mutex()
            val transferSemaphore = Semaphore(maxConcurrentTransfers)
            val tempDir = Files.createTempDirectory("unidrive-relocate-")
            currentTempDir = tempDir // UD-269: published for shutdown-hook cleanup.

            suspend fun emitProgress() {
                emitMutex.withLock {
                    send(
                        MigrateEvent.FileProgressEvent(
                            doneFiles = migratedFiles.get() + skippedFiles.get(),
                            doneSize = migratedSize.get() + skippedSize.get(),
                            totalFiles = totalFiles,
                            totalSize = totalSize,
                            skippedFiles = skippedFiles.get(),
                            skippedSize = skippedSize.get(),
                        ),
                    )
                }
            }

            suspend fun targetIndex(path: String): Map<String, CloudItem> {
                if (!skipExisting) return emptyMap()
                return try {
                    target.listChildren(path).associateBy { it.name }
                } catch (e: CancellationException) {
                    // UD-286: never absorb cancellation — re-throw before the
                    // generic catch so structured concurrency keeps working.
                    throw e
                } catch (e: Exception) {
                    // UD-286: log class name + throwable so postmortem can
                    // distinguish IOException / WSAECONNABORTED / 503 / 404 / …
                    log.warn(
                        "targetIndex failed for '{}', skip check disabled: {}: {}",
                        path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    emptyMap()
                }
            }

            // UD-289: walk is defined as an extension on CoroutineScope so
            // `launch { ... }` inside resolves to the SCOPE PASSED IN by the
            // outer `coroutineScope { walk(...) }` block, not the channelFlow's
            // ProducerScope. Without this distinction, launches escape the
            // join boundary — Completed gets sent before per-file work runs.
            suspend fun kotlinx.coroutines.CoroutineScope.walk(
                sourcePrefix: String,
                targetPrefix: String,
            ) {
                val items = source.listChildren(sourcePrefix)
                val existingByName = targetIndex(targetPrefix)
                log.debug(
                    "target-index for {}: {} entries, keys={}",
                    targetPrefix,
                    existingByName.size,
                    existingByName.keys.take(5),
                )
                for (item in items) {
                    val itemName = item.name
                    val newSource = if (sourcePrefix == "/") "/$itemName" else "$sourcePrefix/$itemName"
                    val newTarget = if (targetPrefix == "/") "/$itemName" else "$targetPrefix/$itemName"

                    if (item.isFolder) {
                        try {
                            target.createFolder(newTarget)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.debug(
                                "createFolder for '{}' failed (assuming exists): {}: {}",
                                newTarget,
                                e.javaClass.simpleName,
                                e.message,
                            )
                        }
                        walk(newSource, newTarget)
                    } else {
                        val existing = existingByName[itemName]
                        if (existing != null) {
                            log.debug(
                                "skip-check {}: isFolder={} equivalent={}",
                                itemName,
                                existing.isFolder,
                                isEquivalent(item, existing),
                            )
                        }
                        if (existing != null && !existing.isFolder && isEquivalent(item, existing)) {
                            skippedFiles.incrementAndGet()
                            skippedSize.addAndGet(item.size)
                            emitProgress()
                            continue
                        }

                        // UD-289: capture the outer coroutineScope so a per-file
                        // CancellationException can cancel siblings explicitly
                        // (Kotlin's structured concurrency does NOT auto-propagate
                        // a child CE up to the scope — the scope just sees the
                        // child as "cancelled" and continues. UD-286 relies on
                        // CE escaping the migrate flow so the caller's `assertFailsWith
                        // <CancellationException>` triggers; without explicit
                        // outerScope.cancel(e), the scope completes normally and
                        // siblings finish in parallel).
                        val outerScope: kotlinx.coroutines.CoroutineScope = this
                        launch {
                            transferSemaphore.withPermit {
                                val tempFile = tempDir.resolve(itemName)
                                try {
                                    val size =
                                        withContext(Dispatchers.IO) {
                                            source.download(newSource, tempFile)
                                        }
                                    target.upload(tempFile, newTarget)
                                    migratedFiles.incrementAndGet()
                                    migratedSize.addAndGet(size)
                                    emitProgress()
                                } catch (e: CancellationException) {
                                    // UD-286 + UD-289: cancel siblings + re-throw
                                    // so the channelFlow body's outer try/catch
                                    // sees CE and lets it escape via .toList().
                                    outerScope.cancel(e)
                                    throw e
                                } catch (e: Exception) {
                                    log.warn(
                                        "Failed to migrate {}: {}: {}",
                                        newSource,
                                        e.javaClass.simpleName,
                                        e.message,
                                        e,
                                    )
                                    errorCount.incrementAndGet()
                                    emitMutex.withLock {
                                        send(
                                            MigrateEvent.Error(
                                                "Failed to migrate $newSource: ${e.javaClass.simpleName}: ${e.message}",
                                            ),
                                        )
                                    }
                                } finally {
                                    Files.deleteIfExists(tempFile)
                                }
                            }
                        }
                    }
                }
            }

            try {
                try {
                    coroutineScope { walk(sourcePath, targetPath) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "Migration walk failed at {}: {}: {}",
                        sourcePath,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    emitMutex.withLock {
                        send(
                            MigrateEvent.Error(
                                "Migration failed: ${e.javaClass.simpleName}: ${e.message}",
                            ),
                        )
                    }
                }
            } finally {
                runCatching { tempDir.toFile().deleteRecursively() }
                currentTempDir = null
            }
            val duration = System.currentTimeMillis() - startTime
            send(
                MigrateEvent.Completed(
                    doneFiles = migratedFiles.get() + skippedFiles.get(),
                    doneSize = migratedSize.get() + skippedSize.get(),
                    totalFiles = totalFiles,
                    totalSize = totalSize,
                    errorCount = errorCount.get(),
                    durationMs = duration,
                    skippedFiles = skippedFiles.get(),
                    skippedSize = skippedSize.get(),
                ),
            )
        }
    // UD-289: NO flowOn(Dispatchers.IO) here — channelFlow manages its
    // own context, and the inner launches (which need to run before
    // coroutineScope returns) need to share the dispatcher with the
    // channelFlow body. flowOn(Dispatchers.IO) added a hop that
    // de-coordinated the launches with the channelFlow's join. Production
    // callers (RelocateCommand) wrap the collect in
    // `withContext(Dispatchers.IO)` already (see UD-294 + UD-263), so
    // the IO offload happens at a higher layer.

    companion object {
        private val gcLog = LoggerFactory.getLogger(CloudRelocator::class.java)

        /**
         * UD-269: scan the JVM's `java.io.tmpdir` for `unidrive-relocate-*`
         * directories older than [maxAgeMs] and delete them recursively.
         *
         * Catches the case where a previous relocate aborted via kill -9 / OS
         * force-terminate / power loss — neither the in-flow `finally` nor the
         * RelocateCommand shutdown hook ran, so the temp directory survives.
         * Without this GC the user accumulates a `unidrive-relocate-<random>/`
         * orphan per crashed run.
         *
         * Default age is 24 h: long enough that a legitimate concurrent
         * relocate (rare but possible) doesn't get its tempDir deleted out
         * from under it; short enough that disk reclaim happens within a day.
         *
         * Returns the number of directories actually deleted (0 = no orphans).
         * Logs WARN per directory it can't delete; never throws.
         */
        fun cleanStaleTempDirs(
            tmpDir: java.nio.file.Path =
                java.nio.file.Paths
                    .get(System.getProperty("java.io.tmpdir")),
            maxAgeMs: Long = 24L * 60 * 60 * 1000,
        ): Int {
            val now = System.currentTimeMillis()
            var deleted = 0
            runCatching {
                Files.list(tmpDir).use { stream ->
                    stream
                        .filter {
                            it.fileName.toString().startsWith("unidrive-relocate-") && Files.isDirectory(it)
                        }.forEach { dir ->
                            val age =
                                runCatching {
                                    now - Files.getLastModifiedTime(dir).toMillis()
                                }.getOrNull() ?: return@forEach
                            if (age >= maxAgeMs) {
                                if (runCatching { dir.toFile().deleteRecursively() }.getOrElse { false }) {
                                    deleted++
                                } else {
                                    gcLog.warn("UD-269: failed to delete stale relocate tempdir: {}", dir)
                                }
                            }
                        }
                }
            }
            return deleted
        }
    }
}
