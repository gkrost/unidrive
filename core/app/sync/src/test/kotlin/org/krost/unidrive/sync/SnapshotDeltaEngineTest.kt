package org.krost.unidrive.sync

import kotlinx.serialization.Serializable
import org.krost.unidrive.CloudItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the shared `computeSnapshotDelta` engine (UD-346).
 *
 * Each provider open-codes its own `hasChanged`, `deletedItem` and
 * path-indexing; the engine itself is exercised here against a generic
 * fixture entry shape that covers the four interesting cases:
 *
 *   1. null cursor → return all
 *   2. unchanged → empty changes
 *   3. added + modified → present in changes (not deleted)
 *   4. deleted → present in changes (deleted = true)
 */
class SnapshotDeltaEngineTest {
    @Serializable
    private data class Entry(
        val size: Long,
        val tag: String,
        val isFolder: Boolean,
    )

    private fun item(
        path: String,
        size: Long = 0,
        isFolder: Boolean = false,
    ): CloudItem =
        CloudItem(
            id = path,
            name = path.substringAfterLast("/"),
            path = path,
            size = size,
            isFolder = isFolder,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
        )

    private fun deletedItem(
        path: String,
        entry: Entry,
    ): CloudItem =
        CloudItem(
            id = path,
            name = path.substringAfterLast("/"),
            path = path,
            size = 0,
            isFolder = entry.isFolder,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
            deleted = true,
        )

    private val hasChanged = { prev: Entry, curr: Entry -> prev.tag != curr.tag || prev.size != curr.size }

    @Test
    fun `null cursor returns all items as a fresh full scan`() {
        val entries =
            mapOf(
                "/a.txt" to Entry(100, "tag-a", false),
                "/b.txt" to Entry(200, "tag-b", false),
            )
        val items = entries.mapValues { (path, _) -> item(path) }
        val page =
            computeSnapshotDelta(
                currentEntries = entries,
                currentItemsByPath = items,
                prevCursor = null,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            )
        assertEquals(2, page.items.size)
        assertFalse(page.hasMore)
        assertTrue(page.items.none { it.deleted })
        assertTrue(page.cursor.isNotEmpty())
    }

    @Test
    fun `unchanged entries produce empty change set`() {
        val entries = mapOf("/x.txt" to Entry(50, "tag-x", false))
        val items = entries.mapValues { (path, _) -> item(path) }
        val priorCursor =
            computeSnapshotDelta(
                currentEntries = entries,
                currentItemsByPath = items,
                prevCursor = null,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            ).cursor

        val page =
            computeSnapshotDelta(
                currentEntries = entries,
                currentItemsByPath = items,
                prevCursor = priorCursor,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            )
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `added and modified entries surface as non-deleted changes`() {
        val prev = mapOf("/keep.txt" to Entry(10, "v1", false))
        val priorCursor =
            computeSnapshotDelta(
                currentEntries = prev,
                currentItemsByPath = prev.mapValues { (p, _) -> item(p) },
                prevCursor = null,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            ).cursor

        val curr =
            mapOf(
                "/keep.txt" to Entry(10, "v2", false), // tag changed
                "/added.txt" to Entry(99, "vN", false), // new
            )
        val items = curr.mapValues { (p, _) -> item(p) }
        val page =
            computeSnapshotDelta(
                currentEntries = curr,
                currentItemsByPath = items,
                prevCursor = priorCursor,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            )
        assertEquals(2, page.items.size)
        assertTrue(page.items.none { it.deleted })
        assertEquals(setOf("/keep.txt", "/added.txt"), page.items.map { it.path }.toSet())
    }

    @Test
    fun `deleted entries surface as deleted CloudItems`() {
        val prev =
            mapOf(
                "/a.txt" to Entry(10, "v1", false),
                "/b.txt" to Entry(20, "v2", false),
            )
        val priorCursor =
            computeSnapshotDelta(
                currentEntries = prev,
                currentItemsByPath = prev.mapValues { (p, _) -> item(p) },
                prevCursor = null,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            ).cursor

        val curr = mapOf("/a.txt" to Entry(10, "v1", false))
        val page =
            computeSnapshotDelta(
                currentEntries = curr,
                currentItemsByPath = curr.mapValues { (p, _) -> item(p) },
                prevCursor = priorCursor,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            )
        assertEquals(1, page.items.size)
        assertEquals("/b.txt", page.items[0].path)
        assertTrue(page.items[0].deleted)
    }

    @Test
    fun `mixed scenario — added, modified, unchanged, deleted in one page`() {
        val prev =
            mapOf(
                "/keep.txt" to Entry(1, "v1", false),
                "/changed.txt" to Entry(2, "v1", false),
                "/gone.txt" to Entry(3, "v1", false),
            )
        val priorCursor =
            computeSnapshotDelta(
                currentEntries = prev,
                currentItemsByPath = prev.mapValues { (p, _) -> item(p) },
                prevCursor = null,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            ).cursor

        val curr =
            mapOf(
                "/keep.txt" to Entry(1, "v1", false), // unchanged
                "/changed.txt" to Entry(2, "v2", false), // modified (tag)
                "/added.txt" to Entry(4, "v1", false), // added
            )
        val page =
            computeSnapshotDelta(
                currentEntries = curr,
                currentItemsByPath = curr.mapValues { (p, _) -> item(p) },
                prevCursor = priorCursor,
                entrySerializer = Entry.serializer(),
                hasChanged = hasChanged,
                deletedItem = ::deletedItem,
            )
        val byPath = page.items.associateBy { it.path }
        assertEquals(3, page.items.size, "expected changed + added + deleted, got ${page.items.map { it.path }}")
        assertFalse(byPath.getValue("/changed.txt").deleted)
        assertFalse(byPath.getValue("/added.txt").deleted)
        assertTrue(byPath.getValue("/gone.txt").deleted)
    }
}
