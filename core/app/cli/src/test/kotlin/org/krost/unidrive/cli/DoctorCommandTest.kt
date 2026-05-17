package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UD-268: unit tests for DoctorCommand.
 *
 * These tests drive the pure check functions directly (DoctorCommand.runChecks)
 * against a seeded profile dir rather than invoking the full picocli pipeline.
 * The seeding pattern matches SweepCommandTest — temp dirs for sync root +
 * profile, plus an in-process StateDatabase. No network, no provider creation.
 */
class DoctorCommandTest {
    private lateinit var profileDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path

    @BeforeTest
    fun setUp() {
        profileDir = Files.createTempDirectory("unidrive-doctor-profile")
        syncRoot = Files.createTempDirectory("unidrive-doctor-syncroot")
        dbPath = profileDir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        // best-effort cleanup; not load-bearing.
    }

    private fun seedDb(block: (StateDatabase) -> Unit) {
        val db = StateDatabase(dbPath)
        try {
            db.initialize()
            block(db)
        } finally {
            db.close()
        }
    }

    private fun runDoctor(
        full: Boolean = false,
        now: Instant = Instant.parse("2026-05-17T12:00:00Z"),
    ): List<DoctorCommand.CheckResult> {
        val cmd = DoctorCommand()
        return cmd.runChecks(profileDir, syncRoot, full, nowOverride = now)
    }

    private fun result(
        checks: List<DoctorCommand.CheckResult>,
        name: String,
    ): DoctorCommand.CheckResult =
        checks.firstOrNull { it.check == name }
            ?: error("expected check '$name' in $checks")

    // ── Command registration ──────────────────────────────────────────────

    @Test
    fun `doctor command is registered`() {
        val cmd = CommandLine(Main())
        assertNotNull(cmd.subcommands["doctor"], "doctor subcommand should be registered")
    }

    @Test
    fun `doctor command exposes --json and --full flags`() {
        val cmd = CommandLine(Main())
        val doctorCmd = cmd.subcommands["doctor"]!!
        val options = doctorCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--json" in options, "expected --json; got $options")
        assertTrue("--full" in options, "expected --full; got $options")
    }

    // ── Clean profile → exit 0 ────────────────────────────────────────────

