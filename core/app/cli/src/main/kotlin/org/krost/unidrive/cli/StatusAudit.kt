package org.krost.unidrive.cli

import org.krost.unidrive.QuotaInfo
import java.util.Locale

/**
 * UD-316 — pure data + formatter for `unidrive status --audit`.
 *
 * Kept separate from [StatusCommand] so the formatter is trivially unit-testable
 * against synthesised inputs (no DB, no network, no provider factory). All
 * provider-specific nuance is reduced to four optionals in [AuditReport]:
 *   - [quota] — null when the provider does not declare [org.krost.unidrive.Capability.QuotaExact]
 *   - [includeShared] — true / false for OneDrive, null for every other provider
 *   - [pendingCursor] — null means enumeration is complete (no pending page)
 *   - [lastFullScan] — raw ISO-8601 string from `sync_state.last_full_scan`, null if never
 */
data class AuditReport(
    val profileName: String,
    val providerDisplayName: String,
    val quota: QuotaInfo?,
    val trackedFiles: Int,
    val trackedBytes: Long,
    val nonHydrated: Int,
    val pendingCursor: String?,
    val lastFullScan: String?,
    val extraFields: List<org.krost.unidrive.StatusField> = emptyList(),
)

/**
 * Severity bucket for the delta. Thresholds match the ticket:
 *   green  < 5%    (or no quota available)
 *   yellow 5–25%
 *   red   >25%   OR  pending_cursor present >24h (caller-supplied)
 */
enum class AuditSeverity { GREEN, YELLOW, RED }

object StatusAudit {
    /**
     * Human-readable bytes with one decimal place above 1 KiB.
     *
     * UD-238: this formerly went by `formatBytesBinary`, which was a lie — the divisor
     * is 2^30 (binary), not 10^9 (decimal-SI). Relabelled to IEC binary units
     * (KiB/MiB/GiB) so the suffix matches the math. Numbers stay identical;
     * only the label changes.
     */
    fun formatBytesBinary(bytes: Long): String {
        val abs = kotlin.math.abs(bytes)
        return when {
            abs < 1024L -> "$bytes B"
            abs < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
            abs < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    fun severityFor(report: AuditReport): AuditSeverity {
        val quota = report.quota ?: return AuditSeverity.GREEN
        if (quota.used <= 0) return AuditSeverity.GREEN
        val delta = quota.used - report.trackedBytes
        if (delta <= 0) return AuditSeverity.GREEN
        val pct = delta.toDouble() / quota.used.toDouble()
        return when {
            pct > 0.25 -> AuditSeverity.RED
            pct >= 0.05 -> AuditSeverity.YELLOW
            else -> AuditSeverity.GREEN
        }
    }

    /**
     * Render the audit report as a fixed-shape block. Colour handling is kept
     * out of this function; callers can post-process with [AnsiHelper] if a TTY
     * is attached. The output layout matches the UD-316 ticket example.
     */
    fun formatAuditReport(report: AuditReport): String {
        val lines = mutableListOf<String>()
        val profileLine = "Profile:          ${report.profileName}  (${report.providerDisplayName})"
        lines.add(profileLine)

        if (report.quota != null) {
            val q = report.quota
            val quotaLine = "Remote quota:     ${formatBytesBinary(q.used)} used / ${formatBytesBinary(q.total)} total"
            lines.add(quotaLine)
        } else {
            lines.add("Remote quota:     unavailable (provider lacks QuotaExact capability) — DB-only stats")
        }

        lines.add(
            "Tracked in DB:    ${String.format(
                Locale.ROOT,
                "%,d",
                report.trackedFiles,
            )} entries, ${formatBytesBinary(report.trackedBytes)}",
        )

        if (report.quota != null) {
            val delta = report.quota.used - report.trackedBytes
            val deltaLabel =
                when {
                    delta <= 0 -> "Delta:            ${formatBytesBinary(
                        -delta,
                    )} in DB beyond reported remote quota (likely folders / trash)"
                    else -> "Delta:            ${formatBytesBinary(delta)} of remote not yet in state.db"
                }
            lines.add(deltaLabel)
            if (delta > 0 && report.pendingCursor != null) {
                lines.add("                  -> run `unidrive -p ${report.profileName} sync` to continue enumeration")
            }
        }

        lines.add("Non-hydrated:     ${String.format(Locale.ROOT, "%,d", report.nonHydrated)} entries waiting for download")

        val cursorLine =
            if (report.pendingCursor != null) {
                "Pending cursor:   present (enumeration not complete)"
            } else {
                "Pending cursor:   none (enumeration complete)"
            }
        lines.add(cursorLine)

        lines.add("Last sync:        ${StatusCommand.formatLastSync(report.lastFullScan)}")

        for (field in report.extraFields) {
            lines.add("${field.label}:".padEnd(18) + field.value)
        }

        return lines.joinToString("\n")
    }
}
