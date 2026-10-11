package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.EntryStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * #737: the sync engine's [FileInTheWayGuard]. A file in the way of a remote folder may be replaced
 * without a copy only when its state row proves the bytes on disk are the bytes the cloud holds:
 *  - the row is a synced, hydrated file (a remote id, not a folder, not quarantined, not a tombstone);
 *  - the file is unchanged since the engine recorded it (size and mtime equal the row's local
 *    baseline) and its size equals the row's remote size;
 *  - the content matches: the provider's own hash against the row's remote hash, or, for a provider
 *    without one, the SHA-256 the engine recorded when it wrote or sent these bytes;
 *  - this pass's remote changes do not say the file is gone: no deletion of its remote id, and its
 *    path is not now reported as a folder, deleted, or another item.
 * Anything missing or unreadable answers false, and the file is kept as a conflict copy.
 */
internal class CloudCopyProof(
    private val db: StateDatabase,
    private val hashAlgorithm: HashAlgorithm?,
    private val passRemoteChanges: () -> Map<String, CloudItem>,
) : FileInTheWayGuard {
    override fun bytesProvablyInCloud(
        relativePath: String,
        file: Path,
    ): Boolean {
        val row = db.getEntry(relativePath) ?: return false
        val remoteId = row.remoteId
        if (remoteId.isNullOrEmpty() || row.isFolder || !row.isHydrated) return false
        if (row.status != EntryStatus.EXISTS || row.downloadQuarantined) return false

        val size = Files.size(file)
        val mtime = Files.getLastModifiedTime(file).toMillis()
        if (size != row.localSize || mtime != row.localMtime || size != row.remoteSize) return false

        val changes = passRemoteChanges()
        changes[row.remotePath ?: row.path]?.let { item ->
            if (item.deleted || item.isFolder || item.id != remoteId) return false
        }
        if (changes.values.any { it.deleted && it.id == remoteId }) return false

        return if (hashAlgorithm != null) {
            HashVerifier.matches(file, row.remoteHash, hashAlgorithm)
        } else {
            val recorded = row.localHash ?: return false
            HashVerifier.computeSha256Hex(file).equals(recorded, ignoreCase = true)
        }
    }
}
