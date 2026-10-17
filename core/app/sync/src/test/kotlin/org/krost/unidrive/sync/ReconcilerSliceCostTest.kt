package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * The per-page cost of the streaming reconcile slice: `resolveSlice` reads the rows its page and the
 * local changes need, not the whole `sync_entries` table once per delta page. The assertions count
 * rows read, never wall time. The "legacy" reconciler below reads the whole table on every slice,
 * which is exactly what `resolveSlice` did before; it is the reference for the parity test.
 */
class ReconcilerSliceCostTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-slice-cost")
        db = StateDatabase(Files.createTempDirectory("unidrive-slice-cost-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private val t0 = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        id: String? = "id-$path",
        hash: String? = "h",
        remotePath: String? = null,
        hydrated: Boolean = true,
    ) = SyncEntry(
        path = path,
        remoteId = id,
        remoteHash = hash,
        remoteSize = 100,
        remoteModified = t0,
        localMtime = 1711627200000,
        localSize = 100,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = t0,
        remotePath = remotePath,
    )

    private fun item(
        path: String,
        id: String = "id-$path",
        hash: String? = "h",
        deleted: Boolean = false,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = 100,
        isFolder = false,
        modified = t0,
        created = null,
        hash = hash,
        mimeType = null,
        deleted = deleted,
    )

    private class RowCounter {
        var lookups = 0
        var rows = 0L
    }

    /** The current reconciler with a lookup that counts the rows it returns. */
    private fun counting(
        counter: RowCounter,
        xdg: Map<String, String> = emptyMap(),
    ) = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, xdgUserDirsOverrides = xdg).also {
        it.entryLookup = { paths ->
            val found = Reconciler.entriesByPathsChunked(db, paths)
            counter.lookups++
            counter.rows += found.size
            found
        }
    }

    /** The pre-fix behaviour: every slice materialises the whole table, whatever the page needs. */
    private fun legacy(
        counter: RowCounter,
        xdg: Map<String, String> = emptyMap(),
    ) = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, xdgUserDirsOverrides = xdg).also {
        it.entryLookup = { _ ->
            val all = db.getAllEntries()
            counter.lookups++
            counter.rows += all.size
            all.associateBy { it.path }
        }
    }

    @Test
    fun `a streamed gather reads only the rows its pages and local changes need`() {
        val tableRows = 100_000
        val pages = 50
        val pageSize = 200
        db.batch {
            for (i in 0 until tableRows) db.upsertEntry(row("/d${i % 500}/f$i.bin"))
        }
        assertEquals(tableRows, db.getEntryCount())

        // Every page reports 200 existing files with a new hash (remote-modified), and the local
        // scan found 40 changed files and 10 new ones.
        val localChanges =
            (0 until 40).associate { "/d${(tableRows - 1 - it) % 500}/f${tableRows - 1 - it}.bin" to ChangeState.MODIFIED } +
                (0 until 10).associate { "/fresh/new$it.bin" to ChangeState.NEW }
        val pageRemote =
            (0 until pages).map { p ->
                (0 until pageSize).associate {
                    val i = p * pageSize + it
                    val path = "/d${i % 500}/f$i.bin"
                    path to item(path, hash = "changed")
                }
            }

        val after = RowCounter()
        val current = counting(after)
        val currentActions = pageRemote.map { current.resolveSlice(it, localChanges) }

        // Before: the same pages through the whole-table lookup. Run on a few pages only, the cost
        // per page is the table size and does not depend on the page.
        val before = RowCounter()
        val reference = legacy(before)
        val legacyPages = 3
        val legacyActions = pageRemote.take(legacyPages).map { reference.resolveSlice(it, localChanges) }

        println("rows read: whole table per slice = ${before.rows / legacyPages} rows x $pages pages = ${before.rows / legacyPages * pages}")
        println("rows read: per-slice lookup = ${after.rows} rows over $pages pages (${after.lookups} lookups)")

        assertEquals(legacyPages, before.lookups)
        assertEquals(tableRows.toLong() * legacyPages, before.rows, "the old path reads the whole table on each slice")
        assertEquals(pages, after.lookups, "one lookup per slice")
        // Each slice reads its 200 page rows plus the 40 local-modified rows; the NEW paths have no row.
        assertEquals((pages * (pageSize + 40)).toLong(), after.rows)
        assertTrue(after.rows < tableRows * 0.2, "all ${pages} pages read less than one table's worth: ${after.rows}")
        assertEquals(legacyActions, currentActions.take(legacyPages), "the first pages plan the same actions")
    }

    @Test
    fun `slice plans equal the whole-table plans on a mixed fixture with renames, deletes and aliased paths`() {
        Files.createDirectories(syncRoot.resolve("Bilder"))
        val xdg = mapOf("XDG_PICTURES_DIR" to "Bilder")

        // Table: untouched files, a remote-modified file, a remote-deleted file, a rename source,
        // a locally modified and a locally deleted file, a pending-upload row, an aliased row.
        for (i in 0 until 300) db.upsertEntry(row("/bulk/b$i.txt"))
        db.upsertEntry(row("/docs/mod.txt"))
        db.upsertEntry(row("/docs/gone.txt"))
        db.upsertEntry(row("/docs/old-name.txt", id = "id-X", hash = "hx"))
        db.upsertEntry(row("/docs/local-mod.txt"))
        db.upsertEntry(row("/docs/local-del.txt"))
        db.upsertEntry(row("/docs/pending.txt", id = null, hash = null, hydrated = true))
        db.upsertEntry(row("/Bilder/known.jpg", id = "id-pic", remotePath = "/Pictures/known.jpg"))
        db.upsertEntry(row("/Bilder/changed.jpg", id = "id-pic2", remotePath = "/Pictures/changed.jpg"))

        val localChanges =
            mutableMapOf(
                "/docs/local-mod.txt" to ChangeState.MODIFIED,
                "/docs/local-del.txt" to ChangeState.DELETED,
                "/docs/pending.txt" to ChangeState.MODIFIED,
                "/docs/gone.txt" to ChangeState.DELETED,
                "/Bilder" to ChangeState.NEW,
                "/Bilder/fresh.jpg" to ChangeState.NEW,
                "/Docs/Mod.txt" to ChangeState.NEW, // case twin of /docs/mod.txt
            )
        // Enough local-only paths that a slice needs more than one lookup chunk.
        for (i in 0 until Reconciler.ENTRY_LOOKUP_CHUNK + 250) localChanges["/many/n$i.txt"] = ChangeState.NEW
        for (i in 0 until 40) localChanges["/bulk/b$i.txt"] = ChangeState.MODIFIED

        val stableTop = setOf("Pictures", "docs", "bulk")
        val pages =
            listOf(
                mapOf(
                    "/docs/new.txt" to item("/docs/new.txt"),
                    "/docs/mod.txt" to item("/docs/mod.txt", hash = "changed"),
                    "/docs/gone.txt" to item("/docs/gone.txt", deleted = true),
                    "/docs/local-mod.txt" to item("/docs/local-mod.txt", hash = "changed"),
                    "/docs/pending.txt" to item("/docs/pending.txt", id = "id-pending", hash = "p"),
                ),
                // The renamed file: id-X now at a new path (the coalesced slice carries the new path).
                mapOf(
                    "/docs/new-name.txt" to item("/docs/new-name.txt", id = "id-X", hash = "hx2"),
                    "/bulk/b7.txt" to item("/bulk/b7.txt", deleted = true),
                    "/bulk/b250.txt" to item("/bulk/b250.txt"),
                ),
                // Aliased canonical paths for rows stored under the locale name.
                mapOf(
                    "/Pictures" to item("/Pictures").copy(isFolder = true, hash = null),
                    "/Pictures/known.jpg" to item("/Pictures/known.jpg", id = "id-pic"),
                    "/Pictures/changed.jpg" to item("/Pictures/changed.jpg", id = "id-pic2", hash = "changed"),
                    "/Pictures/fresh.jpg" to item("/Pictures/fresh.jpg"),
                ),
                // A page wider than one lookup chunk.
                (0 until Reconciler.ENTRY_LOOKUP_CHUNK + 100).associate { i ->
                    val path = if (i < 300) "/bulk/b$i.txt" else "/extra/e$i.txt"
                    path to item(path, hash = if (i % 3 == 0) "changed" else "h")
                },
            )

        val current = counting(RowCounter(), xdg)
        val reference = legacy(RowCounter(), xdg)
        val currentActions = mutableListOf<SyncAction>()
        val referenceActions = mutableListOf<SyncAction>()
        for (page in pages) {
            val now = current.resolveSlice(page, localChanges, stableRemoteTopLevelNames = stableTop)
            val old = reference.resolveSlice(page, localChanges, stableRemoteTopLevelNames = stableTop)
            assertEquals(old, now, "page ${page.keys.take(2)} plans different actions")
            currentActions += now
            referenceActions += old
        }
        assertTrue(currentActions.any { it is SyncAction.DownloadContent }, "fixture plans downloads")
        assertTrue(currentActions.any { it is SyncAction.DeleteLocal || it is SyncAction.RemoveEntry }, "fixture plans deletes")
        assertTrue(currentActions.any { it.path.startsWith("/Bilder/") }, "fixture exercises the locale alias: $currentActions")

        // The scan-end pass (rename detection, recovery, sort) over the streamed actions agrees too.
        val fullRemote = pages.fold(emptyMap<String, CloudItem>()) { acc, page -> acc + page }
        val finalNow = current.finalizeStreaming(currentActions, fullRemote, localChanges)
        val finalOld = reference.finalizeStreaming(referenceActions, fullRemote, localChanges)
        assertEquals(finalOld, finalNow)
        assertTrue(finalNow.any { it is SyncAction.MoveLocal && it.path == "/docs/new-name.txt" }, "the rename is detected: $finalNow")
    }

    @Test
    fun `a row the executor writes between two slices is visible to the second slice`() {
        val reconciler = counting(RowCounter())
        val page = mapOf("/a.txt" to item("/a.txt"))

        val first = reconciler.resolveSlice(page, emptyMap())
        assertEquals(listOf("/a.txt"), first.filterIsInstance<SyncAction.DownloadContent>().map { it.path })

        // The streamed download lands and records its row; nothing is cached across slices.
        db.upsertEntry(row("/a.txt"))
        assertTrue(reconciler.resolveSlice(page, emptyMap()).isEmpty(), "the second slice sees the executor's row")

        db.deleteEntry("/a.txt")
        assertEquals(1, reconciler.resolveSlice(page, emptyMap()).size, "and sees its removal")
    }
}
