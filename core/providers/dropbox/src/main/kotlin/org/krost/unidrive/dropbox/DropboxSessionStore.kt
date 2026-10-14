package org.krost.unidrive.dropbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The Dropbox upload sessions in flight, keyed by remote path (#867): a session id and the offset the
 * next `append_v2` must carry, bound to the local file's identity (size and mtime — a changed file
 * invalidates the session) and its creation instant. Sessions live [SESSION_TTL] (Dropbox keeps a
 * session for 7 days) and older ones are dropped at construction, so an upload that was interrupted
 * for good starts over instead of resuming into a session the server no longer has.
 *
 * The store is a JSON file in the profile folder, which the daemon tightens to owner-only at start
 * (StoragePermissions); it holds a session id and an offset — no token, no path outside the account.
 * The shape follows OneDrive's UploadSessionStore.
 */
class DropboxSessionStore(
    storeDir: Path,
) {
    @Serializable
    data class StoredSession(
        val sessionId: String,
        val committedOffset: Long,
        val localSize: Long,
        val localMtimeMillis: Long,
        val createdAt: String, // ISO-8601 instant
    )

    private val storeDir: Path = storeDir
    private val storeFile: Path get() = storeDir.resolve("upload_sessions.json")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        runCatching { pruneExpired() }
    }

    /**
     * The stored session for [remotePath], or null: none, older than [SESSION_TTL], or the local file
     * changed (size or mtime) since the session was recorded.
     */
    @Synchronized
    fun get(
        remotePath: String,
        localSize: Long,
        localMtimeMillis: Long,
    ): StoredSession? {
        val map = readMap()
        val session = map[remotePath] ?: return null
        if (Instant.parse(session.createdAt).plus(SESSION_TTL).isBefore(Instant.now()) ||
            session.localSize != localSize ||
            session.localMtimeMillis != localMtimeMillis
        ) {
            delete(remotePath)
            return null
        }
        return session
    }

    @Synchronized
    fun put(
        remotePath: String,
        sessionId: String,
        committedOffset: Long,
        localSize: Long,
        localMtimeMillis: Long,
    ) {
        val map = readMap().toMutableMap()
        map[remotePath] =
            StoredSession(sessionId, committedOffset, localSize, localMtimeMillis, Instant.now().toString())
        writeMap(map)
    }

    /** Remove the stored session for [remotePath] (call when the upload finished or failed for good). */
    @Synchronized
    fun delete(remotePath: String) {
        val map = readMap().toMutableMap()
        if (map.remove(remotePath) != null) writeMap(map)
    }

    /** Remove every session older than [SESSION_TTL]; the server has dropped them too. */
    @Synchronized
    fun pruneExpired() {
        val cutoff = Instant.now().minus(SESSION_TTL)
        val map = readMap().filter { (_, s) -> Instant.parse(s.createdAt).isAfter(cutoff) }
        writeMap(map)
    }

    private fun readMap(): Map<String, StoredSession> {
        if (!Files.exists(storeFile)) return emptyMap()
        val root =
            runCatching { json.parseToJsonElement(Files.readString(storeFile)).jsonObject }
                .getOrNull() ?: return emptyMap()
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

    companion object {
        /** Dropbox keeps an upload session for 7 days; an older one answers incorrect_offset for ever. */
        val SESSION_TTL: java.time.Duration = java.time.Duration.ofDays(7)
    }
}
