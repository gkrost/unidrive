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

    data class Upload(
        override val path: String,
    ) : SyncAction()

    data class DeleteLocal(
        override val path: String,
    ) : SyncAction()

    data class DeleteRemote(
        override val path: String,
    ) : SyncAction()

    data class CreateRemoteFolder(
        override val path: String,
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
        val fromPath: String,
        val remoteId: String,
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
