package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
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
    fun refresh_propagates_enumerate_failure_on_the_mount_branch(): Unit = runBlocking {
        // enumerateRemoteIntoState converts provider failures to EnumerateResult(ok=false) rather
        // than throwing; the handler must report that, not emit a false success to subscribers.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = false, error = "delta 503"))
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
        assertTrue(ev.contains("\"event\":\"refresh.done\""), ev)
        assertTrue(ev.contains("\"ok\":false"), "a failed enumerate must report ok:false, not success: $ev")
        assertTrue(ev.contains("\"error\":\"provider_error\""), ev)
        assertTrue(ev.contains("delta 503"), "the enumerate error message must propagate: $ev")
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

    @Test
    fun refresh_run_returns_busy_when_concurrent(): Unit = runBlocking {
        // Invariant I6: at most one refresh.run in flight per daemon. A second
        // request while the first is still running must be rejected with "busy",
        // not started concurrently. The gate holds the first refresh open across
        // the second handle() call.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true), gate = gate)
        val handler =
            RefreshRpcHandler(
                server,
                engine,
                db,
                scope,
                mountClientConnected = { true },
                emit = {},
            )

        val first = handler.handle("conn-1", """{"verb":"refresh.run"}""")
        assertTrue(first.contains("\"ok\":true"), "first refresh must be accepted: $first")

        // Yield until the launched job has actually entered the engine (gated open).
        repeat(200) {
            if (engine.enumerateCalled) return@repeat
            yield()
        }
        assertTrue(engine.enumerateCalled, "first refresh must be in flight before the busy check")

        val second = handler.handle("conn-2", """{"verb":"refresh.run"}""")
        assertTrue(second.contains("\"ok\":false"), "second concurrent refresh must be rejected: $second")
        assertTrue(second.contains("\"error\":\"busy\""), "concurrent refresh must report busy: $second")

        gate.complete(Unit)
        handler.awaitInFlight()
    }

    @Test
    fun refresh_run_emits_provider_error_on_engine_failure(): Unit = runBlocking {
        // A throwing engine (vs. an EnumerateResult(ok=false)) must be caught and
        // surfaced as a provider_error terminal event, with the exception message
        // propagated and embedded quotes escaped — never an uncaught crash or a
        // false success.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = ThrowingEngine(IllegalStateException("delta \"503\""))
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
        assertTrue(ev.contains("\"event\":\"refresh.done\""), ev)
        assertTrue(ev.contains("\"ok\":false"), "a throwing engine must report ok:false: $ev")
        assertTrue(ev.contains("\"error\":\"provider_error\""), ev)
        assertTrue(ev.contains("""delta \"503\""""), "the exception message must propagate with quotes escaped: $ev")
    }

    @Test
    fun refresh_run_emits_shutdown_on_daemon_close(): Unit = runBlocking {
        // Cancelling the daemon scope while a refresh is in flight must produce a
        // shutdown terminal event (CancellationException path), not a provider_error
        // and not a silent drop.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true), gate = gate)
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

        val launchedJob = scope.coroutineContext[kotlinx.coroutines.Job]
        handler.handle("conn-1", """{"verb":"refresh.run"}""")

        // Wait until the refresh has actually suspended inside the engine (gated open).
        repeat(200) {
            if (engine.enumerateCalled) return@repeat
            yield()
        }
        assertTrue(engine.enumerateCalled, "refresh must be in flight before the close")

        // Simulate daemon close: cancel the in-flight refresh's children, then let
        // its cancellation propagate. The gate is never completed.
        launchedJob?.cancelChildren()
        repeat(200) {
            if (emitted.isNotEmpty()) return@repeat
            yield()
        }

        val ev = emitted.single()
        assertTrue(ev.contains("\"event\":\"refresh.done\""), ev)
        assertTrue(ev.contains("\"ok\":false"), ev)
        assertTrue(ev.contains("\"error\":\"shutdown\""), "scope cancellation must emit shutdown, not provider_error: $ev")
    }
}

/**
 * Engine double whose refresh entry points throw (vs. RecordingEngine, which
 * returns or gates). Used to exercise the provider_error reply path. Subclasses
 * the open SyncEngine with throwaway in-temp deps; no engine internals run.
 */
private class ThrowingEngine(
    private val failure: Throwable,
) : SyncEngine(
        provider = ThrowingNoopProvider,
        db = freshDb(),
        syncRoot = Files.createTempDirectory("ud-throwing-root"),
    ) {
    override suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult = throw failure

    override suspend fun syncOnce(
        dryRun: Boolean,
        forceDelete: Boolean,
        reason: org.krost.unidrive.sync.SyncReason,
        skipTransfers: Boolean,
        skipRemoteGather: Boolean,
    ): Unit = throw failure

    companion object {
        private fun freshDb(): StateDatabase {
            val dir = Files.createTempDirectory("ud-throwing-db")
            return StateDatabase(dir.resolve("state.db")).also { it.initialize() }
        }
    }

    private object ThrowingNoopProvider : CloudProvider {
        override val id = "noop"
        override val displayName = "Noop"
        override var isAuthenticated = true

        override fun capabilities() = emptySet<org.krost.unidrive.Capability>()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem = error("noop")

        override suspend fun download(remotePath: String, destination: Path): Long = error("noop")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("noop")

        override suspend fun delete(remotePath: String) = error("noop")

        override suspend fun createFolder(path: String): CloudItem = error("noop")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("noop")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(emptyList(), "x", false)

        override suspend fun quota() = QuotaInfo(0, 0, 0)
    }
}
