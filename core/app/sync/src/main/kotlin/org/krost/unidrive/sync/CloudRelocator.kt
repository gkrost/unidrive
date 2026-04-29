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
}
