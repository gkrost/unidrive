package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.internxt.model.InternxtFolder
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
 * The step after the cursor listing: the paths of the gathered items, and the folders they run through that the listing
 * did not return.
 *
 * Live, a first scan of a large account listed 387,140 live items and then asked `GET /folders/{uuid}/meta` for 949
 * trashed folders, one request after the other, for eight minutes, although the listing it had just walked (the one
 * without the status filter, after the folders stream left a cursor the server could not answer) had carried those very
 * folders. The tests pin the three changes: the trashed folders of the unfiltered walk are kept as ancestor-only records
 * (never items) and answer the paths, so no request is made; the folders that are still missing are fetched a few at a
 * time; and the step is reported, with its own counts, instead of the item count standing still.
 *
 * The fake drive behaves like the public server source: the status filter, the keyset cursors (a cursor is good for the
 * status it was issued for only), a cursor that is walled with HTTP 408 for the filtered query, and a folder meta that
 * answers HTTP 200 for a trashed folder.
 */
class InternxtAncestorRowsTest {
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

    // ---- the fake drive --------------------------------------------------------------------------------------------

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val millis = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val micros = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    private fun at(time: String) = Instant.parse("2026-07-28T${time}Z")

    private data class FolderRow(
        val uuid: String,
        val name: String,
        val parent: String,
        val updatedAt: Instant,
        val status: String = "EXISTS",
    )

    private data class FileRow(
        val uuid: String,
        val name: String,
        val folder: String,
        val updatedAt: Instant,
    )

    private class CursorBody(
        val updatedAt: Instant,
        val uuid: String,
        val status: String?,
    )

    private fun folderJson(
        row: FolderRow,
        withStatus: Boolean = true,
    ): String {
        val status = if (withStatus) ",\"status\":\"${row.status}\",\"deleted\":${row.status == "TRASHED"},\"removed\":${row.status == "DELETED"}" else ""
        return "{\"uuid\":\"${row.uuid}\",\"plainName\":\"${row.name}\",\"parentUuid\":\"${row.parent}\"$status," +
            "\"modificationTime\":\"2026-07-01T10:00:00.000Z\",\"updatedAt\":\"${millis.format(row.updatedAt)}\"}"
    }

    private fun fileJson(row: FileRow) =
        "{\"uuid\":\"${row.uuid}\",\"plainName\":\"${row.name}\",\"type\":\"txt\",\"size\":\"1\",\"folderUuid\":\"${row.folder}\"," +
            "\"status\":\"EXISTS\",\"modificationTime\":\"2026-07-01T10:00:00.000Z\",\"updatedAt\":\"${millis.format(row.updatedAt)}\"}"

