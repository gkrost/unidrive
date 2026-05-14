package org.krost.unidrive.e2e.verify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Serializable
data class ReportEntry(
    val run_id: String,
    val scenario: String,
    val provider: String,
    val os: String,
    val phase: String,
    val status: String,
    val duration_ms: Long,
    val total: Int = 0,
    val pass: Int = 0,
    val fail: Int = 0,
    val skip: Int = 0,
    val failures: List<ReportFailure> = emptyList(),
    val skips: List<ReportSkip> = emptyList(),
)

@Serializable data class ReportFailure(val path: String, val reason: String)
@Serializable data class ReportSkip(val path: String, val reason: String)

private val json = Json { encodeDefaults = true }

object ReportWriter {

    fun append(path: Path, entry: ReportEntry) {
        path.parent?.let { Files.createDirectories(it) }
        val line = json.encodeToString(ReportEntry.serializer(), entry) + "\n"
        Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
