package org.krost.unidrive.io

/**
 * Format a byte count using IEC binary prefixes (`B` / `KiB` / `MiB` / `GiB`).
 *
 * UD-006: lifted from `CliProgressReporter.Companion.formatSize` and reused by
 * the call sites that previously held byte-identical copies (`TrashCommand`,
 * `VersionsCommand`). Output examples:
 *
 *   0           → "0 B"
 *   1023        → "1023 B"
 *   1024        → "1 KiB"
 *   1_000_000_000 → "953 MiB"
 *
 * Integer divisor by design — this variant is for human-scan listings where
 * "953 MiB" reads better than "953.7 MiB". The higher-precision float variants
 * in `RelocateCommand` (`%.1f` + TiB) and `StatusAudit.formatBytesBinary`
 * remain as-is because they intentionally trade a different precision/format;
 * see UD-006 §"Out of scope" for the convergence-to-one-formatter follow-up.
 *
 * Suffix mathematics is binary (divisor 2^10, 2^20, 2^30) so the suffix must
 * be IEC binary (`KiB`/`MiB`/`GiB`) — labelling these "KB"/"MB"/"GB" would
 * under-report by ~7 % per step (UD-238).
 */
fun formatSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
        else -> "${bytes / (1024 * 1024 * 1024)} GiB"
    }
