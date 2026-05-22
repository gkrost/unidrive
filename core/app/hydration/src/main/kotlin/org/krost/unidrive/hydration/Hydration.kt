package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Thin verb-based SPI between the engine and platform-tier consumers
 * (Phase 2 FUSE co-daemon; future Phase 3 Dolphin extension; eventual
 * Windows / Android tiers). All paths are remote-namespace paths
 * (cloud-side), not local FS paths. The cache-path returned from
 * open_* is a local FS path inside ~/.cache/unidrive/hydration/.
 *
 * Handle IDs are caller-supplied opaque strings. The implementation
 * tracks them per IPC connection (see HydrationImpl) so a co-daemon
 * crash cleanly releases its open-set without explicit close calls.
 */
interface Hydration {
    suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult
    suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path): OpenResult
    suspend fun closeHandle(connectionId: String, handleId: String)
    suspend fun hydrate(path: String): HydrateResult
    suspend fun dehydrate(path: String): DehydrateResult

    val events: Flow<HydrationEvent>

    /** Called by IpcServer when an IPC connection closes. Clears that connection's open-set. */
    fun onConnectionClosed(connectionId: String)
}

sealed class OpenResult {
    data class Ok(val cachePath: Path) : OpenResult()
    data class Failed(val error: HydrationError) : OpenResult()
}

sealed class HydrateResult {
    data object Ok : HydrateResult()
    data class Failed(val error: HydrationError) : HydrateResult()
}

sealed class DehydrateResult {
    data object Ok : DehydrateResult()
    data object Busy : DehydrateResult()
    data class Failed(val error: HydrationError) : DehydrateResult()
}
