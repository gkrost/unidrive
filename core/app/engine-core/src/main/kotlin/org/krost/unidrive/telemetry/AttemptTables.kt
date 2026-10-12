package org.krost.unidrive.telemetry

import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestAttempt
import java.sql.Connection

/**
 * The request-attempt tables of `telemetry.db` (schema step 2), as SQL over a connection the
 * [TelemetryStore] owns. The `transfers` table is not touched here.
 *
 * - `request_attempts`: one row per HTTP request attempt. Endpoint class, outcome class, timings, bytes, retry number.
 *   No URL, name, identifier or token is ever stored (the endpoint is a class, see `EndpointClassifier`).
 * - `request_rollups`: one row per minute and endpoint class, computed from the raw rows ([Rollups.compute]).
 */
internal object AttemptTables {
    fun create(c: Connection) {
        c.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS request_attempts (
                    id             INTEGER PRIMARY KEY,
                    ts             INTEGER NOT NULL,
                    profile        TEXT NOT NULL,
                    provider       TEXT NOT NULL,
                    endpoint_class TEXT NOT NULL,
                    method         TEXT NOT NULL,
                    outcome        TEXT NOT NULL,
                    headers_ms     INTEGER,
                    total_ms       INTEGER NOT NULL,
                    bytes_up       INTEGER,
                    bytes_down     INTEGER,
                    retry_no       INTEGER NOT NULL,
                    throttled      INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_request_attempts_ts ON request_attempts(ts)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS request_rollups (
                    minute_ms      INTEGER NOT NULL,
                    provider       TEXT NOT NULL,
                    endpoint_class TEXT NOT NULL,
                    attempts       INTEGER NOT NULL,
                    outcomes       TEXT NOT NULL,
                    throttled      INTEGER NOT NULL,
                    retries        INTEGER NOT NULL,
                    headers_p50_ms INTEGER,
                    headers_p95_ms INTEGER,
                    total_p50_ms   INTEGER NOT NULL,
                    total_p95_ms   INTEGER NOT NULL,
                    bytes_up       INTEGER,
                    bytes_down     INTEGER,
                    PRIMARY KEY (minute_ms, provider, endpoint_class)
                ) WITHOUT ROWID
                """.trimIndent(),
            )
        }
    }

    fun insert(
        c: Connection,
        profile: String,
        attempts: Collection<RequestAttempt>,
    ) {
        c.prepareStatement(
            "INSERT INTO request_attempts(ts, profile, provider, endpoint_class, method, outcome, headers_ms, total_ms, " +
                "bytes_up, bytes_down, retry_no, throttled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { ps ->
            for (a in attempts) {
                ps.setLong(1, a.startedAtMs)
                ps.setString(2, profile)
                ps.setString(3, a.provider)
                ps.setString(4, a.endpointClass)
                ps.setString(5, a.method)
                ps.setString(6, a.outcome.wire)
                ps.setObject(7, a.headersMs)
                ps.setLong(8, a.totalMs)
                ps.setObject(9, a.bytesUp)
                ps.setObject(10, a.bytesDown)
                ps.setInt(11, a.retryNo)
                ps.setInt(12, if (a.throttled) 1 else 0)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /** Recomputes the roll-ups of [minutes] from the raw rows still held, replacing what was there. Idempotent. */
    fun rollUp(
        c: Connection,
        minutes: Collection<Long>,
    ) {
        for (minute in minutes.sorted()) {
            val rows = attemptsOfMinute(c, minute)
            if (rows.isEmpty()) continue
            c.prepareStatement(
                "INSERT OR REPLACE INTO request_rollups(minute_ms, provider, endpoint_class, attempts, outcomes, throttled, retries, " +
                    "headers_p50_ms, headers_p95_ms, total_p50_ms, total_p95_ms, bytes_up, bytes_down) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { ps ->
                for (r in Rollups.compute(minute, rows)) {
                    ps.setLong(1, r.minuteMs)
                    ps.setString(2, r.provider)
                    ps.setString(3, r.endpointClass)
                    ps.setInt(4, r.attempts)
                    ps.setString(5, Rollups.encodeOutcomes(r.outcomes))
                    ps.setInt(6, r.throttled)
                    ps.setInt(7, r.retries)
                    ps.setObject(8, r.headersP50Ms)
                    ps.setObject(9, r.headersP95Ms)
                    ps.setLong(10, r.totalP50Ms)
                    ps.setLong(11, r.totalP95Ms)
                    ps.setObject(12, r.bytesUp)
                    ps.setObject(13, r.bytesDown)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    private fun attemptsOfMinute(
        c: Connection,
        minute: Long,
    ): List<RequestAttempt> =
        c.prepareStatement(
            "SELECT ts, provider, endpoint_class, method, outcome, headers_ms, total_ms, bytes_up, bytes_down, retry_no, throttled " +
                "FROM request_attempts WHERE ts >= ? AND ts < ?",
        ).use { ps ->
            ps.setLong(1, minute)
            ps.setLong(2, minute + MINUTE_MS)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        fun long(i: Int): Long? = rs.getLong(i).takeUnless { rs.wasNull() }
                        add(
                            RequestAttempt(
                                startedAtMs = rs.getLong(1),
                                provider = rs.getString(2),
                                endpointClass = rs.getString(3),
                                method = rs.getString(4),
                                outcome = AttemptOutcome.ofWire(rs.getString(5)) ?: AttemptOutcome.IO,
                                headersMs = long(6),
                                totalMs = rs.getLong(7),
                                bytesUp = long(8),
                                bytesDown = long(9),
                                retryNo = rs.getInt(10),
                                throttled = rs.getInt(11) != 0,
                            ),
                        )
                    }
                }
            }
        }

    /** The minutes that hold raw rows newer than the newest roll-up's start: what a crash left unrolled. */
    fun unrolledMinutes(c: Connection): Set<Long> =
        c.createStatement().use { st ->
            st
                .executeQuery(
                    "SELECT DISTINCT (ts / $MINUTE_MS) * $MINUTE_MS FROM request_attempts " +
                        "WHERE ts >= COALESCE((SELECT MAX(minute_ms) FROM request_rollups), 0)",
                ).use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
        }

    fun readRollups(
        c: Connection,
        q: RollupQuery,
    ): RollupResult {
        val where = "minute_ms >= ? AND minute_ms < ?" + if (q.endpointClass != null) " AND endpoint_class = ?" else ""
        val rows =
            c.prepareStatement(
                "SELECT minute_ms, provider, endpoint_class, attempts, outcomes, throttled, retries, headers_p50_ms, headers_p95_ms, " +
                    "total_p50_ms, total_p95_ms, bytes_up, bytes_down FROM request_rollups WHERE $where " +
                    "ORDER BY minute_ms, endpoint_class LIMIT ?",
            ).use { ps ->
                ps.setLong(1, q.fromMs)
                ps.setLong(2, q.toMs)
                var next = 3
                if (q.endpointClass != null) ps.setString(next++, q.endpointClass)
                ps.setInt(next, q.limit + 1)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            fun long(i: Int): Long? = rs.getLong(i).takeUnless { rs.wasNull() }
                            add(
                                RollupRow(
                                    minuteMs = rs.getLong(1),
                                    provider = rs.getString(2),
                                    endpointClass = rs.getString(3),
                                    attempts = rs.getInt(4),
                                    outcomes = Rollups.decodeOutcomes(rs.getString(5)),
                                    throttled = rs.getInt(6),
                                    retries = rs.getInt(7),
                                    headersP50Ms = long(8),
                                    headersP95Ms = long(9),
                                    totalP50Ms = rs.getLong(10),
                                    totalP95Ms = rs.getLong(11),
                                    bytesUp = long(12),
                                    bytesDown = long(13),
                                ),
                            )
                        }
                    }
                }
            }
        val classes =
            c.prepareStatement("SELECT DISTINCT endpoint_class FROM request_rollups WHERE minute_ms >= ? AND minute_ms < ? ORDER BY 1").use { ps ->
                ps.setLong(1, q.fromMs)
                ps.setLong(2, q.toMs)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        val oldest =
            c.createStatement().use { st ->
                st.executeQuery("SELECT MIN(minute_ms) FROM request_rollups").use { rs ->
                    rs.next()
                    rs.getLong(1).takeUnless { rs.wasNull() }
                }
            }
        return RollupResult(rows.take(q.limit), rows.size > q.limit, classes, oldest)
    }

    /** Age windows: raw rows started before [rawCutoffMs] and roll-ups of minutes before [rollupCutoffMs]. Returns the rows removed. */
    fun deleteOlder(
        c: Connection,
        rawCutoffMs: Long,
        rollupCutoffMs: Long,
    ): Int {
        var removed = 0
        c.prepareStatement("DELETE FROM request_attempts WHERE ts < ?").use {
            it.setLong(1, rawCutoffMs)
            removed += it.executeUpdate()
        }
        c.prepareStatement("DELETE FROM request_rollups WHERE minute_ms < ?").use {
            it.setLong(1, rollupCutoffMs)
            removed += it.executeUpdate()
        }
        return removed
    }

    /**
     * The shared byte budget asks for room: drops the oldest tenth of the raw rows, or, when none is left, of the
     * roll-ups. Returns the rows dropped, 0 when there was nothing to drop. Metrics give way before a transfer row does.
     */
    fun shed(c: Connection): Int {
        val raw = count(c, "request_attempts")
        if (raw > 0) {
            c.prepareStatement(
                "DELETE FROM request_attempts WHERE id IN (SELECT id FROM request_attempts ORDER BY ts LIMIT ?)",
            ).use {
                it.setInt(1, maxOf(1, raw / SHED_FRACTION))
                return it.executeUpdate()
            }
        }
        val rollups = count(c, "request_rollups")
        if (rollups > 0) {
            // WITHOUT ROWID: cut at the minute of the n-th oldest row; whole minutes go, never half of one.
            c.prepareStatement(
                "DELETE FROM request_rollups WHERE minute_ms <= (SELECT minute_ms FROM request_rollups ORDER BY minute_ms LIMIT 1 OFFSET ?)",
            ).use {
                it.setInt(1, maxOf(1, rollups / SHED_FRACTION) - 1)
                return it.executeUpdate()
            }
        }
        return 0
    }

    fun count(
        c: Connection,
        table: String,
    ): Int = c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }

    private const val SHED_FRACTION = 10
}
