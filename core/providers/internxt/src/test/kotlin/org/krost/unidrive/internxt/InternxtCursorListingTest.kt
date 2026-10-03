package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ClosedReadChannelException
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The full enumeration of the whole drive by the cursor listings (`/files/sync`, `/folders/sync`): the paging, the
 * query of every page, the resume from the persisted cursors, the fallback to the offset listing for a server that
 * has no such endpoints, the guards against a server that loops, and the progress it reports.
 *
 * One fake drive serves the cursor endpoints and the offset endpoints from the same fixture, so what the two
 * strategies return can be compared.
 */
class InternxtCursorListingTest {
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

    // ---- the fake drive ----------------------------------------------------------------------------------------------

    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val root = "root"
    private val epoch = "1970-01-01T00:00:00.000Z"

    private fun folder(
        uuid: String,
        name: String,
        parent: String,
    ) = """{"uuid":"$uuid","plainName":"$name","parentUuid":"$parent","status":"EXISTS",""" +
        """"modificationTime":"2026-09-01T10:00:00.123Z","updatedAt":"2026-10-01T10:00:00.456Z"}"""

    private fun file(
        uuid: String,
        name: String,
        ext: String,
        folder: String,
        size: Int = 1,
    ) = """{"uuid":"$uuid","plainName":"$name","type":"$ext","size":"$size","folderUuid":"$folder","status":"EXISTS",""" +
        """"modificationTime":"2026-09-01T10:00:00.123Z","updatedAt":"2026-10-01T10:00:00.456Z"}"""

    // The listing is ordered by updatedAt, not by the tree: a child can arrive before its parent.
    private val standardFolderPages =
        listOf(
            listOf(folder("d4", "Deep", "d2"), folder("d2", "Sub", "d1")),
            listOf(folder("d3", "Pics", root)),
            listOf(folder("d1", "Docs", root)),
        )
    private val standardFilePages =
        listOf(
            listOf(file("f1", "top", "txt", root)),
            listOf(file("f2", "a", "txt", "d1", 2), file("f3", "b", "txt", "d2", 3)),
            listOf(file("f4", "p", "jpg", "d3", 4)),
            listOf(file("f5", "deep", "txt", "d4", 5)),
        )
    private val standardPaths =
        setOf(
            "/Docs",
            "/Docs/Sub",
            "/Docs/Sub/Deep",
            "/Pics",
            "/top.txt",
            "/Docs/a.txt",
            "/Docs/Sub/b.txt",
            "/Pics/p.jpg",
            "/Docs/Sub/Deep/deep.txt",
        )

    private fun filesBody(
        items: List<String>,
        next: String? = null,
    ): String = "{\"files\":[${items.joinToString(",")}]" + (if (next == null) "" else ",\"nextCursor\":\"$next\"") + "}"

    private fun cutByTheGateway() =
        ClosedReadChannelException(
            java.io.EOFException("Failed to parse HTTP response: the server prematurely closed the connection"),
        )

    private sealed interface Failure {
        data class Status(
            val code: Int,
        ) : Failure

        data object Cut : Failure
    }

    private class Seen(
        val stream: String,
        val updatedAt: String?,
        val cursor: String?,
        val status: String?,
        val limit: String?,
        val query: String,
    )

