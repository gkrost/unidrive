package org.krost.unidrive.e2e.verify

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val json = Json { encodeDefaults = true }

object ManifestWriter {

    fun write(path: Path, entries: List<ManifestEntry>) {
        path.parent?.let { Files.createDirectories(it) }
        val lines = entries.joinToString("\n") { json.encodeToString(ManifestEntry.serializer(), it) }
        Files.writeString(path, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
}
