package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
 * The daemon-owned upload queue behind open_write: bounded concurrency shared
 * with the sync engine's per-provider transfer budget, retry with backoff for
 * transient failures, a bounded waiting depth with back-pressure towards the
 * submitting client, and a startup replay of rows whose upload never landed.
 */
class HydrationUploadQueueTest {
    /** Creates the row's cache file with [content] at the path the engine resolves. */
    private fun writeCache(env: HydrationTestEnv, path: String, content: String) {
        val cache = env.syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    @Test
    fun `transient upload failure is retried and succeeds without a client call`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 3,
            uploadRetryDelaysMs = listOf(1L, 1L),
        )
        env.stateDb.insertCreatedRow("/docs/f.txt")
        val cache = env.syncEngine.resolveCachePath("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "retry me")
        env.syncEngine.failUploads(1)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        val r = env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", cache)
        assertIs<OpenResult.Ok>(r)
        advanceUntilIdle()

        assertEquals("retry me", env.syncEngine.remoteContentSeen("/docs/f.txt"))
        assertEquals(2, env.syncEngine.uploadAttempts(), "one failure then one successful retry")
        val failed = events.filterIsInstance<HydrationEvent.Failed>()
        assertEquals(1, failed.size)
        assertEquals(true, failed.single().retryScheduled, "the failed attempt must announce a retry")
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertTrue(completed.ok)
        assertEquals("h1", completed.handleId)
        collector.cancel()
    }

