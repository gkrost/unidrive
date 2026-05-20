package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Duration
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
            // Resumable-scan staging slice. Lives in a dedicated table rather
            // than under `sync_entries` because the staged-row content is
            // partial-path-resolution-at-stage-time (Internxt's `delta()`
            // builds the folder graph from ALL fetched pages, so a file
            // staged mid-scan may not have a resolvable path yet) — storing
            // raw uuid + parent_uuid + name lets the next launch's `delta()`
            // resume fetching from the checkpoint, rebuild the full folder
            // graph from staged + new pages, and resolve every path correctly
            // before returning to the reconciler. The alive_entries view is
            // therefore untouched by the staging layer.
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS scan_staging (
                    scan_id     TEXT NOT NULL,
                    remote_id   TEXT NOT NULL,
                    parent_uuid TEXT,
                    plain_name  TEXT NOT NULL,
                    is_folder   INTEGER NOT NULL DEFAULT 0,
                    size        INTEGER NOT NULL DEFAULT 0,
                    modified    TEXT,
                    remote_hash TEXT,
                    reconciled  INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (scan_id, remote_id)
                )
            """,
            )
            stmt.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_scan_staging_scan_id
                    ON scan_staging(scan_id)
            """,
            )
            // Streaming-reconciliation per-row state machine. STAGED rows
            // (reconciled=0) are the resumable-scan default — a daemon
            // restart resumes by re-reconciling them. RECONCILED rows
            // (reconciled=1) have already been dispatched as safe-now
            // actions and upserted into sync_entries, so the next resume
            // must skip them in the per-page reconciler slice (the
            // deferred deletion-bearing flush still considers them via the
            // union of safe-fired + deferred paths). The ADD COLUMN below
            // is the idempotent path for an existing scan_staging table
            // that pre-dates the streaming work; the CREATE TABLE above
            // already includes `reconciled` for fresh installs. No
            // schema_version bump — additive column.
            if (!columnExists("scan_staging", "reconciled")) {
                stmt.executeUpdate(
                    "ALTER TABLE scan_staging ADD COLUMN reconciled INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }

    /**
     * PRAGMA-driven column-presence probe. The idempotency guard for the
     * additive `scan_staging.reconciled` migration: CREATE TABLE IF NOT
     * EXISTS does not pick up new columns on an existing table, so the
     * ADD COLUMN runs only when the column is missing. Matches SQLite's
     * `IF NOT EXISTS` semantic for ALTER TABLE, which the SQLite version
     * we ship doesn't expose natively.
     */
    private fun columnExists(
        table: String,
        column: String,
    ): Boolean {
        conn.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name=?").use { stmt ->
            stmt.setString(1, table)
            stmt.setString(2, column)
            return stmt.executeQuery().next()
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

    /**
     * Resumable-scan staging slice + checkpoint primitives.
     *
     * The scan model: the engine generates a scan id, writes pages to the
     * dedicated `scan_staging` table as they arrive, and clears both the
     * staging slice and the checkpoint when the reconciler has consumed
     * the full inventory. A daemon crash mid-scan leaves both in place;
     * the next launch resumes from the stored marker rather than
     * re-fetching every page from offset 0.
     *
     * Storage layout:
     * - Staged rows live in `scan_staging`, keyed by `(scan_id, remote_id)`.
     *   The table holds raw cloud identity (uuid, parent_uuid, name, etc.)
     *   so the provider can rebuild its folder graph on resume and
     *   re-resolve paths — Internxt's `delta()` only knows the full path
     *   of a file once every folder page has arrived, so per-page staging
     *   must store the raw uuid graph, not the partially-resolved path.
     * - The view + path partial-unique on `sync_entries` are untouched —
     *   staging is fully orthogonal to the live alive set.
     * - Checkpoint state lives in `sync_state` under the `scan_in_progress_*`
     *   keys so it survives daemon restarts without a separate table.
     */
    @Synchronized
    fun beginScan(initialMarker: String?): String {
        val scanId = UUID.randomUUID().toString()
        batch {
            setSyncState(SCAN_IN_PROGRESS_ID, scanId)
            setSyncState(SCAN_IN_PROGRESS_STARTED_AT, Instant.now().toString())
            if (initialMarker != null) {
                setSyncState(SCAN_IN_PROGRESS_MARKER, initialMarker)
            } else {
                clearSyncState(SCAN_IN_PROGRESS_MARKER)
            }
        }
        return scanId
    }

    /**
     * Returns the active scan checkpoint when one exists and is fresher
     * than [staleThreshold]. A stale checkpoint is cleared in-place
     * (staging slice deleted + sync_state keys cleared) so the caller can
     * treat a null return as "start from scratch".
     *
     * Also invalidates the checkpoint when `last_full_scan` was set AFTER
     * the scan started. That marker is stamped by `promotePendingCursor`
     * on completion AND by the UD-223 fast-bootstrap path; either event
     * means the cursor advanced underneath us, so the persisted offsets
     * index into a different result set than the next gather will return.
     * Without this self-heal, a state.db left over from an older jar
     * (whose fast-bootstrap didn't clear the checkpoint) silently pages
     * past items modified in the seam.
     */
    @Synchronized
    fun getActiveScan(staleThreshold: Duration): ActiveScan? {
        val scanId = getSyncState(SCAN_IN_PROGRESS_ID) ?: return null
        val startedAtRaw = getSyncState(SCAN_IN_PROGRESS_STARTED_AT)
        val startedAt = startedAtRaw?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (startedAt == null || Instant.now().isAfter(startedAt.plus(staleThreshold))) {
            clearScan(scanId)
            return null
        }
        val lastFullScanRaw = getSyncState("last_full_scan")
        val lastFullScan = lastFullScanRaw?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (lastFullScan != null && lastFullScan.isAfter(startedAt)) {
            clearScan(scanId)
            return null
        }
        return ActiveScan(
            scanId = scanId,
            marker = getSyncState(SCAN_IN_PROGRESS_MARKER),
            startedAt = startedAt,
        )
    }

    /**
     * Persist one page of scan items atomically: stage the rows + advance the
     * checkpoint marker in a single transaction. SQLite's WAL rollback leaves
     * neither half-applied on crash. Items are upserted (so a re-issued page
     * after a transient error does not duplicate rows).
     *
     * The staged-row content is the *cloud identity* of each item — uuid,
     * parent uuid, plainname, isFolder, size, modified, hash — extracted
     * from [CloudItem]. Path is intentionally NOT stored: per-page-built
     * paths are partially resolved (folders that arrive in later pages
     * leave child files unanchored) and the provider re-resolves
     * everything from the rebuilt folder graph on resume.
     */
    @Synchronized
    fun persistScanPage(
        scanId: String,
        items: List<CloudItem>,
        marker: String,
    ) {
        batch {
            for (item in items) {
                upsertStagedItem(scanId, item)
            }
            setSyncState(SCAN_IN_PROGRESS_MARKER, marker)
        }
    }

    /**
     * Rehydrate the staged rows of an in-progress scan back into CloudItem
     * shape so the provider can feed them into its delta merge alongside the
     * freshly-fetched pages. Returns rows in stable id order so resumes are
     * deterministic. The returned items carry only the cloud-identity fields
     * (name, parentId, isFolder, size, modified, hash); the `path` field is
     * set to `"/" + plainName` as a placeholder — the provider re-resolves
     * the real path using the rebuilt folder graph before returning to the
     * engine.
     *
     * Returns BOTH STAGED (reconciled=0) and RECONCILED (reconciled=1) rows
     * — the provider needs the full folder graph on resume regardless of
     * reconciliation state. Use [loadStagedItemsByReconciled] when a caller
     * needs the slice that still must be reconciled vs. the slice already
     * dispatched.
     */
    @Synchronized
    fun loadStagedItems(scanId: String): List<CloudItem> {
        conn.prepareStatement(
            "SELECT remote_id, parent_uuid, plain_name, is_folder, size, modified, remote_hash " +
                "FROM scan_staging WHERE scan_id = ? ORDER BY remote_id",
        ).use { stmt ->
            stmt.setString(1, scanId)
            val rs = stmt.executeQuery()
            val items = mutableListOf<CloudItem>()
            while (rs.next()) {
                val name = rs.getString("plain_name")
                items.add(
                    CloudItem(
                        id = rs.getString("remote_id"),
                        name = name,
                        // Placeholder path — the provider rewrites this once it
                        // has the full folder graph. See KDoc.
                        path = "/$name",
                        size = rs.getLong("size"),
                        isFolder = rs.getInt("is_folder") == 1,
                        modified = rs.getString("modified")?.let { Instant.parse(it) },
                        created = null,
                        hash = rs.getString("remote_hash"),
                        mimeType = null,
                        parentId = rs.getString("parent_uuid"),
                    ),
                )
            }
            return items
        }
    }

    /**
     * Streaming-reconciliation per-row state machine. Returns only the
     * STAGED rows (reconciled=0) — the slice the post-restart reconciler
     * must re-process. RECONCILED rows (reconciled=1) were already
     * dispatched as safe-now actions and upserted into sync_entries by the
     * previous daemon, so re-reconciling them would either be a no-op
     * (UNCHANGED+UNCHANGED skip) or, worse, double-fire a download/upload.
     *
     * Ordering matches [loadStagedItems] so resumes are deterministic and
     * crash-resilient: the provider sees the same per-id sequence in both
     * "load all" and "load STAGED only" modes.
     */
    @Synchronized
    fun loadStagedItemsByReconciled(
        scanId: String,
        reconciled: Boolean,
    ): List<CloudItem> {
        conn.prepareStatement(
            "SELECT remote_id, parent_uuid, plain_name, is_folder, size, modified, remote_hash " +
                "FROM scan_staging WHERE scan_id = ? AND reconciled = ? ORDER BY remote_id",
        ).use { stmt ->
            stmt.setString(1, scanId)
            stmt.setInt(2, if (reconciled) 1 else 0)
            val rs = stmt.executeQuery()
            val items = mutableListOf<CloudItem>()
            while (rs.next()) {
                val name = rs.getString("plain_name")
                items.add(
                    CloudItem(
                        id = rs.getString("remote_id"),
                        name = name,
                        path = "/$name",
                        size = rs.getLong("size"),
                        isFolder = rs.getInt("is_folder") == 1,
                        modified = rs.getString("modified")?.let { Instant.parse(it) },
                        created = null,
                        hash = rs.getString("remote_hash"),
                        mimeType = null,
                        parentId = rs.getString("parent_uuid"),
                    ),
                )
            }
            return items
        }
    }

    /**
     * Flip a set of staged rows from STAGED (reconciled=0) to RECONCILED
     * (reconciled=1). Called by the streaming reconciler after a page's
     * safe-now actions have been dispatched and the live sync_entries rows
     * upserted, so a daemon restart resuming the scan will skip them in
     * the per-page reconciler slice. Idempotent — re-marking an already-
     * RECONCILED row is a no-op (the UPDATE just rewrites the same value).
     *
     * Single batched UPDATE rather than a per-row loop — the per-page set
     * is bounded by the provider's page size (Internxt 50, OneDrive 200)
     * so a single statement-with-IN-list is the simplest correct shape.
     */
    @Synchronized
    fun markStagedReconciled(
        scanId: String,
        remoteIds: Collection<String>,
    ) {
        if (remoteIds.isEmpty()) return
        val placeholders = remoteIds.joinToString(",") { "?" }
        conn.prepareStatement(
            "UPDATE scan_staging SET reconciled = 1 " +
                "WHERE scan_id = ? AND remote_id IN ($placeholders)",
        ).use { stmt ->
            stmt.setString(1, scanId)
            remoteIds.forEachIndexed { idx, id -> stmt.setString(idx + 2, id) }
            stmt.executeUpdate()
        }
    }

    /**
     * Clear a completed scan's checkpoint and staged rows. Idempotent — calling
     * with an unknown scan id is a no-op. The live `sync_entries` rows written
     * via [upsertEntry] by the engine remain in place; only the per-scan
     * staging slice is removed.
     */
    @Synchronized
    fun completeScan(scanId: String) {
        clearScan(scanId)
    }

    private fun upsertStagedItem(
        scanId: String,
        item: CloudItem,
    ) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO scan_staging
                (scan_id, remote_id, parent_uuid, plain_name, is_folder, size, modified, remote_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
        ).use { stmt ->
            stmt.setString(1, scanId)
            stmt.setString(2, item.id)
            stmt.setString(3, item.parentId)
            stmt.setString(4, item.name)
            stmt.setInt(5, if (item.isFolder) 1 else 0)
            stmt.setLong(6, item.size)
            stmt.setString(7, item.modified?.toString())
            stmt.setString(8, item.hash)
            stmt.executeUpdate()
        }
    }

    private fun clearScan(scanId: String) {
        batch {
            conn.prepareStatement("DELETE FROM scan_staging WHERE scan_id=?").use { stmt ->
                stmt.setString(1, scanId)
                stmt.executeUpdate()
            }
            clearSyncState(SCAN_IN_PROGRESS_ID)
            clearSyncState(SCAN_IN_PROGRESS_MARKER)
            clearSyncState(SCAN_IN_PROGRESS_STARTED_AT)
        }
    }

    private fun clearSyncState(key: String) {
        conn.prepareStatement("DELETE FROM sync_state WHERE key=?").use { stmt ->
            stmt.setString(1, key)
            stmt.executeUpdate()
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
        // The resumable-scan slice lives in a new `scan_staging` table created
        // via `CREATE TABLE IF NOT EXISTS`, so adding it to existing v2 DBs is
        // idempotent and doesn't justify a version bump.
        internal const val SCHEMA_VERSION: Int = 2
        internal const val SCHEMA_VERSION_KEY: String = "schema_version"

        // sync_state keys used by the resumable-scan checkpoint. Public so
        // tests can inject + assert directly without grepping the .kt source.
        const val SCAN_IN_PROGRESS_ID: String = "scan_in_progress_id"
        const val SCAN_IN_PROGRESS_MARKER: String = "scan_in_progress_marker"
        const val SCAN_IN_PROGRESS_STARTED_AT: String = "scan_in_progress_started_at"
    }
}

/**
 * Snapshot of an in-progress remote scan: enough to decide between resume
 * and start-from-scratch on the next sync pass. [marker] is opaque to the
 * engine — the provider parsed it into its native pagination cursor.
 */
data class ActiveScan(
    val scanId: String,
    val marker: String?,
    val startedAt: Instant,
)
