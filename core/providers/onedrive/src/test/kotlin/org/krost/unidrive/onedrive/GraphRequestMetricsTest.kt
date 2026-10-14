package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.http.RequestMetricsSink
import org.krost.unidrive.http.RequestMetricsTap
import org.krost.unidrive.telemetry.RequestMetricsConfig
import org.krost.unidrive.telemetry.TransferHistory
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Graph client's request-attempt metrics: the endpoint classes it reports, that no identifier or name can be in
 * one, and what the metrics cost a request.
 */
class GraphRequestMetricsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Collected : RequestMetricsSink {
        val attempts = CopyOnWriteArrayList<RequestAttempt>()

        override fun record(attempt: RequestAttempt) {
            attempts += attempt
        }
    }

    private fun service(
        engine: MockEngine,
        collected: Collected?,
    ): GraphApiService =
        GraphApiService(config = OneDriveConfig(), engine = engine, tokenProvider = { "test-token" }).also {
            if (collected != null) it.attachRequestMetrics(collected)
        }

    private fun await(
        collected: Collected,
        n: Int,
    ): List<RequestAttempt> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (collected.attempts.size < n && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(n, collected.attempts.size, "${collected.attempts}")
        return collected.attempts.toList()
    }

    @Test
    fun `the Graph client reports templated endpoint classes once a sink is attached`() =
        runBlocking {
            val collected = Collected()
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/children") -> respond("""{"value":[]}""", HttpStatusCode.OK, json)
                        request.url.encodedPath.endsWith("/drive") -> respond("""{"id":"d1","driveType":"personal"}""", HttpStatusCode.OK, json)
                        else -> respond("""{"id":"i1","name":"n"}""", HttpStatusCode.OK, json)
                    }
                }
            val service = service(engine, collected)

            service.getDrive()
            service.getItemById("01ABCDEF!1234567890")
            service.listChildren("Taxes 2025/Secret Folder")
            service.getItemByPath("Taxes 2025/secret.pdf")

            val classes = await(collected, 4).map { it.endpointClass }
            assertEquals(
                listOf(
                    "GET /v1.0/me/drive",
                    "GET /v1.0/me/drive/items/{id}",
                    "GET /v1.0/me/drive/root:{path}:/children",
                    "GET /v1.0/me/drive/root:{path}",
                ),
                classes,
            )
            assertTrue(collected.attempts.all { it.provider == "onedrive" })
            service.close()
        }

    @Test
    fun `without a sink the Graph client records nothing`() =
        runBlocking {
            val engine = MockEngine { respond("""{"id":"d1"}""", HttpStatusCode.OK, json) }
            val service = service(engine, null)
            service.getDrive()
            val late = Collected()
            service.attachRequestMetrics(late)
            service.getDrive()
            assertEquals(1, await(late, 1).size)
            service.close()
        }

    // Property style over the shapes of every URL this client builds, with identifiers, names and tokens in random places.
    @Test
    fun `no identifier name or query value of any Graph URL reaches the endpoint class`() {
        val random = Random(7)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        fun secret() = "Q" + (1..random.nextInt(18) + 8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

        val base = "https://graph.microsoft.com/v1.0"
        repeat(300) {
            val id = secret()
            val permission = secret()
            val folder = secret() + "%20" + secret()
            val file = secret() + "%C3%A4.txt"
            val token = secret()
            val urls =
                listOf(
                    "$base/me/drive/items/$id/content",
                    "$base/me/drive/items/$id/permissions/$permission",
                    "$base/me/drive/items/$id/createLink",
                    "$base/me/drive/root:/$folder/$file:/content?@microsoft.graph.conflictBehavior=replace",
                    "$base/me/drive/root:/$folder/$file:/createUploadSession",
                    "$base/me/drive/root:/$folder:/children?\$top=999",
                    "$base/me/drive/root/delta?token=$token",
                    "$base/subscriptions/$id",
                    "https://contoso-my.sharepoint.com/personal/$folder/_layouts/15/download.aspx?UniqueId=$id&tempauth=$token",
                    "https://api.onedrive.com/rup/$token",
                )
            for (url in urls) {
                val parsed = io.ktor.http.Url(url)
                val result =
                    GraphEndpoints.classifier.classify(
                        "GET",
                        parsed.host,
                        "/" + parsed.rawSegments.filter { it.isNotEmpty() }.joinToString("/"),
                    )
                for (secretValue in listOf(id, permission, folder, file, token, "contoso", "sharepoint", "download.aspx")) {
                    assertFalse(secretValue in result, "'$secretValue' leaked into '$result' (from $url)")
                }
                assertFalse('?' in result || '%' in result, "'$result' carries query or encoded text")
            }
        }
    }

    // ---- the cost of recording ----------------------------------------------------------------------------------

    private val tenMsMicros = 10_000.0 // a Graph call takes tens to hundreds of milliseconds; 1 % is taken of the 10 ms floor

    /**
     * What recording adds to one request, measured apart from the machine's load: the work the plugin does for an attempt
     * besides the request itself is naming its endpoint class and handing the attempt to the recorder's queue.
     */
    @Test
    fun `naming and queueing an attempt costs a request well under one percent`() {
        val dir = Files.createTempDirectory("graph-metrics-cost")
        val history = TransferHistory.open("p1", dir.resolve("telemetry.db"))
        try {
            val iterations = 200_000
            val recorder = history.requestMetrics(RequestMetricsConfig(queueCapacity = 2 * iterations))
            fun one(i: Int) {
                val cls =
                    GraphEndpoints.classifier.classify("GET", "graph.microsoft.com", "/v1.0/me/drive/items/01ABCDEF!123/children")
                recorder.record(RequestAttempt(i.toLong(), "onedrive", cls, "GET", AttemptOutcome.OK, 20, 35, null, 1_000, 0, false))
            }
            repeat(20_000) { one(it) } // warm up
            val start = System.nanoTime()
            repeat(iterations) { one(it) }
            val perRequestMicros = (System.nanoTime() - start) / 1_000.0 / iterations
            assertTrue(recorder.flush(120_000))

            val percent = perRequestMicros / tenMsMicros * 100.0
            println("OVERHEAD classify_and_queue_us=%.2f percent_of_10ms_request=%.4f dropped=%d".format(perRequestMicros, percent, recorder.stats.dropped))
            assertEquals(0L, recorder.stats.dropped)
            assertTrue(percent < 1.0, "naming and queueing an attempt costs $perRequestMicros us, $percent % of a 10 ms request")
        } finally {
            history.close()
            runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
        }
    }

    /** The same through a real client pipeline, for the record: on a loaded machine its noise is larger than the effect. */
    @Test
    fun `a request through the client with the recorder attached, against one without`() {
        val dir = Files.createTempDirectory("graph-metrics-overhead")
        val history = TransferHistory.open("p1", dir.resolve("telemetry.db"))
        try {
            val recorder = history.requestMetrics()
            val without = RequestMetricsTap()
            val with = RequestMetricsTap().also { it.sink = recorder }
            val engine = MockEngine { respond("""{"value":[]}""", HttpStatusCode.OK, json) }
            val clientWithout = graphHttpClient(without, OneDriveConfig.DEFAULT_USER_AGENT, engine)
            val clientWith = graphHttpClient(with, OneDriveConfig.DEFAULT_USER_AGENT, engine)
            val url = "https://graph.microsoft.com/v1.0/me/drive/items/01ABCDEF!123/children"

            fun perRequestNanos(client: io.ktor.client.HttpClient): Double =
                runBlocking {
                    val n = 3_000
                    val start = System.nanoTime()
                    repeat(n) { client.get(url).bodyAsText() }
                    (System.nanoTime() - start).toDouble() / n
                }

            perRequestNanos(clientWithout)
            perRequestNanos(clientWith) // warm both paths up
            var off = Double.MAX_VALUE
            var on = Double.MAX_VALUE
            repeat(7) { // interleaved rounds, the fastest of each side
                off = minOf(off, perRequestNanos(clientWithout))
                on = minOf(on, perRequestNanos(clientWith))
            }
            assertTrue(recorder.flush(60_000))
            println(
                "PIPELINE per_request_without_us=%.1f per_request_with_us=%.1f difference_us=%.1f dropped=%d"
                    .format(off / 1_000.0, on / 1_000.0, (on - off) / 1_000.0, recorder.stats.dropped),
            )
            assertEquals(0L, recorder.stats.dropped)
        } finally {
            history.close()
            runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
        }
    }
}
