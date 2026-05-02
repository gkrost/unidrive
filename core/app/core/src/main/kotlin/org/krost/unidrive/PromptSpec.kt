package org.krost.unidrive

/**
 * One prompt issued by the `profile add` wizard.
 *
 * Returned in order from [ProviderFactory.credentialPrompts]; the
 * CLI iterates the list and asks each in sequence, populating the
 * `properties` map under [key] before calling [ProviderFactory.create].
 */
data class PromptSpec(
    /** Config-key this prompt populates (e.g. "bucket", "host"). */
    val key: String,
    /** Human-readable label shown to the user (e.g. "S3 bucket"). */
    val label: String,
    /** True for password-style (no echo); false for free-text. */
    val isMasked: Boolean = false,
    /** Optional default suggested in the prompt (e.g. "auto"). */
    val default: String? = null,
    /** Whether the user MUST supply a value (true) or may skip (false). */
    val required: Boolean = true,
)
