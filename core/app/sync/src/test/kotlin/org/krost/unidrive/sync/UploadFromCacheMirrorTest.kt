package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #449, write side. Bytes written through the mount land in the hydration cache and are uploaded by
 * `uploadFromCache`. The row used to be recorded as synced while the sync root had no file, so a later plain
 * `unidrive sync` read the missing file as a local delete (#459). Once the provider has the bytes, the same
 * bytes are placed in the sync root (copy + rename) and the row's baseline is that file.
 */
class UploadFromCacheMirrorTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider
    private lateinit var engine: SyncEngine
    private lateinit var cacheRoot: Path

    private val bytes = "written through the mount".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-449w-root")
        cacheRoot = Files.createTempDirectory("ud-449w-cache")
        db = StateDatabase(Files.createTempDirectory("ud-449w-db").resolve("state.db"))
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
        engine = engineWith()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engineWith(
        exclude: List<String> = emptyList(),
        scope: List<String> = emptyList(),
        root: Path = syncRoot,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = root,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = ProgressReporter.Silent,
        cacheRoot = cacheRoot,
        excludePatterns = exclude,
        standingScope = scope,
        syncPaths = scope,
    )

    /** What the verbs do: the cache file holds the user's bytes, then the engine uploads it. */
    private suspend fun writeThroughMount(
        e: SyncEngine,
        path: String,
        content: ByteArray = bytes,
    ): Path {
        val cache = e.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.write(cache, content)
        // The row `hydration.create` makes: never uploaded, bytes only in the cache. (A row that was
        // synced earlier is kept as it is.)
        if (db.getEntry(path) == null) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = System.currentTimeMillis(),
                    localSize = 0L,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
        }
        e.uploadFromCache(path, cache)
        return cache
    }

    private suspend fun establishCursor() {
        engine.syncOnce()
        provider.deltaItems = emptyList()
        provider.deltaCursor = "cursor-2"
    }

    @Test
    fun `an uploaded mount write also lands in the sync root and a plain sync deletes nothing, even without the cache copy`() =
        runTest {
            establishCursor()
            val cache = writeThroughMount(engine, "/new.txt")
            assertContentEquals(bytes, provider.files["/new.txt"], "precondition: the provider has the bytes")

            val mirror = syncRoot.resolve("new.txt")
            assertTrue(Files.isRegularFile(mirror), "the sync root must hold the file")
            assertContentEquals(bytes, Files.readAllBytes(mirror))
            val row = assertNotNull(db.getEntry("/new.txt"))
            assertEquals(Files.getLastModifiedTime(mirror).toMillis(), row.localMtime, "the baseline is the sync-root file")
            assertEquals(bytes.size.toLong(), row.localSize)
            assertEquals(false, row.cacheBacked)
            assertNotNull(row.localHash, "hashless provider: the hash of the sent bytes is recorded (#396)")

            // The guard of #470 only protects rows whose cache copy exists; take that away.
            Files.delete(cache)
            provider.uploadedPaths.clear()
            engine.syncOnce()

            assertEquals(emptyList(), provider.deletedPaths, "a synced row with a file in the sync root is not a local delete")
            assertEquals(emptyList(), provider.uploadedPaths, "the mirror is the tracked version: nothing to upload")
            assertContentEquals(bytes, provider.files["/new.txt"])
        }

    @Test
    fun `parent directories are created and no temp file is left behind`() =
        runTest {
            establishCursor()
            writeThroughMount(engine, "/a/b/deep.txt")

            val mirror = syncRoot.resolve("a/b/deep.txt")
            assertContentEquals(bytes, Files.readAllBytes(mirror))
            Files.list(mirror.parent).use { stream ->
                assertEquals(listOf("deep.txt"), stream.map { it.fileName.toString() }.toList())
            }
        }

    // Review fix: the mirror used to survive the delete of its row. The next scan then
    // read the orphan file — no row, real bytes — as NEW and re-uploaded the path the
    // user had just deleted: the deleted file was resurrected in the cloud by its own
    // mirror.
    @Test
    fun `a mount unlink removes the mirror, the deleted path is not resurrected by the next sync`() =
        runTest {
            establishCursor()
            writeThroughMount(engine, "/gone.txt")
            assertTrue(Files.isRegularFile(syncRoot.resolve("gone.txt")), "precondition: the mirror exists")

            // What hydration.unlink issues for an uploaded file.
            engine.deleteRemote("/gone.txt")

            assertFalse(Files.exists(syncRoot.resolve("gone.txt")), "the mirror must not survive the delete")

            provider.uploadedPaths.clear()
            provider.deltaItems = emptyList()
            engine.syncOnce()

            assertEquals(listOf("/gone.txt"), provider.deletedPaths, "the delete reached the remote exactly once")
            assertEquals(emptyList(), provider.uploadedPaths, "the orphaned mirror must not be re-uploaded")
            assertNull(db.getEntry("/gone.txt"))
        }

    // Review fix: a mount rename moves the remote item, the row and the cache file, but
    // used to leave the mirror at the old path — an orphan the next scan uploaded under
    // the old name (the #319 resurrection shape, through the mirror).
    @Test
    fun `a mount rename moves the mirror, the old name is not resurrected by the next sync`() =
        runTest {
            establishCursor()
            writeThroughMount(engine, "/a.txt")
            assertTrue(Files.isRegularFile(syncRoot.resolve("a.txt")), "precondition: the mirror exists")

            // What hydration.rename issues for an uploaded file.
            engine.renameRemote("/a.txt", "/b.txt")

            assertFalse(Files.exists(syncRoot.resolve("a.txt")), "the old mirror path must be gone")
            assertContentEquals(bytes, Files.readAllBytes(syncRoot.resolve("b.txt")), "the mirror follows the rename")
            val row = assertNotNull(db.getEntry("/b.txt"))
            assertEquals(false, row.cacheBacked, "the renamed row's baseline is still the sync-root file")
            assertNull(db.getEntry("/a.txt"))

            provider.uploadedPaths.clear()
            provider.deltaItems = emptyList()
            engine.syncOnce()

            assertEquals(listOf("/a.txt" to "/b.txt"), provider.movedPaths, "the remote move is the rename")
            assertEquals(emptyList(), provider.uploadedPaths, "the stray old mirror must not be re-uploaded")
        }

    // Review fix: a folder whose row is deleted takes its EMPTY mirror directory along;
    // a directory holding files that belong to no deleted row is left alone.
    @Test
    fun `deleting a folder removes its empty mirror directory but never one holding other files`() =
        runTest {
            establishCursor()

            db.insertFolder("/d", "id-/d", Instant.now())
            Files.createDirectories(syncRoot.resolve("d"))
            engine.deleteRemote("/d")
            assertFalse(Files.exists(syncRoot.resolve("d")), "the empty mirror directory goes with the folder")

            db.insertFolder("/e", "id-/e", Instant.now())
            Files.createDirectories(syncRoot.resolve("e"))
            Files.writeString(syncRoot.resolve("e/untracked.txt"), "keep")
            engine.deleteRemote("/e")
            assertTrue(Files.isDirectory(syncRoot.resolve("e")), "a non-empty directory is not the folder's alone")
            assertContentEquals("keep".toByteArray(), Files.readAllBytes(syncRoot.resolve("e/untracked.txt")))
        }

    @Test
    fun `a sync-root file edited since the baseline is never overwritten, the next sync decides`() =
        runTest {
            provider.files["/doc.txt"] = "synced bytes".toByteArray()
            provider.deltaItems =
                listOf(
                    CloudItem(
                        id = "id-/doc.txt",
                        name = "doc.txt",
                        path = "/doc.txt",
                        size = 12,
                        isFolder = false,
                        modified = Instant.parse("2026-03-28T12:00:00Z"),
                        created = Instant.parse("2026-03-28T12:00:00Z"),
                        hash = "h",
                        mimeType = null,
                    ),
                )
            engine.syncOnce()
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            val syncFile = syncRoot.resolve("doc.txt")
            val edited = "edited in the sync root, not synced yet".toByteArray()
            Files.write(syncFile, edited)
            Files.setLastModifiedTime(syncFile, FileTime.from(Instant.parse("2026-04-01T08:00:00Z")))

            writeThroughMount(engine, "/doc.txt", "mount bytes".toByteArray())

            assertContentEquals(edited, Files.readAllBytes(syncFile), "the local edit must survive")
            assertTrue(
                Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.toList().none { it.startsWith(".ud-mirror") } },
                "no temp file left behind",
            )
            assertEquals(true, db.getEntry("/doc.txt")?.cacheBacked, "the row records the cache copy as its local file")
        }

    @Test
    fun `an excluded path is not mirrored`() =
        runTest {
            val excluding = engineWith(exclude = listOf("**/*.log"))
            writeThroughMount(excluding, "/noise.log")

            assertFalse(Files.exists(syncRoot.resolve("noise.log")), "keep-local files are neither uploaded nor mirrored")
            assertNull(provider.files["/noise.log"])
        }

    @Test
    fun `a path outside the standing scope is not mirrored`() =
        runTest {
            val scoped = engineWith(scope = listOf("/_INBOX"))
            writeThroughMount(scoped, "/elsewhere/f.txt")

            assertFalse(Files.exists(syncRoot.resolve("elsewhere")), "out of scope: nothing is written into the sync root")
        }

    @Test
    fun `a missing sync root is not created for a mount-only profile`() =
        runTest {
            val absent = syncRoot.resolve("never-created")
            val mountOnly = engineWith(root = absent)
            writeThroughMount(mountOnly, "/f.txt")

            assertFalse(Files.exists(absent), "the sync root must not appear because of a mount write")
            val row = assertNotNull(db.getEntry("/f.txt"))
            assertEquals(true, row.cacheBacked, "the cache copy stays the row's local file")
        }
}
