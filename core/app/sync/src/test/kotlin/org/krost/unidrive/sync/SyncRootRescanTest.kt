package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #504: `SyncEngine.rescanSyncRootForUpload`, the daemon's safety-net pass over the sync root.
 * Files that reach the sync root other than through the mount are uploaded; nothing is
 * downloaded or deleted, and rows without a sync-root file (cloud-only entries, cache-backed mount
 * files) are left alone.
 */
class SyncRootRescanTest {
    private lateinit var syncRoot: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider
    private val invalidated = mutableListOf<Set<String>>()
    private val busyPaths = mutableSetOf<String>()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-504-root")
        cacheRoot = Files.createTempDirectory("ud-504-cache")
        db = StateDatabase(Files.createTempDirectory("ud-504-db").resolve("state.db"))
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(
        syncPaths: List<String> = emptyList(),
        excludePatterns: List<String> = emptyList(),
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        excludePatterns = excludePatterns,
        syncPaths = syncPaths,
        standingScope = syncPaths,
        cacheRoot = cacheRoot,
        uploadInFlight = { it in busyPaths },
        viewInvalidationSink = { paths, _ -> invalidated.add(paths) },
    )

    private fun write(
        rel: String,
        text: String,
    ): Path {
        val p = syncRoot.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return p
    }

    private fun remoteRow(
        path: String,
        hydrated: Boolean,
        size: Long = 5,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = "h",
        remoteSize = size,
        remoteModified = Instant.parse("2026-01-01T00:00:00Z"),
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `a file written straight into the sync root is uploaded and gets an alive row`() =
        runTest {
            write("dropped.txt", "from a backup")
            val r = engine().rescanSyncRootForUpload()
            assertEquals(1, r.uploaded)
            assertEquals(listOf("/dropped.txt"), provider.uploadedPaths)
            val row = assertNotNull(db.getEntry("/dropped.txt"), "the mount lists from state.db: an alive row is needed")
            assertEquals("id-/dropped.txt", row.remoteId)
            assertTrue(row.isHydrated)
            assertTrue(invalidated.any { "/dropped.txt" in it }, "a mount must be told to re-list, got $invalidated")
        }

    @Test
    fun `a second pass uploads nothing more`() =
        runTest {
            write("a.txt", "x")
            val e = engine()
            e.rescanSyncRootForUpload()
            provider.uploadedPaths.clear()
            val r = e.rescanSyncRootForUpload()
            assertEquals(0, r.uploaded)
            assertTrue(provider.uploadedPaths.isEmpty())
        }

    @Test
    fun `an edited synced file is uploaded again over its remote id`() =
        runTest {
            val file = write("doc.txt", "v1")
            val e = engine()
            e.rescanSyncRootForUpload()
            provider.uploadedPaths.clear()
            cloudCopyAsRecorded("/doc.txt")
            Files.writeString(file, "version two")
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            val r = e.rescanSyncRootForUpload()
            assertEquals(1, r.uploaded)
            assertEquals(listOf("/doc.txt"), provider.uploadedPaths)
            assertEquals("id-/doc.txt", provider.lastUploadExistingRemoteId)
        }

    // The cloud copy the provider reports now is exactly the one the row recorded (the fake's upload answers hash "uploaded").
    private fun cloudCopyAsRecorded(path: String, hash: String? = null) {
        val row = assertNotNull(db.getEntry(path))
        provider.deltaItems =
            listOf(
                CloudItem(
                    id = assertNotNull(row.remoteId),
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = row.remoteSize,
                    isFolder = false,
                    modified = row.remoteModified,
                    created = row.remoteModified,
                    hash = hash ?: row.remoteHash,
                    mimeType = null,
                ),
            )
    }

    // #504 review: an edit found by the rescan must never overwrite a cloud change the daemon has not seen yet.
    @Test
    fun `an edited file whose cloud copy changed meanwhile is not uploaded`() =
        runTest {
            val file = write("doc.txt", "v1")
            val e = engine()
            e.rescanSyncRootForUpload()
            provider.uploadedPaths.clear()
            cloudCopyAsRecorded("/doc.txt", hash = "edited elsewhere")
            Files.writeString(file, "my local edit")
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))

