package org.krost.unidrive.hydration

/**
 * Hydration state-change events emitted as a Flow by the SPI.
 * Phase 3 consumers subscribe via `hydration.subscribe` to drive
 * icon overlays / desktop notifications.
 */
sealed class HydrationEvent {
    abstract val path: String

    data class Hydrating(override val path: String) : HydrationEvent()
    data class Hydrated(override val path: String, val bytes: Long) : HydrationEvent()
    data class Dehydrated(override val path: String) : HydrationEvent()
    data class Failed(override val path: String, val error: HydrationError) : HydrationEvent()
}
