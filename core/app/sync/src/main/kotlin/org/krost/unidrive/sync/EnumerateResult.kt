package org.krost.unidrive.sync

data class EnumerateResult(
    val ok: Boolean,
    val upserted: Int = 0,
    val reaped: Int = 0,
    val complete: Boolean = false,
    val error: String? = null,
)
