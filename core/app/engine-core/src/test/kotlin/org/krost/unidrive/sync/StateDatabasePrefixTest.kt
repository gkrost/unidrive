package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Prefix and child queries of [StateDatabase] match paths exactly, by code points: #489 (UTF-16 length against
 * SQLite's character-counting substr let grandchildren through below names outside the BMP) and #490 (SQLite's
 * LIKE folds ASCII case, so a case-only folder rename deleted the folder's whole subtree). Non-BMP characters are
 * built from code points, so the source stays ASCII.
 */
class StateDatabasePrefixTest {
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-prefix").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun entry(
        path: String,
        folder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = "h",
        remoteSize = 1,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = 1,
        localSize = 1,
        isFolder = folder,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.now(),
    )

    private fun cps(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

    private val cat = cps(0x1F638) // one emoji: two UTF-16 units, one SQLite character
    private val cats = cat + cat + cat

    private fun paths(entries: List<SyncEntry>) = entries.map { it.path }.sorted()

    @Test
    fun `listDirectChildren below a non-BMP folder name returns only direct children`() {
        db.upsertEntry(entry("/a/$cats", folder = true))
        db.upsertEntry(entry("/a/$cats/x", folder = true))
        db.upsertEntry(entry("/a/$cats/x/deep.txt"))
        db.upsertEntry(entry("/a/$cats/y.txt"))
        assertEquals(listOf("/a/$cats/x", "/a/$cats/y.txt"), paths(db.listDirectChildren("/a/$cats")))
    }

    @Test
    fun `listDirectChildren of the root and of an ASCII folder are unchanged`() {
        db.upsertEntry(entry("/top", folder = true))
        db.upsertEntry(entry("/top/a.txt"))
        db.upsertEntry(entry("/top/sub/b.txt"))
        db.upsertEntry(entry("/root.txt"))
        assertEquals(listOf("/root.txt", "/top"), paths(db.listDirectChildren("")))
        assertEquals(listOf("/top/a.txt"), paths(db.listDirectChildren("/top")))
    }

    @Test
    fun `listDirectChildren does not mix folders whose names differ only in case`() {
        db.upsertEntry(entry("/Docs", folder = true))
        db.upsertEntry(entry("/Docs/a.txt"))
        db.upsertEntry(entry("/docs", folder = true))
        db.upsertEntry(entry("/docs/b.txt"))
        assertEquals(listOf("/Docs/a.txt"), paths(db.listDirectChildren("/Docs")))
        assertEquals(listOf("/docs/b.txt"), paths(db.listDirectChildren("/docs")))
    }

    @Test
    fun `a case-only folder rename keeps the whole subtree`() {
        db.upsertEntry(entry("/Docs", folder = true))
        db.upsertEntry(entry("/Docs/a.txt"))
        db.upsertEntry(entry("/Docs/sub/b.txt"))
        db.renamePrefix("/Docs", "/docs")
        assertEquals(listOf("/docs", "/docs/a.txt", "/docs/sub/b.txt"), paths(db.getAllEntries()))
    }

    @Test
    fun `a rename onto itself changes nothing`() {
        db.upsertEntry(entry("/same", folder = true))
        db.upsertEntry(entry("/same/a.txt"))
        db.renamePrefix("/same", "/same")
        assertEquals(listOf("/same", "/same/a.txt"), paths(db.getAllEntries()))
    }

    @Test
    fun `renaming a non-BMP folder rewrites the descendants' paths exactly`() {
        db.upsertEntry(entry("/$cats", folder = true))
        db.upsertEntry(entry("/$cats/inner/file.txt"))
        db.renamePrefix("/$cats", "/plain")
        assertEquals(listOf("/plain", "/plain/inner/file.txt"), paths(db.getAllEntries()))
    }

    @Test
    fun `prefix queries are exact about case and about LIKE wildcards`() {
        db.upsertEntry(entry("/Docs/a.txt"))
        db.upsertEntry(entry("/docs/b.txt"))
        db.upsertEntry(entry("/a_b/c.txt"))
        db.upsertEntry(entry("/axb/d.txt"))
        assertEquals(listOf("/Docs/a.txt"), paths(db.getEntriesByPrefix("/Docs/")))
        assertEquals(listOf("/a_b/c.txt"), paths(db.getEntriesByPrefix("/a_b/")))
        assertEquals(1, db.countEntriesUnderTopLevel("/Docs"))
    }
}
