package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ScanContext
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Once a heavy listing has the time it needs, the gateway answers first: on a large account a page of `/drive/folders` came
 * back as Cloudflare's 524 ("origin timed out", JSON body, `Retry-After: 120`) after about two minutes. Cloudflare's origin
 * errors (520 to 524) go through the ladder like 502/503/504 and end as the 503 the callers' fallbacks act on. A 524 does
 * not go through the ladder: the gateway had waited about two minutes already, and the same request gets the same 524.
 */
class InternxtGatewayOriginErrorTest {
    // The shape of Cloudflare's JSON error page (the bodies here are synthetic, only the `type` is Cloudflare's own).
    private fun errorBody(status: Int) =
        """{"type":"https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-$status/",""" +
            """"title":"Error $status","status":$status}"""

    private fun answer(
        status: Int,
        retryAfter: String? = null,
    ) = HttpStatusCode(status, "Cloudflare origin error").let { code ->
        val headers = mutableMapOf("Content-Type" to listOf("application/json"), "Server" to listOf("cloudflare"))
        if (retryAfter != null) headers["Retry-After"] = listOf(retryAfter)
        code to headersOf(*headers.map { it.key to it.value }.toTypedArray())
    }

    private val json = headersOf("Content-Type", "application/json")

    private fun serviceOn(engine: MockEngine) =
        InternxtApiService(
            InternxtConfig(),
            credentialsProvider = { _ ->
                InternxtCredentials(jwt = "test-jwt", mnemonic = "test-mnemonic", rootFolderId = "test-root", email = "test@example.invalid")
            },
            driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
            httpClient = HttpClient(engine),
        )

    @Test
    fun `a 524 is one attempt and ends as the 503 the fallbacks act on`() =
        runTest {
            val calls = AtomicInteger(0)
            val (code, headers) = answer(524, retryAfter = "120")
            val service =
                serviceOn(
                    MockEngine {
                        calls.incrementAndGet()
                        respond(errorBody(524), code, headers)
                    },
                )

            ServiceWarnings().use { warnings ->
                val failure = assertFailsWith<InternxtApiException> { service.listFiles() }

                assertEquals(503, failure.statusCode, "503 is what the /files fallback and the folder skip react to")
                assertFalse(failure.timedOutLocally, "the gateway's cap, not the engine's own timer")
                assertEquals(120_000L, failure.retryAfterMs, "the gateway's Retry-After stays on the exception for the caller")
                val message = failure.message.orEmpty()
                assertTrue(message.startsWith("the gateway answered 524 (the origin timed out) for GET https://gateway.internxt.com/drive/files"), message)
                assertFalse(message.contains("?"), "no query string in a message: $message")
                val cause = failure.cause as InternxtApiException
                assertEquals(524, cause.statusCode)
                assertTrue(cause.message.orEmpty().contains("error-524"), "the gateway's own body is kept in the cause: ${cause.message}")
                assertEquals(1, calls.get(), "one attempt: the same request gets the same 524")
                assertEquals(0L, currentTime, "and no pause before giving up")
                val logged = warnings.messages.single()
                assertTrue(logged.startsWith("GET https://gateway.internxt.com/drive/files: the gateway answered 524 (the origin timed out) after "), logged)
                assertTrue(logged.endsWith(", not retried"), logged)
            }
            service.close()
        }

    @Test
    fun `every call that reads through the ladder maps a 524 the same way`() =
        runTest {
            val calls = AtomicInteger(0)
            val (code, headers) = answer(524)
            val service =
                serviceOn(
                    MockEngine {
                        calls.incrementAndGet()
                        respond(errorBody(524), code, headers)
                    },
                )

            assertEquals(503, assertFailsWith<InternxtApiException> { service.listFolders() }.statusCode)
            assertEquals(503, assertFailsWith<InternxtApiException> { service.getFolderContents("some-folder") }.statusCode)
            assertEquals(503, assertFailsWith<InternxtApiException> { service.getFileMeta("some-file") }.statusCode)

            assertEquals(3, calls.get(), "one request per call")
            service.close()
        }

