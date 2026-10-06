package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * #560 U3: the transfer bookkeeping the mirror pass (`SyncEngine`) and the mount operations
 * (`MountEngine`) share: how a download is addressed, which stats an upload records, and when a
 * recorded local hash (#396) still describes the bytes. Moved out of `SyncEngine` unchanged; the
 * callers pass their logger so the log lines keep their logger name.
 */
object Transfers {
    /** Download [item] to [destination]: by id when the item has one (the fast path), else by [remotePath]. */
    suspend fun downloadByIdOrPath(
        provider: CloudProvider,
        item: CloudItem,
        remotePath: String,
        destination: Path,
    ): Long =
        if (item.id.isNotEmpty()) {
            provider.downloadById(item.id, remotePath, destination)
        } else {
            provider.download(remotePath, destination)
        }

    /** mtime and size of a file about to be uploaded, or null when it cannot be read. */
    fun statBeforeUpload(local: Path): Pair<Long, Long>? =
        try {
            Files.getLastModifiedTime(local).toMillis() to Files.size(local)
        } catch (e: java.io.IOException) {
            null
        }

    /**
     * #396: [entry] with the SHA-256 of [local] as its local hash, for a provider without a content
     * hash ([algorithm] null). The hash is kept only if the file still has [mtime] and [size], so a
     * write that lands while it is hashed cannot turn the new bytes into the tracked baseline. The
     * callers pass a row that already carries the new mtime/size but may still hold the hash of the
     * previous contents, so a failed hash clears it rather than keeping it.
     */
    fun withLocalHash(
        entry: SyncEntry,
        local: Path,
        mtime: Long,
        size: Long,
        algorithm: HashAlgorithm?,
        log: Logger,
    ): SyncEntry {
        if (algorithm != null || entry.isFolder) return entry
        return try {
            val hash = HashVerifier.computeSha256Hex(local)
            if (Files.getLastModifiedTime(local).toMillis() == mtime && Files.size(local) == size) {
                entry.copy(localHash = hash)
            } else {
                entry.copy(localHash = null)
            }
        } catch (e: java.io.IOException) {
            log.debug("#396: cannot hash {} for the local-hash column: {}", entry.path, e.message)
            entry.copy(localHash = null)
        }
    }

    /**
     * An upload row records the file's stats as they were BEFORE the transfer (#337/#148), so a
     * write landing during a long upload keeps a newer mtime than the recorded baseline and the next
     * scan re-uploads it. Hashing the bytes that were sent is therefore only safe while the file still
     * matches those pre-upload stats ([sent]): if the file changed while it was being sent, it gets no
     * hash, otherwise the edit could be absorbed as an unchanged touch.
     */
    fun withSentHash(
        entry: SyncEntry,
        local: Path,
        sent: Pair<Long, Long>?,
        algorithm: HashAlgorithm?,
        log: Logger,
    ): SyncEntry {
        if (sent == null) return entry.copy(localHash = null)
        return withLocalHash(entry, local, sent.first, sent.second, algorithm, log)
    }
}
