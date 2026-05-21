package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

data class SubscriptionInfo(
    val subscriptionId: String,
    val expiresAt: Instant,
)

/**
 * Persists webhook subscription state per profile in SQLite.
 * Uses the same DB file as [StateDatabase] (separate table).
 */
class SubscriptionStore(
    private val dbPath: Path,
) {
    private lateinit var conn: Connection

    @Synchronized
    fun initialize() {
        if (::conn.isInitialized && !conn.isClosed) conn.close()
        Files.createDirectories(dbPath.parent)
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.autoCommit = true
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS webhook_subscriptions (
                    profile     TEXT PRIMARY KEY,
                    sub_id      TEXT NOT NULL,
                    expires_at  TEXT NOT NULL
                )
            """,
            )
        }
    }

    @Synchronized
    fun close() {
        if (::conn.isInitialized && !conn.isClosed) conn.close()
    }

    @Synchronized
    fun save(
        profileName: String,
        subscriptionId: String,
        expiresAt: Instant,
    ) {
        conn
            .prepareStatement(
                "INSERT OR REPLACE INTO webhook_subscriptions (profile, sub_id, expires_at) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, profileName)
                stmt.setString(2, subscriptionId)
                stmt.setString(3, expiresAt.toString())
                stmt.executeUpdate()
            }
    }

    @Synchronized
    fun get(profileName: String): SubscriptionInfo? {
        conn.prepareStatement("SELECT sub_id, expires_at FROM webhook_subscriptions WHERE profile = ?").use { stmt ->
            stmt.setString(1, profileName)
            val rs = stmt.executeQuery()
            return if (rs.next()) {
                SubscriptionInfo(
                    subscriptionId = rs.getString("sub_id"),
                    expiresAt = Instant.parse(rs.getString("expires_at")),
                )
            } else {
                null
            }
        }
    }

    @Synchronized
    fun delete(profileName: String) {
        conn.prepareStatement("DELETE FROM webhook_subscriptions WHERE profile = ?").use { stmt ->
            stmt.setString(1, profileName)
            stmt.executeUpdate()
        }
    }
}
