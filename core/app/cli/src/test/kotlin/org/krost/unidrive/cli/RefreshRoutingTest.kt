package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import kotlin.coroutines.coroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshRoutingTest {
    private lateinit var db: StateDatabase
    private lateinit var server: IpcServer

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("ud-route-db").resolve("state.db")).also { it.initialize() }
        server = IpcServer(Files.createTempDirectory("ud-route-sock").resolve("d.sock"))
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun refresh_routes_to_enumerate_when_a_mount_client_is_connected(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val emitted = mutableListOf<String>()
        val handler =
            RefreshRpcHandler(
                server,
                engine,
                db,
                scope,
                mountClientConnected = { true },
                emit = { emitted.add(it) },
            )

        handler.handle("conn-1", """{"verb":"refresh.run"}""")
        handler.awaitInFlight()

        assertTrue(engine.enumerateCalled, "mount client connected must route to enumerateRemoteIntoState")
        assertFalse(engine.syncOnceCalled, "legacy syncOnce must NOT run for a mounted profile")
    }

    @Test
    fun refresh_uses_legacy_reconcile_when_no_mount_client_is_connected(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val handler =
            RefreshRpcHandler(
                server,
                engine,
                db,
                scope,
                mountClientConnected = { false },
                emit = {},
            )

        handler.handle("conn-1", """{"verb":"refresh.run"}""")
        handler.awaitInFlight()

        assertTrue(engine.syncOnceCalled, "no mount client must use the legacy syncOnce path")
        assertFalse(engine.enumerateCalled, "enumerate must NOT run when no mount client is connected")
    }

    @Test
    fun refresh_surfaces_force_delete_ignored_on_the_enumerate_branch(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val emitted = mutableListOf<String>()
        val handler =
            RefreshRpcHandler(
                server,
                engine,
                db,
                scope,
                mountClientConnected = { true },
                emit = { emitted.add(it) },
            )

        handler.handle("conn-1", """{"verb":"refresh.run","force_delete":true}""")
        handler.awaitInFlight()

        val ev = emitted.single()
        assertTrue(ev.contains("\"event\":\"refresh.done\""), ev)
        assertTrue(ev.contains("\"ok\":true"), ev)
        assertTrue(
            ev.contains("\"force_delete_ignored\":true"),
            "force_delete on the enumerate branch must be surfaced, not silently dropped: $ev",
        )
    }

    @Test
    fun refresh_does_not_surface_force_delete_ignored_without_force_delete(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
        val emitted = mutableListOf<String>()
        val handler =
            RefreshRpcHandler(
                server,
                engine,
                db,
                scope,
                mountClientConnected = { true },
                emit = { emitted.add(it) },
            )

        handler.handle("conn-1", """{"verb":"refresh.run"}""")
        handler.awaitInFlight()

        val ev = emitted.single()
        assertFalse(ev.contains("force_delete_ignored"), "no force_delete in request → no force_delete_ignored: $ev")
    }
}
