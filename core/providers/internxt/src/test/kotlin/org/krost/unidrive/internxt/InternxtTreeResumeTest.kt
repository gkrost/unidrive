package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InternxtTreeResumeTest {
    private val log = LoggerFactory.getLogger(InternxtTreeResumeTest::class.java)
    private val mtime = "2026-01-01T10:00:00.000Z"

    private fun file(
        uuid: String,
        name: String,
    ) = InternxtFile(uuid = uuid, plainName = name, size = "7", modificationTime = mtime)

    private fun folder(
        uuid: String,
        name: String,
    ) = InternxtFolder(uuid = uuid, plainName = name)

    private val tree =
        mapOf(
            "root" to FolderContentResponse(listOf(folder("a", "a"), folder("b", "b")), listOf(file("top", "top.txt"))),
            "a" to FolderContentResponse(listOf(folder("a1", "a1")), listOf(file("fa", "a.txt"))),
            "a1" to FolderContentResponse(emptyList(), listOf(file("fa1", "deep.txt"))),
            "b" to FolderContentResponse(listOf(folder("b1", "b1")), listOf(file("fb", "b.txt"))),
            "b1" to FolderContentResponse(emptyList(), emptyList()),
        )

    // What the engine's scan_staging does: rows upserted by id, the marker replaced, both in one step.
    private class Staging {
        val rows = LinkedHashMap<String, CloudItem>()
        var marker: String? = null

        suspend fun persist(
            items: List<CloudItem>,
            marker: String,
        ) {
            for (i in items) rows[i.id] = i
            this.marker = marker
        }
    }

    private suspend fun walk(
        staging: Staging,
        listed: MutableList<String>,
        failOn: String? = null,
        roots: List<String> = listOf("/"),
    ): ScopedInventory {
        val resume = treeResumeOf(staging.marker, staging.rows.values.toList(), roots)
        return InternxtProvider.collectScopedInventoryImpl(
            getContents = { uuid ->
                if (uuid == failOn) error("outage")
                synchronized(listed) { listed += uuid }
                tree[uuid] ?: error("unexpected uuid $uuid")
            },
            driveRootUuid = "root",
            scopeRoots = roots,
            scanned = AtomicInteger(),
            skipped = AtomicInteger(),
            log = log,
            concurrency = 1,
            resume = resume,
            persist = { rows, startsDone -> staging.persist(rows, treeMarker("2026-10-09T10:00:00Z", roots, startsDone)) },
        )
    }

    private fun ids(inv: ScopedInventory) = (inv.files.map { it.uuid } + inv.folders.map { it.uuid }).toSet()

    @Test
    fun `an interrupted walk resumes without listing the finished folders again`() =
        runTest {
            val staging = Staging()
            val first = mutableListOf<String>()
            assertFailsWith<IllegalStateException> { walk(staging, first, failOn = "b1") }
            assertTrue("root" in first && "b" in first, "the first attempt got past the root: $first")

            val second = mutableListOf<String>()
            val resumed = walk(staging, second)

            val full = walk(Staging(), mutableListOf())
            assertEquals(ids(full), ids(resumed), "the resumed walk ends with the inventory an uninterrupted one has")
            assertEquals(full.files.size, resumed.files.size, "no file is listed twice")
            assertTrue(second.none { it in first }, "folders listed by the first attempt are not listed again: $first / $second")
            assertTrue("b1" in second, "the folder the outage hit is listed")
        }

    @Test
    fun `the resumed walk keeps the instant the first attempt began`() =
        runTest {
            val staging = Staging()
            assertFailsWith<IllegalStateException> { walk(staging, mutableListOf(), failOn = "b1") }
            assertEquals("2026-10-09T10:00:00Z", assertNotNull(treeResumeOf(staging.marker, staging.rows.values.toList(), listOf("/"))).marker.startedAt)
        }

    @Test
    fun `a staged file keeps the modification time string the server sent`() =
        runTest {
            val staging = Staging()
            assertFailsWith<IllegalStateException> { walk(staging, mutableListOf(), failOn = "b1") }
            val resumed = assertNotNull(treeResumeOf(staging.marker, staging.rows.values.toList(), listOf("/")))
            val top = resumed.files.first { it.uuid == "top" }
            assertEquals(InternxtProvider.internxtVersionToken(file("top", "top.txt")), InternxtProvider.internxtVersionToken(top))
        }

    @Test
    fun `a walk is not resumed under other scope roots or without its rows`() =
        runTest {
            val staging = Staging()
            assertFailsWith<IllegalStateException> { walk(staging, mutableListOf(), failOn = "b1") }
            val rows = staging.rows.values.toList()

            assertNull(treeResumeOf(staging.marker, rows, listOf("/a")), "other scope roots")
            assertNull(treeResumeOf(staging.marker, emptyList(), listOf("/")), "marker claims a finished start folder, its rows are gone")
            assertNull(treeResumeOf(cursorMarker("2026-10-09T10:00:00Z", StreamPosition(), StreamPosition()), rows, listOf("/")), "another strategy's marker")
            assertNull(treeResumeOf("12|34", rows, listOf("/")), "the offset listing's marker")
            assertNull(treeResumeOf(null, rows, listOf("/")), "no marker")
        }

    @Test
    fun `rows of another listing are not mistaken for tree rows`() {
        val foreign = listOf(CloudItem("x", "x", "/x", 0, true, null, null, CURSOR_STAGED_TAG, null))
        assertTrue(foreign.none(::isTreeStaged))
    }
}
