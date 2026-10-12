package org.krost.unidrive.telemetry

import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.io.OwnerOnly
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * The SQLite file behind one profile's transfer history: `telemetry.db`, never `state.db`. The
 * schema is versioned with `PRAGMA user_version`.
 *
 * Journal mode is WAL with `synchronous = NORMAL`: the history is not a durability guarantee (a power
 * cut may lose the last few rows, and the sync state never depends on it).
 *
 * The one connection is shared by the history writer thread, the request-metrics writer thread and readers;
 * every method is synchronized, so each takes its turn between the others' batches.
 */
@Suppress("TooManyFunctions") // the one connection of telemetry.db: every table's statements go through its lock
internal class TelemetryStore private constructor(
    val dbPath: Path,
    private val afterOpen: (Connection) -> Unit,
) {
    private var conn: Connection? = null

    @Synchronized
    private fun connection(): Connection {
        conn?.let { if (!it.isClosed) return it }
        val opened = openConnection()
        conn = opened
        return opened
    }

    private fun openConnection(): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        var ready = false
        try {
            c.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout = 5000")
                st.execute("PRAGMA journal_mode = WAL")
                st.execute("PRAGMA synchronous = NORMAL")
            }
            createSchema(c)
            afterOpen(c)
            ready = true
        } finally {
            if (!ready) runCatching { c.close() }
        }
        restrictSidecars()
        return c
    }

    private fun createSchema(c: Connection) {
        val version = c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        check(version <= SCHEMA_VERSION) { "telemetry.db has schema $version, this build knows $SCHEMA_VERSION" }
        c.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS transfers (
                    operation_id  TEXT PRIMARY KEY,
                    profile       TEXT NOT NULL,
                    direction     TEXT NOT NULL,
                    path          TEXT NOT NULL,
                    queued_at     INTEGER NOT NULL,
                    started_at    INTEGER,
                    finished_at   INTEGER,
                    queue_wait_ms INTEGER,
                    transfer_ms   INTEGER,
                    bytes_total   INTEGER,
                    bytes_done    INTEGER,
                    result        TEXT NOT NULL,
                    error_class   TEXT,
                    error_message TEXT,
                    attempts      INTEGER,
                    old_hash      TEXT,
                    new_hash      TEXT
                ) WITHOUT ROWID
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfers_queued ON transfers(queued_at)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfers_running ON transfers(result) WHERE result = 'running'")
            // The order history.list pages in (newest first, ties by id): a seek and a range scan, no sort of the table.
            // CREATE ... IF NOT EXISTS, so an existing file gets it at its next open without a schema version of its own.
            st.execute("CREATE INDEX IF NOT EXISTS idx_transfers_time ON transfers($SORT_TIME, operation_id)")
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
            // Schema step 2: the request-attempt tables. A file at step 1 gains them; nothing of step 1 changes.
            if (version < ATTEMPTS_SCHEMA_VERSION) AttemptTables.create(c)
            st.execute("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }

    /**
     * SQLite gives -wal and -shm the permissions of the database file, and on Windows they inherit
     * from the folder, which the engine restricts at start. Restricted explicitly as well, best effort.
     */
    private fun restrictSidecars() {
        for (suffix in listOf("-wal", "-shm")) {
            val side = dbPath.resolveSibling(dbPath.fileName.toString() + suffix)
            if (Files.exists(side)) {
                val outcome = OwnerOnly.restrictFile(side)
                if (!outcome.restricted) log.warn("Could not restrict {} to the current user: {}", side, outcome)
            }
        }
    }

    /** Rows that were started and never finished (the process died) are interrupted, never successes. */
    @Synchronized
    fun recoverInterrupted(): Int =
        connection().createStatement().use { it.executeUpdate("UPDATE transfers SET result = 'interrupted' WHERE result = 'running'") }

    /** Inserts or replaces each row by operation id, in one transaction. */
    @Synchronized
    fun upsert(rows: Collection<TransferRow>) {
        if (rows.isEmpty()) return
        val c = connection()
        c.autoCommit = false
        try {
            c.prepareStatement(UPSERT).use { ps ->
                for (row in rows) {
                    TransferRowCodec.bind(ps, row)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        } catch (e: SQLException) {
            runCatching { c.rollback() }
            throw e
        } finally {
            runCatching { c.autoCommit = true }
        }
    }

    @Synchronized
    fun putMeta(
        key: String,
        value: String,
        onlyIfAbsent: Boolean = false,
    ) {
        val verb = if (onlyIfAbsent) "INSERT OR IGNORE" else "INSERT OR REPLACE"
        connection().prepareStatement("$verb INTO meta(key, value) VALUES (?, ?)").use {
            it.setString(1, key)
            it.setString(2, value)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun meta(key: String): String? =
        connection().prepareStatement("SELECT value FROM meta WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    @Synchronized
    fun readAll(): List<TransferRow> =
        connection().createStatement().use { st ->
            st.executeQuery("SELECT * FROM transfers ORDER BY queued_at, operation_id").use { rs ->
                buildList { while (rs.next()) add(TransferRowCodec.read(rs)) }
            }
        }

    /** The database plus its write-ahead log, as the file system sees them. */
    @Synchronized
    fun fileBytes(): Long =
        listOf("", "-wal").sumOf { suffix ->
            val f = dbPath.resolveSibling(dbPath.fileName.toString() + suffix)
            try {
                if (Files.exists(f)) Files.size(f) else 0L
            } catch (e: IOException) {
                log.debug("Cannot stat {}: {}", f, e.message)
                0L
            }
        }

    /**
     * Retention: drops finished rows queued before [cutoffMs], then, while the file is over [maxBytes],
     * the oldest tenth of what is left. A row still running is never dropped. Returns the rows removed.
     */
    @Synchronized
    fun prune(
        cutoffMs: Long,
        maxBytes: Long,
    ): Int {
        val c = connection()
        var removed =
            c.prepareStatement("DELETE FROM transfers WHERE result <> 'running' AND queued_at < ?").use {
                it.setLong(1, cutoffMs)
                it.executeUpdate()
            }
        var guard = 0
        while (liveBytes(c) > maxBytes && guard++ < MAX_PRUNE_ROUNDS) {
            // The byte budget is shared with the request metrics, which give way first: raw attempts, then roll-ups.
            val shed = AttemptTables.shed(c)
            if (shed > 0) {
                removed += shed
                continue
            }
            val rowCount = c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM transfers").use { it.next(); it.getInt(1) } }
            val n =
                c.prepareStatement(
                    "DELETE FROM transfers WHERE operation_id IN " +
                        "(SELECT operation_id FROM transfers WHERE result <> 'running' ORDER BY queued_at LIMIT ?)",
                ).use {
                    it.setInt(1, maxOf(1, rowCount / PRUNE_FRACTION))
                    it.executeUpdate()
                }
            removed += n
            if (n == 0) break
        }
        if (removed > 0) shrinkFile(c, maxBytes)
        return removed
    }

    /** Inserts the attempts of one profile in one transaction. */
    @Synchronized
    fun insertAttempts(
        profile: String,
        attempts: Collection<RequestAttempt>,
    ) {
        if (attempts.isEmpty()) return
        inTransaction { AttemptTables.insert(it, profile, attempts) }
    }

    /** Recomputes the roll-ups of [minutes] from the raw rows. */
    @Synchronized
    fun rollUp(minutes: Collection<Long>) {
        if (minutes.isEmpty()) return
        inTransaction { AttemptTables.rollUp(it, minutes) }
    }

    /** Minutes with raw rows that no roll-up covers yet (after a crash). */
    @Synchronized
    fun unrolledMinutes(): Set<Long> = AttemptTables.unrolledMinutes(connection())

    @Synchronized
    fun readRollups(query: RollupQuery): RollupResult = AttemptTables.readRollups(connection(), query)

    @Synchronized
    fun attemptCount(): Int = AttemptTables.count(connection(), "request_attempts")

    /**
     * Retention of the request metrics: raw rows started before [rawCutoffMs], roll-ups before [rollupCutoffMs], then, while
     * the file is over [maxBytes], the oldest tenth of the raw rows and then of the roll-ups. Transfer rows are not touched
     * here; their own [prune] also asks the metrics to give way first. Returns the rows removed.
     */
    @Synchronized
    fun pruneMetrics(
        rawCutoffMs: Long,
        rollupCutoffMs: Long,
        maxBytes: Long,
    ): Int {
        val c = connection()
        var removed = AttemptTables.deleteOlder(c, rawCutoffMs, rollupCutoffMs)
        var guard = 0
        while (liveBytes(c) > maxBytes && guard++ < MAX_PRUNE_ROUNDS) {
            val shed = AttemptTables.shed(c)
            if (shed == 0) break
            removed += shed
        }
        if (removed > 0) shrinkFile(c, maxBytes)
        return removed
    }

    private fun inTransaction(block: (Connection) -> Unit) {
        val c = connection()
        c.autoCommit = false
        try {
            block(c)
            c.commit()
        } catch (e: SQLException) {
            runCatching { c.rollback() }
            throw e
        } finally {
            runCatching { c.autoCommit = true }
        }
    }

    // Deleted rows leave free pages inside the file; a VACUUM gives them back when the file is still too big.
    private fun shrinkFile(
        c: Connection,
        maxBytes: Long,
    ) {
        c.createStatement().use { st ->
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            if (fileBytes() > maxBytes) {
                st.execute("VACUUM")
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
    }

    private fun liveBytes(c: Connection): Long =
        c.createStatement().use { st ->
            fun pragma(name: String) = st.executeQuery("PRAGMA $name").use { it.next(); it.getLong(1) }
            (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
        }

    @Synchronized
    fun close() {
        val c = conn ?: return
        conn = null
        try {
            c.close()
        } catch (e: SQLException) {
            log.debug("Closing telemetry.db: {}", e.message)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
        private const val ATTEMPTS_SCHEMA_VERSION = 2

        /** The instant a transfer sorts by: when it finished, or, while it has not, when it was queued. */
        const val SORT_TIME = "COALESCE(finished_at, queued_at)"
        private const val MAX_PRUNE_ROUNDS = 64
        private const val PRUNE_FRACTION = 10
        private val log = LoggerFactory.getLogger(TelemetryStore::class.java)

        private const val COLUMNS =
            "operation_id, profile, direction, path, queued_at, started_at, finished_at, queue_wait_ms, transfer_ms, " +
                "bytes_total, bytes_done, result, error_class, error_message, attempts, old_hash, new_hash"
        private val UPSERT =
            "INSERT INTO transfers($COLUMNS) VALUES (${List(17) { "?" }.joinToString(",")}) " +
                "ON CONFLICT(operation_id) DO UPDATE SET " +
                COLUMNS.split(", ").drop(1).joinToString(", ") { "$it = excluded.$it" }

        /**
         * Creates the (empty) file owner-only before SQLite writes a byte into it, opens it, and turns
         * the rows an earlier process left running into `interrupted`. Throws [IOException] when the
         * file cannot be restricted to the current user, [SQLException] when it cannot be opened.
         */
        fun open(
            dbPath: Path,
            afterOpen: (Connection) -> Unit = {},
        ): TelemetryStore {
            dbPath.parent?.let { Files.createDirectories(it) }
            if (!Files.exists(dbPath)) Files.createFile(dbPath)
            OwnerOnly.requireFile(dbPath)
            val store = TelemetryStore(dbPath, afterOpen)
            try {
                val interrupted = store.recoverInterrupted()
                if (interrupted > 0) log.info("{} transfer(s) of an earlier run never finished; recorded as interrupted", interrupted)
            } catch (e: SQLException) {
                store.close()
                throw e
            }
            return store
        }
    }
}

/** Binds a [TransferRow] to the upsert statement and reads one back; the column order is [TelemetryStore]'s. */
internal object TransferRowCodec {
    fun bind(
        ps: PreparedStatement,
        r: TransferRow,
    ) {
        ps.setString(1, r.operationId)
        ps.setString(2, r.profile)
        ps.setString(3, r.direction.wire)
        ps.setString(4, r.path)
        ps.setLong(5, r.queuedAt)
        ps.setObject(6, r.startedAt)
        ps.setObject(7, r.finishedAt)
        ps.setObject(8, r.queueWaitMs)
        ps.setObject(9, r.transferMs)
        ps.setObject(10, r.bytesTotal)
        ps.setObject(11, r.bytesDone)
        ps.setString(12, r.result.wire)
        ps.setString(13, r.errorClass)
        ps.setString(14, r.errorMessage)
        ps.setObject(15, r.attempts)
        ps.setString(16, r.oldHash)
        ps.setString(17, r.newHash)
    }

    fun read(rs: ResultSet): TransferRow {
        fun long(column: String): Long? = rs.getLong(column).takeUnless { rs.wasNull() }
        return TransferRow(
            operationId = rs.getString("operation_id"),
            profile = rs.getString("profile"),
            direction = TransferDirection.ofWire(rs.getString("direction")),
            path = rs.getString("path"),
            queuedAt = rs.getLong("queued_at"),
            startedAt = long("started_at"),
            finishedAt = long("finished_at"),
            queueWaitMs = long("queue_wait_ms"),
            transferMs = long("transfer_ms"),
            bytesTotal = long("bytes_total"),
            bytesDone = long("bytes_done"),
            result = TransferResult.ofWire(rs.getString("result")),
            errorClass = rs.getString("error_class"),
            errorMessage = rs.getString("error_message"),
            attempts = long("attempts")?.toInt(),
            oldHash = rs.getString("old_hash"),
            newHash = rs.getString("new_hash"),
        )
    }
}
