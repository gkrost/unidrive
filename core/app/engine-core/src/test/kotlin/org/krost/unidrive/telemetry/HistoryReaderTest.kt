package org.krost.unidrive.telemetry

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryReaderTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val opened = mutableListOf<TransferHistory>()
    private val readers = mutableListOf<HistoryReader>()
    private val wallMs = AtomicLong(1_700_000_000_000L)

    private fun open(): TransferHistory =
        TransferHistory.open("p1", db, clock = { wallMs.get() }).also { opened += it }

    private fun reader(history: TransferHistory): HistoryReader = history.openReader().also { readers += it }

    // A finished transfer: queued at [queuedAt], done at [finishedAt]; the history's clock is set for each call.
    private fun finished(
        history: TransferHistory,
        path: String,
        queuedAt: Long,
        finishedAt: Long,
        direction: TransferDirection = TransferDirection.UPLOAD,
        result: TransferResult = TransferResult.SUCCESS,
    ) {
        wallMs.set(queuedAt)
        val handle = history.begin(direction, path, bytesTotal = 10)
        wallMs.set(finishedAt)
        handle.finish(result, bytesDone = 10)
    }

    private fun paths(page: HistoryPage) = page.rows.map { it.path }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("history-reader-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        readers.forEach { runCatching { it.close() } }
        opened.forEach { runCatching { it.close() } }
        runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
    }

    @Test
    fun `rows come newest first by finish instant, a running row by its queue instant`() {
        val history = open()
        finished(history, "/old", queuedAt = 1_000, finishedAt = 2_000)
        // Queued early, finished last: it sorts by when it finished.
        finished(history, "/slow", queuedAt = 500, finishedAt = 9_000)
        wallMs.set(5_000)
        history.begin(TransferDirection.UPLOAD, "/running").started()
        assertTrue(history.flush())

        assertEquals(listOf("/slow", "/running", "/old"), paths(reader(history).query(HistoryQuery())))
    }

    @Test
    fun `equal instants are ordered by operation id so the order is total`() {
        val history = open()
        repeat(5) { finished(history, "/f$it", queuedAt = 1_000, finishedAt = 2_000) }
        assertTrue(history.flush())
        val rows = reader(history).query(HistoryQuery()).rows
        assertEquals(rows.map { it.operationId }.sortedDescending(), rows.map { it.operationId })
    }

    @Test
    fun `a cursor pages without gaps or repeats while rows are inserted and finish between pages`() {
        val history = open()
        for (i in 1..10) finished(history, "/f$i", queuedAt = i * 1_000L, finishedAt = i * 1_000L + 10)
        assertTrue(history.flush())
        val reader = reader(history)

        val first = reader.query(HistoryQuery(limit = 4))
        assertEquals(listOf("/f10", "/f9", "/f8", "/f7"), paths(first))
        assertTrue(first.hasMore)

        // Rows arrive between the pages: newer than anything the client has, so they sort ahead of the cursor.
        for (i in 11..14) finished(history, "/f$i", queuedAt = i * 1_000L, finishedAt = i * 1_000L + 10)
        assertTrue(history.flush())

        val second = reader.query(HistoryQuery(limit = 4, after = HistoryCursor.of(first.rows.last())))
        assertEquals(listOf("/f6", "/f5", "/f4", "/f3"), paths(second), "the new rows moved nothing the client has not read")
        val third = reader.query(HistoryQuery(limit = 4, after = HistoryCursor.of(second.rows.last())))
        assertEquals(listOf("/f2", "/f1"), paths(third))
        assertFalse(third.hasMore)
        // The same cursor, after yet more rows, still answers the same page.
        finished(history, "/f15", queuedAt = 15_000, finishedAt = 15_010)
        assertTrue(history.flush())
        assertEquals(paths(second), paths(reader.query(HistoryQuery(limit = 4, after = HistoryCursor.of(first.rows.last())))))
    }

    @Test
    fun `a cursor stays valid after retention removed rows, also the one it names`() {
        val history = open()
        val base = 1_700_000_000_000L
        for (i in 1..6) finished(history, "/f$i", queuedAt = base + i * 1_000L, finishedAt = base + i * 1_000L + 10)
        assertTrue(history.flush())
        val reader = reader(history)
        val first = reader.query(HistoryQuery(limit = 2))
        assertEquals(listOf("/f6", "/f5"), paths(first))

        // Retention (90 days) takes the rows queued before base + 2.5 s: /f1 and /f2.
        wallMs.set(base + java.util.concurrent.TimeUnit.DAYS.toMillis(90) + 2_500)
        history.pruneNow()

        val rest = reader.query(HistoryQuery(limit = 10, after = HistoryCursor.of(first.rows.last())))
        assertEquals(listOf("/f4", "/f3"), paths(rest), "the page after the cursor is what is left, nothing shifted")
        // A cursor naming a row that is gone needs no row to stand on.
        assertEquals(emptyList(), paths(reader.query(HistoryQuery(after = HistoryCursor(base + 2_010, "x")))))
    }

    @Test
    fun `each filter narrows on its own and the filters combine`() {
        val history = open()
        finished(history, "/docs/a.txt", 1_000, 2_000, TransferDirection.UPLOAD, TransferResult.SUCCESS)
        finished(history, "/docs/b.txt", 3_000, 4_000, TransferDirection.DOWNLOAD, TransferResult.FAILED)
        finished(history, "/docs/sub/c.txt", 5_000, 6_000, TransferDirection.UPLOAD, TransferResult.SKIPPED)
        finished(history, "/photos/d.jpg", 7_000, 8_000, TransferDirection.DELETE, TransferResult.SUCCESS)
        finished(history, "/Docs/e.txt", 9_000, 10_000, TransferDirection.UPLOAD, TransferResult.CONFLICT)
        wallMs.set(11_000)
        history.begin(TransferDirection.DOWNLOAD, "/docs/f.txt").started()
        assertTrue(history.flush())
        val r = reader(history)

        assertEquals(
            listOf("/docs/f.txt", "/photos/d.jpg", "/docs/b.txt"),
            paths(r.query(HistoryQuery(directions = setOf(TransferDirection.DOWNLOAD, TransferDirection.DELETE)))),
        )
        assertEquals(listOf("/docs/b.txt"), paths(r.query(HistoryQuery(results = setOf(TransferResult.FAILED)))))
        assertEquals(listOf("/docs/f.txt"), paths(r.query(HistoryQuery(results = setOf(TransferResult.RUNNING)))))
        assertEquals(setOf("/docs/b.txt", "/docs/a.txt"), paths(r.query(HistoryQuery(results = setOf(TransferResult.FAILED, TransferResult.SUCCESS), pathPrefix = "/docs/"))).toSet())
        // A plain prefix, case-sensitive: /Docs/ is another folder, and a prefix is not a LIKE pattern.
        assertEquals(listOf("/docs/f.txt", "/docs/sub/c.txt", "/docs/b.txt", "/docs/a.txt"), paths(r.query(HistoryQuery(pathPrefix = "/docs/"))))
        assertEquals(listOf("/docs/sub/c.txt"), paths(r.query(HistoryQuery(pathPrefix = "/docs/sub"))))
        assertEquals(emptyList(), paths(r.query(HistoryQuery(pathPrefix = "/%"))))
        // from is inclusive, to is exclusive, against the finish instant (the queue instant of the running row).
        assertEquals(listOf("/docs/sub/c.txt", "/docs/b.txt"), paths(r.query(HistoryQuery(fromMs = 4_000, toMs = 7_000))))
        assertEquals(listOf("/docs/a.txt"), paths(r.query(HistoryQuery(toMs = 4_000))))
        assertEquals(
            listOf("/docs/sub/c.txt"),
            paths(r.query(HistoryQuery(directions = setOf(TransferDirection.UPLOAD), pathPrefix = "/docs/", fromMs = 5_000))),
        )
    }

    @Test
    fun `a path prefix counts characters, not bytes or UTF-16 units`() {
        val history = open()
        finished(history, "/😀/emoji.txt", 1_000, 2_000)
        finished(history, "/ä/umlaut.txt", 3_000, 4_000)
        assertTrue(history.flush())
        val r = reader(history)
        assertEquals(listOf("/😀/emoji.txt"), paths(r.query(HistoryQuery(pathPrefix = "/😀/"))))
        assertEquals(listOf("/ä/umlaut.txt"), paths(r.query(HistoryQuery(pathPrefix = "/ä"))))
    }

    @Test
    fun `a page says whether more rows exist from a row it read, not from its size`() {
        val history = open()
        for (i in 1..3) finished(history, "/f$i", i * 1_000L, i * 1_000L + 1)
        assertTrue(history.flush())
        val r = reader(history)
        assertFalse(r.query(HistoryQuery(limit = 3)).hasMore, "exactly a page: nothing follows")
        assertTrue(r.query(HistoryQuery(limit = 2)).hasMore)
        assertEquals(emptyList(), r.query(HistoryQuery(limit = 3, after = HistoryCursor(0, "x"))).rows)
    }

    @Test
    fun `the paging query seeks the time index and sorts nothing`() {
        val history = open()
        finished(history, "/a", 1_000, 2_000)
        assertTrue(history.flush())
        val plan =
            reader(history).explain(
                HistoryQuery(directions = setOf(TransferDirection.UPLOAD), after = HistoryCursor(5_000, "x"), limit = 50),
            ).joinToString(" | ")
        assertTrue("idx_transfers_time" in plan, plan)
        assertFalse("TEMP B-TREE" in plan, plan)
    }

    @Test
    fun `a cursor token round-trips and a foreign one is refused`() {
        val cursor = HistoryCursor(1_700_000_000_123, "0a1b-2c.3d")
        assertEquals(cursor, HistoryCursor.decode(cursor.encode()))
        for (bad in listOf("", "not-a-cursor", "!!!", java.util.Base64.getUrlEncoder().encodeToString("2.1.x".toByteArray()), java.util.Base64.getUrlEncoder().encodeToString("1.nope.x".toByteArray()))) {
            assertNull(HistoryCursor.decode(bad), "'$bad' is not a cursor")
        }
    }

    @Test
    fun `reading goes through its own connection and does not wait for the writer thread`() {
        val history = open()
        finished(history, "/a", 1_000, 2_000)
        assertTrue(history.flush())
        val r = reader(history)
        // The writer has a backlog; a query on the reader's own connection answers regardless.
        repeat(2_000) { finished(history, "/bulk$it", 3_000L + it, 3_001L + it) }
        assertEquals(5, r.query(HistoryQuery(limit = 5)).rows.size)
        assertTrue(history.flush())
        assertEquals(HistoryQuery.MAX_LIMIT, r.query(HistoryQuery(limit = HistoryQuery.MAX_LIMIT)).rows.size)
    }

    @Test
    fun `a missing file is a read error, never an empty page`() {
        val missing = HistoryReader(dir.resolve("nothing-here.db")).also { readers += it }
        val failure = runCatching { missing.query(HistoryQuery()) }.exceptionOrNull()
        assertTrue(failure is java.sql.SQLException, "got $failure")
    }

    @Test
    fun `the tail numbers committed rows in order and announces none that were dropped`() {
        val history = open()
        val seen = CopyOnWriteArrayList<Pair<Long, TransferRow>>()
        history.addTailListener { seq, row -> seen += seq to row }
        assertEquals(0L, history.tailSeq)

        finished(history, "/a", 1_000, 2_000)
        finished(history, "/b", 3_000, 4_000)
        assertTrue(history.flush())

        assertEquals(listOf("/a", "/b"), seen.map { it.second.path })
        assertEquals(seen.map { it.first }, seen.map { it.first }.sorted(), "rising")
        assertEquals(seen.size.toLong(), history.tailSeq)
        assertEquals(seen.last().first, history.tailSeq)
        // A row announced is a row a reader finds.
        assertNotNull(reader(history).query(HistoryQuery(pathPrefix = "/b")).rows.singleOrNull())
    }

    @Test
    fun `a tail listener that throws loses nothing for the others or the file`() {
        val history = open()
        val seen = CopyOnWriteArrayList<String>()
        history.addTailListener { _, _ -> error("boom") }
        history.addTailListener { _, row -> seen += row.path }
        finished(history, "/a", 1_000, 2_000)
        assertTrue(history.flush())
        assertEquals(listOf("/a"), seen)
        assertEquals(1, reader(history).query(HistoryQuery()).rows.size)
    }
}
