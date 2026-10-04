package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.io.path.*

class VersionManager(
    private val syncRoot: Path,
) {
    private val versionsDir: Path = syncRoot.resolve(".unidrive-versions")

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

    data class VersionedItem(
        val timestamp: Instant,
        val originalPath: String,
        val versionPath: Path,
        val sizeBytes: Long,
    )

    fun snapshot(relativePath: String): Path? {
        val normalized = relativePath.replace('\\', '/').removePrefix("/")
        val source = syncRoot.resolve(normalized)
        if (!source.exists() || !source.isRegularFile()) return null
        val timestamp = encodeTimestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        val dest = versionsDir.resolve(normalized).resolve(timestamp)
        dest.parent.createDirectories()
        Files.copy(source, dest)
        return dest
    }

    fun listVersions(relativePath: String): List<VersionedItem> {
        val normalized = relativePath.replace('\\', '/').removePrefix("/")
        val fileVersionsDir = versionsDir.resolve(normalized)
        if (!fileVersionsDir.exists() || !fileVersionsDir.isDirectory()) return emptyList()
        val items = mutableListOf<VersionedItem>()
        for (entry in fileVersionsDir.listDirectoryEntries()) {
            if (!entry.isRegularFile()) continue
            val ts = decodeTimestamp(entry.name) ?: continue
            items.add(VersionedItem(ts, normalized, entry, Files.size(entry)))
        }
        return items.sortedByDescending { it.timestamp }
    }

    fun listAll(): List<VersionedItem> {
        if (!versionsDir.exists()) return emptyList()
        val items = mutableListOf<VersionedItem>()
        for (file in versionsDir.toFile().walkTopDown().filter { it.isFile }) {
            val filePath = file.toPath()
            val ts = decodeTimestamp(filePath.name) ?: continue
            // The key is the cloud-style path the version was snapshotted under (the
            // write side resolves forward slashes on every OS), so normalize the
            // walk's separators back: on Windows the raw relativize form made --list
            // print "a\b.txt" and --restore "a/b.txt" never match it.
            val relPath = versionsDir.relativize(filePath.parent).toString().replace('\\', '/')
            items.add(VersionedItem(ts, relPath, filePath, file.length()))
        }
        return items.sortedByDescending { it.timestamp }
    }

    fun restore(
        relativePath: String,
        timestamp: Instant,
    ): Boolean {
        val normalized = relativePath.replace('\\', '/').removePrefix("/")
        val tsDir = encodeTimestamp(timestamp.truncatedTo(ChronoUnit.SECONDS))
        val versionFile = versionsDir.resolve(normalized).resolve(tsDir)
        if (!versionFile.exists()) return false
        val dest = syncRoot.resolve(normalized)
        dest.parent.createDirectories()
        Files.copy(versionFile, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    fun pruneByCount(
        relativePath: String,
        maxVersions: Int,
    ) {
        val versions = listVersions(relativePath)
        if (versions.size <= maxVersions) return
        for (item in versions.drop(maxVersions)) {
            item.versionPath.deleteIfExists()
        }
        cleanEmptyDirs(versionsDir.resolve(relativePath.replace('\\', '/').removePrefix("/")))
    }

    fun pruneByAge(retentionDays: Int) {
        if (!versionsDir.exists()) return
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        for (file in versionsDir.toFile().walkTopDown().filter { it.isFile }) {
            val ts = decodeTimestamp(file.name) ?: continue
            if (ts.isBefore(cutoff)) {
                file.delete()
            }
        }
        cleanEmptyDirsRecursive(versionsDir)
    }

    /** How many version files [pruneByAge] would remove for [retentionDays], without removing them. */
    fun expiredCount(retentionDays: Int): Int {
        if (!versionsDir.exists()) return 0
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        return versionsDir.toFile().walkTopDown().filter { it.isFile }.count { decodeTimestamp(it.name)?.isBefore(cutoff) == true }
    }

    fun pruneAll() {
        if (versionsDir.exists()) {
            versionsDir.toFile().deleteRecursively()
        }
    }

    private fun cleanEmptyDirs(dir: Path) {
        var current = dir
        while (current != versionsDir && current.exists() && current.isDirectory() && current.listDirectoryEntries().isEmpty()) {
            current.deleteIfExists()
            current = current.parent
        }
    }

    private fun cleanEmptyDirsRecursive(dir: Path) {
        if (!dir.exists() || !dir.isDirectory()) return
        for (child in dir.listDirectoryEntries()) {
            if (child.isDirectory()) cleanEmptyDirsRecursive(child)
        }
        if (dir != versionsDir && dir.listDirectoryEntries().isEmpty()) {
            dir.deleteIfExists()
        }
        if (dir == versionsDir && dir.listDirectoryEntries().isEmpty()) {
            dir.deleteIfExists()
        }
    }
}
