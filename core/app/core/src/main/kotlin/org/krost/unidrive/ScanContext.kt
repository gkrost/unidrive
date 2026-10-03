package org.krost.unidrive

/**
 * Resumable-scan plumbing the engine passes to [CloudProvider.delta]. Providers
 * that opt in stage each page through [persistPage] so a daemon crash mid-scan
 * doesn't waste the prior round-trips — the next launch's `delta()` receives a
 * [ScanContext] whose [resumeMarker] reflects the last persisted boundary and
 * whose [resumedItems] carries the rows that were durably staged before the
 * crash.
 *
 * [resumeMarker] is opaque to the engine. The provider parses it back into its
 * native pagination cursor (Internxt: comma-separated stream offsets; OneDrive
 * uses delta tokens that subsume this and may legitimately ignore the marker).
 *
 * [resumedItems] are the engine's rehydration of the previously-staged rows.
 * Only the cloud-identity fields (id, parentId, name, isFolder, size, modified,
 * hash) are reliable — the `path` field is a placeholder that the provider
 * must overwrite when it rebuilds the folder graph from staged + freshly-
 * fetched pages. Empty list on a fresh scan.
 *
 * [persistPage] is invoked from the provider's pagination loop once per
 * successfully-fetched page, in single-page-at-a-time fashion. Implementations
 * MUST be idempotent — a re-issued page after a transient error MUST NOT
 * duplicate stored rows. The engine implementation backs this with a SQLite
 * transaction so the staged rows + the checkpoint marker advance atomically.
 *
 * [scopeRoots] are the remote subtrees the profile tracks; empty means the
 * whole drive. A full enumeration (cursor = null) may enumerate just these roots,
 * which bounds its cost by the subtree size. Providers that cannot enumerate a
 * subtree ignore it and the engine filters the result.
 *
 * [readOnly] is true when the pass is a preview (a dry-run). The provider must then leave
 * every persistent side effect of a delta call undone: it may read the remote and use its
 * credentials, but must not record "delta seen" markers or other state that a real pass would
 * write. The engine's own state.db writes already go to a throwaway copy.
 *
 * [onProgress] receives what a provider knows about the progress of a long full
 * listing while it runs. It may be called from several coroutines at once and
 * after every page or folder, so it must stay cheap; the receiver throttles.
 * Providers that cannot tell leave it uncalled.
 *
 * Providers that don't have a resume story (snapshot-once APIs, all-in-one
 * recursive listings) leave [ScanContext] unset on `delta()` and continue
 * accumulating in memory as before.
 */
data class ScanContext(
    val resumeMarker: String?,
    val resumedItems: List<CloudItem>,
    val persistPage: suspend (items: List<CloudItem>, marker: String) -> Unit,
    val scopeRoots: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val onProgress: ((ScanProgress) -> Unit)? = null,
)

/**
 * One progress report of a running listing. [items] is what has been gathered so far.
 * The folder counts describe a folder walk and stay null for any other listing:
 * [foldersDone] folders listed or skipped, [foldersKnown] folders discovered so far
 * (a lower bound of the total), [foldersSkipped] folders that failed and were skipped.
 * [listing] names the strategy, [LISTING_ACCOUNT] or [LISTING_TREE].
 */
data class ScanProgress(
    val items: Int,
    val foldersDone: Int? = null,
    val foldersKnown: Int? = null,
    val foldersSkipped: Int? = null,
    val listing: String? = null,
) {
    companion object {
        /** Account-wide offset pagination. */
        const val LISTING_ACCOUNT: String = "account"

        /** Folder-by-folder walk of the tree. */
        const val LISTING_TREE: String = "tree"
    }
}
