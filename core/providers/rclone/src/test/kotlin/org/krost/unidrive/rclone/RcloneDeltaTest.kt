package org.krost.unidrive.rclone

import kotlin.test.*

class RcloneDeltaTest {
    private fun makeSnapshot(entries: Map<String, RcloneSnapshotEntry>): String =
        RcloneSnapshot(entries = entries).encode(RcloneSnapshotEntry.serializer())

    @Test
    fun `computeDelta with null cursor returns all items`() {
        val entries =
            listOf(
                RcloneEntry("docs/a.txt", "a.txt", 100L, null, "2025-01-01T00:00:00Z", false, mapOf("MD5" to "hash1")),
                RcloneEntry("docs/b.txt", "b.txt", 200L, null, "2025-01-02T00:00:00Z", false, mapOf("MD5" to "hash2")),
            )

        val page = RcloneProvider.computeDelta(entries, null, "")
        assertEquals(2, page.items.size)
        assertFalse(page.hasMore)
        assertTrue(page.cursor.isNotEmpty())
        assertTrue(page.items.all { !it.deleted })
    }

    @Test
    fun `computeDelta detects new files`() {
        val prev =
            makeSnapshot(
                mapOf(
                    "/a.txt" to RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, "hash1"),
                ),
            )
        val currentEntries =
            listOf(
                RcloneEntry("a.txt", "a.txt", 100L, null, "2025-01-01T00:00:00Z", false, mapOf("MD5" to "hash1")),
                RcloneEntry("b.txt", "b.txt", 200L, null, "2025-01-02T00:00:00Z", false, mapOf("MD5" to "hash2")),
            )

        val page = RcloneProvider.computeDelta(currentEntries, prev, "")
        assertEquals(1, page.items.size)
        assertEquals("/b.txt", page.items[0].path)
        assertFalse(page.items[0].deleted)
    }

    @Test
    fun `computeDelta detects modified files by hash`() {
        val prev =
            makeSnapshot(
                mapOf(
                    "/a.txt" to RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, "oldhash"),
                ),
            )
        val currentEntries =
            listOf(
                RcloneEntry("a.txt", "a.txt", 100L, null, "2025-01-01T00:00:00Z", false, mapOf("MD5" to "newhash")),
            )

        val page = RcloneProvider.computeDelta(currentEntries, prev, "")
        assertEquals(1, page.items.size)
        assertEquals("/a.txt", page.items[0].path)
        assertFalse(page.items[0].deleted)
    }

    @Test
    fun `computeDelta detects modified files by size when no hash`() {
        val prev =
            makeSnapshot(
                mapOf(
                    "/a.txt" to RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, null),
                ),
            )
        val currentEntries =
            listOf(
                RcloneEntry("a.txt", "a.txt", 200L, null, "2025-01-01T00:00:00Z", false, null),
            )

        val page = RcloneProvider.computeDelta(currentEntries, prev, "")
        assertEquals(1, page.items.size)
    }

    @Test
    fun `computeDelta detects deleted files`() {
        val prev =
            makeSnapshot(
                mapOf(
                    "/a.txt" to RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, "hash1"),
                    "/b.txt" to RcloneSnapshotEntry(200L, "2025-01-02T00:00:00Z", false, "hash2"),
                ),
            )
        val currentEntries =
            listOf(
                RcloneEntry("a.txt", "a.txt", 100L, null, "2025-01-01T00:00:00Z", false, mapOf("MD5" to "hash1")),
            )

        val page = RcloneProvider.computeDelta(currentEntries, prev, "")
        assertEquals(1, page.items.size)
        assertEquals("/b.txt", page.items[0].path)
        assertTrue(page.items[0].deleted)
    }

    @Test
    fun `computeDelta returns empty items when nothing changed`() {
        val prev =
            makeSnapshot(
                mapOf(
                    "/a.txt" to RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, "hash1"),
                ),
            )
        val currentEntries =
            listOf(
                RcloneEntry("a.txt", "a.txt", 100L, null, "2025-01-01T00:00:00Z", false, mapOf("MD5" to "hash1")),
            )

        val page = RcloneProvider.computeDelta(currentEntries, prev, "")
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `computeDelta ignores base path in virtual paths`() {
        val entries =
            listOf(
                RcloneEntry("a.txt", "a.txt", 100L, null, "2025-01-01T00:00:00Z", false, null),
            )

        // basePath is ignored — rclone returns paths relative to remote root
        val page = RcloneProvider.computeDelta(entries, null, "sub")
        assertEquals("/a.txt", page.items[0].path)
    }
}
