package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.telemetry.TransferHistory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `metrics.query` against a handler over a real `telemetry.db`: the data-bearing transcripts of
 * `ipc-contract/sessions/` (the live-daemon corpus test can only replay the empty answers), the default range,
 * and the READ class of the verb.
 */
class MetricsRpcHandlerTest {
    private lateinit var dir: Path
    private var history: TransferHistory? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("metrics-rpc-test")
    }

    @AfterTest
    fun tearDown() {
        runCatching { history?.close() }
        runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
    }

    @Suppress("LongParameterList")
    private fun attempt(
        at: Long,
        endpoint: String,
        outcome: AttemptOutcome = AttemptOutcome.OK,
        headersMs: Long? = null,
        totalMs: Long,
        bytesUp: Long? = null,
        bytesDown: Long? = null,
        retryNo: Int = 0,
        throttled: Boolean = false,
    ) = RequestAttempt(at, "internxt", endpoint, endpoint.substringBefore(' '), outcome, headersMs, totalMs, bytesUp, bytesDown, retryNo, throttled)

    /** The seed the `metrics-query-seeded` transcript was written against. */
    private fun seed(): TransferHistory {
        val m = 1_700_000_040_000L
        val h = TransferHistory.open("p1", dir.resolve("telemetry.db"), clock = { m + 90_000 })
        history = h
        val recorder = h.requestMetrics()
        recorder.record(attempt(m + 1_000, "GET /drive/files", headersMs = 40, totalMs = 120, bytesDown = 2000))
        recorder.record(attempt(m + 2_000, "GET /drive/files", headersMs = 60, totalMs = 200, bytesDown = 3000))
        recorder.record(attempt(m + 3_000, "GET /drive/files", AttemptOutcome.THROTTLED, headersMs = 30, totalMs = 35, throttled = true))
        recorder.record(attempt(m + 4_000, "GET /drive/files", AttemptOutcome.RESET, headersMs = null, totalMs = 500, retryNo = 1))
        recorder.record(attempt(m + 5_000, "PUT /drive/files/{id}", headersMs = 80, totalMs = 900, bytesUp = 1_048_576))
        recorder.record(attempt(m + 61_000, "GET /drive/files", headersMs = 10, totalMs = 12))
        return h
    }

    private fun transcript(name: String): List<Pair<String, String>> {
        val lines =
            checkNotNull(javaClass.getResourceAsStream("/ipc-contract/sessions/$name.ndjson")) { "missing transcript $name" }
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() }
        return lines.chunked(2).map { it[0] to it[1] }
    }

    private fun replay(
        name: String,
        handler: MetricsRpcHandler,
    ) {
        for ((request, expected) in transcript(name)) {
            assertEquals(
                Json.parseToJsonElement(expected),
                Json.parseToJsonElement(handler.query(request)),
                "$name: reply to $request",
            )
        }
    }

    @Test
    fun `the seeded transcript matches the roll-ups of the seeded attempts`() {
        val h = seed()
        replay("metrics-query-seeded", MetricsRpcHandler(h.requestMetrics()) { 1_700_000_040_000L + 90_000 })
    }

    @Test
    fun `a daemon that records nothing says so and returns an empty series`() {
        replay("metrics-query-not-recording", MetricsRpcHandler(null))
        val reply = Json.parseToJsonElement(MetricsRpcHandler(null).query("""{"verb":"metrics.query"}""")) as JsonObject
        assertFalse(reply.getValue("recording").jsonPrimitive.boolean)
        assertFalse(reply.getValue("has_data").jsonPrimitive.boolean)
        assertEquals(JsonArray(emptyList()), reply["series"])
    }

    @Test
    fun `the default range is the last hour including the open minute`() {
        val now = 1_700_000_100_000L + 5_000
        val reply = Json.parseToJsonElement(MetricsRpcHandler(null) { now }.query("""{"verb":"metrics.query"}""")) as JsonObject
        val to = reply.getValue("to_ms").jsonPrimitive.long
        assertEquals(1_700_000_160_000L, to, "the end is the end of the current minute")
        assertEquals(to - 3_600_000, reply.getValue("from_ms").jsonPrimitive.long)
    }

    @Test
    fun `a request with no range at all is still well formed`() {
        val h = seed()
        val handler = MetricsRpcHandler(h.requestMetrics()) { 1_700_000_040_000L + 90_000 }
        val reply = Json.parseToJsonElement(handler.query("""{"verb":"metrics.query"}""")) as JsonObject
        assertTrue(reply.getValue("has_data").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(true), reply["ok"])
    }

    @Test
    fun `refusals name the field and change nothing`() {
        val handler = MetricsRpcHandler(null)
        assertEquals("""{"ok":false,"error":"invalid_range"}""", handler.query("""{"verb":"metrics.query","to_ms":-5}"""))
        assertEquals("""{"ok":false,"error":"invalid_range"}""", handler.query("""{"verb":"metrics.query","from_ms":1.5}"""))
        assertEquals("""{"ok":false,"error":"invalid_range"}""", handler.query("""{"verb":"metrics.query","from_ms":10,"to_ms":10}"""))
        assertEquals("""{"ok":false,"error":"invalid_limit"}""", handler.query("""{"verb":"metrics.query","limit":5001}"""))
        assertEquals("""{"ok":false,"error":"invalid_limit"}""", handler.query("""{"verb":"metrics.query","limit":"5"}"""))
        assertEquals("""{"ok":false,"error":"invalid_endpoint_class"}""", handler.query("""{"verb":"metrics.query","endpoint_class":""}"""))
    }

    @Test
    fun `the verb is in the read class`() {
        assertEquals(IpcAuth.VerbClass.READ, IpcAuth.classOf("metrics.query"))
    }
}
