package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A write made through the hydration cache (the mount) is uploaded by `uploadFromCache`. On a profile that
 * also has a populated sync root, the sync-root file still holds the older bytes. The row must not adopt the
 * cache copy's mtime and size as the baseline of THAT sync-root file (#427), or the next scan reads the
 * untouched sync-root file as modified and uploads the old content over the write.
 *
 * #423 decision: the sync root converges immediately — the uploaded bytes are propagated to the sync-root
 * file and the row is rebaselined against that copy — so the row never claims local A == remote B while the
 * sync root keeps stale bytes (which let a later sync-root edit upload over the newer remote content).
 */
class UploadFromCacheKeepsWriteTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val modified = Instant.parse("2026-03-28T12:00:00Z")
    private val oldBytes = "content A, what the sync root holds".toByteArray()
    private val newBytes = "content B, written through the mount, longer than A".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-423-root")
        val dbPath = Files.createTempDirectory("ud-423-db").resolve("state.db")
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
                cacheRoot = Files.createTempDirectory("ud-423-cache"),
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun remoteItem(size: Long) =
        CloudItem(
            id = "id-/f.txt",
            name = "f.txt",
            path = "/f.txt",
            size = size,
            isFolder = false,
            modified = modified,
            created = modified,
            hash = "hash-f",
            mimeType = null,
        )

    @Test
    fun `a write through the cache never overwrites an unsynced local edit in the sync root`() =
        runTest {
            provider.files["/f.txt"] = oldBytes
            provider.deltaItems = listOf(remoteItem(oldBytes.size.toLong()))
            engine.syncOnce()
            val syncFile = syncRoot.resolve("f.txt")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            // The user edits the file in the sync root; nobody has uploaded that edit yet.
            val localEdit = "local edit C, made in the sync root, not uploaded".toByteArray()
            Files.write(syncFile, localEdit)
            Files.setLastModifiedTime(syncFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))

            // Meanwhile a write through the mount reaches the remote.
            val cacheCopy = engine.resolveCachePath("/f.txt")
            Files.createDirectories(cacheCopy.parent)
            Files.write(cacheCopy, newBytes)
            engine.uploadFromCache("/f.txt", cacheCopy)

            assertContentEquals(localEdit, Files.readAllBytes(syncFile), "the unsynced local edit must survive the mirror")
            engine.syncOnce()
            val survivors = Files.walk(syncRoot).use { s -> s.filter { Files.isRegularFile(it) }.map { Files.readAllBytes(it).toList() }.toList() }
            assertTrue(survivors.any { it == localEdit.toList() }, "the local edit must still exist after the next sync (as f.txt or a conflict copy)")
        }

    // #568: the mirror was skipped because the sync root held an unsynced edit (the test above). Deleting the file
    // through the mount afterwards must not delete that edit with the remote item: it is the only copy of it.
    @Test
    fun `a delete through the mount keeps an unsynced edit the mirror skipped`() =
        runTest {
            provider.files["/f.txt"] = oldBytes
            provider.deltaItems = listOf(remoteItem(oldBytes.size.toLong()))
            engine.syncOnce()
            val syncFile = syncRoot.resolve("f.txt")
            val localEdit = "local edit C, made in the sync root, not uploaded".toByteArray()
            Files.write(syncFile, localEdit)
            Files.setLastModifiedTime(syncFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))
            val cacheCopy = engine.resolveCachePath("/f.txt")
            Files.createDirectories(cacheCopy.parent)
            Files.write(cacheCopy, newBytes)
            engine.uploadFromCache("/f.txt", cacheCopy)
            assertEquals(true, db.getEntry("/f.txt")?.cacheBacked, "precondition: the mirror was skipped")

            engine.deleteRemote("/f.txt")

            assertTrue(provider.deletedPaths.contains("/f.txt"), "the remote item is deleted")
            assertContentEquals(localEdit, Files.readAllBytes(syncFile), "the unsynced local edit survives the delete")
        }

    // #568: a mirrored file the user edited in the sync root after the mirror is no longer the copy the row describes.
    @Test
    fun `a delete through the mount keeps a mirrored file edited since`() =
        runTest {
            provider.files["/f.txt"] = oldBytes
            provider.deltaItems = listOf(remoteItem(oldBytes.size.toLong()))
            engine.syncOnce()
            val syncFile = syncRoot.resolve("f.txt")
            val cacheCopy = engine.resolveCachePath("/f.txt")
            Files.createDirectories(cacheCopy.parent)
            Files.write(cacheCopy, newBytes)
            engine.uploadFromCache("/f.txt", cacheCopy)
            assertEquals(false, db.getEntry("/f.txt")?.cacheBacked, "precondition: the mirror wrote the sync-root file")
            val laterEdit = "edited in the sync root after the mirror".toByteArray()
            Files.write(syncFile, laterEdit)
            Files.setLastModifiedTime(syncFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000))

            engine.deleteRemote("/f.txt")

            assertContentEquals(laterEdit, Files.readAllBytes(syncFile), "the edit made after the mirror survives the delete")
        }

    // #449 review fix, unchanged by #568: the untouched mirror copy goes with the delete, so the next scan does not
    // read an orphan file as new and bring the deleted path back.
    @Test
    fun `a delete through the mount removes the untouched mirror copy`() =
        runTest {
            provider.files["/f.txt"] = oldBytes
            provider.deltaItems = listOf(remoteItem(oldBytes.size.toLong()))
            engine.syncOnce()
            val syncFile = syncRoot.resolve("f.txt")
            val cacheCopy = engine.resolveCachePath("/f.txt")
            Files.createDirectories(cacheCopy.parent)
            Files.write(cacheCopy, newBytes)
            engine.uploadFromCache("/f.txt", cacheCopy)
            assertEquals(false, db.getEntry("/f.txt")?.cacheBacked, "precondition: the mirror wrote the sync-root file")

            engine.deleteRemote("/f.txt")

            assertTrue(Files.notExists(syncFile), "the mirror copy goes with the remote item")
        }

    @Test
    fun `a write through the cache is not reverted by the next sync`() =
        runTest {
            provider.files["/f.txt"] = oldBytes
            provider.deltaItems = listOf(remoteItem(oldBytes.size.toLong()))
            engine.syncOnce()
            val syncFile = syncRoot.resolve("f.txt")
            assertContentEquals(oldBytes, Files.readAllBytes(syncFile), "precondition: the sync root holds A")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            val cacheCopy = engine.resolveCachePath("/f.txt")
            Files.createDirectories(cacheCopy.parent)
            Files.write(cacheCopy, newBytes)
            engine.uploadFromCache("/f.txt", cacheCopy)
            assertContentEquals(newBytes, provider.files["/f.txt"], "precondition: the write reached the remote")
            assertContentEquals(
                newBytes,
                Files.readAllBytes(syncFile),
                "#423: the sync root must adopt the uploaded bytes instead of holding stale content",
            )
            val rowAfterUpload = assertNotNull(db.getEntry("/f.txt"))
            assertEquals(
                Files.getLastModifiedTime(syncFile).toMillis(),
                rowAfterUpload.localMtime,
                "the row baselines the sync-root copy it now describes",
            )
            assertEquals(Files.size(syncFile), rowAfterUpload.localSize)
            provider.uploadedPaths.clear()

            engine.syncOnce()

            assertContentEquals(newBytes, provider.files["/f.txt"], "the next sync must not put content A back on the remote")
            assertEquals(emptyList(), provider.uploadedPaths, "the sync root is converged, so nothing is uploaded")
            val row = assertNotNull(db.getEntry("/f.txt"))
            assertTrue(row.remoteSize == newBytes.size.toLong(), "the row describes the remote bytes, got ${row.remoteSize}")
        }
}
