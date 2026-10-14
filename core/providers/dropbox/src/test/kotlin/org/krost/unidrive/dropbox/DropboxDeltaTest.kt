package org.krost.unidrive.dropbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.DeltaCursorExpiredException
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.dropbox.FakeDropbox.Companion.deleted
import org.krost.unidrive.dropbox.FakeDropbox.Companion.file
import org.krost.unidrive.dropbox.FakeDropbox.Companion.folder
import java.nio.file.Files
import java.time.Instant
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The change feed: `list_folder` (recursive, deletions asked for) and `continue` as the delta, `get_latest_cursor` as the
 * fast bootstrap, `reset` as an expired cursor, the long-poll as the wake-up hint. Display paths follow the parent's case.
 */
class DropboxDeltaTest {
    private val dir = Files.createTempDirectory("dropbox-delta")
    private val fake = FakeDropbox()
    private val provider = FakeDropbox.provider(fake, dir)

    @Test
    fun `the provider is found by the service loader`() {
        assertTrue(ServiceLoader.load(ProviderFactory::class.java).any { it.id == "dropbox" })
    }

    @Test
    fun `a full listing pages through continue, keeps the parent's case and leaves out old deletions`() =
        runTest {
            fake.json(
                "files/list_folder",
                """{"entries":[${folder("/Docs", "id:f")},${file("/docs/Report.PDF", "id:1", hash = "a".repeat(64), size = 12)},${deleted("/gone.txt")}],""" +
                    """"cursor":"c1","has_more":true}""",
            )
            fake.json("files/list_folder/continue", """{"entries":[${deleted("/old.txt")},${file("/Top.txt", "id:2")}],"cursor":"c2","has_more":false}""")

            val first = provider.delta(null)
            val second = provider.delta(first.cursor)

            assertEquals(listOf("/Docs", "/Docs/Report.PDF"), first.items.map { it.path })
            assertTrue(first.hasMore)
            val report = first.items[1]
            assertEquals("id:1", report.id)
            assertEquals(12, report.size)
            assertEquals("a".repeat(64), report.hash)
            assertEquals(Instant.parse("2026-01-02T03:04:05Z"), report.modified)
            assertTrue(first.items[0].isFolder)
            assertEquals(listOf("/Top.txt"), second.items.map { it.path })
            assertFalse(second.hasMore)
            assertEquals("c2", second.cursor)

            val args = fake.callsTo("files/list_folder").single().args!!
            assertEquals("", args["path"]!!.jsonPrimitive.content)
            assertTrue(args["recursive"]!!.jsonPrimitive.boolean)
            assertTrue(args["include_deleted"]!!.jsonPrimitive.boolean, "a cursor made without deletions would never report one")
            assertFalse(args["include_non_downloadable_files"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `the heartbeat counts the walk's items so far and a new walk counts from the start`() =
        runTest {
            fake.json(
                "files/list_folder",
                """{"entries":[${file("/a.txt", "id:1")},${file("/b.txt", "id:2")}],"cursor":"c1","has_more":true}""",
            )
            fake.json("files/list_folder/continue", """{"entries":[${file("/c.txt", "id:3")}],"cursor":"c2","has_more":false}""")

            val progress = mutableListOf<Int>()
            val first = provider.delta(null, onPageProgress = { progress += it })
            val second = provider.delta(first.cursor, onPageProgress = { progress += it })
            assertEquals(listOf(2, 3), progress)

            // the next gather is its own walk (the engine's incremental one) and counts from the start again
            provider.delta(second.cursor, onPageProgress = { progress += it })
            assertEquals(listOf(2, 3, 1), progress)
        }

    @Test
    fun `an incremental page reports a deletion with the id last seen at its path`() =
        runTest {
            fake.json("files/list_folder", """{"entries":[${file("/a.txt", "id:7")}],"cursor":"c1","has_more":false}""")
            fake.json("files/list_folder/continue", """{"entries":[${deleted("/A.txt")}],"cursor":"c2","has_more":false}""")

            provider.delta(null)
            val change = provider.delta("c1").items.single()

            assertTrue(change.deleted)
            assertEquals("id:7", change.id)
            assertEquals("/A.txt", change.path)
        }

    @Test
    fun `listing a folder asks only for what exists and pages through continue`() =
        runTest {
            fake.json("files/list_folder", """{"entries":[${file("/Docs/a.txt", "id:1")}],"cursor":"k1","has_more":true}""")
            fake.json("files/list_folder/continue", """{"entries":[${folder("/Docs/Sub", "id:2")}],"cursor":"k2","has_more":false}""")

            val children = provider.listChildren("/Docs/")

            assertEquals(listOf("/Docs/a.txt", "/Docs/Sub"), children.map { it.path })
            val args = fake.callsTo("files/list_folder").single().args!!
            assertEquals("/Docs", args["path"]!!.jsonPrimitive.content)
            assertFalse(args["recursive"]!!.jsonPrimitive.boolean)
            assertFalse(args["include_deleted"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `a reset cursor asks for a full listing`() =
        runTest {
            fake.json("files/list_folder/continue", """{"error_summary":"reset/..","error":{".tag":"reset"}}""", status = 409)

            assertFailsWith<DeltaCursorExpiredException> { provider.delta("stale") }
        }

    @Test
    fun `the fast bootstrap takes the latest cursor with deletions asked for`() =
        runTest {
            fake.json("files/list_folder/get_latest_cursor", """{"cursor":"now"}""")

            val page = (provider.deltaFromLatest() as CapabilityResult.Success).value

            assertEquals("now", page.cursor)
            assertTrue(page.items.isEmpty())
            assertTrue(fake.callsTo("files/list_folder/get_latest_cursor").single().args!!["include_deleted"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `a team account sends its root namespace as the path root`() =
        runTest {
            fake.json(
                "users/get_current_account",
                """{"root_info":{".tag":"team","root_namespace_id":"900","home_namespace_id":"901"}}""",
            )
            fake.json("files/list_folder", """{"entries":[],"cursor":"c","has_more":false}""")

            provider.delta(null)

            val header = fake.callsTo("files/list_folder").single().headers[DropboxApi.PATH_ROOT]
            assertEquals("""{".tag":"root","root":"900"}""", header)
        }

    @Test
    fun `a personal account sends no path root`() =
        runTest {
            fake.json("files/list_folder", """{"entries":[],"cursor":"c","has_more":false}""")

            provider.delta(null)

            assertNull(fake.callsTo("files/list_folder").single().headers[DropboxApi.PATH_ROOT])
        }

    @Test
    fun `the long-poll on the last cursor wakes the engine`() =
        runBlocking {
            fake.json("files/list_folder", """{"entries":[],"cursor":"c9","has_more":false}""")
            fake.json("files/list_folder/longpoll", """{"changes":true}""")
            provider.delta(null)
            val woke = CompletableDeferred<Unit>()

            provider.onRemoteChangeHint { woke.complete(Unit) }
            withTimeout(10_000) { woke.await() }
            provider.close()

            val poll = fake.callsTo("files/list_folder/longpoll").first()
            assertEquals("c9", poll.args!!["cursor"]!!.jsonPrimitive.content)
            assertNull(poll.headers["Authorization"], "the long-poll takes no token")
        }
}
