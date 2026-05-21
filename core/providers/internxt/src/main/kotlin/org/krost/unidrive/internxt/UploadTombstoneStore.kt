package org.krost.unidrive.internxt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.krost.unidrive.UnidriveJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Compensates for Internxt's missing resumable-upload protocol by recording
 * per-upload progress to a JSON sidecar + a co-located encrypted-ciphertext
 * temp file. A daemon kill between encrypt and finishUpload can resume the
 * exact same `indexBytes` / iv / fileKey / ciphertext / hash on the next
 * `upload()` call for the same local path — no re-encrypt, no orphan shard
 * commits.
 *
 * Storage layout: `${tokenPath}/upload-tombstones/<sha256(localPath)>.json`
 * + `${tokenPath}/upload-tombstones/<sha256(localPath)>.enc`. POSIX 0600
 * where the platform supports it (Internxt creds live next door on the
 * same mode). The directory is created lazily on first write.
 *
 * Atomicity: the JSON sidecar is written to a `.tmp` sibling then renamed
 * with `ATOMIC_MOVE`, so a crash mid-write never leaves a half-parsed
 * tombstone visible. The ciphertext temp file is overwritten in place;
 * if a crash truncates it the resume path detects the size mismatch
 * against `encrypted_size` and rotates the tombstone.
 *
 * Staleness is the caller's call. The store hands back what's on disk and
 * exposes [discard] for the caller to drop a tombstone whose preconditions
 * no longer match. The 7-day / 10-minute thresholds live on
 * [InternxtConfig.RESUME_TTL_MS] / [InternxtConfig.URL_TTL_MS].
 */