    @Test
    fun `520 to 523 go through the whole ladder like a 502 and end as the same 503`() =
        runTest {
            for (status in listOf(520, 521, 522, 523)) {
                val calls = AtomicInteger(0)
                val (code, headers) = answer(status)
                val service =
                    serviceOn(
                        MockEngine {
                            calls.incrementAndGet()
                            respond(errorBody(status), code, headers)
                        },
                    )
                val before = currentTime

                ServiceWarnings().use { warnings ->
                    val failure = assertFailsWith<InternxtApiException> { service.listFiles() }

                    assertEquals(503, failure.statusCode, "status $status")
                    assertEquals(status, (failure.cause as InternxtApiException).statusCode)
                    assertTrue(failure.message.orEmpty().startsWith("the gateway answered $status ("), failure.message)
                    assertEquals(3, calls.get(), "status $status: three attempts")
                    assertEquals(2_000L + 4_000L, currentTime - before, "status $status: 2 s, then 4 s")
                    assertEquals(3, warnings.messages.size, "one WARN per attempt: ${warnings.messages}")
                    assertTrue(warnings.messages.first().endsWith(", attempt 1/3"), warnings.messages.first())
                    assertTrue(warnings.messages.last().endsWith(", not retried"), warnings.messages.last())
                }
                service.close()
            }
        }

    @Test
    fun `a 522 that clears on the second attempt is used`() =
        runTest {
            val calls = AtomicInteger(0)
            val (code, headers) = answer(522)
            val service =
                serviceOn(
                    MockEngine {
                        if (calls.incrementAndGet() == 1) respond(errorBody(522), code, headers) else respond("[]", HttpStatusCode.OK, json)
                    },
                )

            assertEquals(emptyList(), service.listFiles())

            assertEquals(2, calls.get())
            assertEquals(2_000L, currentTime)
            service.close()
        }

    @Test
    fun `the Retry-After of a gateway origin error replaces the backoff, capped at a minute`() =
        runTest {
            for ((retryAfter, expectedMs) in listOf("7" to 7_000L, "120" to 60_000L)) {
                val calls = AtomicInteger(0)
                val (code, headers) = answer(523, retryAfter)
                val service =
                    serviceOn(
                        MockEngine {
                            if (calls.incrementAndGet() == 1) respond(errorBody(523), code, headers) else respond("[]", HttpStatusCode.OK, json)
                        },
                    )
                val before = currentTime

                assertEquals(emptyList(), service.listFiles())

                assertEquals(expectedMs, currentTime - before, "Retry-After: $retryAfter")
                service.close()
            }
        }

    // ---- what the callers do with it ---------------------------------------------------------------------------------

    private fun provider(engine: MockEngine): InternxtProvider {
        val provider = InternxtProvider()
        val auth = InternxtProvider::class.java.getDeclaredField("authService").also { it.isAccessible = true }.get(provider)
        val credsField = auth.javaClass.getDeclaredField("credentials").also { it.isAccessible = true }
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            auth,
            InternxtCredentials(
                jwt = "header.$payload.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        val api = InternxtProvider::class.java.getDeclaredField("api").also { it.isAccessible = true }.get(provider)
        val clientField = InternxtApiService::class.java.getDeclaredField("httpClient").also { it.isAccessible = true }
        (clientField.get(api) as? HttpClient)?.close()
        clientField.set(api, HttpClient(engine))
        return provider
    }

    // root -> [_INBOX, other] and top.txt; _INBOX -> a.txt; other -> o.txt
    private val tree =
        mapOf(
            "root" to
                """{"children":[
                    {"uuid":"inbox","plainName":"_INBOX","status":"EXISTS"},
                    {"uuid":"other","plainName":"other","status":"EXISTS"}],
                  "files":[{"uuid":"t","plainName":"top","type":"txt","size":"1","status":"EXISTS"}]}""",
            "inbox" to """{"children":[],"files":[{"uuid":"a","plainName":"a","type":"txt","size":"3","status":"EXISTS"}]}""",
            "other" to """{"children":[],"files":[{"uuid":"o","plainName":"o","type":"txt","size":"5","status":"EXISTS"}]}""",
        )

    private fun contentOf(url: String): String? = tree.entries.firstOrNull { url.endsWith("/folders/content/${it.key}") }?.value

    @Test
    fun `a 524 on the account-wide listings reaches the folder tree walk instead of ending the enumeration`() =
        runTest {
            val requested = Collections.synchronizedList(mutableListOf<String>())
            val (code, headers) = answer(524, retryAfter = "120")
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    val path = request.url.encodedPath
                    when {
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        path.endsWith("/drive/files") || path.endsWith("/drive/folders") -> respond(errorBody(524), code, headers)
                        else -> error("unexpected request: $url")
                    }
                }

            val page = provider(engine).delta(null, null, ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList()))

