package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.*

/**
 * #396: for a provider with no remote content hash (Internxt), an mtime-only change of a
 * synced file made the next sync upload it again — a Windows shell handler bumped the mtime
 * of a just-downloaded .eml via an NTFS alternate stream, and LocalScanner had nothing to
 * compare the unchanged bytes against. The engine now records its own SHA-256 of the bytes
 * it wrote (download) or sent (upload) so the scanner can tell a touch from an edit.
 *
 * These tests drive the engine end to end with a fake hashless provider, plus a fake that
 * declares a hash algorithm (OneDrive-style) to pin that nothing is computed there.
 */
class SyncEngineLocalHashTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-396-root")
        val dbPath = Files.createTempDirectory("ud-396-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // The scanner captures provider.hashAlgorithm() at construction, so build the engine
    // only after a test has finished configuring the provider.
    private fun engine(cacheRoot: Path? = null) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            cacheRoot = cacheRoot,
        )

    private fun remoteFile(
        path: String,
        size: Long,
        id: String = "id-$path",
        hash: String? = null,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = null,
    )

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    /** Initial sync that downloads [name] with [content], leaving a synced row and a stored cursor. */
    private suspend fun downloadFile(
        eng: SyncEngine,
        name: String,
        content: ByteArray,
        hash: String? = null,
    ) {
        provider.files["/$name"] = content
        provider.deltaItems = listOf(remoteFile("/$name", content.size.toLong(), hash = hash))
        eng.syncOnce()
        assertTrue(Files.exists(syncRoot.resolve(name)), "precondition: $name was downloaded")
    }

    /** Second and later syncs: nothing new on the remote side. */
    private fun quietRemote(cursor: String) {
        provider.deltaItems = emptyList()
        provider.deltaCursor = cursor
    }

    // ---- what the engine records ----

    @Test
    fun `download on a hashless provider records the SHA-256 of the placed file`() =
        runTest {
            val content = ByteArray(2048) { ((it * 7) and 0xff).toByte() }
            downloadFile(engine(), "doc.bin", content)

            val row = db.getEntry("/doc.bin")
            assertNotNull(row)
            assertEquals(sha256(syncRoot.resolve("doc.bin")), row.localHash)
            assertNull(row.remoteHash, "the fake reports no remote hash, like Internxt")
        }

    @Test
    fun `upload on a hashless provider records the SHA-256 of the sent file`() =
        runTest {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            Files.writeString(syncRoot.resolve("local.txt"), "hello")

            eng.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/local.txt"))
            val row = db.getEntry("/local.txt")
            assertNotNull(row?.remoteId, "the pending row was promoted by the upload")
            assertEquals(sha256(syncRoot.resolve("local.txt")), row.localHash)
        }

    @Test
    fun `an edit-and-reupload replaces the recorded hash with the new content's`() =
        runTest {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            val file = syncRoot.resolve("mod.txt")
            Files.writeString(file, "v1")
            eng.syncOnce()
            val firstHash = db.getEntry("/mod.txt")?.localHash
            assertNotNull(firstHash)

            quietRemote("cursor-2")
            Files.writeString(file, "v2-longer-content-to-change-the-size")
            eng.syncOnce()

            val row = db.getEntry("/mod.txt")
            assertNotNull(row)
            assertNotEquals(firstHash, row.localHash)
            assertEquals(sha256(file), row.localHash)
        }

    @Test
    fun `a write-back through the cache records the hash of the bytes it just uploaded`() =
        runTest {
            // uploadFromCache is the FUSE write-back path: the bytes live in the daemon's
            // cache copy, and the row records exactly that copy's stats, so its hash must be
            // those bytes' hash. Leaving it null (or keeping a stale one) would strip the
            // touch shield from every mount-edited file.
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            // #319: the write-back requires the row the FUSE create flow wrote
            // (HydrationImpl.create) — a row-less upload is refused so it cannot
            // resurrect a vanished path. Seed the never-uploaded row here.
            seedLocalOnlyRow("/local.txt")
            val cacheCopy = Files.createTempDirectory("ud-396-wb").resolve("local.txt")
            val bytes = "written through the mount".toByteArray()
            Files.write(cacheCopy, bytes)

            eng.uploadFromCache("/local.txt", cacheCopy)

            val row = db.getEntry("/local.txt")
            assertNotNull(row?.remoteId, "the write-back was uploaded")
            assertEquals(sha256(cacheCopy), row.localHash)
        }

    // The row HydrationImpl.create writes for a file created through the mount:
    // never uploaded, cache holds the only copy.
    private fun seedLocalOnlyRow(path: String) {
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = Instant.now().toEpochMilli(),
                localSize = 0L,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun `a mount-mode re-download records the hash of the downloaded bytes instead of keeping the stale one`() =
        runTest {
            // ensureHydrated rewrites the row's local stats from the freshly downloaded cache
            // copy; keeping a hash recorded for the previous contents would pair stale bytes
            // with a fresh mtime/size — the stale-hash shape the touch check must never see.
            val cacheRoot = Files.createTempDirectory("ud-396-hyd-cache")
            val eng = engine(cacheRoot)
            val first = ByteArray(1024) { ((it * 3) and 0xff).toByte() }
            downloadFile(eng, "doc.bin", first)
            // Mount mode: the row is served from the daemon cache and nothing is in the sync
            // root (a row describing a sync-root file keeps its hash in ensureHydrated — the
            // sync-root bytes are unchanged, so the recorded hash still describes them).
            db.upsertEntry(assertNotNull(db.getEntry("/doc.bin")).copy(localMtime = null, localSize = null))
            Files.deleteIfExists(syncRoot.resolve("doc.bin"))
            val staleHash = assertNotNull(db.getEntry("/doc.bin")).localHash
            assertNotNull(staleHash)

            val second = "fresh remote bytes for the re-download".toByteArray()
            provider.files["/doc.bin"] = second
            val cachePath = eng.resolveCachePath("/doc.bin")
            Files.deleteIfExists(cachePath)
            eng.ensureHydrated("/doc.bin")

            val row = assertNotNull(db.getEntry("/doc.bin"))
            assertEquals(sha256(cachePath), row.localHash)
            assertNotEquals(staleHash, row.localHash)
        }

    // The callers hand in a row that already carries the new mtime/size; if hashing then fails,
    // keeping the previous contents' hash would pair stale bytes with fresh stats and let a
    // later touch of a changed file be absorbed as unchanged.

    private fun rowWithStaleHash(file: Path) =
        SyncEntry(
            path = "/stale.bin",
            remoteId = "id-/stale.bin",
            remoteHash = null,
            remoteSize = Files.size(file),
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = Files.getLastModifiedTime(file).toMillis(),
            localSize = Files.size(file),
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.now(),
            localHash = "hash-of-the-previous-contents",
        )

    @Test
    fun `a local hash that cannot be read drops the previous contents' hash`() {
        val file = Files.createTempDirectory("ud-396-fail").resolve("stale.bin")
        Files.write(file, "new bytes".toByteArray())
        val row = rowWithStaleHash(file)
        Files.delete(file)

        val result = engine().withLocalHash(row, file, row.localMtime!!, row.localSize!!)

        assertNull(result.localHash)
    }

    @Test
    fun `a file that changes while it is hashed drops the previous contents' hash`() {
        val file = Files.createTempDirectory("ud-396-race").resolve("stale.bin")
        Files.write(file, "new bytes".toByteArray())
        val row = rowWithStaleHash(file)

        // The recorded mtime no longer matches the file: a writer landed during the hash.
        val result = engine().withLocalHash(row, file, row.localMtime!! - 1_000L, row.localSize!!)

        assertNull(result.localHash)
    }

    // #337: an upload row takes the file's stats after the transfer, so an edit that lands
    // mid-upload is already in the baseline. Its hash must not be recorded too, or a later
    // touch would be absorbed as unchanged and the edit would never reach the remote.

    private fun editDuringUpload(edited: ByteArray) {
        provider.duringUpload = { path ->
            Files.write(path, edited)
            Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 60_000L))
            provider.duringUpload = null
        }
    }

    @Test
    fun `an edit that lands during an upload is sent once the file is touched`() =
        runTest {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            val file = syncRoot.resolve("draft.txt")
            Files.writeString(file, "first version")
            val edited = "edited version".toByteArray() // same size as the first
            editDuringUpload(edited)
            eng.syncOnce()
            assertNull(db.getEntry("/draft.txt")?.localHash, "the uploaded bytes are not the ones on disk")

            quietRemote("cursor-2")
            Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 60_000L))
            eng.syncOnce()

            assertContentEquals(edited, provider.files["/draft.txt"], "the touch must upload the edit")
        }

    @Test
    fun `a write-back whose cache copy changes during the upload records no hash`() =
        runTest {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            // #319: the write-back requires the row the FUSE create flow wrote.
            seedLocalOnlyRow("/local.txt")
            val cacheCopy = Files.createTempDirectory("ud-396-wb-race").resolve("local.txt")
            Files.writeString(cacheCopy, "first version")
            editDuringUpload("edited version".toByteArray())

            eng.uploadFromCache("/local.txt", cacheCopy)

            val row = assertNotNull(db.getEntry("/local.txt"))
            assertNotNull(row.remoteId, "the write-back was uploaded")
            assertNull(row.localHash)
        }

    @Test
    fun `a provider with a remote hash algorithm never gets a local hash`() =
        runTest {
            provider.hashAlgorithmOverride = HashAlgorithm.QuickXor
            val eng = engine()
            downloadFile(eng, "down.bin", ByteArray(64) { it.toByte() }, hash = "remote-quickxor")
            quietRemote("cursor-2")
            Files.writeString(syncRoot.resolve("up.txt"), "hello")
            eng.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/up.txt"), "precondition: the upload happened")
            assertNull(db.getEntry("/down.bin")?.localHash, "download row: no local hash for a hash-capable provider")
            assertNull(db.getEntry("/up.txt")?.localHash, "upload row: no local hash for a hash-capable provider")
        }

    // ---- the bug itself, end to end ----

    @Test
    fun `a downloaded file whose mtime is bumped without a content change is not uploaded again`() =
        runTest {
            val eng = engine()
            downloadFile(eng, "mail.eml", "From: someone\r\nSubject: hi\r\n\r\nbody".toByteArray())
            val file = syncRoot.resolve("mail.eml")
            val recordedMtime = db.getEntry("/mail.eml")?.localMtime
            assertNotNull(recordedMtime)

            // What the Windows property handler did: LastWriteTime jumps to "now", size and bytes stay.
            quietRemote("cursor-2")
            Files.setLastModifiedTime(file, FileTime.fromMillis(recordedMtime + 6 * 3_600_000L))
            eng.syncOnce()

            assertTrue(provider.uploadedPaths.isEmpty(), "an mtime-only change must not become a redundant upload")
            val row = db.getEntry("/mail.eml")
            assertNotNull(row)
            assertEquals(Files.getLastModifiedTime(file).toMillis(), row.localMtime, "tracked mtime follows the touch")
            assertEquals(sha256(file), row.localHash)
        }

    @Test
    fun `a downloaded file edited to the same size with a new mtime is still uploaded`() =
        runTest {
            val eng = engine()
            downloadFile(eng, "note.txt", "aaaa".toByteArray())
            val file = syncRoot.resolve("note.txt")
            val recordedMtime = db.getEntry("/note.txt")?.localMtime
            assertNotNull(recordedMtime)

            quietRemote("cursor-2")
            Files.writeString(file, "bbbb") // same size, one real edit
            Files.setLastModifiedTime(file, FileTime.fromMillis(recordedMtime + 6 * 3_600_000L))
            eng.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/note.txt"), "a real edit must still be uploaded")
            assertEquals(sha256(file), db.getEntry("/note.txt")?.localHash)
        }

    // ---- rows that are moved keep the hash of the (unchanged) bytes ----

    @Test
    fun `a local rename carried to the remote keeps the recorded hash`() =
        runTest {
            val eng = engine()
            downloadFile(eng, "old.txt", "rename me".toByteArray())
            val expected = db.getEntry("/old.txt")?.localHash
            assertNotNull(expected)

            quietRemote("cursor-2")
            Files.move(syncRoot.resolve("old.txt"), syncRoot.resolve("new.txt"))
            eng.syncOnce()

            assertEquals(listOf("/old.txt" to "/new.txt"), provider.movedPaths)
            assertNull(db.getEntry("/old.txt"))
            assertEquals(expected, db.getEntry("/new.txt")?.localHash)
        }

    @Test
    fun `after a remote rename the renamed file ends up with the hash of its own bytes and is not uploaded`() =
        runTest {
            val eng = engine()
            val content = "rename me remotely".toByteArray()
            downloadFile(eng, "before.txt", content)

            // Internxt reports a rename as the same id at a new path.
            provider.files["/after.txt"] = content
            provider.deltaItems = listOf(remoteFile("/after.txt", size = content.size.toLong(), id = "id-/before.txt"))
            provider.deltaCursor = "cursor-2"
            eng.syncOnce()
            quietRemote("cursor-3")
            eng.syncOnce()

            val renamed = syncRoot.resolve("after.txt")
            assertTrue(Files.exists(renamed))
            assertFalse(Files.exists(syncRoot.resolve("before.txt")))
            assertContentEquals(content, Files.readAllBytes(renamed))
            assertEquals(sha256(renamed), db.getEntry("/after.txt")?.localHash)
            assertTrue(provider.uploadedPaths.isEmpty(), "a remote rename must never turn into an upload")
        }
}
