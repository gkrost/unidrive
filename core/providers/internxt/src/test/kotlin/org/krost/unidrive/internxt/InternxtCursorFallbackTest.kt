package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stream whose cursor the server cannot answer. Live, the folders stream was answered HTTP 408 after a constant
 * ~115 s on one cursor, on every attempt, so the scan that resumed that cursor could never end; the official client
 * got past it by asking from an `updatedAt` a little before the cursor without the status filter.
 *
 * The fake server behaves like the public server source: no status filter answers rows of every status, each naming
 * its own; a cursor carries the status it was issued for (none, without a filter) and is refused with a 400 for any
 * other; the first page of a stream needs `updatedAt`. It can wall one cursor: a request for it with the status
 * filter times out (408), the `updatedAt` form without the filter answers.
 */
class InternxtCursorFallbackTest {
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

    // ---- the fake server -------------------------------------------------------------------------------------------

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val statuses = setOf("EXISTS", "TRASHED", "DELETED")
    private val millis = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val micros = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    private fun at(time: String) = Instant.parse("2026-07-28T${time}Z")

    private data class Row(
        val uuid: String,
        val updatedAt: Instant,
        val status: String = "EXISTS",
    )

    private class Seen(
        val stream: String,
        val updatedAt: String?,
        val cursor: String?,
        val cursorUuid: String?,
        val status: String?,
        val limit: String?,
    )

    private class CursorBody(
        val updatedAt: Instant,
        val uuid: String,
        val status: String?,
    )

    // The folders of the account, in the order the keyset lists them (updatedAt, uuid). With pages of two, the filtered
    // listing is [a1 a2] [a3 a4] [b2 b4] [b5]; a4 ends the second page, so the cursor after it is the walled one.
    // Without the filter, from two minutes before a4, it is [a3 a4] [b1 b2] [b3 b4] [b5 b6], tombstones among them.
    private fun accountFolders() =
        listOf(
            Row("a1", at("19:30:00")),
            Row("a2", at("19:31:00")),
            Row("a3", at("19:36:30")),
            Row("a4", at("19:37:56")),
            Row("b1", at("19:40:00"), "TRASHED"),
            Row("b2", at("19:45:00")),
            Row("b3", at("19:50:00"), "DELETED"),
            Row("b4", at("19:55:00")),
            Row("b5", at("20:00:00")),
            Row("b6", at("20:01:00"), "TRASHED"),
        )

    private fun accountFiles() =
        listOf(
            Row("f1", at("19:10:00")),
            Row("f2", at("19:20:00")),
            Row("f3", at("19:30:00")),
        )

    private val liveFolderPaths = setOf("/a1", "/a2", "/a3", "/a4", "/b2", "/b4", "/b5")
    private val liveFilePaths = setOf("/f1.txt", "/f2.txt", "/f3.txt")

    private fun isWallOnFolders(seen: Seen) = seen.stream == "folders" && seen.status == "EXISTS" && seen.cursorUuid == "a4"

