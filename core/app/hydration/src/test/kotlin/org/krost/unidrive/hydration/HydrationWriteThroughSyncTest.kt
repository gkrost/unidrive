package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #449 / #459, end to end through the hydration verbs on an isolated profile: bytes written through the
 * mount (`create`, write the cache file, `open_write`) are uploaded and also land in the sync root, so a
 * later plain `sync` finds a file for every synced row and plans no delete, even when the hydration cache
 * copy is gone (the #470 guard only protects rows whose cache copy exists).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationWriteThroughSyncTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
        val syncRoot: Path,
    )

    private fun freshEnv(): Env {
        val provider = MemProvider()
        val db = StateDatabase(Files.createTempDirectory("ud-449e-db").resolve("state.db"))
        db.initialize()
        val syncRoot = Files.createTempDirectory("ud-449e-root")
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                cacheRoot = Files.createTempDirectory("ud-449e-cache"),
                cacheKey = "ud-449-isolated-profile",
            )
        return Env(provider, db, engine, syncRoot)
    }

    @Test
    fun `a file written through the mount survives a plain sync, also without its cache copy`() =
        runTest {
            val env = freshEnv()
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            env.engine.syncOnce()

            val created = hydration.create("conn", "h-create", "/dropped.txt")
            assertTrue(created is CreateResult.Ok, "expected Ok, got $created")
            val bytes = "bytes made in Explorer".toByteArray()
            Files.write(created.cachePath, bytes)
            val opened = hydration.openForWrite("conn", "h-write", "/dropped.txt", created.cachePath, baseEtag = null)
            assertTrue(opened is OpenResult.Ok, "expected Ok, got $opened")
            hydration.closeHandle("conn", "h-write")
            hydration.closeHandle("conn", "h-create")
            advanceUntilIdle()
            assertContentEquals(bytes, env.provider.remote["/dropped.txt"], "precondition: the upload landed")

            val mirror = env.syncRoot.resolve("dropped.txt")
            assertContentEquals(bytes, Files.readAllBytes(mirror), "the sync root must hold the bytes written through the mount")
            val row = assertNotNull(env.db.getEntry("/dropped.txt"))
            assertEquals(false, row.cacheBacked, "the row's baseline is the sync-root file")
            val hydratedRowsWithoutFile =
                env.db.getAllEntries().filter { !it.isFolder && it.isHydrated }.filter { !Files.exists(env.syncRoot.resolve(it.path.trimStart('/'))) }
            assertEquals(emptyList(), hydratedRowsWithoutFile.map { it.path }, "what doctor's hydration-drift check counts must be empty")

            // The guard of #470 protects only rows whose cache copy exists: take the copy away.
            Files.delete(created.cachePath)
            env.engine.syncOnce()

            assertEquals(emptyList(), env.provider.deleted, "a plain sync after mount writes must not delete them")
            assertContentEquals(bytes, env.provider.remote["/dropped.txt"])
        }

    @Test
    fun `a deliberate delete in the sync root of a file only read through the mount propagates`() =
        runTest {
            val env = freshEnv()
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            // Three files, so that deleting one stays under the deletion safeguards (percentage thresholds).
            val at = Instant.parse("2026-03-28T12:00:00Z")
            for (name in listOf("doc.txt", "keep1.txt", "keep2.txt")) {
                val bytes = "content of $name".toByteArray()
                env.provider.remote["/$name"] = bytes
                env.provider.items["/$name"] =
                    CloudItem(
                        id = "id-/$name",
                        name = name,
                        path = "/$name",
                        size = bytes.size.toLong(),
                        isFolder = false,
                        modified = at,
                        created = at,
                        hash = "h-$name",
                        mimeType = null,
                    )
            }
            env.engine.syncOnce()
            val syncFile = env.syncRoot.resolve("doc.txt")
            assertTrue(Files.isRegularFile(syncFile), "precondition: the sync run put the file in the sync root")
            val downloadsBefore = env.provider.downloads.size

            val opened = hydration.openForRead("conn", "h-read", "/doc.txt")
            assertTrue(opened is OpenResult.Ok, "expected Ok, got $opened")
            hydration.closeHandle("conn", "h-read")
            assertEquals(downloadsBefore, env.provider.downloads.size, "the read was served from the sync-root copy")
            assertTrue(Files.isRegularFile(opened.cachePath), "precondition: the cache copy stays after the read (#450)")
            assertEquals(false, env.db.getEntry("/doc.txt")?.cacheBacked, "the baseline is still the sync-root file")

            Files.delete(syncFile)
            env.engine.syncOnce()

            assertEquals(listOf("/doc.txt"), env.provider.deleted, "the user deleted the sync-root file on purpose: the delete must reach the remote")
            assertContentEquals("content of keep1.txt".toByteArray(), env.provider.remote["/keep1.txt"])
        }

    /** create + write the cache + open_write, drained to upload and mirror. */
    private suspend fun TestScope.writeThroughMount(env: Env, hydration: Hydration, path: String): Path {
        val created = hydration.create("conn", "h-create", path)
        assertTrue(created is CreateResult.Ok, "expected Ok, got $created")
        Files.write(created.cachePath, "bytes of $path".toByteArray())
        assertTrue(hydration.openForWrite("conn", "h-write", path, created.cachePath, baseEtag = null) is OpenResult.Ok)
        advanceUntilIdle()
        hydration.closeHandle("conn", "h-write")
        hydration.closeHandle("conn", "h-create")
        return created.cachePath
    }

    // Review fix: the mirror used to survive the mount unlink. The next sync then read
    // the orphan file as NEW and re-uploaded the deleted path — the delete was undone
    // in the cloud by the file's own mirror.
    @Test
    fun `an unlink through the mount takes the mirror with it and is not resurrected`() =
        runTest {
            val env = freshEnv()
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            env.engine.syncOnce()
            writeThroughMount(env, hydration, "/doomed.txt")
            val mirror = env.syncRoot.resolve("doomed.txt")
            assertTrue(Files.isRegularFile(mirror), "precondition: the write was mirrored")

            assertTrue(hydration.unlink("/doomed.txt") is UnlinkResult.Ok)

            assertFalse(Files.exists(mirror), "the mirror must not survive the mount unlink")
            env.engine.syncOnce()

            assertEquals(listOf("/doomed.txt"), env.provider.deleted, "the delete reached the remote exactly once")
            assertNull(env.db.getEntry("/doomed.txt"))
        }

    // Review fix: the rename moved the remote item, the row and the cache file, but left
    // the mirror at the old path — the next sync re-uploaded the old name (resurrection).
    @Test
    fun `a rename through the mount moves the mirror`() =
        runTest {
            val env = freshEnv()
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            env.engine.syncOnce()
            writeThroughMount(env, hydration, "/a.txt")
            assertTrue(Files.isRegularFile(env.syncRoot.resolve("a.txt")), "precondition: the write was mirrored")

            assertEquals(RenameResult.Ok, hydration.rename("/a.txt", "/b.txt"))

            assertFalse(Files.exists(env.syncRoot.resolve("a.txt")), "the old mirror path must be gone")
            assertContentEquals(
                "bytes of /a.txt".toByteArray(),
                Files.readAllBytes(env.syncRoot.resolve("b.txt")),
                "the mirror follows the rename",
            )
            env.engine.syncOnce()

            assertNull(env.provider.remote["/a.txt"], "the old name must not be resurrected in the cloud")
            assertNotNull(env.provider.remote["/b.txt"])
        }
}
