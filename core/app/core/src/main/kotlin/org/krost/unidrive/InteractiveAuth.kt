package org.krost.unidrive

import java.time.Instant

/**
 * Result of [ProviderFactory.beginInteractiveAuth].
 *
 * The map-carrier shape (rather than a typed subclass per flow) keeps
 * :app:mcp provider-agnostic at the type level: each provider populates
 * its own JSON-payload keys, and the MCP handler embeds them verbatim.
 * UD-014 rationale: see docs/specs/2026-05-16-ud-014-*.md §8.
 */
data class BeginAuthResult(
    /** Opaque handle the caller passes to completeInteractiveAuth. */
    val continuationHandle: String,
    /**
     * Provider-supplied JSON-payload keys (string values to keep the
     * wire format unambiguous). OneDrive populates verification_uri,
     * user_code, interval_seconds, expires_in, message.
     *
     * CONTRACT: must be an insertion-order-preserving Map (i.e. a
     * [LinkedHashMap] built via [linkedMapOf] or `buildMap { … }`).
     * The MCP handler emits these keys in iteration order, so a
     * non-ordered map would produce nondeterministic JSON key order
     * across runs.
     */
    val fields: Map<String, String>,
    /** Wall-clock deadline after which the handle is invalid. */
    val expiresAt: Instant,
    /** Provider-suggested polling interval, if applicable. */
    val retryAfterSeconds: Long? = null,
)

/** Outcome of [ProviderFactory.completeInteractiveAuth]. */
sealed class CompleteAuthResult {
    /** Tokens persisted to disk under profileDir; caller may proceed. */
    data object Success : CompleteAuthResult()

    /** Auth not finished — poll again after [retryAfterSeconds]. */
    data class Pending(
        val retryAfterSeconds: Long,
    ) : CompleteAuthResult()

    /** Terminal failure with a user-displayable message. */
    data class Error(
        val message: String,
    ) : CompleteAuthResult()
}
