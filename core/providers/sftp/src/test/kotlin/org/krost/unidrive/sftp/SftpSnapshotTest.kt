package org.krost.unidrive.sftp

import kotlin.test.*

class SftpSnapshotTest {
    @Test
    fun `encode and decode round-trip preserves entries`() {
        val entries =
            mapOf(
                "/docs/report.pdf" to SftpSnapshotEntry(size = 204800L, mtimeSeconds = 1_700_000_000L, isFolder = false),
                "/docs/" to SftpSnapshotEntry(size = 0L, mtimeSeconds = 1_699_000_000L, isFolder = true),
            )
        val original = SftpSnapshot(entries = entries, timestamp = 1_700_000_000_000L)
        val decoded = SftpSnapshot.decode(original.encode())

        assertEquals(original.entries, decoded.entries)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun `empty snapshot encodes and decodes`() {
        val snapshot = SftpSnapshot(entries = emptyMap(), timestamp = 0L)
        val decoded = SftpSnapshot.decode(snapshot.encode())
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `decode rejects invalid base64`() {
        assertFailsWith<Exception> { SftpSnapshot.decode("not-valid-base64!!!") }
    }

    @Test
    fun `encode produces valid base64`() {
        val snapshot = SftpSnapshot(entries = emptyMap())
        val encoded = snapshot.encode()
        val bytes =
            java.util.Base64
                .getDecoder()
                .decode(encoded)
        assertTrue(bytes.isNotEmpty())
    }
}
