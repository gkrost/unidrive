package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.internxt.model.InternxtFolder
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A folder the server has no more is not a transient failure. Its meta answers 404 (or 410) every time it is asked, so a
 * pass that counted the items below it as left out could never complete, and the engine, which recovers from an
 * incomplete pass by listing the whole drive again, did so for ever. The items below such a folder are unreachable, not
 * deleted: the page is complete without them and names them (DeltaPage.unreachableIds) so that the engine does not read
 * their absence as a deletion. A folder that is unavailable for any other reason (5xx, a body that cannot be read) is the
 * old case: the pass is incomplete and nothing is named.
 */
class InternxtUnreachableItemsTest {
    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    // /Docs holds readme. /Lost (T1) is not on the drive any more: a file (f2) and a live folder /Lost/Sub (S1) with a
    // file (f3) still hang below it. [extraFiles] hang below further folders the test names in [metaStatus].
    private fun lostDrive(
        metaStatus: Map<String, Int> = emptyMap(),
        extraFiles: List<FakeFile> = emptyList(),
    ) = FakeDrive(
        listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00")), FakeFolder("S1", "Sub", "T1", fakeAt("19:35:00"))),
        listOf(
            FakeFile("f1", "readme", "a1", fakeAt("19:10:00")),
            FakeFile("f2", "note", "T1", fakeAt("19:20:00")),
            FakeFile("f3", "deep", "S1", fakeAt("19:21:00")),
        ) + extraFiles,
        pageSize = 100,
        metaStatus = metaStatus,
    )

    private fun <T> logged(run: suspend () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger(InternxtProvider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val before = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            var result: T? = null
            runTest { result = run() }
            @Suppress("UNCHECKED_CAST")
            return (result as T) to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            logger.level = before
        }
    }

    @Test
    fun `a 404 on an ancestor leaves the items below it unreachable, the pass complete, and one line in the log`() {
        val drive = lostDrive()

        val (page, events) = logged { internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page), "what can be placed is delivered")
        assertTrue(page.complete, "nothing the pass could still get is missing")
        assertEquals(setOf("f2", "f3", "S1"), page.unreachableIds, "the file, the live folder and the file below it")
        assertEquals(listOf("T1"), drive.metaRequests.toList(), "asked once, however many items hang below the gone folder")

        val warnings = events.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertTrue(warnings.none { "unfetchable" in it || "dropped" in it }, "no line per gone folder, and no incomplete-pass warning: $warnings")
        val summary = events.filter { it.level == Level.INFO && "unreachable" in it.formattedMessage }.map { it.formattedMessage }
        assertEquals(1, summary.size, "one line for the pass: $summary")
        assertTrue(
            summary.single().startsWith("Internxt delta gather: 3 item(s) (2 file(s), 1 folder(s)) sit below 1 folder(s) that no longer exist"),
            summary.single(),
        )
        val perFolder = events.filter { it.level == Level.DEBUG && "T1" in it.formattedMessage && "is gone (404)" in it.formattedMessage }
        assertEquals(1, perFolder.size, "the folder itself is told at DEBUG, once")
    }

    @Test
    fun `a 410 is gone as well`() {
        val drive = lostDrive(metaStatus = mapOf("T1" to 410))

        val (page, _) = logged { internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertTrue(page.complete)
        assertEquals(setOf("f2", "f3", "S1"), page.unreachableIds)
    }

    @Test
    fun `a 503 on an ancestor keeps the pass incomplete and names nothing unreachable`() {
        val drive = lostDrive(metaStatus = mapOf("T1" to 503))

        val (page, events) = logged { internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page))
        assertFalse(page.complete, "the folder may answer on the next pass: no deletion may be inferred from this one")
        assertTrue(page.unreachableIds.isEmpty(), "unavailable is not gone")
        val warnings = events.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertTrue(warnings.any { "unfetchable (503)" in it && "T1" in it }, "$warnings")
        assertTrue(warnings.any { it.startsWith("Internxt delta gather dropped 3 item(s)") && "complete=false" in it }, "$warnings")
        assertTrue(events.none { it.level == Level.INFO && "unreachable" in it.formattedMessage })
    }

    @Test
    fun `a gone folder beside an unavailable one gives an incomplete pass that names only the items below the gone one`() {
        val drive = lostDrive(metaStatus = mapOf("T2" to 503), extraFiles = listOf(FakeFile("f4", "stuck", "T2", fakeAt("19:22:00"))))

        val (page, _) = logged { internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertFalse(page.complete, "one folder is only unavailable")
        assertEquals(setOf("f2", "f3", "S1"), page.unreachableIds, "f4 hangs below the unavailable folder, which proves nothing about it")
    }

    private fun folder(
        uuid: String,
        parent: String?,
    ) = InternxtFolder(uuid = uuid, plainName = uuid, parentUuid = parent)

    @Test
    fun `goneAncestorOf names the gone folder that stops a path and nothing else`() {
        val known = mapOf("S1" to folder("S1", "T1"), "S2" to folder("S2", "S1"))

        assertEquals("T1", InternxtProvider.goneAncestorOf("S2", known, emptyMap(), "root", setOf("T1"), setOf("T1")))
        assertEquals("T1", InternxtProvider.goneAncestorOf("T1", known, emptyMap(), "root", setOf("T1"), setOf("T1")), "the parent itself")
        assertNull(InternxtProvider.goneAncestorOf("S2", known, emptyMap(), "root", setOf("T1"), emptySet()), "unfetchable for another reason")
        assertNull(InternxtProvider.goneAncestorOf("S2", known, emptyMap(), "root", emptySet(), setOf("T1")), "nothing on the way is unresolved")
        assertNull(InternxtProvider.goneAncestorOf("root", known, emptyMap(), "root", setOf("T1"), setOf("T1")), "an item at the top has no ancestor to blame")
        assertNull(InternxtProvider.goneAncestorOf(null, known, emptyMap(), "root", setOf("T1"), setOf("T1")))
        assertEquals(
            "T1",
            InternxtProvider.goneAncestorOf("A", emptyMap(), mapOf("A" to folder("A", "T1")), "root", setOf("T1"), setOf("T1")),
            "the way runs through an ancestor-only row",
        )
    }

    @Test
    fun `goneAncestorOf ends on a parent chain that comes back to itself`() {
        val loop = mapOf("A" to folder("A", "B"), "B" to folder("B", "A"))

        assertNull(InternxtProvider.goneAncestorOf("A", loop, emptyMap(), "root", emptySet(), emptySet()))
    }
}
