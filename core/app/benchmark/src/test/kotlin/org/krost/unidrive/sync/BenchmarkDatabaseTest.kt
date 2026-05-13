package org.krost.unidrive.sync

import java.nio.file.Files
import kotlin.test.*

class BenchmarkDatabaseTest {

    private lateinit var db: BenchmarkDatabase

    @BeforeTest
    fun setUp() {
        val tmpDir = Files.createTempDirectory("unidrive-bench-test")
        db = BenchmarkDatabase(tmpDir.resolve("benchmarks.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun run(
        runId: String = "run-1",
        providerId: String = "onedrive-personal",
        providerType: String = "onedrive",
        finishedAt: String? = null,
        throttled: Boolean = false,
    ) = BenchmarkRun(
        runId = runId,
        startedAt = "2026-04-01T10:00:00Z",
        finishedAt = finishedAt,
        providerId = providerId,
        providerType = providerType,
        protocol = "https",
        endpoint = "graph.microsoft.com",
        ipStack = "dual",
        resolvedIp = "1.2.3.4",
        clientLocation = "DE",
        clientNetwork = "home",
        unidriveVersion = "1.0.0",
        iterations = 3,
        fileSizes = "1024,65536,1048576",
        throttled = throttled,
    )

    private fun point(runId: String = "run-1", iteration: Int = 1) = BenchmarkPoint(
        runId = runId,
        iteration = iteration,
        operation = "upload",
        fileSize = 65536L,
        durationMs = 250L,
        throughputBps = 262144L,
        status = "ok",
        errorCode = null,
        errorMessage = null,
        ipStack = "dual",
        timestamp = "2026-04-01T10:00:05Z",
    )

    @Test
    fun `insert and query run`() {
        val r = run()
        db.insertRun(r)
        val loaded = db.getRun("run-1")
        assertNotNull(loaded)
        assertEquals("run-1", loaded.runId)
        assertEquals("2026-04-01T10:00:00Z", loaded.startedAt)
        assertNull(loaded.finishedAt)
        assertEquals("onedrive-personal", loaded.providerId)
        assertEquals("onedrive", loaded.providerType)
        assertEquals("https", loaded.protocol)
        assertEquals("graph.microsoft.com", loaded.endpoint)
        assertEquals("dual", loaded.ipStack)
        assertEquals("1.2.3.4", loaded.resolvedIp)
        assertEquals("DE", loaded.clientLocation)
        assertEquals("home", loaded.clientNetwork)
        assertEquals("1.0.0", loaded.unidriveVersion)
        assertEquals(3, loaded.iterations)
        assertEquals("1024,65536,1048576", loaded.fileSizes)
        assertFalse(loaded.throttled)
    }

    @Test
    fun `get nonexistent run returns null`() {
        assertNull(db.getRun("nope"))
    }

    @Test
    fun `insert and query points`() {
        db.insertRun(run())
        db.insertPoint(point())
        val pts = db.getPoints("run-1")
        assertEquals(1, pts.size)
        val p = pts[0]
        assertEquals("run-1", p.runId)
        assertEquals(1, p.iteration)
        assertEquals("upload", p.operation)
        assertEquals(65536L, p.fileSize)
        assertEquals(250L, p.durationMs)
        assertEquals(262144L, p.throughputBps)
        assertEquals("ok", p.status)
        assertNull(p.errorCode)
        assertNull(p.errorMessage)
        assertEquals("dual", p.ipStack)
        assertEquals("2026-04-01T10:00:05Z", p.timestamp)
    }

    @Test
    fun `getPoints returns empty list for unknown run`() {
        assertEquals(emptyList(), db.getPoints("nope"))
    }

    @Test
    fun `latestRuns returns most recent per provider`() {
        // Three runs for the same provider — only the latest (run-3) should appear
        db.insertRun(run("run-1", finishedAt = "2026-04-01T10:00:00Z"))
        db.insertRun(run("run-2", finishedAt = "2026-04-01T11:00:00Z"))
        db.insertRun(run("run-3", finishedAt = "2026-04-01T12:00:00Z"))
        val latest = db.latestRuns()
        assertEquals(1, latest.size)
        assertEquals("run-3", latest[0].runId)
    }

    @Test
    fun `latestRuns filtered by providerId`() {
        db.insertRun(run("run-a", providerId = "onedrive-personal", finishedAt = "2026-04-01T10:00:00Z"))
        db.insertRun(run("run-b", providerId = "sftp-work", providerType = "sftp", finishedAt = "2026-04-01T11:00:00Z"))
        val result = db.latestRuns(providerId = "sftp-work")
        assertEquals(1, result.size)
        assertEquals("run-b", result[0].runId)
    }

    @Test
    fun `latestRuns excludes unfinished runs`() {
        db.insertRun(run("run-unfinished"))  // no finishedAt
        db.insertRun(run("run-done", finishedAt = "2026-04-01T10:00:00Z"))
        val latest = db.latestRuns()
        assertEquals(1, latest.size)
        assertEquals("run-done", latest[0].runId)
    }

    @Test
    fun `finishRun updates fields`() {
        db.insertRun(run())
        db.finishRun("run-1", finishedAt = "2026-04-01T10:30:00Z", throttled = true)
        val loaded = db.getRun("run-1")
        assertNotNull(loaded)
        assertEquals("2026-04-01T10:30:00Z", loaded.finishedAt)
        assertTrue(loaded.throttled)
    }

    @Test
    fun `allRuns returns all ordered by started_at desc`() {
        db.insertRun(run("run-1", finishedAt = "2026-04-01T10:00:00Z"))
        db.insertRun(run("run-2").copy(startedAt = "2026-04-02T10:00:00Z", finishedAt = "2026-04-02T10:30:00Z"))
        val all = db.allRuns()
        assertEquals(2, all.size)
        assertEquals("run-2", all[0].runId)
        assertEquals("run-1", all[1].runId)
    }

    @Test
    fun `null optional fields round-trip`() {
        val r = run().copy(endpoint = null, resolvedIp = null, clientLocation = null, clientNetwork = null)
        db.insertRun(r)
        val loaded = db.getRun("run-1")
        assertNotNull(loaded)
        assertNull(loaded.endpoint)
        assertNull(loaded.resolvedIp)
        assertNull(loaded.clientLocation)
        assertNull(loaded.clientNetwork)
    }

    @Test
    fun `point with error fields round-trip`() {
        db.insertRun(run())
        val errPoint = point().copy(
            status = "error",
            throughputBps = null,
            errorCode = 403,
            errorMessage = "Forbidden",
        )
        db.insertPoint(errPoint)
        val pts = db.getPoints("run-1")
        assertEquals(1, pts.size)
        assertEquals("error", pts[0].status)
        assertNull(pts[0].throughputBps)
        assertEquals(403, pts[0].errorCode)
        assertEquals("Forbidden", pts[0].errorMessage)
    }

    @Test
    fun `deleteRun removes run and its points`() {
        db.insertRun(run("run-1"))
        db.insertPoint(point("run-1", iteration = 1))
        db.insertPoint(point("run-1", iteration = 2))

        val deleted = db.deleteRun("run-1")

        assertTrue(deleted)
        assertNull(db.getRun("run-1"))
        assertEquals(emptyList(), db.getPoints("run-1"))
    }

    @Test
    fun `deleteRun returns false for nonexistent run`() {
        val deleted = db.deleteRun("no-such-run")
        assertFalse(deleted)
    }
}
