package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
}
