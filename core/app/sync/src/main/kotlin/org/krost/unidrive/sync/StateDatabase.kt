package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Persistence layer for the sync state.
 *
 * Schema invariants (enforced by [createTables] and the [Recovery] split):
 * - `remote_id` is the primary key (Internxt UUID, or a `local:<uuid4>`
 *   synthetic for pending-upload rows that have no cloud identity yet).
 * - `path` is unique only among alive rows (partial unique index `WHERE
 *   status='EXISTS'`), so trashing `/foo` and re-creating a different file
 *   at the same path is a clean INSERT, not a UNIQUE violation.
 * - `parent_uuid` references another row's `remote_id` (no FK enforced;
 *   relink happens lazily as ancestors arrive). NULL = drive root.
 * - `status` is one of EXISTS / TRASHED / DELETED. The sync loop reads only
 *   via [getEntry] / [getAllEntries] / [getEntriesByPrefix] / etc., which
 *   query the `alive_entries` view; tombstones are reachable only via
 *   [recovery]. This is the compiler-enforced split — there is no method
 *   on `StateDatabase` that returns a non-EXISTS row.
 *
 * Upgrade story: [schemaVersion] starts at the constant [SCHEMA_VERSION].
 * A pre-redesign DB (sync_state present without `schema_version`) drops
 * `sync_entries` and recreates with the new shape; the next scan repopulates.
 */
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

    val recovery: Recovery = Recovery()

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
        bootstrapSchema()
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

    /**
     * Three bootstrap cases (acceptance criterion: upgrade path):
     * 1. `sync_state` missing entirely → fresh install. Create everything
     *    from the new schema and stamp `schema_version`.
     * 2. `sync_state` present, no `schema_version` row → pre-redesign DB.
     *    DROP TABLE sync_entries and recreate with the new shape; the next
     *    scan repopulates. `sync_state` and `pin_rules` are preserved.
     * 3. `sync_state` present with `schema_version=SCHEMA_VERSION` → nothing
     *    to do beyond ensuring objects exist (CREATE IF NOT EXISTS is safe).
     */
    @Synchronized
    private fun bootstrapSchema() {
        val syncStateExists = tableExists("sync_state")
        if (syncStateExists) {
            val recordedVersion = readSchemaVersion()
            if (recordedVersion == null) {
                // Case 2: pre-redesign DB. Drop sync_entries; the new schema
                // below recreates it. Drop any old indexes the previous shape
                // owned so SQLite's auto-recreation on CREATE TABLE doesn't
                // collide with stale objects.
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DROP TABLE IF EXISTS sync_entries")
                }
            }
        }
        createTables()
        stampSchemaVersion()
    }

    @Synchronized
    private fun createTables() {
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sync_entries (
                    remote_id       TEXT PRIMARY KEY,
                    parent_uuid     TEXT,
                    path            TEXT NOT NULL,
                    remote_hash     TEXT,
                    remote_size     INTEGER NOT NULL DEFAULT 0,
                    remote_modified TEXT,
                    local_mtime     INTEGER,
                    local_size      INTEGER,
                    is_folder       INTEGER NOT NULL DEFAULT 0,
                    is_pinned       INTEGER NOT NULL DEFAULT 0,
                    is_hydrated     INTEGER NOT NULL DEFAULT 0,
                    last_synced     TEXT NOT NULL,
                    status          TEXT NOT NULL DEFAULT 'EXISTS'
                                    CHECK (status IN ('EXISTS','TRASHED','DELETED'))
                )
            """,
            )
            // Partial unique index on path — only alive rows compete. Trashing
            // /foo and re-creating a different file at the same path is now
            // INSERT-clean.
            stmt.executeUpdate(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_sync_entries_path_alive
                    ON sync_entries(path) WHERE status='EXISTS'
            """,
            )
            // Composite index so "alive children of X" is an index seek
            // (acceptance criterion: EXPLAIN QUERY PLAN reports USING INDEX,
            // not SCAN sync_entries).
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_parent_alive
                    ON sync_entries(parent_uuid, status)
            """,
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_sync_entries_path_lower_alive
                    ON sync_entries(path COLLATE NOCASE) WHERE status='EXISTS'
            """,
            )
            // The alive_entries VIEW is the only surface the sync loop reads
            // from. Recovery flows hit sync_entries directly.
            stmt.executeUpdate(
                """
                CREATE VIEW IF NOT EXISTS alive_entries AS
                    SELECT * FROM sync_entries WHERE status='EXISTS'
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
    }

    private fun tableExists(name: String): Boolean {
        conn.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        ).use { stmt ->
            stmt.setString(1, name)
            return stmt.executeQuery().next()
        }
    }

    private fun readSchemaVersion(): Int? {
        if (!tableExists("sync_state")) return null
        conn.prepareStatement("SELECT value FROM sync_state WHERE key=?").use { stmt ->
            stmt.setString(1, SCHEMA_VERSION_KEY)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("value").toIntOrNull() else null
        }
    }

    private fun stampSchemaVersion() {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO sync_state (key, value) VALUES (?, ?)",
        ).use { stmt ->
            stmt.setString(1, SCHEMA_VERSION_KEY)
            stmt.setString(2, SCHEMA_VERSION.toString())
            stmt.executeUpdate()
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

    /**
     * Upsert by remote_id (the primary key). Rows with a real cloud UUID
     * survive the path partial-unique index trivially. Rows with
     * `remoteId=null` (pending-upload placeholders from LocalScanner) get
     * a stable `local:<uuid4>` synthetic so the PK is satisfied; later, when
     * the real upload completes and the same path is upserted with a real
     * `remoteId`, the partial unique on path triggers INSERT OR REPLACE to
     * swap the synthetic out cleanly.
     */
    @Synchronized
    fun upsertEntry(entry: SyncEntry) {
        // Path-collision resolution: if a different alive row already holds
        // this path, the partial unique index `WHERE status='EXISTS'` would
        // block our INSERT. SQLite's INSERT OR REPLACE deletes the
        // conflicting row by PK only, not by other unique constraints, so
        // we explicitly delete the colliding-path alive row first.
        conn
            .prepareStatement(
                "DELETE FROM sync_entries WHERE path=? AND status='EXISTS' AND remote_id<>?",
            ).use { stmt ->
                stmt.setString(1, entry.path)
                stmt.setString(2, entry.remoteId ?: storedRemoteIdFor(entry))
                stmt.executeUpdate()
            }
        conn
            .prepareStatement(
                """
            INSERT OR REPLACE INTO sync_entries
                (remote_id, parent_uuid, path, remote_hash, remote_size, remote_modified,
                 local_mtime, local_size, is_folder, is_pinned, is_hydrated, last_synced, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
            ).use { stmt ->
                val storedId = entry.remoteId ?: pickSyntheticIdForPath(entry.path)
                stmt.setString(1, storedId)
                stmt.setString(2, entry.parentUuid)
                stmt.setString(3, entry.path)
                stmt.setString(4, entry.remoteHash)
                stmt.setLong(5, entry.remoteSize)
                stmt.setString(6, entry.remoteModified?.toString())
                entry.localMtime?.let { stmt.setLong(7, it) } ?: stmt.setNull(7, java.sql.Types.BIGINT)
                entry.localSize?.let { stmt.setLong(8, it) } ?: stmt.setNull(8, java.sql.Types.BIGINT)
                stmt.setInt(9, if (entry.isFolder) 1 else 0)
                stmt.setInt(10, if (entry.isPinned) 1 else 0)
                stmt.setInt(11, if (entry.isHydrated) 1 else 0)
                stmt.setString(12, entry.lastSynced.toString())
                stmt.setString(13, entry.status.name)
                stmt.executeUpdate()
            }
    }

    /**
     * For a pending-upload row whose Kotlin `remoteId` is null, reuse any
     * existing synthetic ID already pinned to this alive path so the row
     * count stays at one and the PK doesn't churn. Otherwise mint a fresh
     * `local:<uuid4>` synthetic.
     */
    private fun pickSyntheticIdForPath(path: String): String {
        conn
            .prepareStatement(
                "SELECT remote_id FROM sync_entries WHERE path=? AND status='EXISTS' " +
                    "AND remote_id LIKE 'local:%' LIMIT 1",
            ).use { stmt ->
                stmt.setString(1, path)
                val rs = stmt.executeQuery()
                if (rs.next()) return rs.getString(1)
            }
        return "local:${UUID.randomUUID()}"
    }

    /** For upsertEntry's collision DELETE step — get the storage-layer key without minting one. */
    private fun storedRemoteIdFor(entry: SyncEntry): String =
        entry.remoteId ?: pickSyntheticIdForPath(entry.path)

    @Synchronized
    fun getEntry(path: String): SyncEntry? {
        conn.prepareStatement("SELECT * FROM alive_entries WHERE path = ?").use { stmt ->
            stmt.setString(1, path)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.toSyncEntry() else null
        }
    }

    @Synchronized
    fun getEntryByRemoteId(remoteId: String): SyncEntry? {
        conn.prepareStatement("SELECT * FROM alive_entries WHERE remote_id = ?").use { stmt ->
            stmt.setString(1, remoteId)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.toSyncEntry() else null
        }
    }

    @Synchronized
    fun getEntryCaseInsensitive(path: String): SyncEntry? {
        conn.prepareStatement("SELECT * FROM alive_entries WHERE path = ? COLLATE NOCASE").use { stmt ->
            stmt.setString(1, path)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.toSyncEntry() else null
        }
    }

    @Synchronized
    fun getEntryCount(): Int {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM alive_entries")
            rs.next()
            return rs.getInt(1)
        }
    }

    @Synchronized
    fun getAllEntries(): List<SyncEntry> {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM alive_entries")
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
     */
    @Synchronized
    fun hasHydratedDescendant(topLevel: String): Boolean {
        val pattern = "${escapeLike(topLevel)}/%"
        conn
            .prepareStatement(
                "SELECT 1 FROM alive_entries WHERE (path = ? OR path LIKE ? ESCAPE '\\') " +
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
                "SELECT COUNT(*) FROM alive_entries WHERE path = ? OR path LIKE ? ESCAPE '\\'",
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
        conn.prepareStatement("SELECT * FROM alive_entries WHERE path LIKE ? ESCAPE '\\'").use { stmt ->
            stmt.setString(1, "${escapeLike(prefix)}%")
            val rs = stmt.executeQuery()
            val entries = mutableListOf<SyncEntry>()
            while (rs.next()) entries.add(rs.toSyncEntry())
            return entries
        }
    }

    /**
     * Hard-delete an alive row (no tombstone). Use this only for transitions
     * that DO NOT correspond to a cloud-side delete: move-source cleanup
     * (the item moved, didn't disappear) and pending-upload-row cleanup
     * (the row never reached the cloud, so there's nothing to tombstone).
     *
     * For cloud deletes, call [setStatusTrashed] instead.
     */
    @Synchronized
    fun deleteEntry(path: String) {
        conn.prepareStatement("DELETE FROM sync_entries WHERE path = ? AND status='EXISTS'").use { stmt ->
            stmt.setString(1, path)
            stmt.executeUpdate()
        }
    }

    /**
     * Idempotent flip from EXISTS to TRASHED, keyed by `remote_id`. Returns
     * true if exactly one row was flipped; false if no alive row carried that
     * id (already trashed, already deleted, or the daemon never tracked it).
     *
     * The `WHERE status='EXISTS'` clause makes concurrent flippers safe — the
     * second writer sees zero rows updated and moves on without re-emitting
     * any cascading work.
     */
    @Synchronized
    fun setStatusTrashed(remoteId: String): Boolean {
        if (remoteId.startsWith("local:")) return false // never trash a synthetic
        conn
            .prepareStatement(
                "UPDATE sync_entries SET status='TRASHED' WHERE remote_id=? AND status='EXISTS'",
            ).use { stmt ->
                stmt.setString(1, remoteId)
                return stmt.executeUpdate() == 1
            }
    }

    /**
     * Flip a TRASHED row back to EXISTS. Triggered when a scan reports a
     * previously-trashed `remote_id` is alive again on the cloud — the
     * reconciler then treats it as an arrival. Idempotent.
     */
    @Synchronized
    fun setStatusExists(remoteId: String): Boolean {
        if (remoteId.startsWith("local:")) return false
        conn
            .prepareStatement(
                "UPDATE sync_entries SET status='EXISTS' WHERE remote_id=? AND status='TRASHED'",
            ).use { stmt ->
                stmt.setString(1, remoteId)
                return stmt.executeUpdate() == 1
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
        // collided with the path partial-unique index. Wrap DELETE + UPDATE
        // in batch{} so they're atomic. Pass 1 already runs inside a batch
        // (SyncEngine.kt:356), so the nested call is a no-op per the batch{}
        // contract; tests calling renamePrefix directly get their own
        // transaction.
        batch {
            conn
                .prepareStatement(
                    "DELETE FROM sync_entries WHERE status='EXISTS' " +
                        "AND (path = ? OR path LIKE ? ESCAPE '\\')",
                ).use { stmt ->
                    // Match both the destination root itself AND its descendants.
                    stmt.setString(1, newPrefix.removeSuffix("/"))
                    stmt.setString(2, "${escapeLike(new)}%")
                    stmt.executeUpdate()
                }
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET path = ? || substr(path, ?) " +
                        "WHERE status='EXISTS' AND path LIKE ? ESCAPE '\\'",
                ).use { stmt ->
                    stmt.setString(1, new)
                    stmt.setInt(2, old.length + 1)
                    stmt.setString(3, "${escapeLike(old)}%")
                    stmt.executeUpdate()
                }
            // A folder rename is one UPDATE on the folder row itself too
            // (path swap from /old → /new). The descendants UPDATE above
            // matches old + "/" so it skips the folder-root row. The
            // partial unique on path is per-row so we can update the root
            // safely as long as no alive collision lurks — which we just
            // wiped above.
            conn
                .prepareStatement(
                    "UPDATE sync_entries SET path = ? WHERE status='EXISTS' AND path = ?",
                ).use { stmt ->
                    stmt.setString(1, newPrefix.removeSuffix("/"))
                    stmt.setString(2, oldPrefix.removeSuffix("/"))
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

    private fun ResultSet.toSyncEntry(): SyncEntry {
        val storedId = getString("remote_id")
        // Translate the storage-layer synthetic back to Kotlin's nullable
        // `remoteId` contract — callers never see `local:*` strings.
        val surfacedId = if (storedId != null && storedId.startsWith("local:")) null else storedId
        return SyncEntry(
            path = getString("path"),
            remoteId = surfacedId,
            parentUuid = getString("parent_uuid"),
            remoteHash = getString("remote_hash"),
            remoteSize = getLong("remote_size"),
            remoteModified = getString("remote_modified")?.let { Instant.parse(it) },
            localMtime = getLong("local_mtime").let { if (wasNull()) null else it },
            localSize = getLong("local_size").let { if (wasNull()) null else it },
            isFolder = getInt("is_folder") == 1,
            isPinned = getInt("is_pinned") == 1,
            isHydrated = getInt("is_hydrated") == 1,
            lastSynced = Instant.parse(getString("last_synced")),
            status = EntryStatus.valueOf(getString("status")),
        )
    }

    /**
     * Recovery namespace — the only path to TRASHED / DELETED rows from
     * outside this class. The split is enforced at the type level: nothing
     * on [StateDatabase] returns a non-EXISTS row, so the "did we leak a
     * tombstone into the sync loop?" question is answered by a single
     * grep-free Kotlin check: is the call site `db.something(...)` (alive
     * only) or `db.recovery.something(...)` (any status)?
     */
    inner class Recovery {
        /** All TRASHED rows. The recovery scenario in the spec is one SELECT here + one batched PATCH. */
        @Synchronized
        fun trashedEntries(): List<SyncEntry> {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM sync_entries WHERE status='TRASHED'")
                val entries = mutableListOf<SyncEntry>()
                while (rs.next()) entries.add(rs.toSyncEntry())
                return entries
            }
        }

        /** All rows regardless of status, for diagnostic dumps. */
        @Synchronized
        fun allEntriesAnyStatus(): List<SyncEntry> {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM sync_entries")
                val entries = mutableListOf<SyncEntry>()
                while (rs.next()) entries.add(rs.toSyncEntry())
                return entries
            }
        }

        /** Lookup by remote_id across all statuses — needed for the un-trash decision path. */
        @Synchronized
        fun getEntryByRemoteIdAnyStatus(remoteId: String): SyncEntry? {
            conn.prepareStatement("SELECT * FROM sync_entries WHERE remote_id = ?").use { stmt ->
                stmt.setString(1, remoteId)
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.toSyncEntry() else null
            }
        }
    }

    companion object {
        // Bumped to 2 by the redesign: 1 == pre-redesign (path-PK, no
        // tombstones), 2 == remote_id-PK with status + parent_uuid + alive view.
        internal const val SCHEMA_VERSION: Int = 2
        internal const val SCHEMA_VERSION_KEY: String = "schema_version"
    }
}
