package org.krost.unidrive

import java.time.Instant

data class CloudItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val isFolder: Boolean,
    val modified: Instant?,
    val created: Instant?,
    val hash: String?,
    val mimeType: String?,
    val deleted: Boolean = false,
    val hydrated: Boolean = true,
    // Parent's provider UUID. Internxt populates from folderUuid/parentUuid so
    // state.db can index alive children of a folder via (parent_uuid, status).
    // Null = drive root (or provider doesn't expose parent identity).
    val parentId: String? = null,
    // #183: Graph `@microsoft.graph.removed` with `state="removed"` signals that
    // access was revoked (shared link revoked, item moved out of scope). The item
    // still physically exists on the remote — the local copy MUST be kept — but the
    // DB row should be retired (TRASHED) so it is no longer an unreapable orphan.
    // Distinct from [deleted]=true which means a hard delete and triggers local
    // file removal. [accessRevoked] is only set by OneDriveProvider; all other
    // providers and construction sites leave it at the safe default of false.
    val accessRevoked: Boolean = false,
)