    @Test
    fun `clean profile reports no warnings or errors`() {
        seedDb { db ->
            // Recent cursors + fresh quota — nothing for any check to flag.
            db.setSyncState("last_full_scan", Instant.parse("2026-05-17T08:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
        }

        val checks = runDoctor()
        val warnings = checks.filter { it.severity == DoctorCommand.Severity.WARN }
        val errors = checks.filter { it.severity == DoctorCommand.Severity.ERR }
        assertTrue(
            warnings.isEmpty(),
            "expected no warnings on a clean profile; got ${warnings.map { it.summary }}",
        )
        assertTrue(
            errors.isEmpty(),
            "expected no errors on a clean profile; got ${errors.map { it.summary }}",
        )

        val worstRank = checks.maxOf { it.severity.rank }
        assertEquals(0, worstRank, "worst severity rank → exit 0")
    }

    // ── Stale cursor → WARN, exit 1 ───────────────────────────────────────

    @Test
    fun `stale last_full_scan triggers cursor-freshness WARN`() {
        seedDb { db ->
            // 30 days ago vs the pinned "now" — well past the 7-day threshold.
            db.setSyncState("last_full_scan", Instant.parse("2026-04-17T12:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
        }

        val checks = runDoctor()
        val cursor = result(checks, "cursor-freshness")
        assertEquals(DoctorCommand.Severity.WARN, cursor.severity)
        assertTrue(
            cursor.summary.contains("30 days old"),
            "expected '30 days old' in summary; got '${cursor.summary}'",
        )

        val worstRank = checks.maxOf { it.severity.rank }
        assertEquals(1, worstRank, "stale-cursor profile → exit 1")
    }

    @Test
    fun `pending_cursor_complete=false triggers cursor-freshness WARN even with fresh scan`() {
        seedDb { db ->
            db.setSyncState("last_full_scan", Instant.parse("2026-05-17T08:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "false")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
        }
        val checks = runDoctor()
        val cursor = result(checks, "cursor-freshness")
        assertEquals(DoctorCommand.Severity.WARN, cursor.severity)
        assertTrue(
            cursor.summary.contains("pending_cursor_complete=false"),
            "expected pending-cursor-incomplete summary; got '${cursor.summary}'",
        )
    }

    // ── High-delete-day audit log → WARN, exit 1 ──────────────────────────

    @Test
    fun `100 deletes in today's audit log triggers recent-destructive WARN`() {
        // Seed an audit log with 100 Delete actions on today's UTC date.
        val now = Instant.parse("2026-05-17T12:00:00Z")
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val auditFile = profileDir.resolve("audit-$today.jsonl")
        val lines = buildString {
            repeat(100) { i ->
                append(
                    """{"ts":"$now","action":"Delete","path":"/doomed/file-$i.bin","result":"success","profile":"test"}""",
                )
                append("\n")
            }
        }
        Files.writeString(auditFile, lines)

        // Seed a clean state.db so the other checks don't escalate.
        seedDb { db ->
            db.setSyncState("last_full_scan", Instant.parse("2026-05-17T08:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
        }

        val checks = runDoctor(now = now)
        val destructive = result(checks, "recent-destructive")
        assertEquals(DoctorCommand.Severity.WARN, destructive.severity)
        assertTrue(
            destructive.summary.contains("100"),
            "expected delete count 100 in summary; got '${destructive.summary}'",
        )
    }

    @Test
    fun `250 deletes escalates recent-destructive to ERR exit 2`() {
        val now = Instant.parse("2026-05-17T12:00:00Z")
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val auditFile = profileDir.resolve("audit-$today.jsonl")
        val lines = buildString {
            repeat(250) { i ->
                append(
                    """{"ts":"$now","action":"Delete","path":"/d/file-$i.bin","result":"success","profile":"test"}""",
                )
                append("\n")
            }
        }
        Files.writeString(auditFile, lines)
        seedDb { db ->
            db.setSyncState("last_full_scan", Instant.parse("2026-05-17T08:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
        }

        val checks = runDoctor(now = now)
        val destructive = result(checks, "recent-destructive")
        assertEquals(DoctorCommand.Severity.ERR, destructive.severity)
        val worstRank = checks.maxOf { it.severity.rank }
        assertEquals(2, worstRank, "high-delete profile → exit 2")
    }

    // ── --json output is well-formed ──────────────────────────────────────

    @Test
    fun `--json output parses cleanly with kotlinx-serialization`() {
        seedDb { db ->
            db.setSyncState("last_full_scan", Instant.parse("2026-04-01T12:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
        }
        val checks = runDoctor()
        val cmd = DoctorCommand()
        val jsonText = cmd.renderJson(checks)

        // Must parse without throwing.
        val parsed = Json.parseToJsonElement(jsonText)
        val obj = parsed as? JsonObject
            ?: error("expected JsonObject at root; got $parsed")
        assertNotNull(obj["version"])
        assertEquals(1, (obj["version"] as JsonPrimitive).content.toInt())
        assertNotNull(obj["checks"])
        val checkArray = obj["checks"]!!.jsonArray
        assertTrue(checkArray.size >= 4, "expected >= 4 checks in JSON; got ${checkArray.size}")

        // Every check object has the required keys.
        for (el in checkArray) {
            val co = el.jsonObject
            assertNotNull(co["check"], "missing 'check' key in $co")
            assertNotNull(co["severity"], "missing 'severity' key in $co")
            assertNotNull(co["summary"], "missing 'summary' key in $co")
            assertNotNull(co["detail"], "missing 'detail' key in $co")
            // Severity is one of the lowercase enum names.
            val sev = (co["severity"] as JsonPrimitive).content
            assertTrue(sev in setOf("ok", "warn", "err"), "unexpected severity '$sev'")
        }
    }

    // ── Effective scope check surfaces UD-256 state ───────────────────────

    @Test
    fun `effective-scope check reports the persisted scope when set`() {
        seedDb { db ->
            db.setSyncState("effective_scope", "/Documents/Projects")
            db.setSyncState("last_full_scan", Instant.parse("2026-05-17T08:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
        }
        val checks = runDoctor()
        val scope = result(checks, "effective-scope")
        assertEquals(DoctorCommand.Severity.OK, scope.severity, "scope info is always OK")
        assertTrue(
            scope.summary.contains("/Documents/Projects"),
            "expected scope path in summary; got '${scope.summary}'",
        )
    }

    // ── Lock liveness — stale PID ─────────────────────────────────────────

    @Test
    fun `stale lock pid triggers lock-liveness WARN`() {
        // PID 1 is unlikely to be a running JVM under our user on most hosts;
        // but to make this test deterministic across platforms we use a PID
        // that's astronomically unlikely to exist (max-uint32-ish).
        Files.writeString(profileDir.resolve(".lock.pid"), "4294967290")
        seedDb { db ->
            db.setSyncState("last_full_scan", Instant.parse("2026-05-17T08:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
        }
        val checks = runDoctor()
        val lock = result(checks, "lock-liveness")
        assertEquals(DoctorCommand.Severity.WARN, lock.severity)
        assertTrue(
            lock.summary.contains("stale"),
            "expected 'stale' in summary; got '${lock.summary}'",
        )
    }

    // ── Hydration drift — 60 missing files trips WARN ─────────────────────

    @Test
    fun `60 missing hydrated files trips hydration-drift WARN`() {
        seedDb { db ->
            db.setSyncState("last_full_scan", Instant.parse("2026-05-17T08:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
            // 60 hydrated rows with no matching file on disk → > 50 → WARN.
            repeat(60) { i ->
                db.upsertEntry(
                    SyncEntry(
                        path = "/missing-$i.bin",
                        remoteId = "r$i",
                        remoteHash = "h$i",
                        remoteSize = 100,
                        remoteModified = Instant.now(),
                        localMtime = 0L,
                        localSize = 100,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = Instant.now(),
                    ),
                )
            }
        }
        val checks = runDoctor()
        val drift = result(checks, "hydration-drift")
        assertEquals(DoctorCommand.Severity.WARN, drift.severity)
        assertTrue(
            drift.summary.contains("60 of 60"),
            "expected '60 of 60' in summary; got '${drift.summary}'",
        )
    }

    // ── Doctor is read-only — sync_state must be untouched after a run ────

    @Test
    fun `doctor does not mutate sync_state`() {
        seedDb { db ->
            db.setSyncState("last_full_scan", Instant.parse("2026-04-01T12:00:00Z").toString())
            db.setSyncState("pending_cursor_complete", "true")
            db.setSyncState("quota_fetched_at", Instant.parse("2026-05-17T08:00:00Z").toString())
            db.setSyncState("test_canary", "before")
        }
        runDoctor()
        // Re-open and confirm canary is intact + no extra keys appeared.
        val db = StateDatabase(dbPath)
        try {
            db.initialize()
            assertEquals("before", db.getSyncState("test_canary"), "doctor must not touch sync_state")
            // Doctor must never write a new "doctor_*" or similar key.
            assertFalse(db.getSyncState("doctor_last_run") != null, "doctor must not write its own state")
        } finally {
            db.close()
        }
    }
}
