package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A drive that refuses an entry by its name: the block is recorded on the row (`name_block`, an additive column like
 * `upload_refused`), read back, and cleared by any rewrite of the row. The rows that live under a server name carry it
 * in `remote_path`; the queries that find the nearest folder above a path with a remote path or a block, and the two
 * updates that keep the remote paths of a subtree right across a move.
 */
class StateDatabaseNameBlockTest {
    private fun entry(
        path: String,
        folder: Boolean = false,
        remoteId: String? = null,
        remotePath: String? = null,
    ) = SyncEntry(
        path = path,
        remotePath = remotePath,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 0,
        remoteModified = null,
        localMtime = 1,
        localSize = 0,
        isFolder = folder,
        isPinned = false,
        isHydrated = !folder,
        lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
    )

    private fun open(path: Path = Files.createTempDirectory("ud-nameblock-db").resolve("state.db")) = StateDatabase(path).also { it.initialize() }

    @Test
    fun `a block is recorded with the sibling, read back, listed, and cleared by a rewrite of the row`() {
        val db = open()
        try {
            db.upsertEntry(entry("/a.txt"))
            db.upsertEntry(entry("/b.txt"))
            assertNull(db.nameBlock("/a.txt"))

            assertTrue(db.markNameBlocked("/a.txt", "similar_name", "pipe|sibling"))
            assertTrue(db.markNameBlocked("/b.txt", "similar_name", null))

            assertEquals(StateDatabase.NameBlock("similar_name", "pipe|sibling"), db.nameBlock("/a.txt"), "a '|' in the sibling's name survives")
            assertEquals(StateDatabase.NameBlock("similar_name", null), db.nameBlock("/b.txt"))
            assertEquals(listOf("/a.txt", "/b.txt"), db.blockedRows().map { it.path })
            assertTrue(db.getEntry("/a.txt")!!.lastErrorAt != null, "a block is also a failed upload on the row")

            db.upsertEntry(entry("/a.txt").copy(localMtime = 2)) // an upload that landed rewrites the row
            assertNull(db.nameBlock("/a.txt"))
            assertEquals(listOf("/b.txt"), db.blockedRows().map { it.path })
        } finally {
            db.close()
        }
    }

