package org.krost.unidrive.onedrive

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Persists OneDrive upload session URLs to disk so that large-file uploads can
 * be resumed across process restarts.
 *
 * File: [storeDir]/upload_sessions.json
 * Format: JSON object mapping remote path → [StoredSession].
 *
 * Expired entries (past [StoredSession.expiresAt]) are silently discarded on read
 * and pruned from the file.  A missing or corrupt file is treated as an empty store,
 * and any individual entry that fails to decode (e.g. written by an older format that
 * lacked the local-file identity fields) is treated as absent rather than crashing.
 */
class UploadSessionStore(
    private val storeDir: Path,
) {
    @Serializable
    data class StoredSession(
        val uploadUrl: String,
        val expiresAt: String, // ISO-8601 Instant
        val localSize: Long,
        val localMtimeMillis: Long,
    )

    private val storeFile: Path get() = storeDir.resolve("upload_sessions.json")

    private val json = Json { ignoreUnknownKeys = true }

    init {
        // GC sessions whose local hint has lapsed before the store gets used.
        // get() self-prunes only the queried path; this catches strays whose
        // local file disappeared between daemon runs and would otherwise sit
        // in the JSON forever.
        runCatching { pruneExpired() }
    }

    /** Returns a valid (non-expired) upload URL for [remotePath], or null if none. */
    @Synchronized
    fun get(remotePath: String): String? = getSession(remotePath)?.uploadUrl

    /** Returns the full valid (non-expired) session for [remotePath], or null if none. */
    @Synchronized
    fun getSession(remotePath: String): StoredSession? {
        val map = readMap()
        val session = map[remotePath] ?: return null
        if (Instant.parse(session.expiresAt).isBefore(Instant.now())) {
            delete(remotePath)
            return null
        }
        return session
    }

    /**
     * Persist [uploadUrl] for [remotePath] with [expiresAt] as the expiry instant,
     * bound to the local file's identity ([localSize] bytes, [localMtimeMillis] epoch millis).
     */
    @Synchronized
    fun put(
        remotePath: String,
        uploadUrl: String,
        expiresAt: Instant,
        localSize: Long,
        localMtimeMillis: Long,
    ) {
        val map = readMap().toMutableMap()
        map[remotePath] = StoredSession(uploadUrl, expiresAt.toString(), localSize, localMtimeMillis)
        writeMap(map)
    }

    /** Remove the stored session for [remotePath] (call on success or permanent failure). */
    @Synchronized
    fun delete(remotePath: String) {
        val map = readMap().toMutableMap()
        if (map.remove(remotePath) != null) writeMap(map)
    }

    /** Remove all sessions whose expiry is in the past. */
    @Synchronized
    fun pruneExpired() {
        val now = Instant.now()
        val map = readMap().filter { (_, s) -> Instant.parse(s.expiresAt).isAfter(now) }
        writeMap(map)
    }

    private fun readMap(): Map<String, StoredSession> {
        if (!Files.exists(storeFile)) return emptyMap()
        val root =
            runCatching { json.parseToJsonElement(Files.readString(storeFile)).jsonObject }
                .getOrNull() ?: return emptyMap()
        // Decode entry-by-entry so a single legacy/corrupt entry (e.g. missing the
        // local-file identity fields) is dropped, not allowed to nuke the whole store.
        return root.mapNotNull { (path, element) ->
            runCatching { json.decodeFromJsonElement(StoredSession.serializer(), element) }
                .getOrNull()
                ?.let { path to it }
        }.toMap()
    }

    private fun writeMap(map: Map<String, StoredSession>) {
        Files.createDirectories(storeDir)
        Files.writeString(
            storeFile,
            json.encodeToString(map),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