    @Test
    fun `permanent upload failure stops after max attempts and stays visible`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 3,
            uploadRetryDelaysMs = listOf(1L, 1L),
        )
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "doomed")
        env.syncEngine.failUploads(999)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle()

        assertEquals(3, env.syncEngine.uploadAttempts(), "attempts must be bounded")
        val failed = events.filterIsInstance<HydrationEvent.Failed>()
        assertEquals(listOf(true, true, false), failed.map { it.retryScheduled })
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertFalse(completed.ok)
        assertEquals("injected upload failure", completed.error?.message)
        assertTrue(env.stateDb.lastErrorAt("/docs/f.txt") != null, "the failed row stays visible to doctor")
        collector.cancel()
    }

    @Test
    fun `a remote conflict is reported as conflict and is not retried`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 3,
            uploadRetryDelaysMs = listOf(1L, 1L),
        )
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "my edit")
        env.syncEngine.conflictUploads(999)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "a conflict is deterministic: retrying cannot succeed")
        assertEquals(listOf(false), events.filterIsInstance<HydrationEvent.Failed>().map { it.retryScheduled })
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertFalse(completed.ok)
        assertEquals(HydrationError.CONFLICT_TOKEN, completed.error?.message)
        assertTrue(Files.exists(env.syncEngine.resolveCachePath("/docs/f.txt")), "the cache copy is the only copy of the edit")
        collector.cancel()
    }

    // #493: an upload the provider refuses as such (Internxt's 400 for an empty file, #485) used to run the whole retry
    // ladder, holding a transfer slot through every backoff, before failing the same way.
    @Test
    fun `an upload the provider refuses is not retried`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 3,
            uploadRetryDelaysMs = listOf(1L, 1L),
        )
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "refused")
        env.syncEngine.refuseUploads(999)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "a refusal is deterministic: retrying cannot succeed")
        assertEquals(listOf(false), events.filterIsInstance<HydrationEvent.Failed>().map { it.retryScheduled })
        assertFalse(events.filterIsInstance<HydrationEvent.Completed>().single().ok)
        assertTrue(env.stateDb.lastErrorAt("/docs/f.txt") != null, "the refused row stays visible as failed")
        collector.cancel()
    }

    // #493: the refusal is persisted with the content's stamp: the replay at the next daemon start and a resubmission of
    // the same bytes do not ask the provider again; new content does.
    @Test
    fun `a refused upload is not replayed at the next start, nor resubmitted unchanged, but new content goes`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 3,
            uploadRetryDelaysMs = listOf(1L, 1L),
            failedReplayDelayMs = 60_000L,
        )
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "refused")
        env.syncEngine.refuseUploads(1)
        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts())

        assertEquals(0, env.hydration.replayPendingUploads(), "the refused row is not replayed at a start")
        env.hydration.openForWrite("conn1", "h2", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts(), "unchanged content is not sent again")

        // New bytes are not what was refused. The row still carries its failure mark, so since #499 it is replayed like
        // any failed row: after the delay, not at once.
        writeCache(env, "/docs/f.txt", "different bytes now")
        assertEquals(0, env.hydration.replayPendingUploads(), "new content is replayed, after the delay of failed rows")
        advanceTimeBy(60_000L)
        advanceUntilIdle()
        assertEquals(2, env.syncEngine.uploadAttempts())
        assertEquals("different bytes now", env.syncEngine.remoteContentSeen("/docs/f.txt"))
    }

    @Test
    fun `an upload whose row vanished while queued is not retried`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 3,
            uploadRetryDelaysMs = listOf(1L, 1L),
        )
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "orphaned edit")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        env.stateDb.deleteRow("/docs/f.txt") // renamed away / unlinked before the worker ran
        advanceUntilIdle()

        assertEquals(listOf(false), events.filterIsInstance<HydrationEvent.Failed>().map { it.retryScheduled })
        assertFalse(events.filterIsInstance<HydrationEvent.Completed>().single().ok)
        assertEquals(0, env.syncEngine.uploadAttempts(), "the engine refuses before reaching the provider")
        assertFalse(env.hydration.hasUploadSlot("/docs/f.txt"))
        collector.cancel()
    }

    @Test
    fun `queued event precedes hydrating for every submitted upload`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertCreatedRow("/docs/f.txt")
        writeCache(env, "/docs/f.txt", "bytes")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/f.txt", env.syncEngine.resolveCachePath("/docs/f.txt"))
        advanceUntilIdle()

        val queuedIdx = events.indexOfFirst { it is HydrationEvent.Queued }
        val hydratingIdx = events.indexOfFirst { it is HydrationEvent.Hydrating }
        assertEquals(0, queuedIdx, "the first event of an upload submission is queued")
        assertTrue(hydratingIdx > queuedIdx, "queued must precede hydrating")
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertTrue(completed.ok)
        collector.cancel()
    }

    @Test
    fun `queued uploads never exceed the per-provider transfer cap`() = runBlocking {
        // Real concurrency: uploads park on a gate inside provider.upload, so
        // the number of in-flight calls equals the number of held permits.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val env = HydrationTestEnv(recoveryUploadScope = scope)
        val gate = CompletableDeferred<Unit>()
        env.providerForTest.uploadGate = gate

        val paths = (1..77).map { "/q/f$it.txt" }
        for (p in paths) {
            env.stateDb.insertCreatedRow(p)
            writeCache(env, p, "bytes-$p")
        }
        for (p in paths) {
            scope.launch {
                val r = env.hydration.openForWrite("conn-q", "h-$p", p, env.syncEngine.resolveCachePath(p))
                assertIs<OpenResult.Ok>(r)
            }
        }

        // Saturate: with the default cap of 4 (unknown provider id), exactly 4
        // uploads may be in flight no matter how deep the queue behind them.
        val deadline = System.currentTimeMillis() + 10_000
        while (env.providerForTest.maxConcurrentUploadsTotal() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(
            env.providerForTest.maxConcurrentUploadsTotal() <= 4,
            "uploads must never exceed the default per-provider cap of 4 (saw ${env.providerForTest.maxConcurrentUploadsTotal()})",
        )
        assertEquals(4, env.providerForTest.maxConcurrentUploadsTotal(), "the queue must saturate to the cap")

        gate.complete(Unit)
        val drainDeadline = System.currentTimeMillis() + 10_000
        while (env.providerForTest.completedUploads() < paths.size && System.currentTimeMillis() < drainDeadline) {
            Thread.sleep(10)
        }
        assertEquals(paths.size, env.providerForTest.completedUploads(), "the queue must drain completely")
        scope.cancel()
    }

    @Test
    fun `open_write blocks when the waiting queue is full and resumes as it drains`() = runTest {
        // Same-path burst against depth 1: the first upload holds the permit
        // parked at the gate, the second holds the only waiting slot waiting
        // for its per-path turn, so the third submission must block until a
        // waiting slot frees up.
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            uploadQueueDepth = 1,
            maxUploadAttempts = 1,
            uploadRetryDelaysMs = emptyList(),
        )
        val gate = CompletableDeferred<Unit>()
        env.providerForTest.uploadGate = gate

        env.stateDb.insertCreatedRow("/q/f.txt")
        writeCache(env, "/q/f.txt", "bytes")

        for (i in 1..2) {
            val r = env.hydration.openForWrite("conn1", "h-$i", "/q/f.txt", env.syncEngine.resolveCachePath("/q/f.txt"))
            assertIs<OpenResult.Ok>(r)
        }
        val thirdReturned = CompletableDeferred<Unit>()
        launch {
            env.hydration.openForWrite("conn1", "h-3", "/q/f.txt", env.syncEngine.resolveCachePath("/q/f.txt"))
            thirdReturned.complete(Unit)
        }
        runCurrent()
        assertFalse(thirdReturned.isCompleted, "the third submission must block on the bounded queue")

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(thirdReturned.isCompleted, "the blocked submission must resume as the queue drains")
        assertEquals(3, env.syncEngine.uploadAttempts(), "all three submissions upload in per-path FIFO order")
        assertEquals("bytes", env.syncEngine.remoteContentSeen("/q/f.txt"))
    }

    // #613: an upload whose provider call suspended with no progress held its path's
    // slot for the daemon's lifetime — no provider request, no failure, no completed
    // — and every later hand-over of the path queued behind it. The stall watchdog
    // bounds each attempt: the hung upload runs the failed-attempt path to a failed
    // completed, uploads of other paths keep flowing, and a later hand-over of the
    // same path runs once the provider answers again.
    @Test
    fun `a stalled upload is failed by the watchdog while other paths flow and the path is reusable after`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 2,
            uploadRetryDelaysMs = listOf(1L),
            uploadStallTimeoutMs = 5_000L,
        )
        env.stateDb.insertCreatedRow("/q/hung.txt")
        writeCache(env, "/q/hung.txt", "stuck bytes")
        env.stateDb.insertCreatedRow("/q/flows.txt")
        writeCache(env, "/q/flows.txt", "flowing bytes")
        env.providerForTest.stallUploadsOf("/q/hung.txt")
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        assertIs<OpenResult.Ok>(
            env.hydration.openForWrite("conn1", "h-hung", "/q/hung.txt", env.syncEngine.resolveCachePath("/q/hung.txt")),
        )
        assertIs<OpenResult.Ok>(
            env.hydration.openForWrite("conn1", "h-flow", "/q/flows.txt", env.syncEngine.resolveCachePath("/q/flows.txt")),
        )
        advanceUntilIdle()

        assertEquals("flowing bytes", env.syncEngine.remoteContentSeen("/q/flows.txt"), "uploads of other paths keep flowing")
        assertEquals(3, env.syncEngine.uploadAttempts(), "the hung path made 2 attempts, the flowing path 1")
        val failed = events.filterIsInstance<HydrationEvent.Failed>().filter { it.path == "/q/hung.txt" }
        assertEquals(listOf(true, false), failed.map { it.retryScheduled }, "the first stall schedules a retry, the second ends the ladder")
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h-hung" }
        assertFalse(completed.ok)
        assertTrue(
            completed.error?.message?.contains("stall watchdog") == true,
            "the completed carries the stall, got: ${completed.error?.message}",
        )
        assertTrue(env.stateDb.lastErrorAt("/q/hung.txt") != null, "the stalled row is stamped like any failed attempt")
        assertFalse(env.hydration.hasUploadSlot("/q/hung.txt"), "the path's slot is freed")

        // A later hand-over of the same path runs once the provider answers again.
        env.providerForTest.releaseStalledUpload("/q/hung.txt")
        assertIs<OpenResult.Ok>(
            env.hydration.openForWrite("conn1", "h-hung-2", "/q/hung.txt", env.syncEngine.resolveCachePath("/q/hung.txt")),
        )
        advanceUntilIdle()
        assertEquals("stuck bytes", env.syncEngine.remoteContentSeen("/q/hung.txt"))
        collector.cancel()
    }

    // #613: the watchdog keys on silent time — an upload whose provider still
    // reports byte progress is never cancelled, however long the transfer runs.
    @Test
    fun `a slow upload that keeps reporting progress is not cancelled by the watchdog`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            maxUploadAttempts = 2,
            uploadRetryDelaysMs = listOf(1L),
            uploadStallTimeoutMs = 5_000L,
        )
        env.stateDb.insertCreatedRow("/q/slow.txt")
        writeCache(env, "/q/slow.txt", "slow bytes")
        env.providerForTest.stallUploadsOf("/q/slow.txt", tickProgress = true)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        assertIs<OpenResult.Ok>(
            env.hydration.openForWrite("conn1", "h-slow", "/q/slow.txt", env.syncEngine.resolveCachePath("/q/slow.txt")),
        )
        advanceTimeBy(12_000L)
        assertEquals(
            0,
            events.filterIsInstance<HydrationEvent.Failed>().size,
            "no stall fired while the provider kept reporting bytes",
        )
        env.providerForTest.releaseStalledUpload("/q/slow.txt")
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "one attempt carried the whole transfer")
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertTrue(completed.ok)
        assertEquals("slow bytes", env.syncEngine.remoteContentSeen("/q/slow.txt"))
        collector.cancel()
    }

    @Test
    fun `full write sequence - create, write, open_write, completed, list settles`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        assertIs<MkdirResult.Ok>(env.hydration.mkdir("/docs"))
        val created = env.hydration.create("conn1", "h1", "/docs/hello.txt")
        assertIs<CreateResult.Ok>(created)
        Files.writeString(created.cachePath, "hello cloud")
        val opened = env.hydration.openForWrite("conn1", "h1", "/docs/hello.txt", created.cachePath)
        assertIs<OpenResult.Ok>(opened)
        advanceUntilIdle()

        assertEquals("hello cloud", env.syncEngine.remoteContentSeen("/docs/hello.txt"))
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single()
        assertTrue(completed.ok)
        assertEquals("h1", completed.handleId)
        val entry = (env.hydration.list("/docs") as ListResult.Ok).entries.single()
        assertFalse(entry.pendingUpload, "after completed the row is settled")
        assertEquals("uploaded-/docs/hello.txt", entry.remoteId, "the row carries the cloud id")
        collector.cancel()
    }

    @Test
    fun `safe-save sequence - temp upload then replace-rename lands the new version`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        assertIs<MkdirResult.Ok>(env.hydration.mkdir("/docs"))
        // Seed the target through the same write path.
        val seeded = env.hydration.create("conn1", "h-old", "/docs/target.txt")
        assertIs<CreateResult.Ok>(seeded)
        Files.writeString(seeded.cachePath, "old version")
        env.hydration.openForWrite("conn1", "h-old", "/docs/target.txt", seeded.cachePath)
        advanceUntilIdle()

        // Safe-save: temp file, write, upload, then rename over the target.
        val tmp = env.hydration.create("conn1", "h-tmp", "/docs/.save-tmp")
        assertIs<CreateResult.Ok>(tmp)
        Files.writeString(tmp.cachePath, "new version")
        env.hydration.openForWrite("conn1", "h-tmp", "/docs/.save-tmp", tmp.cachePath)
        advanceUntilIdle()
        events.awaitCompletedOk("h-tmp")

        val beforeRename = (env.hydration.list("/docs") as ListResult.Ok).entries.associateBy { it.path }
        assertEquals(
            "uploaded-/docs/.save-tmp",
            beforeRename.getValue("/docs/.save-tmp").remoteId,
            "the temp row must be uploaded before the replace-rename",
        )
        assertEquals("new version", env.syncEngine.remoteContent("/docs/.save-tmp"), "pre-rename remote state")
        assertIs<RenameResult.Ok>(env.hydration.rename("/docs/.save-tmp", "/docs/target.txt", replace = true))
        advanceUntilIdle()

        assertEquals(listOf("/docs/target.txt"), env.syncEngine.deletedPaths(), "the replace destination is deleted")
        assertEquals(listOf("/docs/.save-tmp" to "/docs/target.txt"), env.syncEngine.movedPairs(), "the source is moved")
        assertEquals("new version", env.syncEngine.remoteContent("/docs/target.txt"))
        collector.cancel()
    }

    private suspend fun MutableList<HydrationEvent>.awaitCompletedOk(handleId: String) {
        // The Completed event is emitted after the slot releases; a short real
        // wait inside virtual time is enough because the upload already ran.
        kotlinx.coroutines.yield()
        kotlin.test.assertNotNull(
            filterIsInstance<HydrationEvent.Completed>().singleOrNull { it.handleId == handleId && it.ok },
            "upload for $handleId must have completed",
        )
    }

    @Test
    fun `replay enqueues pending local rows once, skipping excluded and out-of-scope ones`() = runTest {
        val env = HydrationTestEnv(
            recoveryUploadScope = this,
            syncPaths = listOf("/_INBOX"),
            excludePatterns = listOf("*.tmp"),
        )
        for (p in listOf("/_INBOX/a.txt", "/_INBOX/b.txt", "/_INBOX/scratch.tmp", "/outside/x.txt")) {
            env.stateDb.insertCreatedRow(p)
            writeCache(env, p, "bytes-$p")
        }
        // Row whose cache copy is gone: nothing to upload from.
        env.stateDb.insertCreatedRow("/_INBOX/gone.txt")

        val enqueued = env.hydration.replayPendingUploads()
        assertEquals(2, enqueued, "only the two in-scope non-excluded rows with a cache copy replay")
        advanceUntilIdle()

        assertEquals("bytes-/_INBOX/a.txt", env.syncEngine.remoteContentSeen("/_INBOX/a.txt"))
        assertEquals("bytes-/_INBOX/b.txt", env.syncEngine.remoteContentSeen("/_INBOX/b.txt"))
        assertNull(env.syncEngine.remoteContentSeen("/_INBOX/scratch.tmp"))
        assertNull(env.syncEngine.remoteContentSeen("/outside/x.txt"))
        assertNull(env.syncEngine.remoteContentSeen("/_INBOX/gone.txt"))
    }

    // #493: two uploads that kept failing were replayed at every start and held both transfer slots for their whole
    // retry ladder; a row whose last attempt failed now waits, and fresh work goes first.
    @Test
    fun `a row whose last upload failed is replayed later, after the rows that can go now`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, failedReplayDelayMs = 60_000L)
        for (p in listOf("/q/fresh.txt", "/q/failed-before.txt")) {
            env.stateDb.insertCreatedRow(p)
            writeCache(env, p, "bytes-$p")
        }
        env.stateDb.markUploadFailed("/q/failed-before.txt", java.time.Instant.now())

        assertEquals(1, env.hydration.replayPendingUploads(), "only the row without a failure replays at once")
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("bytes-/q/fresh.txt", env.syncEngine.remoteContentSeen("/q/fresh.txt"))
        assertNull(env.syncEngine.remoteContentSeen("/q/failed-before.txt"), "the failed row waits for its delay")

        advanceUntilIdle()
        assertEquals("bytes-/q/failed-before.txt", env.syncEngine.remoteContentSeen("/q/failed-before.txt"), "then it is replayed")
    }

    @Test
    fun `a deferred replay is dropped when the row was uploaded meanwhile`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, failedReplayDelayMs = 60_000L)
        env.stateDb.insertCreatedRow("/q/f.txt")
        writeCache(env, "/q/f.txt", "bytes")
        env.stateDb.markUploadFailed("/q/f.txt", java.time.Instant.now())

        assertEquals(0, env.hydration.replayPendingUploads())
        // The client writes the file again before the deferred replay's turn: that upload lands.
        env.hydration.openForWrite("conn1", "h1", "/q/f.txt", env.syncEngine.resolveCachePath("/q/f.txt"))
        runCurrent()
        assertEquals(1, env.syncEngine.uploadAttempts())

        advanceUntilIdle()
        assertEquals(1, env.syncEngine.uploadAttempts(), "the deferred replay must not upload a row that has landed")
    }

    @Test
    fun `with no delay a failed row replays at once, as before`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, failedReplayDelayMs = 0L)
        env.stateDb.insertCreatedRow("/q/f.txt")
        writeCache(env, "/q/f.txt", "bytes")
        env.stateDb.markUploadFailed("/q/f.txt", java.time.Instant.now())

        assertEquals(1, env.hydration.replayPendingUploads())
        advanceUntilIdle()
        assertEquals("bytes", env.syncEngine.remoteContentSeen("/q/f.txt"))
    }
}
