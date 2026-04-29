package org.krost.unidrive.rclone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
data class RcloneEntry(
    @SerialName("Path") val path: String,
    @SerialName("Name") val name: String,
    @SerialName("Size") val size: Long,
    @SerialName("MimeType") val mimeType: String? = null,
    @SerialName("ModTime") val modTime: String,
    @SerialName("IsDir") val isDir: Boolean,
    @SerialName("Hashes") val hashes: Map<String, String>? = null,
    @SerialName("ID") val id: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

private val AUTH_ERROR_PATTERNS =
    listOf(
        "couldn't find remote",
        "no such host",
        "access denied",
        "unauthorized",
        "invalid_grant",
        "failed to create file system",
    )

class RcloneCliService(
    private val config: RcloneConfig,
) {
    private val log = LoggerFactory.getLogger(RcloneCliService::class.java)

    // ── Public API ──────────────────────────────────────────────────────────

    suspend fun verifyRemote() {
        execute(15.seconds, "lsjson", remotePath(""), "--max-depth", "0")
    }

    suspend fun list(path: String): List<RcloneEntry> {
        val output = execute(2.minutes, "lsjson", remotePath(path), "--hash")
        // lsjson returns only filenames; prepend the parent path for full relative paths
        val parent = sanitizePath(path)
        return parseEntries(output).map { entry ->
            val fullPath = if (parent.isEmpty()) entry.path else "$parent/${entry.path}"
            entry.copy(path = fullPath)
        }
    }

    suspend fun listAllRecursive(): List<RcloneEntry> =
        try {
            val output = execute(10.minutes, "lsjson", remotePath(""), "--hash", "--recursive")
            parseEntries(output)
        } catch (e: RcloneException) {
            // Exit code 3 = directory not found — treat as empty (mirrors native SFTP behavior)
            if (e.exitCode == 3) emptyList() else throw e
        }

    suspend fun stat(path: String): RcloneEntry {
        val output = execute(30.seconds, "lsjson", "--stat", remotePath(path), "--hash")
        // --stat returns a single JSON object (not an array), but some rclone versions
        // may wrap it in an array. Handle both by trying object first, then array.
        val entry =
            try {
                json.decodeFromString<RcloneEntry>(output)
            } catch (_: Exception) {
                val entries = json.decodeFromString<List<RcloneEntry>>(output)
                entries.firstOrNull() ?: throw RcloneException("Path not found: $path")
            }
        // --stat returns only the filename as Path; replace with the requested operation path
        val sanitized = sanitizePath(path)
        return entry.copy(path = sanitized)
    }

    suspend fun copyTo(
        remotePath: String,
        destination: Path,
    ): Long {
        // --ignore-times forces download even when local file has matching size (e.g. sparse placeholders)
        execute(10.minutes, "copyto", this.remotePath(remotePath), destination.toString(), "--no-traverse", "--ignore-times")
        return java.nio.file.Files
            .size(destination)
    }

    suspend fun upload(
        localPath: Path,
        remotePath: String,
    ): CloudItem {
        execute(10.minutes, "copyto", localPath.toString(), this.remotePath(remotePath), "--no-traverse")
        return stat(remotePath).let { toCloudItem(it, config.path) }
    }

    suspend fun deleteFile(path: String) {
        execute(2.minutes, "deletefile", remotePath(path))
    }

    suspend fun mkdir(path: String): CloudItem {
        execute(30.seconds, "mkdir", remotePath(path))
        // Query the remote for the actual state rather than constructing from input,
        // in case the backend normalizes the path differently.
        return try {
            stat(path).let { toCloudItem(it, config.path) }
        } catch (_: Exception) {
            // Some backends (e.g., S3) don't support stat on directories.
            // Fall back to constructing from input.
            val sanitized = sanitizePath(path)
            val virtualPath = "/$sanitized"
            CloudItem(
                id = virtualPath,
                name = sanitized.substringAfterLast("/"),
                path = virtualPath,
                size = 0,
                isFolder = true,
                modified = Instant.now(),
                created = null,
                hash = null,
                mimeType = null,
            )
        }
    }

    suspend fun moveTo(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        execute(2.minutes, "moveto", remotePath(fromPath), remotePath(toPath))
        return stat(toPath).let { toCloudItem(it, config.path) }
    }

    suspend fun about(): Map<String, Long> {
        val output = execute(30.seconds, "about", "--json", remotePath(""))
        return json.decodeFromString(output)
    }

    // ── Path handling ───────────────────────────────────────────────────────

    fun remotePath(operationPath: String): String {
        val base = sanitizePath(config.path)
        val op = sanitizePath(operationPath)
        // Preserve leading "/" for absolute remote paths (e.g. SFTP /tmp/...)
        val prefix = if (config.path.startsWith("/") && base.isNotEmpty()) "/" else ""
        val combined =
            when {
                base.isEmpty() && op.isEmpty() -> ""
                base.isEmpty() -> op
                op.isEmpty() -> "$prefix$base"
                else -> "$prefix$base/$op"
            }
        return "${config.remote}$combined"
    }

    // ── Process execution ───────────────────────────────────────────────────

    private suspend fun execute(
        timeout: Duration = 2.minutes,
        vararg args: String,
    ): String {
        val cmd =
            buildList {
                add(config.rcloneBinary)
                if (config.rcloneConfigPath != null) {
                    add("--config")
                    add(config.rcloneConfigPath)
                }
                addAll(args)
            }
        log.debug("Executing: {}", cmd.joinToString(" "))
        return withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start()
            try {
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exited = process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    throw RcloneException("rclone timed out after $timeout", exitCode = -1)
                }
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    val stderrLower = stderr.lowercase()
                    if (AUTH_ERROR_PATTERNS.any { it in stderrLower }) {
                        throw AuthenticationException("rclone auth failed: $stderr")
                    }
                    throw RcloneException("rclone failed (exit $exitCode): $stderr", exitCode = exitCode)
                }
                stdout
            } catch (e: kotlinx.coroutines.CancellationException) {
                process.destroyForcibly()
                throw e
            }
        }
    }

    companion object {
        fun parseEntries(jsonStr: String): List<RcloneEntry> {
            if (jsonStr.isBlank()) return emptyList()
            return json.decodeFromString<List<RcloneEntry>>(jsonStr)
        }

        fun toCloudItem(
            entry: RcloneEntry,
            @Suppress("UNUSED_PARAMETER") basePath: String,
        ): CloudItem {
            // rclone lsjson returns paths relative to the queried root — virtual paths are always /<entry.path>
            val virtualPath = "/${entry.path}"
            return CloudItem(
                id = virtualPath,
                name = entry.name,
                path = virtualPath,
                size = entry.size,
                isFolder = entry.isDir,
                modified = parseModTime(entry.modTime),
                created = null,
                hash = entry.hashes?.values?.firstOrNull(),
                mimeType = entry.mimeType,
            )
        }

        fun sanitizePath(raw: String): String {
            val normalized = raw.replace('\\', '/')
            val segments = normalized.split("/").filter { it.isNotEmpty() && it != "." }
            require(segments.none { it == ".." }) { "Path traversal not allowed: $raw" }
            return segments.joinToString("/")
        }

        private fun parseModTime(modTime: String): Instant? =
            try {
                Instant.parse(modTime)
            } catch (_: Exception) {
                null
            }
    }
}