    private inner class SyncServer(
        val folders: MutableList<Row> = accountFolders().toMutableList(),
        val files: MutableList<Row> = accountFiles().toMutableList(),
        val pageSize: Int = 2,
        // A request for which this is true is answered with [wallStatus] (408: the server's timeout), however often it comes.
        val wall: (Seen) -> Boolean = { false },
        val wallStatus: HttpStatusCode = HttpStatusCode.RequestTimeout,
        // Any request that is not a sync listing (the tree walk) is answered with this status instead of failing the test.
        val otherStatus: HttpStatusCode? = null,
        // The pages asked for without a status filter leave the status out of their rows.
        val rowsWithoutStatus: Boolean = false,
    ) {
        val seen = CopyOnWriteArrayList<Seen>()
        val wallHits = AtomicInteger()

        private fun cursorFor(
            row: Row,
            status: String?,
        ): String {
            val fields = listOfNotNull("\"updatedAt\":\"${micros.format(row.updatedAt)}\"", "\"uuid\":\"${row.uuid}\"", status?.let { "\"status\":\"$it\"" })
            return Base64.getEncoder().encodeToString("{${fields.joinToString(",")}}".toByteArray())
        }

        private fun decodeCursor(token: String): CursorBody? =
            runCatching {
                val fields = Json.parseToJsonElement(String(Base64.getDecoder().decode(token))).jsonObject
                CursorBody(
                    Instant.parse(fields.getValue("updatedAt").jsonPrimitive.content),
                    fields.getValue("uuid").jsonPrimitive.content,
                    fields["status"]?.jsonPrimitive?.contentOrNull,
                )
            }.getOrNull()

        private fun rowJson(
            stream: String,
            row: Row,
            withStatus: Boolean,
        ): String {
            val status = if (withStatus) ",\"status\":\"${row.status}\"" else ""
            val common = "\"uuid\":\"${row.uuid}\",\"plainName\":\"${row.uuid}\"$status,\"modificationTime\":\"2026-07-01T10:00:00.000Z\",\"updatedAt\":\"${millis.format(row.updatedAt)}\""
            return if (stream == "folders") {
                val flags = ",\"removed\":${row.status == "DELETED"},\"deleted\":${row.status == "TRASHED"}"
                "{$common,\"parentUuid\":\"root\"$flags}"
            } else {
                "{$common,\"type\":\"txt\",\"size\":\"1\",\"folderUuid\":\"root\"}"
            }
        }

        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val stream =
                    when {
                        path.endsWith("/drive/folders/sync") -> "folders"
                        path.endsWith("/drive/files/sync") -> "files"
                        otherStatus != null -> return@MockEngine respond("{}", otherStatus, jsonHeaders)
                        else -> error("unexpected request: ${request.url}")
                    }
                val params = request.url.parameters
                val status = params["status"]
                val token = params["cursor"]
                val cursor = token?.let(::decodeCursor)
                val record = Seen(stream, params["updatedAt"], token, cursor?.uuid, status, params["limit"])
                seen += record
                fun refuse(message: String) = respond("""{"statusCode":400,"message":"$message"}""", HttpStatusCode.BadRequest, jsonHeaders)
                when {
                    status != null && status !in statuses -> refuse("status must be one of the following values")
                    token == null && params["updatedAt"] == null -> refuse("updatedAt must be a valid ISO8601 date")
                    token != null && cursor == null -> refuse("Invalid cursor")
                    cursor != null && cursor.status != status -> refuse("Cursor does not match status filter")
                    wall(record) -> {
                        wallHits.incrementAndGet()
                        respond("""{"statusCode":${wallStatus.value},"message":"${wallStatus.description}"}""", wallStatus, jsonHeaders)
                    }
                    else -> {
                        val listed = (if (stream == "folders") folders else files).filter { status == null || it.status == status }
                        val ordered = listed.sortedWith(compareBy<Row>({ it.updatedAt }, { it.uuid }))
                        val after =
                            if (cursor != null) {
                                ordered.filter { r -> isAfter(r, cursor) }
                            } else {
                                ordered.filter { it.updatedAt.isAfter(Instant.parse(params["updatedAt"]!!)) }
                            }
                        val page = after.take(pageSize)
                        val next = if (after.size > pageSize) cursorFor(page.last(), status) else null
                        val rows = page.joinToString(",") { rowJson(stream, it, withStatus = !(rowsWithoutStatus && status == null)) }
                        respond("""{"$stream":[$rows],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            }
    }

    // The keyset: strictly after the cursor's (updatedAt, uuid).
    private fun isAfter(
        row: Row,
        cursor: CursorBody,
    ): Boolean {
        val byInstant = row.updatedAt.compareTo(cursor.updatedAt)
        return byInstant > 0 || (byInstant == 0 && row.uuid > cursor.uuid)
    }

    private class Killed : RuntimeException("killed by the test")

    /** The engine's staging as a test double: the rows and every marker, handed to the next attempt as `loadStagedItems` returns them. */
    private class Staging(
        var dieWhen: ((String) -> Boolean)? = null,
    ) {
        val rows = ConcurrentHashMap<String, CloudItem>()
        val markers: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val persistPage: suspend (List<CloudItem>, String) -> Unit = { items, marker ->
            items.forEach { rows[it.id] = it }
            markers += marker
            if (dieWhen?.invoke(marker) == true) throw Killed()
        }

        fun context(): ScanContext {
            val staged = rows.values.sortedBy { it.id }.map { it.copy(path = "/${it.name}", created = null, mimeType = null) }
            return ScanContext(markers.lastOrNull(), staged, persistPage)
        }

        fun marker() = assertNotNull(decodeCursorMarker(markers.last()), "the marker is the cursor listing's")
    }

    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    // ---- a transient failure keeps today's behaviour ------------------------------------------------------------------

    @Test
    fun `a failure that does not repeat resumes the same cursor with the status filter and never leaves it`() =
        runTest {
            val firstTry = AtomicInteger()
            val server = SyncServer(wall = { isWallOnFolders(it) && firstTry.getAndIncrement() < 1 })
            val staging = Staging()

            val failure = assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }
            assertEquals(408, failure.statusCode)
            val walled = server.seen.last { isWallOnFolders(it) }.cursor
            assertEquals(StreamFailure(walled!!, "408", 1), staging.marker().folders.failure, "the failure is on record, once")
            assertFalse(staging.marker().folders.unfiltered, "one failure does not leave the cursor")

            val resumed = provider(server.engine).delta(null, null, staging.context())

            assertEquals(liveFolderPaths + liveFilePaths, paths(resumed))
            assertTrue(resumed.complete)
            assertEquals(2, server.seen.count { isWallOnFolders(it) }, "the walled cursor was asked for again, as before")
            assertTrue(server.seen.all { it.status == "EXISTS" }, "no request left the status filter")
            assertNull(staging.marker().folders.failure, "a page that came back clears the record")
        }

    @Test
    fun `a failure on one cursor followed by a failure of another kind or on another cursor counts as a first failure again`() {
        val position = StreamPosition(cursor = "c1")
        val once = position.failedWith("408")
        assertEquals(StreamFailure("c1", "408", 1), once.failure)
        assertEquals(StreamFailure("c1", "408", 2), once.failedWith("408").failure)
        assertEquals(StreamFailure("c1", "5xx", 1), once.failedWith("5xx").failure, "another kind of failure starts the count again")
        assertEquals(StreamFailure("c2", "408", 1), once.copy(cursor = "c2").failedWith("408").failure, "so does another cursor")
        assertEquals(StreamPosition(), StreamPosition().failedWith("408"), "a first page has no cursor to leave")
    }

    @Test
    fun `only a timeout or a server-side failure can be the fault of a cursor`() {
        assertEquals("408", failureKindOf(408))
        for (code in listOf(500, 502, 503, 504, 524)) assertEquals("5xx", failureKindOf(code), "HTTP $code")
        for (code in listOf(0, 400, 401, 403, 404, 405, 429)) assertNull(failureKindOf(code), "HTTP $code")
    }

    // ---- the fallback ---------------------------------------------------------------------------------------------------

    @Test
    fun `the same failure twice on the same cursor continues the stream without the status filter and keeps the live rows only`() =
        runTest {
            val server = SyncServer(wall = ::isWallOnFolders)
            val staging = Staging()
            val logger = LoggerFactory.getLogger("org.krost.unidrive.internxt.CursorListing") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val before = logger.level
            logger.level = Level.INFO
            logger.addAppender(appender)

            val page =
                try {
                    assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }
                    provider(server.engine).delta(null, null, staging.context())
                } finally {
                    logger.detachAppender(appender)
                    logger.level = before
                }

            assertEquals(liveFolderPaths + liveFilePaths, paths(page), "the live rows, whichever side of the walled cursor they are on")
            assertTrue(page.complete)
            assertEquals(page.items.size, page.items.map { it.id }.toSet().size, "a3 and a4 arrive twice (the two minutes before the cursor) and are kept once")
            assertEquals(2, server.wallHits.get(), "the walled cursor was asked for twice and not a third time")

            val folders = server.seen.filter { it.stream == "folders" }
            val afterWall = folders.dropWhile { !isWallOnFolders(it) }.dropWhile { isWallOnFolders(it) }
            val first = afterWall.first()
            assertEquals("2026-07-28T19:35:56.000Z", first.updatedAt, "two minutes before the cursor, in the form the official client sends")
            assertNull(first.cursor)
            assertNull(first.status, "no status filter")
            assertEquals("1000", first.limit)
            for (request in afterWall.drop(1)) {
                assertNull(request.status, "the cursors of the unfiltered stream carry no status and are sent without one")
                assertNull(request.updatedAt)
                assertNotNull(request.cursor)
            }
            assertEquals(4, afterWall.size, "[a3 a4] [b1 b2] [b3 b4] [b5 b6]")
            assertTrue(server.seen.filter { it.stream == "files" }.all { it.status == "EXISTS" }, "the files stream had no trouble and kept its filter")

            assertTrue(staging.rows.keys.none { it in setOf("b1", "b3", "b6") }, "rows that are not live were never staged: ${staging.rows.keys}")
            val marker = staging.marker()
            assertTrue(marker.folders.done && marker.folders.unfiltered, "the stream ended in the mode it ended in: ${marker.folders}")
            assertTrue(marker.files.done && !marker.files.unfiltered)

            val notes = appender.list.filter { it.level == Level.INFO && it.formattedMessage.contains("leaving the cursor") }
            assertEquals(1, notes.size, "one INFO line when the fallback starts: ${appender.list.map { it.formattedMessage }}")
            val note = notes.single().formattedMessage
            assertTrue("folders" in note && "2 times in a row" in note && "HTTP 408" in note, note)
            assertTrue("2026-07-28T19:37:56Z" in note && "2026-07-28T19:35:56.000Z" in note && "without the status filter" in note, note)
        }

    @Test
    fun `a server-side failure twice on the same cursor leaves it too, the second time after the tree walk took over`() =
        runTest {
            // The first failure keeps today's behaviour: the gather is handed to the folder walk (which here cannot list
            // anything and ends incomplete), marker and staged rows stay. The next gather, in a new process, meets the
            // same gateway timeout on the same cursor and leaves it.
            val server = SyncServer(wall = ::isWallOnFolders, wallStatus = HttpStatusCode.fromValue(524), otherStatus = HttpStatusCode.InternalServerError)
            val staging = Staging()

            val walked = provider(server.engine).delta(null, null, staging.context())

            assertFalse(walked.complete, "the walk could not list the tree")
            val recorded = staging.marker().folders
            assertEquals("5xx", recorded.failure?.kind)
            assertEquals(1, recorded.failure?.count)
            assertFalse(recorded.unfiltered)

            val page = provider(server.engine).delta(null, null, staging.context())

            assertEquals(liveFolderPaths + liveFilePaths, paths(page))
            assertTrue(page.complete)
            assertEquals(2, server.wallHits.get())
            assertTrue(staging.marker().folders.let { it.done && it.unfiltered })
            assertEquals(4, server.seen.count { it.stream == "folders" && it.status == null }, "[a3 a4] [b1 b2] [b3 b4] [b5 b6]")
        }

    @Test
    fun `the files stream leaves its cursor the same way`() =
        runTest {
            val server = SyncServer(wall = { it.stream == "files" && it.status == "EXISTS" && it.cursorUuid == "f2" }, pageSize = 1)
            val staging = Staging()

            assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }
            val page = provider(server.engine).delta(null, null, staging.context())

            assertEquals(liveFolderPaths + liveFilePaths, paths(page))
            val files = server.seen.filter { it.stream == "files" }
            val unfiltered = files.filter { it.status == null }
            assertEquals("2026-07-28T19:18:00.000Z", unfiltered.first().updatedAt, "two minutes before f2")
            assertTrue(unfiltered.drop(1).all { it.cursor != null && it.updatedAt == null })
            assertTrue(server.seen.filter { it.stream == "folders" }.all { it.status == "EXISTS" })
        }

    @Test
    fun `a row that was live before the switch and is trashed since leaves the listing`() =
        runTest {
            val server = SyncServer(wall = ::isWallOnFolders)
            val staging = Staging()
            assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }
            assertTrue(staging.rows.containsKey("a3"), "a3 is staged live by the first attempt")

            // a3 is trashed between the attempts: it changed, so it is listed again, as trashed, after the walled cursor.
            server.folders.replaceAll { if (it.uuid == "a3") Row("a3", at("19:58:00"), "TRASHED") else it }
            val page = provider(server.engine).delta(null, null, staging.context())

            assertEquals(liveFolderPaths - "/a3" + liveFilePaths, paths(page))
            assertTrue(page.complete)
        }

    @Test
    fun `a stream that left its cursor does not fall back a second time`() =
        runTest {
            // The unfiltered query itself runs into a wall: nothing is left to fall back to, the failure surfaces
            // and the stream stays in its unfiltered form, where the next attempt asks for the same thing again.
            val server = SyncServer(wall = { isWallOnFolders(it) || (it.stream == "folders" && it.status == null && it.updatedAt != null) })
            val staging = Staging()
            assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }

            repeat(2) {
                val failure = assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }
                assertEquals(408, failure.statusCode)
            }

            val folders = staging.marker().folders
            assertTrue(folders.unfiltered && folders.cursor == null && folders.failure == null, "it stands at the start of its unfiltered form: $folders")
            val unfiltered = server.seen.filter { it.stream == "folders" && it.status == null }
            assertEquals(2, unfiltered.size, "the unfiltered form was asked for by each of the two later attempts")
            assertTrue(unfiltered.all { it.updatedAt == "2026-07-28T19:35:56.000Z" }, "and from the same instant")
            assertEquals(2, server.seen.count { isWallOnFolders(it) }, "the walled cursor is not asked for after the switch")
        }

    @Test
    fun `a first page that fails twice has no cursor to leave`() =
        runTest {
            val server = SyncServer(wall = { it.stream == "folders" && it.cursor == null })
            val staging = Staging()

            repeat(3) { assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) } }

            assertTrue(server.seen.filter { it.stream == "folders" }.all { it.status == "EXISTS" && it.updatedAt == "1970-01-01T00:00:00.000Z" })
        }

    // ---- the scan marker survives the switch -----------------------------------------------------------------------------

    @Test
    fun `a restart in the middle of the unfiltered stream resumes by its cursor and gives the result of an uninterrupted run`() =
        runTest {
            val clean =
                SyncServer(wall = ::isWallOnFolders).let { server ->
                    val staging = Staging()
                    assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }
                    provider(server.engine).delta(null, null, staging.context())
                }

            val server = SyncServer(wall = ::isWallOnFolders)
            val staging = Staging()
            assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }
            // The daemon dies after the first page of the unfiltered stream is stored.
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.folders.let { it.unfiltered && it.cursor != null } }
            assertFailsWith<Killed> { provider(server.engine).delta(null, null, staging.context()) }
            val stored = staging.marker().folders
            assertTrue(stored.unfiltered && stored.cursor != null && stored.failure == null, "the marker holds the unfiltered position: $stored")

            staging.dieWhen = null
            val askedBefore = server.seen.size
            val resumed = provider(server.engine).delta(null, null, staging.context())

            val folders = server.seen.drop(askedBefore).filter { it.stream == "folders" }
            assertEquals(stored.cursor, folders.first().cursor, "it continues by the stored cursor")
            assertNull(folders.first().status, "without the status filter, which that cursor was not issued for")
            assertNull(folders.first().updatedAt)
            assertTrue(folders.none { isWallOnFolders(it) }, "and never asks the walled cursor again")
            assertEquals(clean.items.map { it.path }.toSet(), paths(resumed))
            assertEquals(liveFolderPaths + liveFilePaths, paths(resumed))
            assertEquals(clean.items.size, resumed.items.size)
            assertTrue(resumed.complete)
        }

    @Test
    fun `a restart between the switch and the first unfiltered page asks for the same updatedAt again`() =
        runTest {
            val server = SyncServer(wall = ::isWallOnFolders)
            val staging = Staging()
            assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.folders.let { it.unfiltered && it.cursor == null } }
            assertFailsWith<Killed> { provider(server.engine).delta(null, null, staging.context()) }
            val stored = staging.marker().folders
            assertEquals("2026-07-28T19:35:56.000Z", stored.since, "the switch is persisted with the instant it continues from")

            staging.dieWhen = null
            val askedBefore = server.seen.size
            val resumed = provider(server.engine).delta(null, null, staging.context())

            val first = server.seen.drop(askedBefore).first { it.stream == "folders" }
            assertEquals(stored.since, first.updatedAt)
            assertNull(first.status)
            assertNull(first.cursor)
            assertEquals(liveFolderPaths + liveFilePaths, paths(resumed))
        }

    @Test
    fun `the new fields of the marker are optional and a damaged one is not trusted`() {
        val old =
            """{"strategy":"cursor","status":"EXISTS","startedAt":"2026-07-28T19:00:00Z","folders":{"cursor":"x","done":false},"files":{"cursor":null,"done":true}}"""
        assertEquals(StreamPosition(cursor = "x"), decodeCursorMarker(old)!!.folders, "a marker written before the fallback existed")

        val unfiltered = StreamPosition(unfiltered = true, since = "2026-07-28T19:35:56.000Z")
        val failed = StreamPosition(cursor = "c", failure = StreamFailure("c", "408", 1))
        val raw = cursorMarker("2026-07-28T19:00:00Z", unfiltered, failed)
        val marker = assertNotNull(decodeCursorMarker(raw))
        assertEquals(unfiltered, marker.folders)
        assertEquals(failed, marker.files)

        fun damaged(
            folders: StreamPosition,
            files: StreamPosition = StreamPosition(done = true),
        ) = decodeCursorMarker(cursorMarker("2026-07-28T19:00:00Z", folders, files))
        assertNull(damaged(StreamPosition(unfiltered = true)), "an unfiltered stream that has not started must say from when")
        assertNull(damaged(StreamPosition(unfiltered = true, since = "yesterday")))
        assertNull(damaged(StreamPosition(cursor = "c", failure = StreamFailure(" ", "408", 1))))
        assertNull(damaged(StreamPosition(cursor = "c", failure = StreamFailure("c", "408", 0))))
        assertNotNull(damaged(StreamPosition(unfiltered = true, done = true)), "a finished stream needs no starting instant")
    }

    // ---- the starting point of the unfiltered stream -----------------------------------------------------------------

    @Test
    fun `the unfiltered stream starts two minutes before the instant of the cursor, as the official client did`() {
        // The cursor the server issued for the walled folders page of the account the fallback was written for.
        val live = "eyJ1cGRhdGVkQXQiOiIyMDI2LTA3LTI4VDE5OjM3OjU2LjAwMDAwMFoiLCJ1dWlkIjoiMzI5ZDZmOWQtMWFiOS00M2YwLThjMTQtNWQzNjFmNjFkYTgyIiwic3RhdHVzIjoiRVhJU1RTIn0="

        assertEquals(Instant.parse("2026-07-28T19:37:56Z"), instantOfCursor(live))
        assertEquals(StreamPosition(unfiltered = true, since = "2026-07-28T19:35:56.000Z"), unfilteredPositionAfter(live))
    }

    @Test
    fun `a cursor that cannot be read is not left`() {
        for (junk in listOf("", "not base64 at all!", Base64.getEncoder().encodeToString("""{"uuid":"u"}""".toByteArray()), Base64.getEncoder().encodeToString("[]".toByteArray()))) {
            assertNull(unfilteredPositionAfter(junk), "[$junk]")
        }
        val urlSafe = Base64.getUrlEncoder().encodeToString("""{"updatedAt":"2026-07-28T19:37:56.123456Z","uuid":"u"}""".toByteArray())
        assertEquals("2026-07-28T19:35:56.123Z", unfilteredPositionAfter(urlSafe)?.since, "the margin is kept to the millisecond the server takes")
        val early = Base64.getEncoder().encodeToString("""{"updatedAt":"1970-01-01T00:00:30.000000Z","uuid":"u"}""".toByteArray())
        assertEquals("1970-01-01T00:00:00.000Z", unfilteredPositionAfter(early)?.since, "never before the beginning of time")
    }

    @Test
    fun `a query without a status filter names none, and the first page names updatedAt or the cursor`() {
        assertEquals(mapOf("limit" to "1000", "updatedAt" to "2026-07-28T19:35:56.000Z"), syncQueryParams("2026-07-28T19:35:56.000Z", null, null, 1000))
        assertEquals(mapOf("limit" to "1000", "cursor" to "c"), syncQueryParams(null, "c", null, 1000))
        assertEquals(mapOf("status" to "EXISTS", "limit" to "1000", "cursor" to "c"), syncQueryParams("ignored", "c", "EXISTS", 1000))
    }

    // ---- the status of the rows ------------------------------------------------------------------------------------------

    @Test
    fun `rows of an unfiltered page that do not name their status are not guessed at`() =
        runTest {
            val server = SyncServer(wall = ::isWallOnFolders, rowsWithoutStatus = true)
            val staging = Staging()
            assertFailsWith<InternxtApiException> { provider(server.engine).delta(null, null, staging.context()) }

            val failure = assertFailsWith<SyncRowWithoutStatus> { provider(server.engine).delta(null, null, staging.context()) }

            assertEquals("folders", failure.stream)
            assertTrue(staging.rows.keys.none { it.startsWith("b") }, "nothing of that page was staged as live: ${staging.rows.keys}")
            val folders = staging.marker().folders
            assertTrue(folders.unfiltered && folders.cursor == null, "the stream stands where it began: $folders")
        }

    @Test
    fun `the same body names a status when the filter was sent, whatever the model defaults to`() {
        val body = """{"folders":[{"uuid":"u","plainName":"u","parentUuid":"root"}],"nextCursor":null}"""

        assertEquals(1, parseFoldersSyncPage(body).items.size, "a filtered page keeps today's tolerance")
        assertFailsWith<SyncRowWithoutStatus> { parseFoldersSyncPage(body, rowsCarryStatus = true) }
        assertFailsWith<SyncRowWithoutStatus> { parseFilesSyncPage("""{"files":[{"uuid":"f","status":null}]}""", rowsCarryStatus = true) }
        assertEquals(1, parseFilesSyncPage("""{"files":[{"uuid":"f","status":"TRASHED"}]}""", rowsCarryStatus = true).items.size)
    }
}
