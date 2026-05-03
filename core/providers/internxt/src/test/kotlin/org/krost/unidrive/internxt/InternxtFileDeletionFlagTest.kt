package org.krost.unidrive.internxt

import kotlinx.serialization.json.Json
import org.krost.unidrive.internxt.model.InternxtFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-359: `InternxtFile` must surface the server-provided `removed` and
 * `deleted` boolean flags, not just the `status` enum. The Internxt Drive
 * `FileDto` exposes both; until 2026-05-03 the provider silently dropped
 * them and the deletion-detection logic only fired on
 * `status="TRASHED"|"DELETED"`. Server schema drift to `removed=true` /
 * `deleted=true` while leaving `status="EXISTS"` would have masked
 * deletions entirely.
 */
class InternxtFileDeletionFlagTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialise removed flag from API payload`() {
        val payload = """{"uuid":"u1","status":"EXISTS","removed":true}"""
        val file = json.decodeFromString<InternxtFile>(payload)
        assertTrue(file.removed)
        assertFalse(file.deleted)
        assertEquals("EXISTS", file.status)
    }

    @Test
    fun `deserialise deleted flag from API payload`() {
        val payload = """{"uuid":"u1","status":"EXISTS","deleted":true}"""
        val file = json.decodeFromString<InternxtFile>(payload)
        assertTrue(file.deleted)
        assertFalse(file.removed)
    }

    @Test
    fun `default values when fields absent`() {
        // Pin backward compatibility with payloads that don't yet send the flags.
        val payload = """{"uuid":"u1","status":"EXISTS"}"""
        val file = json.decodeFromString<InternxtFile>(payload)
        assertFalse(file.removed)
        assertFalse(file.deleted)
    }

    @Test
    fun `fileToDeltaCloudItem flags removed=true as deleted`() {
        // The exact regression: server sends EXISTS+removed=true (e.g. soft-delete
        // intermediate state); the engine MUST see the CloudItem as deleted, not
        // as a normal file that just disappeared from the delta.
        val file = InternxtFile(uuid = "u1", plainName = "secret.txt", status = "EXISTS", removed = true)
        val item = InternxtProvider.fileToDeltaCloudItem(file, "/some/folder")
        assertTrue(item.deleted, "removed=true must propagate to CloudItem.deleted")
    }

    @Test
    fun `fileToDeltaCloudItem flags deleted=true as deleted`() {
        val file = InternxtFile(uuid = "u1", plainName = "secret.txt", status = "EXISTS", deleted = true)
        val item = InternxtProvider.fileToDeltaCloudItem(file, "/some/folder")
        assertTrue(item.deleted)
    }

    @Test
    fun `fileToDeltaCloudItem leaves alive files unflagged`() {
        val file = InternxtFile(uuid = "u1", plainName = "secret.txt", status = "EXISTS")
        val item = InternxtProvider.fileToDeltaCloudItem(file, "/some/folder")
        assertFalse(item.deleted)
    }

    @Test
    fun `fileToCloudItem (listChildren path) also honours removed and deleted`() {
        // Same logic must apply on the listChildren / getMetadata path so a
        // tombstoned file that comes back from /folders/content/{uuid} doesn't
        // appear alive.
        val viaRemoved = InternxtFile(uuid = "u1", plainName = "x.txt", status = "EXISTS", removed = true)
        val viaDeleted = InternxtFile(uuid = "u2", plainName = "y.txt", status = "EXISTS", deleted = true)
        assertTrue(InternxtProvider.fileToCloudItem(viaRemoved, "/").deleted)
        assertTrue(InternxtProvider.fileToCloudItem(viaDeleted, "/").deleted)
    }

    @Test
    fun `status TRASHED still flags deletion regardless of removed and deleted`() {
        // Pin the existing status-based path — UD-359 adds new signals, must
        // not regress the old ones.
        val file = InternxtFile(uuid = "u1", plainName = "x.txt", status = "TRASHED")
        assertTrue(InternxtProvider.fileToDeltaCloudItem(file, "/").deleted)
    }
}
