package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.telemetry.TransferDirection
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.telemetry.TransferResult
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
import kotlin.test.assertTrue

/** The history read verbs without a socket: parsing, paging, the byte cap, the tail and its `lost` marker, and the unavailable replies. */
class HistoryRpcHandlerTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val wallMs = AtomicLong(1_700_000_000_000L)
    private var history: TransferHistory? = null
    private var handler: HistoryRpcHandler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("history-rpc-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        runCatching { handler?.close() }
        runCatching { history?.close() }
        runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
    }

    private fun open(capacity: Int = HistoryRpcHandler.SUBSCRIBER_QUEUE_CAPACITY): HistoryRpcHandler {
        val h = TransferHistory.open("p1", db, clock = { wallMs.get() }).also { history = it }
        return HistoryRpcHandler(h, HistoryRpcHandler.OPEN_FAILED, subscriberCapacity = capacity).also { handler = it }
    }

    private fun record(
        path: String,
        direction: TransferDirection = TransferDirection.UPLOAD,
        result: TransferResult = TransferResult.SUCCESS,
    ) {
        wallMs.addAndGet(1)
        val handle = history!!.begin(direction, path, bytesTotal = 10)
        wallMs.addAndGet(1)
        handle.finish(result, bytesDone = 10)
    }

    private fun obj(reply: String) = Json.parseToJsonElement(reply).jsonObject

    @Test
    fun `a row carries the public fields only and a value that was not measured is null`() = runBlocking {
        val h = open()
        wallMs.set(5_000)
        val handle = history!!.begin(TransferDirection.DOWNLOAD, "/a.txt")
        handle.started()
        assertTrue(history!!.flush())
        h.start(scope) { _, _ -> true }

        val row = obj(h.list("""{"verb":"history.list"}""")).getValue("rows").jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "operation_id", "direction", "path", "queued_at_ms", "started_at_ms", "finished_at_ms", "queue_wait_ms", "transfer_ms",
                "bytes_total", "bytes_done", "result", "error_class", "error_message", "attempts",
            ),
            row.keys,
            "no profile, no hashes, no internal key",
        )
        assertEquals("running", row.getValue("result").jsonPrimitive.content)
        for (unmeasured in listOf("finished_at_ms", "transfer_ms", "bytes_total", "bytes_done", "error_class", "error_message", "attempts")) {
            assertEquals(JsonNull, row.getValue(unmeasured), "$unmeasured: unknown stays null, never 0")
        }
    }

    @Test
    fun `an empty history is an empty list with no next cursor, flagged complete`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        val reply = obj(h.list("""{"verb":"history.list"}"""))
        assertEquals(0, reply.getValue("rows").jsonArray.size)
        assertEquals(JsonNull, reply.getValue("next_cursor"))
        assertFalse(reply.getValue("incomplete").jsonPrimitive.boolean)
        assertEquals(0L, reply.getValue("tail_seq").jsonPrimitive.long)
    }

    @Test
    fun `a limit over the maximum is the maximum and the cursor carries on`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        repeat(520) { record("/f$it") }
        assertTrue(history!!.flush())

        val first = obj(h.list("""{"verb":"history.list","limit":100000}"""))
        assertEquals(500, first.getValue("rows").jsonArray.size)
        val cursor = first.getValue("next_cursor").jsonPrimitive.content
        val second = obj(h.list("""{"verb":"history.list","limit":100000,"cursor":"$cursor"}"""))
        assertEquals(20, second.getValue("rows").jsonArray.size)
        assertEquals(JsonNull, second.getValue("next_cursor"))
        val ids = (first.getValue("rows").jsonArray + second.getValue("rows").jsonArray).map { it.jsonObject.getValue("operation_id").jsonPrimitive.content }
        assertEquals(520, ids.toSet().size, "every row once")
    }

    @Test
    fun `paging stays whole while rows arrive between the pages`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        repeat(30) { record("/f$it") }
        assertTrue(history!!.flush())

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var arrivals = 0
        do {
            val request = if (cursor == null) """{"verb":"history.list","limit":7}""" else """{"verb":"history.list","limit":7,"cursor":"$cursor"}"""
            val page = obj(h.list(request))
            page.getValue("rows").jsonArray.forEach { seen += it.jsonObject.getValue("path").jsonPrimitive.content }
            cursor = (page.getValue("next_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content
            // New transfers land between two pages.
            repeat(3) { record("/late${arrivals++}") }
            assertTrue(history!!.flush())
        } while (cursor != null)

        assertEquals((0 until 30).map { "/f${29 - it}" }, seen, "the 30 rows that existed, newest first, none twice, none missed")
    }

    @Test
    fun `a reply stays under the byte cap and the next cursor loses nothing`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        val longName = "x".repeat(3_000)
        repeat(300) { record("/$it/$longName") }
        assertTrue(history!!.flush())

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val request = if (cursor == null) """{"verb":"history.list","limit":500}""" else """{"verb":"history.list","limit":500,"cursor":"$cursor"}"""
            val reply = h.list(request)
            assertTrue(reply.length < HistoryRpcHandler.MAX_REPLY_BYTES + 8_000, "a page of ${reply.length} chars")
            val page = obj(reply)
            page.getValue("rows").jsonArray.forEach { seen += it.jsonObject.getValue("operation_id").jsonPrimitive.content }
            cursor = (page.getValue("next_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content
            pages++
        } while (cursor != null)

        assertTrue(pages > 1, "300 rows of 3 KB do not fit one reply")
        assertEquals(300, seen.toSet().size)
        assertEquals(300, seen.size)
    }

    @Test
    fun `a request that cannot be read is refused with a token and changes nothing`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        assertEquals("""{"ok":false,"error":"invalid_request"}""", h.list("""[1,2]"""))
        assertEquals("""{"ok":false,"error":"invalid_request"}""", h.list("""not json"""))
        assertEquals("""{"ok":false,"error":"invalid_limit"}""", h.list("""{"limit":-3}"""))
        assertEquals("""{"ok":false,"error":"invalid_limit"}""", h.list("""{"limit":1.5}"""))
        assertEquals("""{"ok":false,"error":"invalid_cursor"}""", h.list("""{"cursor":7}"""))
        assertEquals("""{"ok":false,"error":"invalid_direction"}""", h.list("""{"filters":{"direction":["upload",3]}}"""))
        assertEquals("""{"ok":false,"error":"invalid_result"}""", h.list("""{"filters":{"result":"done"}}"""))
        assertEquals("""{"ok":false,"error":"invalid_time_range"}""", h.list("""{"filters":{"from_ms":"yesterday"}}"""))
        // Unknown members are ignored: older and newer clients meet.
        assertEquals("true", obj(h.list("""{"verb":"history.list","future_member":true,"filters":{"future":1}}""")).getValue("ok").toString())
    }

    @Test
    fun `no history is history_unavailable for both verbs, with the reason`() = runBlocking {
        for (reason in listOf(HistoryRpcHandler.MIRROR, HistoryRpcHandler.OPEN_FAILED)) {
            val h = HistoryRpcHandler(null, reason)
            h.start(scope) { _, _ -> true }
            assertEquals("""{"ok":false,"error":"history_unavailable","reason":"$reason"}""", h.list("""{"verb":"history.list"}"""))
            assertEquals("""{"ok":false,"error":"history_unavailable","reason":"$reason"}""", h.subscribe("c1"))
            assertFalse(h.hasSubscribers())
        }
    }

    @Test
    fun `a file that cannot be read is history_unavailable, not an empty list`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        record("/a")
        assertTrue(history!!.flush())
        assertEquals(1, obj(h.list("""{"verb":"history.list"}""")).getValue("rows").jsonArray.size)

        history!!.close()
        h.close()
        Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.forEach { it.deleteIfExists() } }
        val reply = obj(h.list("""{"verb":"history.list"}"""))
        assertEquals("history_unavailable", reply.getValue("error").jsonPrimitive.content)
        assertEquals("read_failed", reply.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `rows that were dropped mark the list incomplete`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        record("/a")
        assertTrue(history!!.flush())
        // The writer's own account of a gap: a snapshot it could not keep.
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('incomplete_since', '1700000000000')") }
        }
        assertTrue(obj(h.list("""{"verb":"history.list"}""")).getValue("incomplete").jsonPrimitive.boolean)
    }

    @Test
    fun `a subscriber is told the tail position, then gets each committed row with a rising seq`() = runBlocking {
        val h = open()
        val lines = CopyOnWriteArrayList<String>()
        h.start(scope) { _, line -> lines += line; true }
        record("/before")
        assertTrue(history!!.flush())

        val reply = obj(h.subscribe("c1"))
        val tailSeq = reply.getValue("tail_seq").jsonPrimitive.long
        assertEquals(1L, tailSeq)
        assertTrue(h.hasSubscribers())
        // Nothing is written before the reply is out: the stream starts when the server says so.
        record("/after1")
        assertTrue(history!!.flush())
        delay(200)
        assertTrue(lines.isEmpty(), "no line before beginStreaming: $lines")

        h.beginStreaming("c1")
        record("/after2")
        assertTrue(history!!.flush())
        awaitLines(lines, 2)
        val events = lines.map { obj(it) }
        assertEquals(listOf("history.row", "history.row"), events.map { it.getValue("event").jsonPrimitive.content })
        assertEquals(listOf("/after1", "/after2"), events.map { it.getValue("row").jsonObject.getValue("path").jsonPrimitive.content })
        assertEquals(listOf(2L, 3L), events.map { it.getValue("seq").jsonPrimitive.long }, "every event past tail_seq, none before it")
    }

    @Test
    fun `a subscriber that falls behind gets a lost marker with the count, never a silent gap`() = runBlocking {
        val h = open(capacity = 4)
        val gate = CompletableDeferred<Unit>()
        val lines = CopyOnWriteArrayList<String>()
        h.start(scope) { _, line -> gate.await(); lines += line; true }
        h.subscribe("c1")
        h.beginStreaming("c1")
        repeat(20) { record("/f$it") }
        assertTrue(history!!.flush())
        gate.complete(Unit)
        awaitQuiet(lines)

        val events = lines.map { obj(it) }
        val lost = events.filter { it.getValue("event").jsonPrimitive.content == "history.lost" }
        val rows = events.filter { it.getValue("event").jsonPrimitive.content == "history.row" }
        assertTrue(lost.isNotEmpty(), "20 snapshots into a queue of 4 behind a stalled writer: $lines")
        assertEquals(setOf("event", "since_last"), lost.first().keys, "the shape events/history.lost.ndjson pins")
        assertEquals(20, rows.size + lost.sumOf { it.getValue("since_last").jsonPrimitive.int }, "delivered plus lost is everything committed")
        // The marker comes ahead of the row after the gap, and the seqs rise.
        val seqs = rows.map { it.getValue("seq").jsonPrimitive.long }
        assertEquals(seqs.sorted(), seqs)
        val firstGap = seqs.zipWithNext().indexOfFirst { (a, b) -> b != a + 1 }
        assertTrue(firstGap >= 0)
        val markerIndex = events.indexOfFirst { it.getValue("event").jsonPrimitive.content == "history.lost" }
        assertEquals("history.row", events[markerIndex + 1].getValue("event").jsonPrimitive.content)
        assertEquals(seqs[firstGap + 1], events[markerIndex + 1].getValue("seq").jsonPrimitive.long)
    }

    @Test
    fun `the lost marker has the shape of its corpus fixture`() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/events/history.lost.ndjson")) { "fixture missing" }
            .bufferedReader().readText().trim()
        val parsed = obj(fixture)
        assertEquals("history.lost", parsed.getValue("event").jsonPrimitive.content)
        assertEquals(setOf("event", "since_last"), parsed.keys)
        assertEquals(3, parsed.getValue("since_last").jsonPrimitive.int)
    }

    @Test
    fun `a closed connection stops its stream and a repeated subscribe leaves one writer`() = runBlocking {
        val h = open()
        val lines = CopyOnWriteArrayList<String>()
        h.start(scope) { _, line -> lines += line; true }
        h.subscribe("c1")
        h.beginStreaming("c1")
        h.subscribe("c1")
        h.beginStreaming("c1")
        record("/a")
        assertTrue(history!!.flush())
        awaitLines(lines, 1)
        delay(200)
        assertEquals(1, lines.size, "one writer: the row is written once")

        h.onConnectionClosed("c1")
        assertFalse(h.hasSubscribers())
        record("/b")
        assertTrue(history!!.flush())
        delay(200)
        assertEquals(1, lines.size)
    }

    private suspend fun awaitLines(
        lines: List<String>,
        count: Int,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (lines.size < count && System.currentTimeMillis() < deadline) delay(10)
        assertNotNull(lines.getOrNull(count - 1), "expected $count line(s), got $lines")
    }

    // Waits until no line has arrived for a moment.
    private suspend fun awaitQuiet(lines: List<String>) {
        var last = -1
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            delay(150)
            if (lines.size == last && lines.isNotEmpty()) return
            last = lines.size
        }
    }
}
