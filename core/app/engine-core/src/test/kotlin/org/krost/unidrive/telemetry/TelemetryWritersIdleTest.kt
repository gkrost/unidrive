package org.krost.unidrive.telemetry

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestAttempt
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two telemetry writers sleep on their queue until something is due. They polled every 500 ms, so a daemon with
 * nothing to do woke each of them twice a second for ever (a recording of an idle daemon showed both loops among its
 * few samples).
 */
class TelemetryWritersIdleTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val opened = mutableListOf<TransferHistory>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("telemetry-idle-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        runCatching {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
        }
    }

    private fun open(): TransferHistory = TransferHistory.open("idle", db, HistoryConfig()).also { opened += it }

    private fun attempt(at: Long) =
        RequestAttempt(
            startedAtMs = at,
            provider = "internxt",
            endpointClass = "GET /x",
            method = "GET",
            outcome = AttemptOutcome.OK,
            headersMs = 10,
            totalMs = 20,
            bytesUp = null,
            bytesDown = null,
            retryNo = 0,
            throttled = false,
        )

    @Test
    fun `idle writers do not wake to poll`() {
        val history = open()
        val metrics = history.requestMetrics()
        assertTrue(history.flush() && metrics.flush(), "both writers are up and idle")
        val jfr = dir.resolve("idle.jfr")
        Recording().use { recording ->
            recording.enable("jdk.ThreadPark")
            recording.start()
            Thread.sleep(2_200) // four polls of the old 500 ms each
            recording.stop()
            recording.dump(jfr)
        }

        val wakeups =
            RecordingFile
                .readAllEvents(jfr)
                .filter { it.eventType.name == "jdk.ThreadPark" }
                .map { it.thread?.javaName.orEmpty() }
                .filter { it == "telemetry-writer-idle" || it == "request-metrics-idle" }

        assertEquals(emptyList(), wakeups, "a park that ended is a wake-up; the writers' parks are still running")
    }

    @Test
    fun `minutes waiting for a roll-up are rolled up when the interval falls due, with an idle queue`() {
        val history = open()
        val metrics = history.requestMetrics(RequestMetricsConfig(rollupIntervalMs = 300))
        metrics.record(attempt(at = 1_700_000_000_000L))

        val deadline = System.nanoTime() + 10_000_000_000L
        while (rollupRows() == 0) {
            if (System.nanoTime() > deadline) fail("the writer slept past the roll-up interval")
            Thread.sleep(20)
        }
    }

    private fun rollupRows(): Int =
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM request_rollups").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
}