            assertEquals(
                setOf("/_INBOX", "/other", "/top.txt", "/_INBOX/a.txt", "/other/o.txt"),
                page.items.map { it.path }.toSet(),
            )
            assertTrue(page.complete)
            val listings = requested.filter { java.net.URI(it).path.let { p -> p.endsWith("/drive/files") || p.endsWith("/drive/folders") } }
            assertTrue(listings.size in 1..4, "each listing page is asked once, no ladder: $listings")
            assertEquals(3, requested.count { it.contains("/folders/content/") }, "root, _INBOX and other: $requested")
        }

    // The provider remembers an unavailable account-wide listing for 30 minutes and walks the tree straight away in the
    // meantime. A 524 ends as the 503 that starts that window, like any other unavailable listing.
    @Test
    fun `a 524 on the account-wide listings starts the skip window, the next gather walks straight away`() =
        runTest {
            val accountWide = AtomicInteger(0)
            val (code, headers) = answer(524, retryAfter = "120")
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val path = request.url.encodedPath
                    when {
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        path.endsWith("/drive/files") || path.endsWith("/drive/folders") -> {
                            accountWide.incrementAndGet()
                            respond(errorBody(524), code, headers)
                        }
                        else -> error("unexpected request: $url")
                    }
                }
            val provider = provider(engine)
            val scanContext = ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList())

            val first = provider.delta(null, null, scanContext)
            val attemptsOfTheFirst = accountWide.get()
            val second = provider.delta(null, null, scanContext)

            assertTrue(first.complete)
            assertTrue(attemptsOfTheFirst in 1..4, "each listing page was asked once, no ladder: $attemptsOfTheFirst")
            assertEquals(attemptsOfTheFirst, accountWide.get(), "the second gather did not ask the account-wide listings again")
            assertEquals(first.items.map { it.path }.toSet(), second.items.map { it.path }.toSet(), "and walked the same tree")
            assertTrue(second.complete)
        }

    // ---- on a real socket ---------------------------------------------------------------------------------------------

    @Test
    fun `a 524 read off a real socket is the same one attempt`() {
        LoopbackServer(tls = false) { exchange ->
            exchange.readHead()
            Thread.sleep(600)
            exchange.respond(524, "A Timeout Occurred", errorBody(524), mapOf("Server" to "cloudflare", "Retry-After" to "120"))
        }.use { server ->
            // The listing timeouts outlast the 600 ms the gateway takes; the default socket timeout (400 ms) would not.
            loopbackService(loopbackClient(server, socketTimeoutMs = 400), socketMs = 400, listingSocketMs = 8_000, listingRequestMs = 10_000).use { api ->
                runBlocking {
                    val started = System.nanoTime()
                    val failure = assertFailsWith<InternxtApiException> { api.listFolders() }
                    val tookMs = (System.nanoTime() - started) / 1_000_000

                    assertEquals(503, failure.statusCode)
                    assertFalse(failure.timedOutLocally, "the gateway answered, our timer did not fire: ${failure.message}")
                    assertEquals(120_000L, failure.retryAfterMs)
                    assertEquals(524, (failure.cause as InternxtApiException).statusCode)
                    assertEquals(1, server.connections, "one attempt")
                    assertTrue(tookMs < 5_000, "no ladder pauses of 2 s and 4 s: $tookMs ms")
                }
            }
        }
    }
}
