package org.krost.unidrive.hidrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeltaSnapshotTest {
    @Test
    fun `encode and decode round-trip`() {
        val snapshot =
            DeltaSnapshot(
                entries =
                    mapOf(
                        "/Documents/file.txt" to SnapshotEntry("id1", "hash1", 1024, 1700000000, false),
                        "/Photos" to SnapshotEntry("id2", null, 0, 1700000001, true),
                    ),
                timestamp = 1700000000000,
            )

        val encoded = snapshot.encode()
        val decoded = DeltaSnapshot.decode(encoded)

        assertEquals(snapshot.entries.size, decoded.entries.size)
        assertEquals(snapshot.entries["/Documents/file.txt"], decoded.entries["/Documents/file.txt"])
        assertEquals(snapshot.entries["/Photos"], decoded.entries["/Photos"])
        assertEquals(snapshot.timestamp, decoded.timestamp)
    }

    @Test
    fun `empty snapshot encodes and decodes`() {
        val snapshot = DeltaSnapshot(entries = emptyMap(), timestamp = 0)
        val decoded = DeltaSnapshot.decode(snapshot.encode())
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `detect new items between snapshots`() {
        val previous =
            DeltaSnapshot(
                entries =
                    mapOf(
                        "/file1.txt" to SnapshotEntry("id1", "hash1", 100, 1000, false),
                    ),
            )
        val current =
            DeltaSnapshot(
                entries =
                    mapOf(
                        "/file1.txt" to SnapshotEntry("id1", "hash1", 100, 1000, false),
                        "/file2.txt" to SnapshotEntry("id2", "hash2", 200, 2000, false),
                    ),
            )

        val newPaths = current.entries.keys - previous.entries.keys
        assertEquals(setOf("/file2.txt"), newPaths)
    }

    @Test
    fun `detect deleted items between snapshots`() {
        val previous =
            DeltaSnapshot(
                entries =
                    mapOf(
                        "/file1.txt" to SnapshotEntry("id1", "hash1", 100, 1000, false),
                        "/file2.txt" to SnapshotEntry("id2", "hash2", 200, 2000, false),
                    ),
            )
        val current =
            DeltaSnapshot(
                entries =
                    mapOf(
                        "/file1.txt" to SnapshotEntry("id1", "hash1", 100, 1000, false),
                    ),
            )

        val deletedPaths = previous.entries.keys - current.entries.keys
        assertEquals(setOf("/file2.txt"), deletedPaths)
    }

    @Test
    fun `detect modified items by chash change`() {
        val previous =
            DeltaSnapshot(
                entries =
                    mapOf(
                        "/file.txt" to SnapshotEntry("id1", "hash_old", 100, 1000, false),
                    ),
            )
        val current =
            DeltaSnapshot(
                entries =
                    mapOf(
                        "/file.txt" to SnapshotEntry("id1", "hash_new", 150, 2000, false),
                    ),
            )

        val entry = current.entries["/file.txt"]!!
        val prevEntry = previous.entries["/file.txt"]!!
        val isModified = entry.chash != prevEntry.chash || entry.size != prevEntry.size || entry.mtime != prevEntry.mtime
        assertTrue(isModified)
    }

    @Test
    fun `unchanged items not detected as modified`() {
        val entry = SnapshotEntry("id1", "hash1", 100, 1000, false)
        val previous = DeltaSnapshot(entries = mapOf("/file.txt" to entry))
        val current = DeltaSnapshot(entries = mapOf("/file.txt" to entry))

        val prevEntry = previous.entries["/file.txt"]!!
        val curEntry = current.entries["/file.txt"]!!
        val isModified = curEntry.chash != prevEntry.chash || curEntry.size != prevEntry.size || curEntry.mtime != prevEntry.mtime
        assertFalse(isModified)
    }

    @Test
    fun `folder entries have null chash`() {
        val snapshot =
            DeltaSnapshot(
                entries =
                    mapOf(
                        "/Documents" to SnapshotEntry("id1", null, 0, 1000, true),
                    ),
            )
        val decoded = DeltaSnapshot.decode(snapshot.encode())
        val entry = decoded.entries["/Documents"]!!
        assertTrue(entry.isFolder)
        assertEquals(null, entry.chash)
    }
}
