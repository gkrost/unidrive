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
 * The fix leaves the row's local side (baseline and isHydrated) alone when the row is about the
 * file in the sync root: either the row is hydrated and the file has exactly the mtime and size
 * it recorded, or the row is NOT hydrated and a file is there (a placeholder from `unidrive free`
 * or an interrupted download, which never matches the baseline because `free` stamps the remote
 * mtime on it, and which must not start claiming real bytes). Without a sync-root file (mount
 * mode: FUSE / Windows client) the cache copy IS the local file and the row keeps recording it,
 * because `HydrationImpl.lastSynced()` and the co-daemon's crash-recovery scanner use
 * `local_mtime` as their watermark.
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

            // What `unidrive free` does to a file that was downloaded (so its mtime already equals
            // the remote one): a zero-filled placeholder of the remote size, row flagged not hydrated.
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

    @Test
    fun `ensureHydrated on a freed placeholder with a different mtime than the baseline never uploads the stub`() =
        runTest {
            // A file the user created locally and the engine uploaded: the row's local baseline is the
            // user's mtime, remote_modified is the time the cloud stored it.
            engine.syncOnce()
            val syncFile = syncRoot.resolve("doc.txt")
            Files.write(syncFile, content)
            Files.setLastModifiedTime(syncFile, FileTime.from(Instant.parse("2026-03-20T10:00:00.835Z")))
            engine.syncOnce()
            assertTrue(provider.uploadedPaths.contains("/doc.txt"), "precondition: the engine uploaded the local file")
            provider.uploadedPaths.clear()
            val uploaded = assertNotNull(db.getEntry("/doc.txt"))
            assertTrue(uploaded.isHydrated, "precondition: hydrated after the upload")
            assertEquals(mtimeOf(syncFile), uploaded.localMtime, "precondition: baseline is the user's file")

            // Exactly what `unidrive free` does (FreeCommand): dehydrate() zero-fills the file to the
            // remote size and stamps the REMOTE modified time on it; the row only gets isHydrated=false
            // and keeps its local baseline.
            PlaceholderManager(syncRoot).dehydrate("/doc.txt", uploaded.remoteSize, uploaded.remoteModified)
            db.upsertEntry(uploaded.copy(isHydrated = false, lastSynced = Instant.now()))
            assertNotEquals(uploaded.localMtime, mtimeOf(syncFile), "precondition: free changed the file's mtime")
            assertEquals(uploaded.localSize, Files.size(syncFile), "precondition: same size as the baseline")
            assertTrue(Files.readAllBytes(syncFile).all { it == 0.toByte() }, "precondition: zero-filled placeholder")

            engine.ensureHydrated("/doc.txt")

            val after = assertNotNull(db.getEntry("/doc.txt"))
            assertFalse(after.isHydrated, "the sync-root file is still a placeholder")
            assertEquals(uploaded.localMtime, after.localMtime, "the baseline must stay the user's")
            assertEquals(uploaded.localSize, after.localSize)

            assertTrue(LocalScanner(syncRoot, db).scan().isEmpty(), "the placeholder must not look like a local edit")
            engine.syncOnce()
            assertTrue(provider.uploadedPaths.isEmpty(), "the zero-filled placeholder must never be uploaded, got: ${provider.uploadedPaths}")
            assertContentEquals(content, provider.files["/doc.txt"], "the remote content must be untouched")
            assertContentEquals(content, Files.readAllBytes(syncFile), "the next sync re-hydrates the sync-root file")
        }

    @Test
    fun `ensureHydrated on an unhydrated row without a baseline leaves the stub in the sync root alone`() =
        runTest {
            // A row from a remote enumeration (no baseline, not hydrated) and a zero-byte stub an
            // interrupted download left in the sync root.
            provider.files["/stub.txt"] = content
            val item = remoteItem("/stub.txt", content.size.toLong())
            db.upsertEntry(
                SyncEntry(
                    path = "/stub.txt",
                    remoteId = item.id,
                    remoteHash = item.hash,
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
            val stub = syncRoot.resolve("stub.txt")
            Files.createFile(stub)
            Files.setLastModifiedTime(stub, FileTime.from(Instant.parse("2026-04-02T09:30:00Z")))

            engine.ensureHydrated("/stub.txt")

            val after = assertNotNull(db.getEntry("/stub.txt"))
            assertFalse(after.isHydrated, "the sync-root file is still a stub")
            assertEquals(null, after.localMtime, "there is no baseline to invent for the stub")
            assertEquals(null, after.localSize)
            assertEquals(content.size.toLong(), after.remoteSize)

            assertTrue(LocalScanner(syncRoot, db).scan().isEmpty(), "the stub must not look like a local edit")
            provider.deltaItems = listOf(item)
            engine.syncOnce()
            assertTrue(provider.uploadedPaths.isEmpty(), "the empty stub must never be uploaded, got: ${provider.uploadedPaths}")
            assertContentEquals(content, provider.files["/stub.txt"], "the remote content must be untouched")
            assertContentEquals(content, Files.readAllBytes(stub), "the next sync fills the stub with the real bytes")
        }

    @Test
    fun `ensureHydrated on a not-hydrated row holding real user content does not treat it as a placeholder`() =
        runTest {
            // The user replaced a freed placeholder with real content of the remote's size (a
            // restore, an editor writing into the stub). A file with non-placeholder bytes must
            // not be left for the recovery download to overwrite: it is an edit and gets uploaded.
            syncRemoteFileDown("/doc.txt")
            val syncFile = syncRoot.resolve("doc.txt")
            val hydrated = assertNotNull(db.getEntry("/doc.txt"))
            PlaceholderManager(syncRoot).dehydrate("/doc.txt", hydrated.remoteSize, hydrated.remoteModified)
            db.upsertEntry(hydrated.copy(isHydrated = false))

            val restored = "restored bytes!".toByteArray()
            assertEquals(hydrated.remoteSize, restored.size.toLong(), "precondition: same size as the remote")
            Files.write(syncFile, restored)
            Files.setLastModifiedTime(syncFile, FileTime.from(Instant.parse("2026-04-03T07:15:00Z")))

            engine.ensureHydrated("/doc.txt")

            val after = assertNotNull(db.getEntry("/doc.txt"))
            assertTrue(after.isHydrated, "real content under a not-hydrated row falls back to the cache baseline")
            val changes = LocalScanner(syncRoot, db).scan()
            assertEquals(ChangeState.MODIFIED, changes["/doc.txt"], "the restored content must be visible to the scanner")

            engine.syncOnce()
            assertTrue(provider.uploadedPaths.contains("/doc.txt"), "the user's content must be uploaded, got: ${provider.uploadedPaths}")
            assertContentEquals(restored, provider.files["/doc.txt"], "the remote must end up with the user's content")
        }

    @Test
    fun `uploadFromCache replay after a hydration read converges the sync-root placeholder`() =
        runTest {
            // The crash-recovery scanner replays a cache file whose mtime exceeds the last_synced
            // watermark as an open_write. After an open_read on a freed placeholder that replay
            // used to rebaseline the row to the cache copy's stats while the sync-root file was
            // still the zero-filled stub — the next sync then uploaded the stub (#420, one daemon
            // restart later). #423: the replayed bytes (identical to the remote's) are now
            // propagated into the sync root, so the placeholder is filled immediately and the
            // row baselines the FILLED file — never the stub. The #420 invariant stands: the
            // stub is never uploaded.
            syncRemoteFileDown("/doc.txt")
            val syncFile = syncRoot.resolve("doc.txt")
            val hydrated = assertNotNull(db.getEntry("/doc.txt"))
            PlaceholderManager(syncRoot).dehydrate("/doc.txt", hydrated.remoteSize, hydrated.remoteModified)
            db.upsertEntry(hydrated.copy(isHydrated = false, lastSynced = Instant.now()))
            assertTrue(
                Files.readAllBytes(syncFile).all { it == 0.toByte() },
                "precondition: the sync-root file is a zero-filled placeholder",
            )

            val cachePath = engine.ensureHydrated("/doc.txt")
            assertContentEquals(content, Files.readAllBytes(cachePath), "precondition: the cache copy was downloaded")

            // What the co-daemon's cache_scanner does on the next daemon start. The replay
            // re-uploads the identical cache bytes (the scanner cannot know); clear the fake's
            // record so the assertions below see only what the NEXT SYNC does.
            engine.uploadFromCache("/doc.txt", cachePath)
            provider.uploadedPaths.clear()

            assertContentEquals(
                content,
                Files.readAllBytes(syncFile),
                "#423: the replay propagated the uploaded bytes into the sync root, filling the placeholder",
            )
            val after = assertNotNull(db.getEntry("/doc.txt"))
            assertTrue(after.isHydrated, "the sync-root file now holds real bytes")
            assertEquals(mtimeOf(syncFile), after.localMtime, "the baseline is the filled sync-root file")
            assertEquals(Files.size(syncFile), after.localSize)

            assertTrue(LocalScanner(syncRoot, db).scan().isEmpty(), "the filled file matches its baseline — no local edit")
            engine.syncOnce()
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing is uploaded once converged, got: ${provider.uploadedPaths}")
            assertContentEquals(content, provider.files["/doc.txt"], "the remote content must be untouched")
        }
}
