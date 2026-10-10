package org.krost.unidrive.sync.model

import java.time.Instant

data class SyncEntry(
    val path: String,
    // #115: canonical REMOTE path for a row whose local [path] is an XDG
    // locale alias (e.g. path=/Bilder/x.jpg, remotePath=/Pictures/x.jpg).
    // When null the remote path EQUALS [path] — the universal, non-aliased
    // case, so a null here is byte-identical to pre-#115 behaviour. The
    // effective remote path of any row is therefore `remotePath ?: path`.
    // [path] always stays the REAL local sync_root-relative path so
    // LocalScanner (which keys/scans by the on-disk name) keeps finding the
    // row; the reconciler's remote-delta match uses the effective remote
    // path so an uploaded-to-canonical row is found by its canonical delta
    // key without re-uploading or planning a spurious local move.
    val remotePath: String? = null,
    val remoteId: String?,
    val remoteHash: String?,
    val remoteSize: Long,
    val remoteModified: Instant?,
    val localMtime: Long?,
    val localSize: Long?,
    val isFolder: Boolean,
    val isPinned: Boolean,
    val isHydrated: Boolean,
    val lastSynced: Instant,
    // Parent's provider UUID. Internxt populates from folderUuid/parentUuid so
    // alive children of a folder are reachable via the (parent_uuid, status)
    // composite index. Null = drive root, OR provider doesn't track parent
    // identity (OneDrive in v1), OR row is a pending-upload placeholder whose
    // cloud parent isn't known yet.
    val parentUuid: String? = null,
    val status: EntryStatus = EntryStatus.EXISTS,
    // Permanent-failure quarantine flag. True when a download against this
    // row's remote_id returned a stable 404 ("object is gone, retry won't
    // recover it"). The recovery loops in Reconciler skip quarantined rows
    // so the engine doesn't burn cycles retrying the same dead identifier;
    // the next delta event that re-reports the same remote_id clears the
    // flag (handled in updateRemoteEntries).
    val downloadQuarantined: Boolean = false,
    // Timestamp of the last permanent failure that set [downloadQuarantined].
    // Recorded for operator audit; not currently used as input to any
    // policy decision.
    val lastErrorAt: Instant? = null,
    // #396: SHA-256 (lowercase hex) of the local file's bytes as the engine last wrote
    // (download) or sent (upload) them. Recorded only for providers with no remote content
    // hash (hashAlgorithm() == null), where it is the only way LocalScanner can tell a
    // touched-but-unchanged file (mtime bumped by a shell handler, indexer, antivirus...)
    // from a real edit. Null = unknown, which keeps the plain mtime+size behaviour.
    val localHash: String? = null,
    // #449: which file the local baseline (localMtime, localSize, localHash) describes. Written by the
    // mount (MountEngine); the mirror engine never sets it.
    // true  = the hydration cache copy: the bytes were written or downloaded into the cache.
    // false = not the cache copy: the mount's cache sweep found a cache copy that is not the baseline
    //         (MountEngine.evictCacheCopy), or a legacy hybrid row whose baseline was the sync-root file.
    // null  = unknown: rows written before the column, and every row the mirror engine writes. The
    //         mount's cache sweep treats the cache copy as the baseline only while its mtime and size
    //         match the row (MountEngine.cacheDisposition).
    // Only meaningful while [isHydrated]; the database stores null for a row without local bytes.
    // The mirror's Reconciler does not read it (#680): in a mirror profile a missing sync-root file is
    // a local delete whatever this column holds.
    val cacheBacked: Boolean? = null,
) {
    // UD-901 / #136: the pending-upload predicate, assembled in ONE place. A pending
    // upload is a file whose only copy is the local bytes and which has never
    // reached the cloud. Consumers used to re-assemble the predicate from halves
    // (`remoteId == null` here, `isHydrated` there), and rows in the gap — a sparse
    // partial-download row (remoteId == null, isHydrated == false) has no real bytes
    // to upload — were counted or re-uploaded by whichever half the consumer checked.
    // Where the local bytes are follows from the profile's mode (#560): a mirror
    // profile's pending rows are LocalScanner's (bytes in the sync root, uploaded by
    // the next sync), a mount profile's are the mount's (bytes in the hydration cache,
    // replayed by the daemon). A legacy profile converted to a mount can still hold
    // scanner rows whose bytes were quarantined; the replay skips a row without a cache
    // copy (HydrationImpl.replayable).
    // Deliberately a computed property: derived from two stored columns, so it takes
    // no part in equals/copy.
    val isPendingUpload: Boolean
        get() = remoteId == null && isHydrated
}

/**
 * Lifecycle state of a [SyncEntry]. EXISTS rows are the only ones the sync
 * loop reads (via the alive-only view); TRASHED and DELETED are tombstones
 * preserved for recovery and for the un-trash-on-scan branch.
 */
enum class EntryStatus { EXISTS, TRASHED, DELETED }
