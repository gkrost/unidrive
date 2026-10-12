package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.http.RequestMetricsSink
import org.krost.unidrive.http.RequestMetricsTap
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Internxt client's request-attempt metrics: the endpoint classes it reports and that nothing identifying is in one. */
class InternxtRequestMetricsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Collected : RequestMetricsSink {
        val attempts = CopyOnWriteArrayList<RequestAttempt>()

        override fun record(attempt: RequestAttempt) {
            attempts += attempt
        }
    }

    private fun service(
        engine: MockEngine,
        tap: RequestMetricsTap,
    ): InternxtApiService =
        InternxtApiService(
            InternxtConfig(credentialProtector = null),
            credentialsProvider = { _ ->
                InternxtCredentials(jwt = "test-jwt", mnemonic = "test-mnemonic", rootFolderId = "test-root", email = "test@example.invalid")
            },
            requestMetrics = tap,
            httpClient = InternxtApiService.defaultHttpClient(tap, engine),
        )

    @Test
    fun `the Internxt client reports templated endpoint classes once a sink is attached`() =
        runBlocking {
            val collected = Collected()
            val tap = RequestMetricsTap()
            val engine = MockEngine { respond("[]", HttpStatusCode.OK, json) }
            val service = service(engine, tap)
            service.attachRequestMetrics(collected)

            service.listFiles()

            val deadline = System.nanoTime() + 5_000_000_000L
            while (collected.attempts.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            val attempt = collected.attempts.single()
            assertEquals("GET /drive/files", attempt.endpointClass)
            assertEquals("internxt", attempt.provider)
            service.close()
        }

    @Test
    fun `no uuid bucket name or query value of any Internxt URL reaches the endpoint class`() {
        val random = Random(11)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        fun token() = "t" + (1..random.nextInt(20) + 10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

        repeat(300) {
            val uuid = UUID(random.nextLong(), random.nextLong()).toString()
            val other = UUID(random.nextLong(), random.nextLong()).toString()
            val bucket = token()
            val fileId = token()
            val name = token()
            val urls =
                listOf(
                    "https://gateway.internxt.com/drive/files/$uuid/meta",
                    "https://gateway.internxt.com/drive/files/$uuid",
                    "https://gateway.internxt.com/drive/folders/content/$uuid",
                    "https://gateway.internxt.com/drive/folders/v2/content/$uuid/files?limit=1000&offset=0&updatedAt=$name",
                    "https://gateway.internxt.com/drive/folders/content/$uuid/folders?cursor=$other",
                    "https://gateway.internxt.com/drive/files?plainName=$name",
                    "https://api.internxt.com/buckets/$bucket/files/$fileId/info",
                    "https://api.internxt.com/v2/buckets/$bucket/files/start?multiparts=1",
                    "https://api.internxt.com/v2/buckets/$bucket/files/finish",
                    "https://storage-$bucket.example.test/$fileId/$name?X-Amz-Signature=$name",
                )
            for (url in urls) {
                val parsed = Url(url)
                val result =
                    InternxtEndpoints.classifier.classify(
                        "PUT",
                        parsed.host,
                        "/" + parsed.rawSegments.filter { it.isNotEmpty() }.joinToString("/"),
                    )
                for (secret in listOf(uuid, other, bucket, fileId, name, "example.test")) {
                    assertFalse(secret in result, "'$secret' leaked into '$result' (from $url)")
                }
                assertTrue(Regex("""^PUT (external|/[A-Za-z0-9_{}/-]*)$""").matches(result), "unexpected class '$result'")
            }
        }
    }
}
