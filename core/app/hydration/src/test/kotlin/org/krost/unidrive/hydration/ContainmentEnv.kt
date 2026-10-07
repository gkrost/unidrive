package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * A profile with its own temp folders for the cache-containment tests: a [SyncEngine] over a [MemProvider], its
 * state database, and [HydrationImpl] on top. [cacheDir] is the profile's hydration cache folder.
 */
internal class ContainmentEnv {
    val provider = MemProvider()
    val base: Path = Files.createTempDirectory("ud-containment")
    val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
    val engine =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createDirectories(base.resolve("root")),
            cacheRoot = base.resolve("cache"),
            cacheKey = "profile",
        )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val hydration = HydrationImpl(engine, db, recoveryUploadScope = scope)

    // The profile's cache folder.
    val cacheDir: Path = base.resolve("cache").resolve("unidrive").resolve("hydration").resolve("profile")

    // Where an unchecked resolution puts the cache file of "/../outside.txt": beside the cache folder.
    val beside: Path = cacheDir.resolveSibling("outside.txt")

    fun fileRow(
        path: String,
        remoteId: String? = "id-$path",
        isHydrated: Boolean = true,
        localMtime: Long? = null,
        localSize: Long? = null,
        cacheBacked: Boolean? = null,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = if (remoteId == null) null else "h-$path",
        remoteSize = localSize ?: 0L,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = localMtime,
        localSize = localSize,
        isFolder = false,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = Instant.now(),
        cacheBacked = cacheBacked,
    )

    fun folderRow(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = null,
            remoteSize = 0L,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = true,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    fun write(
        file: Path,
        text: String,
    ): Path {
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    fun close() {
        provider.uploadGate?.complete(Unit)
        scope.cancel()
        db.close()
        base.toFile().deleteRecursively()
    }

    companion object {
        val windows: Boolean = System.getProperty("os.name", "").lowercase().contains("win")

        /** The wire token of a failed verb result, or null for any other result. */
        fun tokenOf(result: Any?): String? =
            when (result) {
                is OpenResult.Failed -> result.error.message
                is CreateResult.Failed -> result.error.message
                is MkdirResult.Failed -> result.error.message
                is RenameResult.Failed -> result.error.message
                is DehydrateResult.Failed -> result.error.message
                is HydrateResult.Failed -> result.error.message
                else -> null
            }
    }
}
