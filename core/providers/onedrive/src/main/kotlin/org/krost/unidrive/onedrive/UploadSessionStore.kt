package org.krost.unidrive.onedrive

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
 * and pruned from the file.  A missing or corrupt file is treated as an empty store.
 */
class UploadSessionStore(
    private val storeDir: Path,
) {
    @Serializable
    private data class StoredSession(
        val uploadUrl: String,
        val expiresAt: String, // ISO-8601 Instant
    )

    private val storeFile: Path get() = storeDir.resolve("upload_sessions.json")

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns a valid (non-expired) upload URL for [remotePath], or null if none. */
    @Synchronized
    fun get(remotePath: String): String? {
        val map = readMap()
        val session = map[remotePath] ?: return null
        if (Instant.parse(session.expiresAt).isBefore(Instant.now())) {
            delete(remotePath)
            return null
        }
        return session.uploadUrl
    }

    /** Persist [uploadUrl] for [remotePath] with [expiresAt] as the expiry instant. */
    @Synchronized
    fun put(
        remotePath: String,
        uploadUrl: String,
        expiresAt: Instant,
    ) {
        val map = readMap().toMutableMap()
        map[remotePath] = StoredSession(uploadUrl, expiresAt.toString())
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
        return try {
            json.decodeFromString<Map<String, StoredSession>>(Files.readString(storeFile))
        } catch (_: Exception) {
            emptyMap()
        }
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
