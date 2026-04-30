package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.slf4j.LoggerFactory
import java.nio.file.Files

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
        flow {
            val startTime = System.currentTimeMillis()
            val (totalSize, totalFiles) =
                if (knownTotalSize != null && knownTotalFiles != null) {
                    knownTotalSize to knownTotalFiles
                } else {
                    preFlightCheck(sourcePath)
                }
            emit(MigrateEvent.Started(totalFiles = totalFiles, totalSize = totalSize))

            var migratedFiles = 0
            var migratedSize = 0L
            var skippedFiles = 0
            var skippedSize = 0L
            var errorCount = 0
            val tempDir = Files.createTempDirectory("unidrive-relocate-")
            currentTempDir = tempDir // UD-269: published for shutdown-hook cleanup.

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

            suspend fun walk(
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
                            // UD-286: never absorb cancellation.
                            throw e
                        } catch (e: Exception) {
                            // Folder may already exist (HTTP 405 on WebDAV /
                            // 409 on OneDrive / similar elsewhere) — that's the
                            // intended idempotent-resume path. Log at DEBUG: a
                            // real network failure here will surface loudly on
                            // the subsequent walk into this folder, and the
                            // per-file catch below produces the actual error.
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
                            skippedFiles++
                            skippedSize += item.size
                            emit(
                                MigrateEvent.FileProgressEvent(
                                    doneFiles = migratedFiles + skippedFiles,
                                    doneSize = migratedSize + skippedSize,
                                    totalFiles = totalFiles,
                                    totalSize = totalSize,
                                    skippedFiles = skippedFiles,
                                    skippedSize = skippedSize,
                                ),
                            )
                            continue
                        }

                        val tempFile = tempDir.resolve(itemName)
                        try {
                            val size =
                                withContext(Dispatchers.IO) {
                                    source.download(newSource, tempFile)
                                }
                            target.upload(tempFile, newTarget)
                            migratedFiles++
                            migratedSize += size
                            emit(
                                MigrateEvent.FileProgressEvent(
                                    doneFiles = migratedFiles + skippedFiles,
                                    doneSize = migratedSize + skippedSize,
                                    totalFiles = totalFiles,
                                    totalSize = totalSize,
                                    skippedFiles = skippedFiles,
                                    skippedSize = skippedSize,
                                ),
                            )
                        } catch (e: CancellationException) {
                            // UD-286: never absorb cancellation. The finally
                            // block still runs and cleans up tempFile.
                            throw e
                        } catch (e: Exception) {
                            // UD-286: log class name + stack so unidrive.log
                            // captures *what* failed, not just `e.message`.
                            // The CLI surfaces e.message via MigrateEvent.Error;
                            // postmortem reads the full record from the log.
                            log.warn(
                                "Failed to migrate {}: {}: {}",
                                newSource,
                                e.javaClass.simpleName,
                                e.message,
                                e,
                            )
                            errorCount++
                            emit(
                                MigrateEvent.Error(
                                    "Failed to migrate $newSource: ${e.javaClass.simpleName}: ${e.message}",
                                ),
                            )
                        } finally {
                            Files.deleteIfExists(tempFile)
                        }
                    }
                }
            }

            try {
                try {
                    walk(sourcePath, targetPath)
                } catch (e: CancellationException) {
                    // UD-286: cancellation propagates past the walk so the
                    // outer flow honours structured concurrency. The finally
                    // below still cleans up tempDir.
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "Migration walk failed at {}: {}: {}",
                        sourcePath,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    emit(
                        MigrateEvent.Error(
                            "Migration failed: ${e.javaClass.simpleName}: ${e.message}",
                        ),
                    )
                }
            } finally {
                // UD-286: recursive cleanup that never throws. If walk was
                // cancelled mid-download, a partial tempFile may remain in
                // tempDir; deleteIfExists on a non-empty dir would otherwise
                // throw DirectoryNotEmptyException and shadow the original
                // failure cause.
                runCatching { tempDir.toFile().deleteRecursively() }
                currentTempDir = null // UD-269: tempDir is gone; clear pointer.
            }
            val duration = System.currentTimeMillis() - startTime
            emit(
                MigrateEvent.Completed(
                    doneFiles = migratedFiles + skippedFiles,
                    doneSize = migratedSize + skippedSize,
                    totalFiles = totalFiles,
                    totalSize = totalSize,
                    errorCount = errorCount,
                    durationMs = duration,
                    skippedFiles = skippedFiles,
                    skippedSize = skippedSize,
                ),
            )
        }.flowOn(Dispatchers.IO)

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
