package org.krost.unidrive.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trash namespace: a virtual top-level folder that shows what the provider keeps in its trash apart from the live
 * tree. It has a stable internal key and a configurable name, takes the first free suffixed name when a live top-level
 * item already has its name, and is the one place that says which paths are read-only.
 */
class TrashNamespaceTest {
    private fun item(
        id: String,
        path: String,
        isFolder: Boolean = false,
        trashed: Boolean = false,
        deleted: Boolean = false,
        parentId: String? = null,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = if (isFolder) 0 else 1,
        isFolder = isFolder,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
        deleted = deleted,
        trashed = trashed,
        parentId = parentId,
    )

    private fun place(
        trash: TrashNamespace,
        vararg items: CloudItem,
        known: List<String> = emptyList(),
    ) = trash.place(items.toList()) { known }

    private fun warnings(
        logger: Logger,
        block: () -> Unit,
    ): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    // ---- placing ---------------------------------------------------------------------------------------------

    @Test
    fun `a trashed item goes under the root at its original path and keeps its id`() {
        val trash = TrashNamespace()

        val placed =
            place(
                trash,
                item("t1", "/Old", isFolder = true, trashed = true, parentId = "drive-root"),
                item("f1", "/Old/note.txt", trashed = true, parentId = "t1"),
                item("l1", "/Live.txt"),
            )

        val byId = placed.associateBy { it.id }
        assertEquals("/Papierkorb/Old", byId.getValue("t1").path)
        assertEquals("/Papierkorb/Old/note.txt", byId.getValue("f1").path)
        assertEquals("/Live.txt", byId.getValue("l1").path, "a live item is not touched")
        assertTrue(byId.getValue("f1").trashed, "the flag stays for the consumers that read it")
        assertEquals("Old", byId.getValue("t1").name)
    }

    @Test
    fun `the root folder is added with the stable key, and a top-level trashed item hangs below it`() {
        val trash = TrashNamespace()

        val placed = place(trash, item("t1", "/Old", isFolder = true, trashed = true, parentId = "drive-root"))

        val root = placed.single { it.id == TrashNamespace.ROOT_ID }
        assertEquals("unidrive:trash", TrashNamespace.ROOT_ID)
        assertEquals("/Papierkorb", root.path)
        assertEquals("Papierkorb", root.name)
        assertTrue(root.isFolder && root.trashed && !root.deleted)
        assertEquals(TrashNamespace.ROOT_ID, placed.single { it.id == "t1" }.parentId, "its parent in the tree the user sees is the root")
    }

    @Test
    fun `a trashed item below a trashed folder keeps its real parent`() {
        val trash = TrashNamespace()

        val placed =
            place(
                trash,
                item("t1", "/Old", isFolder = true, trashed = true, parentId = "drive-root"),
                item("t2", "/Old/Deep", isFolder = true, trashed = true, parentId = "t1"),
            )

        assertEquals("t1", placed.single { it.id == "t2" }.parentId)
    }

    @Test
    fun `a page without a trashed item comes back as it is, and a tombstone is not placed`() {
        val trash = TrashNamespace()
        val live = listOf(item("a", "/a.txt"), item("b", "/b.txt", deleted = true, trashed = true))

        val placed = trash.place(live) { error("no collision to decide") }

        assertTrue(placed === live, "the same list, not a copy")
        assertNull(trash.rootPath(), "nothing is in the trash")
    }

    @Test
    fun `placing remembers the ids, and an item that is live again is forgotten`() {
        val trash = TrashNamespace()
        place(trash, item("f1", "/Old/a.txt", trashed = true), item("f2", "/Old/b.txt", trashed = true))

        assertTrue(trash.holdsId("f1") && trash.holdsId("f2"))
        assertTrue(trash.holdsId(TrashNamespace.ROOT_ID))
        assertFalse(trash.holdsId("other"))
        assertFalse(trash.holdsId(null))

        // f1 was restored: the next page carries it live.
        place(trash, item("f1", "/Back/a.txt"), item("f2", "/Old/b.txt", trashed = true))

        assertFalse(trash.holdsId("f1"))
        assertTrue(trash.holdsId("f2"))
    }

    // ---- the name --------------------------------------------------------------------------------------------

    @Test
    fun `the name comes from the profile setting`() {
        val trash = TrashNamespace("Corbeille")

        val placed = place(trash, item("f1", "/Old/a.txt", trashed = true))

        assertEquals("Corbeille", trash.configuredName)
        assertEquals("/Corbeille/Old/a.txt", placed.single { it.id == "f1" }.path)
        assertEquals("/Corbeille", trash.rootPath())
    }

    @Test
    fun `a name that is no folder name is not used`() {
        val log = LoggerFactory.getLogger("trash-name-test") as Logger
        val shown = warnings(log) { TrashNamespace("a/b", log = log) }
        assertEquals(1, shown.size, "said once: $shown")
        for (bad in listOf("", "  ", ".", "..", "a/b", "a\\b", "a:b", "a<b", "a|b", "a?b", "a*b", "x\"y", "trail.", "tab\there", "n".repeat(101))) {
            assertFalse(TrashNamespace.isUsableName(bad), "[$bad]")
            assertEquals("Papierkorb", TrashNamespace(bad).configuredName, "[$bad] falls back")
        }
        for (good in listOf("Papierkorb", "Trash", "Corbeille", "Cestino", "Papelera de reciclaje", "ごみ箱", "Trash (cloud)", "a.b")) {
            assertTrue(TrashNamespace.isUsableName(good), "[$good]")
        }
        assertEquals("Trash", TrashNamespace("  Trash ").configuredName, "surrounding blanks are not part of the name")
        assertEquals("Papierkorb", TrashNamespace(null).configuredName)
    }

