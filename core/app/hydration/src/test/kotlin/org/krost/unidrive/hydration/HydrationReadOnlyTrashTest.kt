package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.TrashNamespace
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trash namespace as the mount serves it: the root folder and everything below it show what the provider keeps in
 * its trash. The listing says so (`read_only`, and `trash_root` for the root), so a client refuses the action before it
 * asks; every verb that would write answers `read_only` and changes nothing; reading, hydrating and copying out work as
 * everywhere else.
 */
class HydrationReadOnlyTrashTest {
    private fun trashedItem(path: String) =
        CloudItem("t-$path", path.substringAfterLast('/'), path, 1, false, null, null, null, null, trashed = true)

    // The namespace exists once the gather has placed a trashed item; its rows are the engine's own.
    private fun HydrationTestEnv.withTrash() {
        trash.place(listOf(trashedItem("/Old/a.txt"))) { emptyList() }
        stateDb.insertFolderEntry("/Papierkorb")
        stateDb.insertFolderEntry("/Papierkorb/Old")
        stateDb.insertUnhydratedEntry("/Papierkorb/Old/a.txt", 10)
        stateDb.insertHydratedEntry("/Papierkorb/Old/h.txt", 5)
        stateDb.insertUnhydratedEntry("/Live.txt", 3)
    }

    private fun tokenOf(result: Any?): String =
        when (result) {
            is OpenResult.Failed -> result.error.message
            is MkdirResult.Failed -> result.error.message
            is CreateResult.Failed -> result.error.message
            is RenameResult.Failed -> result.error.message
            is UnlinkResult.Failed -> result.error.message
            is RmdirResult.Failed -> result.error.message
            else -> error("did not fail: $result")
        }

    // ---- the listing says it -------------------------------------------------------------------------------------

    @Test
    fun `the listing marks the root and everything below it read-only, and the root as the root`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.withTrash()

            val top = assertIs<ListResult.Ok>(env.hydration.list("/")).entries.associateBy { it.path }
            val old = assertIs<ListResult.Ok>(env.hydration.list("/Papierkorb/Old")).entries.associateBy { it.path }

