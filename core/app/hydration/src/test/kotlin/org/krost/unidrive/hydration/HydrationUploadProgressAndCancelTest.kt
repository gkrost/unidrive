package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upload progress events (bytes done/total per open_write, coalesced,
 * correlated by the client's handle id) and the hydration.cancel verb for
 * queued or running uploads. (Client side: the Windows column's `Uploading n
 * %` and the progress flyout.)
 */
class HydrationUploadProgressAndCancelTest {
    private fun writeCache(env: HydrationTestEnv, path: String, content: String) {
        val cache = env.syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    @Test
    fun `uploading events report monotonic progress and the final completed`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, uploadProgressMinIntervalMs = 0)
        val provider = env.providerForTest
        provider.progressSteps = 4
        env.stateDb.insertCreatedRow("/docs/big.bin")
        writeCache(env, "/docs/big.bin", "0123456789")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/big.bin", env.syncEngine.resolveCachePath("/docs/big.bin"))
        advanceUntilIdle()

        val uploads = events.filterIsInstance<HydrationEvent.Uploading>()
        assertTrue(uploads.isNotEmpty(), "a progress-reporting upload must emit uploading events")
        assertEquals("h1", uploads.first().handleId, "progress must be correlated to the client's handle")
        assertEquals(10L, uploads.last().bytesTotal)
        val dones = uploads.map { it.bytesDone }
        assertEquals(dones.sorted(), dones, "progress bytes_done must be monotonic")
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertTrue(completed.ok, "progress ends in a successful Completed")
        collector.cancel()
    }

    @Test
    fun `uploading events are coalesced to a few per second`() = runBlocking {
        // Real time: the fake paces 10 progress callbacks 50ms apart (~500ms
        // total); the 400ms coalescing gap must collapse them to a handful.
        val env = HydrationTestEnv(uploadProgressMinIntervalMs = 400)
        val provider = env.providerForTest
        provider.progressSteps = 10
        provider.progressPaceMs = 50
        env.stateDb.insertCreatedRow("/docs/big.bin")
        writeCache(env, "/docs/big.bin", "0123456789")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/big.bin", env.syncEngine.resolveCachePath("/docs/big.bin"))
        collector.joinUploadingQuietPeriod(events, minEvents = 1)

        val uploads = events.filterIsInstance<HydrationEvent.Uploading>()
        assertTrue(
            uploads.size in 1..5,
            "400ms coalescing over ~500ms of 50ms callbacks must emit a few events, saw ${uploads.size}",
        )
        collector.cancel()
    }

    // Polls until an uploading event has landed (no replay: the collector must
    // be subscribed first), bounded by a real-time deadline.
    private suspend fun kotlinx.coroutines.Job.joinUploadingQuietPeriod(
        events: MutableList<HydrationEvent>,
        minEvents: Int,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (events.count { it is HydrationEvent.Uploading } < minEvents && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
        }
        kotlinx.coroutines.delay(600)
    }

    @Test
    fun `cancel during a running upload leaves no remote item and ends the handle`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val gate = CompletableDeferred<Unit>()
        env.providerForTest.uploadGate = gate
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "do not land")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle() // worker now parked inside the provider transfer

        assertTrue(env.hydration.cancelUpload("/docs/f.txt"), "a running upload must be cancellable")
        advanceUntilIdle()

        assertNull(env.syncEngine.remoteContentSeen("/docs/f.txt"), "a cancelled upload must not land remotely")
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertFalse(completed.ok)
        assertEquals(HydrationError.CANCELLED_TOKEN, completed.error?.message)
        assertEquals("h1", completed.handleId)
        assertNull(env.stateDb.lastErrorAt("/docs/f.txt"), "a cancelled upload is not a failed upload")
        collector.cancel()
    }

    @Test
    fun `cancel aborts queued same-path submissions and a later upload still runs`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 1)
        val gate = CompletableDeferred<Unit>()
        env.providerForTest.uploadGate = gate
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "stale bytes")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        // First parks inside the transfer; second waits for its per-path turn.
        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        env.hydration.openForWrite("conn1", "h2", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle()

        assertTrue(env.hydration.cancelUpload("/docs/f.txt"))
        advanceUntilIdle()

        val cancelled = events.filterIsInstance<HydrationEvent.Completed>()
            .filter { it.error?.message == HydrationError.CANCELLED_TOKEN }
        assertEquals(setOf("h1", "h2"), cancelled.map { it.handleId }.toSet(), "both submissions must be terminated")

        // A fresh write after the cancel gets a fresh, uncancelled upload.
        gate.complete(Unit)
        val r = env.hydration.openForWrite("conn1", "h3", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        assertIs<OpenResult.Ok>(r)
        advanceUntilIdle()
        val last = events.filterIsInstance<HydrationEvent.Completed>().last()
        assertTrue(last.ok, "a post-cancel upload must run normally")
        assertEquals("stale bytes", env.syncEngine.remoteContentSeen("/docs/f.txt"))
        collector.cancel()
    }

    @Test
    fun `cancel before the worker coroutine first runs still releases the slot and the queue permit`() = runTest {
        // The worker is launched on the (test) dispatcher and has not been scheduled
        // when the cancel arrives. A plainly-launched coroutine cancelled before its
        // first dispatch never runs its body, so neither its `finally` (pending count,
        // slot removal) nor its queue-permit release executes: the path would then be
        // busy to dehydrate and protected from cache reaping forever, one waiting-queue
        // permit would be lost per occurrence, and the client handle would never get a
        // Completed.
        val env = HydrationTestEnv(recoveryUploadScope = this, uploadQueueDepth = 1)
        env.stateDb.insertCreatedRow("/docs/f.txt")
        env.stateDb.insertCreatedRow("/docs/g.txt")
        writeCache(env, "/docs/f.txt", "f bytes")
        writeCache(env, "/docs/g.txt", "g bytes")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        assertTrue(env.hydration.cancelUpload("/docs/f.txt"), "a queued, not yet started upload must be cancellable")
        advanceUntilIdle()

        assertFalse(env.hydration.hasUploadSlot("/docs/f.txt"), "the cancelled upload must not leave its slot behind")
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }
        assertEquals(HydrationError.CANCELLED_TOKEN, completed.error?.message)
        assertNull(env.syncEngine.remoteContentSeen("/docs/f.txt"))

        // Depth 1: if the cancelled job leaked its waiting-queue permit this submission suspends forever.
        val secondReturned = CompletableDeferred<Unit>()
        launch {
            env.hydration.openForWrite("conn1", "h2", "/docs/g.txt", env.syncEngine.resolveCachePath("/docs/g.txt"))
            secondReturned.complete(Unit)
        }
        advanceUntilIdle()
        assertTrue(secondReturned.isCompleted, "a leaked queue permit blocks every later open_write")
        assertEquals("g bytes", env.syncEngine.remoteContentSeen("/docs/g.txt"))
        collector.cancel()
    }

    @Test
    fun `cancel with nothing in flight answers cancelled false`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertCreatedRow("/docs/f.txt")
        assertFalse(env.hydration.cancelUpload("/docs/f.txt"))
        assertFalse(env.hydration.cancelUpload("/never/existed.txt"))
    }

    @Test
    fun `cancel verb replies with the cancelled flag`() = runTest {
        val handler = HydrationIpcHandler(NoopHydration())
        assertEquals(
            """{"ok":true,"cancelled":false}""",
            handler.handle("conn1", """{"verb":"hydration.cancel","path":"/a"}"""),
        )
        assertEquals(
            """{"ok":false,"error":"missing_path"}""",
            handler.handle("conn1", """{"verb":"hydration.cancel"}"""),
        )
    }

    private class NoopHydration : Hydration {
        override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult =
            OpenResult.Failed(HydrationError.UnknownPath)
        override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: java.nio.file.Path, baseEtag: String?): OpenResult =
            OpenResult.Failed(HydrationError.UnknownPath)
        override suspend fun closeHandle(connectionId: String, handleId: String) {}
        override suspend fun cancelUpload(path: String): Boolean = false
        override suspend fun hydrate(path: String): HydrateResult = HydrateResult.Ok
        override suspend fun dehydrate(path: String): DehydrateResult = DehydrateResult.Ok
        override suspend fun lastSynced(path: String): LastSyncedResult = LastSyncedResult.Unknown("unknown_path")
        override suspend fun list(prefix: String): ListResult = ListResult.Ok(emptyList())
        override suspend fun mkdir(path: String): MkdirResult = MkdirResult.Ok
        override suspend fun unlink(path: String): UnlinkResult = UnlinkResult.Ok
        override suspend fun rmdir(path: String): RmdirResult = RmdirResult.Ok
        override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult =
            CreateResult.Failed(HydrationError.UnknownPath)
        override suspend fun openWriteBegin(connectionId: String, path: String, handleId: String?): OpenResult =
            OpenResult.Failed(HydrationError.UnknownPath)
        override suspend fun rename(oldPath: String, newPath: String, replace: Boolean): RenameResult = RenameResult.Ok
        override val events = kotlinx.coroutines.flow.emptyFlow<HydrationEvent>()
        override fun onConnectionClosed(connectionId: String) {}
    }
}
