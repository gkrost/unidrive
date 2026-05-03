package org.krost.unidrive.internxt.model

import java.time.Instant

/**
 * UD-372: lenient ISO-8601 → Instant parser used by [InternxtFile] and [InternxtFolder]
 * to eagerly resolve their timestamp strings at construction time. Behaviour is identical
 * to the legacy `InternxtProvider.parseTime` helper this replaces — malformed or empty
 * input returns `null` rather than throwing — so the existing reconcile loop's tolerance
 * for `modified=null` rows is preserved.
 *
 * Eager parsing at construction means the per-row `delta()` hot loop in `toDeltaCloudItem`
 * no longer pays the `?.let { … }` lambda allocation + try/catch frame per timestamp;
 * for a 60k-item walk that's ~120k fewer Function1 allocations.
 */
internal fun tryParseInternxtInstant(raw: String?): Instant? {
    if (raw.isNullOrEmpty()) return null
    return try {
        Instant.parse(raw)
    } catch (_: Exception) {
        null
    }
}
