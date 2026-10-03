package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.time.Instant
import java.util.Base64
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternxtScopedDeltaTest {
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


    @Test
    fun `a scoped full delta lists only the scope subtree and never the account-wide endpoints`() =
        runTest {
            val requested = Collections.synchronizedList(mutableListOf<String>())
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    val body =
                        when {
                            url.endsWith("/folders/content/root") ->
                                """{"children":[
                                    {"uuid":"inbox","plainName":"_INBOX","status":"EXISTS"},
                                    {"uuid":"inboxx","plainName":"_INBOXX","status":"EXISTS"}],
                                  "files":[{"uuid":"t","plainName":"top","type":"txt","size":"1","status":"EXISTS"}]}"""
                            url.endsWith("/folders/content/inbox") ->
                                """{"children":[{"uuid":"sub","plainName":"sub","status":"EXISTS"}],
                                  "files":[{"uuid":"a","plainName":"a","type":"txt","size":"3","status":"EXISTS"}]}"""
                            url.endsWith("/folders/content/sub") ->
                                """{"children":[],"files":[{"uuid":"d","plainName":"deep","type":"txt","size":"4","status":"EXISTS"}]}"""
                            else -> error("unexpected request: $url")
                        }
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            val before = Instant.now()

            val page =
                provider(engine).delta(
                    cursor = null,
                    onPageProgress = null,
                    scanContext = ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = listOf("/_INBOX")),
                )

            assertEquals(
                setOf("/_INBOX", "/_INBOX/sub", "/_INBOX/a.txt", "/_INBOX/sub/deep.txt"),
                page.items.map { it.path }.toSet(),
            )
            assertTrue(page.complete)
            assertTrue(!page.hasMore)
            assertTrue(!Instant.parse(page.cursor).isBefore(before.minusSeconds(1)), "cursor is the walk start time: ${page.cursor}")
            assertEquals(3, requested.size, "root, _INBOX and sub only: $requested")
            assertTrue(requested.none { it.contains("/files") && !it.contains("/folders/content/") }, "no account-wide /files call")
            assertTrue(requested.none { it.endsWith("/folders") || it.contains("/folders?") }, "no account-wide /folders call")
            assertTrue(requested.none { it.contains("/sync") }, "no cursor listing either: it always walks the whole account")
        }

    // ---- the account-wide listings cut: the tree walk takes over ---------------------------------------------------------
    //
    // /files and /folders page through the whole account with an offset, and on a large account they are slow server-side
    // enough (25-56 s per folder page, #517 F3) that the client's own read-idle watchdog used to cut them at 60 s — over
    // TLS indistinguishable from a server close (#517 F1). The mocks reproduce that close shape. After the retry ladder
    // that is a 503, and the gather used to end with it (a failing /folders) or to crawl the tree one folder at a time (a
    // failing /files).

    private val json = headersOf("Content-Type", "application/json")

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

    // The tests that exercise the offset listing and its fallbacks run against a server without the cursor listings (an
    // older or a self-hosted one): the cursor endpoints answer 404, which sends the gather to the offset listing.
    private fun isCursorListing(path: String) = path.endsWith("/drive/files/sync") || path.endsWith("/drive/folders/sync")

    private fun cutByTheGateway() =
        io.ktor.utils.io.ClosedReadChannelException(
            java.io.EOFException("Failed to parse HTTP response: the server prematurely closed the connection"),
        )

    @Test
    fun `a full delta whose account-wide listings are cut walks the whole tree instead`() =
        runTest {
            val requested = Collections.synchronizedList(mutableListOf<String>())
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    when {
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        isAccountWideListing(request.url.encodedPath) -> throw cutByTheGateway()
                        else -> error("unexpected request: $url")
                    }
                }

            val page = provider(engine).delta(null, null, ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList()))

            assertEquals(
                setOf("/_INBOX", "/other", "/top.txt", "/_INBOX/a.txt", "/other/o.txt"),
                page.items.map { it.path }.toSet(),
            )
            assertTrue(page.complete)
            assertTrue(!page.hasMore)
            assertTrue(requested.any { isAccountWideListing(java.net.URI(it).path) }, "the account-wide listing was tried first")
            assertEquals(3, requested.count { it.contains("/folders/content/") }, "root, _INBOX and other: $requested")
        }

    @Test
    fun `a failing files listing alone also ends in the tree walk, not in the half the folders listing returned`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        request.url.encodedPath.endsWith("/drive/folders") -> respond("[]", HttpStatusCode.OK, json)
                        request.url.encodedPath.endsWith("/drive/files") -> throw cutByTheGateway()
                        else -> error("unexpected request: $url")
                    }
                }

            val page = provider(engine).delta(null, null, ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList()))

            assertEquals(
                setOf("/_INBOX", "/other", "/top.txt", "/_INBOX/a.txt", "/other/o.txt"),
                page.items.map { it.path }.toSet(),
            )
            assertTrue(page.complete)
        }

    @Test
    fun `an incremental delta of a scoped profile, listings cut, walks the scope only`() =
        runTest {
            val requested = Collections.synchronizedList(mutableListOf<String>())
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    when {
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        isAccountWideListing(request.url.encodedPath) -> throw cutByTheGateway()
                        else -> error("unexpected request: $url")
                    }
                }

            val page =
                provider(engine).delta(
                    cursor = "2026-10-01T00:00:00Z",
                    onPageProgress = null,
                    scanContext = ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = listOf("/_INBOX")),
                )

            assertEquals(setOf("/_INBOX", "/_INBOX/a.txt"), page.items.map { it.path }.toSet())
            assertTrue(requested.none { it.endsWith("/folders/content/other") }, "a sibling of the scope is not listed: $requested")
        }

    @Test
    fun `the handover to the fallback walk never reports a lower progress total`() =
        runTest {
            // Six full /files pages cross the heartbeat's 5,000-item interval, so the speculative phase reports 5,994
            // before page seven is cut. A walk heartbeat seeded with nothing would report its own count from 1 —
            // progress jumping down at the exact moment the gather just failed (the jump that alarmed users before,
            // 208k → 261k → 218k). Seeded with the items already counted, the walk's first report clears the 5,000
            // threshold at once and the reported sequence never steps down.
            val ticks = Collections.synchronizedList(mutableListOf<Int>())
            val engine =
                MockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        isCursorListing(path) -> respond("{}", HttpStatusCode.NotFound, json)
                        path.endsWith("/drive/files") -> {
                            val offset = request.url.parameters["offset"]?.toInt() ?: 0
                            if (offset < 6 * 999) {
                                val page =
                                    (0 until 999).joinToString(",", "[", "]") { i ->
                                        val n = offset + i
                                        """{"uuid":"f$n","plainName":"f$n","type":"txt","size":"1","status":"EXISTS"}"""
                                    }
                                respond(page, HttpStatusCode.OK, json)
                            } else {
                                throw cutByTheGateway()
                            }
                        }
                        path.endsWith("/drive/folders") -> respond("[]", HttpStatusCode.OK, json)
                        contentOf(request.url.toString()) != null ->
                            respond(contentOf(request.url.toString())!!, HttpStatusCode.OK, json)
                        else -> error("unexpected request: $path")
                    }
                }

            val page =
                provider(engine).delta(
                    cursor = null,
                    onPageProgress = { itemsSoFar -> ticks += itemsSoFar },
                    scanContext = ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList()),
                )

            assertEquals(
                setOf("/_INBOX", "/other", "/top.txt", "/_INBOX/a.txt", "/other/o.txt"),
                page.items.map { it.path }.toSet(),
            )
            assertTrue(page.complete)
            assertTrue(ticks.size >= 2, "the walk reports progress of its own: $ticks")
            assertTrue(
                ticks.zipWithNext().all { (before, after) -> after >= before },
                "the handover to the walk never reports a lower total: $ticks",
            )
        }

    @Test
    fun `a gather that ended in the walk skips the account-wide attempt on the next gather`() =
        runTest {
            val accountWide = java.util.concurrent.atomic.AtomicInteger(0)
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        isAccountWideListing(request.url.encodedPath) -> {
                            accountWide.incrementAndGet()
                            throw cutByTheGateway()
                        }
                        else -> error("unexpected request: $url")
                    }
                }
            val p = provider(engine)
            val scanContext = ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList())

            val first = p.delta(null, null, scanContext)
            assertTrue(first.complete)
            val wideAfterFirst = accountWide.get()
            assertTrue(wideAfterFirst > 0, "the first gather tried the account-wide listing: $accountWide")

            val second = p.delta(null, null, scanContext)
            assertEquals(
                setOf("/_INBOX", "/other", "/top.txt", "/_INBOX/a.txt", "/other/o.txt"),
                second.items.map { it.path }.toSet(),
            )
            assertTrue(second.complete)
            assertEquals(
                wideAfterFirst,
                accountWide.get(),
                "the second gather skipped the doomed account-wide attempt and walked straight away",
            )
        }

    @Test
    fun `a listing that fails for another reason than the gateway's unavailability still fails the gather`() =
        runTest {
            val requested = Collections.synchronizedList(mutableListOf<String>())
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    when {
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        request.url.encodedPath.endsWith("/drive/folders") -> respond("[]", HttpStatusCode.OK, json)
                        request.url.encodedPath.endsWith("/drive/files") -> respond("{}", HttpStatusCode.NotFound, json)
                        else -> error("unexpected request: $url")
                    }
                }

            val failure =
                kotlin.test.assertFailsWith<InternxtApiException> {
                    provider(engine).delta(null, null, ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList()))
                }

            assertEquals(404, failure.statusCode)
            assertTrue(requested.none { it.contains("/folders/content/") }, "no tree walk for a 404: $requested")
        }

    // ---- the progress a running listing reports to the engine's status --------------------------------------------------------

    private fun filesPage(range: IntRange) =
        range.joinToString(",", "[", "]") { """{"uuid":"f$it","plainName":"f$it","type":"txt","size":"1","status":"EXISTS"}""" }

    private fun foldersPage(range: IntRange) = range.joinToString(",", "[", "]") { """{"uuid":"d$it","plainName":"d$it","status":"EXISTS"}""" }

    @Test
    fun `an account-wide listing reports the items of both streams after every page and names the listing`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val offset = request.url.parameters["offset"]?.toInt() ?: 0
                    when {
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        request.url.encodedPath.endsWith("/drive/files") -> respond(if (offset == 0) filesPage(1..3) else "[]", HttpStatusCode.OK, json)
                        request.url.encodedPath.endsWith("/drive/folders") -> respond(if (offset == 0) foldersPage(1..2) else "[]", HttpStatusCode.OK, json)
                        else -> error("unexpected request: ${request.url}")
                    }
                }
            val reports = Collections.synchronizedList(mutableListOf<ScanProgress>())

            val page =
                provider(engine).delta(
                    cursor = null,
                    onPageProgress = null,
                    scanContext = ScanContext(null, emptyList(), { _, _ -> }, onProgress = { reports += it }),
                )

            assertEquals(5, page.items.size)
            // The cursor listing is tried first and names itself at its start; the 404 sends the gather here.
            assertEquals(ScanProgress.LISTING_CURSOR, reports.first().listing)
            val account = reports.filter { it.listing == ScanProgress.LISTING_ACCOUNT }
            assertEquals(reports.drop(1), account, "everything after the cursor listing's start is the offset listing's: $reports")
            assertTrue(
                account.all { it.foldersDone == null && it.foldersKnown == null && it.foldersSkipped == null },
                "an offset pagination has no folder walk to report: $reports",
            )
            assertEquals(0, account.first().items, "the listing is named before the first page is back")
            assertEquals(5, account.last().items, "files and folders together")
            assertEquals(3, account.size, "the start and one report per page")
            assertTrue(account.zipWithNext().all { (a, b) -> b.items >= a.items }, "the count only grows: $reports")
        }

    @Test
    fun `when the account-wide listing is cut the reports turn into the folder walk's and end with every folder done`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        isCursorListing(request.url.encodedPath) -> respond("{}", HttpStatusCode.NotFound, json)
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        isAccountWideListing(request.url.encodedPath) -> throw cutByTheGateway()
                        else -> error("unexpected request: $url")
                    }
                }
            val reports = Collections.synchronizedList(mutableListOf<ScanProgress>())

            provider(engine).delta(
                null,
                null,
                ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = emptyList(), onProgress = { reports += it }),
            )

            assertEquals(
                listOf(ScanProgress.LISTING_CURSOR, ScanProgress.LISTING_ACCOUNT, ScanProgress.LISTING_TREE),
                reports.map { it.listing }.distinct(),
                "the cursor listing was tried first, then the account-wide offset listing, then the walk",
            )
            assertEquals(
                ScanProgress(items = 5, foldersDone = 3, foldersKnown = 3, foldersSkipped = 0, listing = ScanProgress.LISTING_TREE),
                reports.last(),
                "root, _INBOX and other listed; 3 files and 2 folders found",
            )
        }

    @Test
    fun `a scoped full listing reports the walk of the scope only`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        contentOf(url) != null -> respond(contentOf(url)!!, HttpStatusCode.OK, json)
                        else -> error("unexpected request: $url")
                    }
                }
            val reports = Collections.synchronizedList(mutableListOf<ScanProgress>())

            provider(engine).delta(
                null,
                null,
                ScanContext(null, emptyList(), { _, _ -> }, scopeRoots = listOf("/_INBOX"), onProgress = { reports += it }),
            )

            assertTrue(reports.all { it.listing == ScanProgress.LISTING_TREE }, "$reports")
            assertEquals(
                ScanProgress(items = 2, foldersDone = 1, foldersKnown = 1, foldersSkipped = 0, listing = ScanProgress.LISTING_TREE),
                reports.last(),
                "_INBOX listed; a.txt and _INBOX found",
            )
        }
}
