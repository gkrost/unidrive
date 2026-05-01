package org.krost.unidrive.s3

import org.krost.unidrive.sync.Snapshot
import kotlin.test.*

class S3SnapshotTest {
    private fun S3Snapshot.encode(): String = encode(S3SnapshotEntry.serializer())

    private fun decode(cursor: String): S3Snapshot = Snapshot.decode(cursor, S3SnapshotEntry.serializer())

    @Test
    fun `encode and decode round-trip preserves entries`() {
        val entries =
            mapOf(
                "/folder/file.txt" to
                    S3SnapshotEntry(
                        etag = "\"abc123\"",
                        size = 1024L,
                        lastModified = "2024-01-15T10:00:00Z",
                        isFolder = false,
                    ),
                "/folder/" to
                    S3SnapshotEntry(
                        etag = null,
                        size = 0L,
                        lastModified = null,
                        isFolder = true,
                    ),
            )
        val original = S3Snapshot(entries = entries, timestamp = 1_700_000_000_000L)
        val decoded = decode(original.encode())

        assertEquals(original.entries, decoded.entries)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun `empty snapshot encodes and decodes`() {
        val snapshot = S3Snapshot(entries = emptyMap(), timestamp = 0L)
        val decoded = decode(snapshot.encode())
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `decode rejects invalid base64`() {
        assertFailsWith<Exception> { decode("not-valid-base64!!!") }
    }

    @Test
    fun `encode produces valid base64`() {
        val snapshot = S3Snapshot(entries = emptyMap())
        val encoded = snapshot.encode()
        // Must be decodable without IllegalArgumentException
        val bytes =
            java.util.Base64
                .getDecoder()
                .decode(encoded)
        assertTrue(bytes.isNotEmpty())
    }
}