            val r = e.rescanSyncRootForUpload()

            assertEquals(0, r.uploaded)
            assertEquals(1, r.conflicts)
            assertEquals(emptyList(), provider.uploadedPaths, "the remote change must not be overwritten")
            assertEquals("my local edit", Files.readString(file), "the local edit stays for a full sync to keep both")
        }

    @Test
    fun `an edited file whose cloud copy is gone is not uploaded again`() =
        runTest {
            val file = write("doc.txt", "v1")
            val e = engine()
            e.rescanSyncRootForUpload()
            provider.uploadedPaths.clear()
            provider.getMetadataError = org.krost.unidrive.ProviderException("Item not found: /doc.txt")
            Files.writeString(file, "edit of a file deleted in the cloud")
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))

            val r = e.rescanSyncRootForUpload()

            assertEquals(0, r.uploaded)
            assertEquals(1, r.conflicts)
            assertEquals(emptyList(), provider.uploadedPaths, "a remote delete is a full sync's decision, not a resurrection")
        }

    @Test
    fun `an edited file whose cloud copy cannot be checked waits for the next pass`() =
        runTest {
            val file = write("doc.txt", "v1")
            val e = engine()
            e.rescanSyncRootForUpload()
            provider.uploadedPaths.clear()
            provider.getMetadataError = java.io.IOException("connection reset")
            Files.writeString(file, "version two")
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))

            val first = e.rescanSyncRootForUpload()
            assertEquals(0, first.uploaded)
            assertEquals(emptyList(), provider.uploadedPaths)

            provider.getMetadataError = null
            cloudCopyAsRecorded("/doc.txt")
            val second = e.rescanSyncRootForUpload()
            assertEquals(1, second.uploaded, "uploaded once the cloud copy could be checked")
        }

    @Test
    fun `a new folder is created remotely before the file in it is uploaded`() =
        runTest {
            write("new/deep/file.txt", "x")
            val r = engine().rescanSyncRootForUpload()
            assertEquals(2, r.foldersCreated)
            assertEquals(listOf("/new", "/new/deep"), provider.createdFolders)
            assertEquals(listOf("/new/deep/file.txt"), provider.uploadedPaths)
        }

    @Test
    fun `nothing is downloaded or deleted and cloud-only and cache-backed rows are untouched`() =
        runTest {
            // A remote-only row: no file in the sync root, never hydrated.
            db.upsertEntry(remoteRow("/cloud-only.bin", hydrated = false))
            // A hydrated row whose sync-root file the user deleted: a delete the pass must NOT propagate.
            val gone = write("deleted-locally.txt", "12345")
            db.upsertEntry(
                remoteRow("/deleted-locally.txt", hydrated = true).copy(
                    localMtime = Files.getLastModifiedTime(gone).toMillis(),
                    localSize = 5,
                ),
            )
            Files.delete(gone)
            // A mount write whose only copy is the cache file (cache-backed, not uploaded yet).
            val cachePath = engine().resolveCachePath("/mount-made.txt")
            Files.createDirectories(cachePath.parent)
            Files.writeString(cachePath, "cached")
            db.upsertEntry(
                SyncEntry(
                    path = "/mount-made.txt", remoteId = null, remoteHash = null, remoteSize = 0, remoteModified = null,
                    localMtime = Files.getLastModifiedTime(cachePath).toMillis(), localSize = 6, isFolder = false,
                    isPinned = false, isHydrated = true, lastSynced = Instant.now(), cacheBacked = true,
                ),
            )
            // A plain file that does need uploading, so the pass has real work.
            write("fresh.txt", "new")

            val before = db.getAllEntries().filter { it.path != "/fresh.txt" }.associateBy { it.path }
            val r = engine().rescanSyncRootForUpload()

            assertEquals(1, r.uploaded)
            assertEquals(listOf("/fresh.txt"), provider.uploadedPaths)
            assertTrue(provider.downloadByIdCalls.isEmpty() && provider.downloadByPathCalls.isEmpty(), "no download")
            assertTrue(provider.deletedPaths.isEmpty(), "no remote delete")
            for ((path, row) in before) assertEquals(row, db.getEntry(path), "$path must be untouched")
            assertFalse(Files.exists(syncRoot.resolve("cloud-only.bin")), "no placeholder or download in the sync root")
            assertTrue(Files.exists(cachePath), "the cache copy stays")
        }

    @Test
    fun `an edited not-hydrated row is left for a full sync to resolve`() =
        runTest {
            db.upsertEntry(remoteRow("/stub.txt", hydrated = false, size = 9))
            write("stub.txt", "my own bytes")
            val r = engine().rescanSyncRootForUpload()
            assertEquals(0, r.uploaded)
            assertEquals(1, r.skipped)
            assertTrue(provider.uploadedPaths.isEmpty())
            assertTrue(provider.downloadByIdCalls.isEmpty())
            assertEquals("my own bytes", Files.readString(syncRoot.resolve("stub.txt")))
        }

    @Test
    fun `a path whose hydration upload is queued or in flight is skipped`() =
        runTest {
            write("busy.txt", "x")
            write("free.txt", "y")
            busyPaths.add("/busy.txt")
            val r = engine().rescanSyncRootForUpload()
            assertEquals(listOf("/free.txt"), provider.uploadedPaths)
            assertEquals(1, r.skipped)
        }

    @Test
    fun `excluded files and files outside the scope are ignored`() =
        runTest {
            write("inbox/keep.txt", "in scope")
            write("inbox/skip.tmp2", "excluded")
            write("other/outside.txt", "out of scope")
            val r = engine(syncPaths = listOf("/inbox"), excludePatterns = listOf("/inbox/*.tmp2")).rescanSyncRootForUpload()
            assertEquals(listOf("/inbox/keep.txt"), provider.uploadedPaths)
            assertEquals(1, r.uploaded)
            assertNull(db.getEntry("/inbox/skip.tmp2"))
            assertNull(db.getEntry("/other/outside.txt"))
            assertFalse("/other" in provider.createdFolders)
        }

    @Test
    fun `a failed upload does not stop the pass and is retried by the next one`() =
        runTest {
            write("a.txt", "x")
            write("b.txt", "y")
            provider.uploadFailCount = 1
            val e = engine()
            val first = e.rescanSyncRootForUpload()
            assertEquals(1, first.failed)
            assertEquals(1, first.uploaded)
            val second = e.rescanSyncRootForUpload()
            assertEquals(1, second.uploaded, "the file whose upload failed is retried")
            assertEquals(setOf("/a.txt", "/b.txt"), provider.uploadedPaths.toSet())
        }

    @Test
    fun `a missing sync root is a no-op and is not created`() =
        runTest {
            val gone = syncRoot.resolve("not-there")
            val e =
                SyncEngine(
                    provider = provider, db = db, syncRoot = gone, cacheRoot = cacheRoot,
                )
            assertTrue(e.rescanSyncRootForUpload().notRun)
            assertFalse(Files.exists(gone))
        }

    @Test
    fun `a sync root that differs from the recorded one is not scanned`() =
        runTest {
            db.setSyncState("sync_root", syncRoot.resolveSibling("somewhere-else").toAbsolutePath().normalize().toString())
            write("a.txt", "x")
            assertTrue(engine().rescanSyncRootForUpload().notRun)
            assertTrue(provider.uploadedPaths.isEmpty())
        }
}
