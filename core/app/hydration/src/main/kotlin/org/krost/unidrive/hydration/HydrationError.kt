package org.krost.unidrive.hydration

/**
 * Sealed extension point for hydration failure modes. Phase 1 ships
 * only Generic; Phase 3 adds Transient / Permanent / QuotaExhausted /
 * Busy as the icon-overlay UX surfaces them.
 */
sealed interface HydrationError {
    val message: String

    data class Generic(override val message: String) : HydrationError
}
