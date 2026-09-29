package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #418: `SyncEngine.ensureHydrated` downloads into the hydration CACHE directory, a different
 * file from the one in the sync root. `local_mtime` / `local_size` on the row are the baseline
 * [LocalScanner] compares the SYNC-ROOT file against, so writing the cache copy's stats into them
 * made the next scan read an untouched sync-root file as modified and upload it.
 *
 * The fix leaves the baseline alone only when it provably still describes the sync-root file
 * (the file is there with exactly the mtime and size the row recorded). Without such a file
 * (mount mode: FUSE / Windows client) the cache copy IS the local file and the row keeps
 * recording it, because `HydrationImpl.lastSynced()` and the co-daemon's crash-recovery scanner
 * use `local_mtime` as their watermark.
 */
class HydrationKeepsSyncRootBaselineTest {
    private lateinit var syncRoot: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val remoteModified = Instant.parse("2026-03-28T12:00:00Z")
    private val content = "hello hydration".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-418-root")
        cacheRoot = Files.createTempDirectory("ud-418-cache")
        val dbPath = Files.createTempDirectory("ud-418-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                cacheRoot = cacheRoot,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun remoteItem(
        path: String,
        size: Long,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = false,
        modified = remoteModified,
        created = remoteModified,
        hash = "hash-$path",
        mimeType = null,
    )

    /** A normal sync run: the remote file lands in the sync root and the row baselines it. */
    private suspend fun syncRemoteFileDown(path: String) {
        provider.files[path] = content
        provider.deltaItems = listOf(remoteItem(path, content.size.toLong()))
        engine.syncOnce()
        // From here on nothing changes remotely.
        provider.deltaItems = emptyList()
        provider.deltaCursor = "cursor-2"
    }

    private fun mtimeOf(path: Path): Long = Files.getLastModifiedTime(path).toMillis()

    @Test
    fun `ensureHydrated leaves the sync-root baseline alone when the file there is the tracked version`() =
        runTest {
            syncRemoteFileDown("/doc.txt")
            val syncFile = syncRoot.resolve("doc.txt")
            val before = assertNotNull(db.getEntry("/doc.txt"))
            assertTrue(before.isHydrated, "precondition: the sync run hydrated the file")
            assertEquals(mtimeOf(syncFile), before.localMtime, "precondition: baseline is the sync-root file")
            assertEquals(content.size.toLong(), before.localSize, "precondition: baseline is the sync-root file")

            // Cold cache: the copy the daemon serves does not exist yet.
            val cachePath = engine.resolveCachePath("/doc.txt")
            assertFalse(Files.exists(cachePath), "precondition: cold cache")

            assertEquals(cachePath, engine.ensureHydrated("/doc.txt"))
            assertContentEquals(content, Files.readAllBytes(cachePath), "the cache copy must have been downloaded")
            assertNotEquals(
                mtimeOf(syncFile),
                mtimeOf(cachePath),
                "precondition: the cache copy carries a different mtime than the sync-root file",
            )

            val after = assertNotNull(db.getEntry("/doc.txt"))
            assertEquals(mtimeOf(syncFile), after.localMtime, "local_mtime must still describe the sync-root file")
            assertEquals(Files.size(syncFile), after.localSize, "local_size must still describe the sync-root file")
            assertTrue(after.isHydrated)
            assertEquals(content.size.toLong(), after.remoteSize)

            // What the next sync sees: the untouched sync-root file is unchanged.
            val changes = LocalScanner(syncRoot, db).scan()
            assertTrue(changes.isEmpty(), "LocalScanner must report no change for the untouched file, got: $changes")

            engine.syncOnce()
            assertTrue(provider.uploadedPaths.isEmpty(), "no upload for a file nobody changed, got: ${provider.uploadedPaths}")
        }

