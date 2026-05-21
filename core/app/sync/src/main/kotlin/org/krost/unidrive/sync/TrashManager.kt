package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.io.path.*

class TrashManager(
    private val syncRoot: Path,
) {
    private val trashDir: Path = syncRoot.resolve(".unidrive-trash")

    private companion object {
        val DIR_FORMAT: DateTimeFormatter =
            DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)

        fun encodeTimestamp(instant: Instant): String = DIR_FORMAT.format(instant)

        fun decodeTimestamp(dirName: String): Instant? =
            try {
                DIR_FORMAT.parse(dirName, Instant::from)
            } catch (_: Exception) {
                null
            }
    }

    fun trash(relativePath: String): Path {
        val timestamp = encodeTimestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        val dest = trashDir.resolve(timestamp).resolve(relativePath.removePrefix("/"))
        dest.parent.createDirectories()
        val source = syncRoot.resolve(relativePath.removePrefix("/"))
        if (source.exists()) {
            Files.move(source, dest)
        }
        return dest
    }

    data class TrashedItem(
        val timestamp: Instant,
        val originalPath: String,
        val trashPath: Path,
        val sizeBytes: Long,
    )

    fun list(): List<TrashedItem> {
        if (!trashDir.exists()) return emptyList()
        val items = mutableListOf<TrashedItem>()
        for (timestampDir in trashDir.listDirectoryEntries().sorted()) {
            if (!timestampDir.isDirectory()) continue
            val ts = decodeTimestamp(timestampDir.name) ?: continue
            for (file in timestampDir.toFile().walkTopDown().filter { it.isFile }) {
                val relPath = timestampDir.relativize(file.toPath()).toString()
                items.add(TrashedItem(ts, relPath, file.toPath(), file.length()))
            }
        }
        return items
    }

    fun restore(originalPath: String): Boolean {
        val normalized = originalPath.removePrefix("/")
        val items = list().filter { it.originalPath == normalized }.sortedByDescending { it.timestamp }
        val latest = items.firstOrNull() ?: return false
        val dest = syncRoot.resolve(normalized)
        dest.parent.createDirectories()
        Files.move(latest.trashPath, dest)
        cleanEmptyDirs(latest.trashPath.parent)
        return true
    }

    fun purge(retentionDays: Int) {
        if (!trashDir.exists()) return
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        for (timestampDir in trashDir.listDirectoryEntries()) {
            if (!timestampDir.isDirectory()) continue
            val ts = decodeTimestamp(timestampDir.name) ?: continue
            if (ts.isBefore(cutoff)) {
                timestampDir.toFile().deleteRecursively()
            }
        }
        if (trashDir.exists() && trashDir.listDirectoryEntries().isEmpty()) {
            trashDir.deleteIfExists()
        }
    }

    fun purgeAll() {
        if (trashDir.exists()) {
            trashDir.toFile().deleteRecursively()
        }
    }

    private fun cleanEmptyDirs(dir: Path) {
        var current = dir
        while (current != trashDir && current.exists() && current.listDirectoryEntries().isEmpty()) {
            current.deleteIfExists()
            current = current.parent
        }
    }
}
