package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which items of a listing the provider reports as trashed.
 *
 * Internxt trashes a folder and leaves what is in it as it was: the items below keep reporting `EXISTS`, name the
 * trashed folder as their parent, and a listing of the live items carries them at the path of their trashed
 * ancestors. A first scan of the account this was measured on listed 5,536 files and 266 folders that way, as if they
 * were live. The provider now says what they are: [CloudItem.trashed] on every live item below a folder that is not
 * live, at the path of the provider's own tree, and the folders in between as trashed folders, so that the engine can
 * show the way to them. A folder is judged by its `status`, whether the listing returned it or its meta did (which
 * answers HTTP 200 for a trashed folder, like for a live one).
 */
class InternxtTrashedItemsTest {
    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    private fun DeltaPage.item(id: String): CloudItem = items.single { it.id == id }

    private suspend fun fullListing(
        drive: FakeDrive,
        walled: Boolean,
    ): DeltaPage {
        val staging = FakeStaging()
        if (walled) assertFailsWith<InternxtApiException> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
        return internxtProviderOn(drive.engine).delta(null, null, staging.context())
    }

    // The same account whether the trashed folders come with the walk without the status filter or from their meta.
    private fun drive(walled: Boolean) = FakeDrive(fakeAccountFolders(), fakeAccountFiles(), wall = if (walled) "a2" else null)

    @Test
    fun `a live item below a trashed folder is marked trashed and keeps the path of the provider's tree`() =
        runTest {
            for (walled in listOf(true, false)) {
                val page = fullListing(drive(walled), walled)

                assertTrue(page.complete, "walled=$walled")
                val trashed = page.items.filter { it.trashed && !it.isFolder }.map { it.path }.toSet()
                assertEquals(setOf("/OldStuff/note.txt", "/OldStuff/Deep/Kept/keep.txt"), trashed, "walled=$walled")
                assertTrue(page.item("L2").trashed, "a live folder below a trashed one is trashed too")
                assertEquals("/OldStuff/Deep/Kept", page.item("L2").path)
                for (id in listOf("a1", "a2", "f1")) assertFalse(page.item(id).trashed, "$id is not below anything trashed")
                assertTrue(page.items.none { it.deleted }, "a trashed item is not a deleted one")
            }
        }

    @Test
    fun `the trashed folders that a live item hangs below are reported as trashed folders, the others are not`() =
        runTest {
            for (walled in listOf(true, false)) {
                val page = fullListing(drive(walled), walled)

                for (id in listOf("T1", "T2")) {
                    val folder = page.item(id)
                    assertTrue(folder.isFolder && folder.trashed && !folder.deleted, "$id: $folder (walled=$walled)")
                }
                assertEquals("/OldStuff", page.item("T1").path)
                assertEquals("/OldStuff/Deep", page.item("T2").path)
                assertTrue(page.items.none { it.id == "D1" }, "nothing live is below the deleted folder: it is no folder of the trash")
            }
        }

    @Test
    fun `the walk without the status filter needs no request for it, the filtered listing asks for each folder once`() =
        runTest {
            val walled = drive(true)
            fullListing(walled, true)
            assertEquals(emptyList(), walled.metaRequests.toList())

            val filtered = drive(false)
            fullListing(filtered, false)
            assertEquals(listOf("T1", "T2"), filtered.metaRequests.sorted(), "each once, T2 hangs below T1 and T1 below the root")
        }

    @Test
    fun `an ancestor that only its meta knows is judged by its status`() =
        runTest {
            val live = FakeFolder("X", "Live", "root", fakeAt("19:00:00"))
            val trashed = FakeFolder("Y", "Binned", "root", fakeAt("19:01:00"), "TRASHED")
            val deleted = FakeFolder("Z", "Removed", "root", fakeAt("19:02:00"), "DELETED")
            val drive =
                FakeDrive(
                    folders = listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00"))),
                    files =
                        listOf(
                            FakeFile("fx", "inlive", "X", fakeAt("19:10:00")),
                            FakeFile("fy", "intrash", "Y", fakeAt("19:11:00")),
                            FakeFile("fz", "inremoved", "Z", fakeAt("19:12:00")),
                        ),
                    pageSize = 100,
                    metaOnly = listOf(live, trashed, deleted),
                )

            val page = internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context())

