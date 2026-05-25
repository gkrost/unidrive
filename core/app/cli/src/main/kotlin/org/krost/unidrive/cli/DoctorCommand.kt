package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * UD-268: read-only one-shot diagnostic. Bundles the drift / staleness / health
 * checks an experienced operator would otherwise run manually with sqlite3 +
 * `find` + `grep audit-*.jsonl`. The 2026-05-17 Internxt audit reproduced this
 * end-to-end and took ~40 minutes for a single profile; doctor closes that
 * gap with a single command that completes in well under 30 s on a 200k-entry
 * profile.
 *
 * **Read-only by contract.** No `setSyncState`, no `upsertEntry`, no provider
 * calls, no network. Safe to run on a wedged or rate-limited profile. The unit
 * tests verify each check writes nothing to state.db.
 *
 * **Exit codes** reflect worst severity across all checks:
 *   - `0` — all checks OK
 *   - `1` — at least one warning
 *   - `2` — at least one error
 *
 * **v1 scope.** Seven checks land here; four from the ticket body
 * (state-vs-config drift, recent failure spike, credential health, JFR perf
 * snapshot) are deferred to a v2 ticket — they each need extra wiring
 * (SyncConfig delta, failures.jsonl reader, parent.checkCredentialHealth bind,
 * a JFR parser) that's better as its own focused commit. The four are
 * documented in the closing comment block so the next agent doesn't have to
 * re-derive them.
 */
