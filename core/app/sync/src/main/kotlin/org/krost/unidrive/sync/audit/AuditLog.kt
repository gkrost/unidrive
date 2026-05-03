package org.krost.unidrive.sync.audit

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * UD-113: append-only JSONL audit log of sync mutations.
 *
 * One entry per applied action (Download / Upload / Delete / Move / CreateRemoteFolder),
 * carrying enough context to prove "this run mutated X at Y on behalf of Z" without
 * relying on the verbose DEBUG-level log file (which rotates aggressively + loses MDC
 * under coroutine resumption per UD-254a). Closes the STRIDE Repudiation gap noted in
 * the v0.0.0 SECURITY.md seed (UD-111 covers token-refresh failure telemetry only;
 * UD-113 covers the per-action audit trail).
 *
 * **File layout.** One file per UTC day per profile:
 * `{profileConfigDir}/audit-YYYY-MM-DD.jsonl`. Date-stamped naming is the rotation —
 * no separate sweeper, no log4j rolling-file-appender. Old files stay around for the
 * operator's retention policy to handle (out of scope here).
 *
 * **Concurrency.** [emit] synchronises on the writer; safe to call from concurrent Pass-2
 * upload/download workers. Each entry is a single JSONL line + newline; no partial writes
 * cross between threads.
 *
 * **MCP surfacing.** Out of scope for this ticket — file-growth is the only acceptance
 * criterion. A future ticket can add an `unidrive_audit` MCP tool that reads these files.
 */
@Serializable
data class AuditEntry(
    /** ISO-8601 UTC timestamp the action was *applied* (post-success or post-failure). */
    val ts: String,
    /** Action verb: `Download`, `Upload`, `Delete`, `Move`, `CreateRemoteFolder`. */
    val action: String,
    /** Forward-slash sync path, e.g. `/Documents/report.pdf`. */
    val path: String,
    /** Bytes transferred (uploads/downloads) or affected (delete=remote size). null for moves/folders. */
    val size: Long? = null,
    /** Pre-action remote hash (if known). null for new uploads, deletes against unhydrated rows. */
    val oldHash: String? = null,
    /** Post-action remote hash (if known). null for deletes. */
    val newHash: String? = null,
    /** `success` or `failed:<exception class>: <message>`. */
    val result: String,
    /** Profile name (provider profile key from config.toml). */
    val profile: String,
    /** For Move actions, the source path (path is the destination). */
    val fromPath: String? = null,
)

class AuditLog(
    private val profileConfigDir: Path,
    private val profileName: String,
    /** Test seam — defaults to system clock; tests can pin a date. */
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(AuditLog::class.java)
    private val json = Json { encodeDefaults = false }
    private val writeLock = Any()

    init {
        Files.createDirectories(profileConfigDir)
    }

    /** UTC-day-stamped filename. Rotation is implicit in the name; new day → new file. */
    internal fun pathForToday(): Path {
        val today = LocalDate.ofInstant(clock(), ZoneOffset.UTC)
        return profileConfigDir.resolve("audit-$today.jsonl")
    }

    /**
     * Append [entry] to today's audit file. Failures (disk full, permission denied) are
     * logged at WARN and swallowed — the audit log must not block the sync engine. The
     * trade-off is documented in SECURITY.md UD-113 §"Audit log durability".
     */
    fun emit(entry: AuditEntry) {
        try {
            synchronized(writeLock) {
                val line = json.encodeToString(entry) + "\n"
                Files.write(
                    pathForToday(),
                    line.toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        } catch (e: Exception) {
            log.warn("UD-113: failed to write audit entry for {}: {}", entry.path, e.message)
        }
    }

    /** Convenience builder — fills [ts] from the clock and [profile] from the constructor. */
    fun emit(
        action: String,
        path: String,
        result: String,
        size: Long? = null,
        oldHash: String? = null,
        newHash: String? = null,
        fromPath: String? = null,
    ) {
        emit(
            AuditEntry(
                ts = clock().toString(),
                action = action,
                path = path,
                size = size,
                oldHash = oldHash,
                newHash = newHash,
                result = result,
                profile = profileName,
                fromPath = fromPath,
            ),
        )
    }
}
