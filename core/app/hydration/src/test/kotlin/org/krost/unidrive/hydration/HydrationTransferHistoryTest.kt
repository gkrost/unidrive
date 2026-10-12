package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.telemetry.TransferHistory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #652: the mount's transfers are recorded as logical transfers in the profile's history. One row per upload
 * or download, written where the transfer ends (the places `completed` goes out): `queued_at` when the
 * operation is queued, `started_at` when it holds a transfer permit, `finished_at` at the end. A retry is
 * not a second row; the start-up replay and an identical-bytes skip are recorded like any other upload.
 *
 * The history is real (its own temp `telemetry.db`, read back over JDBC); the clock is a counter shared by
 * the wall and the monotonic reading, so the order of the three instants is exact.
 */
class HydrationTransferHistoryTest {
    private val dir: Path = Files.createTempDirectory("ud-hydration-history")
    private val dbPath: Path = dir.resolve("telemetry.db")
    private val ticks = AtomicLong(1_000)

    private fun tick(): Long = ticks.addAndGet(10)

    private val history: TransferHistory =
        TransferHistory.open("p1", dbPath, clock = ::tick, nanoClock = { tick() * 1_000_000L })

    @AfterTest
    fun tearDown() {
        history.close()
        runCatching { dir.toFile().deleteRecursively() }
    }

    private data class Row(
        val direction: String,
        val path: String,
        val queuedAt: Long,
        val startedAt: Long?,
        val finishedAt: Long?,
        val queueWaitMs: Long?,
        val transferMs: Long?,
        val bytesTotal: Long?,
        val bytesDone: Long?,
        val result: String,
        val errorClass: String?,
        val attempts: Int?,
        val newHash: String?,
    )

