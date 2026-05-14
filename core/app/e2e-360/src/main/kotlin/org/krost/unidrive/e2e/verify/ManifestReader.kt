package org.krost.unidrive.e2e.verify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ManifestEntry(
    val path: String,
    val size: Long,
    val hash: String,
    val verify_mode: String = "full_hash",
    val encoding: String? = null,
) {
    val verifyMode get() = verify_mode
}

private val json = Json { ignoreUnknownKeys = true }

object ManifestReader {

    fun read(path: Path): List<ManifestEntry> =
        Files.readAllLines(path)
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(ManifestEntry.serializer(), it) }
}
