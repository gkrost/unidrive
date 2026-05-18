package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

class StateDatabase(
    private val dbPath: Path,
    // UD-738: when true, open an in-memory SQLite DB instead of a file-backed
    // one. Used by `--reset --dry-run` to plan against a clean slate without
    // touching the on-disk state.db. dbPath is retained for callers that
    // still want to know "where the real one lives" but is not used for the
    // connection URL.
    private val inMemory: Boolean = false,
) {
    private var _conn: Connection? = null
    private val conn: Connection
        get() = _conn ?: error("StateDatabase not initialized — call initialize() first")

    @Synchronized
    fun initialize() {
        _conn?.takeIf { !it.isClosed }?.close()
        val url =
            if (inMemory) {
                "jdbc:sqlite::memory:"
            } else {
                Files.createDirectories(dbPath.parent)
                "jdbc:sqlite:$dbPath"
            }
        _conn = DriverManager.getConnection(url)
        conn.autoCommit = true
        createTables()
    }

    @Synchronized
    fun close() {
        _conn?.takeIf { !it.isClosed }?.close()
    }

    @Synchronized
    fun resetAll() {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("DELETE FROM sync_entries")
            stmt.executeUpdate("DELETE FROM sync_state")
        }
    }

    @Synchronized
    private fun createTables() {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sync_entries (
                    path            TEXT PRIMARY KEY,
                    remote_id       TEXT,
                    remote_hash     TEXT,
                    remote_size     INTEGER NOT NULL DEFAULT 0,
                    remote_modified TEXT,
                    local_mtime     INTEGER,
                    local_size      INTEGER,
                    is_folder       INTEGER NOT NULL DEFAULT 0,
                    is_pinned       INTEGER NOT NULL DEFAULT 0,
                    is_hydrated     INTEGER NOT NULL DEFAULT 0,
                    last_synced     TEXT NOT NULL
                )
            """,
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_path_lower
                    ON sync_entries(path COLLATE NOCASE)
            """,
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_remote_id
                    ON sync_entries(remote_id)
            """,
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sync_state (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """,
            )
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS pin_rules (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    pattern TEXT NOT NULL UNIQUE,
                    pinned  INTEGER NOT NULL
                )
            """,
            )
        }
        dedupeRemoteIdsOnce()
    }

    /**
     * One-shot cleanup of duplicate `remote_id` rows produced by the
     * Internxt phantom-folder bug (path-collapse on missing ancestor —
     * see docs/audits/internxt-phantom-investigation.md). For each
     * `remote_id` appearing on more than one row, keep the row with the
     * longest path and delete the others; the bug shallowed paths (it
     * never deepened them), so the longest path is the pre-bug truth.
     *
     * Idempotent: gated by a marker in `sync_state` so the scan only
     * runs once per profile. Fresh databases set the marker immediately
     * with zero rows touched.
     */
    @Synchronized
    private fun dedupeRemoteIdsOnce() {
        val markerKey = "migration:dedupe_remote_id"
        conn.prepareStatement("SELECT value FROM sync_state WHERE key = ?").use { stmt ->
            stmt.setString(1, markerKey)
            if (stmt.executeQuery().next()) return
        }
        val deleted =
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    DELETE FROM sync_entries WHERE rowid IN (
                        SELECT rowid FROM (
                            SELECT rowid,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY remote_id
                                       ORDER BY LENGTH(path) DESC, rowid ASC
                                   ) AS rn
                            FROM sync_entries
                            WHERE remote_id IS NOT NULL
                        )
                        WHERE rn > 1
                    )
                """,
                )
            }
        conn.prepareStatement("INSERT OR REPLACE INTO sync_state (key, value) VALUES (?, ?)").use { stmt ->
            stmt.setString(1, markerKey)
            stmt.setString(2, "deleted=$deleted")
            stmt.executeUpdate()
        }
        if (deleted > 0) {
            // No log dependency in this module — call sites observe via
            // sync_state marker (`migration:dedupe_remote_id` value carries
            // the count). One-shot at first initialize after upgrade.
        }
    }

    /**
     * Run [block] inside a single SQLite transaction. All writes inside the block share one
     * fsync instead of one per statement. Nested calls are no-ops (autoCommit already false).
     */
    @Synchronized
    fun <T> batch(block: () -> T): T {
        if (!conn.autoCommit) return block() // already inside a transaction
        conn.autoCommit = false
        return try {
            val result = block()
            conn.commit()
            result
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Begin a manual transaction (for suspend callers that can't use the [batch] lambda). */
    @Synchronized fun beginBatch() {
        if (conn.autoCommit) conn.autoCommit = false
    }

    /** Commit a manual transaction started by [beginBatch]. */
    @Synchronized fun commitBatch() {
        conn.commit()
        conn.autoCommit = true
    }

    /** Roll back a manual transaction started by [beginBatch]. */
    @Synchronized fun rollbackBatch() {
        conn.rollback()
        conn.autoCommit = true
    }

    @Synchronized
    fun upsertEntry(entry: SyncEntry) {
        conn
            .prepareStatement(
                """
            INSERT OR REPLACE INTO sync_entries
                (path, remote_id, remote_hash, remote_size, remote_modified,
                 local_mtime, local_size, is_folder, is_pinned, is_hydrated, last_synced)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
            ).use { stmt ->
                stmt.setString(1, entry.path)
                stmt.setString(2, entry.remoteId)
                stmt.setString(3, entry.remoteHash)
                stmt.setLong(4, entry.remoteSize)
                stmt.setString(5, entry.remoteModified?.toString())
                entry.localMtime?.let { stmt.setLong(6, it) } ?: stmt.setNull(6, java.sql.Types.BIGINT)
                entry.localSize?.let { stmt.setLong(7, it) } ?: stmt.setNull(7, java.sql.Types.BIGINT)
                stmt.setInt(8, if (entry.isFolder) 1 else 0)
                stmt.setInt(9, if (entry.isPinned) 1 else 0)
                stmt.setInt(10, if (entry.isHydrated) 1 else 0)
                stmt.setString(11, entry.lastSynced.toString())
                stmt.executeUpdate()
            }
    }

    @Synchronized
    fun getEntry(path: String): SyncEntry? {
        conn.prepareStatement("SELECT * FROM sync_entries WHERE path = ?").use { stmt ->
            stmt.setString(1, path)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.toSyncEntry() else null
        }
    }

    @Synchronized
    fun getEntryByRemoteId(remoteId: String): SyncEntry? {
        conn.prepareStatement("SELECT * FROM sync_entries WHERE remote_id = ?").use { stmt ->
            stmt.setString(1, remoteId)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.toSyncEntry() else null
        }
    }

    @Synchronized
    fun getEntryCaseInsensitive(path: String): SyncEntry? {
        conn.prepareStatement("SELECT * FROM sync_entries WHERE path = ? COLLATE NOCASE").use { stmt ->
            stmt.setString(1, path)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.toSyncEntry() else null
        }
    }

    @Synchronized
    fun getEntryCount(): Int {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM sync_entries")
            rs.next()
            return rs.getInt(1)
        }
    }

    @Synchronized
    fun getAllEntries(): List<SyncEntry> {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM sync_entries")
            val entries = mutableListOf<SyncEntry>()
            while (rs.next()) entries.add(rs.toSyncEntry())
            return entries
        }
    }

    /**
     * UD-264: cheap "has anything under this top-level ever been hydrated
     * locally?" probe for the top-level-never-hydrated delete guard. The
     * top-level is passed as e.g. `/Documents`; we test against descendants
     * under `/Documents/`. Returns true on the first hit (`LIMIT 1`) — the
     * intent is "is this subtree known to the user", not a count.
     *
     * The path-LIKE index (`idx_sync_entries_path_lower`) doesn't help here
     * because we're filtering on the additional column, but the predicate is
     * narrow enough (single-segment prefix, short-circuit on first row) that
     * we don't need a dedicated index even for 280k-row state.dbs.
     */
    @Synchronized
    fun hasHydratedDescendant(topLevel: String): Boolean {
        val pattern = "${escapeLike(topLevel)}/%"
        conn
            .prepareStatement(
                "SELECT 1 FROM sync_entries WHERE (path = ? OR path LIKE ? ESCAPE '\\') " +
                    "AND (is_hydrated = 1 OR local_mtime IS NOT NULL) LIMIT 1",
            ).use { stmt ->
                stmt.setString(1, topLevel)
                stmt.setString(2, pattern)
                val rs = stmt.executeQuery()
                return rs.next()
            }
    }

    /**
     * UD-265: count tracked entries under a given top-level cloud path
     * (e.g. `/Documents`). Used by the per-subtree deletion safeguard to
     * compute the denominator when evaluating "delete N% of this subtree".
     * Counts the top-level row itself plus all descendants — matches what
     * the deletion plan operates on.
     */
    @Synchronized
    fun countEntriesUnderTopLevel(topLevel: String): Int {
        val pattern = "${escapeLike(topLevel)}/%"
        conn
            .prepareStatement(
                "SELECT COUNT(*) FROM sync_entries WHERE path = ? OR path LIKE ? ESCAPE '\\'",
            ).use { stmt ->
                stmt.setString(1, topLevel)
                stmt.setString(2, pattern)
                val rs = stmt.executeQuery()
                rs.next()
                return rs.getInt(1)
            }
    }

    @Synchronized
    fun getEntriesByPrefix(prefix: String): List<SyncEntry> {
        conn.prepareStatement("SELECT * FROM sync_entries WHERE path LIKE ? ESCAPE '\\'").use { stmt ->
            stmt.setString(1, "${escapeLike(prefix)}%")
            val rs = stmt.executeQuery()
            val entries = mutableListOf<SyncEntry>()
            while (rs.next()) entries.add(rs.toSyncEntry())
            return entries
        }
    }

    @Synchronized
    fun deleteEntry(path: String) {
        conn.prepareStatement("DELETE FROM sync_entries WHERE path = ?").use { stmt ->
            stmt.setString(1, path)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun renamePrefix(
        oldPrefix: String,
        newPrefix: String,
    ) {
        val old = if (oldPrefix.endsWith('/')) oldPrefix else "$oldPrefix/"
        val new = if (newPrefix.endsWith('/')) newPrefix else "$newPrefix/"
        // UD-901c: clear any pre-existing destination rows BEFORE the UPDATE.
        // Pre-fix, when LocalScanner had already written UD-901 pending rows
        // at the destination prefix (because the user moved a folder locally
        // and the new path appeared as NEW during local-scan), the UPDATE
        // collided with SQLite's PK uniqueness on `path`. The action failed,
        // the remote-side move had already landed, and the DB was left
        // half-moved — source rows at old prefix, pending rows at new prefix
        // — feeding a permanent failure cascade on subsequent runs.
        //
        // Wrap DELETE + UPDATE in batch{} so they're atomic. Pass 1 already
        // runs inside a batch (SyncEngine.kt:356), so the nested call is a
        // no-op per the batch{} contract; tests calling renamePrefix
        // directly get their own transaction.
        batch {
            conn
                .prepareStatement(
                    "DELETE FROM sync_entries WHERE path = ? OR path LIKE ? ESCAPE '\\'",
                ).use { stmt ->
                    // Match both the destination root itself AND its descendants.
                    stmt.setString(1, newPrefix.removeSuffix("/"))
                    stmt.setString(2, "${escapeLike(new)}%")
                    stmt.executeUpdate()
                }
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET path = ? || substr(path, ?) WHERE path LIKE ? ESCAPE '\\'",
                ).use { stmt ->
                    stmt.setString(1, new)
                    stmt.setInt(2, old.length + 1)
                    stmt.setString(3, "${escapeLike(old)}%")
                    stmt.executeUpdate()
                }
        }
    }

    @Synchronized
    fun getSyncState(key: String): String? {
        conn.prepareStatement("SELECT value FROM sync_state WHERE key = ?").use { stmt ->
            stmt.setString(1, key)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("value") else null
        }
    }

    @Synchronized
    fun setSyncState(
        key: String,
        value: String,
    ) {
        conn.prepareStatement("INSERT OR REPLACE INTO sync_state (key, value) VALUES (?, ?)").use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, value)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun addPinRule(
        pattern: String,
        pinned: Boolean,
    ) {
        conn.prepareStatement("INSERT OR REPLACE INTO pin_rules (pattern, pinned) VALUES (?, ?)").use { stmt ->
            stmt.setString(1, pattern)
            stmt.setInt(2, if (pinned) 1 else 0)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun removePinRule(pattern: String) {
        conn.prepareStatement("DELETE FROM pin_rules WHERE pattern = ?").use { stmt ->
            stmt.setString(1, pattern)
            stmt.executeUpdate()
        }
    }

    @Synchronized
    fun getPinRules(): List<Pair<String, Boolean>> {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT pattern, pinned FROM pin_rules")
            val rules = mutableListOf<Pair<String, Boolean>>()
            while (rs.next()) rules.add(rs.getString("pattern") to (rs.getInt("pinned") == 1))
            return rules
        }
    }

    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun ResultSet.toSyncEntry() =
        SyncEntry(
            path = getString("path"),
            remoteId = getString("remote_id"),
            remoteHash = getString("remote_hash"),
            remoteSize = getLong("remote_size"),
            remoteModified = getString("remote_modified")?.let { Instant.parse(it) },
            localMtime = getLong("local_mtime").let { if (wasNull()) null else it },
            localSize = getLong("local_size").let { if (wasNull()) null else it },
            isFolder = getInt("is_folder") == 1,
            isPinned = getInt("is_pinned") == 1,
            isHydrated = getInt("is_hydrated") == 1,
            lastSynced = Instant.parse(getString("last_synced")),
        )
}
