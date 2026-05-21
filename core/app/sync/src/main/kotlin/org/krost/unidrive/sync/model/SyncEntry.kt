package org.krost.unidrive.sync.model

import java.time.Instant

data class SyncEntry(
    val path: String,
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
)

/**
 * Lifecycle state of a [SyncEntry]. EXISTS rows are the only ones the sync
 * loop reads (via the alive-only view); TRASHED and DELETED are tombstones
 * preserved for recovery and for the un-trash-on-scan branch.
 */
enum class EntryStatus { EXISTS, TRASHED, DELETED }