            assertFalse(page.item("fx").trashed, "a folder whose meta says EXISTS is live")
            assertTrue(page.item("fy").trashed, "TRASHED, answered with HTTP 200")
            assertTrue(page.item("fz").trashed, "a folder that is not live is not live, whatever the word for it")
            assertEquals(setOf("Y", "Z"), page.items.filter { it.isFolder && it.trashed }.map { it.id }.toSet(), "only the folders in the trash")
            assertEquals(setOf("fx", "fy", "fz", "a1", "Y", "Z"), page.items.map { it.id }.toSet())
        }

    @Test
    fun `an account with nothing in the trash reports nothing trashed`() =
        runTest {
            val folders = fakeAccountFolders().filter { it.status == "EXISTS" && it.uuid != "L2" }
            val files = fakeAccountFiles().filter { it.uuid == "f1" }
            val page = internxtProviderOn(FakeDrive(folders, files, pageSize = 100).engine).delta(null, null, FakeStaging().context())

            assertTrue(page.items.none { it.trashed })
            assertEquals(setOf("/Docs", "/Pics", "/Docs/readme.txt"), paths(page))
        }

    // ---- an incremental delta ------------------------------------------------------------------------------------

    @Test
    fun `in an incremental delta a trashed folder stays a deletion and the live items below it are trashed`() =
        runTest {
            val drive =
                FakeDrive(
                    folders = listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:00:00")), FakeFolder("T1", "OldStuff", "root", fakeAt("19:40:00"), "TRASHED")),
                    files =
                        listOf(
                            FakeFile("f1", "readme", "a1", fakeAt("19:41:00")),
                            FakeFile("f2", "note", "T1", fakeAt("19:42:00")),
                        ),
                    pageSize = 100,
                )

            val page = internxtProviderOn(drive.engine).delta("2026-07-28T19:30:00.000Z", null, null)

            val tombstone = page.item("T1")
            assertTrue(tombstone.deleted && !tombstone.trashed, "a folder trashed since the cursor is gone from the live view: $tombstone")
            assertEquals(1, page.items.count { it.id == "T1" }, "and it is not repeated as a folder of the trash")
            assertTrue(page.item("f2").trashed && !page.item("f2").deleted)
            assertFalse(page.item("f1").trashed)
            assertEquals(listOf("a1"), drive.metaRequests.toList(), "the unchanged live folder is asked for, T1 came with the page")
        }

    @Test
    fun `in an incremental delta a trashed file stays a deletion`() =
        runTest {
            val drive =
                FakeDrive(
                    folders = listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:00:00"))),
                    files = listOf(FakeFile("f9", "gone", "a1", fakeAt("19:41:00"), "TRASHED")),
                    pageSize = 100,
                )

            val page = internxtProviderOn(drive.engine).delta("2026-07-28T19:30:00.000Z", null, null)

            val tombstone = page.item("f9")
            assertTrue(tombstone.deleted && !tombstone.trashed, "a file trashed in a live folder is gone from the live view: $tombstone")
        }

    // ---- the engine reads the flag, not the path -------------------------------------------------------------------

    @Test
    fun `a trashed item carries no sign in its path or its name`() =
        runTest {
            val page = fullListing(drive(false), false)

            for (item in page.items.filter { it.trashed }) {
                assertEquals(item.path.substringAfterLast('/'), item.name, "the name is the item's own: ${item.path}")
                assertTrue(item.path.startsWith("/OldStuff"), "the original place: ${item.path}")
            }
        }
}
