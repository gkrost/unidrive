package org.krost.unidrive.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Generic snapshot wrapper used by every snapshot-based delta provider
 * (HiDrive, S3, SFTP, WebDAV, Rclone, LocalFs).
 *
 * Storage shape: Base64(JSON(`{"entries":{...},"timestamp":N}`)).
 *
 * The element type `E` is provider-specific (the per-provider
 * `XxxSnapshotEntry` data classes). Callers pass the element's
 * `KSerializer` to [encode] and [decode] so the same wrapper works
 * across providers without each one shipping its own copy.
 *
 * On-disk-compatible with the six pre-UD-345 per-provider implementations:
 * the field order (`entries` then `timestamp`) and JSON shape match
 * byte-for-byte — existing `state.db` cursors continue to round-trip.
 */
@Serializable
class Snapshot<E>(
    val entries: Map<String, E>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun encode(elementSerializer: KSerializer<E>): String {
        val json = Json.encodeToString(serializer(elementSerializer), this)
        return Base64.getEncoder().encodeToString(json.toByteArray())
    }

    companion object {
        fun <E> decode(
            cursor: String,
            elementSerializer: KSerializer<E>,
        ): Snapshot<E> {
            val bytes = Base64.getDecoder().decode(cursor)
            return Json.decodeFromString(serializer(elementSerializer), String(bytes))
        }
    }
}