    @Test
    fun `a live top-level item with the name keeps it and the root takes the next free one, said once`() {
        val log = LoggerFactory.getLogger("trash-collision-test") as Logger
        val trash = TrashNamespace(log = log)
        val live = item("p", "/Papierkorb", isFolder = true)
        val trashed = item("f1", "/Old/a.txt", trashed = true)

        val shown =
            warnings(log) {
                val first = place(trash, live, trashed)
                assertEquals("/Papierkorb (2)", first.single { it.id == TrashNamespace.ROOT_ID }.path)
                assertEquals("/Papierkorb (2)/Old/a.txt", first.single { it.id == "f1" }.path)
                assertEquals("/Papierkorb", first.single { it.id == "p" }.path, "the live folder is untouched")
                place(trash, live, trashed)
            }

        assertEquals(1, shown.size, "once, not on every page: $shown")
        assertTrue("Papierkorb (2)" in shown.single(), shown.single())
        assertEquals("/Papierkorb (2)", trash.rootPath())
    }

    @Test
    fun `two collisions take the third name, and the state of the page does not matter`() {
        val trash = TrashNamespace()

        val placed =
            place(
                trash,
                item("a", "/Papierkorb"),
                item("b", "/Papierkorb (2)", isFolder = true),
                item("f1", "/Old/a.txt", trashed = true),
            )

        assertEquals("/Papierkorb (3)", placed.single { it.id == TrashNamespace.ROOT_ID }.path)
    }

    @Test
    fun `a live top-level item that only state db knows is a collision too`() {
        val trash = TrashNamespace()

        val placed = place(trash, item("f1", "/Old/a.txt", trashed = true), known = listOf("Papierkorb", "Docs"))

        assertEquals("/Papierkorb (2)", placed.single { it.id == TrashNamespace.ROOT_ID }.path)
    }

    @Test
    fun `a trashed or deleted item does not compete for the name`() {
        val trash = TrashNamespace()

        val placed =
            place(
                trash,
                item("x", "/Papierkorb", isFolder = true, trashed = true),
                item("y", "/Papierkorb", isFolder = true, deleted = true),
                item("f1", "/Old/a.txt", trashed = true),
            )

        assertEquals("/Papierkorb", placed.single { it.id == TrashNamespace.ROOT_ID }.path)
    }

    @Test
    fun `where the file system folds case a name that differs by case is a collision`() {
        val folding = TrashNamespace(ignoreCase = true)
        val exact = TrashNamespace(ignoreCase = false)

        val a = place(folding, item("p", "/papierkorb"), item("f1", "/Old/a.txt", trashed = true))
        val b = place(exact, item("p", "/papierkorb"), item("f1", "/Old/a.txt", trashed = true))

        assertEquals("/Papierkorb (2)", a.single { it.id == TrashNamespace.ROOT_ID }.path)
        assertEquals("/Papierkorb", b.single { it.id == TrashNamespace.ROOT_ID }.path)
    }

    // ---- the read-only rule ----------------------------------------------------------------------------------

    @Test
    fun `nothing is read-only while nothing is in the trash`() {
        val trash = TrashNamespace()

        assertFalse(trash.contains("/Papierkorb"), "the name is not reserved until the root exists")
        assertFalse(trash.contains("/Papierkorb/x"))
        assertFalse(trash.isRoot("/Papierkorb"))
    }

    @Test
    fun `the root and everything below it is in the namespace, a neighbour is not`() {
        val trash = TrashNamespace(ignoreCase = false)
        place(trash, item("f1", "/Old/a.txt", trashed = true))

        assertTrue(trash.contains("/Papierkorb"))
        assertTrue(trash.contains("/Papierkorb/Old"))
        assertTrue(trash.contains("/Papierkorb/Old/a.txt"))
        assertTrue(trash.contains("/Papierkorb/never/synced/file.bin"), "a path nobody has made is just as read-only")
        assertFalse(trash.contains("/PapierkorbX"))
        assertFalse(trash.contains("/Papierkorb2/a.txt"))
        assertFalse(trash.contains("/papierkorb/a.txt"), "case counts where the file system keeps it")
        assertFalse(trash.contains("/Old/Papierkorb/a.txt"))
        assertFalse(trash.contains("/Papier"))
        assertTrue(trash.isRoot("/Papierkorb"))
        assertFalse(trash.isRoot("/Papierkorb/Old"))
    }

    @Test
    fun `where the file system folds case the namespace does too`() {
        val trash = TrashNamespace(ignoreCase = true)
        place(trash, item("f1", "/Old/a.txt", trashed = true))

        assertTrue(trash.contains("/PAPIERKORB/old"))
        assertTrue(trash.isRoot("/papierkorb"))
        assertFalse(trash.contains("/PapierkorbX"))
    }

    // ---- the root from state.db ------------------------------------------------------------------------------

    @Test
    fun `the root is read back from state db and gone when its row is`() {
        val dir = Files.createTempDirectory("trash-namespace-db")
        val db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
        try {
            val trash = TrashNamespace()
            trash.refresh(db)
            assertNull(trash.rootPath(), "an empty database has no trash")

            db.upsertEntry(
                SyncEntry(
                    path = "/Corbeille (2)",
                    remoteId = TrashNamespace.ROOT_ID,
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            trash.refresh(db)
            assertEquals("/Corbeille (2)", trash.rootPath(), "a restart knows the suffixed name of the last pass")
            assertTrue(trash.contains("/Corbeille (2)/x"))

            db.markDeleted("/Corbeille (2)")
            trash.refresh(db)
            assertNull(trash.rootPath())
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }
}
