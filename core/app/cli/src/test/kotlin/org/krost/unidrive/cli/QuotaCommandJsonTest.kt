package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #655: `quota --json` is the read-only surface — the running daemon's snapshot or the cached
 * one, never a network call and never a daemon start. A stopped profile stays stopped.
 */
class QuotaCommandJsonTest {
    private lateinit var profileDir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeTest
    fun setUp() {
        profileDir = Files.createTempDirectory("quota-json-profile")
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        profileDir.toFile().deleteRecursively()
    }

    private fun seedCache(
        used: Long,
        total: Long,
        fetchedAt: Instant,
    ) {
        val db = StateDatabase(profileDir.resolve("state.db"))
        db.initialize()
        try {
            db.setSyncState("quota_used", used.toString())
            db.setSyncState("quota_total", total.toString())
            db.setSyncState("quota_remaining", (total - used).toString())
            db.setSyncState("quota_fetched_at", fetchedAt.toString())
        } finally {
            db.close()
        }
    }

    private fun runJson(): Int {
        val code = QuotaCommand().runJson("p", profileDir.resolve("state.db"))
        System.out.flush()
        System.err.flush()
        return code
    }

    @Test
    fun `quota --json reads the cached snapshot and never starts a daemon`() {
        seedCache(used = 42, total = 100, fetchedAt = Instant.now().minusSeconds(3_600))

        val exitCode = runJson()

        assertEquals(0, exitCode)
        val quota = Json.parseToJsonElement(out.toString()).jsonObject
        assertEquals(42L, quota.getValue("used_bytes").jsonPrimitive.long)
        assertEquals(100L, quota.getValue("total_bytes").jsonPrimitive.long)
        assertTrue(quota.getValue("stale").jsonPrimitive.boolean, "a cache-only read is stale by definition")
        assertFalse(Files.exists(IpcServer.defaultSocketPath("p")), "no daemon was started (no socket)")
    }

    @Test
    fun `quota --json with no daemon and no cache fails with the remedy`() {
        val exitCode = runJson()

        assertEquals(1, exitCode)
        assertTrue(err.toString().contains("No running daemon"), "the error names the situation: $err")
        assertTrue(err.toString().contains("to seed the cache"), "the error names the seeding command: $err")
    }
}
