package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An incremental delta rewinds its cursor, so a poll hands the engine the same items again until they age out of the
 * window. Their folders are not in the page (unchanged), and each was asked for by `GET /folders/{uuid}/meta` on every
 * poll: 364 requests for 1029 items, every ~100 s. The engine already tracks those folders, so the pass takes them from
 * the rows state.db holds (ScanContext.trackedRow) and asks the remote only where the rows cannot be believed: a folder
 * the pass delivered under another parent than the one tracked, an item the engine has not seen under that parent, and
 * a folder the remote then says is gone.
 */
class InternxtTrackedAncestorsTest {
    private val cursor = "2026-07-28T19:30:00.000Z"

    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    private fun row(
        id: String,
        name: String,
        parent: String,
        isFolder: Boolean,
    ) = CloudItem(
        id = id,
        name = name,
        path = "/$name",
        size = 0,
        isFolder = isFolder,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
        parentId = parent,
    )

    private suspend fun delta(
        drive: FakeDrive,
        tracked: List<CloudItem>?,
    ): DeltaPage {
        val byId = tracked?.associateBy { it.id }
        val context = FakeStaging().context().copy(trackedRow = byId?.let { it::get })
        return internxtProviderOn(drive.engine).delta(cursor, null, context)
    }

    // 28 folders at the top, 12 below each: 364 folders that no listing returns (unchanged since 10:00), and 1029 files
    // below the 336 inner ones that changed after the cursor.
    private class Tree {
        val old = fakeAt("10:00:00")
        val top = (0 until 28).map { FakeFolder("g$it", "Group$it", "root", old) }
        val inner = (0 until 336).map { FakeFolder("s$it", "Sub$it", "g${it / 12}", old) }
        val files = (0 until 1029).map { FakeFile("f$it", "file$it", "s${it % 336}", fakeAt("19:40:00").plusSeconds(it.toLong())) }
        val folders = top + inner

        fun drive() = FakeDrive(emptyList(), files, pageSize = 5000, metaOnly = folders)

        fun tracked(): List<CloudItem> =
            folders.map { CloudItem(it.uuid, it.name, "/${it.name}", 0, true, null, null, null, null, parentId = it.parent) } +
                files.map { CloudItem(it.uuid, "${it.name}.txt", "/${it.name}.txt", 1, false, null, null, null, null, parentId = it.folder) }
    }

    @Test
    fun `a re-delivery of 1029 items whose 364 folders are all tracked asks the remote for none of them`() =
        runTest {
            val tree = Tree()

            val without = tree.drive()
            val before = delta(without, tracked = null)
            val with = tree.drive()
            val after = delta(with, tracked = tree.tracked())

            assertEquals(364, without.metaRequests.size, "the pass as it was: every folder above the items asked for")
            assertEquals(0, with.metaRequests.size, "every folder is a tracked one")
            assertEquals(1029, after.items.size)
            assertTrue(after.complete)
            assertEquals(paths(before), paths(after), "the same paths, from the rows as from the remote")
            assertTrue("/Group3/Sub40/file40.txt" in paths(after), paths(after).take(3).toString())
        }

    @Test
    fun `a folder delivered under another parent than the tracked one is asked for, with the parent it has now`() =
        runTest {
            val a = FakeFolder("A", "Old", "root", fakeAt("10:00:00"))
            val b = FakeFolder("B", "New", "root", fakeAt("10:00:00"))
            // F was moved from A to B: the pass delivers it, and its file, with the new parent.
            val moved = FakeFolder("F", "Moved", "B", fakeAt("19:40:00"))
            val drive = FakeDrive(listOf(moved), listOf(FakeFile("c", "inside", "F", fakeAt("19:41:00"))), pageSize = 100, metaOnly = listOf(a, b))
            val tracked =
                listOf(
                    row("A", "Old", "root", true),
                    row("B", "New", "root", true),
                    row("F", "Moved", "A", true),
                    row("c", "inside.txt", "F", false),
                )

            val page = delta(drive, tracked)

            assertEquals(listOf("B"), drive.metaRequests.toList(), "the tracked chain of F ends at A, the pass says B: B is not taken from the rows")
            assertEquals(setOf("/New/Moved", "/New/Moved/inside.txt"), paths(page), "the path follows the delivered parent")
            assertTrue(page.complete)
        }

    @Test
    fun `an item the engine has not seen below a folder gets that folder from the remote`() =
        runTest {
            val a = FakeFolder("A", "Docs", "root", fakeAt("10:00:00"))
            val drive =
                FakeDrive(
                    emptyList(),
                    listOf(FakeFile("seen", "old", "A", fakeAt("19:41:00")), FakeFile("fresh", "new", "A", fakeAt("19:42:00"))),
                    pageSize = 100,
                    metaOnly = listOf(a),
                )
            val tracked = listOf(row("A", "Docs", "root", true), row("seen", "old.txt", "A", false))

            val page = delta(drive, tracked)

            assertEquals(listOf("A"), drive.metaRequests.toList(), "the new file has no tracked row to vouch for its parent")
            assertEquals(setOf("/Docs/old.txt", "/Docs/new.txt"), paths(page))
        }

    @Test
    fun `a tracked folder that the remote says is gone stays gone and the items below it are unreachable`() =
        runTest {
            val x = FakeFolder("X", "Lost", "root", fakeAt("10:00:00"))
            val drive =
                FakeDrive(
                    emptyList(),
                    listOf(FakeFile("seen", "old", "X", fakeAt("19:41:00")), FakeFile("fresh", "new", "X", fakeAt("19:42:00"))),
                    pageSize = 100,
                    metaOnly = listOf(x),
                    metaGone = setOf("X"),
                )
            val tracked = listOf(row("X", "Lost", "root", true), row("seen", "old.txt", "X", false))

            val page = delta(drive, tracked)

            assertEquals(listOf("X"), drive.metaRequests.toList(), "asked once")
            assertEquals(emptySet(), paths(page), "the row of a folder the remote has no more does not place anything")
            assertEquals(setOf("seen", "fresh"), page.unreachableIds)
            assertTrue(page.complete)
        }

    @Test
    fun `a trashed tracked folder keeps the items below it trashed`() =
        runTest {
            val t = FakeFolder("T", "Binned", "root", fakeAt("10:00:00"), "TRASHED")
            val drive = FakeDrive(emptyList(), listOf(FakeFile("in", "kept", "T", fakeAt("19:41:00"))), pageSize = 100, metaOnly = listOf(t))
            val tracked = listOf(row("T", "Binned", "root", true).copy(trashed = true), row("in", "kept.txt", "T", false))

            val page = delta(drive, tracked)

            assertEquals(emptyList(), drive.metaRequests.toList())
            assertTrue(page.items.single { it.id == "in" }.trashed, "the row said trashed")
            assertTrue(page.items.single { it.id == "T" }.let { it.isFolder && it.trashed }, "and the folder of the trash is reported, as when it was fetched")
        }

    @Test
    fun `a tracked row that is not a folder, or has no parent to vouch for, is not used`() =
        runTest {
            val a = FakeFolder("A", "Docs", "root", fakeAt("10:00:00"))
            val drive = FakeDrive(emptyList(), listOf(FakeFile("f", "x", "A", fakeAt("19:41:00"))), pageSize = 100, metaOnly = listOf(a))
            val tracked = listOf(row("A", "Docs.txt", "root", false), row("f", "x.txt", "A", false))

            val page = delta(drive, tracked)

            assertEquals(listOf("A"), drive.metaRequests.toList())
            assertEquals(setOf("/Docs/x.txt"), paths(page))
        }
}
