package org.krost.unidrive.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Append-only JSONL conflict log with backup storage for loser files.
 *
 * Log file: `conflicts.jsonl` — one JSON object per line.
 * Backup dir: `conflict-backups/` — timestamped copies of overwritten files.
 */
class ConflictLog(
    private val logFile: Path,
    private val backupDir: Path,
) {
    @Serializable
    data class Entry(
        val timestamp: String,
        val path: String,
        val localState: String,
        val remoteState: String,
        val policy: String,
        val backupFile: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val timestampFormat =
        DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneId.of("UTC"))

    /**
     * Record a conflict resolution.
     * If [loserFile] is non-null, copies it to [backupDir] with a timestamped name.
     */
    fun record(
        path: String,
        localState: String,
        remoteState: String,
        policy: String,
        loserFile: Path?,
    ): Entry {
        val now = Instant.now()
        val ts = timestampFormat.format(now)
        val originalFilename = path.substringAfterLast("/")

        val backupName =
            if (loserFile != null && Files.exists(loserFile)) {
                Files.createDirectories(backupDir)
                var name = "$ts-$originalFilename"
                var seq = 1
                while (Files.exists(backupDir.resolve(name))) {
                    name = "$ts-${seq++}-$originalFilename"
                }
                Files.copy(loserFile, backupDir.resolve(name))
                name
            } else {
                null
            }

        val entry =
            Entry(
                timestamp = now.toString(),
                path = path,
                localState = localState,
                remoteState = remoteState,
                policy = policy,
                backupFile = backupName,
            )

        Files.createDirectories(logFile.parent)
        val line = json.encodeToString(Entry.serializer(), entry) + "\n"
        Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        return entry
    }

    /**
     * Return the most recent [limit] entries in reverse-chronological order.
     */
    fun recent(limit: Int = 20): List<Entry> {
        if (!Files.exists(logFile)) return emptyList()
        return Files
            .readAllLines(logFile)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    json.decodeFromString(Entry.serializer(), line)
                } catch (_: Exception) {
                    null
                }
            }.reversed()
            .take(limit)
    }

    /**
     * Find all backup entries matching a given [path].
     */
    fun findBackups(path: String): List<Entry> {
        if (!Files.exists(logFile)) return emptyList()
        return Files
            .readAllLines(logFile)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    json.decodeFromString(Entry.serializer(), line)
                } catch (_: Exception) {
                    null
                }
            }.filter { it.path == path && it.backupFile != null }
            .reversed()
    }

    /**
     * Restore a backup to the original path under [syncRoot].
     * Creates parent directories if needed.
     */
    fun restore(
        entry: Entry,
        syncRoot: Path,
    ): Path {
        val backupName =
            entry.backupFile
                ?: throw IllegalArgumentException("Entry has no backup file.")
        val backupPath = backupDir.resolve(backupName)
        if (!Files.exists(backupPath)) {
            throw IllegalStateException("Backup file not found: $backupPath")
        }
        val target = syncRoot.resolve(entry.path)
        Files.createDirectories(target.parent)
        Files.copy(backupPath, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /**
     * Delete all backups and truncate the log.
     */
    fun clear() {
        if (Files.isDirectory(backupDir)) {
            Files
                .walk(backupDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
        Files.deleteIfExists(logFile)
    }
}
