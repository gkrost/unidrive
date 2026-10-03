package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InternxtScopedInventoryTest {
    private val log = LoggerFactory.getLogger(InternxtScopedInventoryTest::class.java)

    private fun file(
        uuid: String,
        name: String,
    ) = InternxtFile(uuid = uuid, plainName = name)

    private fun folder(
        uuid: String,
        name: String,
    ) = InternxtFolder(uuid = uuid, plainName = name)

    // drive
    //   _INBOX/        a.txt, sub/ (deep.txt)
    //   _INBOXX/       b.txt
    //   other/         c.txt, many/ (m1.txt)
    //   top.txt
    private val tree =
        mapOf(
            "root" to FolderContentResponse(listOf(folder("inbox", "_INBOX"), folder("inboxx", "_INBOXX"), folder("other", "other")), listOf(file("top", "top.txt"))),
            "inbox" to FolderContentResponse(listOf(folder("sub", "sub")), listOf(file("fa", "a.txt"))),
            "sub" to FolderContentResponse(emptyList(), listOf(file("fd", "deep.txt"))),
            "inboxx" to FolderContentResponse(emptyList(), listOf(file("fb", "b.txt"))),
            "other" to FolderContentResponse(listOf(folder("many", "many")), listOf(file("fc", "c.txt"))),
            "many" to FolderContentResponse(emptyList(), listOf(file("fm", "m1.txt"))),
        )

    private suspend fun run(
        roots: List<String>,
        listed: MutableList<String> = mutableListOf(),
        contents: Map<String, FolderContentResponse> = tree,
        skipped: AtomicInteger = AtomicInteger(0),
        scanned: AtomicInteger = AtomicInteger(0),
        onProgress: ((ScanProgress) -> Unit)? = null,
    ) = InternxtProvider.collectScopedInventoryImpl(
        getContents = { uuid ->
            synchronized(listed) { listed += uuid }
            contents[uuid] ?: error("unexpected uuid $uuid")
        },
        driveRootUuid = "root",
        scopeRoots = roots,
        scanned = scanned,
        skipped = skipped,
        log = log,
        onProgress = onProgress,
    )

    private fun paths(inv: ScopedInventory): Set<String> {
        val folderMap = inv.folders.associateBy { it.uuid }
        val folderPaths = inv.folders.map { InternxtProvider.buildFolderPath(it.uuid, folderMap, "root")!! }
        val filePaths =
            inv.files.map {
                val parent = InternxtProvider.buildFolderPath(it.folderUuid!!, folderMap, "root")!!
                parent + "/" + it.plainName
            }
        return (folderPaths + filePaths).toSet()
    }

    @Test
    fun `walks only the scope root and lists nothing outside it`() =
        runTest {
            val listed = mutableListOf<String>()

            val inv = run(listOf("/_INBOX"), listed)

            assertEquals(setOf("/_INBOX", "/_INBOX/sub", "/_INBOX/a.txt", "/_INBOX/sub/deep.txt"), paths(inv))
            assertEquals(setOf("root", "inbox", "sub"), listed.toSet(), "folders outside the scope must never be listed")
        }

    @Test
    fun `a sibling that shares the scope prefix is not entered`() =
        runTest {
            val listed = mutableListOf<String>()
            run(listOf("/_INBOX"), listed)
            assertTrue("inboxx" !in listed)
        }

    @Test
    fun `folders leading to a nested root are emitted without their other files`() =
        runTest {
            val inv = run(listOf("/other/many"))

            assertEquals(setOf("/other", "/other/many", "/other/many/m1.txt"), paths(inv))
        }

    @Test
    fun `several roots are gathered and shared ancestors are emitted once`() =
        runTest {
            val inv = run(listOf("/_INBOX", "/other/many"))

            assertEquals(
                setOf("/_INBOX", "/_INBOX/sub", "/_INBOX/a.txt", "/_INBOX/sub/deep.txt", "/other", "/other/many", "/other/many/m1.txt"),
                paths(inv),
            )
            assertEquals(inv.folders.size, inv.folders.map { it.uuid }.toSet().size)
        }

    @Test
    fun `a missing scope root fails the gather`() =
        runTest {
            val e = assertFailsWith<ProviderException> { run(listOf("/_INBOX/nope")) }
            assertTrue("/_INBOX/nope" in e.message!! && "nope" in e.message!!)
        }

    @Test
    fun `a trashed folder with the scope name is not matched`() =
        runTest {
            val trashed = InternxtFolder(uuid = "old", plainName = "_INBOX", status = "TRASHED")
            val contents = tree + ("root" to FolderContentResponse(listOf(trashed, folder("inbox", "_INBOX")), emptyList()))

            val inv = run(listOf("/_INBOX"), contents = contents)

            assertTrue(inv.folders.none { it.uuid == "old" })
            assertTrue("/_INBOX/a.txt" in paths(inv))
        }

    @Test
    fun `a 503 inside the subtree is counted and the rest is kept`() =
        runTest {
            val skipped = AtomicInteger(0)
            val scanned = AtomicInteger(0)
            val inv =
                InternxtProvider.collectScopedInventoryImpl(
                    getContents = { uuid ->
                        if (uuid == "sub") throw InternxtApiException("unavailable", 503)
                        tree[uuid] ?: error("unexpected uuid $uuid")
                    },
                    driveRootUuid = "root",
                    scopeRoots = listOf("/_INBOX"),
                    scanned = scanned,
                    skipped = skipped,
                    log = log,
                )

            assertEquals(1, skipped.get())
            assertTrue("/_INBOX/a.txt" in paths(inv))
            assertTrue("/_INBOX/sub/deep.txt" !in paths(inv))
        }

    @Test
    fun `a failure while resolving a scope root propagates instead of being skipped`() =
        runTest {
            assertFailsWith<InternxtApiException> {
                InternxtProvider.collectScopedInventoryImpl(
                    getContents = { throw InternxtApiException("unavailable", 503) },
                    driveRootUuid = "root",
                    scopeRoots = listOf("/_INBOX"),
                    scanned = AtomicInteger(0),
                    skipped = AtomicInteger(0),
                    log = log,
                )
            }
        }

    @Test
    fun `listing count is bounded by the subtree, not the drive`() =
        runTest {
            val big = tree.toMutableMap()
            val extra = (1..500).map { folder("x$it", "x$it") }
            big["root"] = FolderContentResponse(tree["root"]!!.children + extra, emptyList())
            extra.forEach { big[it.uuid] = FolderContentResponse(emptyList(), listOf(file("f${it.uuid}", "f.txt"))) }
            val listed = mutableListOf<String>()

            run(listOf("/_INBOX"), listed, contents = big)

            assertEquals(3, listed.size, "root + _INBOX + sub only, however many other folders exist")
        }

    @Test
    fun `scoped delta page carries resolved paths, the start-time cursor, and is complete`() =
        runTest {
            val inv = run(listOf("/_INBOX"))

            val page = InternxtProvider.scopedDeltaPage(inv, "root", "2026-09-29T10:00:00Z", skipped = 0)

            assertEquals(
                setOf("/_INBOX", "/_INBOX/sub", "/_INBOX/a.txt", "/_INBOX/sub/deep.txt"),
                page.items.map { it.path }.toSet(),
            )
            assertEquals("2026-09-29T10:00:00Z", page.cursor)
            assertTrue(page.complete)
            assertTrue(!page.hasMore)
        }

    @Test
    fun `scoped delta page is incomplete when a subtree was skipped`() =
        runTest {
            val page = InternxtProvider.scopedDeltaPage(run(listOf("/_INBOX")), "root", "c", skipped = 1)
            assertTrue(!page.complete)
        }

    @Test
    fun `scoped delta page drops items with unresolvable ancestors and flags the page`() {
        val orphan = InternxtFile(uuid = "o", plainName = "o.txt", folderUuid = "not-in-inventory")
        val inv = ScopedInventory(listOf(orphan), listOf(folder("inbox", "_INBOX").copy(parentUuid = "root")))

        val page = InternxtProvider.scopedDeltaPage(inv, "root", "c", skipped = 0)

        assertEquals(listOf("/_INBOX"), page.items.map { it.path })
        assertTrue(!page.complete)
    }

    // ---- the progress the walk reports to the engine's status ------------------------------------------------------------

    @Test
    fun `the walk reports its folders as they are listed and ends with every folder counted`() =
        runTest {
            val reports = mutableListOf<ScanProgress>()

            run(listOf("/"), onProgress = { reports += it })

            assertEquals(
                ScanProgress(items = 0, foldersDone = 0, foldersKnown = 0, foldersSkipped = 0, listing = ScanProgress.LISTING_TREE),
                reports.first(),
                "the walk is named before the first folder is back",
            )
            assertEquals(
                ScanProgress(items = 11, foldersDone = 6, foldersKnown = 6, foldersSkipped = 0, listing = ScanProgress.LISTING_TREE),
                reports.last(),
                "root, _INBOX, _INBOXX, other, sub and many listed; 6 files and 5 folders found",
            )
            assertEquals(7, reports.size, "the start and one report per folder")
            assertTrue(
                reports.zipWithNext().all { (a, b) ->
                    b.foldersDone!! >= a.foldersDone!! && b.foldersKnown!! >= a.foldersKnown!! && b.items >= a.items
                },
                "the counts only grow: $reports",
            )
            // A folder counts as known before its parent counts as done, so the queue is never empty while the walk goes on.
            assertTrue(reports.drop(1).dropLast(1).all { it.foldersKnown!! > it.foldersDone!! }, "$reports")
        }

    @Test
    fun `a folder that cannot be listed counts as done and as skipped`() =
        runTest {
            val reports = mutableListOf<ScanProgress>()

            InternxtProvider.collectScopedInventoryImpl(
                getContents = { uuid ->
                    if (uuid == "sub") throw InternxtApiException("unavailable", 503)
                    tree[uuid] ?: error("unexpected uuid $uuid")
                },
                driveRootUuid = "root",
                scopeRoots = listOf("/_INBOX"),
                scanned = AtomicInteger(0),
                skipped = AtomicInteger(0),
                log = log,
                onProgress = { reports += it },
            )

            assertEquals(
                ScanProgress(items = 3, foldersDone = 2, foldersKnown = 2, foldersSkipped = 1, listing = ScanProgress.LISTING_TREE),
                reports.last(),
                "_INBOX listed, sub skipped; a.txt, _INBOX and sub found, deep.txt never listed",
            )
        }

    @Test
    fun `the folders that lead to a nested scope root are not counted as folders of the walk`() =
        runTest {
            val reports = mutableListOf<ScanProgress>()

            run(listOf("/other/many"), onProgress = { reports += it })

            assertEquals(
                ScanProgress(items = 3, foldersDone = 1, foldersKnown = 1, foldersSkipped = 0, listing = ScanProgress.LISTING_TREE),
                reports.last(),
                "only many is walked; other is resolved on the way",
            )
        }
}
