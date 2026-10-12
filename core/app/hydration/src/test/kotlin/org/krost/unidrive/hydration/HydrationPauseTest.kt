package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #778: what a paused daemon does with the transfers of the mount. No upload starts and no cloud download
 * starts; a write made meanwhile is queued and reads as waiting, never failed; a transfer already running
 * finishes when it needs under ten seconds and is otherwise aborted and re-queued without counting a failure.
 */
class HydrationPauseTest {
    private fun writeCache(env: HydrationTestEnv, path: String, content: String) {
        val cache = env.syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    // The events the hydration layer emitted since the call. A collector of the test scope itself: one in
    // backgroundScope does not see an event emitted by a coroutine that is being cancelled.
    private class EventLog(
        private val events: MutableList<HydrationEvent>,
        private val collector: kotlinx.coroutines.Job,
    ) : MutableList<HydrationEvent> by events {
        fun stop() = collector.cancel()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.collect(env: HydrationTestEnv): EventLog {
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()
        return EventLog(events, collector)
    }

    // ── uploads ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a write made while paused is queued and waiting, not started and not failed`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertCreatedRow("/q/f.txt")
        writeCache(env, "/q/f.txt", "bytes")
        val events = collect(env)
        env.pause.pause("metered", auto = true)

        assertIs<OpenResult.Ok>(env.hydration.openForWrite("conn1", "h1", "/q/f.txt", env.syncEngine.resolveCachePath("/q/f.txt")))
        advanceUntilIdle()

        assertEquals(0, env.syncEngine.uploadAttempts(), "no upload starts while paused")
        assertTrue(events.any { it is HydrationEvent.Queued && it.path == "/q/f.txt" }, "the write is announced as queued")
        assertTrue(events.none { it is HydrationEvent.Failed || it is HydrationEvent.Completed }, "waiting is neither failure nor completion: $events")
        assertTrue(env.hydration.hasUploadSlot("/q/f.txt"), "the upload holds its place in the queue")
        val health = env.hydration.uploadHealth()
        assertEquals(1, health.pending, "the write is pending")
        assertEquals(0, health.inFlight)
        assertEquals(0, health.failed, "a paused upload is not a failed one")
        assertNull(env.stateDb.lastErrorAt("/q/f.txt"), "no failure is stamped on the row")
        val entry = assertIs<ListResult.Ok>(env.hydration.list("/q")).entries.single()
        assertTrue(entry.pendingUpload, "the listing says the bytes still wait for the cloud")
        assertFalse(entry.hasError)

        env.pause.resume()
        advanceUntilIdle()

        assertEquals("bytes", env.syncEngine.remoteContentSeen("/q/f.txt"), "resume lets the queued upload run")
        assertTrue(events.filterIsInstance<HydrationEvent.Completed>().single().ok)
        assertFalse(env.hydration.hasUploadSlot("/q/f.txt"))
        events.stop()
    }

    @Test
    fun `an upload that was waiting for a pause does not hold the queue back-pressure`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, uploadQueueDepth = 1)
        for (name in listOf("a", "b", "c")) {
            env.stateDb.insertCreatedRow("/q/$name.txt")
            writeCache(env, "/q/$name.txt", name)
        }
        env.pause.pause("user", auto = false)

        // With one waiting slot a second submission would suspend until the first got a transfer permit,
        // which it cannot while paused: the caller of open_write must not be held for the whole pause.
        for (name in listOf("a", "b", "c")) {
            assertIs<OpenResult.Ok>(env.hydration.openForWrite("conn1", "h-$name", "/q/$name.txt", env.syncEngine.resolveCachePath("/q/$name.txt")))
            advanceUntilIdle()
        }

