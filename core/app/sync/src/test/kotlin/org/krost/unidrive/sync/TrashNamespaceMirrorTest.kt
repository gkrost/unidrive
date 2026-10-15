package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.ReadOnlyTrashProvider
import org.krost.unidrive.engine.TrashNamespace
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
 * The mirror engine and the trash namespace. Items the provider reports as trashed are shown under the root folder;
 * an item that an earlier version stored at its original place arrives there with the same id and is moved by the rename
 * machinery (a remote rename); and nothing is ever written to the cloud for an item in the namespace: not the edit the
 * user made before the item moved there, not a file created there, not a file deleted there.
 *
 * The planner drops those writes; the wrapper around the provider is the net under it. The tests run the engine on the
 * bare fake provider (so a write the planner let through would show in its records) and, once, behind the wrapper.
 */
class TrashNamespaceMirrorTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var trash: TrashNamespace
    private lateinit var engine: SyncEngine
    private val stamp = Instant.parse("2026-03-28T12:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-trash-mirror")
        db = StateDatabase(Files.createTempDirectory("unidrive-trash-mirror-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        trash = TrashNamespace(ignoreCase = false)
        engine = engineOver(provider)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engineOver(p: org.krost.unidrive.CloudProvider) =
        SyncEngine(
            provider = p,
            db = db,
            syncRoot = syncRoot,
            reporter = ProgressReporter.Silent,
            trashNamespace = trash,
        )

    private fun item(
        id: String,
        path: String,
        size: Long = 0,
        isFolder: Boolean = false,
        trashed: Boolean = false,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = stamp,
        created = stamp,
        hash = if (isFolder) null else "hash-$id",
        mimeType = if (isFolder) null else "application/octet-stream",
        trashed = trashed,
    )

    // What an earlier version stored: the live items at their original places, no folder for the trashed ones.
    private fun before() =
        listOf(
            item("id-a", "/Old/a.txt", 4),
            item("id-edit", "/Old/edit.txt", 5),
            item("id-b", "/Old/sub/b.txt", 6),
            item("id-livedir", "/Live", isFolder = true),
            item("id-live", "/Live/l.txt", 2),
        )

    // What the provider says now: the same ids, flagged, and the trashed folders in between.
    private fun now() =
        listOf(
            item("id-t1", "/Old", isFolder = true, trashed = true),
            item("id-t2", "/Old/sub", isFolder = true, trashed = true),
            item("id-a", "/Old/a.txt", 4, trashed = true),
            item("id-edit", "/Old/edit.txt", 5, trashed = true),
            item("id-b", "/Old/sub/b.txt", 6, trashed = true),
            item("id-livedir", "/Live", isFolder = true),
            item("id-live", "/Live/l.txt", 2),
        )

    private fun seedRemote() {
        provider.files["/Old/a.txt"] = "AAAA".toByteArray()
        provider.files["/Old/edit.txt"] = "EDIT0".toByteArray()
        provider.files["/Old/sub/b.txt"] = "BBBBBB".toByteArray()
        provider.files["/Live/l.txt"] = "LL".toByteArray()
    }

    private fun assertNothingSentToTheCloud(note: String) {
        assertEquals(emptyList(), provider.uploadAttempts, "no upload ($note)")
        assertEquals(emptyList(), provider.deletedPaths, "no delete ($note)")
        assertEquals(emptyList(), provider.movedPaths, "no move or rename ($note)")
        assertEquals(emptyList(), provider.createdFolders.toList(), "no folder ($note)")
    }

    @Test
    fun `the migration moves rows and files under the root, keeps the user's edit and sends nothing`() =
        runTest {
            seedRemote()
            provider.deltaItems = before()
            engine.syncOnce()
            assertEquals("AAAA", Files.readString(syncRoot.resolve("Old/a.txt")), "precondition: hydrated at the original place")

            // The user's edit that has not been uploaded yet, and a file they never touched.
            val edited = syncRoot.resolve("Old/edit.txt")
            Files.writeString(edited, "MY-UNSYNCED-EDIT")
            Files.setLastModifiedTime(edited, FileTime.from(Instant.now().plusSeconds(60)))

            provider.deltaItems = now()
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            // The untouched files moved with their bytes, the edited one with the user's bytes.
            assertEquals("AAAA", Files.readString(syncRoot.resolve("Papierkorb/Old/a.txt")))
            assertEquals("BBBBBB", Files.readString(syncRoot.resolve("Papierkorb/Old/sub/b.txt")))
            assertEquals("MY-UNSYNCED-EDIT", Files.readString(syncRoot.resolve("Papierkorb/Old/edit.txt")), "the edit is not lost")
            assertFalse(Files.exists(syncRoot.resolve("Old/a.txt")), "nothing is left under the old place")
            assertFalse(Files.exists(syncRoot.resolve("Old/edit.txt")))
            assertEquals("LL", Files.readString(syncRoot.resolve("Live/l.txt")), "a live file is where it was")

            // The rows moved with their ids: no delete and create.
            assertEquals("/Papierkorb/Old/a.txt", db.getEntryByRemoteId("id-a")?.path)
            assertEquals("/Papierkorb/Old/edit.txt", db.getEntryByRemoteId("id-edit")?.path)
            assertEquals("/Papierkorb/Old/sub/b.txt", db.getEntryByRemoteId("id-b")?.path)
            assertEquals(TrashNamespace.ROOT_ID, db.getEntry("/Papierkorb")?.remoteId)
            assertEquals(true, db.getEntry("/Papierkorb/Old/a.txt")?.isHydrated, "a moved file stays hydrated")
            assertNull(db.getEntry("/Old/a.txt"))
            assertNull(db.getEntry("/Old/edit.txt"))

            assertNothingSentToTheCloud("the pass that moved the items")
        }

    @Test
    fun `the edit made before the move is never uploaded, in this pass or any later one`() =
        runTest {
            seedRemote()
            provider.deltaItems = before()
            engine.syncOnce()
            val edited = syncRoot.resolve("Old/edit.txt")
            Files.writeString(edited, "MY-UNSYNCED-EDIT")
            Files.setLastModifiedTime(edited, FileTime.from(Instant.now().plusSeconds(60)))
            provider.deltaItems = now()
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            // Later passes: nothing new from the cloud, the edit still there.
            for (n in 3..5) {
                provider.deltaItems = emptyList()
                provider.deltaCursor = "cursor-$n"
                engine.syncOnce()
            }

            assertEquals("MY-UNSYNCED-EDIT", Files.readString(syncRoot.resolve("Papierkorb/Old/edit.txt")), "still the user's bytes")
            assertNull(provider.files["/Papierkorb/Old/edit.txt"], "and the cloud never got them")
            assertEquals("EDIT0", String(provider.files.getValue("/Old/edit.txt")), "the cloud copy is the one it was")
            assertNothingSentToTheCloud("later passes")
        }

    @Test
    fun `a file created, edited or deleted in the namespace is ignored`() =
        runTest {
            seedRemote()
            provider.deltaItems = before()
            engine.syncOnce()
            provider.deltaItems = now()
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()
            assertNothingSentToTheCloud("the migration")

            // The user, in the namespace: a new file, a new folder, an edit, a delete.
            Files.writeString(syncRoot.resolve("Papierkorb/new.txt"), "new")
            Files.createDirectories(syncRoot.resolve("Papierkorb/Old/newdir"))
            Files.writeString(syncRoot.resolve("Papierkorb/Old/newdir/x.txt"), "x")
            val a = syncRoot.resolve("Papierkorb/Old/a.txt")
            Files.writeString(a, "CHANGED")
            Files.setLastModifiedTime(a, FileTime.from(Instant.now().plusSeconds(60)))
            Files.delete(syncRoot.resolve("Papierkorb/Old/sub/b.txt"))
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()

            assertNothingSentToTheCloud("local changes in the namespace")
            assertEquals("CHANGED", Files.readString(a), "the local edit is left alone")
            assertNotNull(db.getEntryByRemoteId("id-b"), "a local delete does not remove the row either")
        }

    @Test
    fun `the same migration behind the wrapper runs clean`() =
        runTest {
            val guarded = engineOver(ReadOnlyTrashProvider(provider, trash))
            seedRemote()
            provider.deltaItems = before()
            guarded.syncOnce()
            val edited = syncRoot.resolve("Old/edit.txt")
            Files.writeString(edited, "MY-UNSYNCED-EDIT")
            Files.setLastModifiedTime(edited, FileTime.from(Instant.now().plusSeconds(60)))

            provider.deltaItems = now()
            provider.deltaCursor = "cursor-2"
            guarded.syncOnce()
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            guarded.syncOnce()

            assertEquals("MY-UNSYNCED-EDIT", Files.readString(syncRoot.resolve("Papierkorb/Old/edit.txt")))
            assertNothingSentToTheCloud("behind the wrapper")
        }

    @Test
    fun `a first scan puts the trashed items under the root`() =
        runTest {
            // The provider addresses a download by id; the fake keeps its bytes by the path the engine asks with.
            provider.files["/Papierkorb/Old/a.txt"] = "AAAA".toByteArray()
            provider.files["/Papierkorb/Old/sub/b.txt"] = "BBBBBB".toByteArray()
            provider.files["/Live/l.txt"] = "LL".toByteArray()
            provider.deltaItems = now()

            engine.syncOnce()

            assertEquals("AAAA", Files.readString(syncRoot.resolve("Papierkorb/Old/a.txt")))
            assertEquals("BBBBBB", Files.readString(syncRoot.resolve("Papierkorb/Old/sub/b.txt")))
            assertTrue(Files.isDirectory(syncRoot.resolve("Papierkorb/Old/sub")))
            assertFalse(Files.exists(syncRoot.resolve("Old")), "the trashed folder is not among the live ones")
            assertEquals("/Papierkorb", trash.rootPath())
            assertNothingSentToTheCloud("a first scan")
        }
}
