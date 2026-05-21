package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictLogTest {
    private fun tempDir(): Path = Files.createTempDirectory("conflict-test-")

    private fun createLog(dir: Path = tempDir()): ConflictLog =
        ConflictLog(
            logFile = dir.resolve("conflicts.jsonl"),
            backupDir = dir.resolve("backups"),
        )

    @Test
    fun `record with loserFile creates backup and appends JSONL entry`() {
        val dir = tempDir()
        val log = createLog(dir)
        val loser = dir.resolve("loser.txt")
        Files.writeString(loser, "original content")

        val entry =
            log.record(
                path = "Documents/report.pdf",
                localState = "MODIFIED",
                remoteState = "MODIFIED",
                policy = "LAST_WRITER_WINS",
                loserFile = loser,
            )

        assertNotNull(entry.backupFile)
        assertTrue(Files.exists(dir.resolve("backups").resolve(entry.backupFile!!)))
        assertEquals("Documents/report.pdf", entry.path)
        assertEquals("LAST_WRITER_WINS", entry.policy)

        // JSONL file should have one line
        val lines = Files.readAllLines(dir.resolve("conflicts.jsonl"))
        assertEquals(1, lines.filter { it.isNotBlank() }.size)
    }

    @Test
    fun `record without loserFile has null backupFile`() {
        val log = createLog()
        val entry =
            log.record(
                path = "docs/readme.md",
                localState = "MODIFIED",
                remoteState = "MODIFIED",
                policy = "KEEP_BOTH",
                loserFile = null,
            )

        assertNull(entry.backupFile)
        assertEquals("KEEP_BOTH", entry.policy)
    }

    @Test
    fun `recent returns entries in reverse-chronological order`() {
        val log = createLog()
        log.record("a.txt", "NEW", "NEW", "KEEP_BOTH", null)
        log.record("b.txt", "MODIFIED", "MODIFIED", "KEEP_BOTH", null)
        log.record("c.txt", "DELETED", "MODIFIED", "LAST_WRITER_WINS", null)

        val entries = log.recent(10)
        assertEquals(3, entries.size)
        assertEquals("c.txt", entries[0].path)
        assertEquals("b.txt", entries[1].path)
        assertEquals("a.txt", entries[2].path)
    }

    @Test
    fun `recent with limit returns only requested count`() {
        val log = createLog()
        repeat(10) { i ->
            log.record("file$i.txt", "MODIFIED", "MODIFIED", "KEEP_BOTH", null)
        }
        val entries = log.recent(5)
        assertEquals(5, entries.size)
        assertEquals("file9.txt", entries[0].path) // most recent first
    }

    @Test
    fun `findBackups returns only matching path entries with backups`() {
        val dir = tempDir()
        val log = createLog(dir)
        val loser = dir.resolve("loser.txt")
        Files.writeString(loser, "data")

        log.record("a.txt", "MODIFIED", "MODIFIED", "LAST_WRITER_WINS", loser)
        log.record("b.txt", "MODIFIED", "MODIFIED", "KEEP_BOTH", null)
        log.record("a.txt", "MODIFIED", "MODIFIED", "LAST_WRITER_WINS", loser)

        val backups = log.findBackups("a.txt")
        assertEquals(2, backups.size)
        assertTrue(backups.all { it.path == "a.txt" && it.backupFile != null })
    }

    @Test
    fun `findBackups returns empty for unknown path`() {
        val log = createLog()
        log.record("a.txt", "MODIFIED", "MODIFIED", "KEEP_BOTH", null)
        assertEquals(emptyList(), log.findBackups("nonexistent.txt"))
    }

    @Test
    fun `restore copies backup to correct sync root path`() {
        val dir = tempDir()
        val log = createLog(dir)
        val loser = dir.resolve("original.txt")
        Files.writeString(loser, "restore me")

        val entry =
            log.record(
                path = "sub/dir/file.txt",
                localState = "MODIFIED",
                remoteState = "MODIFIED",
                policy = "LAST_WRITER_WINS",
                loserFile = loser,
            )

        val syncRoot = dir.resolve("syncroot")
        Files.createDirectories(syncRoot)
        val restored = log.restore(entry, syncRoot)

        assertEquals(syncRoot.resolve("sub/dir/file.txt"), restored)
        assertTrue(Files.exists(restored))
        assertEquals("restore me", Files.readString(restored))
    }

    @Test
    fun `restore with missing backup file throws`() {
        val dir = tempDir()
        val log = createLog(dir)
        val entry =
            ConflictLog.Entry(
                timestamp = "2026-04-02T14:30:00Z",
                path = "test.txt",
                localState = "MODIFIED",
                remoteState = "MODIFIED",
                policy = "LAST_WRITER_WINS",
                backupFile = "nonexistent-backup.txt",
            )
        val syncRoot = dir.resolve("syncroot")
        assertFails { log.restore(entry, syncRoot) }
    }

    @Test
    fun `restore with null backupFile throws`() {
        val dir = tempDir()
        val log = createLog(dir)
        val entry =
            ConflictLog.Entry(
                timestamp = "2026-04-02T14:30:00Z",
                path = "test.txt",
                localState = "MODIFIED",
                remoteState = "MODIFIED",
                policy = "KEEP_BOTH",
                backupFile = null,
            )
        assertFails { log.restore(entry, dir) }
    }

    @Test
    fun `clear removes all backups and log`() {
        val dir = tempDir()
        val log = createLog(dir)
        val loser = dir.resolve("f.txt")
        Files.writeString(loser, "x")
        log.record("a.txt", "MODIFIED", "MODIFIED", "LAST_WRITER_WINS", loser)

        assertTrue(Files.exists(dir.resolve("conflicts.jsonl")))
        assertTrue(Files.isDirectory(dir.resolve("backups")))

        log.clear()

        assertTrue(!Files.exists(dir.resolve("conflicts.jsonl")))
        assertTrue(!Files.isDirectory(dir.resolve("backups")))
    }

    @Test
    fun `empty log returns empty list`() {
        val log = createLog()
        assertEquals(emptyList(), log.recent())
        assertEquals(emptyList(), log.findBackups("any"))
    }
}
