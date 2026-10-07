package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `refresh.run` with `"reset": true` and no mount client clears state.db and re-enumerates. The rows that
 * still await upload, and the rows whose hydration cache holds an edit the cloud has not seen, stay. The
 * request is read as JSON: only a top-level boolean `reset` / `force_delete` counts.
 */
class RefreshResetKeepsPendingTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private lateinit var server: IpcServer
    private lateinit var engine: RecordingEngine
    private val events = mutableListOf<String>()

    private val watermark = Instant.parse("2026-03-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-reset-keeps")
        db = StateDatabase(tmp.resolve("state.db")).also { it.initialize() }
        server = IpcServer(tmp.resolve("d.sock"))
        engine = RecordingEngine(cacheRoot = tmp.resolve("cache"))
        db.upsertEntry(row("/synced.txt", remoteId = "id-synced"))
        db.upsertEntry(row("/pending.txt", remoteId = null))
        db.setSyncState("delta_cursor", "cursor-before")
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun row(
        path: String,
        remoteId: String?,
        hydrated: Boolean = true,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = null,
        localMtime = 1_000,
        localSize = 1,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = watermark,
    )

    private fun cacheCopy(
        path: String,
        modified: Instant,
    ) {
        val file = engine.resolveCachePath(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, "bytes")
        Files.setLastModifiedTime(file, FileTime.from(modified))
    }

    private fun refresh(
        request: String,
        mounted: Boolean = false,
        uploadInFlight: (String) -> Boolean = { false },
    ) = runBlocking {
        engine.syncOnceCalled = false
        val handler =
            RefreshRpcHandler(
                server,
                engine,
                db,
                CoroutineScope(coroutineContext + SupervisorJob()),
                mountClientConnected = { mounted },
                uploadInFlight = uploadInFlight,
                emit = { events += it },
            )
        val reply = handler.handle("conn-1", request)
        handler.awaitInFlight()
        reply
    }

    private fun assertNothingCleared(why: String) {
        assertNotNull(db.getEntry("/synced.txt"), "$why: the synced row stays")
        assertNotNull(db.getEntry("/pending.txt"), "$why: the pending row stays")
        assertEquals("cursor-before", db.getSyncState("delta_cursor"), "$why: the cursor stays")
        assertTrue(engine.syncOnceCalled, "$why: the refresh itself still runs")
    }

    @Test
    fun `a reset without a mount client keeps the row that awaits upload and clears the rest`() {
        val reply = refresh("""{"verb":"refresh.run","reset":true}""")

        assertTrue(reply.contains("\"ok\":true"), reply)
        assertTrue(engine.syncOnceCalled)
        assertEquals(true to false, engine.lastSyncOnceFlags)
        assertNull(db.getEntry("/synced.txt"), "a row with a cloud id is cleared")
        assertNull(db.getSyncState("delta_cursor"), "the cursor is cleared")
        val kept = db.getEntry("/pending.txt")
        assertNotNull(kept, "the row that still awaits upload is kept")
        assertNull(kept.remoteId)
        assertEquals(listOf("/pending.txt"), db.pendingUploadPaths())
        assertTrue(events.single().contains("\"ok\":true"), events.toString())
    }

    @Test
    fun `a row whose upload is under way is kept even though it has a cloud id`() {
        db.upsertEntry(row("/uploading.txt", remoteId = "id-uploading"))

        refresh("""{"verb":"refresh.run","reset":true}""", uploadInFlight = { it == "/uploading.txt" })

        assertNotNull(db.getEntry("/uploading.txt"))
        assertNull(db.getEntry("/synced.txt"))
    }

    @Test
    fun `a row without local bytes is kept while its upload is under way`() {
        db.upsertEntry(row("/overwritten.txt", remoteId = "id-overwritten", hydrated = false))

        refresh("""{"verb":"refresh.run","reset":true}""", uploadInFlight = { it == "/overwritten.txt" })

        assertNotNull(db.getEntry("/overwritten.txt"))
    }

    @Test
    fun `a row with a failed attempt and a newer cache copy is kept even without local bytes`() {
        db.upsertEntry(row("/failed.txt", remoteId = "id-failed", hydrated = false))
        db.markUploadFailed("/failed.txt", Instant.parse("2026-03-02T00:00:00Z"))
        cacheCopy("/failed.txt", watermark.plusSeconds(60))

        refresh("""{"verb":"refresh.run","reset":true}""")

        assertNotNull(db.getEntry("/failed.txt"))
        assertNull(db.getEntry("/synced.txt"))
    }

    @Test
    fun `a row whose cache copy is newer than its watermark is kept, one whose copy is not is cleared`() {
        db.upsertEntry(row("/edited.txt", remoteId = "id-edited"))
        db.upsertEntry(row("/read-only.txt", remoteId = "id-read-only"))
        cacheCopy("/edited.txt", watermark.plusSeconds(60))
        cacheCopy("/read-only.txt", watermark.minusSeconds(60))

        refresh("""{"verb":"refresh.run","reset":true}""")

        assertNotNull(db.getEntry("/edited.txt"), "the cache holds an edit the cloud has not seen")
        assertNull(db.getEntry("/read-only.txt"), "the cache copy is what the cloud has")
        assertNull(db.getEntry("/synced.txt"), "no cache copy at all")
    }

    @Test
    fun `without a reset nothing is cleared`() {
        refresh("""{"verb":"refresh.run"}""")
        assertNothingCleared("no reset field")

        refresh("""{"verb":"refresh.run","reset":false}""")
        assertNothingCleared("reset false")
    }

    @Test
    fun `a reset field inside a nested object or array does not count`() {
        for (body in listOf(
            """{"verb":"refresh.run","opts":{"reset":true}}""",
            """{"opts":{"reset":true},"verb":"refresh.run"}""",
            """{"verb":"refresh.run","reset":false,"opts":{"reset":true}}""",
            """{"verb":"refresh.run","list":[{"reset":true}]}""",
            """{"verb":"refresh.run","deep":{"a":{"b":{"reset":true}}}}""",
        )) {
            refresh(body)
            assertNothingCleared(body)
        }
    }

    @Test
    fun `a reset text inside a string value does not count`() {
        for (body in listOf(
            """{"verb":"refresh.run","note":"\"reset\":true"}""",
            """{"verb":"refresh.run","note":"x \"reset\" : true y"}""",
            """{"verb":"refresh.run","reset-me":true}""",
            """{"verb":"refresh.run","resetting":true}""",
        )) {
            refresh(body)
            assertNothingCleared(body)
        }
    }

    @Test
    fun `only the JSON boolean true is a reset`() {
        for (body in listOf(
            """{"verb":"refresh.run","reset":"true"}""",
            """{"verb":"refresh.run","reset":1}""",
            """{"verb":"refresh.run","reset":null}""",
            """{"verb":"refresh.run","reset":"yes"}""",
            """{"verb":"refresh.run","reset":[true]}""",
            """{"verb":"refresh.run","reset":{"value":true}}""",
        )) {
            refresh(body)
            assertNothingCleared(body)
        }
    }

    @Test
    fun `a body that is not a JSON object clears nothing`() {
        for (body in listOf(
            """{"verb":"refresh.run","reset":true""",
            """["refresh.run",{"reset":true}]""",
            """"reset":true""",
            """true""",
            "",
        )) {
            refresh(body)
            assertNothingCleared(body)
        }
    }

    @Test
    fun `a top-level reset is found among other fields, in any order and spacing`() {
        refresh("""{ "extra" : {"reset":false,"n":[1,2]} , "client" : "x" , "reset" : true , "verb" : "refresh.run" }""")

        assertNull(db.getEntry("/synced.txt"))
        assertNotNull(db.getEntry("/pending.txt"))
    }

    @Test
    fun `with a mount client the reset goes to the enumeration and only a top-level reset does`() {
        engine.enumerateResult = EnumerateResult(ok = true)

        refresh("""{"verb":"refresh.run","reset":true}""", mounted = true)
        assertEquals(true, engine.lastReset)

        refresh("""{"verb":"refresh.run","opts":{"reset":true}}""", mounted = true)
        assertEquals(false, engine.lastReset, "a nested reset is not a reset")

        assertNotNull(db.getEntry("/synced.txt"), "the mounted route never clears rows")
        assertNotNull(db.getEntry("/pending.txt"))
    }

    @Test
    fun `force_delete is reported as ignored on the mount route only for a top-level true`() {
        engine.enumerateResult = EnumerateResult(ok = true)

        refresh("""{"verb":"refresh.run","force_delete":true}""", mounted = true)
        assertTrue(events.last().contains("\"force_delete_ignored\":true"), events.last())

        for (body in listOf(
            """{"verb":"refresh.run"}""",
            """{"verb":"refresh.run","force_delete":false}""",
            """{"verb":"refresh.run","force_delete":"true"}""",
            """{"verb":"refresh.run","opts":{"force_delete":true}}""",
            """{"verb":"refresh.run","note":"\"force_delete\":true"}""",
        )) {
            refresh(body, mounted = true)
            assertFalse(events.last().contains("force_delete_ignored"), "$body -> ${events.last()}")
        }
    }
}
