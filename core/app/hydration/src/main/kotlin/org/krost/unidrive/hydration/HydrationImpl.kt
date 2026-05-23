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
            val bytes = java.nio.file.Files.size(p)
            _events.emit(HydrationEvent.Hydrated(path, bytes))
            p
        } catch (e: Exception) {
            val err = HydrationError.Generic(e.message ?: "download failed")
            _events.emit(HydrationEvent.Failed(path, err))
            return OpenResult.Failed(err)
        }

        openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = path
        return OpenResult.Ok(cachePath)
    }

    override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path): OpenResult {
        stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.Generic("Unknown path: $path"))

        return try {
            _events.emit(HydrationEvent.Hydrating(path))
            syncEngine.uploadFromCache(path, cachePath)
            val bytes = java.nio.file.Files.size(cachePath)
            _events.emit(HydrationEvent.Hydrated(path, bytes))
            openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = path
            OpenResult.Ok(cachePath)
        } catch (e: Exception) {
            val err = HydrationError.Generic(e.message ?: "upload failed")
            _events.emit(HydrationEvent.Failed(path, err))
            OpenResult.Failed(err)
        }
    }

    override suspend fun closeHandle(connectionId: String, handleId: String) {
        openSets[connectionId]?.remove(handleId)
    }
    override suspend fun hydrate(path: String): HydrateResult {
        return try {
            _events.emit(HydrationEvent.Hydrating(path))
            val cachePath = syncEngine.ensureHydrated(path)
            val bytes = java.nio.file.Files.size(cachePath)
            _events.emit(HydrationEvent.Hydrated(path, bytes))
            HydrateResult.Ok
        } catch (e: Exception) {
            val err = HydrationError.Generic(e.message ?: "hydrate failed")
            _events.emit(HydrationEvent.Failed(path, err))
            HydrateResult.Failed(err)
        }
    }
    override suspend fun dehydrate(path: String): DehydrateResult {
        stateDb.getEntry(path)
            ?: return DehydrateResult.Failed(HydrationError.Generic("Unknown path: $path"))

        // Check the open-set across ALL connections
        val anyOpen = openSets.values.any { perConn -> perConn.containsValue(path) }
        if (anyOpen) return DehydrateResult.Busy

        return try {
            val cachePath = syncEngine.resolveCachePath(path)
            java.nio.file.Files.deleteIfExists(cachePath)
            stateDb.markUnhydrated(path)
            _events.emit(HydrationEvent.Dehydrated(path))
            DehydrateResult.Ok
        } catch (e: Exception) {
            val err = HydrationError.Generic(e.message ?: "dehydrate failed")
            _events.emit(HydrationEvent.Failed(path, err))
            DehydrateResult.Failed(err)
        }
    }
    override suspend fun lastSynced(path: String): LastSyncedResult {
        val entry = stateDb.getEntry(path) ?: return LastSyncedResult.Unknown("unknown_path")
        val mtime = entry.localMtime ?: return LastSyncedResult.Unknown("no_mtime")
        return LastSyncedResult.Ok(mtime)
    }
    override fun onConnectionClosed(connectionId: String) {
        openSets.remove(connectionId)
    }
}
