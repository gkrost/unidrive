package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.RandomAccessFile
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * UD-226: unit tests for SweepCommand's stub detection + rehydrate flag flip.
 *
 * The CLI entry is exercised via Main's picocli CommandLine in integration tests (UD-202);
 * here we test the pure logic — `findNullByteStubs` and `isAllNullSampled` — via reflection
 * because keeping them package-private lets callers stay simple.
 */
class SweepCommandTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-sweep-test")
        val dbPath = Files.createTempDirectory("unidrive-sweep-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun entry(
        path: String,
        remoteSize: Long,
        isHydrated: Boolean = true,
        isFolder: Boolean = false,
    ): SyncEntry =
        SyncEntry(
            path = path,
            remoteId = "id$path",
            remoteHash = "h$path",
            remoteSize = remoteSize,
            remoteModified = Instant.parse("2026-04-18T12:00:00Z"),
            localMtime = 0L,
            localSize = remoteSize,
            isFolder = isFolder,
            isPinned = false,
            isHydrated = isHydrated,
            lastSynced = Instant.now(),
        )

    private fun writeNullFile(
        rel: String,
        size: Long,
    ) {
        val p = syncRoot.resolve(rel)
        Files.createDirectories(p.parent ?: syncRoot)
        RandomAccessFile(p.toFile(), "rw").use { it.setLength(size) }
    }

    private fun writeContentFile(
        rel: String,
        content: ByteArray,
    ) {
        val p = syncRoot.resolve(rel)
        Files.createDirectories(p.parent ?: syncRoot)
        Files.write(p, content)
    }

    private fun invokeFindNullByteStubs(): List<String> {
        val cmd = SweepCommand()
        val m: Method = SweepCommand::class.java.getDeclaredMethod("findNullByteStubs", Path::class.java, StateDatabase::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(cmd, syncRoot, db) as List<String>
    }

    @Test
    fun `empty dir returns no stubs`() {
        assertTrue(invokeFindNullByteStubs().isEmpty())
    }

    @Test
    fun `null-byte file with matching DB entry is flagged`() {
        writeNullFile("a.bin", 100_000)
        db.upsertEntry(entry("/a.bin", 100_000))
        assertEquals(listOf("/a.bin"), invokeFindNullByteStubs())
    }

    @Test
    fun `file with real content is not flagged even if size matches`() {
        writeContentFile("real.txt", ByteArray(1000) { 0x41 })
        db.upsertEntry(entry("/real.txt", 1000))
        assertTrue(invokeFindNullByteStubs().isEmpty())
    }

    @Test
    fun `null-byte file without DB entry is ignored`() {
        writeNullFile("orphan.bin", 100)
        // no upsert — not known to unidrive
        assertTrue(invokeFindNullByteStubs().isEmpty())
    }

    @Test
    fun `null-byte file with mismatched remoteSize is ignored`() {
        writeNullFile("size-mismatch.bin", 500)
        db.upsertEntry(entry("/size-mismatch.bin", 900)) // DB says 900, file is 500
        assertTrue(invokeFindNullByteStubs().isEmpty())
    }

    @Test
    fun `zero-byte local file is not flagged`() {
        writeNullFile("tiny.bin", 0)
        db.upsertEntry(entry("/tiny.bin", 0))
        assertTrue(invokeFindNullByteStubs().isEmpty())
    }

    @Test
    fun `folder entry is not flagged even with matching path`() {
        Files.createDirectories(syncRoot.resolve("a-folder"))
        db.upsertEntry(entry("/a-folder", 0, isFolder = true))
        assertTrue(invokeFindNullByteStubs().isEmpty())
    }

    @Test
    fun `large file with NUL head but content tail is not flagged`() {
        // Invariant: any file with non-NUL content anywhere must not be flagged,
        // regardless of the detector's sampling strategy. UD-812.
        val content = ByteArray(20 * 1024)
        for (i in 8 * 1024 until content.size) content[i] = 0x42
        writeContentFile("partial.bin", content)
        db.upsertEntry(entry("/partial.bin", content.size.toLong()))
        assertTrue(invokeFindNullByteStubs().isEmpty(), "Partial NUL file must not be flagged")
    }

    @Test
    fun `large file with content head but NUL tail is not flagged`() {
        // Invariant: any file with non-NUL content anywhere must not be flagged,
        // regardless of the detector's sampling strategy. UD-812.
        val content = ByteArray(20 * 1024)
        for (i in 0 until 8 * 1024) content[i] = 0x42
        writeContentFile("tailnul.bin", content)
        db.upsertEntry(entry("/tailnul.bin", content.size.toLong()))
        assertTrue(invokeFindNullByteStubs().isEmpty(), "File with content at head must not be flagged")
    }

    @Test
    fun `all-NUL 1MB file is flagged`() {
        writeNullFile("large.bin", 1_000_000)
        db.upsertEntry(entry("/large.bin", 1_000_000))
        assertEquals(listOf("/large.bin"), invokeFindNullByteStubs())
    }

    @Test
    fun `many files — only the null ones are flagged`() {
        writeNullFile("dir1/stub1.jpg", 5000)
        writeNullFile("dir1/stub2.jpg", 7000)
        writeContentFile("dir1/real.jpg", ByteArray(5000) { (it and 0xff).toByte() })
        writeContentFile("dir2/real2.jpg", ByteArray(500) { 1 })

        db.upsertEntry(entry("/dir1/stub1.jpg", 5000))
        db.upsertEntry(entry("/dir1/stub2.jpg", 7000))
        db.upsertEntry(entry("/dir1/real.jpg", 5000))
        db.upsertEntry(entry("/dir2/real2.jpg", 500))

        val flagged = invokeFindNullByteStubs().sorted()
        assertEquals(listOf("/dir1/stub1.jpg", "/dir1/stub2.jpg"), flagged)
    }

    @Test
    fun `help text documents --null-bytes as required scan mode and dry-run-rehydrate as modifiers (UD-244)`() {
        val help = CommandLine(SweepCommand()).usageMessage
        // Command description must flag --null-bytes as the only supported mode so the
        // help block no longer lies about --dry-run / --rehydrate being standalone modes.
        assertTrue(
            help.contains("--null-bytes") && help.contains("only scan mode"),
            "Command description must mention --null-bytes is the only supported scan mode. Got:\n$help",
        )
        // Each modifier flag description must advertise the --null-bytes dependency.
        // Flatten whitespace first because picocli wraps long descriptions across columns.
        val flat = help.replace(Regex("\\s+"), " ")
        assertTrue(
            Regex("""--dry-run[^|]*?requires --null-bytes""").containsMatchIn(flat),
            "--dry-run description must say it requires --null-bytes. Got:\n$help",
        )
        assertTrue(
            Regex("""--rehydrate[^|]*?requires --null-bytes""").containsMatchIn(flat),
            "--rehydrate description must say it requires --null-bytes. Got:\n$help",
        )
    }

    @Test
    fun `trash and versions sidecar dirs are skipped`() {
        writeNullFile(".unidrive-trash/old.bin", 1000)
        writeNullFile(".unidrive-versions/snapshot.bin", 1000)
        db.upsertEntry(entry("/.unidrive-trash/old.bin", 1000))
        db.upsertEntry(entry("/.unidrive-versions/snapshot.bin", 1000))
        assertTrue(invokeFindNullByteStubs().isEmpty())
    }
}
