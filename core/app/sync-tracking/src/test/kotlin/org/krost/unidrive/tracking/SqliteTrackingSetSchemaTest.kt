package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #323 (tracking.db half): tracking_meta exists but carried no schema version, so the
// format could not evolve without ad-hoc probes. The version is stamped at open; a db
// stamped by a NEWER build refuses to open (an older jar must never stamp downward —
// the next upgrade would believe nothing changed and skip migrations). The state.db
// half of #323 is covered by StateDatabaseTest.
class SqliteTrackingSetSchemaTest {
    private fun schemaVersionOf(dbPath: Path): Int? {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().toUri()}").use { c ->
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT value FROM tracking_meta WHERE key='${SqliteTrackingSet.SCHEMA_VERSION_KEY}'")
                    .use { rs ->
                        return if (rs.next()) rs.getString(1).toIntOrNull() else null
                    }
            }
        }
    }

    private fun overwriteSchemaVersion(dbPath: Path, version: Int) {
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().toUri()}").use { c ->
            c.createStatement().use { stmt ->
                stmt.executeUpdate(
                    "UPDATE tracking_meta SET value='$version' " +
                        "WHERE key='${SqliteTrackingSet.SCHEMA_VERSION_KEY}'",
                )
            }
        }
    }

    @Test
    fun `a fresh tracking_db is stamped with the schema version and reopens`() {
        val dbPath = Files.createTempDirectory("ud-323-tracking-fresh").resolve("tracking.db")
        SqliteTrackingSet(dbPath).let { it.initialize(); it.close() }
        assertEquals(SqliteTrackingSet.SCHEMA_VERSION, schemaVersionOf(dbPath))

        SqliteTrackingSet(dbPath).let { it.initialize(); it.close() }
        assertEquals(SqliteTrackingSet.SCHEMA_VERSION, schemaVersionOf(dbPath), "reopening keeps the stamp")
    }

    @Test
    fun `opening a tracking_db stamped by a newer schema is refused and the stamp is untouched`() {
        val dbPath = Files.createTempDirectory("ud-323-tracking-newer").resolve("tracking.db")
        SqliteTrackingSet(dbPath).let { it.initialize(); it.close() }
        overwriteSchemaVersion(dbPath, SqliteTrackingSet.SCHEMA_VERSION + 1)

        val ex =
            assertFailsWith<IllegalStateException> {
                SqliteTrackingSet(dbPath).let { it.initialize(); it.close() }
            }
        assertTrue(
            ex.message!!.contains("schema ${SqliteTrackingSet.SCHEMA_VERSION + 1}") &&
                ex.message!!.contains("supports ${SqliteTrackingSet.SCHEMA_VERSION}") &&
                ex.message!!.contains("upgrade unidrive"),
            "the refusal must name both versions and the way out, got: ${ex.message}",
        )
        assertEquals(
            SqliteTrackingSet.SCHEMA_VERSION + 1,
            schemaVersionOf(dbPath),
            "a refused open must not write anything, least of all stamp the version downward",
        )
    }

    @Test
    fun `opening a tracking_db with an invalid schema stamp is refused without replacing it`() {
        val dbPath = Files.createTempDirectory("tracking-invalid-schema").resolve("tracking.db")
        SqliteTrackingSet(dbPath).let { it.initialize(); it.close() }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { stmt ->
                stmt.executeUpdate("UPDATE tracking_meta SET value='future' WHERE key='schema_version'")
            }
        }

        val ex = assertFailsWith<IllegalStateException> { SqliteTrackingSet(dbPath).initialize() }
        assertTrue(ex.message!!.contains("invalid schema version"))
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT value FROM tracking_meta WHERE key='schema_version'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("future", rs.getString(1), "a refused open must not replace an unknown stamp")
                }
            }
        }
    }

    @Test
    fun `a tracking_db without a stamp adopts the current schema and keeps its rows`() {
        // Pre-version database: tracking_meta exists (it always has) but carries no
        // schema_version row. There is no migration to perform — the first versioned
        // build adopts the format as-is and stamps it.
        val dbPath = Files.createTempDirectory("ud-323-tracking-legacy").resolve("tracking.db")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE tracking_entries (
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
                stmt.executeUpdate("CREATE TABLE tracking_meta (key TEXT PRIMARY KEY, value TEXT)")
                stmt.executeUpdate(
                    "INSERT INTO tracking_entries VALUES ('/a.txt', 'internxt', 'u1', 'TrackedSynced', " +
                        "'h1', 5, 'e1', 5, '2026-01-01T00:00:00Z')",
                )
            }
        }
        assertNull(schemaVersionOf(dbPath))

        val reopened = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            assertNotNull(reopened.lookup("/a.txt"), "rows of an adopted pre-version db survive")
            assertEquals(SqliteTrackingSet.SCHEMA_VERSION, schemaVersionOf(dbPath), "adoption stamps the version")
        } finally {
            reopened.close()
        }
    }
}
