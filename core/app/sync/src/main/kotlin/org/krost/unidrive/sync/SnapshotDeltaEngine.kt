package org.krost.unidrive.sync

import kotlinx.serialization.KSerializer
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage

/**
 * Snapshot-based diff loop shared by every snapshot-cursor provider
 * (HiDrive, S3, SFTP, WebDAV, Rclone, LocalFs).
 *
 * Lifted in UD-346. Each provider's `delta()` previously open-coded the
 * same ~30-line pattern: build current snapshot, return-all-on-null,
 * detect added/changed via the per-provider `hasChanged` predicate,
 * detect deleted by previous-snapshot keys not in current.
 *
 * The provider supplies pre-indexed inputs:
 * - [currentEntries] — the snapshot map (path -> entry) that will be
 *   serialised into the new cursor and compared against [prevCursor].
 * - [currentItemsByPath] — the same paths mapped to fully-resolved
 *   `CloudItem` instances. Providers build this themselves so per-provider
 *   path-normalisation quirks (S3 `keyToPath`, Rclone `"/${path}"`,
 *   WebDAV `path.ifEmpty { "/" }` etc.) stay in the provider, not here.
 *
 * The engine is generic over the entry shape `E` and takes:
 * - [entrySerializer] — for cursor encode/decode of `Snapshot<E>`.
 * - [hasChanged] — provider-specific change predicate (etag vs hash vs
 *   size+mtime).
 * - [deletedItem] — builds the deleted-`CloudItem` placeholder; the entry
 *   types differ (some carry an `id`, some need to fall back to the path)
 *   so the provider supplies the constructor.
 */
fun <E> computeSnapshotDelta(
    currentEntries: Map<String, E>,
    currentItemsByPath: Map<String, CloudItem>,
    prevCursor: String?,
    entrySerializer: KSerializer<E>,
    hasChanged: (prev: E, curr: E) -> Boolean,
    deletedItem: (path: String, entry: E) -> CloudItem,
): DeltaPage {
    val currentSnapshot = Snapshot(currentEntries)
    val newCursor = currentSnapshot.encode(entrySerializer)

    if (prevCursor == null) {
        return DeltaPage(
            items = currentItemsByPath.values.toList(),
            cursor = newCursor,
            hasMore = false,
        )
    }

    val previousSnapshot = Snapshot.decode(prevCursor, entrySerializer)
    val changes = mutableListOf<CloudItem>()

    // New + modified entries.
    for ((path, entry) in currentEntries) {
        val prev = previousSnapshot.entries[path]
        if (prev == null || hasChanged(prev, entry)) {
            currentItemsByPath[path]?.let(changes::add)
        }
    }

    // Deleted entries — anything in previous but not in current.
    for ((path, entry) in previousSnapshot.entries) {
        if (path !in currentEntries) {
            changes.add(deletedItem(path, entry))
        }
    }

    return DeltaPage(
        items = changes,
        cursor = newCursor,
        hasMore = false,
    )
}
