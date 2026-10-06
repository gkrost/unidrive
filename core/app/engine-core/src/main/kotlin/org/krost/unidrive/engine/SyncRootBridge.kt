package org.krost.unidrive.engine

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Path

/**
 * #560 U3: the mount's access to the mirror's sync root, the coordinated model of
 * unidrive-windows#84 that Option 2 retires. The mount front-end (`MountEngine`, :app:hydration)
 * still serves reads from a current sync-root copy (#449), mirrors what it writes and makes into
 * the sync root (#449, #500), drops or moves that copy on a delete or rename (#449 review, #568),
 * and rescans the sync root for files that arrived there out of band (#504). The sync root, its
 * name rules and its scanner belong to the mirror engine (`SyncEngine`, :app:sync), which
 * implements this bridge; the mount reaches them only through it.
 *
 * The bridge goes when mount profiles stop touching a sync root (#560 U6, cutover).
 */
interface SyncRootBridge {
    /** The sync root folder (it may not exist: a mount-only profile never creates it). */
    val root: Path

    /** The sync-root file of a remote [path]. Throws when the name cannot be represented locally. */
    fun resolveLocal(path: String): Path

    /** Why [path] cannot be represented in the sync root, or null when it can. */
    fun localNameIssue(path: String): String?

    /** #418: true when [local] has the shape of a placeholder for [entry] (it holds no real bytes). */
    fun looksLikePlaceholder(
        local: Path,
        entry: SyncEntry,
    ): Boolean

    /** Runs [block] with the mirror's local watcher told to ignore events for [path]. */
    fun <T> withEchoSuppression(
        path: String,
        block: () -> T,
    ): T

    /** UD-299: whether two absolute, normalised sync-root paths name the same folder. */
    fun sameRoot(
        stored: String,
        current: String,
    ): Boolean

    /**
     * #504/#552: the files and folders in the sync root that are new or modified since their rows
     * were recorded, in the scanner's order. No deletion detection. The scan writes the pending rows
     * the mirror's scanner always writes for new files.
     */
    fun scanNewAndModified(): List<LocalChange>

    /** #504: create [path] in the cloud the way a sync pass does (the mirror's folder executor). */
    suspend fun createRemoteFolder(path: String)

    /** #504: upload the sync-root file of [path] the way a sync pass does (the mirror's upload executor). */
    suspend fun upload(
        path: String,
        remoteId: String?,
        remoteTarget: String?,
    )

    /** One entry of [scanNewAndModified]: [isNew] is false for a modified one. */
    data class LocalChange(
        val path: String,
        val isNew: Boolean,
    )
}
