package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
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
 * #560 U1: what `refresh.run` does with state.db today, by route. RefreshRoutingTest pins which engine entry point
 * runs; these pin the `reset` handling and the flags of the reconcile route. A reset keeps the rows that still await
 * upload (RefreshResetKeepsPendingTest has the detail). #560 U4 changes this routing on purpose (refresh of a mount
 * profile always enumerates); these tests then change with it.
 */
class RefreshRoutingCurrentBehaviourTest {
    private lateinit var db: StateDatabase
    private lateinit var server: IpcServer

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("ud-560-route-db").resolve("state.db")).also { it.initialize() }
        server = IpcServer(Files.createTempDirectory("ud-560-route-sock").resolve("d.sock"))
        // A synced row and a pending upload written through the mount (never uploaded).
        db.upsertEntry(row("/synced.txt", remoteId = "id-1"))
        db.upsertEntry(row("/pending.txt", remoteId = null))
        db.setSyncState("delta_cursor", "cursor-before")
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(
        path: String,
        remoteId: String?,
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
        isHydrated = true,
        lastSynced = Instant.EPOCH,
    )

    private fun refresh(
        engine: RecordingEngine,
        mounted: Boolean,
        request: String,
    ) = runBlocking {
        val handler =
            RefreshRpcHandler(
                server,
                engine,
                db,
                CoroutineScope(coroutineContext + SupervisorJob()),
                mountClientConnected = { mounted },
                emit = {},
            )
        handler.handle("conn-1", request)
        handler.awaitInFlight()
    }

    @Test
    fun `current routing - without a mount client, reset clears state_db except the rows that await upload, then reconciles without transfers`() {
        val engine = RecordingEngine()

        refresh(engine, mounted = false, request = """{"verb":"refresh.run","reset":true}""")

        assertTrue(engine.syncOnceCalled)
        assertEquals(true to false, engine.lastSyncOnceFlags, "syncOnce(skipTransfers = true, skipRemoteGather = false)")
        assertNull(db.getEntry("/synced.txt"), "the synced row was cleared")
        assertNotNull(db.getEntry("/pending.txt"), "the row that still awaits upload was kept")
        assertEquals(listOf("/pending.txt"), db.pendingUploadPaths())
        assertNull(db.getSyncState("delta_cursor"), "and the cursor was cleared")
    }

    @Test
    fun `current routing - without a mount client and without reset, state_db is kept and the reconcile runs without transfers`() {
        val engine = RecordingEngine()

        refresh(engine, mounted = false, request = """{"verb":"refresh.run"}""")

        assertEquals(true to false, engine.lastSyncOnceFlags)
        assertNotNull(db.getEntry("/synced.txt"))
        assertNotNull(db.getEntry("/pending.txt"))
        assertEquals("cursor-before", db.getSyncState("delta_cursor"))
    }

    @Test
    fun `current routing - with a mount client, reset is handed to the enumeration and state_db is not cleared`() {
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))

        refresh(engine, mounted = true, request = """{"verb":"refresh.run","reset":true}""")

        assertTrue(engine.enumerateCalled)
        assertFalse(engine.syncOnceCalled)
        assertEquals(true, engine.lastReset, "enumerateRemoteIntoState(reset = true)")
        assertNotNull(db.getEntry("/synced.txt"), "no db.resetAll() on the mount route")
        assertNotNull(db.getEntry("/pending.txt"))
        assertEquals("cursor-before", db.getSyncState("delta_cursor"), "the handler itself leaves the cursor to the engine")
    }

    @Test
    fun `current routing - with a mount client and without reset, the enumeration runs incrementally`() {
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))

        refresh(engine, mounted = true, request = """{"verb":"refresh.run"}""")

        assertEquals(false, engine.lastReset)
    }
}
