package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * rename(replace=true) is the editors' safe-save: close the temp file (the client fires
 * open_write, which uploads in the background), then rename it over the target. The
 * rename must not race that background upload: it would delete the destination in the
 * cloud, strand the temp's still-queued upload against a path that no longer exists, and
 * leave the new content owned by a row nothing will upload.
 */
class HydrationImplRenameUploadRaceTest {

    private class UploadingProvider : CloudProvider {
        override val id = "fake-upload-race"
        override val displayName = "Fake (rename/upload race test)"
        override var isAuthenticated = true

        // Provider calls in order, e.g. "upload:/a.txt=NEW", "delete:/b.txt", "move:/a.txt->/b.txt".
        val log = mutableListOf<String>()

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun getMetadata(path: String): CloudItem = throw ProviderException("Item not found: $path")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            val content = Files.readString(localPath)
            log += "upload:$remotePath=$content"
            return CloudItem(
                id = "up-$remotePath",
                name = remotePath.substringAfterLast('/'),
                path = remotePath,
                size = content.length.toLong(),
                isFolder = false,
                modified = Instant.now(),
                created = null,
                hash = "h-${content.length}",
                mimeType = null,
            )
        }
        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            log += "delete:$remotePath"
        }
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            log += "move:$fromPath->$toPath"
            return CloudItem(
                id = "mv-$toPath",
                name = toPath.substringAfterLast('/'),
                path = toPath,
                size = 0L,
                isFolder = false,
                modified = Instant.now(),
                created = null,
                hash = null,
                mimeType = null,
            )
        }
        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Rig(uploadScope: CoroutineScope) {
        val provider = UploadingProvider()
        val db = StateDatabase(dbPath = Files.createTempDirectory("unidrive-race-db").resolve("state.db"), inMemory = true)
            .also { it.initialize() }
        val engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createTempDirectory("unidrive-race-sync"),
            cacheRoot = Files.createTempDirectory("unidrive-race-cache"),
        )
        val impl = HydrationImpl(engine, db, recoveryUploadScope = uploadScope)

        // A file that already lives in the cloud (remote id set), cache cold.
        fun seedUploaded(path: String) = db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = "rid-$path",
                remoteHash = "h-old",
                remoteSize = 3L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )

        // A file created through the mount: no remote id yet, the cache holds the only copy.
        fun seedLocalOnly(path: String, content: String): Path {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = Instant.now().toEpochMilli(),
                    localSize = content.length.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            val cache = engine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, content)
            return cache
        }
    }

    private val busy = RenameResult.Failed(HydrationError.Generic("busy"))

    @Test
    fun `replace is refused as busy while the source upload is queued and works once it landed`() = runTest {
        val rig = Rig(this)
        rig.seedUploaded("/target.txt")
        val cache = rig.seedLocalOnly("/tmp.txt", "NEW")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { rig.impl.events.collect { events.add(it) } }
        yield()

        assertTrue(rig.impl.openForWrite("c", "h1", "/tmp.txt", cache) is OpenResult.Ok)
        val early = rig.impl.rename("/tmp.txt", "/target.txt", replace = true)

        assertEquals(busy, early, "the temp file's upload has not run yet; the replace must wait for it")
        assertTrue(rig.provider.log.isEmpty(), "a refused replace must not touch the cloud: ${rig.provider.log}")
        assertNotNull(rig.db.getEntry("/target.txt"), "the old destination must survive a refused replace")

        advanceUntilIdle() // the queued upload lands
        assertEquals(RenameResult.Ok, rig.impl.rename("/tmp.txt", "/target.txt", replace = true))

        assertEquals(
            listOf("upload:/tmp.txt=NEW", "delete:/target.txt", "move:/tmp.txt->/target.txt"),
            rig.provider.log,
            "upload, then delete the destination, then move the uploaded temp over it",
        )
        assertTrue(events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }.ok)
        assertNull(rig.db.getEntry("/tmp.txt"), "no row may be left at the temp path")
        assertNotNull(rig.db.getEntry("/target.txt")?.remoteId, "the target must be a cloud-backed row")
        collector.cancel()
    }

    @Test
    fun `replace is refused as busy while the destination upload is queued`() = runTest {
        val rig = Rig(this)
        rig.seedUploaded("/src.txt")
        rig.seedUploaded("/target.txt")
        val destCache = rig.engine.resolveCachePath("/target.txt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "EDITED")
        }

        assertTrue(rig.impl.openForWrite("c", "h1", "/target.txt", destCache) is OpenResult.Ok)
        val early = rig.impl.rename("/src.txt", "/target.txt", replace = true)

        assertEquals(busy, early, "deleting the destination under its own in-flight upload could resurrect stale bytes")
        assertTrue(rig.provider.log.isEmpty(), "a refused replace must not touch the cloud: ${rig.provider.log}")

        advanceUntilIdle()
        assertEquals(RenameResult.Ok, rig.impl.rename("/src.txt", "/target.txt", replace = true))
        assertEquals(
            listOf("upload:/target.txt=EDITED", "delete:/target.txt", "move:/src.txt->/target.txt"),
            rig.provider.log,
        )
    }

    @Test
    fun `rename without replace is not held back by an upload in flight`() = runTest {
        val rig = Rig(this)
        rig.seedUploaded("/src.txt")
        val cache = rig.engine.resolveCachePath("/src.txt").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "EDITED")
        }

        assertTrue(rig.impl.openForWrite("c", "h1", "/src.txt", cache) is OpenResult.Ok)
        val r = rig.impl.rename("/src.txt", "/dst.txt")

        assertEquals(RenameResult.Ok, r, "only the replace path is gated; the plain rename keeps its old behaviour")
        advanceUntilIdle()
    }
}
