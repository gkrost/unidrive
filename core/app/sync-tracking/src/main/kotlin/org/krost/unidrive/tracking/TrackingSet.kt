package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * Persistent store of the tracking set. Co-exists with the existing
 * engine's `state.db`; this is a separate file (`tracking.db`) so the
 * two engines can run on the same profile and the user can always fall
 * back to the original engine.
 *
 * Three operations dominate the interface:
 * - `lookup(path)`: per-path read during reconcile.
 * - `adopt(path, …)`: when both sides match at first-scan time
 *   (spec Amendment 2's adopt-on-exact-content-match).
 * - `upsert(record)`: every other state transition (Pending* during
 *   apply, TrackedSynced after success, TrackedLocal/RemoteGone after
 *   observing absence on a tracked path).
 *
 * `paths()` is used by the engine to drive per-path reconciliation over
 * every tracked entry — the tracking-set engine's loop iterates the
 * UNION of local observations + remote observations + tracking-set
 * entries, so the "remote vanished and local already deleted" case is
 * still observable even when the remote enumeration and local walk
 * both omit the path.
 */
interface TrackingSet {
    fun initialize()

    fun close()

    fun lookup(path: String): TrackingRecord?

    fun upsert(record: TrackingRecord)

    /** Remove a path from the set. Used by `TrackedBothGone` cleanup and `unclaim`. */
    fun remove(path: String)

    /** Adopt at first-scan time: both sides match, no transfer needed. */
    fun adopt(
        path: String,
        providerId: String,
        local: LocalObservation,
        remote: RemoteObservation,
        at: Instant = Instant.now(),
    )

    /** All currently-tracked paths (any state, including Pending*). Used to drive reconcile + ts status. */
    fun paths(): Set<String>

    /** Count by state — used by `ts status`. */
    fun countsByState(): Map<TrackState, Int>
}

/**
 * SQLite-backed [TrackingSet]. New table layout — NOT shared with
 * `state.db`'s `sync_entries`. Co-existence is intentional: the user can
 * run both engines on the same profile (different DB files) and fall
 * back without data loss.
 *
 * Schema is intentionally minimal. Future fields (move-detection
 * bookkeeping, conflict-resolution policy per path) should add columns
 * rather than overload existing ones.
 */
class SqliteTrackingSet(
    private val dbPath: Path,
    private val inMemory: Boolean = false,
) : TrackingSet {
    private var conn: Connection? = null

    @Synchronized
    override fun initialize() {
        val url =
            if (inMemory) {
                "jdbc:sqlite::memory:"
            } else {
                Files.createDirectories(dbPath.parent)
                "jdbc:sqlite:$dbPath"
            }
        conn = DriverManager.getConnection(url)
        conn!!.autoCommit = true
        createTables()
    }

    @Synchronized
    override fun close() {
        conn?.takeIf { !it.isClosed }?.close()
        conn = null
    }

    private fun createTables() {
        val c = conn ?: error("not initialized")
        c.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS tracking_entries (
                    path             TEXT PRIMARY KEY,
                    provider_id      TEXT NOT NULL,
                    remote_file_id   TEXT,
                    state            TEXT NOT NULL,
                    local_hash       TEXT,
                    local_size       INTEGER,
                    remote_etag      TEXT,
                    remote_size      INTEGER,
                    last_synced      TEXT NOT NULL
                )
                """,
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_tracking_state
                  ON tracking_entries(state)
                """,
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_tracking_remote_id
                  ON tracking_entries(remote_file_id)
                """,
            )
        }
    }

    @Synchronized
    override fun lookup(path: String): TrackingRecord? {
        val c = conn ?: error("not initialized")
        c.prepareStatement(
            "SELECT path, provider_id, remote_file_id, state, local_hash, local_size, " +
                "remote_etag, remote_size, last_synced FROM tracking_entries WHERE path = ?",
        ).use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return TrackingRecord(
                    path = rs.getString("path"),
                    providerId = rs.getString("provider_id"),
                    remoteFileId = rs.getString("remote_file_id"),
                    state = TrackState.valueOf(rs.getString("state")),
                    localHash = rs.getString("local_hash"),
                    localSize = rs.getObject("local_size")?.let { (it as Number).toLong() },
                    remoteEtag = rs.getString("remote_etag"),
                    remoteSize = rs.getObject("remote_size")?.let { (it as Number).toLong() },
                    lastSynced = Instant.parse(rs.getString("last_synced")),
                )
            }
        }
    }

    @Synchronized
    override fun upsert(record: TrackingRecord) {
        val c = conn ?: error("not initialized")
        c.prepareStatement(
            """
            INSERT INTO tracking_entries
                (path, provider_id, remote_file_id, state, local_hash, local_size,
                 remote_etag, remote_size, last_synced)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
                provider_id    = excluded.provider_id,
                remote_file_id = excluded.remote_file_id,
                state          = excluded.state,
                local_hash     = excluded.local_hash,
                local_size     = excluded.local_size,
                remote_etag    = excluded.remote_etag,
                remote_size    = excluded.remote_size,
                last_synced    = excluded.last_synced
            """,
        ).use { ps ->
            ps.setString(1, record.path)
            ps.setString(2, record.providerId)
            ps.setString(3, record.remoteFileId)
            ps.setString(4, record.state.name)
            ps.setString(5, record.localHash)
            if (record.localSize != null) ps.setLong(6, record.localSize) else ps.setNull(6, java.sql.Types.INTEGER)
            ps.setString(7, record.remoteEtag)
            if (record.remoteSize != null) ps.setLong(8, record.remoteSize) else ps.setNull(8, java.sql.Types.INTEGER)
            ps.setString(9, record.lastSynced.toString())
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun remove(path: String) {
        val c = conn ?: error("not initialized")
        c.prepareStatement("DELETE FROM tracking_entries WHERE path = ?").use { ps ->
            ps.setString(1, path)
            ps.executeUpdate()
        }
    }

    override fun adopt(
        path: String,
        providerId: String,
        local: LocalObservation,
        remote: RemoteObservation,
        at: Instant,
    ) {
        upsert(
            TrackingRecord(
                path = path,
                providerId = providerId,
                remoteFileId = remote.remoteFileId,
                state = TrackState.TrackedSynced,
                localHash = local.hash,
                localSize = local.size,
                remoteEtag = remote.etag,
                remoteSize = remote.size,
                lastSynced = at,
            ),
        )
    }

    @Synchronized
    override fun paths(): Set<String> {
        val c = conn ?: error("not initialized")
        val out = mutableSetOf<String>()
        c.prepareStatement("SELECT path FROM tracking_entries").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) out += rs.getString(1)
            }
        }
        return out
    }

    @Synchronized
    override fun countsByState(): Map<TrackState, Int> {
        val c = conn ?: error("not initialized")
        val out = mutableMapOf<TrackState, Int>()
        c.prepareStatement("SELECT state, COUNT(*) FROM tracking_entries GROUP BY state").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out[TrackState.valueOf(rs.getString(1))] = rs.getInt(2)
                }
            }
        }
        return out
    }
}
