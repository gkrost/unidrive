package org.krost.unidrive.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip + byte-identity tests for the shared `Snapshot<E>` wrapper.
 *
 * Each `legacyXxxSnapshot` data class below mirrors the field set of one of
 * the six pre-UD-345 per-provider snapshot classes verbatim (same order,
 * same names, same nullability). The tests then encode the legacy shape
 * via the legacy code-path (free-form `Json.encodeToString` of a data class)
 * and decode it via the shared `Snapshot<E>.decode(...)` — and vice versa
 * — to prove on-disk-cursor compatibility.
 */
class DeltaSnapshotTest {
    // ── Round-trip ───────────────────────────────────────────────────────────

    @Serializable
    private data class FixtureEntry(
        val size: Long,
        val tag: String?,
        val isFolder: Boolean,
    )

    @Test
    fun `encode then decode round-trips entries and timestamp`() {
        val original =
            Snapshot(
                entries =
                    mapOf(
                        "/a.txt" to FixtureEntry(100, "etag-a", false),
                        "/b" to FixtureEntry(0, null, true),
                    ),
                timestamp = 1700000000000L,
            )
        val cursor = original.encode(FixtureEntry.serializer())
        val decoded = Snapshot.decode(cursor, FixtureEntry.serializer())
        assertEquals(original.entries, decoded.entries)
        assertEquals(original.timestamp, decoded.timestamp)
    }

    @Test
    fun `empty snapshot round-trips`() {
        val original = Snapshot<FixtureEntry>(entries = emptyMap(), timestamp = 0L)
        val decoded = Snapshot.decode(original.encode(FixtureEntry.serializer()), FixtureEntry.serializer())
        assertTrue(decoded.entries.isEmpty())
        assertEquals(0L, decoded.timestamp)
    }

    // ── Byte-identity gate (UD-345 cursor-format compatibility) ─────────────
    //
    // For each of the six per-provider entry shapes, we:
    //   1. construct a "legacy" snapshot (the pre-UD-345 data-class shape)
    //   2. encode it the legacy way (`Json.encodeToString(legacy)` + Base64)
    //   3. decode the resulting cursor with the new `Snapshot<E>.decode(...)`
    //   4. assert the entries Map and timestamp match
    //   5. re-encode with the new wrapper and assert the cursor string is
    //      byte-for-byte identical to the legacy cursor
    //
    // If any provider's legacy cursor fails this gate, the lift is
    // not on-disk-compatible.

    private inline fun <reified L : Any, reified E : Any> assertCursorByteIdentity(
        legacy: L,
        entries: Map<String, E>,
        timestamp: Long,
        elementSerializer: kotlinx.serialization.KSerializer<E>,
    ) {
        val legacyJson = Json.encodeToString(legacy)
        val legacyCursor = Base64.getEncoder().encodeToString(legacyJson.toByteArray())

        val decoded = Snapshot.decode(legacyCursor, elementSerializer)
        assertEquals(entries, decoded.entries, "entries map decode mismatch")
        assertEquals(timestamp, decoded.timestamp, "timestamp decode mismatch")

        val newCursor = Snapshot(entries, timestamp).encode(elementSerializer)
        assertEquals(legacyCursor, newCursor, "cursor byte-identity mismatch")
    }

    // -- HiDrive ---------------------------------------------------------------

    @Serializable
    private data class LegacyHiDriveEntry(
        val id: String,
        val chash: String?,
        val size: Long,
        val mtime: Long?,
        val isFolder: Boolean,
    )

    @Serializable
    private data class LegacyHiDriveSnapshot(
        val entries: Map<String, LegacyHiDriveEntry>,
        val timestamp: Long,
    )

    @Test
    fun `byte identity HiDrive cursor`() {
        val entries =
            mapOf(
                "/Documents/file.txt" to LegacyHiDriveEntry("id1", "hash1", 1024, 1700000000, false),
                "/Photos" to LegacyHiDriveEntry("id2", null, 0, 1700000001, true),
            )
        val timestamp = 1700000000000L
        assertCursorByteIdentity(
            legacy = LegacyHiDriveSnapshot(entries, timestamp),
            entries = entries,
            timestamp = timestamp,
            elementSerializer = LegacyHiDriveEntry.serializer(),
        )
    }

    // -- S3 --------------------------------------------------------------------

    @Serializable
    private data class LegacyS3Entry(
        val etag: String?,
        val size: Long,
        val lastModified: String?,
        val isFolder: Boolean,
    )

