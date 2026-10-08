package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
 * errors (520 to 524) are the gateway saying that the server did not answer, the same condition as a 502/503/504: they end
 * as the 503 the callers' fallbacks act on (the account-wide listing falls back to the folder walk, a folder whose content
 * cannot be read is skipped). A 520 to 523 goes through the ladder like the others. A 524 does not: the gateway had waited
 * about two minutes already, and the same request gets the same 524.
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

    // ---- the call: one attempt for a 524, the ladder for 520 to 523, both ending as the 503 ---------------------------

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

            val failure = assertFailsWith<InternxtApiException> { service.listFiles() }

            assertEquals(503, failure.statusCode, "503 is what the /files fallback and the folder skip react to")
            assertEquals(120_000L, failure.retryAfterMs, "the gateway's Retry-After stays on the exception for the caller")
            val message = failure.message.orEmpty()
            assertTrue(
                message.startsWith("the gateway answered 524 (the origin timed out) for GET https://gateway.internxt.com/drive/files"),
                message,
            )
            assertFalse(message.contains("?"), "no query string in a message: $message")
            val cause = failure.cause as InternxtApiException
            assertEquals(524, cause.statusCode)
            assertTrue(cause.message.orEmpty().contains("error-524"), "the gateway's own body is kept in the cause: ${cause.message}")
            assertEquals(1, calls.get(), "one attempt: the same request gets the same 524")
            assertEquals(0L, currentTime, "and no pause before giving up")
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
                val (code, headers) = answer(status, retryAfter = "30")
                val service =
                    serviceOn(
                        MockEngine {
                            calls.incrementAndGet()
                            respond(errorBody(status), code, headers)
                        },
                    )
                val before = currentTime

                val failure = assertFailsWith<InternxtApiException> { service.listFiles() }

                assertEquals(503, failure.statusCode, "status $status")
                assertEquals(status, (failure.cause as InternxtApiException).statusCode)
                assertEquals(30_000L, failure.retryAfterMs, "status $status: the Retry-After hint is kept")
                assertTrue(failure.message.orEmpty().startsWith("the gateway answered $status ("), failure.message)
                assertEquals(3, calls.get(), "status $status: three attempts")
                assertEquals(2_000L + 4_000L, currentTime - before, "status $status: 2 s, then 4 s")
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
    fun `a status the gateway did not make up for an unavailable origin is not touched`() =
        runTest {
            val calls = AtomicInteger(0)
            val service =
                serviceOn(
                    MockEngine {
                        calls.incrementAndGet()
                        respond("{}", HttpStatusCode.NotFound, json)
                    },
                )

            val failure = assertFailsWith<InternxtApiException> { service.listFiles() }

            assertEquals(404, failure.statusCode)
            assertEquals(1, calls.get())
            service.close()
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

    private fun isAccountWideListing(path: String) = path.endsWith("/drive/files") || path.endsWith("/drive/folders")

    // A full gather asks the cursor listings first; a server without them (404) sends it on to the offset listings these tests are about.
    private fun isCursorListing(path: String) = path.endsWith("/drive/files/sync") || path.endsWith("/drive/folders/sync")

    private val fullGather = ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList())

    @Test
    fun `a 524 on the account-wide listings of a full gather reaches the folder tree walk instead of ending the gather`() =
        runTest {
            val requested = Collections.synchronizedList(mutableListOf<String>())
            val (code, headers) = answer(524, retryAfter = "120")
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    when {
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        isAccountWideListing(request.url.encodedPath) -> respond(errorBody(524), code, headers)
                        else -> error("unexpected request: $url")
                    }
                }

            val page = provider(engine).delta(null, null, fullGather)

            assertEquals(
                setOf("/_INBOX", "/other", "/top.txt", "/_INBOX/a.txt", "/other/o.txt"),
                page.items.map { it.path }.toSet(),
            )
            assertTrue(page.complete)
            assertTrue(!page.hasMore)
            val listings = requested.filter { isAccountWideListing(java.net.URI(it).path) }
            assertTrue(listings.size in 1..4, "each listing page is asked once, no ladder: $listings")
            assertEquals(3, requested.count { it.contains("/folders/content/") }, "root, _INBOX and other: $requested")
        }

    // The provider remembers an unavailable account-wide listing and walks the tree straight away in the meantime. A 524
    // ends as the 503 that opens that window, like any other unavailable listing.
    @Test
    fun `a 524 on the account-wide listings of a full gather opens the skip window, the next full gather walks straight away`() =
        runTest {
            val accountWide = AtomicInteger(0)
            val (code, headers) = answer(524, retryAfter = "120")
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        isAccountWideListing(request.url.encodedPath) -> {
                            accountWide.incrementAndGet()
                            respond(errorBody(524), code, headers)
                        }
                        else -> error("unexpected request: $url")
                    }
                }
            val provider = provider(engine)

            val first = provider.delta(null, null, fullGather)
            val attemptsOfTheFirst = accountWide.get()
            val second = provider.delta(null, null, fullGather)

            assertTrue(first.complete)
            assertTrue(attemptsOfTheFirst in 1..4, "each listing page was asked once, no ladder: $attemptsOfTheFirst")
            assertEquals(attemptsOfTheFirst, accountWide.get(), "the second gather did not ask the account-wide listings again")
            assertEquals(first.items.map { it.path }.toSet(), second.items.map { it.path }.toSet(), "and walked the same tree")
            assertTrue(second.complete)
        }

    // The walk: a folder whose combined content call the gateway or the origin cannot serve is re-listed
    // through the paginated per-folder endpoints (#523). When those fail the same way, the folder is
    // skipped like before: the walk goes on, the folder's files are missing, the gather is incomplete.
    @Test
    fun `a 524 on one folder's content call falls back to the paged listing and is skipped when that fails too`() =
        runTest {
            for (status in listOf(503, 524)) {
                val content = AtomicInteger(0)
                val pagedFolders = AtomicInteger(0)
                val (code, headers) = if (status == 524) answer(524, retryAfter = "120") else (HttpStatusCode.ServiceUnavailable to json)
                val engine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            // the listings are out of the way: the gather is a walk from the start
                            isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                            isAccountWideListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.ServiceUnavailable, json)
                            url.endsWith("/folders/content/other") -> {
                                content.incrementAndGet()
                                respond(errorBody(status), code, headers)
                            }
                            // the paged (cursor) fallback fails the same way: the folders stream dies first, the files one never runs
                            request.url.encodedPath.endsWith("/folders/v2/content/other/folders") -> {
                                pagedFolders.incrementAndGet()
                                respond(errorBody(status), code, headers)
                            }
                            contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                            else -> error("unexpected request: $url")
                        }
                    }

                val page = provider(engine).delta(null, null, fullGather)

                assertEquals(
                    setOf("/_INBOX", "/other", "/top.txt", "/_INBOX/a.txt"),
                    page.items.map { it.path }.toSet(),
                    "status $status: everything but the folder that was skipped",
                )
                assertFalse(page.complete, "status $status: a skipped folder makes the gather incomplete")
                assertEquals(
                    if (status == 524) 1 else 3,
                    content.get(),
                    "status $status: attempts at the skipped folder's combined call",
                )
                assertEquals(
                    if (status == 524) 1 else 3,
                    pagedFolders.get(),
                    "status $status: attempts at the paged fallback",
                )
            }
        }

    // What was true before and stays true: a status that does not mean "the server is unavailable" still aborts the walk.
    @Test
    fun `a 404 on one folder's content call still fails the walk`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        isAccountWideListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.ServiceUnavailable, json)
                        url.endsWith("/folders/content/other") -> respond("{}", HttpStatusCode.NotFound, json)
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        else -> error("unexpected request: $url")
                    }
                }

            val failure = assertFailsWith<InternxtApiException> { provider(engine).delta(null, null, fullGather) }

            assertEquals(404, failure.statusCode)
        }

    // #463: the daemon polls by default, so an incremental delta is the poll. One unavailable answer on its cheap
    // updatedAt-filtered listing used to send the poll into a walk of the whole folder tree (about half an hour on a
    // large drive, for one blip). It ends the poll instead: the poller backs off and the next poll asks the cheap
    // listing again from the same cursor. Only a full gather has a reason to walk.
    @Test
    fun `an unavailable listing ends an incremental delta instead of walking the whole tree`() =
        runTest {
            for (status in listOf(503, 524)) {
                val walked = AtomicInteger(0)
                val (code, headers) = if (status == 524) answer(524, retryAfter = "120") else (HttpStatusCode.ServiceUnavailable to json)
                val engine =
                    MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            contentOf(url) != null -> {
                                walked.incrementAndGet()
                                respond(contentOf(url)!!, HttpStatusCode.OK, json)
                            }
                            isCursorListing(request.url.encodedPath) -> error("an incremental delta never asks the cursor listing: $url")
                            isAccountWideListing(request.url.encodedPath) -> respond(errorBody(status), code, headers)
                            else -> error("unexpected request: $url")
                        }
                    }

                val failure =
                    assertFailsWith<InternxtApiException> {
                        provider(engine).delta("2026-10-01T00:00:00.000Z", null, fullGather)
                    }

                assertEquals(503, failure.statusCode, "status $status")
                assertEquals(0, walked.get(), "status $status: no folder of the tree was listed")
            }
        }

    // #463 review: failing fast on a blip must not become failing forever. When the cheap incremental listing stays
    // unavailable (a cursor whose query the gateway keeps cutting), the poll walks the tree once after
    // INCREMENTAL_WALK_AFTER, as it always did; the walk's fresh cursor starts the cheap polls over.
    @Test
    fun `an incremental listing that stays unavailable walks the tree once after the bound`() =
        runTest {
            val walked = AtomicInteger(0)
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        contentOf(url) != null -> {
                            walked.incrementAndGet()
                            respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        }
                        isAccountWideListing(request.url.encodedPath) -> respond(errorBody(503), HttpStatusCode.ServiceUnavailable, json)
                        else -> error("unexpected request: $url")
                    }
                }
            val provider = provider(engine)
            val t0 = java.time.Instant.parse("2026-10-05T10:00:00Z")
            var now = t0
            provider.clock = { now }
            val cursor = "2026-10-01T00:00:00.000Z"

            assertFailsWith<InternxtApiException> { provider.delta(cursor, null, fullGather) }
            now = t0.plus(InternxtProvider.INCREMENTAL_WALK_AFTER).minusSeconds(60)
            assertFailsWith<InternxtApiException> { provider.delta(cursor, null, fullGather) }
            assertEquals(0, walked.get(), "within the bound a failing poll walks nothing")

            now = t0.plus(InternxtProvider.INCREMENTAL_WALK_AFTER).plusSeconds(1)
            provider.delta(cursor, null, fullGather)
            assertTrue(walked.get() > 0, "past the bound the poll walks the tree instead of failing again")

            val afterWalk = walked.get()
            now = now.plusSeconds(60)
            assertFailsWith<InternxtApiException> { provider.delta(cursor, null, fullGather) }
            assertEquals(afterWalk, walked.get(), "the walk restarts the bound: the next failing poll fails fast again")
        }

    @Test
    fun `an incremental listing that answers in between restarts the bound`() =
        runTest {
            var unavailable = true
            val walked = AtomicInteger(0)
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        contentOf(url) != null -> {
                            walked.incrementAndGet()
                            respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        }
                        isAccountWideListing(request.url.encodedPath) ->
                            if (unavailable) respond(errorBody(503), HttpStatusCode.ServiceUnavailable, json) else respond("[]", HttpStatusCode.OK, json)
                        else -> error("unexpected request: $url")
                    }
                }
            val provider = provider(engine)
            val t0 = java.time.Instant.parse("2026-10-05T10:00:00Z")
            var now = t0
            provider.clock = { now }
            val cursor = "2026-10-01T00:00:00.000Z"

            assertFailsWith<InternxtApiException> { provider.delta(cursor, null, fullGather) }
            now = t0.plusSeconds(600)
            unavailable = false
            provider.delta(cursor, null, fullGather)
            unavailable = true
            now = t0.plus(InternxtProvider.INCREMENTAL_WALK_AFTER).plusSeconds(60)
            assertFailsWith<InternxtApiException> { provider.delta(cursor, null, fullGather) }

            assertEquals(0, walked.get(), "a success in between restarts the bound: one later blip walks nothing")
        }
}