    private fun queryPlan(
        dbPath: Path,
        sql: String,
    ): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("EXPLAIN QUERY PLAN $sql")
                generateSequence { if (rs.next()) rs.getString("detail") else null }.toList()
            }
        }

    @Test
    fun `the blocked rows are read from the index of the few, not by walking every row in path order`() {
        // Asked every few seconds by the upload snapshot, under the monitor. `ORDER BY path` alone made SQLite walk
        // the whole path index to get the order for free: on a drive of 372,000 rows that was 0.3 s a call, with the
        // status request queued behind it.
        val dbPath = Files.createTempDirectory("ud-nameblock-plan").resolve("state.db")
        val db = open(dbPath)
        try {
            db.upsertEntry(entry("/a.txt"))
            db.upsertEntry(entry("/b.txt"))
            db.markNameBlocked("/b.txt", "similar_name", null)
            val plan = queryPlan(dbPath, StateDatabase.BLOCKED_ROWS_SQL)

            assertTrue(plan.any { it.contains("idx_sync_entries_name_block") }, "the partial index of blocked rows answers: $plan")
            assertTrue(plan.none { it.contains("idx_sync_entries_path_alive") }, "the path index is not walked: $plan")
            assertEquals(listOf("/b.txt"), db.blockedRows().map { it.path })
        } finally {
            db.close()
        }
    }

    @Test
    fun `the nearest blocked ancestor is a proper ancestor folder, never the path itself or a similar prefix`() {
        val db = open()
        try {
            db.upsertEntry(entry("/x", folder = true))
            db.markNameBlocked("/x", "similar_name", null)
            db.upsertEntry(entry("/x/sub", folder = true))
            db.upsertEntry(entry("/xy", folder = true))

            assertEquals("/x", db.blockedAncestor("/x/sub/f.txt"))
            assertEquals("/x", db.blockedAncestor("/x/sub"))
            assertNull(db.blockedAncestor("/x"), "the folder itself is not its own ancestor")
            assertNull(db.blockedAncestor("/xy/f.txt"), "/xy is not below /x")
        } finally {
            db.close()
        }
    }

    @Test
    fun `the nearest folder with a remote path of its own answers for everything beneath it`() {
        val db = open()
        try {
            db.upsertEntry(entry("/a", folder = true, remoteId = "ra", remotePath = "/a~ud1"))
            db.upsertEntry(entry("/a/b", folder = true, remoteId = "rb", remotePath = "/a~ud1/b~ud2"))
            db.upsertEntry(entry("/plain", folder = true, remoteId = "rp"))

            assertEquals("/a/b" to "/a~ud1/b~ud2", db.remoteAncestor("/a/b/f.txt"))
            assertEquals("/a" to "/a~ud1", db.remoteAncestor("/a/other.txt"))
            assertNull(db.remoteAncestor("/plain/f.txt"))
            assertNull(db.remoteAncestor("/a"), "a folder is not its own ancestor")
            assertEquals(setOf("/a" to "/a~ud1", "/a/b" to "/a~ud1/b~ud2"), db.foldersWithRemotePath().toSet())
        } finally {
            db.close()
        }
    }

    @Test
    fun `a move rebases the remote paths beneath it and drops one that equals its path again`() {
        val db = open()
        try {
            db.upsertEntry(entry("/a", folder = true, remoteId = "ra", remotePath = "/a~ud1"))
            db.upsertEntry(entry("/a/f.txt", remoteId = "rf", remotePath = "/a~ud1/f.txt"))
            db.upsertEntry(entry("/other.txt", remoteId = "ro"))

            // The folder is renamed to an ordinary name: /a -> /renamed, remote /a~ud1 -> /renamed.
            db.renamePrefix("/a", "/renamed")
            db.rebaseRemotePaths("/a~ud1", "/renamed")

            assertNull(db.getEntry("/renamed")!!.remotePath)
            assertNull(db.getEntry("/renamed/f.txt")!!.remotePath)
            assertNull(db.getEntry("/other.txt")!!.remotePath, "a row with no remote path is untouched")
        } finally {
            db.close()
        }
    }

    @Test
    fun `moving into a folder that lives under a server name gives the moved rows theirs`() {
        val db = open()
        try {
            db.upsertEntry(entry("/dest", folder = true, remoteId = "rd", remotePath = "/dest~ud1"))
            db.upsertEntry(entry("/dest/moved", folder = true, remoteId = "rm"))
            db.upsertEntry(entry("/dest/moved/f.txt", remoteId = "rf"))
            db.upsertEntry(entry("/dest/moved/g.txt", remoteId = "rg", remotePath = "/dest~ud1/moved/g~ud9.txt"))

            db.assignRemotePaths("/dest/moved", "/dest~ud1/moved")

            assertEquals("/dest~ud1/moved", db.getEntry("/dest/moved")!!.remotePath)
            assertEquals("/dest~ud1/moved/f.txt", db.getEntry("/dest/moved/f.txt")!!.remotePath)
            assertEquals("/dest~ud1/moved/g~ud9.txt", db.getEntry("/dest/moved/g.txt")!!.remotePath, "a remote path a row already has stays")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a state db written before the column gets it on open, and its rows read as not blocked`() {
        val dbPath = Files.createTempDirectory("ud-nameblock-old").resolve("state.db")
        open(dbPath).also { it.upsertEntry(entry("/old.txt")) }.close()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DROP VIEW IF EXISTS alive_entries")
                stmt.executeUpdate("DROP INDEX IF EXISTS idx_sync_entries_name_block")
                stmt.executeUpdate("ALTER TABLE sync_entries DROP COLUMN name_block")
            }
        }

        val db = open(dbPath)
        try {
            assertNull(db.nameBlock("/old.txt"))
            assertTrue(db.markNameBlocked("/old.txt", "similar_name", "s"))
            assertEquals("s", db.nameBlock("/old.txt")!!.sibling)
        } finally {
            db.close()
        }
    }
}
