package org.krost.unidrive.sync

data class EnumerateResult(
    val ok: Boolean,
    val upserted: Int = 0,
    val reaped: Int = 0,
    val complete: Boolean = false,
    // True when this call was a no-op because another enumerate pass was already in
    // flight (engine-level single-flight guard). The view is being refreshed by the
    // in-flight pass; callers treat this as success and need not retry.
    val skipped: Boolean = false,
    val error: String? = null,
)
