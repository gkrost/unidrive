package org.krost.unidrive.sync.model

import org.krost.unidrive.CloudItem

sealed class SyncAction {
    abstract val path: String

    data class CreatePlaceholder(
        override val path: String,
        val remoteItem: CloudItem,
        val shouldHydrate: Boolean,
    ) : SyncAction()

    data class UpdatePlaceholder(
        override val path: String,
        val remoteItem: CloudItem,
        val wasHydrated: Boolean,
    ) : SyncAction()

    data class DownloadContent(
        override val path: String,
        val remoteItem: CloudItem,
    ) : SyncAction()

    /**
     * UD-366: [remoteId] carries the existing remote UUID for MODIFIED uploads so providers
     * with replace-in-place semantics (Internxt `PUT /files/{uuid}`) can overwrite the entry
     * instead of POSTing a duplicate that 409s. NEW uploads leave it null.
     */
    data class Upload(
        override val path: String,
        val remoteId: String? = null,
        // #115: canonical REMOTE path to upload to when [path] is a local
        // locale alias (e.g. path=/Bilder/x.jpg, remoteTarget=/Pictures/x.jpg).
        // null = remote path equals [path] (the non-aliased default). The
        // executor reads the local file at [path] but uploads to
        // `remoteTarget ?: path`.
        val remoteTarget: String? = null,
    ) : SyncAction()

    data class DeleteLocal(
        override val path: String,
    ) : SyncAction()

    data class DeleteRemote(
        override val path: String,
    ) : SyncAction()

    data class CreateRemoteFolder(
        override val path: String,
        // #115: canonical REMOTE path to create when [path] is under a local
        // locale alias. null = remote path equals [path] (non-aliased default).
        val remoteTarget: String? = null,
    ) : SyncAction()

    data class Conflict(
        override val path: String,
        val localState: ChangeState,
        val remoteState: ChangeState,
        val remoteItem: CloudItem?,
        val policy: ConflictPolicy,
    ) : SyncAction()

    data class MoveRemote(
        override val path: String,
        // [fromPath] is the REAL-LOCAL source path (the source row's `path`
        // column), so the executor's DB ops (getEntry / deleteEntry /
        // renamePrefix) and resolveLocal land on the right row / on-disk dir.
        val fromPath: String,
        val remoteId: String,
        // #115: canonical REMOTE destination path when [path] is under a local
        // locale alias. null = remote destination equals [path] (non-aliased
        // default). The canonical remote SOURCE is derived in the executor from
        // the looked-up source row's `remotePath ?: fromPath`, so no separate
        // field is needed here.
        val remoteTarget: String? = null,
    ) : SyncAction()

    data class MoveLocal(
        override val path: String,
        val fromPath: String,
        val remoteItem: CloudItem,
    ) : SyncAction()

    data class RemoveEntry(
        override val path: String,
    ) : SyncAction()
}
