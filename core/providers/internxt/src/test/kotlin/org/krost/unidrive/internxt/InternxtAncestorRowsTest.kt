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

    // What the page holds: the live items, at the path of the provider's tree, and the two trashed folders that live items
    // hang below (/OldStuff, /OldStuff/Deep), reported as trashed folders. The deleted folder has nothing live below it.
    private val liveAndBelowTrash =
        setOf(
            "/Docs", "/Pics", "/OldStuff/Deep/Kept", "/Docs/readme.txt", "/OldStuff/note.txt", "/OldStuff/Deep/Kept/keep.txt",
            "/OldStuff", "/OldStuff/Deep",
        )

    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    // ---- the fallback walk keeps the trashed folders it saw ----------------------------------------------------------

    @Test
    fun `the walk without the status filter keeps its trashed folders as ancestors and resolves the paths with no folder meta request`() =
        runTest {
            val drive = FakeDrive(fakeAccountFolders(), fakeAccountFiles(), wall = "a2")
            val staging = FakeStaging()
            val reports = CopyOnWriteArrayList<ScanProgress>()

            assertFailsWith<InternxtApiException> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            val page = internxtProviderOn(drive.engine).delta(null, null, staging.context(reports::add))

            assertEquals(liveAndBelowTrash, paths(page), "paths run through the trashed folders as they did when those were fetched")
            assertTrue(page.complete)
            assertEquals(emptyList(), drive.metaRequests.toList(), "the folders came with the listing: not one request for them")
            assertTrue(page.items.none { it.id == "D1" }, "a deleted folder is no ancestor of anything live: not an item")
            assertTrue(
                page.items.filter { it.id in setOf("T1", "T2") }.all { it.isFolder && it.trashed && !it.deleted },
                "an ancestor-only record is an item only as a folder of the trash",
            )
            assertTrue(reports.none { it.pathsTotal != null }, "nothing was missing, so there was no step to report: $reports")
        }

    @Test
    fun `the ancestors are staged beside the live rows with a tag of their own, a deleted folder is not kept`() =
        runTest {
            val drive = FakeDrive(fakeAccountFolders(), fakeAccountFiles(), wall = "a2")
            val staging = FakeStaging()
            assertFailsWith<InternxtApiException> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            internxtProviderOn(drive.engine).delta(null, null, staging.context())

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
            val drive = FakeDrive(fakeAccountFolders(), fakeAccountFiles(), wall = "a2")
            val staging = FakeStaging()
            assertFailsWith<InternxtApiException> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            // The process dies when the last page of the folders stream is stored: every row of the listing is staged,
            // the paths are not resolved yet (the situation of a first scan that was ended by a decoding fault).
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.folders.done }
            assertFailsWith<StagingKilled> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            staging.dieWhen = null
            assertTrue(staging.rows.values.any { it.hash == CURSOR_ANCESTOR_TAG }, "the ancestors were staged before the process died")

            val resumed = internxtProviderOn(drive.engine).delta(null, null, staging.context())

            assertEquals(liveAndBelowTrash, paths(resumed))
            assertTrue(resumed.complete)
            assertEquals(emptyList(), drive.metaRequests.toList(), "a new process resolved the paths from the staged ancestors")
        }

    @Test
    fun `a trashed folder that turns up live later in the walk is an item, not an ancestor`() =
        runTest {
            // T1 is listed trashed at 19:40 and again, restored, at 19:55: the later version is the one that stands.
            val drive = FakeDrive(fakeAccountFolders() + FakeFolder("T1", "OldStuff", "root", fakeAt("19:55:00")), fakeAccountFiles(), wall = "a2")
            val staging = FakeStaging()
            assertFailsWith<InternxtApiException> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }

            val page = internxtProviderOn(drive.engine).delta(null, null, staging.context())

            assertEquals(liveAndBelowTrash, paths(page))
            assertFalse(page.items.single { it.id == "T1" }.trashed, "the restored folder is a live item")
            assertTrue(page.items.single { it.id == "T2" }.trashed, "T2 below it is still a trashed ancestor")
            assertEquals(CURSOR_STAGED_TAG, staging.rows.getValue("T1").hash, "and its staged row is the live one")
            assertEquals(emptyList(), drive.metaRequests.toList())
        }

    // ---- without the fallback the missing folders are fetched, a few at a time ---------------------------------------

    @Test
    fun `without the status-less walk the missing folders are fetched once each, with at most four requests in flight`() =
        runTest {
            // Twelve live files, each in its own trashed top-level folder, and the filtered listing only.
            val folders = (1..12).map { FakeFolder("T$it", "Trash$it", "root", fakeAt("19:%02d:00".format(it)), "TRASHED") } + FakeFolder("a1", "Docs", "root", fakeAt("19:30:00"))
            val files = (1..12).map { FakeFile("f$it", "n$it", "T$it", fakeAt("19:%02d:30".format(it))) }
            val drive = FakeDrive(folders, files, pageSize = 100, metaDelayMs = 40)

            val page = internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context())

            assertEquals(setOf("/Docs") + (1..12).map { "/Trash$it/n$it.txt" } + (1..12).map { "/Trash$it" }, paths(page))
            assertTrue(page.complete)
            assertEquals((1..12).map { "T$it" }.toSet(), drive.metaRequests.toSet())
            assertEquals(12, drive.metaRequests.size, "each folder was asked for once")
            assertTrue(drive.maxInFlight.get() <= InternxtProvider.PER_FOLDER_REQUESTS_IN_FLIGHT, "in flight at once: ${drive.maxInFlight.get()}")
        }

    @Test
    fun `the step is reported with its own counts after the listing, and the item count stands`() =
        runTest {
            val folders = (1..5).map { FakeFolder("T$it", "Trash$it", "root", fakeAt("19:%02d:00".format(it)), "TRASHED") } + FakeFolder("a1", "Docs", "root", fakeAt("19:30:00"))
            val files = (1..5).map { FakeFile("f$it", "n$it", "T$it", fakeAt("19:%02d:30".format(it))) }
            val drive = FakeDrive(folders, files, pageSize = 100)
            val reports = CopyOnWriteArrayList<ScanProgress>()

            internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context(reports::add))

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
    fun `a folder the server no longer has is recorded and its items are left out as unreachable`() =
        runTest {
            val drive =
                FakeDrive(
                    listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00")), FakeFolder("T1", "Lost", "root", fakeAt("19:40:00"), "TRASHED")),
                    listOf(FakeFile("f1", "readme", "a1", fakeAt("19:10:00")), FakeFile("f2", "orphan", "T1", fakeAt("19:20:00"))),
                    pageSize = 100,
                    metaGone = setOf("T1"),
                )

            val page = internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context())

            assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page))
            assertTrue(page.complete, "the folder is gone for good (404): the pass has nothing it could still get")
            assertEquals(setOf("f2"), page.unreachableIds, "the item is left out, and named, so that no deletion is inferred from its absence")
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
