package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

data class BenchmarkRun(
    val runId: String, val startedAt: String, val finishedAt: String?,
    val providerId: String, val providerType: String, val protocol: String,
    val endpoint: String?, val ipStack: String, val resolvedIp: String?,
    val clientLocation: String?, val clientNetwork: String?,
    val unidriveVersion: String, val iterations: Int, val fileSizes: String,
    val throttled: Boolean,
)

data class BenchmarkPoint(
    val runId: String, val iteration: Int, val operation: String,
    val fileSize: Long, val durationMs: Long, val throughputBps: Long?,
    val status: String, val errorCode: Int?, val errorMessage: String?,
    val ipStack: String, val timestamp: String,
)

class BenchmarkDatabase(private val dbPath: Path) : AutoCloseable {

    private lateinit var conn: Connection

    @Synchronized
    fun initialize() {
        Files.createDirectories(dbPath.parent)
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        createTables()
    }

    @Synchronized
    override fun close() {
        if (::conn.isInitialized && !conn.isClosed) conn.close()
    }

    @Synchronized
    private fun createTables() {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS benchmark_runs (
                    run_id           TEXT PRIMARY KEY,
                    started_at       TEXT NOT NULL,
                    finished_at      TEXT,
                    provider_id      TEXT NOT NULL,
                    provider_type    TEXT NOT NULL,
                    protocol         TEXT NOT NULL,
                    endpoint         TEXT,
                    ip_stack         TEXT NOT NULL,
                    resolved_ip      TEXT,
                    client_location  TEXT,
                    client_network   TEXT,
                    unidrive_version TEXT NOT NULL,
                    iterations       INTEGER NOT NULL,
                    file_sizes       TEXT NOT NULL,
                    throttled        INTEGER NOT NULL DEFAULT 0
                )
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS benchmark_points (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id        TEXT NOT NULL REFERENCES benchmark_runs(run_id),
                    iteration     INTEGER NOT NULL,
                    operation     TEXT NOT NULL,
                    file_size     INTEGER NOT NULL,
                    duration_ms   INTEGER NOT NULL,
                    throughput_bps INTEGER,
                    status        TEXT NOT NULL,
                    error_code    INTEGER,
                    error_message TEXT,
                    ip_stack      TEXT NOT NULL,
                    timestamp     TEXT NOT NULL
                )
            """)
            stmt.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_bench_points_run ON benchmark_points(run_id)
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS provider_info (
                    provider_type      TEXT PRIMARY KEY,
                    display_name       TEXT NOT NULL,
                    jurisdiction       TEXT,
                    gdpr_compliant     INTEGER,
                    cloud_act_exposure INTEGER,
                    encryption         TEXT,
                    free_tier          TEXT,
                    pricing_per_tb     TEXT,
                    signup_url         TEXT,
                    tier               TEXT
                )
            """)
        }
    }

    @Synchronized
    fun insertRun(run: BenchmarkRun) {
        conn.prepareStatement("""
            INSERT INTO benchmark_runs
                (run_id, started_at, finished_at, provider_id, provider_type, protocol,
                 endpoint, ip_stack, resolved_ip, client_location, client_network,
                 unidrive_version, iterations, file_sizes, throttled)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).use { stmt ->
            stmt.setString(1, run.runId)
            stmt.setString(2, run.startedAt)
            stmt.setString(3, run.finishedAt)
            stmt.setString(4, run.providerId)
            stmt.setString(5, run.providerType)
            stmt.setString(6, run.protocol)
            stmt.setString(7, run.endpoint)
            stmt.setString(8, run.ipStack)
            stmt.setString(9, run.resolvedIp)
            stmt.setString(10, run.clientLocation)
            stmt.setString(11, run.clientNetwork)
            stmt.setString(12, run.unidriveVersion)
            stmt.setInt(13, run.iterations)
            stmt.setString(14, run.fileSizes)
            stmt.setInt(15, if (run.throttled) 1 else 0)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun finishRun(runId: String, finishedAt: String, throttled: Boolean) {
        conn.prepareStatement("""
            UPDATE benchmark_runs SET finished_at = ?, throttled = ? WHERE run_id = ?
        """).use { stmt ->
            stmt.setString(1, finishedAt)
            stmt.setInt(2, if (throttled) 1 else 0)
            stmt.setString(3, runId)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun insertPoint(point: BenchmarkPoint) {
        conn.prepareStatement("""
            INSERT INTO benchmark_points
                (run_id, iteration, operation, file_size, duration_ms, throughput_bps,
                 status, error_code, error_message, ip_stack, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """).use { stmt ->
            stmt.setString(1, point.runId)
            stmt.setInt(2, point.iteration)
            stmt.setString(3, point.operation)
            stmt.setLong(4, point.fileSize)
            stmt.setLong(5, point.durationMs)
            point.throughputBps?.let { stmt.setLong(6, it) } ?: stmt.setNull(6, java.sql.Types.BIGINT)
            stmt.setString(7, point.status)
            point.errorCode?.let { stmt.setInt(8, it) } ?: stmt.setNull(8, java.sql.Types.INTEGER)
            stmt.setString(9, point.errorMessage)
            stmt.setString(10, point.ipStack)
            stmt.setString(11, point.timestamp)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun getRun(runId: String): BenchmarkRun? {
        conn.prepareStatement("SELECT * FROM benchmark_runs WHERE run_id = ?").use { stmt ->
            stmt.setString(1, runId)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.toBenchmarkRun() else null
        }
    }

    @Synchronized
    fun getPoints(runId: String): List<BenchmarkPoint> {
        conn.prepareStatement(
            "SELECT * FROM benchmark_points WHERE run_id = ? ORDER BY id"
        ).use { stmt ->
            stmt.setString(1, runId)
            val rs = stmt.executeQuery()
            val list = mutableListOf<BenchmarkPoint>()
            while (rs.next()) list.add(rs.toBenchmarkPoint())
            return list
        }
    }

    @Synchronized
    fun latestRuns(providerId: String? = null): List<BenchmarkRun> {
        val sql = if (providerId == null) {
            """
            SELECT r.* FROM benchmark_runs r
            INNER JOIN (
                SELECT provider_id, MAX(finished_at) AS max_finished
                FROM benchmark_runs
                WHERE finished_at IS NOT NULL
                GROUP BY provider_id
            ) latest ON r.provider_id = latest.provider_id
                    AND r.finished_at = latest.max_finished
            ORDER BY r.finished_at DESC
            """
        } else {
            """
            SELECT r.* FROM benchmark_runs r
            INNER JOIN (
                SELECT provider_id, MAX(finished_at) AS max_finished
                FROM benchmark_runs
                WHERE finished_at IS NOT NULL AND provider_id = ?
                GROUP BY provider_id
            ) latest ON r.provider_id = latest.provider_id
                    AND r.finished_at = latest.max_finished
            ORDER BY r.finished_at DESC
            """
        }
        conn.prepareStatement(sql).use { stmt ->
            if (providerId != null) stmt.setString(1, providerId)
            val rs = stmt.executeQuery()
            val list = mutableListOf<BenchmarkRun>()
            while (rs.next()) list.add(rs.toBenchmarkRun())
            return list
        }
    }

    @Synchronized
    fun deleteRun(runId: String): Boolean {
        val deleted = conn.prepareStatement("DELETE FROM benchmark_points WHERE run_id = ?").use { ps ->
            ps.setString(1, runId)
            ps.executeUpdate()
        }
        val runDeleted = conn.prepareStatement("DELETE FROM benchmark_runs WHERE run_id = ?").use { ps ->
            ps.setString(1, runId)
            ps.executeUpdate()
        }
        return runDeleted > 0
    }

    @Synchronized
    fun allRuns(): List<BenchmarkRun> {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM benchmark_runs ORDER BY started_at DESC")
            val list = mutableListOf<BenchmarkRun>()
            while (rs.next()) list.add(rs.toBenchmarkRun())
            return list
        }
    }

    private fun ResultSet.toBenchmarkRun() = BenchmarkRun(
        runId = getString("run_id"),
        startedAt = getString("started_at"),
        finishedAt = getString("finished_at"),
        providerId = getString("provider_id"),
        providerType = getString("provider_type"),
        protocol = getString("protocol"),
        endpoint = getString("endpoint"),
        ipStack = getString("ip_stack"),
        resolvedIp = getString("resolved_ip"),
        clientLocation = getString("client_location"),
        clientNetwork = getString("client_network"),
        unidriveVersion = getString("unidrive_version"),
        iterations = getInt("iterations"),
        fileSizes = getString("file_sizes"),
        throttled = getInt("throttled") == 1,
    )

    private fun ResultSet.toBenchmarkPoint() = BenchmarkPoint(
        runId = getString("run_id"),
        iteration = getInt("iteration"),
        operation = getString("operation"),
        fileSize = getLong("file_size"),
        durationMs = getLong("duration_ms"),
        throughputBps = getLong("throughput_bps").let { if (wasNull()) null else it },
        status = getString("status"),
        errorCode = getInt("error_code").let { if (wasNull()) null else it },
        errorMessage = getString("error_message"),
        ipStack = getString("ip_stack"),
        timestamp = getString("timestamp"),
    )
}