        assertEquals(3, env.hydration.uploadHealth().pending)
        env.pause.resume()
        advanceUntilIdle()
        assertEquals(3, env.syncEngine.uploadAttempts())
    }

    @Test
    fun `the start-up replay does not start uploads while paused`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertCreatedRow("/q/replayed.txt")
        writeCache(env, "/q/replayed.txt", "bytes")
        env.pause.pause("user", auto = false)

        assertEquals(1, env.hydration.replayPendingUploads())
        advanceUntilIdle()
        assertEquals(0, env.syncEngine.uploadAttempts())

        env.pause.resume()
        advanceUntilIdle()
        assertEquals("bytes", env.syncEngine.remoteContentSeen("/q/replayed.txt"))
    }

    @Test
    fun `an upload in flight that needs more than ten seconds is aborted and re-queued without a failure`() = runTest {
        // One attempt allowed: if the abort were counted as an attempt, the resumed upload would never run.
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 1)
        val provider = env.providerForTest
        provider.progressBeforeGate = 1L to 1_000_000_000L // a byte of a gigabyte
        provider.uploadGate = CompletableDeferred()
        env.stateDb.insertCreatedRow("/q/big.bin")
        writeCache(env, "/q/big.bin", "payload")
        val events = collect(env)
        env.hydration.openForWrite("conn1", "h1", "/q/big.bin", env.syncEngine.resolveCachePath("/q/big.bin"))
        advanceUntilIdle()
        assertEquals(1, env.hydration.uploadHealth().inFlight, "the transfer is under way")

        env.pause.pause("metered", auto = true)
        assertEquals(1, env.pause.abortSlowTransfers())
        advanceUntilIdle()

        assertEquals(0, env.hydration.uploadHealth().inFlight, "the aborted transfer gave its permit back")
        assertTrue(events.none { it is HydrationEvent.Failed }, "an abort for a pause is not a failed attempt: $events")
        assertTrue(events.none { it is HydrationEvent.Completed }, "the upload is not over, it waits: $events")
        assertNull(env.stateDb.lastErrorAt("/q/big.bin"), "no failure is stamped on the row")
        assertTrue(env.hydration.hasUploadSlot("/q/big.bin"), "it is still queued")
        assertEquals(1, env.hydration.uploadHealth().pending)
        assertEquals(0, env.hydration.uploadHealth().failed)

        provider.uploadGate!!.complete(Unit)
        env.pause.resume()
        advanceUntilIdle()

        assertEquals(2, env.syncEngine.uploadAttempts(), "resume re-runs the aborted upload")
        assertEquals("payload", env.syncEngine.remoteContentSeen("/q/big.bin"))
        assertTrue(events.filterIsInstance<HydrationEvent.Completed>().single().ok)
        assertFalse(env.hydration.hasUploadSlot("/q/big.bin"), "no stale queue entry is left")
        assertEquals(0, env.hydration.uploadHealth().inFlight)
        events.stop()
    }

    @Test
    fun `an upload in flight that needs under ten seconds is left to finish`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val provider = env.providerForTest
        provider.progressBeforeGate = 9L to 10L // nearly done
        provider.uploadGate = CompletableDeferred()
        env.stateDb.insertCreatedRow("/q/small.txt")
        writeCache(env, "/q/small.txt", "0123456789")
        val events = collect(env)
        env.hydration.openForWrite("conn1", "h1", "/q/small.txt", env.syncEngine.resolveCachePath("/q/small.txt"))
        advanceUntilIdle()

        env.pause.pause("metered", auto = true)
        assertEquals(0, env.pause.abortSlowTransfers(), "nothing to abort")
        provider.uploadGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals("0123456789", env.syncEngine.remoteContentSeen("/q/small.txt"), "it finished although the daemon is paused")
        assertEquals(1, env.syncEngine.uploadAttempts())
        assertTrue(events.filterIsInstance<HydrationEvent.Completed>().single().ok)
        assertTrue(env.pause.isPaused, "the pause itself is untouched")
        events.stop()
    }

    @Test
    fun `an upload submitted while another is still finishing does not start before the pause ends`() = runTest {
        // The first upload is nearly done and parks inside the provider; the daemon is paused; a second upload
        // is submitted. The first may finish, the second must not start until the pause ends.
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val provider = env.providerForTest
        provider.progressBeforeGate = 9L to 10L
        provider.uploadGate = CompletableDeferred()
        env.stateDb.insertCreatedRow("/q/a.txt")
        env.stateDb.insertCreatedRow("/q/b.txt")
        writeCache(env, "/q/a.txt", "0123456789")
        writeCache(env, "/q/b.txt", "0123456789")
        env.hydration.openForWrite("conn1", "h1", "/q/a.txt", env.syncEngine.resolveCachePath("/q/a.txt"))
        advanceUntilIdle()

        env.pause.pause("user", auto = false)
        env.pause.abortSlowTransfers()
        env.hydration.openForWrite("conn1", "h2", "/q/b.txt", env.syncEngine.resolveCachePath("/q/b.txt"))
        advanceUntilIdle()
        val attemptsWhilePaused = env.syncEngine.uploadAttempts()
        provider.uploadGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, attemptsWhilePaused, "only the transfer that was already running")
        assertEquals(1, env.syncEngine.uploadAttempts(), "the second upload still waits for the end of the pause")
        env.pause.resume()
        advanceUntilIdle()
        assertEquals("0123456789", env.syncEngine.remoteContentSeen("/q/b.txt"))
    }

    @Test
    fun `cancelling an upload that waits for the end of a pause ends its handle`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertCreatedRow("/q/f.txt")
        writeCache(env, "/q/f.txt", "bytes")
        val events = collect(env)
        env.pause.pause("user", auto = false)
        env.hydration.openForWrite("conn1", "h1", "/q/f.txt", env.syncEngine.resolveCachePath("/q/f.txt"))
        advanceUntilIdle()

        assertTrue(env.hydration.cancelUpload("/q/f.txt"))
        advanceUntilIdle()

        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertEquals(HydrationError.CANCELLED_TOKEN, completed.error?.message)
        assertFalse(env.hydration.hasUploadSlot("/q/f.txt"))
        assertNull(env.stateDb.lastErrorAt("/q/f.txt"))
        events.stop()
    }

    @Test
    fun `stopping the uploads while paused ends them cleanly and leaves the write pending for the next start`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val env = HydrationTestEnv(recoveryUploadScope = scope)
        env.stateDb.insertCreatedRow("/q/f.txt")
        writeCache(env, "/q/f.txt", "bytes")
        env.pause.pause("user", auto = false)
        env.hydration.openForWrite("conn1", "h1", "/q/f.txt", env.syncEngine.resolveCachePath("/q/f.txt"))
        delay(100)
        assertTrue(env.hydration.hasUploadSlot("/q/f.txt"), "the upload waits")

        withTimeout(10_000) { env.hydration.shutdownUploads() }

        assertEquals(0, env.syncEngine.uploadAttempts())
        assertFalse(env.hydration.hasUploadSlot("/q/f.txt"), "no slot outlives the stop")
        val health = env.hydration.uploadHealth()
        assertEquals(1, health.pending, "the row is still a never-uploaded one: the next start replays it")
        assertEquals(0, health.failed)
        assertNull(env.stateDb.lastErrorAt("/q/f.txt"))
    }

    // ── downloads ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a cold file is refused at once with the paused error and nothing is downloaded`() = runBlocking {
        val env = HydrationTestEnv()
        env.syncEngine.seedRemoteContent("/d/cold.txt", "hello")
        env.stateDb.insertUnhydratedEntry("/d/cold.txt", remoteSize = 5)
        env.pause.pause("metered", auto = true)

        val read = env.hydration.openForRead("conn1", "h1", "/d/cold.txt")
        val hydrate = env.hydration.hydrate("/d/cold.txt")

        assertEquals(OpenResult.Failed(HydrationError.Paused), read)
        assertEquals(HydrateResult.Failed(HydrationError.Paused), hydrate)
        assertEquals("paused", HydrationError.Paused.message)
        assertEquals(0, env.syncEngine.downloadCount(), "no cloud download starts while paused")
        assertFalse(env.stateDb.isHydrated("/d/cold.txt"))
    }

    @Test
    fun `a refused open is announced as failed and completed with the paused token`() = runBlocking {
        val env = HydrationTestEnv()
        env.syncEngine.seedRemoteContent("/d/cold.txt", "hello")
        env.stateDb.insertUnhydratedEntry("/d/cold.txt", remoteSize = 5)
        env.pause.pause("user", auto = false)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch(Dispatchers.Unconfined) { env.hydration.events.collect { events.add(it) } }

        env.hydration.openForRead("conn1", "h1", "/d/cold.txt")

        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertFalse(completed.ok)
        assertEquals("paused", completed.error?.message)
        assertEquals(HydrationEvent.Completed.Direction.DOWNLOAD, completed.direction)
        collector.cancel()
    }

    @Test
    fun `a file already in the cache is still served while paused`() = runBlocking {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/d/warm.txt", localSize = 5)
        env.syncEngine.seedCacheContent("/d/warm.txt", "hello")
        env.pause.pause("metered", auto = true)

        val read = env.hydration.openForRead("conn1", "h1", "/d/warm.txt")

        assertIs<OpenResult.Ok>(read)
        assertEquals(0, env.syncEngine.downloadCount())
    }

    @Test
    fun `after resume a refused file is served`() = runBlocking {
        val env = HydrationTestEnv()
        env.syncEngine.seedRemoteContent("/d/cold.txt", "hello")
        env.stateDb.insertUnhydratedEntry("/d/cold.txt", remoteSize = 5)
        env.pause.pause("user", auto = false)
        assertEquals(OpenResult.Failed(HydrationError.Paused), env.hydration.openForRead("conn1", "h1", "/d/cold.txt"))

        env.pause.resume()

        assertIs<OpenResult.Ok>(env.hydration.openForRead("conn1", "h2", "/d/cold.txt"))
        assertEquals(1, env.syncEngine.downloadCount())
        assertTrue(env.stateDb.isHydrated("/d/cold.txt"))
    }

    @Test
    fun `a download in flight that needs more than ten seconds is aborted and its open is refused as paused`() = runBlocking {
        val env = HydrationTestEnv()
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 10)
        env.providerForTest.downloadStall = stall
        env.syncEngine.seedRemoteContent("/d/big.bin", "x")
        env.stateDb.insertUnhydratedEntry("/d/big.bin", remoteSize = 1_000_000_000L) // ten bytes of a gigabyte
        val open = async(Dispatchers.Default) { env.hydration.openForRead("conn1", "h1", "/d/big.bin") }
        withTimeout(10_000) { stall.reached.await() }

        env.pause.pause("metered", auto = true)
        assertEquals(1, env.pause.abortSlowTransfers())

        assertEquals(OpenResult.Failed(HydrationError.Paused), withTimeout(10_000) { open.await() })
        assertFalse(env.stateDb.isHydrated("/d/big.bin"))
        val cacheDir = env.syncEngine.resolveCachePath("/d/big.bin").parent
        val leftovers = Files.list(cacheDir).use { it.toList() }
        assertTrue(leftovers.isEmpty(), "the aborted download leaves no staged file: $leftovers")
        assertEquals(0, env.pause.inFlightCount())
    }

    @Test
    fun `a download in flight that needs under ten seconds is left to finish`() = runBlocking {
        val env = HydrationTestEnv()
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 990)
        env.providerForTest.downloadStall = stall
        val content = "y".repeat(1_000)
        env.syncEngine.seedRemoteContent("/d/near.bin", content)
        env.stateDb.insertUnhydratedEntry("/d/near.bin", remoteSize = 1_000)
        val open = async(Dispatchers.Default) { env.hydration.openForRead("conn1", "h1", "/d/near.bin") }
        withTimeout(10_000) { stall.reached.await() }

        env.pause.pause("metered", auto = true)
        assertEquals(0, env.pause.abortSlowTransfers())
        stall.gate.complete(Unit)

        assertIs<OpenResult.Ok>(withTimeout(10_000) { open.await() })
        assertTrue(env.stateDb.isHydrated("/d/near.bin"))
    }
}
