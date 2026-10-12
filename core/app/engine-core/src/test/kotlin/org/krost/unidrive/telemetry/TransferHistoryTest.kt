package org.krost.unidrive.telemetry

import org.krost.unidrive.io.OwnerOnly
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
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

class TransferHistoryTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val opened = mutableListOf<TransferHistory>()

    // A hand-driven clock: wall milliseconds and the monotonic nanoseconds move together.
    private val wallMs = AtomicLong(1_700_000_000_000L)
    private val nanos = AtomicLong(0L)

    private fun advance(ms: Long) {
        wallMs.addAndGet(ms)
        nanos.addAndGet(ms * 1_000_000L)
    }

    private fun open(
        config: HistoryConfig = HistoryConfig(),
        afterOpen: (Connection) -> Unit = {},
    ): TransferHistory =
        TransferHistory
            .open("p1", db, config, clock = { wallMs.get() }, nanoClock = { nanos.get() }, afterOpen = afterOpen)
            .also { opened += it }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("telemetry-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        runCatching {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
        }
    }

    @Test
    fun `a row per direction keeps queue wait and transfer time apart`() {
        val history = open()
        val upload = history.begin(TransferDirection.UPLOAD, "/a/up.bin", bytesTotal = 1000)
        advance(300) // waited for a permit
        upload.started()
        advance(700) // transferred
        upload.finish(TransferResult.SUCCESS, bytesDone = 1000, newHash = "h1")

        val download = history.begin(TransferDirection.DOWNLOAD, "/a/down.bin", bytesTotal = 50)
        download.started()
        advance(40)
        download.finish(TransferResult.SUCCESS, bytesDone = 50)

        history.emit("Delete", "/a/gone.bin", "success", 5, "h0", null)
        history.emit("Move", "/a/to.bin", "success", null, null, null)
        history.emit("CreateRemoteFolder", "/a/dir", "success", null, null, null)
        assertTrue(history.flush())

        val byPath = history.rows().associateBy { it.path }
        assertEquals(
            setOf(
                TransferDirection.UPLOAD,
                TransferDirection.DOWNLOAD,
                TransferDirection.DELETE,
                TransferDirection.RENAME,
                TransferDirection.MKDIR,
            ),
            byPath.values.map { it.direction }.toSet(),
        )
        val up = byPath.getValue("/a/up.bin")
        assertEquals("p1", up.profile)
        assertEquals(TransferResult.SUCCESS, up.result)
        assertEquals(300L, up.queueWaitMs)
        assertEquals(700L, up.transferMs)
        assertEquals(up.queuedAt + 300, up.startedAt)
        assertEquals(up.queuedAt + 1000, up.finishedAt)
        assertEquals(1000L, up.bytesTotal)
        assertEquals(1000L, up.bytesDone)
        assertEquals(1, up.attempts)
        assertEquals("h1", up.newHash)
        assertEquals(0L, byPath.getValue("/a/down.bin").queueWaitMs)
        assertEquals(40L, byPath.getValue("/a/down.bin").transferMs)

        // A mutation reported through the AuditSink alone knows no timing: unknown stays unknown.
        val deleted = byPath.getValue("/a/gone.bin")
        assertNull(deleted.startedAt)
        assertNull(deleted.queueWaitMs)
        assertNull(deleted.transferMs)
        assertNull(deleted.attempts)
        assertNotNull(deleted.finishedAt)
        assertEquals(5L, deleted.bytesTotal)
        assertEquals("h0", deleted.oldHash)
    }

    @Test
    fun `a transfer refused before any request made no attempt and a conflict is its own result`() {
        val history = open()
        val refused = history.begin(TransferDirection.UPLOAD, "/r/refused.bin")
        refused.finish(TransferResult.FAILED, errorClass = "refused_earlier", attempted = false)
        val conflicted = history.begin(TransferDirection.UPLOAD, "/r/conflict.bin")
        assertFalse(conflicted.hasStarted)
        conflicted.started()
        assertTrue(conflicted.hasStarted)
        conflicted.finish(TransferResult.CONFLICT, errorClass = "RemoteConflictException")
        assertTrue(history.flush())

        val byPath = history.rows().associateBy { it.path }
        assertEquals(0, byPath.getValue("/r/refused.bin").attempts)
        assertNull(byPath.getValue("/r/refused.bin").startedAt)
        assertEquals(TransferResult.CONFLICT, byPath.getValue("/r/conflict.bin").result)
        assertEquals(1, byPath.getValue("/r/conflict.bin").attempts)
        assertEquals(TransferResult.CONFLICT, TransferResult.ofWire("conflict"))
    }

    @Test
    fun `a failed attempt followed by a success is one row with two attempts`() {
        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/r/retry.bin", bytesTotal = 10)
        handle.started()
        advance(10)
        handle.attemptFailed("SocketTimeoutException", "timed out")
        advance(10)
        handle.finish(TransferResult.SUCCESS, bytesDone = 10)
        assertTrue(history.flush())

        val rows = history.rows().filter { it.path == "/r/retry.bin" }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(TransferResult.SUCCESS, row.result)
        assertEquals(2, row.attempts)
        // The last failed attempt stays visible on the row that needed the retry.
        assertEquals("SocketTimeoutException", row.errorClass)
    }

    @Test
    fun `a failed transfer keeps its error class and counts its attempt`() {
        val history = open()
        val handle = history.begin(TransferDirection.DOWNLOAD, "/r/bad.bin")
        handle.started()
        handle.finish(TransferResult.FAILED, errorClass = "IOException", message = "disk gone")
        history.emit("Upload", "/r/bad2.bin", "failed:IllegalStateException: boom", 3, null, null)
        history.emit("Upload", "/r/same.bin", "skipped:unchanged", 3, null, null)
        assertTrue(history.flush())

        val byPath = history.rows().associateBy { it.path }
        assertEquals(TransferResult.FAILED, byPath.getValue("/r/bad.bin").result)
        assertEquals(1, byPath.getValue("/r/bad.bin").attempts)
        assertEquals("IOException", byPath.getValue("/r/bad.bin").errorClass)
        assertEquals(TransferResult.FAILED, byPath.getValue("/r/bad2.bin").result)
        assertEquals("IllegalStateException", byPath.getValue("/r/bad2.bin").errorClass)
        assertEquals("boom", byPath.getValue("/r/bad2.bin").errorMessage)
        assertEquals(TransferResult.SKIPPED, byPath.getValue("/r/same.bin").result)
    }

    @Test
    fun `retention prunes rows older than the age limit and keeps a running one`() {
        val history = open(HistoryConfig(maxAgeDays = 90))
        val day = 24L * 3600 * 1000
        advance(-100 * day)
        history.begin(TransferDirection.UPLOAD, "/old/done.bin").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        history.begin(TransferDirection.UPLOAD, "/old/still-running.bin").started()
        advance(50 * day)
        history.begin(TransferDirection.UPLOAD, "/mid/done.bin").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        advance(50 * day) // /old is now 100 days old, /mid 50
        history.begin(TransferDirection.UPLOAD, "/new/done.bin").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        assertTrue(history.flush())

        history.pruneNow()
        val paths = history.rows().map { it.path }.toSet()
        assertFalse("/old/done.bin" in paths, "a row past 90 days is pruned")
        assertTrue("/old/still-running.bin" in paths, "a transfer in flight is never pruned")
        assertTrue("/mid/done.bin" in paths)
        assertTrue("/new/done.bin" in paths)
    }

    @Test
    fun `retention prunes the oldest rows when the file is over the size limit`() {
        val cap = 256L * 1024
        val history = open(HistoryConfig(maxBytes = cap, batchSize = 200))
        val pad = "x".repeat(150)
        repeat(6_000) { i ->
            history.begin(TransferDirection.UPLOAD, "/size/$pad/file-$i.bin").also {
                it.started()
                advance(1)
                it.finish(TransferResult.SUCCESS, bytesDone = 1)
            }
        }
        assertTrue(history.flush())
        assertTrue(history.fileBytes() > cap, "the fixture must exceed the cap first")

        history.pruneNow()
        assertTrue(history.fileBytes() <= cap, "file is ${history.fileBytes()} bytes, cap $cap")
        val paths = history.rows().map { it.path }
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.any { it.endsWith("file-5999.bin") }, "the newest row survives")
        assertFalse(paths.any { it.endsWith("file-0.bin") }, "the oldest row goes first")
    }

    @Test
    fun `a row started and never finished becomes interrupted at the next open`() {
        val first = open()
        first.begin(TransferDirection.UPLOAD, "/crash/done.bin").also {
            it.started()
            it.finish(TransferResult.SUCCESS)
        }
        val inFlight = first.begin(TransferDirection.UPLOAD, "/crash/inflight.bin", bytesTotal = 99)
        advance(5)
        inFlight.started()
        advance(5)
        inFlight.attemptFailed("IOException", "reset")
        assertTrue(first.flush())
        first.abandon() // the process dies here: no finish, no clean close

        val second = open()
        val byPath = second.rows().associateBy { it.path }
        assertEquals(TransferResult.SUCCESS, byPath.getValue("/crash/done.bin").result)
        val row = byPath.getValue("/crash/inflight.bin")
        assertEquals(TransferResult.INTERRUPTED, row.result)
        assertNull(row.finishedAt)
        assertNull(row.transferMs)
        assertEquals(1, row.attempts)
        assertEquals(99L, row.bytesTotal)
        assertTrue(byPath.values.none { it.result == TransferResult.RUNNING })
    }

    @Test
    fun `a burst of 50000 rows goes through the queue without dropping`() {
        val total = 50_000
        val history = open(HistoryConfig(queueCapacity = 4 * total, batchSize = 1000))
        val produceStart = System.nanoTime()
        repeat(total) { i ->
            val handle = history.begin(TransferDirection.UPLOAD, "/burst/f$i.bin", bytesTotal = 10)
            handle.started()
            handle.finish(TransferResult.SUCCESS, bytesDone = 10)
        }
        val produceMs = (System.nanoTime() - produceStart) / 1_000_000
        val drainStart = System.nanoTime()
        assertTrue(history.flush(120_000))
        val drainMs = (System.nanoTime() - drainStart) / 1_000_000

        println("BURST rows=$total produce_ms=$produceMs drain_ms=$drainMs written=${history.stats.written}")
        assertEquals(0L, history.stats.dropped)
        assertTrue(history.stats.written >= total, "every row is written at least once")
        assertEquals(total, history.rows().size)
        assertTrue(history.rows().all { it.result == TransferResult.SUCCESS })
    }

    @Test
    fun `a full queue drops and counts the row and never blocks the caller`() {
        val total = 50_000
        val history = open(HistoryConfig(queueCapacity = 16, batchSize = 4))
        repeat(total) { i -> history.emit("Upload", "/drop/f$i.bin", "success", 1, null, null) }
        assertTrue(history.flush(120_000))

        val stats = history.stats
        assertTrue(stats.dropped > 0, "a 16-row queue cannot take a 50k burst")
        assertEquals(total.toLong(), stats.written + stats.dropped)
        assertTrue(stats.incomplete)
        history.close()
        // The flag outlives the process: a reader of the file can tell the history has a gap.
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT value FROM meta WHERE key = 'incomplete_since'").use { rs ->
                    assertTrue(rs.next())
                }
            }
        }
    }

    @Test
    fun `a full disk drops the rows, counts them and never fails the transfer`() {
        val history =
            open(HistoryConfig(batchSize = 50)) { conn ->
                // Room for the schema and a few rows, then SQLITE_FULL: a disk that filled up.
                conn.createStatement().use { st ->
                    val pages =
                        st.executeQuery("PRAGMA page_count").use {
                            it.next()
                            it.getLong(1)
                        }
                    st.execute("PRAGMA max_page_count = ${pages + 30}")
                }
            }
        val pad = "y".repeat(200)
        repeat(2_000) { i ->
            // Neither begin, started nor finish may throw.
            val handle = history.begin(TransferDirection.UPLOAD, "/full/$pad/f$i.bin", bytesTotal = 1)
            handle.started()
            handle.finish(TransferResult.SUCCESS, bytesDone = 1)
        }
        assertTrue(history.flush())

        val stats = history.stats
        assertTrue(stats.dropped > 0, "rows past the full disk are dropped: $stats")
        assertTrue(stats.incomplete)
        assertTrue(stats.written > 0, "rows before the disk filled up are kept: $stats")
        // The writer survived and still answers.
        history.begin(TransferDirection.UPLOAD, "/full/after.bin").finish(TransferResult.SKIPPED)
        assertTrue(history.flush())
    }

    @Test
    fun `no url or token reaches the stored message`() {
        val history = open()
        val handle = history.begin(TransferDirection.UPLOAD, "/secret/a.bin")
        handle.started()
        handle.attemptFailed(
            "ClientRequestException",
            "GET https://api.example.com/v1/files/abc?access_token=SECRET123&sig=ZZZ failed",
        )
        handle.finish(
            TransferResult.FAILED,
            errorClass = "IOException",
            message = "Authorization: Bearer eyJhbGciOi.eyJzdWIiOi.abcdef123456 rejected; password=hunter2",
        )
        history.emit("Upload", "/secret/b.bin", "failed:HttpException: 403 at https://gateway.example/x?token=TTT", 1, null, null)
        assertTrue(history.flush())

        val all = history.rows().flatMap { listOfNotNull(it.errorClass, it.errorMessage) }.joinToString(" | ")
        for (needle in listOf("http", "SECRET123", "ZZZ", "eyJ", "hunter2", "TTT", "Bearer eyJ")) {
            assertFalse(all.contains(needle), "stored text must not contain '$needle': $all")
        }
        assertTrue(history.rows().any { it.errorMessage?.contains("rejected") == true }, "the message itself is kept")
    }

    @Test
    fun `telemetry db is its own file and owner only`() {
        val history = open()
        history.begin(TransferDirection.UPLOAD, "/perm/a.bin").finish(TransferResult.SKIPPED)
        assertTrue(history.flush())

        assertEquals("telemetry.db", db.fileName.toString())
        assertTrue(Files.isRegularFile(db))
        assertFalse(Files.exists(dir.resolve("state.db")))
        assertEquals(OwnerOnly.Outcome.Unchanged, OwnerOnly.restrictFile(db))
    }
}