    @Test
    fun `ensureHydrated without a sync-root file still records the cache copy as the local baseline`() =
        runTest {
            // Mount mode (FUSE / Windows client): the row comes from an enumeration, nothing is in
            // the sync root, and the cache copy IS the local file.
            provider.files["/mnt.txt"] = content
            db.upsertEntry(
                SyncEntry(
                    path = "/mnt.txt",
                    remoteId = "id-/mnt.txt",
                    remoteHash = "hash-/mnt.txt",
                    remoteSize = content.size.toLong(),
                    remoteModified = remoteModified,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            assertFalse(Files.exists(syncRoot.resolve("mnt.txt")), "precondition: no file in the sync root")

            val cachePath = engine.ensureHydrated("/mnt.txt")

            val after = assertNotNull(db.getEntry("/mnt.txt"))
            assertTrue(after.isHydrated)
            assertEquals(mtimeOf(cachePath), after.localMtime, "mount mode: local_mtime is the cache copy's (the lastSynced watermark)")
            assertEquals(Files.size(cachePath), after.localSize)
        }

    @Test
    fun `ensureHydrated does not rebaseline a sync-root file that was modified locally`() =
        runTest {
            syncRemoteFileDown("/doc.txt")
            val syncFile = syncRoot.resolve("doc.txt")
            val original = assertNotNull(db.getEntry("/doc.txt"))

            // The user edits the file after the last sync: new content, new size, new mtime.
            val edited = "edited after the last sync".toByteArray()
            Files.write(syncFile, edited)
            Files.setLastModifiedTime(syncFile, FileTime.from(Instant.parse("2026-04-01T08:00:00Z")))

            engine.ensureHydrated("/doc.txt")

            val after = assertNotNull(db.getEntry("/doc.txt"))
            assertFalse(
                after.localMtime == mtimeOf(syncFile) && after.localSize == Files.size(syncFile),
                "the row must not adopt the edited file's mtime/size as its baseline",
            )
            assertNotEquals(original.localMtime, mtimeOf(syncFile), "sanity: the file really differs from the row's baseline")

            val changes = LocalScanner(syncRoot, db).scan()
            assertEquals(ChangeState.MODIFIED, changes["/doc.txt"], "the edit must still be visible to the scanner")

            engine.syncOnce()
            assertTrue(provider.uploadedPaths.contains("/doc.txt"), "the local edit must be uploaded, not lost")
            assertContentEquals(edited, provider.files["/doc.txt"], "the remote must end up with the edited content")
        }

    @Test
    fun `ensureHydrated on a freed placeholder never turns it into an upload of the stub`() =
        runTest {
            syncRemoteFileDown("/doc.txt")
            val syncFile = syncRoot.resolve("doc.txt")

            // What `unidrive free` does: the sync-root file becomes a zero-filled placeholder of
            // the remote size and remote mtime, and the row is flagged not hydrated.
            val hydrated = assertNotNull(db.getEntry("/doc.txt"))
            PlaceholderManager(syncRoot).dehydrate("/doc.txt", hydrated.remoteSize, hydrated.remoteModified)
            db.upsertEntry(hydrated.copy(isHydrated = false))
            assertEquals(hydrated.localMtime, mtimeOf(syncFile), "precondition: the placeholder matches the row's baseline")
            assertEquals(hydrated.localSize, Files.size(syncFile), "precondition: the placeholder matches the row's baseline")

            engine.ensureHydrated("/doc.txt")

            // The cache got the real bytes; the sync-root file is still the placeholder, so the row
            // must neither claim it is hydrated nor re-baseline it against the cache copy.
            val after = assertNotNull(db.getEntry("/doc.txt"))
            assertFalse(after.isHydrated, "the sync-root file is still a placeholder")
            assertEquals(hydrated.localMtime, after.localMtime)
            assertEquals(hydrated.localSize, after.localSize)

            assertTrue(LocalScanner(syncRoot, db).scan().isEmpty(), "the placeholder must not look like a local edit")
            engine.syncOnce()
            assertTrue(provider.uploadedPaths.isEmpty(), "the zero-filled placeholder must never be uploaded, got: ${provider.uploadedPaths}")
            assertContentEquals(content, provider.files["/doc.txt"], "the remote content must be untouched")
            assertContentEquals(content, Files.readAllBytes(syncFile), "the next sync re-hydrates the sync-root file")
        }
}
