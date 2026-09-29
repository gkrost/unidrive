package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateDatabaseSnapshotTest {
    private lateinit var dir: Path

    private val tmp: Path get() = dir.resolve("tmp")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("snapshot-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun entry(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = null,
            remoteSize = 1,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
        )

    private fun seeded(): Path {
        val src = dir.resolve("src/state.db")
        val db = StateDatabase(src)
        db.initialize()
        db.upsertEntry(entry("/a.txt"))
        db.upsertEntry(entry("/b.txt"))
        db.setSyncState("delta_cursor", "cursor-1")
        db.close()
        return src
    }

    private fun sha(p: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") { "%02x".format(it) }

    private fun snapshotDirs(): List<Path> =
        if (!Files.exists(tmp)) emptyList() else Files.list(tmp).use { s -> s.filter { it.fileName.toString().startsWith("unidrive-dryrun-") }.toList() }

    @Test
    fun `snapshot holds the same rows and state as the source`() {
        val src = seeded()
        val snap = StateDatabase.snapshotOf(src, tmp)
        try {
            assertEquals(setOf("/a.txt", "/b.txt"), snap.getAllEntries().map { it.path }.toSet())
            assertEquals("cursor-1", snap.getSyncState("delta_cursor"))
            assertTrue(snap.isDisposable)
        } finally {
            snap.close()
        }
    }

    @Test
    fun `taking a snapshot and writing to it never changes the source file`() {
        val src = seeded()
        val before = sha(src)
        val snap = StateDatabase.snapshotOf(src, tmp)
        snap.upsertEntry(entry("/c.txt"))
        snap.setSyncState("delta_cursor", "moved")
        snap.close()

        assertEquals(before, sha(src), "the source state.db must be byte-identical")
        val reopened = StateDatabase(src)
        reopened.initialize()
        assertEquals(2, reopened.getEntryCount())
        assertEquals("cursor-1", reopened.getSyncState("delta_cursor"))
        reopened.close()
    }

    @Test
    fun `close deletes the copy and its directory`() {
        val snap = StateDatabase.snapshotOf(seeded(), tmp)
        val file = snap.file
        assertTrue(Files.exists(file))
        snap.close()
        assertFalse(Files.exists(file))
        assertFalse(Files.exists(file.parent))
        assertTrue(snapshotDirs().isEmpty())
    }

    @Test
    fun `a source path with spaces or a hash is read, not silently truncated`() {
        // A file: URI truncated at these characters used to open an EMPTY
        // phantom database, so the dry-run previewed nothing. The plain-path
        // open must see the real rows.
        val spaced = Files.createDirectories(dir.resolve("with space #and hash"))
        val src = spaced.resolve("state.db")
        val db = StateDatabase(src)
        db.initialize()
        db.upsertEntry(entry("/a.txt"))
        db.close()

        val snap = StateDatabase.snapshotOf(src, tmp)
        try {
            assertEquals(setOf("/a.txt"), snap.getAllEntries().map { it.path }.toSet())
        } finally {
            snap.close()
        }
    }

    @Test
    fun `a real database is not disposable and an in-memory one is`() {
        val real = StateDatabase(dir.resolve("real.db"))
        real.initialize()
        assertFalse(real.isDisposable)
        real.close()
        val shadow = StateDatabase(dir.resolve("unused.db"), inMemory = true)
        assertTrue(shadow.isDisposable)
    }

    @Test
    fun `a missing source gives a blank disposable database`() {
        val snap = StateDatabase.snapshotOf(dir.resolve("nope/state.db"), tmp)
        try {
            assertTrue(snap.isDisposable)
            assertEquals(0, snap.getEntryCount())
            assertTrue(Files.exists(snap.file))
        } finally {
            snap.close()
        }
    }

    @Test
    fun `a snapshot taken while another connection holds an open write sees only committed rows`() {
        val src = seeded()
        val writer = DriverManager.getConnection("jdbc:sqlite:$src")
        writer.autoCommit = false
        writer.createStatement().use {
            it.execute("INSERT INTO sync_entries (remote_id, path, status, last_synced) VALUES ('uncommitted','/x','EXISTS','2026-03-28T12:00:00Z')")
        }
        try {
            val snap = StateDatabase.snapshotOf(src, tmp)
            try {
                assertEquals(setOf("/a.txt", "/b.txt"), snap.getAllEntries().map { it.path }.toSet())
            } finally {
                snap.close()
            }
        } finally {
            writer.rollback()
            writer.close()
        }
    }

    @Test
    fun `a pre-redesign source is migrated on the copy only`() {
        val src = dir.resolve("old/state.db")
        Files.createDirectories(src.parent)
        DriverManager.getConnection("jdbc:sqlite:$src").use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT)")
                it.execute("CREATE TABLE sync_entries (path TEXT PRIMARY KEY, legacy TEXT)")
                it.execute("INSERT INTO sync_entries VALUES ('/old.txt','legacy-row')")
            }
        }
        val before = sha(src)

        val snap = StateDatabase.snapshotOf(src, tmp)
        assertEquals(0, snap.getEntryCount(), "the copy is migrated to the new shape")
        snap.close()

        assertEquals(before, sha(src))
        DriverManager.getConnection("jdbc:sqlite:$src").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT legacy FROM sync_entries").use { rs -> assertTrue(rs.next()) } }
        }
    }

    @Test
    fun `a source written by a newer build fails before anything is copied`() {
        val src = seeded()
        DriverManager.getConnection("jdbc:sqlite:$src").use { c ->
            c.createStatement().use { it.execute("UPDATE sync_state SET value='99' WHERE key='schema_version'") }
        }
        val e = assertFailsWith<IllegalStateException> { StateDatabase.snapshotOf(src, tmp) }
        assertTrue("newer" in e.message!!, e.message)
        assertTrue(snapshotDirs().isEmpty(), "no half-made copy may be left behind")
    }

    @Test
    fun `not enough free space fails cleanly and leaves nothing behind`() {
        val src = seeded()
        val e = assertFailsWith<IllegalStateException> { StateDatabase.snapshotOf(src, tmp, usableSpace = { 0L }) }
        assertTrue("free space" in e.message!! && "--reset --dry-run" in e.message!!, e.message)
        assertTrue(snapshotDirs().isEmpty())
    }

    @Test
    fun `the stale sweep removes only old snapshot directories`() {
        Files.createDirectories(tmp)
        val old = Files.createDirectories(tmp.resolve("unidrive-dryrun-old"))
        val fresh = Files.createDirectories(tmp.resolve("unidrive-dryrun-fresh"))
        val other = Files.createDirectories(tmp.resolve("something-else-old"))
        val aged = FileTime.from(Instant.now().minus(Duration.ofHours(48)))
        Files.setLastModifiedTime(old, aged)
        Files.setLastModifiedTime(other, aged)

        StateDatabase.sweepStaleSnapshots(tmp, Duration.ofHours(24))

        assertFalse(Files.exists(old), "an old snapshot directory is swept")
        assertTrue(Files.exists(fresh), "a recent one may belong to a running dry-run")
        assertTrue(Files.exists(other), "directories that are not ours are never touched")
    }
}