internal class UploadTombstoneStore(
    private val tombstoneDir: Path,
) {
    /**
     * Read the tombstone at [pathHash] if present. Returns null when the
     * sidecar is absent, malformed, or its schema discriminator doesn't
     * match the current shape (a future schema bump will route the old
     * shape into discard rather than try to parse it).
     */
    fun read(pathHash: String): UploadTombstone? {
        val jsonPath = tombstoneDir.resolve("$pathHash.json")
        if (!Files.exists(jsonPath)) return null
        val raw =
            try {
                Files.readString(jsonPath)
            } catch (_: Exception) {
                return null
            }
        return try {
            val parsed = json.decodeFromString(UploadTombstone.serializer(), raw)
            if (parsed.schema != UploadTombstone.CURRENT_SCHEMA) null else parsed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Atomic write of [tombstone] to its `.json` sidecar. Caller is
     * responsible for refreshing [UploadTombstone.tombstoneWrittenAtMillis]
     * to `Instant.now().toEpochMilli()` on every transition — the store
     * just persists what's handed in.
     */
    fun write(
        pathHash: String,
        tombstone: UploadTombstone,
    ) {
        ensureDir()
        val finalPath = tombstoneDir.resolve("$pathHash.json")
        val tmpPath = tombstoneDir.resolve("$pathHash.json.tmp")
        val body = json.encodeToString(UploadTombstone.serializer(), tombstone)
        Files.writeString(tmpPath, body)
        try {
            Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Some filesystems (e.g. cross-volume tmpfs on certain CI runners)
            // refuse ATOMIC_MOVE; fall back to a plain replace. The non-atomic
            // window is small and the read path handles a malformed file as
            // "no tombstone", so the worst case is a forced re-encrypt.
            Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
        }
        tryRestrictPermissions(finalPath)
    }

    /**
     * Drop the JSON sidecar AND any co-located ciphertext file for
     * [pathHash]. Idempotent — missing files are ignored. Called both on
     * upload success (cleanup) and on stale-tombstone detection (discard).
     */
    fun discard(pathHash: String) {
        Files.deleteIfExists(tombstoneDir.resolve("$pathHash.json"))
        Files.deleteIfExists(tombstoneDir.resolve("$pathHash.json.tmp"))
        Files.deleteIfExists(ciphertextPath(pathHash))
    }

    /**
     * Deterministic ciphertext temp-file location for [pathHash]. The
     * resume path writes here on stage 3 and the in-flight path reads
     * from here on stages 5-7; success-path cleanup runs through
     * [discard].
     */
    fun ciphertextPath(pathHash: String): Path = tombstoneDir.resolve("$pathHash.enc")

    /**
     * Sweep tombstones whose underlying `local_path` no longer exists OR
     * whose `tombstone_written_at_millis` is older than [maxAgeMs]. Called
     * inline at the top of each `upload()` so an account with a few stale
     * tombstones from old kills doesn't accumulate them indefinitely.
     * Bounded by the directory listing size — typical accounts will see
     * single-digit entries here.
     */
    fun gc(maxAgeMs: Long) {
        if (!Files.isDirectory(tombstoneDir)) return
        val nowMillis = System.currentTimeMillis()
        val entries =
            try {
                Files.list(tombstoneDir).use { stream ->
                    stream.toList()
                }
            } catch (_: Exception) {
                return
            }
        for (path in entries) {
            val name = path.fileName.toString()
            if (!name.endsWith(".json")) continue
            val pathHash = name.removeSuffix(".json")
            val tomb = read(pathHash) ?: run {
                // Unparseable / mismatched schema — kill the pair.
                discard(pathHash)
                continue
            }
            val ageMs = nowMillis - tomb.tombstoneWrittenAtMillis
            val localStillExists =
                try {
                    Files.exists(java.nio.file.Paths.get(tomb.localPath))
                } catch (_: Exception) {
                    false
                }
            if (ageMs > maxAgeMs || !localStillExists) {
                discard(pathHash)
            }
        }
    }

    private fun ensureDir() {
        if (Files.isDirectory(tombstoneDir)) return
        Files.createDirectories(tombstoneDir)
        tryRestrictPermissions(tombstoneDir)
    }

    private fun tryRestrictPermissions(path: Path) {
        // POSIX-only; no-op on Windows where the parent profile dir already
        // carries the appropriate ACLs.
        try {
            val view = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
            if (view != null) {
                val perms =
                    if (Files.isDirectory(path)) {
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                    } else {
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                    }
                view.setPermissions(perms)
            }
        } catch (_: Exception) {
            // Best-effort; the parent profile dir already restricts access.
        }
    }

    companion object {
        private val json: Json = UnidriveJson

        /**
         * `sha256(localPath.absolute().toString())` as lowercase hex.
         * Used as the stable per-upload key so concurrent `upload()` calls
         * for different paths can't collide. Path normalisation is left
         * to the caller — the same path string in always produces the
         * same hash out.
         */
        fun pathHash(localPathString: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(localPathString.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Crash-recoverable record for an in-flight Internxt upload. Persisted as
 * a JSON sidecar by [UploadTombstoneStore]. Every field except
 * [tombstoneWrittenAtMillis] is set once on first write; the
 * write-timestamp refreshes on each [UploadTombstoneStore.write] so the
 * URL-TTL freshness check can fence off a presigned PUT URL handed out
 * more than [InternxtConfig.URL_TTL_MS] ago.
 *
 * Fields are wire-named with snake_case to match the rest of unidrive's
 * sidecar JSON conventions and to keep a future hex-editor / `jq`
 * inspection readable. Secrets policy: stores `index_bytes_hex` (32-byte
 * server-stored salt that derives the IV via the mnemonic but is itself
 * not a key); does NOT store the derived `fileKey`, the mnemonic, or
 * the IV. A leaked tombstone without the mnemonic is not sufficient to
 * decrypt the ciphertext.
 */
@Serializable
internal data class UploadTombstone(
    val schema: Int = CURRENT_SCHEMA,
    val localPath: String,
    val localMtimeMillis: Long,
    val localSize: Long,
    val bucket: String,
    val folderUuid: String,
    val plainName: String,
    val ext: String?,
    val indexBytesHex: String,
    val shardUuid: String? = null,
    val bridgePutUrl: String? = null,
    val encryptedSize: Long? = null,
    val hashHex: String? = null,
    val existingRemoteId: String? = null,
    val stage: Stage,
    val startedAtMillis: Long,
    val tombstoneWrittenAtMillis: Long,
) {
    enum class Stage {
        ENCRYPTING,
        PUT_PENDING,
        PUT_DONE,
        FINISH_DONE,
    }

    companion object {
        const val CURRENT_SCHEMA: Int = 1
    }
}
