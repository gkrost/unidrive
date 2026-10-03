package org.krost.unidrive.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #503 / #491: two local siblings whose names are equal after NFC but differ in code points
 * are refused: neither is synced, no churn, one WARN per pass naming both, files untouched.
 * All names are built from escapes so this file stays ASCII.
 */
class LocalScannerNfcCollisionTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var scanner: LocalScanner
    private lateinit var appender: ListAppender<ILoggingEvent>
    private val scannerLogger = LoggerFactory.getLogger(LocalScanner::class.java) as Logger

    private val composed = "café.txt"
    private val decomposed = "café.txt"

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-nfc-test")
        db = StateDatabase(Files.createTempDirectory("unidrive-nfc-db").resolve("state.db"))
        db.initialize()
        scanner = LocalScanner(syncRoot, db)
        appender = ListAppender<ILoggingEvent>().apply { start() }
        scannerLogger.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        scannerLogger.detachAppender(appender)
        db.close()
    }

    private fun warns() = appender.list.filter { it.level == Level.WARN && it.formattedMessage.contains("Local path collision") }

    private fun write(
        rel: String,
        text: String,
    ) {
        val p = syncRoot.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    private fun uploads(changes: Map<String, ChangeState>) = changes.filterValues { it == ChangeState.NEW || it == ChangeState.MODIFIED }

    private fun syncedRow(
        path: String,
        size: Long,
        mtime: Long,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = "h",
        remoteSize = size,
        remoteModified = Instant.now(),
        localMtime = mtime,
        localSize = size,
        isFolder = false,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.now(),
    )

    /** Equivalent of a completed upload: promote pending rows so a second pass sees them as synced. */
    private fun markSynced(changes: Map<String, ChangeState>) {
        for (path in uploads(changes).keys) {
            val f = syncRoot.resolve(path.removePrefix("/"))
            if (!Files.isRegularFile(f)) continue
            db.upsertEntry(syncedRow(path, Files.size(f), Files.getLastModifiedTime(f).toMillis()))
        }
    }

    @Test
    fun `a pair is refused, warned once naming both, and both files stay`() {
        write(composed, "composed")
        write(decomposed, "decomposed")
        val changes = scanner.scan()
        assertTrue(uploads(changes).isEmpty(), "no upload of either member, got $changes")
        assertTrue(db.getAllEntries().isEmpty(), "no pending-upload row either")
        val w = warns()
        assertEquals(1, w.size, "exactly one WARN per pass")
        val msg = w.single().formattedMessage
        assertTrue(msg.contains("/$composed") && msg.contains(decomposed), msg)
        assertTrue(msg.contains("#491") && msg.contains("neither is synced"), msg)
        assertEquals("composed", Files.readString(syncRoot.resolve(composed)))
        assertEquals("decomposed", Files.readString(syncRoot.resolve(decomposed)))
    }

    @Test
    fun `a second pass with nothing changed has no uploads and warns once more`() {
        write(composed, "composed")
        write(decomposed, "decomposed")
        markSynced(scanner.scan())
        appender.list.clear()
        val second = scanner.scan()
        assertTrue(uploads(second).isEmpty(), "no churn: $second")
        assertEquals(1, warns().size)
    }

    @Test
    fun `an existing state row for the key is left alone, even when neither member is NFC`() {
        // Both members non-NFC (angstrom sign vs A + ring): the NFC name itself is not on disk.
        write("Å.txt", "angstrom")
        write("Å.txt", "ring")
        db.upsertEntry(syncedRow("/Å.txt", 8, 1))
        val changes = scanner.scan()
        assertTrue(changes.isEmpty(), "must not report DELETED/MODIFIED for the key: $changes")
        assertNotNull(db.getEntry("/Å.txt"), "the row stays as it was")
    }

    @Test
    fun `a lone decomposed name still syncs`() {
        write(decomposed, "alone")
        val changes = scanner.scan()
        assertEquals(ChangeState.NEW, changes["/$composed"])
        assertTrue(warns().isEmpty())
    }

    @Test
    fun `sigma and final sigma are not a clash`() {
        write("Σ.txt", "big")
        write("ς.txt", "final")
        val changes = scanner.scan()
        assertEquals(setOf("/Σ.txt", "/ς.txt"), uploads(changes).keys)
        assertTrue(warns().isEmpty())
    }

    @Test
    fun `the other documented pairs are refused`() {
        val pairs =
            listOf(
                "Å" to "Å",
                "Å" to "Å",
                "각" to "각",
                "ガ" to "ガ",
                "K" to "K",
            )
        for ((i, p) in pairs.withIndex()) {
            write("d$i/${p.first}.txt", "a")
            write("d$i/${p.second}.txt", "b")
        }
        val changes = scanner.scan()
        assertTrue(uploads(changes).filterValues { true }.keys.none { it.count { c -> c == '/' } > 1 }, "$changes")
        assertEquals(pairs.size, warns().size)
    }

    @Test
    fun `a clashing folder name blocks everything below it`() {
        write("café/inner.txt", "one")
        write("café/other.txt", "two")
        write("plain.txt", "ok")
        val changes = scanner.scan()
        assertEquals(setOf("/plain.txt"), uploads(changes).keys, "$changes")
        assertEquals(1, warns().size)
        assertTrue(Files.exists(syncRoot.resolve("café/inner.txt")))
        assertTrue(Files.exists(syncRoot.resolve("café/other.txt")))
    }
}
