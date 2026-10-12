package org.krost.unidrive.telemetry

import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestAttempt
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
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

class RequestMetricsRecorderTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val opened = mutableListOf<TransferHistory>()

    private val wallMs = AtomicLong(T0)

    private fun open(
        config: HistoryConfig = HistoryConfig(),
        afterOpen: (Connection) -> Unit = {},
    ): TransferHistory =
        TransferHistory.open("p1", db, config, clock = { wallMs.get() }, afterOpen = afterOpen).also { opened += it }

    @Suppress("LongParameterList")
    private fun attempt(
        at: Long = T0,
        endpoint: String = "GET /drive/folders/content/{id}/files",
        outcome: AttemptOutcome = AttemptOutcome.OK,
        headersMs: Long? = 20,
        totalMs: Long = 30,
        bytesUp: Long? = null,
        bytesDown: Long? = null,
        retryNo: Int = 0,
        throttled: Boolean = false,
    ) = RequestAttempt(
        startedAtMs = at,
        provider = "internxt",
        endpointClass = endpoint,
        method = endpoint.substringBefore(' '),
        outcome = outcome,
        headersMs = headersMs,
        totalMs = totalMs,
        bytesUp = bytesUp,
        bytesDown = bytesDown,
        retryNo = retryNo,
        throttled = throttled,
    )

    private fun query(
        history: TransferHistory,
        from: Long = T0 - HOUR,
        to: Long = T0 + HOUR,
        endpoint: String? = null,
    ) = history.requestMetrics().query(RollupQuery(from, to, endpoint))

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("request-metrics-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        runCatching {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
        }
    }

    // ---- roll-up math ---------------------------------------------------------------------------------------------

    @Test
    fun `nearest-rank percentiles`() {
        val ten = (1L..10L).toList()
        assertEquals(5L, Rollups.percentile(ten, 0.50))
        assertEquals(10L, Rollups.percentile(ten, 0.95))
        assertEquals(7L, Rollups.percentile(listOf(7L), 0.50))
        assertEquals(7L, Rollups.percentile(listOf(7L), 0.95))
        val hundred = (1L..100L).toList()
        assertEquals(50L, Rollups.percentile(hundred, 0.50))
        assertEquals(95L, Rollups.percentile(hundred, 0.95))
        assertEquals(1L, Rollups.percentile(listOf(1L, 2L), 0.50))
        assertEquals(2L, Rollups.percentile(listOf(1L, 2L), 0.95))
    }

    @Test
    fun `a roll-up counts outcomes, percentiles and sums bytes only where they are known`() {
        val minute = minuteOf(T0)
        val a = "GET /a/{id}"
        val b = "PUT /b"
        val rows =
            Rollups.compute(
                minute,
                listOf(
                    attempt(endpoint = a, totalMs = 100, headersMs = 10, bytesDown = 500),
                    attempt(endpoint = a, totalMs = 300, headersMs = 30, bytesDown = 700),
                    attempt(endpoint = a, totalMs = 200, headersMs = 20, bytesDown = null),
                    attempt(endpoint = a, outcome = AttemptOutcome.THROTTLED, throttled = true, totalMs = 50, headersMs = 40, retryNo = 1),
                    attempt(endpoint = a, outcome = AttemptOutcome.RESET, headersMs = null, totalMs = 400, retryNo = 2),
                    attempt(endpoint = b, totalMs = 9, headersMs = 9, bytesUp = 1000),
                ),
            )
        val ra = rows.single { it.endpointClass == a }
        assertEquals(5, ra.attempts)
        assertEquals(mapOf("2xx" to 3, "429" to 1, "reset" to 1), ra.outcomes)
        assertEquals(5, ra.outcomes.values.sum())
        assertEquals(1, ra.throttled)
        assertEquals(2, ra.retries)
        // Header times known for 4 of 5 attempts: 10, 20, 30, 40.
        assertEquals(20L, ra.headersP50Ms)
        assertEquals(40L, ra.headersP95Ms)
        // Total times of all 5: 50, 100, 200, 300, 400.
        assertEquals(200L, ra.totalP50Ms)
        assertEquals(400L, ra.totalP95Ms)
        assertEquals(null, ra.bytesUp, "no attempt of this class knew its upload size: unknown, not 0")
        assertEquals(1200L, ra.bytesDown, "the sum of the two that knew")

        val rb = rows.single { it.endpointClass == b }
        assertEquals(1000L, rb.bytesUp)
        assertNull(rb.bytesDown)
        assertEquals(minute, rb.minuteMs)
    }

    @Test
    fun `a minute where no attempt got a response has no header percentile`() {
        val row = Rollups.compute(minuteOf(T0), listOf(attempt(outcome = AttemptOutcome.TIMEOUT, headersMs = null, totalMs = 60_000))).single()
        assertNull(row.headersP50Ms)
        assertNull(row.headersP95Ms)
        assertEquals(60_000L, row.totalP50Ms)
    }

    // ---- the recorder over the file --------------------------------------------------------------------------------

    @Test
    fun `attempts of two minutes and two classes read back as four roll-ups, oldest first`() {
        val history = open()
        val metrics = history.requestMetrics()
        val m0 = minuteOf(T0)
        metrics.record(attempt(at = m0 + 1_000, totalMs = 10))
        metrics.record(attempt(at = m0 + 2_000, totalMs = 30))
        metrics.record(attempt(at = m0 + 3_000, endpoint = "PUT /drive/files/{id}", outcome = AttemptOutcome.THROTTLED, throttled = true))
        metrics.record(attempt(at = m0 + MINUTE + 1_000, totalMs = 70))
        metrics.record(attempt(at = m0 + MINUTE + 2_000, endpoint = "PUT /drive/files/{id}"))

        val result = query(history)
        assertEquals(listOf(m0, m0, m0 + MINUTE, m0 + MINUTE), result.rows.map { it.minuteMs })
        assertEquals(listOf(2, 1, 1, 1), result.rows.map { it.attempts })
        assertEquals(listOf("GET /drive/folders/content/{id}/files", "PUT /drive/files/{id}"), result.endpointClasses)
        assertEquals(m0, result.oldestMinuteMs)
        assertFalse(result.truncated)
        assertTrue(result.rows.first().provider == "internxt")
    }

    @Test
    fun `the open current minute is rolled up on demand`() {
        val history = open(HistoryConfig())
        val metrics = history.requestMetrics(RequestMetricsConfig(rollupIntervalMs = 3_600_000))
        metrics.record(attempt(at = T0))
        val result = metrics.query(RollupQuery(T0 - HOUR, T0 + HOUR))
        assertEquals(1, result.rows.single().attempts)
    }

    @Test
    fun `no data is an empty series and no oldest minute, never zeros`() {
        val history = open()
        val result = query(history)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.endpointClasses.isEmpty())
        assertNull(result.oldestMinuteMs)
        assertFalse(result.truncated)
        // A range outside the data is also empty, and says where the data begins.
        history.requestMetrics().record(attempt(at = T0))
        val later = history.requestMetrics().query(RollupQuery(T0 + DAY, T0 + 2 * DAY))
        assertTrue(later.rows.isEmpty())
        assertEquals(minuteOf(T0), later.oldestMinuteMs)
    }

    @Test
    fun `the endpoint filter and the limit apply and truncation is reported`() {
        val history = open()
        val metrics = history.requestMetrics()
        val m0 = minuteOf(T0)
        repeat(5) { i -> metrics.record(attempt(at = m0 + i * MINUTE, endpoint = "GET /x")) }
        metrics.record(attempt(at = m0, endpoint = "GET /y"))

        val onlyX = metrics.query(RollupQuery(m0, m0 + HOUR, endpointClass = "GET /x", limit = 3))
        assertEquals(3, onlyX.rows.size)
        assertTrue(onlyX.rows.all { it.endpointClass == "GET /x" })
        assertTrue(onlyX.truncated)
        assertEquals(listOf("GET /x", "GET /y"), onlyX.endpointClasses, "the class list ignores the filter")
        val all = metrics.query(RollupQuery(m0, m0 + HOUR, endpointClass = "GET /x"))
        assertFalse(all.truncated)
        assertEquals(5, all.rows.size)
    }

    @Test
    fun `a file at schema step 1 gains the attempt tables and keeps its transfers`() {
        open().also {
            it.begin(TransferDirection.UPLOAD, "/old/row.bin").finish(TransferResult.SUCCESS, bytesDone = 3)
            assertTrue(it.flush())
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.execute("DROP TABLE request_attempts")
                st.execute("DROP TABLE request_rollups")
                st.execute("PRAGMA user_version = 1")
            }
        }
        val history = open()
        assertEquals(listOf("/old/row.bin"), history.rows().map { it.path })
        history.requestMetrics().record(attempt())
        assertEquals(1, query(history).rows.size)
        history.close()
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use {
                    it.next()
                    assertEquals(TelemetryStore.SCHEMA_VERSION, it.getInt(1))
                }
            }
        }
        assertEquals(2, TelemetryStore.SCHEMA_VERSION)
    }

    @Test
    fun `attempts written before a crash are rolled up at the next open`() {
        val first = open()
        val metrics = first.requestMetrics(RequestMetricsConfig(rollupIntervalMs = 3_600_000))
        repeat(3) { metrics.record(attempt(at = T0 + it)) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (metrics.stats.written < 3 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(3L, metrics.stats.written)
        first.abandon() // the process dies after the write and before any roll-up

        val second = open()
        assertEquals(3, query(second).rows.single().attempts)
    }

    @Test
    fun `raw rows age out sooner than the roll-ups and roll-ups age out last`() {
        val history = open()
        val metrics = history.requestMetrics(RequestMetricsConfig(rawRetentionHours = 24, rollupRetentionDays = 30))
        val old = T0 - 25 * HOUR // past the raw window, inside the roll-up window
        val ancient = T0 - 31 * DAY // past both
        metrics.record(attempt(at = old))
        metrics.record(attempt(at = ancient))
        metrics.record(attempt(at = T0))
        assertTrue(metrics.flush())
        assertEquals(3, history.attemptCount())

        assertTrue(metrics.pruneNow())
        assertEquals(1, history.attemptCount(), "only the attempt inside the raw window is kept")
        val minutes = metrics.query(RollupQuery(T0 - 40 * DAY, T0 + HOUR)).rows.map { it.minuteMs }
        assertEquals(listOf(minuteOf(old), minuteOf(T0)), minutes, "the roll-up of the old minute outlives its raw row; the ancient one is gone")
    }

    @Test
    fun `the byte budget is shared and the metrics give way before the transfers`() {
        val cap = 512L * 1024
        val history = open(HistoryConfig(maxBytes = cap, batchSize = 200))
        val pad = "z".repeat(60)
        repeat(300) { i -> history.begin(TransferDirection.UPLOAD, "/keep/$pad/f$i.bin").finish(TransferResult.SUCCESS, bytesDone = 1) }
        assertTrue(history.flush())
        val metrics = history.requestMetrics(RequestMetricsConfig(batchSize = 500, queueCapacity = 100_000))
        repeat(20_000) { i ->
            metrics.record(attempt(at = T0 - i * 1_000L, endpoint = "GET /e/{id}", totalMs = 5L + i % 50))
        }
        assertTrue(metrics.flush(60_000))
        assertEquals(0L, metrics.stats.dropped)
        assertTrue(history.fileBytes() > cap, "the fixture must exceed the budget first (${history.fileBytes()} bytes)")

        history.pruneNow() // the history's own retention, which shares the budget
        assertTrue(history.fileBytes() <= cap, "file is ${history.fileBytes()} bytes, budget $cap")
        assertEquals(300, history.rows().size, "no transfer row is lost while metrics can still give way")
        assertTrue(history.attemptCount() < 20_000, "raw attempts went first")
    }

    @Test
    fun `a full queue drops and counts the attempt and never blocks the caller`() {
        val history = open()
        val metrics = history.requestMetrics(RequestMetricsConfig(queueCapacity = 16, batchSize = 4))
        val total = 50_000
        repeat(total) { i -> metrics.record(attempt(at = T0 + i)) }
        assertTrue(metrics.flush(120_000))
        val stats = metrics.stats
        assertTrue(stats.dropped > 0, "a 16-attempt queue cannot take a 50k burst")
        assertEquals(total.toLong(), stats.written + stats.dropped)
        assertTrue(stats.incomplete)
    }

    @Test
    fun `a full disk drops the attempts and the recorder survives`() {
        val history =
            open { conn ->
                conn.createStatement().use { st ->
                    val pages = st.executeQuery("PRAGMA page_count").use { it.next(); it.getLong(1) }
                    st.execute("PRAGMA max_page_count = ${pages + 30}")
                }
            }
        val metrics = history.requestMetrics(RequestMetricsConfig(batchSize = 50))
        repeat(5_000) { i -> metrics.record(attempt(at = T0 + i, endpoint = "GET /full/" + "y".repeat(100))) }
        assertTrue(metrics.flush())
        assertTrue(metrics.stats.dropped > 0, "attempts past the full disk are dropped: ${metrics.stats}")
        assertTrue(metrics.stats.incomplete)
        metrics.record(attempt())
        assertTrue(metrics.flush(), "the writer still answers")
    }

    @Test
    fun `an attempt table row holds a class and numbers and nothing that names content`() {
        val history = open()
        val metrics = history.requestMetrics()
        metrics.record(attempt(bytesUp = 10, bytesDown = 20))
        assertTrue(metrics.flush())
        history.close()
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM request_attempts").use { rs ->
                    val meta = rs.metaData
                    val columns = (1..meta.columnCount).map { meta.getColumnName(it) }
                    assertEquals(
                        listOf(
                            "id", "ts", "profile", "provider", "endpoint_class", "method", "outcome", "headers_ms", "total_ms",
                            "bytes_up", "bytes_down", "retry_no", "throttled",
                        ),
                        columns,
                    )
                    assertTrue(rs.next())
                    assertEquals("p1", rs.getString("profile"))
                    assertNotNull(rs.getString("endpoint_class"))
                }
            }
        }
    }

    companion object {
        private const val T0 = 1_700_000_000_000L
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
    }
}
