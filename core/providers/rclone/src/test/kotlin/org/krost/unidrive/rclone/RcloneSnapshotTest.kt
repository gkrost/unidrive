package org.krost.unidrive.rclone

import kotlin.test.*

class RcloneSnapshotTest {
    @Test
    fun `encode and decode round-trip preserves entries`() {
        val entries =
            mapOf(
                "/docs/report.pdf" to
                    RcloneSnapshotEntry(
                        size = 512_000L,
                        modTime = "2025-03-10T14:00:00.000000000Z",
                        isFolder = false,
                        hash = "abc123def456",
                    ),
                "/docs" to
                    RcloneSnapshotEntry(
                        size = 0L,
                        modTime = "2025-03-10T13:00:00.000000000Z",
                        isFolder = true,
                        hash = null,
                    ),
            )
        val original = RcloneSnapshot(entries = entries, timestamp = 1_700_000_000_000L)
        val decoded = RcloneSnapshot.decode(original.encode())

        assertEquals(original.entries, decoded.entries)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun `empty snapshot encodes and decodes`() {
        val snapshot = RcloneSnapshot(entries = emptyMap(), timestamp = 0L)
        val decoded = RcloneSnapshot.decode(snapshot.encode())
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `decode rejects invalid base64`() {
        assertFailsWith<Exception> { RcloneSnapshot.decode("not-valid-base64!!!") }
    }

    @Test
    fun `encode produces valid base64`() {
        val snapshot = RcloneSnapshot(entries = emptyMap())
        val encoded = snapshot.encode()
        val bytes =
            java.util.Base64
                .getDecoder()
                .decode(encoded)
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `hasChanged returns true when hash differs`() {
        val prev = RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, "hash1")
        val curr = RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, "hash2")
        assertTrue(RcloneSnapshot.hasChanged(prev, curr))
    }

    @Test
    fun `hasChanged returns false when hash matches even if modTime differs`() {
        val prev = RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, "samehash")
        val curr = RcloneSnapshotEntry(100L, "2025-06-15T12:00:00Z", false, "samehash")
        assertFalse(RcloneSnapshot.hasChanged(prev, curr))
    }

    @Test
    fun `hasChanged returns true when size differs and no hashes`() {
        val prev = RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, null)
        val curr = RcloneSnapshotEntry(200L, "2025-01-01T00:00:00Z", false, null)
        assertTrue(RcloneSnapshot.hasChanged(prev, curr))
    }

    @Test
    fun `hasChanged returns true when modTime differs and no hashes`() {
        val prev = RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, null)
        val curr = RcloneSnapshotEntry(100L, "2025-06-15T12:00:00Z", false, null)
        assertTrue(RcloneSnapshot.hasChanged(prev, curr))
    }

    @Test
    fun `hasChanged returns false when size and modTime match and no hashes`() {
        val prev = RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, null)
        val curr = RcloneSnapshotEntry(100L, "2025-01-01T00:00:00Z", false, null)
        assertFalse(RcloneSnapshot.hasChanged(prev, curr))
    }
}