    /**
     * The drive. [wall]: a folders request with the status filter after the cursor of this uuid is answered 408, however
     * often it comes (the unfiltered form of the query answers). [metaDelayMs]: each folder meta takes this long.
     * [metaGone]: folders whose meta answers 404.
     */
    private inner class Drive(
        val folders: List<FolderRow>,
        val files: List<FileRow>,
        val pageSize: Int = 2,
        val wall: String? = null,
        val metaDelayMs: Long = 0,
        val metaGone: Set<String> = emptySet(),
    ) {
        val metaRequests = CopyOnWriteArrayList<String>()
        private val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        private fun cursorFor(
            updatedAt: Instant,
            uuid: String,
            status: String?,
        ): String {
            val fields = listOfNotNull("\"updatedAt\":\"${micros.format(updatedAt)}\"", "\"uuid\":\"$uuid\"", status?.let { "\"status\":\"$it\"" })
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

        private fun <T> page(
            rows: List<T>,
            updatedAt: (T) -> Instant,
            uuid: (T) -> String,
            status: String?,
            cursor: CursorBody?,
            since: String?,
        ): Pair<List<T>, String?> {
            val ordered = rows.sortedWith(compareBy({ updatedAt(it) }, { uuid(it) }))
            val after =
                if (cursor != null) {
                    ordered.filter { r -> updatedAt(r).isAfter(cursor.updatedAt) || (updatedAt(r) == cursor.updatedAt && uuid(r) > cursor.uuid) }
                } else {
                    ordered.filter { updatedAt(it).isAfter(Instant.parse(since!!)) }
                }
            val taken = after.take(pageSize)
            val next = if (after.size > pageSize) cursorFor(updatedAt(taken.last()), uuid(taken.last()), status) else null
            return taken to next
        }

        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                val params = request.url.parameters
                val status = params["status"]
                val token = params["cursor"]
                val cursor = token?.let(::decodeCursor)
                fun respondJson(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)
                when {
                    path.endsWith("/drive/folders/sync") -> {
                        if (token != null && cursor == null) return@MockEngine respond("{}", HttpStatusCode.BadRequest, jsonHeaders)
                        if (cursor != null && cursor.status != status) return@MockEngine respond("{}", HttpStatusCode.BadRequest, jsonHeaders)
                        if (status == "EXISTS" && cursor != null && cursor.uuid == wall) {
                            return@MockEngine respond("""{"statusCode":408,"message":"Request Timeout"}""", HttpStatusCode.RequestTimeout, jsonHeaders)
                        }
                        val listed = folders.filter { status == null || it.status == status }
                        val (taken, next) = page(listed, { it.updatedAt }, { it.uuid }, status, cursor, params["updatedAt"])
                        val rows = taken.joinToString(",") { folderJson(it) }
                        respondJson("""{"folders":[$rows],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""")
                    }
                    path.endsWith("/drive/files/sync") -> {
                        val (taken, next) = page(files, { it.updatedAt }, { it.uuid }, status, cursor, params["updatedAt"])
                        respondJson("""{"files":[${taken.joinToString(",") { fileJson(it) }}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""")
                    }
                    path.endsWith("/meta") -> {
                        val uuid = path.substringBeforeLast("/meta").substringAfterLast('/')
                        metaRequests += uuid
                        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                        try {
                            if (metaDelayMs > 0) delay(metaDelayMs)
                        } finally {
                            inFlight.decrementAndGet()
                        }
                        // A trashed folder is answered with its row and HTTP 200, as the live server does.
                        val row = folders.lastOrNull { it.uuid == uuid }
                        if (row == null || uuid in metaGone) respond("""{"statusCode":404}""", HttpStatusCode.NotFound, jsonHeaders) else respondJson(folderJson(row))
                    }
                    else -> error("unexpected request: ${request.url}")
                }
            }
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

        fun context(onProgress: ((ScanProgress) -> Unit)? = null): ScanContext {
            // What the engine hands back keeps the cloud identity only: no path, no creation time, no mime type.
            val staged = rows.values.sortedBy { it.id }.map { it.copy(path = "/${it.name}", created = null, mimeType = null) }
            return ScanContext(markers.lastOrNull(), staged, persistPage, onProgress = onProgress)
        }
    }

    // The account: two live folders on the first page (the cursor after a2 is walled), then a trashed tree with live
    // content under it, a deleted folder, and a live folder whose parent is trashed. Files hang below all of them.
    //
    //   a1, a2 (live, top)          T1 "OldStuff" (trashed, top)   D1 "Gone" (deleted, top)
    //                               T2 "Deep" (trashed, in T1)
    //                               L2 "Kept" (live, in T2)
    private fun accountFolders() =
        listOf(
            FolderRow("a1", "Docs", "root", at("19:30:00")),
            FolderRow("a2", "Pics", "root", at("19:31:00")),
            FolderRow("T1", "OldStuff", "root", at("19:40:00"), "TRASHED"),
            FolderRow("T2", "Deep", "T1", at("19:41:00"), "TRASHED"),
            FolderRow("D1", "Gone", "root", at("19:42:00"), "DELETED"),
            FolderRow("L2", "Kept", "T2", at("19:50:00")),
        )

    private fun accountFiles() =
        listOf(
            FileRow("f1", "readme", "a1", at("19:10:00")),
            FileRow("f2", "note", "T1", at("19:20:00")),
            FileRow("f3", "keep", "L2", at("19:30:00")),
        )

    private val liveAndBelowTrash =
        setOf("/Docs", "/Pics", "/OldStuff/Deep/Kept", "/Docs/readme.txt", "/OldStuff/note.txt", "/OldStuff/Deep/Kept/keep.txt")

    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    // ---- the fallback walk keeps the trashed folders it saw ----------------------------------------------------------