    /**
     * The drive as a server: [folderPages] and [filePages] are served page by page by `/folders/sync` and `/files/sync`
     * (the cursor of page i is `cursorOf(stream, i)`, an opaque string with characters that need URL encoding), and in
     * one piece by the offset endpoints when [offsetListing] is on. [failure] makes a page fail, [body] replaces one.
     * Every cursor request is recorded in [seen], every other request in [other].
     */
    private inner class DriveMock(
        val folderPages: List<List<String>> = standardFolderPages,
        val filePages: List<List<String>> = standardFilePages,
        val offsetListing: Boolean = false,
        val failure: (stream: String, page: Int) -> Failure? = { _, _ -> null },
        val body: (stream: String, cursor: String?) -> String? = { _, _ -> null },
    ) {
        val seen = CopyOnWriteArrayList<Seen>()
        val other = CopyOnWriteArrayList<String>()

        fun cursorOf(
            stream: String,
            page: Int,
        ) = "c-$stream-$page+/="

        fun pageOf(
            stream: String,
            cursor: String?,
        ): Int {
            if (cursor == null) return 0
            val match = Regex("""c-$stream-(\d+)\+/=""").matchEntire(cursor) ?: error("a malformed $stream cursor reached the server: $cursor")
            return match.groupValues[1].toInt()
        }

        private fun pageBody(
            stream: String,
            page: Int,
        ): String {
            val pages = if (stream == "folders") folderPages else filePages
            val next = if (page + 1 < pages.size) ",\"nextCursor\":\"${cursorOf(stream, page + 1)}\"" else ""
            return "{\"$stream\":[${pages[page].joinToString(",")}]$next}"
        }

        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val params = request.url.parameters
                val stream =
                    when {
                        path.endsWith("/drive/folders/sync") -> "folders"
                        path.endsWith("/drive/files/sync") -> "files"
                        else -> null
                    }
                if (stream == null) {
                    other += request.url.toString()
                    if (!offsetListing || !(path.endsWith("/drive/folders") || path.endsWith("/drive/files"))) {
                        error("unexpected request: ${request.url}")
                    }
                    val offset = params["offset"]?.toInt() ?: 0
                    val all = (if (path.endsWith("/drive/folders")) folderPages else filePages).flatten()
                    respond(if (offset == 0) all.joinToString(",", "[", "]") else "[]", HttpStatusCode.OK, jsonHeaders)
                } else {
                    val cursor = params["cursor"]
                    seen += Seen(stream, params["updatedAt"], cursor, params["status"], params["limit"], request.url.encodedQuery)
                    val custom = body(stream, cursor)
                    if (custom != null) {
                        respond(custom, HttpStatusCode.OK, jsonHeaders)
                    } else {
                        val page = pageOf(stream, cursor)
                        when (val f = failure(stream, page)) {
                            is Failure.Status -> respond("{}", HttpStatusCode.fromValue(f.code), jsonHeaders)
                            Failure.Cut -> throw cutByTheGateway()
                            null -> respond(pageBody(stream, page), HttpStatusCode.OK, jsonHeaders)
                        }
                    }
                }
            }
    }

    private class Killed : RuntimeException("killed by the test")

    /**
     * The engine's staging as a test double: [persistPage] keeps the rows and every marker, and the context of the next
     * attempt carries the last marker and the rows as `loadStagedItems` returns them (cloud identity only). It can die
     * after a number of pages, the way a crashed daemon stops: after the page is stored.
     */
    private class FakeStaging(
        var dieAfterPages: Int? = null,
    ) {
        val rows = ConcurrentHashMap<String, CloudItem>()
        val markers: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val pages = AtomicInteger()

        val persistPage: suspend (List<CloudItem>, String) -> Unit = { items, marker ->
            items.forEach { rows[it.id] = it }
            markers += marker
            val die = dieAfterPages
            if (die != null && pages.incrementAndGet() >= die) throw Killed()
        }

        // [strangers]: rows an offset listing staged earlier. They carry no mark of the cursor listing.
        fun context(
            strangers: Boolean = false,
            onProgress: ((ScanProgress) -> Unit)? = null,
        ): ScanContext {
            val staged = rows.values.sortedBy { it.id }.map { it.copy(path = "/${it.name}", created = null, mimeType = null) }
            val foreign =
                if (!strangers) {
                    emptyList()
                } else {
                    listOf(
                        CloudItem("ghost-folder", "Ghost", "/Ghost", 0, true, null, null, null, null),
                        CloudItem("ghost-file", "ghost.txt", "/ghost.txt", 1, false, null, null, null, null, parentId = "ghost-folder"),
                    )
                }
            return ScanContext(markers.lastOrNull(), staged + foreign, persistPage, onProgress = onProgress)
        }
    }

    private fun quiet(onProgress: ((ScanProgress) -> Unit)? = null) = ScanContext(null, emptyList(), { _, _ -> }, onProgress = onProgress)

    // A resumed row keeps the cloud identity staging holds: no creation time, and a root-level parent as null.
    private fun essence(item: CloudItem) = listOf(item.id, item.path, item.name, item.size, item.isFolder, item.modified, item.deleted)

    // ---- paging, dedupe, queries -------------------------------------------------------------------------------------

    @Test
    fun `both streams page to their last page, an item that turns up twice is kept once, and the paths come from the whole folder graph`() =
        runTest {
            // d3 is renamed while the listing runs, so it turns up again on the last folder page; f1 changes its size.
            val folderPages =
                listOf(
                    listOf(folder("d4", "Deep", "d2"), folder("d2", "Sub", "d1")),
                    listOf(folder("d3", "Pics", root)),
                    listOf(folder("d1", "Docs", root), folder("d3", "Photos", root)),
                )
            val filePages =
                listOf(
                    listOf(file("f1", "top", "txt", root, size = 1)),
                    listOf(file("f2", "a", "txt", "d1", 2), file("f3", "b", "txt", "d2", 3)),
                    listOf(file("f4", "p", "jpg", "d3", 4)),
                    listOf(file("f5", "deep", "txt", "d4", 5), file("f1", "top", "txt", root, size = 99)),
                )
            val drive = DriveMock(folderPages, filePages)
            val before = Instant.now()

            val page = provider(drive.engine).delta(null, null, quiet())

            assertEquals(
                setOf(
                    "/Docs",
                    "/Docs/Sub",
                    "/Docs/Sub/Deep",
                    "/Photos",
                    "/top.txt",
                    "/Docs/a.txt",
                    "/Docs/Sub/b.txt",
                    "/Photos/p.jpg",
                    "/Docs/Sub/Deep/deep.txt",
                ),
                page.items.map { it.path }.toSet(),
            )
            assertEquals(9, page.items.size, "every uuid once: ${page.items.map { it.path }}")
            assertEquals(99L, page.items.single { it.id == "f1" }.size, "the later version of f1 wins")
            assertTrue(page.complete)
            assertTrue(!page.hasMore)
            assertEquals(3, drive.seen.count { it.stream == "folders" }, "one request per folder page, no more after the last")
            assertEquals(4, drive.seen.count { it.stream == "files" }, "one request per file page, no more after the last")
            assertTrue(drive.other.isEmpty(), "the offset listing is not touched: ${drive.other}")
            val began = Instant.parse(page.cursor)
            assertTrue(!began.isBefore(before.minusSeconds(1)) && !began.isAfter(Instant.now()), "the cursor is when the listing began: ${page.cursor}")
        }

    @Test
    fun `without a scan context the listing keeps everything in memory and still pages to the end`() =
        runTest {
            val drive = DriveMock()

            val page = provider(drive.engine).delta(null)

            assertEquals(standardPaths, page.items.map { it.path }.toSet())
            assertTrue(page.complete)
            assertEquals(3 + 4, drive.seen.size)
        }

    @Test
    fun `the first page of a stream asks by updatedAt and every later page by its cursor alone`() =
        runTest {
            val drive = DriveMock()

            provider(drive.engine).delta(null, null, quiet())

            for ((stream, pages) in listOf("folders" to 3, "files" to 4)) {
                val requests = drive.seen.filter { it.stream == stream }
                assertEquals(pages, requests.size, stream)
                assertEquals(epoch, requests.first().updatedAt, "$stream: the first page asks for everything since the beginning of time")
                assertNull(requests.first().cursor, "$stream: and has no cursor yet")
                requests.forEachIndexed { i, r ->
                    assertEquals("EXISTS", r.status, "$stream page $i names the status")
                    assertEquals("1000", r.limit, "$stream page $i names the limit")
                    if (i > 0) {
                        // The fake rejects any cursor that is not exactly what it issued, so the characters + / = survived the URL.
                        assertEquals(drive.cursorOf(stream, i), r.cursor, "$stream page $i asks with the cursor of the page before")
                        assertNull(r.updatedAt, "$stream page $i must not name updatedAt")
                        assertTrue(!r.query.contains("updatedAt"), r.query)
                    }
                }
            }
            assertTrue(drive.seen.none { it.query.contains("offset=") || it.query.contains("sort=") }, "the cursor listing has no offset or sort")
        }

    @Test
    fun `a stream that has no items at all ends on its first page`() =
        runTest {
            val drive = DriveMock(folderPages = listOf(emptyList()), filePages = listOf(emptyList()))

            val page = provider(drive.engine).delta(null, null, quiet())

            assertEquals(emptyList(), page.items)
            assertTrue(page.complete)
            assertEquals(1, drive.seen.count { it.stream == "folders" })
            assertEquals(1, drive.seen.count { it.stream == "files" })
        }

    @Test
    fun `the cursor listing returns what the offset listing returns for the same drive`() =
        runTest {
            val viaCursor = provider(DriveMock().engine).delta(null, null, quiet())
            val offsetOnly =
                DriveMock(
                    offsetListing = true,
                    failure = { _, page -> if (page == 0) Failure.Status(404) else null },
                )

            val viaOffset = provider(offsetOnly.engine).delta(null, null, quiet())

            assertEquals(standardPaths, viaCursor.items.map { it.path }.toSet())
            assertEquals(viaOffset.items.toSet(), viaCursor.items.toSet(), "same items, same paths, same everything")
            assertEquals(viaOffset.complete, viaCursor.complete)
            assertTrue(offsetOnly.other.any { it.contains("offset=") }, "the second run really went through the offset listing")
        }

    // ---- resume from the persisted cursors ---------------------------------------------------------------------------

    @Test
    fun `a listing killed part way resumes from the persisted cursors, asks for the later pages only and gives the same result`() =
        runTest {
            val clean = provider(DriveMock().engine).delta(null, null, quiet())

            val staging = FakeStaging(dieAfterPages = 4)
            assertFailsWith<Killed> { provider(DriveMock().engine).delta(null, null, staging.context()) }
            val marker = assertNotNull(decodeCursorMarker(staging.markers.last()), "the marker is the cursor listing's")
            val stagedAtKill = staging.rows.size
            assertTrue(stagedAtKill > 0 && stagedAtKill < 9, "killed part way: $stagedAtKill rows staged")

            // The restart. Rows an offset listing staged earlier are among the staged ones and must not be merged.
            staging.dieAfterPages = null
            val second = DriveMock()
            val reports = Collections.synchronizedList(mutableListOf<ScanProgress>())
            val resumed = provider(second.engine).delta(null, null, staging.context(strangers = true, onProgress = { reports += it }))

            fun pagesAsked(stream: String) = second.seen.filter { it.stream == stream }.map { second.pageOf(stream, it.cursor) }

            fun pagesLeft(
                stream: String,
                position: StreamPosition,
                total: Int,
            ) = when {
                position.done -> emptyList()
                position.cursor == null -> (0 until total).toList()
                else -> (second.pageOf(stream, position.cursor) until total).toList()
            }
            assertEquals(pagesLeft("folders", marker.folders, 3), pagesAsked("folders"), "folders: only the pages after the persisted cursor")
            assertEquals(pagesLeft("files", marker.files, 4), pagesAsked("files"), "files: only the pages after the persisted cursor")
            assertTrue(second.seen.size < 7, "not every page was asked for again: ${second.seen.size}")
            for ((stream, position) in listOf("folders" to marker.folders, "files" to marker.files)) {
                val asked = second.seen.filter { it.stream == stream }
                if (position.cursor != null) assertNull(asked.first().updatedAt, "$stream continues by its cursor, it does not start over")
            }

            assertEquals(clean.items.map(::essence).toSet(), resumed.items.map(::essence).toSet(), "the result of the uninterrupted listing")
            assertEquals(clean.items.size, resumed.items.size)
            assertTrue(resumed.complete)
            assertTrue(resumed.items.none { it.id.startsWith("ghost") }, "rows of another listing are not part of this one")
            assertEquals(marker.startedAt, resumed.cursor, "the resumed listing keeps the instant the enumeration began")

            assertEquals(stagedAtKill, reports.first().items, "a resumed listing reports from the staged rows, not from zero")
            assertTrue(reports.zipWithNext().all { (a, b) -> b.items >= a.items }, "the count only grows across the seam: ${reports.map { it.items }}")
            assertEquals(9, reports.last().items)
            assertEquals(marker.startedAt, decodeCursorMarker(staging.markers.last())!!.startedAt, "and so does every marker written after it")
            assertTrue(decodeCursorMarker(staging.markers.last())!!.let { it.folders.done && it.files.done }, "the last marker says both streams are done")
        }

    @Test
    fun `a listing whose streams were both done resumes with no request at all`() =
        runTest {
            val staging = FakeStaging()
            provider(DriveMock().engine).delta(null, null, staging.context())
            val finished = decodeCursorMarker(staging.markers.last())!!
            assertTrue(finished.folders.done && finished.files.done)

            // The attempt that stored the last page died before the engine took the result.
            val again = DriveMock()
            val page = provider(again.engine).delta(null, null, staging.context())

            assertTrue(again.seen.isEmpty(), "nothing left to ask: ${again.seen.size} requests")
            assertEquals(standardPaths, page.items.map { it.path }.toSet())
            assertTrue(page.complete)
        }

    @Test
    fun `a marker of the offset listing is not resumable, the cursor listing starts over, ignores its rows and replaces the marker`() =
        runTest {
            val drive = DriveMock()
            val staging = FakeStaging()
            val offsetRows =
                listOf(
                    CloudItem("ghost-folder", "Ghost", "/Ghost", 0, true, null, null, null, null),
                    CloudItem("ghost-file", "ghost.txt", "/ghost.txt", 1, false, null, null, null, null, parentId = "ghost-folder"),
                )

            val page = provider(drive.engine).delta(null, null, ScanContext(InternxtProvider.buildMarker(2, 3), offsetRows, staging.persistPage))

            assertEquals(standardPaths, page.items.map { it.path }.toSet(), "the rows of the offset listing may describe items that are gone")
            for (stream in listOf("folders", "files")) {
                val first = drive.seen.first { it.stream == stream }
                assertEquals(epoch, first.updatedAt, "$stream starts from scratch")
                assertNull(first.cursor)
            }
            assertTrue(staging.markers.isNotEmpty() && staging.markers.all { decodeCursorMarker(it) != null }, "the offset marker is replaced: ${staging.markers}")
            assertTrue(decodeCursorMarker(staging.markers.last())!!.let { it.folders.done && it.files.done })
        }

    @Test
    fun `a marker that claims progress while the rows it stands for are gone is not trusted`() =
        runTest {
            // Both streams "done" and no row staged: resuming would hand the engine an empty drive as a complete listing,
            // and the engine reads what is missing from a complete listing as deleted.
            val claimsDone = cursorMarker("2026-10-03T10:00:00Z", StreamPosition(done = true), StreamPosition(done = true))
            val drive = DriveMock()

            val page = provider(drive.engine).delta(null, null, ScanContext(claimsDone, emptyList(), { _, _ -> }))

            assertEquals(standardPaths, page.items.map { it.path }.toSet(), "the listing started over instead of returning nothing")
            assertEquals(3 + 4, drive.seen.size, "every page was asked for")
            assertTrue(page.cursor != "2026-10-03T10:00:00Z", "and it began now, not when the dead marker says")

            val tagged = CloudItem("d1", "Docs", "/Docs", 0, true, null, null, CURSOR_STAGED_TAG, null)
            val untagged = tagged.copy(hash = null)
            val unstarted = cursorMarker("2026-10-03T10:00:00Z", StreamPosition(), StreamPosition())
            assertNotNull(cursorResumeOf(unstarted, emptyList()), "no progress is claimed, nothing contradicts it")
            assertNull(cursorResumeOf(claimsDone, emptyList()))
            assertNull(cursorResumeOf(claimsDone, listOf(untagged)), "rows of another listing do not vouch for this marker")
            assertEquals(listOf(tagged), assertNotNull(cursorResumeOf(claimsDone, listOf(untagged, tagged))).rows)
            assertNull(cursorResumeOf(InternxtProvider.buildMarker(2, 3), listOf(tagged)), "an offset marker is never resumed, whatever rows are there")
        }

    @Test
    fun `staged rows that the cursor listing did not stage are left out even under a cursor marker`() =
        runTest {
            val staging = FakeStaging()
            provider(DriveMock().engine).delta(null, null, staging.context()) // stores everything, both streams done
            val page = provider(DriveMock().engine).delta(null, null, staging.context(strangers = true))

            assertEquals(standardPaths, page.items.map { it.path }.toSet(), "no ghost folder or ghost file")
        }

    @Test
    fun `neither marker format is read as the other`() =
        runTest {
            val cursors = cursorMarker("2026-10-03T10:00:00Z", StreamPosition(cursor = "c-folders-1+/="), StreamPosition(done = true))

            val parsed = InternxtProvider.parseResumeOffsets(cursors)
            assertEquals(0 to 0, parsed.filesOffset to parsed.foldersOffset, "a cursor marker is no pair of offsets")
            assertNull(decodeCursorMarker(InternxtProvider.buildMarker(120, 45)), "a pair of offsets is no cursor marker")

            // End to end: the cursor endpoints are missing, so the offset listing runs, and it starts at offset 0 whatever the marker.
            val drive = DriveMock(offsetListing = true, failure = { _, page -> if (page == 0) Failure.Status(404) else null })
            val unstarted = cursorMarker("2026-10-03T10:00:00Z", StreamPosition(), StreamPosition())
            val page = provider(drive.engine).delta(null, null, ScanContext(unstarted, emptyList(), { _, _ -> }))

            assertEquals(standardPaths, page.items.map { it.path }.toSet())
            val offsets = drive.other.map { Regex("offset=(\\d+)").find(it)!!.groupValues[1].toInt() }
            assertTrue(offsets.isNotEmpty() && offsets.all { it % 999 == 0 } && offsets.contains(0), "the offset listing began at 0: $offsets")
        }

    // ---- when the endpoints are missing and when they fail -----------------------------------------------------------

    @Test
    fun `no such endpoint on the first page of either stream falls back to the offset listing`() =
        runTest {
            val viaCursor = provider(DriveMock().engine).delta(null, null, quiet())

            for (status in listOf(404, 405, 501)) {
                for (stream in listOf("folders", "files")) {
                    val drive =
                        DriveMock(
                            offsetListing = true,
                            failure = { s, page -> if (s == stream && page == 0) Failure.Status(status) else null },
                        )

                    val viaOffset = provider(drive.engine).delta(null, null, quiet())

                    assertEquals(viaCursor.items.toSet(), viaOffset.items.toSet(), "HTTP $status on the first page of $stream")
                    assertTrue(viaOffset.complete)
                    assertTrue(drive.other.any { it.contains("offset=") }, "HTTP $status on $stream: the offset listing ran")
                }
            }
        }

    @Test
    fun `an overloaded server, a connection cut part way and a missing later page fail the gather and do not fall back`() =
        runTest {
            // 503 on the first page: the whole ladder, then the failure. Not "no such endpoint".
            val overloaded = DriveMock(failure = { s, page -> if (s == "folders" && page == 0) Failure.Status(503) else null })
            val unavailable = assertFailsWith<InternxtApiException> { provider(overloaded.engine).delta(null, null, quiet()) }
            assertEquals(503, unavailable.statusCode)
            assertEquals(3, overloaded.seen.count { it.stream == "folders" }, "the retry ladder of three attempts")
            assertTrue(overloaded.other.isEmpty(), "no offset listing after a 503: ${overloaded.other}")

            // A connection cut in the middle of the files stream: the marker stays after the last stored page.
            val staging = FakeStaging()
            val cut = DriveMock(failure = { s, page -> if (s == "files" && page == 2) Failure.Cut else null })
            val cutOff = assertFailsWith<InternxtApiException> { provider(cut.engine).delta(null, null, staging.context()) }
            assertEquals(503, cutOff.statusCode)
            assertTrue(cut.other.isEmpty(), "no offset listing after a cut connection: ${cut.other}")
            val marker = assertNotNull(decodeCursorMarker(staging.markers.last()))
            assertEquals(cut.cursorOf("files", 2), marker.files.cursor, "the files stream stands after its last stored page")
            assertTrue(!marker.files.done)

            // 404 on a later page is a failure, not "this server has no such endpoint".
            val vanished = DriveMock(failure = { s, page -> if (s == "files" && page == 1) Failure.Status(404) else null })
            val notFound = assertFailsWith<InternxtApiException> { provider(vanished.engine).delta(null, null, quiet()) }
            assertEquals(404, notFound.statusCode)
            assertEquals(2, vanished.seen.count { it.stream == "files" }, "asked once, not retried")
            assertTrue(vanished.other.isEmpty(), "no offset listing after a 404 on a later page: ${vanished.other}")
        }

    @Test
    fun `a failure after some pages leaves a marker that the next attempt resumes from, without the offset listing`() =
        runTest {
            val staging = FakeStaging()
            val cut = DriveMock(failure = { s, page -> if (s == "files" && page == 2) Failure.Cut else null })
            assertFailsWith<InternxtApiException> { provider(cut.engine).delta(null, null, staging.context()) }

            val healthy = DriveMock()
            val page = provider(healthy.engine).delta(null, null, staging.context())

            assertEquals(standardPaths, page.items.map { it.path }.toSet())
            assertTrue(page.complete)
            assertEquals(listOf(2, 3), healthy.seen.filter { it.stream == "files" }.map { healthy.pageOf("files", it.cursor) }, "files go on from the cursor of page 2")
            assertNull(healthy.seen.first { it.stream == "files" }.updatedAt)
            assertTrue(healthy.other.isEmpty())
        }

    // ---- the guards against a server that loops ----------------------------------------------------------------------

    @Test
    fun `a page whose next cursor was already asked for ends the stream and leaves the gather incomplete`() =
        runTest {
            val staging = FakeStaging()
            val drive =
                DriveMock(
                    body = { stream, cursor ->
                        if (stream != "files") {
                            null
                        } else {
                            when (cursor) {
                                null -> filesBody(listOf(file("f1", "top", "txt", root)), next = "X")
                                "X" -> filesBody(listOf(file("f2", "a", "txt", "d1", 2)), next = "X")
                                else -> error("a cursor the fake never issued: $cursor")
                            }
                        }
                    },
                )

            val page = provider(drive.engine).delta(null, null, staging.context())

            assertEquals(2, drive.seen.count { it.stream == "files" }, "the repeated cursor is not asked for again")
            assertEquals(setOf("/Docs", "/Docs/Sub", "/Docs/Sub/Deep", "/Pics", "/top.txt", "/Docs/a.txt"), page.items.map { it.path }.toSet(), "the rows of the page are kept")
            assertTrue(!page.complete, "nobody saw the end of the files stream: the engine must not infer deletions from it")
            val marker = assertNotNull(decodeCursorMarker(staging.markers.last()))
            assertEquals(StreamPosition(cursor = "X"), marker.files, "the position is not advanced: a restart asks for that page again")
        }

    @Test
    fun `an empty page that carries a cursor is tolerated once, a second one in a row ends the stream`() =
        runTest {
            // Tolerated: items, empty (cursor), items, end.
            val tolerated =
                DriveMock(
                    body = { stream, cursor ->
                        if (stream != "files") {
                            null
                        } else {
                            when (cursor) {
                                null -> filesBody(listOf(file("f1", "top", "txt", root)), next = "c1")
                                "c1" -> filesBody(emptyList(), next = "c2")
                                "c2" -> filesBody(listOf(file("f2", "a", "txt", "d1", 2)))
                                else -> error("a cursor the fake never issued: $cursor")
                            }
                        }
                    },
                )
            val ok = provider(tolerated.engine).delta(null, null, quiet())
            assertEquals(3, tolerated.seen.count { it.stream == "files" })
            assertTrue(ok.complete)
            assertTrue(ok.items.any { it.path == "/Docs/a.txt" }, "the page after the empty one was read")

            // Isolated empty pages never add up: only two in a row end the stream.
            val isolated =
                DriveMock(
                    body = { stream, cursor ->
                        if (stream != "files") {
                            null
                        } else {
                            when (cursor) {
                                null -> filesBody(listOf(file("f1", "top", "txt", root)), next = "c1")
                                "c1" -> filesBody(emptyList(), next = "c2")
                                "c2" -> filesBody(listOf(file("f2", "a", "txt", "d1", 2)), next = "c3")
                                "c3" -> filesBody(emptyList(), next = "c4")
                                "c4" -> filesBody(listOf(file("f3", "b", "txt", "d2", 3)))
                                else -> error("a cursor the fake never issued: $cursor")
                            }
                        }
                    },
                )
            val all = provider(isolated.engine).delta(null, null, quiet())
            assertEquals(5, isolated.seen.count { it.stream == "files" })
            assertTrue(all.complete)
            assertEquals(3, all.items.count { !it.isFolder })

            // Ended: items, empty (cursor), empty (cursor). The third cursor is never asked for.
            val staging = FakeStaging()
            val endless =
                DriveMock(
                    body = { stream, cursor ->
                        if (stream != "files") {
                            null
                        } else {
                            when (cursor) {
                                null -> filesBody(listOf(file("f1", "top", "txt", root)), next = "c1")
                                "c1" -> filesBody(emptyList(), next = "c2")
                                "c2" -> filesBody(emptyList(), next = "c3")
                                else -> error("the stream should have ended before asking for $cursor")
                            }
                        }
                    },
                )
            val cutShort = provider(endless.engine).delta(null, null, staging.context())
            assertEquals(3, endless.seen.count { it.stream == "files" })
            assertTrue(!cutShort.complete, "a stream ended by a guard is not a complete listing")
            assertEquals(StreamPosition(cursor = "c2"), decodeCursorMarker(staging.markers.last())!!.files, "the position stands after the last page that was kept")
        }

    // ---- what is reported while it runs ------------------------------------------------------------------------------

    @Test
    fun `the listing is named at its start and reports the count of both streams after every page`() =
        runTest {
            val reports = Collections.synchronizedList(mutableListOf<ScanProgress>())

            provider(DriveMock().engine).delta(null, null, quiet { reports += it })

            assertTrue(reports.all { it.listing == ScanProgress.LISTING_CURSOR }, "$reports")
            assertTrue(
                reports.all { it.foldersDone == null && it.foldersKnown == null && it.foldersSkipped == null },
                "a cursor listing has no folder total to report: $reports",
            )
            assertEquals(0, reports.first().items, "named before the first page is back")
            assertEquals(9, reports.last().items, "folders and files together")
            assertEquals(1 + 3 + 4, reports.size, "the start and one report per page")
            assertTrue(reports.zipWithNext().all { (a, b) -> b.items >= a.items }, "the count only grows: ${reports.map { it.items }}")
        }

    @Test
    fun `the legacy page callback ticks while the pages come in`() =
        runTest {
            val pages = (0 until 6).map { p -> (0 until 1000).map { i -> file("f${p * 1000 + i}", "n${p * 1000 + i}", "txt", root) } }
            val ticks = Collections.synchronizedList(mutableListOf<Int>())

            val page = provider(DriveMock(listOf(emptyList()), pages).engine).delta(null, { ticks += it }, quiet())

            assertEquals(6000, page.items.size)
            assertTrue(ticks.isNotEmpty() && ticks.last() >= 5000, "the heartbeat fired past 5,000 items: $ticks")
            assertTrue(ticks.zipWithNext().all { (a, b) -> b >= a }, "never a lower total: $ticks")
        }

    // ---- what stays as it was ----------------------------------------------------------------------------------------

    @Test
    fun `an incremental delta does not use the cursor listing`() =
        runTest {
            val drive = DriveMock(offsetListing = true)

            provider(drive.engine).delta("2026-10-01T00:00:00Z", null, quiet())

            assertTrue(drive.seen.isEmpty(), "no cursor listing for an incremental poll")
            assertTrue(drive.other.isNotEmpty() && drive.other.all { it.contains("sort=updatedAt") && it.contains("status=ALL") }, "${drive.other}")
        }

    // ---- the API calls -----------------------------------------------------------------------------------------------

    private fun serviceOn(engine: MockEngine) =
        InternxtApiService(
            InternxtConfig(),
            credentialsProvider = { _ ->
                InternxtCredentials(jwt = "test-jwt", mnemonic = "test-mnemonic", rootFolderId = "root", email = "test@example.invalid")
            },
            driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
            httpClient = HttpClient(engine),
        )

    @Test
    fun `a cursor page is a GET with the bearer token, the first by updatedAt, and gives its items and its next cursor`() =
        runTest {
            val requests = CopyOnWriteArrayList<HttpRequestData>()
            val service =
                serviceOn(
                    MockEngine { request ->
                        requests += request
                        respond(
                            """{"folders":[{"uuid":"d1","plainName":"Docs","parentUuid":"root","status":"EXISTS"}],"nextCursor":"n+1/="}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    },
                )

            val first = service.getFoldersSync(updatedAt = epoch, cursor = null)
            val later = service.getFoldersSync(updatedAt = null, cursor = first.nextCursor)

            assertEquals(listOf("d1"), first.items.map { it.uuid })
            assertEquals("n+1/=", first.nextCursor)
            assertEquals(2, requests.size)
            assertEquals("/drive/folders/sync", requests[0].url.encodedPath)
            assertEquals("Bearer test-jwt", requests[0].headers[HttpHeaders.Authorization])
            assertEquals(mapOf("status" to "EXISTS", "limit" to "1000", "updatedAt" to epoch), requests[0].url.parameters.entries().associate { it.key to it.value.single() })
            assertEquals(mapOf("status" to "EXISTS", "limit" to "1000", "cursor" to "n+1/="), requests[1].url.parameters.entries().associate { it.key to it.value.single() })
            assertEquals(1, later.items.size)
            service.close()
        }

    @Test
    fun `the files listing goes to files sync`() =
        runTest {
            val paths = CopyOnWriteArrayList<String>()
            val service =
                serviceOn(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        respond("""{"files":[{"uuid":"f1","plainName":"a","type":"txt","size":"3"}]}""", HttpStatusCode.OK, jsonHeaders)
                    },
                )

            val page = service.getFilesSync(updatedAt = epoch, cursor = null)

            assertEquals(listOf("/drive/files/sync"), paths)
            assertEquals(listOf("f1"), page.items.map { it.uuid })
            assertNull(page.nextCursor, "no nextCursor is the last page")
            service.close()
        }

    @Test
    fun `no such endpoint is answered once and not retried, an unavailable server is retried`() =
        runTest {
            for (status in listOf(404, 405, 501)) {
                val calls = AtomicInteger()
                val service = serviceOn(MockEngine { calls.incrementAndGet(); respond("{}", HttpStatusCode.fromValue(status), jsonHeaders) })
                val failure = assertFailsWith<InternxtApiException> { service.getFoldersSync(updatedAt = epoch, cursor = null) }
                assertEquals(status, failure.statusCode)
                assertEquals(1, calls.get(), "HTTP $status is not retried")
                service.close()
            }
            val calls = AtomicInteger()
            val service = serviceOn(MockEngine { calls.incrementAndGet(); respond("{}", HttpStatusCode.ServiceUnavailable, jsonHeaders) })
            assertEquals(503, assertFailsWith<InternxtApiException> { service.getFilesSync(updatedAt = epoch, cursor = null) }.statusCode)
            assertEquals(3, calls.get(), "HTTP 503 goes through the whole ladder")
            service.close()
        }

    // ---- parsing, query and marker -----------------------------------------------------------------------------------

    @Test
    fun `a page parses tolerantly - unknown keys, nulls where the model has a default, no cursor, no array`() {
        val folders =
            parseFoldersSyncPage(
                """{"folders":[{"bucket":"b","createdAt":"2026-01-01T00:00:00.000Z","creationTime":"2026-01-01T00:00:00.000Z","deleted":false,
                    "encryptVersion":"03-aes","id":42,"modificationTime":"2026-01-02T00:00:00.123Z","name":"enc","parent":null,"parentId":null,
                    "parentUuid":"root","plainName":"Docs","removed":false,"size":0,"status":"EXISTS","type":"folder",
                    "updatedAt":"2026-01-02T00:00:00.123Z","user":{"id":7},"userId":7,"uuid":"d1","aFutureKey":[1,2,3]}],
                  "nextCursor":"abc"}""",
            )
        assertEquals(listOf("d1"), folders.items.map { it.uuid })
        assertEquals("Docs", folders.items.single().plainName)
        assertEquals(0L, folders.items.single().parentId, "null where the model has a default takes the default")
        assertEquals("abc", folders.nextCursor)

        val files =
            parseFilesSyncPage(
                """{"files":[{"bucket":"b","createdAt":"2026-01-01T00:00:00.000Z","creationTime":"2026-01-01T00:00:00.000Z",
                    "encryptVersion":"03-aes","fileId":"abc","folder":{"id":1,"uuid":"d1"},"folderId":null,"folderUuid":"d1","id":9,
                    "modificationTime":"2026-01-02T00:00:00.123Z","name":"enc","plainName":"a","size":"12","status":"EXISTS","type":"txt",
                    "updatedAt":"2026-01-02T00:00:00.123Z","userId":7,"uuid":"f1"}],"nextCursor":null}""",
            )
        assertEquals("12", files.items.single().size)
        assertEquals("d1", files.items.single().folderUuid)
        assertNull(files.nextCursor, "a null nextCursor is the last page")

        assertNull(parseFilesSyncPage("""{"files":[]}""").nextCursor, "an absent nextCursor is the last page")
        assertNull(parseFilesSyncPage("""{"files":[],"nextCursor":""}""").nextCursor, "a blank one too")
        assertEquals(emptyList(), parseFoldersSyncPage("""{"nextCursor":"x"}""").items, "no array is an empty page")
        assertEquals(emptyList(), parseFoldersSyncPage("""{"folders":null}""").items)
    }

    @Test
    fun `an item that cannot be read fails the page instead of being dropped`() {
        assertFailsWith<kotlinx.serialization.SerializationException> {
            parseFilesSyncPage("""{"files":[{"uuid":"f1","plainName":"a"},{"plainName":"no uuid"}]}""")
        }
        assertFailsWith<kotlinx.serialization.SerializationException> { parseFoldersSyncPage("[]") }
    }

    @Test
    fun `the query of the first page names updatedAt, a later page names only its cursor`() {
        assertEquals(
            mapOf("status" to "EXISTS", "limit" to "1000", "updatedAt" to epoch),
            syncQueryParams(updatedAt = epoch, cursor = null, status = "EXISTS", limit = 1000),
        )
        assertEquals(
            mapOf("status" to "EXISTS", "limit" to "1000", "cursor" to "next"),
            syncQueryParams(updatedAt = null, cursor = "next", status = "EXISTS", limit = 1000),
        )
        assertEquals(
            mapOf("status" to "EXISTS", "limit" to "50", "cursor" to "next"),
            syncQueryParams(updatedAt = epoch, cursor = "next", status = "EXISTS", limit = 50),
            "given both, the cursor wins and updatedAt is not sent",
        )
    }

    @Test
    fun `the marker round-trips, whatever characters a cursor holds, and anything else is not a cursor marker`() {
        val odd = "a|b\"c{}\\ é 日本 =+/\n"
        val raw = cursorMarker("2026-10-03T10:00:00Z", StreamPosition(cursor = odd), StreamPosition(done = true))

        val marker = assertNotNull(decodeCursorMarker(raw))
        assertEquals(odd, marker.folders.cursor)
        assertTrue(marker.files.done)
        assertEquals("cursor", marker.strategy)
        assertEquals("EXISTS", marker.status)
        assertEquals("2026-10-03T10:00:00Z", marker.startedAt)

        val anotherStrategy = raw.replace("\"strategy\":\"cursor\"", "\"strategy\":\"tree\"")
        assertTrue(anotherStrategy != raw)
        for (bad in listOf(null, "", " ", "120|45", "0|0", "{}", "[]", "null", "garbage", raw.dropLast(1), anotherStrategy)) {
            assertNull(decodeCursorMarker(bad), "not resumable by this strategy: $bad")
        }
        assertNull(decodeCursorMarker(raw.replace("EXISTS", "ALL")), "a cursor belongs to the status it was issued for")
        assertNull(decodeCursorMarker(raw.replace("2026-10-03T10:00:00Z", "yesterday")), "no start instant, no cursor")
        assertNull(decodeCursorMarker(cursorMarker("2026-10-03T10:00:00Z", StreamPosition(cursor = " "), StreamPosition())), "a blank cursor is damage")
    }
}
