package org.krost.unidrive.localfs

import org.krost.unidrive.sync.Snapshot
import kotlin.test.*

class LocalFsSnapshotTest {
    private fun LocalFsSnapshot.encode(): String = encode(LocalFsSnapshotEntry.serializer())

    private fun decode(cursor: String): LocalFsSnapshot = Snapshot.decode(cursor, LocalFsSnapshotEntry.serializer())

    @Test
    fun `encode and decode round-trip preserves entries`() {
        val entries =
            mapOf(
                "docs/report.pdf" to LocalFsSnapshotEntry(size = 204800L, mtimeMillis = 1_700_000_000_000L, isFolder = false),
                "docs" to LocalFsSnapshotEntry(size = 0L, mtimeMillis = 1_699_000_000_000L, isFolder = true),
            )
        val original = LocalFsSnapshot(entries = entries, timestamp = 1_700_000_000_000L)
        val decoded = decode(original.encode())

        assertEquals(original.entries, decoded.entries)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun `empty snapshot encodes and decodes`() {
        val snapshot = LocalFsSnapshot(entries = emptyMap(), timestamp = 0L)
        val decoded = decode(snapshot.encode())
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `decode rejects invalid base64`() {
        assertFailsWith<Exception> { decode("not-valid-base64!!!") }
    }

    @Test
    fun `hasChanged detects mtime difference`() {
        val old = mapOf("a.txt" to LocalFsSnapshotEntry(size = 100L, mtimeMillis = 1000L, isFolder = false))
        val new = mapOf("a.txt" to LocalFsSnapshotEntry(size = 100L, mtimeMillis = 2000L, isFolder = false))
        val prev = LocalFsSnapshot(entries = old)
        val curr = LocalFsSnapshot(entries = new)

        val changed =
            curr.entries.filter { (path, entry) ->
                val p = prev.entries[path]
                p == null || p.size != entry.size || p.mtimeMillis != entry.mtimeMillis
            }
        assertEquals(1, changed.size)
        assertEquals("a.txt", changed.keys.first())
    }

    @Test
    fun `hasChanged detects size difference`() {
        val old = mapOf("a.txt" to LocalFsSnapshotEntry(size = 100L, mtimeMillis = 1000L, isFolder = false))
        val new = mapOf("a.txt" to LocalFsSnapshotEntry(size = 200L, mtimeMillis = 1000L, isFolder = false))
        val prev = LocalFsSnapshot(entries = old)
        val curr = LocalFsSnapshot(entries = new)

        val changed =
            curr.entries.filter { (path, entry) ->
                val p = prev.entries[path]
                p == null || p.size != entry.size || p.mtimeMillis != entry.mtimeMillis
            }
        assertEquals(1, changed.size)
    }

    @Test
    fun `hasChanged detects new file`() {
        val old = mapOf("a.txt" to LocalFsSnapshotEntry(size = 100L, mtimeMillis = 1000L, isFolder = false))
        val new = old + ("b.txt" to LocalFsSnapshotEntry(size = 50L, mtimeMillis = 1500L, isFolder = false))
        val prev = LocalFsSnapshot(entries = old)
        val curr = LocalFsSnapshot(entries = new)

        val changed =
            curr.entries.filter { (path, entry) ->
                val p = prev.entries[path]
                p == null || p.size != entry.size || p.mtimeMillis != entry.mtimeMillis
            }
        assertEquals(1, changed.size)
        assertEquals("b.txt", changed.keys.first())
    }

    @Test
    fun `hasChanged detects deleted file`() {
        val old =
            mapOf(
                "a.txt" to LocalFsSnapshotEntry(size = 100L, mtimeMillis = 1000L, isFolder = false),
                "b.txt" to LocalFsSnapshotEntry(size = 50L, mtimeMillis = 1500L, isFolder = false),
            )
        val new = mapOf("a.txt" to LocalFsSnapshotEntry(size = 100L, mtimeMillis = 1000L, isFolder = false))
        val prev = LocalFsSnapshot(entries = old)
        val curr = LocalFsSnapshot(entries = new)

        val deleted = prev.entries.keys - curr.entries.keys
        assertEquals(setOf("b.txt"), deleted)
    }
}