    // Everything the history was handed so far, as the file has it.
    private fun rows(): List<Row> {
        assertTrue(history.flush(), "the history answers")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM transfers ORDER BY queued_at, operation_id").use { rs ->
                    fun long(column: String): Long? = rs.getLong(column).takeUnless { rs.wasNull() }
                    return buildList {
                        while (rs.next()) {
                            add(
                                Row(
                                    direction = rs.getString("direction"),
                                    path = rs.getString("path"),
                                    queuedAt = rs.getLong("queued_at"),
                                    startedAt = long("started_at"),
                                    finishedAt = long("finished_at"),
                                    queueWaitMs = long("queue_wait_ms"),
                                    transferMs = long("transfer_ms"),
                                    bytesTotal = long("bytes_total"),
                                    bytesDone = long("bytes_done"),
                                    result = rs.getString("result"),
                                    errorClass = rs.getString("error_class"),
                                    attempts = long("attempts")?.toInt(),
                                    newHash = rs.getString("new_hash"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun writeCache(env: HydrationTestEnv, path: String, content: String) {
        val cache = env.syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    private suspend fun upload(env: HydrationTestEnv, path: String, content: String = "bytes", handle: String = "h1") {
        writeCache(env, path, content)
        assertIs<OpenResult.Ok>(env.hydration.openForWrite("conn1", handle, path, env.syncEngine.resolveCachePath(path)))
    }

    // ── one row per direction ───────────────────────────────────────────────────────────────────

    @Test
    fun `an upload is one row, queued then started then finished, with the bytes and the new hash`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/q/f.txt")

        upload(env, "/q/f.txt")
        advanceUntilIdle()

        val row = rows().single()
        assertEquals("upload", row.direction)
        assertEquals("/q/f.txt", row.path)
        assertEquals("success", row.result)
        assertEquals(1, row.attempts)
        val started = assertNotNull(row.startedAt, "a permit was acquired")
        val finished = assertNotNull(row.finishedAt)
        assertTrue(row.queuedAt < started && started < finished, "queued ${row.queuedAt} < started $started < finished $finished")
        assertTrue((row.queueWaitMs ?: -1) > 0, "the queue wait is measured: ${row.queueWaitMs}")
        assertTrue((row.transferMs ?: -1) > 0, "the transfer time is measured on its own: ${row.transferMs}")
        assertEquals(5L, row.bytesDone)
        assertNotNull(row.newHash, "the hash the cloud reports")
    }

    @Test
    fun `a cold read is one download row and a warm read is none`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.syncEngine.seedRemoteContent("/d/cold.txt", "hello")
        env.stateDb.insertUnhydratedEntry("/d/cold.txt", remoteSize = 5)
        env.stateDb.insertHydratedEntry("/d/warm.txt", localSize = 5)
        env.syncEngine.seedCacheContent("/d/warm.txt", "hello")

        assertIs<OpenResult.Ok>(env.hydration.openForRead("conn1", "h1", "/d/cold.txt"))
        assertIs<OpenResult.Ok>(env.hydration.openForRead("conn1", "h2", "/d/warm.txt"))

        val row = rows().single()
        assertEquals("download", row.direction)
        assertEquals("/d/cold.txt", row.path)
        assertEquals("success", row.result)
        assertEquals(1, row.attempts)
        assertEquals(5L, row.bytesTotal)
        assertEquals(5L, row.bytesDone)
        val started = assertNotNull(row.startedAt)
        val finished = assertNotNull(row.finishedAt)
        assertTrue(row.queuedAt < started && started < finished, "queued ${row.queuedAt} < started $started < finished $finished")
        assertTrue((row.transferMs ?: -1) > 0)
    }

    @Test
    fun `the hydrate verb records its download like an open does`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.syncEngine.seedRemoteContent("/d/cold.txt", "hello")
        env.stateDb.insertUnhydratedEntry("/d/cold.txt", remoteSize = 5)

        assertEquals(HydrateResult.Ok, env.hydration.hydrate("/d/cold.txt"))

        val row = rows().single()
        assertEquals("download", row.direction)
        assertEquals("success", row.result)
    }

    @Test
    fun `a download that fails is one failed row with the error class`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.syncEngine.seedRemoteContent("/d/cold.txt", "hello")
        env.stateDb.insertUnhydratedEntry("/d/cold.txt", remoteSize = 5)
        env.syncEngine.makeNextDownloadThrow(IllegalStateException("the cloud said no"))

        assertIs<OpenResult.Failed>(env.hydration.openForRead("conn1", "h1", "/d/cold.txt"))

        val row = rows().single()
        assertEquals("download", row.direction)
        assertEquals("failed", row.result)
        assertEquals("IllegalStateException", row.errorClass)
        assertEquals(1, row.attempts)
    }

    // ── retries are one logical transfer ────────────────────────────────────────────────────────

    @Test
    fun `a failed attempt followed by a success is one row with two attempts`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/q/f.txt")
        env.syncEngine.failUploads(1)

        upload(env, "/q/f.txt")
        advanceUntilIdle()

        assertEquals(2, env.syncEngine.uploadAttempts())
        val row = rows().single()
        assertEquals("success", row.result)
        assertEquals(2, row.attempts)
        assertEquals("IllegalStateException", row.errorClass, "the failed attempt that preceded the success stays on the row")
        val started = assertNotNull(row.startedAt)
        assertTrue(row.queuedAt < started && started < assertNotNull(row.finishedAt))
    }

    @Test
    fun `an upload that fails every attempt is one failed row counting them all`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/q/f.txt")
        env.syncEngine.failUploads(100)

        upload(env, "/q/f.txt")
        advanceUntilIdle()

        val row = rows().single()
        assertEquals("failed", row.result)
        assertEquals(3, row.attempts)
        assertEquals("IllegalStateException", row.errorClass)
        assertNotNull(row.finishedAt)
    }

    @Test
    fun `an upload the cloud refuses for a changed copy is a conflict row`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/q/f.txt")
        env.syncEngine.conflictUploads(1)

        upload(env, "/q/f.txt")
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "a conflict is not retried")
        val row = rows().single()
        assertEquals("conflict", row.result)
        assertEquals(1, row.attempts)
    }

    @Test
    fun `an upload the provider refuses is one failed row`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/q/f.txt")
        env.syncEngine.refuseUploads(1)

        upload(env, "/q/f.txt")
        advanceUntilIdle()

        val row = rows().single()
        assertEquals("failed", row.result)
        assertEquals(1, row.attempts)
        assertEquals("PermanentUploadFailureException", row.errorClass)
    }

    // ── the replay and the skip ─────────────────────────────────────────────────────────────────

    @Test
    fun `an upload the start-up replay runs is recorded like any other`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/q/replayed.txt")
        writeCache(env, "/q/replayed.txt", "bytes")

        assertEquals(1, env.hydration.replayPendingUploads())
        advanceUntilIdle()

        val row = rows().single()
        assertEquals("upload", row.direction)
        assertEquals("/q/replayed.txt", row.path)
        assertEquals("success", row.result)
        assertEquals(5L, row.bytesDone)
    }

    @Test
    fun `identical bytes the cloud already holds are a skipped row that made no attempt`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.syncEngine.seedRemoteContent("/q/same.txt", "x")
        env.stateDb.insertCloudInStepRow("/q/same.txt", "x")

        upload(env, "/q/same.txt", content = "x")
        advanceUntilIdle()

        assertEquals(0, env.syncEngine.uploadAttempts(), "nothing was sent")
        val row = rows().single()
        assertEquals("skipped", row.result)
        assertEquals(0, row.attempts)
        assertNull(row.bytesDone, "no bytes moved")
        val started = assertNotNull(row.startedAt)
        assertTrue(row.queuedAt < started && started < assertNotNull(row.finishedAt))
    }

    @Test
    fun `a write to an excluded path is not a transfer and leaves no row`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history, excludePatterns = listOf("*.tmp"))
        env.stateDb.insertCreatedRow("/q/scratch.tmp")

        writeCache(env, "/q/scratch.tmp", "x")
        val open = env.hydration.openForWrite("conn1", "h1", "/q/scratch.tmp", env.syncEngine.resolveCachePath("/q/scratch.tmp"))
        advanceUntilIdle()

        assertTrue((open as OpenResult.Ok).excluded)
        assertEquals(emptyList(), rows())
    }

    // ── a pause is neither a failure nor an interruption ────────────────────────────────────────

    @Test
    fun `an upload that waits for a pause leaves no row until it runs and the wait is queue time`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/q/f.txt")
        env.pause.pause("user", auto = false)

        upload(env, "/q/f.txt")
        advanceUntilIdle()
        assertEquals(emptyList(), rows(), "waiting for a pause writes nothing: no permit, no start, no end")

        env.pause.resume()
        advanceUntilIdle()

        val row = rows().single()
        assertEquals("success", row.result)
        assertEquals(1, row.attempts, "the wait was not an attempt")
        val started = assertNotNull(row.startedAt)
        assertTrue(started > row.queuedAt, "started only after the pause ended")
        assertTrue((row.queueWaitMs ?: -1) > 0, "the wait for the end of the pause is queue wait, not transfer time")
    }

    @Test
    fun `an upload aborted by a pause stays one running row and finishes as one success after the resume`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history, maxUploadAttempts = 1)
        val provider = env.providerForTest
        provider.progressBeforeGate = 1L to 1_000_000_000L // a byte of a gigabyte
        provider.uploadGate = CompletableDeferred()
        env.stateDb.insertCreatedRow("/q/big.bin")
        upload(env, "/q/big.bin", content = "payload")
        advanceUntilIdle()
        val whileRunning = rows().single()
        assertEquals("running", whileRunning.result)
        assertNotNull(whileRunning.startedAt)

        env.pause.pause("metered", auto = true)
        assertEquals(1, env.pause.abortSlowTransfers())
        advanceUntilIdle()
        val whilePaused = rows().single()
        assertEquals("running", whilePaused.result, "an abort for a pause is neither a failure nor an interruption")
        assertNull(whilePaused.errorClass)
        assertEquals(whileRunning.startedAt, whilePaused.startedAt)

        provider.uploadGate!!.complete(Unit)
        env.pause.resume()
        advanceUntilIdle()

        val row = rows().single()
        assertEquals("success", row.result)
        assertEquals(1, row.attempts, "the abort did not count as an attempt")
        assertNull(row.errorClass)
    }

    @Test
    fun `a cold download refused for a pause leaves no row`() = runBlocking<Unit> {
        val env = HydrationTestEnv(history = history)
        env.syncEngine.seedRemoteContent("/d/cold.txt", "hello")
        env.stateDb.insertUnhydratedEntry("/d/cold.txt", remoteSize = 5)
        env.pause.pause("user", auto = false)

        assertEquals(OpenResult.Failed(HydrationError.Paused), env.hydration.openForRead("conn1", "h1", "/d/cold.txt"))

        assertEquals(emptyList(), rows(), "it never started, and a refusal for a pause is not a failure")
    }

    @Test
    fun `a download aborted by a pause ends its row as interrupted by the pause`() = runBlocking<Unit> {
        val env = HydrationTestEnv(history = history)
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 10)
        env.providerForTest.downloadStall = stall
        env.syncEngine.seedRemoteContent("/d/big.bin", "x")
        env.stateDb.insertUnhydratedEntry("/d/big.bin", remoteSize = 1_000_000_000L)
        val open = async(Dispatchers.Default) { env.hydration.openForRead("conn1", "h1", "/d/big.bin") }
        withTimeout(10_000) { stall.reached.await() }

        env.pause.pause("metered", auto = true)
        assertEquals(1, env.pause.abortSlowTransfers())
        assertEquals(OpenResult.Failed(HydrationError.Paused), withTimeout(10_000) { open.await() })

        val row = rows().single()
        assertEquals("interrupted", row.result, "the download is over (the client reads again later): neither success nor failure")
        assertEquals("paused", row.errorClass)
        assertNotNull(row.finishedAt)
    }

    // ── cancelling, stopping ────────────────────────────────────────────────────────────────────

    @Test
    fun `a cancelled upload ends its row as interrupted`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.providerForTest.uploadGate = CompletableDeferred()
        env.stateDb.insertCreatedRow("/q/f.txt")
        upload(env, "/q/f.txt")
        advanceUntilIdle()
        assertEquals("running", rows().single().result)

        assertTrue(env.hydration.cancelUpload("/q/f.txt"))
        advanceUntilIdle()

        val row = rows().single()
        assertEquals("interrupted", row.result)
        assertEquals("cancelled", row.errorClass)
        assertNotNull(row.finishedAt)
    }

    @Test
    fun `stopping the uploads ends the transfers in flight as interrupted`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val env = HydrationTestEnv(recoveryUploadScope = scope, history = history)
        env.providerForTest.uploadGate = CompletableDeferred()
        env.stateDb.insertCreatedRow("/q/f.txt")
        upload(env, "/q/f.txt")
        withTimeout(10_000) { while (env.hydration.uploadHealth().inFlight == 0) kotlinx.coroutines.delay(5) }

        withTimeout(10_000) { env.hydration.shutdownUploads() }

        assertEquals("interrupted", rows().single().result)
    }

    // ── the history is optional and never in the way ────────────────────────────────────────────

    @Test
    fun `a closed history does not fail or slow the transfer`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/q/f.txt")
        history.close()

        upload(env, "/q/f.txt")
        advanceUntilIdle()

        assertEquals("bytes", env.syncEngine.remoteContentSeen("/q/f.txt"), "the upload landed")
    }

    @Test
    fun `without a history nothing is recorded and everything works`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertCreatedRow("/q/f.txt")

        upload(env, "/q/f.txt")
        advanceUntilIdle()

        assertEquals("bytes", env.syncEngine.remoteContentSeen("/q/f.txt"))
        assertEquals(emptyList(), rows())
    }
}
