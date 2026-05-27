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
)

/**
 * Lifecycle state of a [SyncEntry]. EXISTS rows are the only ones the sync
 * loop reads (via the alive-only view); TRASHED and DELETED are tombstones
 * preserved for recovery and for the un-trash-on-scan branch.
 */
enum class EntryStatus { EXISTS, TRASHED, DELETED }
