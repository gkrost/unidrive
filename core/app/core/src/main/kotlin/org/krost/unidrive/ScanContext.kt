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
 * Providers that don't have a resume story (snapshot-once APIs, all-in-one
 * recursive listings) leave [ScanContext] unset on `delta()` and continue
 * accumulating in memory as before.
 */
data class ScanContext(
    val resumeMarker: String?,
    val resumedItems: List<CloudItem>,
    val persistPage: suspend (items: List<CloudItem>, marker: String) -> Unit,
)
