package org.krost.unidrive.webdav

import org.krost.unidrive.sync.Snapshot
import kotlin.test.*

class WebDavSnapshotTest {
    private fun WebDavSnapshot.encode(): String = encode(WebDavSnapshotEntry.serializer())

    private fun decode(cursor: String): WebDavSnapshot = Snapshot.decode(cursor, WebDavSnapshotEntry.serializer())

    @Test
    fun `encode and decode round-trip preserves entries`() {
        val entries =
            mapOf(
                "/files/report.pdf" to
                    WebDavSnapshotEntry(
                        size = 512_000L,
                        lastModified = "2024-03-10T14:00:00Z",
                        etag = "abc123",
                        isFolder = false,
                    ),
                "/files/" to
                    WebDavSnapshotEntry(
                        size = 0L,
                        lastModified = null,
                        etag = null,
                        isFolder = true,
                    ),
            )
        val original = WebDavSnapshot(entries = entries, timestamp = 1_700_000_000_000L)
        val decoded = decode(original.encode())

        assertEquals(original.entries, decoded.entries)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun `empty snapshot encodes and decodes`() {
        val snapshot = WebDavSnapshot(entries = emptyMap(), timestamp = 0L)
        val decoded = decode(snapshot.encode())
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `decode rejects invalid base64`() {
        assertFailsWith<Exception> { decode("not-valid-base64!!!") }
    }

    @Test
    fun `encode produces valid base64`() {
        val snapshot = WebDavSnapshot(entries = emptyMap())
        val encoded = snapshot.encode()
        val bytes =
            java.util.Base64
                .getDecoder()
                .decode(encoded)
        assertTrue(bytes.isNotEmpty())
    }
}
