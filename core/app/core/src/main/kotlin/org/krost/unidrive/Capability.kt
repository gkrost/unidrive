package org.krost.unidrive

/**
 * Sealed hierarchy of provider capabilities. Each [CloudProvider] declares which
 * capabilities it actually supports via [CloudProvider.capabilities]; callers can
 * pattern-match on [CapabilityResult.Unsupported] to distinguish "feature not
 * supported by this adapter" from a successful-but-empty result.
 *
 * See ADR-0005 / UD-301.
 */
sealed class Capability {
    object Delta : Capability()

    object DeltaShared : Capability()

    object Webhook : Capability()

    object Share : Capability()

    object ListShares : Capability()

    object RevokeShare : Capability()

    object VerifyItem : Capability()

    object QuotaExact : Capability()

    object FastBootstrap : Capability()

    override fun toString(): String = this::class.simpleName ?: "Capability"
}

/**
 * Result wrapper for optional [CloudProvider] operations.
 *
 * - [Success] carries the operation's value.
 * - [Unsupported] signals that the provider does not declare the given [Capability]
 *   and callers should take a different path (skip, degrade, warn). Never thrown —
 *   callers must handle both branches explicitly.
 */
sealed class CapabilityResult<out T> {
    data class Success<T>(
        val value: T,
    ) : CapabilityResult<T>()

    data class Unsupported(
        val capability: Capability,
        val reason: String,
    ) : CapabilityResult<Nothing>()

    /** True when this is [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** True when this is [Unsupported]. */
    val isUnsupported: Boolean get() = this is Unsupported

    /** Return the [Success] value, or null if [Unsupported]. */
    fun valueOrNull(): T? = (this as? Success)?.value
}

/**
 * Thrown by adapter-internal helpers that prefer to fail fast on capability
 * misuse. Not thrown from the public [CloudProvider] surface — those methods
 * return [CapabilityResult.Unsupported] instead.
 */
class UnsupportedCapabilityException(
    val capability: Capability,
    message: String,
) : RuntimeException(message)
