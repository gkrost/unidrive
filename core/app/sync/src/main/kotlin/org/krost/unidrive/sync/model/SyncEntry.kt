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
)
