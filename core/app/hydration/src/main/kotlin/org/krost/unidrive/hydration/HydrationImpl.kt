package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class HydrationImpl(
    private val syncEngine: SyncEngine,
    private val stateDb: StateDatabase,
) : Hydration {

    private val _events = MutableSharedFlow<HydrationEvent>(extraBufferCapacity = 64)
    override val events: Flow<HydrationEvent> = _events.asSharedFlow()

    // connectionId -> handleId -> path
    // TODO(Task 8): when closeHandle and openForWrite land, two coroutines for
    // the same connectionId may race on the inner map. Switch to
    // `ConcurrentHashMap<String, ConcurrentMap<String, String>>` (or guard inner
    // mutations with a connection-scoped lock) before this becomes load-bearing.
    private val openSets =
        ConcurrentHashMap<String, MutableMap<String, String>>()

    override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult {
        val entry = stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.Generic("Unknown path: $path"))

        val cachePath = try {
            // Always emit Hydrating + Hydrated, even when SyncEngine returns a warm cache
            // without downloading: subscribers should see a consistent event stream
            // regardless of cache state; the cache layer is an implementation detail of
            // SyncEngine, not part of the Hydration SPI contract.
            _events.emit(HydrationEvent.Hydrating(path))
            val p = syncEngine.ensureHydrated(path)
            _events.emit(HydrationEvent.Hydrated(path, entry.remoteSize))
            p
        } catch (e: Exception) {
            val err = HydrationError.Generic(e.message ?: "download failed")
            _events.emit(HydrationEvent.Failed(path, err))
            return OpenResult.Failed(err)
        }

        openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = path
        return OpenResult.Ok(cachePath)
    }

    // Stubs for verbs not yet implemented — fail fast so missed tests show up
    override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path) =
        TODO("Task 9")
    override suspend fun closeHandle(connectionId: String, handleId: String) = TODO("Task 8")
    override suspend fun hydrate(path: String): HydrateResult = TODO("Task 10")
    override suspend fun dehydrate(path: String): DehydrateResult = TODO("Task 11")
    override fun onConnectionClosed(connectionId: String) { TODO("Task 8") }
}