    @Test
    fun `the walk without the status filter keeps its trashed folders as ancestors and resolves the paths with no folder meta request`() =
        runTest {
            val drive = Drive(accountFolders(), accountFiles(), wall = "a2")
            val staging = Staging()
            val reports = CopyOnWriteArrayList<ScanProgress>()

            assertFailsWith<InternxtApiException> { provider(drive.engine).delta(null, null, staging.context()) }
            val page = provider(drive.engine).delta(null, null, staging.context(reports::add))

            assertEquals(liveAndBelowTrash, paths(page), "paths run through the trashed folders as they did when those were fetched")
            assertTrue(page.complete)
            assertEquals(emptyList(), drive.metaRequests.toList(), "the folders came with the listing: not one request for them")
            assertTrue(page.items.none { it.id in setOf("T1", "T2", "D1") }, "an ancestor-only record is never an item")
            assertTrue(reports.none { it.pathsTotal != null }, "nothing was missing, so there was no step to report: $reports")
        }

    @Test
    fun `the ancestors are staged beside the live rows with a tag of their own, a deleted folder is not kept`() =
        runTest {
            val drive = Drive(accountFolders(), accountFiles(), wall = "a2")
            val staging = Staging()
            assertFailsWith<InternxtApiException> { provider(drive.engine).delta(null, null, staging.context()) }
            provider(drive.engine).delta(null, null, staging.context())

            val ancestors = staging.rows.values.filter { it.hash == CURSOR_ANCESTOR_TAG }.map { it.id }.toSet()
            assertEquals(setOf("T1", "T2"), ancestors, "the trashed folders, and not the deleted one: ${staging.rows.keys}")
            assertTrue(staging.rows.getValue("L2").hash == CURSOR_STAGED_TAG, "a live row keeps its own tag")
            assertTrue(staging.rows.getValue("T2").isFolder)
            assertEquals("T1", staging.rows.getValue("T2").parentId)
            val marker = assertNotNull(decodeCursorMarker(staging.markers.last()), "the marker is the one it always was")
            assertTrue(marker.folders.done && marker.files.done)
        }

    @Test
    fun `a restart after the listing keeps the ancestors and still asks for no folder`() =
        runTest {
            val drive = Drive(accountFolders(), accountFiles(), wall = "a2")
            val staging = Staging()
            assertFailsWith<InternxtApiException> { provider(drive.engine).delta(null, null, staging.context()) }
            // The process dies when the last page of the folders stream is stored: every row of the listing is staged,
            // the paths are not resolved yet (the situation of a first scan that was ended by a decoding fault).
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.folders.done }
            assertFailsWith<Killed> { provider(drive.engine).delta(null, null, staging.context()) }
            staging.dieWhen = null
            assertTrue(staging.rows.values.any { it.hash == CURSOR_ANCESTOR_TAG }, "the ancestors were staged before the process died")

            val resumed = provider(drive.engine).delta(null, null, staging.context())

