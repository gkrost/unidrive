package org.krost.unidrive.hydration

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #658: the engine's own account of "is everything uploaded?" and "how big is the cache?",
 * the numbers `daemon.status` carries as `uploads` and `cache`.
 *
 * Pending = the rows whose bytes never reached the cloud (`local:` rows) plus every path that holds
 * an upload slot (a dirty overwrite of an uploaded row has no such row), minus keep-local and
 * out-of-scope paths, which no upload will ever take.
 */
class HydrationUploadHealthTest {
    private val since = Instant.parse("2026-01-01T00:00:00Z")

    private fun writeCache(env: HydrationTestEnv, path: String, content: String) {
        val cache = env.syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    @Test
    fun `nothing pending reads as zero with no age`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)

        assertEquals(HydrationImpl.UploadHealth(0, 0, 0, null), env.hydration.uploadHealth())
    }

    @Test
    fun `a stalled upload is pending and in flight and its age keeps growing`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertCreatedRow("/q/stuck.txt", lastSynced = since)
        writeCache(env, "/q/stuck.txt", "bytes")
        env.providerForTest.stallUploadsOf("/q/stuck.txt")

        assertIs<OpenResult.Ok>(
            env.hydration.openForWrite("conn1", "h1", "/q/stuck.txt", env.syncEngine.resolveCachePath("/q/stuck.txt")),
        )
        runCurrent()

        val first = env.hydration.uploadHealth(nowMs = since.toEpochMilli() + 10_000)
        val later = env.hydration.uploadHealth(nowMs = since.toEpochMilli() + 70_000)
        assertEquals(1, first.pending)
        assertEquals(1, first.inFlight, "the upload holds a transfer permit")
        assertEquals(0, first.failed)
        assertEquals(10_000L, first.oldestPendingAgeMs)
        assertEquals(70_000L, later.oldestPendingAgeMs, "nothing moved: the age only grows")

        env.providerForTest.releaseStalledUpload("/q/stuck.txt")
        advanceUntilIdle()
        assertEquals(HydrationImpl.UploadHealth(0, 0, 0, null), env.hydration.uploadHealth(), "landed: nothing pending, so no age")
    }

    @Test
    fun `the start-up replay's own uploads are counted`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertCreatedRow("/q/replayed.txt", lastSynced = since)
        writeCache(env, "/q/replayed.txt", "bytes")
        env.providerForTest.stallUploadsOf("/q/replayed.txt")

        assertEquals(1, env.hydration.replayPendingUploads())
        runCurrent()

        val health = env.hydration.uploadHealth(nowMs = since.toEpochMilli() + 5_000)
        assertEquals(1, health.pending)
        assertEquals(1, health.inFlight)
        assertEquals(5_000L, health.oldestPendingAgeMs)
        env.providerForTest.releaseStalledUpload("/q/replayed.txt")
        advanceUntilIdle()
    }

    @Test
    fun `a dirty overwrite has no local row but its upload is pending and ages from its submission`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUploadedRow("/q/f.txt", mtime = 1_000L, size = 3L, remoteHash = "remote-1")
        writeCache(env, "/q/f.txt", "xyz")
        env.providerForTest.stallUploadsOf("/q/f.txt")

        assertEquals(1, env.hydration.replayPendingUploads(), "the drifted cache copy is replayed")
        runCurrent()

        val now = System.currentTimeMillis()
        val health = env.hydration.uploadHealth(nowMs = now + 5_000)
        assertEquals(1, health.pending)
        assertEquals(1, health.inFlight)
        val age = assertNotNull(health.oldestPendingAgeMs)
        assertTrue(age in 5_000L..120_000L, "the slot's age: $age")
        env.providerForTest.releaseStalledUpload("/q/f.txt")
        advanceUntilIdle()
    }

    @Test
    fun `a keep-local row is not counted`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, excludePatterns = listOf("*.tmp"))
        // desktop.ini-style rows: a `local:` row that no upload will ever take.
        env.stateDb.insertCreatedRow("/q/scratch.tmp", lastSynced = since)
        env.stateDb.insertCreatedRow("/q/real.txt", lastSynced = since.plusSeconds(60))

        val health = env.hydration.uploadHealth(nowMs = since.toEpochMilli() + 120_000)

        assertEquals(1, health.pending, "only the real file waits for an upload")
        assertEquals(60_000L, health.oldestPendingAgeMs, "the excluded row's older age does not leak in")
    }

    @Test
    fun `a row outside the sync scope is not counted`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, syncPaths = listOf("/in"))
        env.stateDb.insertCreatedRow("/out/f.txt", lastSynced = since)

        assertEquals(HydrationImpl.UploadHealth(0, 0, 0, null), env.hydration.uploadHealth())
    }

    @Test
    fun `a row whose upload failed is pending and failed, with no upload in flight`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, failedReplayDelayMs = 3_600_000L)
        env.stateDb.insertCreatedRow("/q/failed.txt", lastSynced = since)
        env.stateDb.insertCreatedRow("/q/fresh.txt", lastSynced = since)
        env.stateDb.markUploadFailed("/q/failed.txt", since.plusSeconds(1))

        val health = env.hydration.uploadHealth(nowMs = since.toEpochMilli() + 1_000)

        assertEquals(HydrationImpl.UploadHealth(pending = 2, inFlight = 0, failed = 1, oldestPendingAgeMs = 1_000L), health)
    }

    // ── the reading behind daemon.status ────────────────────────────────────────────────────────

    @Test
    fun `the status reading reports what the rows say, and null until there is a reading`() = runBlocking {
        val env = HydrationTestEnv()
        env.stateDb.insertCreatedRow("/q/a.txt", lastSynced = since)

        val health = assertNotNull(env.hydration.uploadHealthSnapshot(nowMs = since.toEpochMilli() + 4_000))

        assertEquals(HydrationImpl.UploadHealth(pending = 1, inFlight = 0, failed = 0, oldestPendingAgeMs = 4_000L), health)
    }

    @Test
    fun `a state database held by a long save does not stall the status reading`() = runBlocking {
        val env = HydrationTestEnv()
        env.stateDb.insertCreatedRow("/q/a.txt", lastSynced = since)
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            env.stateDb.holdingDatabase {
                held.countDown()
                release.await()
            }
        }
        holder.start()
        try {
            assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Never read: unknown, and the wait is bounded.
            val started = System.nanoTime()
            assertNull(env.hydration.uploadHealthSnapshot(waitMs = 200), "nothing was read yet: unknown, not zero")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "the wait is bounded")
        } finally {
            release.countDown()
            holder.join()
        }

        // A reading now, then the database is held again and the reading is older than the re-read interval.
        assertEquals(1, assertNotNull(env.hydration.uploadHealthSnapshot(waitMs = 5_000)).pending)
        Thread.sleep(HydrationImpl.UPLOAD_SNAPSHOT_TTL_MS + 100)
        val held2 = java.util.concurrent.CountDownLatch(1)
        val release2 = java.util.concurrent.CountDownLatch(1)
        val holder2 = Thread {
            env.stateDb.holdingDatabase {
                held2.countDown()
                release2.await()
            }
        }
        holder2.start()
        try {
            assertTrue(held2.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val started = System.nanoTime()
            val served = assertNotNull(env.hydration.uploadHealthSnapshot(nowMs = since.toEpochMilli() + 90_000), "the previous reading is served")
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000, "a re-read waits only briefly for a busy database")
            assertEquals(1, served.pending)
            assertEquals(90_000L, served.oldestPendingAgeMs, "its age is carried forward, not frozen")
        } finally {
            release2.countDown()
            holder2.join()
        }
    }

    // ── the cache ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the cache size is unknown until it is measured, then follows the files`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, cacheMaxBytes = 1_000_000L)
        assertEquals(HydrationImpl.CacheHealth(bytes = null, budgetBytes = 1_000_000L), env.hydration.cacheHealth(), "never measured: unknown, not 0")

        writeCache(env, "/c/a.bin", "x".repeat(300))
        env.hydration.evictCache()
        assertEquals(300L, env.hydration.cacheHealth().bytes, "the eviction pass walks the cache and records what it found")

        writeCache(env, "/c/b.bin", "y".repeat(200))
        env.hydration.sweepCache()
        assertEquals(500L, env.hydration.cacheHealth().bytes)
    }

    @Test
    fun `a cache over its budget reports what is there, not the budget`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, cacheMaxBytes = 100L)
        // Unfinished creates are never evicted, so the cache can stay over budget (#450).
        env.stateDb.insertCreatedRow("/c/big.bin")
        writeCache(env, "/c/big.bin", "z".repeat(400))

        env.hydration.evictCache()

        assertEquals(HydrationImpl.CacheHealth(bytes = 400L, budgetBytes = 100L), env.hydration.cacheHealth())
    }

    @Test
    fun `no budget is reported as no budget`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, cacheMaxBytes = 0L)
        writeCache(env, "/c/a.bin", "x".repeat(10))

        env.hydration.evictCache()

        assertEquals(HydrationImpl.CacheHealth(bytes = 10L, budgetBytes = null), env.hydration.cacheHealth())
    }

    @Test
    fun `a measurement request fills in the size off the caller's thread`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, cacheMaxBytes = 1_000_000L)
        writeCache(env, "/c/a.bin", "x".repeat(42))
        assertNull(env.hydration.cacheHealth().bytes)

        env.hydration.requestCacheMeasurement()
        env.hydration.requestCacheMeasurement() // single flight: a second request while one runs is a no-op

        // The walk runs on the IO dispatcher, so wait in real time, not in the test scheduler's.
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(10_000) {
                var seen = env.hydration.cacheHealth().bytes
                while (seen == null) {
                    kotlinx.coroutines.delay(10)
                    seen = env.hydration.cacheHealth().bytes
                }
                seen
            }
        }
        assertEquals(42L, bytes)
    }
}
