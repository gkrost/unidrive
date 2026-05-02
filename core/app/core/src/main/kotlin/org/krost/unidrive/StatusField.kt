package org.krost.unidrive

/**
 * Provider-contributed key/value pair rendered in `unidrive status`
 * output after the shared fields.
 *
 * Returned from [CloudProvider.statusFields]. The renderer formats
 * each entry as `"${label}:".padEnd(18) + value`. Provider keeps
 * full control over what appears in its own rows.
 */
data class StatusField(
    /** Field label as shown in status output. */
    val label: String,
    /** Field value, already formatted for display. */
    val value: String,
)