    @Serializable
    private data class LegacyS3Snapshot(
        val entries: Map<String, LegacyS3Entry>,
        val timestamp: Long,
    )

    @Test
    fun `byte identity S3 cursor`() {
        val entries =
            mapOf(
                "/key.bin" to LegacyS3Entry("\"abc123\"", 4096, "2026-04-30T12:00:00Z", false),
                "/folder/" to LegacyS3Entry(null, 0, null, true),
            )
        val timestamp = 1700000001000L
        assertCursorByteIdentity(
            legacy = LegacyS3Snapshot(entries, timestamp),
            entries = entries,
            timestamp = timestamp,
            elementSerializer = LegacyS3Entry.serializer(),
        )
    }

    // -- SFTP ------------------------------------------------------------------

    @Serializable
    private data class LegacySftpEntry(
        val size: Long,
        val mtimeSeconds: Long,
        val isFolder: Boolean,
    )

    @Serializable
    private data class LegacySftpSnapshot(
        val entries: Map<String, LegacySftpEntry>,
        val timestamp: Long,
    )

    @Test
    fun `byte identity SFTP cursor`() {
        val entries =
            mapOf(
                "/home/user/file.dat" to LegacySftpEntry(2048, 1700000000L, false),
                "/home/user/dir" to LegacySftpEntry(0, 1700000005L, true),
            )
        val timestamp = 1700000002000L
        assertCursorByteIdentity(
            legacy = LegacySftpSnapshot(entries, timestamp),
            entries = entries,
            timestamp = timestamp,
            elementSerializer = LegacySftpEntry.serializer(),
        )
    }

    // -- WebDAV ----------------------------------------------------------------

    @Serializable
    private data class LegacyWebDavEntry(
        val size: Long,
        val lastModified: String?,
        val etag: String?,
        val isFolder: Boolean,
    )

    @Serializable
    private data class LegacyWebDavSnapshot(
        val entries: Map<String, LegacyWebDavEntry>,
        val timestamp: Long,
    )

    @Test
    fun `byte identity WebDAV cursor`() {
        val entries =
            mapOf(
                "/dav/file.xml" to LegacyWebDavEntry(512, "Tue, 30 Apr 2026 12:00:00 GMT", "\"etag-x\"", false),
                "/dav/dir/" to LegacyWebDavEntry(0, null, null, true),
            )
        val timestamp = 1700000003000L
        assertCursorByteIdentity(
            legacy = LegacyWebDavSnapshot(entries, timestamp),
            entries = entries,
            timestamp = timestamp,
            elementSerializer = LegacyWebDavEntry.serializer(),
        )
    }

    // -- Rclone ----------------------------------------------------------------

    @Serializable
    private data class LegacyRcloneEntry(
        val size: Long,
        val modTime: String,
        val isFolder: Boolean,
        val hash: String? = null,
    )

    @Serializable
    private data class LegacyRcloneSnapshot(
        val entries: Map<String, LegacyRcloneEntry>,
        val timestamp: Long,
    )

    @Test
    fun `byte identity Rclone cursor`() {
        val entries =
            mapOf(
                "/file.txt" to LegacyRcloneEntry(100, "2026-04-30T12:00:00Z", false, "deadbeef"),
                "/dir" to LegacyRcloneEntry(0, "2026-04-30T11:00:00Z", true, null),
            )
        val timestamp = 1700000004000L
        assertCursorByteIdentity(
            legacy = LegacyRcloneSnapshot(entries, timestamp),
            entries = entries,
            timestamp = timestamp,
            elementSerializer = LegacyRcloneEntry.serializer(),
        )
    }

    // -- LocalFs ---------------------------------------------------------------

    @Serializable
    private data class LegacyLocalFsEntry(
        val size: Long,
        val mtimeMillis: Long,
        val isFolder: Boolean,
    )

    @Serializable
    private data class LegacyLocalFsSnapshot(
        val entries: Map<String, LegacyLocalFsEntry>,
        val timestamp: Long,
    )

    @Test
    fun `byte identity LocalFs cursor`() {
        val entries =
            mapOf(
                "rel/path.txt" to LegacyLocalFsEntry(64, 1700000000000L, false),
                "rel/dir" to LegacyLocalFsEntry(0, 1700000005000L, true),
            )
        val timestamp = 1700000005000L
        assertCursorByteIdentity(
            legacy = LegacyLocalFsSnapshot(entries, timestamp),
            entries = entries,
            timestamp = timestamp,
            elementSerializer = LegacyLocalFsEntry.serializer(),
        )
    }
}