            assertEquals(liveAndBelowTrash, paths(resumed))
            assertTrue(resumed.complete)
            assertEquals(emptyList(), drive.metaRequests.toList(), "a new process resolved the paths from the staged ancestors")
        }

    @Test
    fun `a trashed folder that turns up live later in the walk is an item, not an ancestor`() =
        runTest {
            // T1 is listed trashed at 19:40 and again, restored, at 19:55: the later version is the one that stands.
            val drive = Drive(accountFolders() + FolderRow("T1", "OldStuff", "root", at("19:55:00")), accountFiles(), wall = "a2")
            val staging = Staging()
            assertFailsWith<InternxtApiException> { provider(drive.engine).delta(null, null, staging.context()) }

            val page = provider(drive.engine).delta(null, null, staging.context())

            assertEquals(liveAndBelowTrash + "/OldStuff", paths(page), "the restored folder is a live item, T2 below it is still a trashed ancestor")
            assertEquals(CURSOR_STAGED_TAG, staging.rows.getValue("T1").hash, "and its staged row is the live one")
            assertEquals(emptyList(), drive.metaRequests.toList())
        }

    // ---- without the fallback the missing folders are fetched, a few at a time ---------------------------------------

    @Test
    fun `without the status-less walk the missing folders are fetched once each, with at most four requests in flight`() =
        runTest {
            // Twelve live files, each in its own trashed top-level folder, and the filtered listing only.
            val folders = (1..12).map { FolderRow("T$it", "Trash$it", "root", at("19:%02d:00".format(it)), "TRASHED") } + FolderRow("a1", "Docs", "root", at("19:30:00"))
            val files = (1..12).map { FileRow("f$it", "n$it", "T$it", at("19:%02d:30".format(it))) }
            val drive = Drive(folders, files, pageSize = 100, metaDelayMs = 40)

            val page = provider(drive.engine).delta(null, null, Staging().context())

            assertEquals(setOf("/Docs") + (1..12).map { "/Trash$it/n$it.txt" }, paths(page))
            assertTrue(page.complete)
            assertEquals((1..12).map { "T$it" }.toSet(), drive.metaRequests.toSet())
            assertEquals(12, drive.metaRequests.size, "each folder was asked for once")
            assertTrue(drive.maxInFlight.get() <= InternxtProvider.PER_FOLDER_REQUESTS_IN_FLIGHT, "in flight at once: ${drive.maxInFlight.get()}")
        }

    @Test
    fun `the step is reported with its own counts after the listing, and the item count stands`() =
        runTest {
            val folders = (1..5).map { FolderRow("T$it", "Trash$it", "root", at("19:%02d:00".format(it)), "TRASHED") } + FolderRow("a1", "Docs", "root", at("19:30:00"))
            val files = (1..5).map { FileRow("f$it", "n$it", "T$it", at("19:%02d:30".format(it))) }
            val drive = Drive(folders, files, pageSize = 100)
            val reports = CopyOnWriteArrayList<ScanProgress>()

            provider(drive.engine).delta(null, null, Staging().context(reports::add))

            val listing = reports.takeWhile { it.pathsTotal == null }
            val resolving = reports.drop(listing.size)
            assertTrue(listing.isNotEmpty() && listing.all { it.listing == ScanProgress.LISTING_CURSOR }, "$listing")
            assertTrue(resolving.isNotEmpty() && resolving.all { it.pathsTotal != null }, "once the step began every report belongs to it: $reports")
            assertEquals(ScanProgress(items = 6, listing = ScanProgress.LISTING_CURSOR, pathsDone = 0, pathsTotal = 5), resolving.first(), "it begins with the size of the set")
            assertEquals(ScanProgress(items = 6, listing = ScanProgress.LISTING_CURSOR, pathsDone = 5, pathsTotal = 5), resolving.last())
            val dones = resolving.map { it.pathsDone!! }
            assertEquals(dones.sorted(), dones, "done rises")
            assertTrue(resolving.all { it.items == 6 && it.pathsDone!! <= it.pathsTotal!! })
            assertEquals(listing.last().items, resolving.first().items, "the item count is the one the listing ended with")
        }

    // ---- the resolution on its own ---------------------------------------------------------------------------------------

    private fun folder(
        uuid: String,
        parent: String?,
        status: String = "EXISTS",
    ) = InternxtFolder(uuid = uuid, plainName = uuid, parentUuid = parent, status = status)

    @Test
    fun `no more than the limit are in flight, and they all are when there are enough folders`() =
        runTest {
            val missing = (1..10).map { "m$it" }
            var inFlight = 0
            var most = 0

            val folderMap = mutableMapOf<String, InternxtFolder>()
            val resolution =
                InternxtProvider.resolveAncestors(
                    parents = missing.asSequence(),
                    folderMap = folderMap,
                    ancestors = emptyMap(),
                    rootUuid = "root",
                    unfetchable = mutableSetOf(),
                    fetchFolder = { uuid ->
                        inFlight++
                        most = maxOf(most, inFlight)
                        delay(100)
                        inFlight--
                        folder(uuid, "root")
                    },
                )

            assertEquals(4, most)
            assertEquals(10, resolution.fetched)
            assertEquals(missing.toSet(), folderMap.keys)
        }

    @Test
    fun `a fetched folder that hangs below another missing one grows the total`() =
        runTest {
            val known = mapOf("M2" to folder("M2", "M1"), "M1" to folder("M1", "root"))
            val reports = mutableListOf<Pair<Int, Int>>()

            val resolution =
                InternxtProvider.resolveAncestors(
                    parents = sequenceOf("M2"),
                    folderMap = mutableMapOf(),
                    ancestors = emptyMap(),
                    rootUuid = "root",
                    unfetchable = mutableSetOf(),
                    fetchFolder = { known[it] },
                    onProgress = { done, total -> reports += done to total },
                )

            assertEquals(2, resolution.fetched)
            assertEquals(listOf(0 to 1, 1 to 1, 1 to 2, 2 to 2), reports, "the second folder is known once the first is fetched")
        }

    @Test
    fun `the ancestor rows answer before a request is made, and are read into the map once`() =
        runTest {
            val ancestors = mapOf("T2" to folder("T2", "T1", "TRASHED"), "T1" to folder("T1", "root", "TRASHED"))
            val folderMap = mutableMapOf("L" to folder("L", "T2"))
            var requests = 0

            val resolution =
                InternxtProvider.resolveAncestors(
                    parents = sequenceOf("T2", "L", "T2"),
                    folderMap = folderMap,
                    ancestors = ancestors,
                    rootUuid = "root",
                    unfetchable = mutableSetOf(),
                    fetchFolder = {
                        requests++
                        null
                    },
                )

            assertEquals(0, requests)
            assertEquals(2, resolution.fromRows, "T2 and T1, each counted once however many items hang below")
            assertEquals(0, resolution.fetched)
            assertEquals("/T1/T2/L", InternxtProvider.buildFolderPath("L", folderMap, "root"))
        }

    @Test
    fun `buildFolderPathFetching reads the ancestor rows before it calls the fetch`() =
        runTest {
            val ancestors = mapOf("T1" to folder("T1", "root", "TRASHED"))
            val folderMap = mutableMapOf("L" to folder("L", "T1"))
            var requests = 0

            val path =
                InternxtProvider.buildFolderPathFetching("L", folderMap, "root", mutableSetOf(), { requests++; null }, ancestors)

            assertEquals("/T1/L", path)
            assertEquals(0, requests)
            assertNotNull(folderMap["T1"], "spliced into the map like a fetched folder")
        }

    @Test
    fun `a folder nothing can place is recorded and its items are dropped as before`() =
        runTest {
            val drive =
                Drive(
                    listOf(FolderRow("a1", "Docs", "root", at("19:30:00")), FolderRow("T1", "Lost", "root", at("19:40:00"), "TRASHED")),
                    listOf(FileRow("f1", "readme", "a1", at("19:10:00")), FileRow("f2", "orphan", "T1", at("19:20:00"))),
                    pageSize = 100,
                    metaGone = setOf("T1"),
                )

            val page = provider(drive.engine).delta(null, null, Staging().context())

            assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page))
            assertFalse(page.complete, "an item was left out, so no deletion may be inferred from the page")
        }

    // ---- the staging stays readable -------------------------------------------------------------------------------------

    @Test
    fun `a scan staged before the ancestors existed resumes as it did`() {
        val rows = listOf(CloudItem(id = "x", name = "x", path = "/x", size = 0, isFolder = true, modified = null, created = null, hash = CURSOR_STAGED_TAG, mimeType = null))
        val marker = cursorMarker("2026-07-28T19:00:00Z", StreamPosition(cursor = "c"), StreamPosition(done = true))

        val resume = assertNotNull(cursorResumeOf(marker, rows))

        assertEquals(rows, resume.rows)
        assertEquals(emptyList(), resume.ancestors)
    }

    @Test
    fun `the ancestor rows are handed back apart from the live ones, and a lone ancestor does not claim progress`() {
        fun row(
            id: String,
            tag: String,
        ) = CloudItem(id = id, name = id, path = "/$id", size = 0, isFolder = true, modified = null, created = null, hash = tag, mimeType = null)
        val marker = cursorMarker("2026-07-28T19:00:00Z", StreamPosition(cursor = "c"), StreamPosition(done = true))

        val resume = assertNotNull(cursorResumeOf(marker, listOf(row("live", CURSOR_STAGED_TAG), row("trashed", CURSOR_ANCESTOR_TAG))))
        assertEquals(listOf("live"), resume.rows.map { it.id })
        assertEquals(listOf("trashed"), resume.ancestors.map { it.id })

        assertNull(cursorResumeOf(marker, listOf(row("trashed", CURSOR_ANCESTOR_TAG))), "a marker that claims progress with no live row staged is not resumed")
    }
}