@Command(
    name = "doctor",
    description = ["Read-only diagnostic: drift, staleness, destructive-activity, scope, hydration"],
    mixinStandardHelpOptions = true,
)
class DoctorCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--json"], description = ["Emit a structured JSON report (one object per check + a summary)"])
    var json: Boolean = false

    @Option(
        names = ["--full"],
        description = [
            "Run expensive checks at full fidelity (full-disk-walk for orphans, full-table hydration scan).",
            "Default samples up to 5000 rows / 5000 disk files instead.",
        ],
    )
    var full: Boolean = false

    override fun run() {
        // `--verbose` is parent-scoped (INHERIT) and re-used here to gate
        // per-row detail under each check (sample paths, per-day delete counts).
        // Same convention as the rest of the CLI: -v expands a summary into the
        // operator-friendly detail block.
        val detail = parent.verbose

        val profile = parent.resolveCurrentProfile()
        val profileDir = parent.providerConfigDir()
        val syncRoot = profile.syncRoot

        // PR #47 Codex P2: thread exclude_patterns through to the orphans check.
        // Mirrors SyncCommand.run wiring. Doctor is read-only and must still
        // run on a wedged config, so any error falls back to no excludes.
        val excludePatterns: List<String> =
            try {
                parent.loadSyncConfig().effectiveExcludePatterns(profile.name)
            } catch (_: Throwable) {
                emptyList()
            }

        val checks = runChecks(profileDir, syncRoot, full, excludePatterns = excludePatterns)
        if (json) {
            println(renderJson(checks))
        } else {
            println(renderText(profile.name, checks, detail))
        }

        // Exit code reflects worst severity.
        val worst = checks.maxOfOrNull { it.severity.rank } ?: 0
        if (worst > 0) System.exit(worst)
    }

    /**
     * Run every check against the profile dir + sync root. Pure (no I/O to the
     * outside world beyond the profile dir and the sync root) and returns the
     * full list of [CheckResult] for both text and JSON renderers.
     *
     * Exposed as `internal` so DoctorCommandTest can drive it with a seeded
     * temp dir, without needing to bootstrap a full Main + picocli pipeline.
     */
    internal fun runChecks(
        profileDir: Path,
        syncRoot: Path,
        full: Boolean,
        nowOverride: Instant? = null,
        excludePatterns: List<String> = emptyList(),
    ): List<CheckResult> {
        val now = nowOverride ?: Instant.now()
        val results = mutableListOf<CheckResult>()
        results += checkLockLiveness(profileDir)
        // Every later check needs a live state.db handle. We keep one open
        // across the run and close at the end so we don't pay sqlite open
        // cost per-check on a 200k-entry profile.
        val stateDb = profileDir.resolve("state.db")
        if (Files.exists(stateDb)) {
            val db = StateDatabase(stateDb)
            try {
                db.initialize()
                results += checkCursorFreshness(db, now)
                results += checkHydrationDrift(db, syncRoot, full)
                results += checkLocalOrphans(db, syncRoot, full, excludePatterns)
                results += checkEffectiveScope(db)
                results += checkQuotaFreshness(db, now)
            } finally {
                db.close()
            }
        } else {
            results += CheckResult(
                "cursor-freshness",
                Severity.WARN,
                "no state.db at $stateDb — profile has never synced",
                emptyList(),
            )
        }
        results += checkRecentDestructive(profileDir, now)
        return results
    }

    // ────────────────────────────────────────────────────────────────────────
    // Individual checks. Each returns a single CheckResult.
    // ────────────────────────────────────────────────────────────────────────

    /**
     * **Check 1 — Daemon + lock liveness.** Reads `.lock.pid` under the profile
     * dir, compares to `ProcessHandle.of(pid)`. Stale lock (PID gone) is the
     * 2026-05-17-class issue the audit found (`2420` from a previous session).
     */
    internal fun checkLockLiveness(profileDir: Path): CheckResult {
        val name = "lock-liveness"
        val lockPid = profileDir.resolve(".lock.pid")
        if (!Files.exists(lockPid)) {
            return CheckResult(name, Severity.OK, "no lock file (no daemon running)", emptyList())
        }
        val pidStr = try {
            Files.readString(lockPid).trim()
        } catch (e: Exception) {
            return CheckResult(name, Severity.WARN, "lock file unreadable: ${e.message}", emptyList())
        }
        // The `.lock.pid` sidecar carries the mode-mutex wire format
        // `<pid> <mode>` (e.g. `551057 daemon`); parse only the first
        // whitespace-separated token, mirroring ProcessLock.readHolderPid.
        // A legacy pid-only file (`<pid>\n`) still parses cleanly.
        val pid = pidStr.substringBefore(' ').toLongOrNull()
            ?: return CheckResult(name, Severity.WARN, "lock file contents not a PID: '$pidStr'", emptyList())
        val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        return if (alive) {
            CheckResult(name, Severity.OK, "daemon alive (PID $pid)", emptyList())
        } else {
            CheckResult(
                name,
                Severity.WARN,
                "stale lock — PID $pid is not running (consider removing $lockPid)",
                emptyList(),
            )
        }
    }

    /**
     * **Check 2 — Cursor freshness.** Reads `last_full_scan`, `delta_cursor`,
     * `pending_cursor`, `pending_cursor_complete`. Warns on > 7 days stale or
     * `pending_cursor_complete == "false"`. Stale cursors mean a missed-event
     * window — the engine will under-report local changes until the next full
     * scan.
     */
    internal fun checkCursorFreshness(
        db: StateDatabase,
        now: Instant,
    ): CheckResult {
        val name = "cursor-freshness"
        val lastFullScan = db.getSyncState("last_full_scan")
        val deltaCursor = db.getSyncState("delta_cursor")
        val pendingCursor = db.getSyncState("pending_cursor")
        val pendingComplete = db.getSyncState("pending_cursor_complete")
        val detail = mutableListOf<String>()
        detail += "last_full_scan = ${lastFullScan ?: "(unset)"}"
        detail += "delta_cursor = ${deltaCursor ?: "(unset)"}"
        detail += "pending_cursor = ${pendingCursor ?: "(unset)"}"
        detail += "pending_cursor_complete = ${pendingComplete ?: "(unset)"}"

        var severity = Severity.OK
        var summary = "fresh"

        if (lastFullScan != null) {
            val age = ageDaysOrNull(lastFullScan, now)
            if (age != null && age > 7) {
                severity = Severity.WARN
                summary = "last_full_scan is $age days old (> 7-day threshold)"
            }
        } else {
            severity = Severity.WARN
            summary = "last_full_scan never recorded"
        }
        if (pendingComplete == "false") {
            severity = Severity.WARN
            summary = "pending_cursor_complete=false (mid-pass interruption — re-run sync)"
        }
        return CheckResult(name, severity, summary, detail)
    }

    /**
     * **Check 3 — Hydration drift.** Sample up to 5000 rows (or all rows under
     * `--full`) with `is_hydrated=1`. For each, verify the file exists on disk
     * under `syncRoot`. The count of missing files is the seed UD-267 (the
     * dedicated hydration-drift ticket) will graduate into a sweeper. Severity:
     * `WARN` > 50, `ERR` > 1000. Sample worst 5 paths in detail.
     */
    internal fun checkHydrationDrift(
        db: StateDatabase,
        syncRoot: Path,
        full: Boolean,
    ): CheckResult {
        val name = "hydration-drift"
        val all = db.getAllEntries()
        val hydratedFiles = all.asSequence()
            .filter { !it.isFolder && it.isHydrated }
            .let { if (full) it.toList() else it.take(HYDRATION_SAMPLE).toList() }
        var missing = 0
        val sampleMissing = mutableListOf<String>()
        for (entry in hydratedFiles) {
            val rel = entry.path.trimStart('/')
            if (!Files.exists(syncRoot.resolve(rel))) {
                missing++
                if (sampleMissing.size < SAMPLE_DETAIL) sampleMissing += entry.path
            }
        }
        val scope = if (full) "full table" else "sample ${hydratedFiles.size} rows"
        val severity = when {
            missing > 1000 -> Severity.ERR
            missing > 50 -> Severity.WARN
            else -> Severity.OK
        }
        val summary = "$missing of ${hydratedFiles.size} hydrated rows missing on disk ($scope)"
        val detail = mutableListOf<String>()
        if (sampleMissing.isNotEmpty()) {
            detail += "sample missing paths:"
            sampleMissing.forEach { detail += "  $it" }
        }
        return CheckResult(name, severity, summary, detail)
    }

    /**
     * **Check 4 — Local orphans.** Walks sync root (sampled to 5000 entries by
     * default, full walk under `--full`) and checks each file path against
     * `db.getEntry(...)`. The 2026-05-17 audit found 90 orphans on the
     * Internxt profile; severity escalates at > 100. Sample 5 in detail.
     *
     * Skips well-known sidecar dirs (`.unidrive-trash`, `.unidrive-versions`)
     * that legitimately hold files not in state.db.
     */
    internal fun checkLocalOrphans(
        db: StateDatabase,
        syncRoot: Path,
        full: Boolean,
        excludePatterns: List<String> = emptyList(),
    ): CheckResult {
        val name = "local-orphans"
        if (!Files.exists(syncRoot)) {
            return CheckResult(name, Severity.WARN, "sync root does not exist: $syncRoot", emptyList())
        }
        var orphanCount = 0
        var scanned = 0
        var excluded = 0
        val sampleOrphans = mutableListOf<String>()
        val sidecarDirs = setOf(".unidrive-trash", ".unidrive-versions")
        Files.walk(syncRoot).use { stream ->
            val iter = stream.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                if (p == syncRoot) continue
                if (Files.isDirectory(p)) continue
                val rel = syncRoot.relativize(p).toString().replace('\\', '/')
                if (rel.isEmpty()) continue
                // Skip sidecar dirs
                if (sidecarDirs.any { rel == it || rel.startsWith("$it/") }) continue
                // PR #47 Codex P2: skip configured excludes (same path-key
                // shape LocalScanner.isExcluded uses: leading slash, forward
                // slashes). Without this filter a profile with
                // exclude_patterns = ["/Videos/**"] reports every excluded
                // file as an orphan and falsely trips the WARN threshold.
                val relForGlob = "/$rel"
                if (excludePatterns.any { pattern -> org.krost.unidrive.sync.Reconciler.matchesGlob(relForGlob, pattern) }) {
                    excluded++
                    continue
                }
                scanned++
                val dbPath = "/$rel"
                if (db.getEntry(dbPath) == null) {
                    orphanCount++
                    if (sampleOrphans.size < SAMPLE_DETAIL) sampleOrphans += dbPath
                }
                if (!full && scanned >= ORPHAN_SAMPLE) break
            }
        }
        val scope = if (full) "full walk" else "sample $scanned files"
        val severity = if (orphanCount > 100) Severity.WARN else Severity.OK
        val excludedNote = if (excluded > 0) " (skipped $excluded excluded files)" else ""
        val summary = "$orphanCount orphans of $scanned files scanned ($scope)$excludedNote"
        val detail = mutableListOf<String>()
        if (sampleOrphans.isNotEmpty()) {
            detail += "sample orphans (on disk, no state.db row):"
            sampleOrphans.forEach { detail += "  $it" }
        }
        return CheckResult(name, severity, summary, detail)
    }

    /**
     * **Check 5 — Effective scope (UD-256).** Print `effective_scope` (if set)
     * so the operator sees what bare bidirectional would refuse. Informational
     * `OK` always — the persisted scope itself is not a problem, the absence
     * of it under bare bidirectional is, but that's already enforced by
     * SyncEngine and surfaced at command run time.
     */
    internal fun checkEffectiveScope(db: StateDatabase): CheckResult {
        val name = "effective-scope"
        val scope = db.getSyncState("effective_scope")
        return if (scope == null) {
            CheckResult(name, Severity.OK, "no narrowed scope (full-tree sync)", emptyList())
        } else {
            CheckResult(
                name,
                Severity.OK,
                "scope = $scope (UD-256: bare bidirectional would refuse without --full-tree)",
                emptyList(),
            )
        }
    }

    /**
     * **Check 6 — Quota freshness.** Warn if `quota_fetched_at` is > 7 days old.
     * The 2026-05-17 audit found a 17-day-stale cache (681 GB used vs cached
     * 359 GB).
     */
    internal fun checkQuotaFreshness(
        db: StateDatabase,
        now: Instant,
    ): CheckResult {
        val name = "quota-freshness"
        val fetchedAt = db.getSyncState("quota_fetched_at")
        if (fetchedAt == null) {
            return CheckResult(name, Severity.WARN, "no cached quota (run `unidrive quota` to refresh)", emptyList())
        }
        val age = ageDaysOrNull(fetchedAt, now)
        return if (age != null && age > 7) {
            CheckResult(name, Severity.WARN, "quota cache is $age days old (> 7-day threshold)", listOf("fetched_at = $fetchedAt"))
        } else {
            CheckResult(name, Severity.OK, "quota cache is fresh (age=${age ?: "?"} days)", listOf("fetched_at = $fetchedAt"))
        }
    }

    /**
     * **Check 7 — Recent destructive activity.** Tails the last 7 days of
     * `audit-*.jsonl` files under the profile dir, counting `Delete` actions
     * per day. Severity: `WARN` if any day > 50, `ERR` if any day > 200. The
     * 2026-05-16 incident (405 deletes in a single day) would have surfaced
     * here on the very next session.
     *
     * Audit lines are JSON objects with `action`, `path`, `ts` (see
     * [org.krost.unidrive.sync.audit.AuditEntry]). We do shallow JSON parsing —
     * `JsonElement.jsonObject["action"]?.jsonPrimitive?.content` — rather than
     * deserializing to AuditEntry, because the schema may evolve and we only
     * care about two fields.
     */
    internal fun checkRecentDestructive(
        profileDir: Path,
        now: Instant,
    ): CheckResult {
        val name = "recent-destructive"
        if (!Files.isDirectory(profileDir)) {
            return CheckResult(name, Severity.OK, "no profile dir at $profileDir — nothing to check", emptyList())
        }
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val perDay = sortedMapOf<LocalDate, Int>()
        val sampleDeletes = mutableListOf<String>()
        val parser = Json { ignoreUnknownKeys = true; isLenient = true }

        Files.list(profileDir).use { stream ->
            stream
                .filter { it.fileName.toString().startsWith("audit-") && it.fileName.toString().endsWith(".jsonl") }
                .forEach { file ->
                    // audit-YYYY-MM-DD.jsonl
                    val dateStr = file.fileName.toString().removePrefix("audit-").removeSuffix(".jsonl")
                    val date = try {
                        LocalDate.parse(dateStr)
                    } catch (_: Exception) {
                        return@forEach
                    }
                    if (today.toEpochDay() - date.toEpochDay() > 7) return@forEach
                    try {
                        Files.newBufferedReader(file).useLines { lines ->
                            for (line in lines) {
                                if (line.isBlank()) continue
                                val obj = try {
                                    parser.parseToJsonElement(line)
                                } catch (_: Exception) {
                                    continue
                                }
                                val action = obj.fieldString("action") ?: continue
                                if (action != "Delete") continue
                                perDay.merge(date, 1) { a, b -> a + b }
                                if (sampleDeletes.size < 3) {
                                    val path = obj.fieldString("path") ?: "?"
                                    sampleDeletes += "[$date] $path"
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // unreadable audit file — skip silently; not the doctor's
                        // job to fix log-rotation issues.
                    }
                }
        }
        val worstDay = perDay.maxByOrNull { it.value }
        val severity = when {
            worstDay != null && worstDay.value > 200 -> Severity.ERR
            worstDay != null && worstDay.value > 50 -> Severity.WARN
            else -> Severity.OK
        }
        val summary = if (worstDay != null) {
            "worst day: ${worstDay.value} deletes on ${worstDay.key} " +
                "(total over ${perDay.size} day(s): ${perDay.values.sum()})"
        } else {
            "no deletes in last 7 days"
        }
        val detail = mutableListOf<String>()
        if (perDay.isNotEmpty()) {
            detail += "per-day delete counts:"
            for ((date, count) in perDay) detail += "  $date  $count"
        }
        if (sampleDeletes.isNotEmpty()) {
            detail += "sample destructive paths:"
            sampleDeletes.forEach { detail += "  $it" }
        }
        return CheckResult(name, severity, summary, detail)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Rendering.
    // ────────────────────────────────────────────────────────────────────────

    internal fun renderText(
        profileName: String,
        checks: List<CheckResult>,
        detail: Boolean,
    ): String =
        buildString {
            appendLine("unidrive doctor — profile '$profileName'")
            appendLine()
            for (c in checks) {
                val glyph = when (c.severity) {
                    Severity.OK -> "OK "
                    Severity.WARN -> "WARN"
                    Severity.ERR -> "ERR "
                }
                appendLine("[$glyph] ${c.check}: ${c.summary}")
                if (detail && c.detail.isNotEmpty()) {
                    for (line in c.detail) appendLine("       $line")
                }
            }
            appendLine()
            val warnCount = checks.count { it.severity == Severity.WARN }
            val errCount = checks.count { it.severity == Severity.ERR }
            appendLine("Summary: ${checks.size} checks, $warnCount warning(s), $errCount error(s)")
        }

    internal fun renderJson(checks: List<CheckResult>): String {
        val json = Json { prettyPrint = true }
        val element = buildJsonObject {
            put("version", JsonPrimitive(1))
            put("warn_count", JsonPrimitive(checks.count { it.severity == Severity.WARN }))
            put("err_count", JsonPrimitive(checks.count { it.severity == Severity.ERR }))
            put(
                "checks",
                buildJsonArray {
                    for (c in checks) {
                        add(
                            buildJsonObject {
                                put("check", JsonPrimitive(c.check))
                                put("severity", JsonPrimitive(c.severity.name.lowercase()))
                                put("summary", JsonPrimitive(c.summary))
                                put(
                                    "detail",
                                    buildJsonArray { c.detail.forEach { add(JsonPrimitive(it)) } },
                                )
                            },
                        )
                    }
                },
            )
        }
        return json.encodeToString(JsonElement.serializer(), element)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Internals.
    // ────────────────────────────────────────────────────────────────────────

    internal data class CheckResult(
        val check: String,
        val severity: Severity,
        val summary: String,
        val detail: List<String>,
    )

    internal enum class Severity(val rank: Int) {
        OK(0),
        WARN(1),
        ERR(2),
    }

    companion object {
        // Sample sizes balance < 30 s completion against detecting drift on a
        // 200k-entry profile. 5000 rows ≈ 2.5 % of the worst-case profile size,
        // which is enough to surface > 50-missing-files warnings (the threshold)
        // with high probability while keeping disk I/O bounded.
        internal const val HYDRATION_SAMPLE = 5_000
        internal const val ORPHAN_SAMPLE = 5_000

        // Per-check sample size for detail blocks (sample worst-N paths).
        internal const val SAMPLE_DETAIL = 5

        /**
         * Parse an ISO-8601 instant string and return age in whole days, or
         * null if the timestamp is unparseable. Robust to legacy formats with
         * trailing Z / fractional seconds.
         */
        internal fun ageDaysOrNull(
            isoTimestamp: String,
            now: Instant,
        ): Long? =
            try {
                val ts = Instant.parse(isoTimestamp)
                Duration.between(ts, now).toDays()
            } catch (_: Exception) {
                null
            }

        /** Shallow JSON field accessor — only handles flat string fields. */
        internal fun JsonElement.fieldString(key: String): String? =
            try {
                val obj = (this as? JsonObject) ?: return null
                val v = obj[key] as? JsonPrimitive ?: return null
                v.contentOrNull
            } catch (_: Exception) {
                null
            }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// DEFERRED v2 checks — moved out of this commit to keep the v1 surface
// reviewable. Each is straightforward to land as a follow-up:
//
// 8.  State-vs-config drift — compare `db.getSyncState("sync_root")` against
//     profile.syncRoot. UD-299 already detects this elsewhere; surface it
//     here in doctor.
// 9.  Recent failure spike — tail failures.jsonl in profileDir, bucket by
//     action+truncated-error, flag top-3. The 2026-05-17 sample would have
//     led with `down :: 429 1015` (861 entries).
// 10. Credential health — bind parent.checkCredentialHealth for the active
//     profile and surface the same OK/Warning/Missing/ExpiresIn result.
// 11. JFR-friendly perf snapshot — last_scan_secs_remote /
//     last_scan_count_remote → items/s. Warn if < 10 items/s
//     (likely Cloudflare 1015 rate-limited).
// ───────────────────────────────────────────────────────────────────────────
