package org.krost.unidrive

import java.time.Instant

/**
 * Result of [ProviderFactory.beginInteractiveAuth].
 *
 * The map-carrier shape (rather than a typed subclass per flow) keeps
 * :app:mcp provider-agnostic at the type level: each provider populates
 * its own JSON-payload keys, and the MCP handler embeds them verbatim.
 * UD-014 rationale: see docs/specs/2026-05-16-ud-014-mcp-auth-provider-agnostic-design.md §8.
 */
data class BeginAuthResult(
    /** Opaque handle the caller passes to completeInteractiveAuth. */
    val continuationHandle: String,
    /**
     * Provider-supplied JSON-payload keys (string values to keep the
     * wire format unambiguous). OneDrive populates verification_uri,
     * user_code, interval_seconds, expires_in, message.
     *
     * CONTRACT: iteration order matters — the MCP handler emits these
     * keys in iteration order. Callers in `:app:core`'s sister
     * modules should construct via [BeginAuthResult.of] (below),
     * which defensively copies into a [LinkedHashMap]. Direct
     * primary-constructor calls bypass that and require the caller
     * to guarantee a [LinkedHashMap] / `linkedMapOf` / `buildMap`.
     */
    val fields: Map<String, String>,
    /** Wall-clock deadline after which the handle is invalid. */
    val expiresAt: Instant,
    /** Provider-suggested polling interval, if applicable. */
    val retryAfterSeconds: Long? = null,
) {
    companion object {
        /**
         * Preferred entry point. Defensively copies [fields] into a
         * [LinkedHashMap] so iteration order is frozen at construction
         * time — turns the documented iteration-order contract into
         * a real runtime invariant for callers that may not have
         * built their map via [linkedMapOf].
         */
        fun of(
            continuationHandle: String,
            fields: Map<String, String>,
            expiresAt: Instant,
            retryAfterSeconds: Long? = null,
        ): BeginAuthResult =
            BeginAuthResult(
                continuationHandle = continuationHandle,
                fields = LinkedHashMap(fields),
                expiresAt = expiresAt,
                retryAfterSeconds = retryAfterSeconds,
            )
    }
}

/** Outcome of [ProviderFactory.completeInteractiveAuth]. */
sealed class CompleteAuthResult {
    /** Tokens persisted to disk under profileDir; caller may proceed. */
    data object Success : CompleteAuthResult()

    /** Auth not finished — poll again after [retryAfterSeconds]. */
    data class Pending(
        val retryAfterSeconds: Long,
    ) : CompleteAuthResult()

    /** Terminal failure with a user-displayable message. */
    data class Failure(
        val message: String,
    ) : CompleteAuthResult()
}