            assertTrue(top.getValue("/Papierkorb").readOnly && top.getValue("/Papierkorb").trashRoot)
            assertFalse(top.getValue("/Live.txt").readOnly, "a live item is not")
            assertFalse(top.getValue("/Live.txt").trashRoot)
            assertTrue(old.getValue("/Papierkorb/Old/a.txt").readOnly)
            assertTrue(old.getValue("/Papierkorb/Old/h.txt").readOnly)
            assertFalse(old.getValue("/Papierkorb/Old/a.txt").trashRoot, "only the root is the root")
        }

    @Test
    fun `without a trash the listing marks nothing, even a folder with the default name`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.stateDb.insertFolderEntry("/Papierkorb")

            val top = assertIs<ListResult.Ok>(env.hydration.list("/")).entries.single()

            assertFalse(top.readOnly)
            assertFalse(top.trashRoot)
        }

    // ---- the verbs that write ------------------------------------------------------------------------------------

    @Test
    fun `mkdir and create below the root are refused and write no row, no cache file and nothing to the provider`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.withTrash()

            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Papierkorb/Old/new")))
            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Papierkorb/Other")))
            assertEquals("read_only", tokenOf(env.hydration.create("c1", "h1", "/Papierkorb/Old/new.txt")))

            assertNull(env.stateDb.remoteSizeOf("/Papierkorb/Old/new"))
            assertNull(env.stateDb.remoteSizeOf("/Papierkorb/Old/new.txt"))
            assertFalse(Files.exists(env.syncEngine.resolveCachePath("/Papierkorb/Old/new.txt")))
            assertEquals(emptyList(), env.syncEngine.createdFolders())
        }

    @Test
    fun `unlink and rmdir are refused and the rows stay`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.withTrash()

            assertEquals("read_only", tokenOf(env.hydration.unlink("/Papierkorb/Old/a.txt")))
            assertEquals("read_only", tokenOf(env.hydration.unlink("/Papierkorb/Old/h.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rmdir("/Papierkorb/Old")))
            assertEquals("read_only", tokenOf(env.hydration.rmdir("/Papierkorb")))

            assertEquals(10L, env.stateDb.remoteSizeOf("/Papierkorb/Old/a.txt"))
            assertNotNull(env.stateDb.remoteSizeOf("/Papierkorb/Old"))
            assertEquals(emptyList(), env.syncEngine.deletedPaths(), "nothing was deleted in the cloud")
        }

    @Test
    fun `rename out of, into or within the namespace is refused and moves nothing`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.withTrash()

            assertEquals("read_only", tokenOf(env.hydration.rename("/Papierkorb/Old/a.txt", "/Out.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Live.txt", "/Papierkorb/Live.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Papierkorb/Old/a.txt", "/Papierkorb/Old/b.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Papierkorb/Old/a.txt", "/Papierkorb/Old/h.txt", replace = true)))

            assertEquals(emptyList(), env.syncEngine.movedPairs(), "nothing was moved in the cloud")
            assertEquals(emptyList(), env.syncEngine.deletedPaths(), "and a replace deleted nothing")
            assertEquals(10L, env.stateDb.remoteSizeOf("/Papierkorb/Old/a.txt"))
            assertEquals(5L, env.stateDb.remoteSizeOf("/Papierkorb/Old/h.txt"))
            assertEquals(3L, env.stateDb.remoteSizeOf("/Live.txt"))
        }

    @Test
    fun `a write handle on a file in the namespace is refused before an upload is queued, and the copy is kept`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.withTrash()
            env.syncEngine.seedCacheContent("/Papierkorb/Old/h.txt", "edited by the user")
            val cache = env.syncEngine.resolveCachePath("/Papierkorb/Old/h.txt")

            val begun = env.hydration.openWriteBegin("conn", "/Papierkorb/Old/h.txt", "h1")
            val written = env.hydration.openForWrite("conn", "h2", "/Papierkorb/Old/h.txt", cache)

            assertEquals("read_only", tokenOf(begun))
            assertEquals("read_only", tokenOf(written))
            assertEquals("edited by the user", Files.readString(cache), "a refusal is not a delete: the bytes stay where they are")
            assertEquals(0, env.syncEngine.uploadAttempts(), "no upload was queued")
        }

    // ---- what is not a write -------------------------------------------------------------------------------------

    @Test
    fun `an item in the namespace can be read and hydrated`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.withTrash()
            env.syncEngine.seedRemoteContent("/Papierkorb/Old/a.txt", "content of a")

            val hydrated = env.hydration.hydrate("/Papierkorb/Old/a.txt")
            val opened = env.hydration.openForRead("conn", "h1", "/Papierkorb/Old/a.txt")

            assertEquals(HydrateResult.Ok, hydrated)
            val ok = assertIs<OpenResult.Ok>(opened)
            assertEquals("content of a", Files.readString(ok.cachePath), "copying out works")
            assertEquals(emptyList(), env.syncEngine.deletedPaths())
        }

    @Test
    fun `a change in the cache copy of a file in the namespace is never uploaded`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.withTrash()
            env.syncEngine.seedCacheContent("/Papierkorb/Old/h.txt", "edited")
            val mount = MountEngine.over(env.engine)

            val outcome = mount.uploadFromCache("/Papierkorb/Old/h.txt", env.syncEngine.resolveCachePath("/Papierkorb/Old/h.txt"))

            assertFalse(outcome.sent, "the keep-local branch: nothing is sent")
            assertEquals(0, env.syncEngine.uploadAttempts())
            assertEquals("id-/Papierkorb/Old/h.txt", env.engine.mountWiring.db.getEntry("/Papierkorb/Old/h.txt")?.remoteId, "the row is the cloud's row still")
        }
}
