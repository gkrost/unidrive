package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawSyncConfig
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Shared data classes for table rendering
//
// UD-756: dropped the FILES column and split LOCAL into HYDRATED + PENDING.
// FILES (raw row count) was the cheapest column to drop while still keeping
// the table within ~110 chars after adding a column. The two new columns
// answer different questions:
//   - HYDRATED — bytes downloaded from the cloud and physically on disk.
//   - PENDING  — bytes added locally that haven't been uploaded yet
//                (entries with remoteId=null + isHydrated=true, written by
//                LocalScanner.scan() per UD-901).
data class AccountRow(
    val profileName: String,
    val status: String, // "ok", "err", "auth", "dim"
    val statusLabel: String, // "[✔ OK]", "[✘ ERR]", "[✘ AUTH]", "[– –]"
    val sparse: Int,
    val cloudSize: String,
    val hydratedSize: String,
    val pendingSize: String,
    val lastSync: String,
)

data class ProviderGroup(
    val type: String,
    val displayName: String,
    val accounts: List<AccountRow>,
)

/**
 * UD-756: byte-bucket split of state.db entries used by the status table.
 *
 *  - hydrated: bytes downloaded from the cloud and physically on disk
 *              (entries with `remoteId != null && isHydrated == true`).
 *  - pending:  bytes added locally that have not yet been uploaded
 *              (entries with `remoteId == null && isHydrated == true`,
 *              written by LocalScanner.scan() per UD-901).
 *
 * Folders and not-hydrated entries (sparse placeholders) contribute zero
 * to either bucket.
 */
internal data class LocalSizeBuckets(
    val hydratedBytes: Long,
    val pendingBytes: Long,
)

/**
 * Summary counts read directly from a `tracking.db` (tracking-set engine) using
 * plain JDBC — no compile-time dependency on `:app:sync-tracking` required.
 *
 *  - [total]   total entries in the tracking set (any state)
 *  - [synced]  entries in state `TrackedSynced` (both sides agree with snapshot)
 *  - [pending] entries in any `Pending*` state (in-flight transfers/deletes)
 *  - [cloudBytes]  sum of `remote_size` for non-null remote_file_id rows
 *  - [localBytes]  sum of `local_size` for rows with non-null local_size
 */
internal data class TrackingDbCounts(
    val total: Int,
    val synced: Int,
    val pending: Int,
    val cloudBytes: Long,
    val localBytes: Long,
)

/**
 * Read [TrackingDbCounts] from a `tracking.db` at [dbPath] via plain JDBC.
 * Returns null when the file does not exist, is not a valid SQLite database,
 * or does not have the expected `tracking_entries` table.
 *
 * Using plain JDBC (sqlite-jdbc is on the runtime classpath via `:app:sync`)
 * avoids a compile-time dependency on `:app:sync-tracking`, which itself
 * depends on `:app:cli` — that cycle is broken by keeping this call raw.
 */
internal fun readTrackingDbCounts(dbPath: Path): TrackingDbCounts? {
    if (!Files.exists(dbPath)) return null
    return try {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            var total = 0
            var synced = 0
            var pending = 0
            var cloudBytes = 0L
            var localBytes = 0L
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT state, COUNT(*), " +
                        "SUM(CASE WHEN remote_file_id IS NOT NULL THEN COALESCE(remote_size,0) ELSE 0 END), " +
                        "SUM(COALESCE(local_size,0)) " +
                        "FROM tracking_entries GROUP BY state",
                ).use { rs ->
                    while (rs.next()) {
                        val state = rs.getString(1) ?: continue
                        val count = rs.getInt(2)
                        val cloud = rs.getLong(3)
                        val local = rs.getLong(4)
                        total += count
                        cloudBytes += cloud
                        localBytes += local
                        if (state == "TrackedSynced") synced += count
                        if (state.startsWith("Pending")) pending += count
                    }
                }
            }
            TrackingDbCounts(total, synced, pending, cloudBytes, localBytes)
        }
    } catch (_: Exception) {
        null
    }
}

internal fun computeLocalSizeBuckets(entries: List<org.krost.unidrive.sync.model.SyncEntry>): LocalSizeBuckets {
    var hydrated = 0L
    var pending = 0L
    for (e in entries) {
        if (e.isFolder || !e.isHydrated) continue
        val size = e.localSize ?: 0L
        if (e.remoteId != null) hydrated += size else pending += size
    }
    return LocalSizeBuckets(hydrated, pending)
}

@Command(name = "status", description = ["Show sync status"], mixinStandardHelpOptions = true)
class StatusCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["-a", "--all"], description = ["Show status for all configured providers and accounts"])
    var all: Boolean = false

    @Option(names = ["--check-auth"], description = ["Check credential health for configured profiles (offline, no network calls)"])
    var checkAuth: Boolean = false

    @Option(names = ["--audit"], description = ["Cross-check state.db against remote quota and surface the enumeration gap (UD-316)"])
    var audit: Boolean = false

    @Option(
        names = ["--pending"],
        description = [
            "Print the count of pending transfers (downloads + uploads) that a follow-up `unidrive apply`",
            "would drain. Reads state.db only — no network calls. Useful as a no-op preview.",
        ],
    )
    var pending: Boolean = false

    override fun run() {
        if (checkAuth) {
            showCredentialHealthReport()
            return
        }
        if (audit) {
            showAuditReport()
            return
        }
        if (pending) {
            showPendingReport()
            return
        }
        if (all) {
            showMultiProviderStatus()
        } else {
            showSingleProviderStatus()
        }
    }

    /**
     * Report pending transfers without fetching anything from the network.
     * Walks state.db for the same row patterns the Reconciler's UD-225 / UD-901
     * recovery loops surface as DownloadContent / Upload actions:
     *   - `isHydrated = false` non-folder rows → download pending
     *   - `remoteId IS NULL` rows  → upload pending (UD-901 pre-write)
     */
    private fun showPendingReport() {
        val profile = parent.resolveCurrentProfile()
        val configDir = parent.configBaseDir().resolve(profile.name)
        val stateDbPath = configDir.resolve("state.db")
        if (!Files.exists(stateDbPath)) {
            println("No state.db for profile '${profile.name}' — nothing pending.")
            return
        }
        val db = StateDatabase(stateDbPath)
        var downloadsPending = 0
        var uploadsPending = 0
        var downloadBytes = 0L
        var uploadBytes = 0L
        try {
            db.initialize()
            for (entry in db.getAllEntries()) {
                if (entry.isFolder) continue
                if (!entry.isHydrated) {
                    downloadsPending++
                    downloadBytes += entry.remoteSize
                } else if (entry.remoteId == null) {
                    uploadsPending++
                    uploadBytes += entry.localSize ?: 0L
                }
            }
        } finally {
            db.close()
        }
        val total = downloadsPending + uploadsPending
        if (total == 0) {
            println("No pending transfers for profile '${profile.name}'. (state.db has no UD-225/UD-901 markers — nothing for `apply` to do.)")
            return
        }
        println("Pending transfers for ${AnsiHelper.bold(profile.name)}:")
        if (downloadsPending > 0) {
            println("  Download: $downloadsPending file(s), ${CliProgressReporter.formatSize(downloadBytes)}")
        }
        if (uploadsPending > 0) {
            println("  Upload:   $uploadsPending file(s), ${CliProgressReporter.formatSize(uploadBytes)}")
        }
        println("Run `unidrive -p ${profile.name} apply` to drain.")
    }

    private fun showAuditReport() {
        val profile = parent.resolveCurrentProfile()
        val baseDir = parent.configBaseDir()
        val configDir = baseDir.resolve(profile.name)
        val provider = parent.createProvider()
        val report =
            try {
                buildAuditReport(profile, configDir, provider)
            } catch (e: AuthenticationException) {
                parent.handleAuthError(e, provider)
                return
            }
        println(StatusAudit.formatAuditReport(report))
    }

    private fun buildAuditReport(
        profile: ProfileInfo,
        configDir: Path,
        provider: CloudProvider,
    ): AuditReport {
        // Local state aggregates (no writes).
        var trackedFiles = 0
        var trackedBytes = 0L
        var nonHydrated = 0
        var lastFullScan: String? = null
        var pendingCursor: String? = null

        val stateDbPath = configDir.resolve("state.db")
        if (Files.exists(stateDbPath)) {
            val db = StateDatabase(stateDbPath)
            try {
                db.initialize()
                val entries = db.getAllEntries()
                val files = entries.filter { !it.isFolder }
                trackedFiles = files.size
                trackedBytes = files.sumOf { it.remoteSize }
                nonHydrated = files.count { !it.isHydrated }
                lastFullScan = db.getSyncState("last_full_scan")
                pendingCursor = db.getSyncState("pending_cursor")
            } finally {
                db.close()
            }
        }

        // Remote quota — only fetched when the provider declares QuotaExact so
        // non-quota providers (S3, SFTP, WebDAV) do not trigger a misleading
        // "0 B used" line or a pointless round-trip.
        val quota =
            if (Capability.QuotaExact in provider.capabilities()) {
                runBlocking {
                    provider.authenticateAndLog()
                    provider.quota()
                }
            } else {
                null
            }

        val extraFields = provider.statusFields()

        return AuditReport(
            profileName = profile.name,
            providerDisplayName = providerDisplayName(profile.type),
            quota = quota,
            trackedFiles = trackedFiles,
            trackedBytes = trackedBytes,
            nonHydrated = nonHydrated,
            pendingCursor = pendingCursor,
            lastFullScan = lastFullScan,
            extraFields = extraFields,
        )
    }

    private fun showSingleProviderStatus() {
        val baseDir = parent.configBaseDir()
        val requestedName = parent.provider ?: SyncConfig.resolveDefaultProfile(baseDir)
        val configFile = baseDir.resolve("config.toml")
        val raw =
            if (Files.exists(configFile)) SyncConfig.parseRaw(Files.readString(configFile))
            else SyncConfig.parseRaw("[general]\n")
        val profile =
            if (resolvesSingleProfileViaConfig(requestedName, raw)) {
                // Name resolves to a configured / type-resolved profile — use the
                // full resolution path so side-effects (MDC stamp, memoisation,
                // error formatting) are unchanged.  This is the pre-#117 path and
                // must run FIRST (PR #196 review fix).
                parent.resolveCurrentProfile()
            } else {
                // No configured profile matched: look for an orphan dir with that
                // exact name (#117 addition). If none exists either, let
                // resolveCurrentProfile handle the error surface (exits with code 1).
                discoverProfiles(baseDir).find { it.name == requestedName && it.isOrphan }
                    ?: parent.resolveCurrentProfile()
            }
        val configDir = baseDir.resolve(profile.name)
        val row = buildAccountRow(profile, baseDir, configDir, fetchQuotaUsedIfSupported(profile, configDir))
        val group = ProviderGroup(profile.type, providerDisplayName(profile.type), listOf(row))
        val ansi = AnsiHelper.isAnsiSupported()
        renderTable(listOf(group), ansi)
    }

    private fun showMultiProviderStatus() {
        val baseDir = parent.configBaseDir()
        val ansi = AnsiHelper.isAnsiSupported()
        val profiles = discoverProfiles(baseDir)
        val accountsByType = mutableMapOf<String, MutableList<AccountRow>>()

        for (profile in profiles) {
            val configDir = baseDir.resolve(profile.name)
            val row = buildAccountRow(profile, baseDir, configDir, fetchQuotaUsedIfSupported(profile, configDir))
            accountsByType.getOrPut(profile.type) { mutableListOf() }.add(row)
        }

        val groups =
            accountsByType
                .map { (type, accounts) ->
                    val sorted = accounts.sortedWith(compareBy<AccountRow> { it.status != "ok" }.thenBy { it.profileName })
                    ProviderGroup(type, providerDisplayName(type), sorted)
                }.sortedBy { it.displayName }

        renderTable(groups, ansi)
    }

    private fun showCredentialHealthReport() {
        val baseDir = parent.configBaseDir()
        val profiles = discoverProfiles(baseDir)
        if (profiles.isEmpty()) {
            println("No configured profiles found.")
            return
        }

        println("Credential health check (offline — no network calls):")
        println()
        var hasIssues = false
        for (profile in profiles) {
            val configDir = baseDir.resolve(profile.name)
            val displayName = profileDisplayLabel(profile)
            val health = parent.checkCredentialHealth(profile, configDir)
            val (icon, message) =
                when (health) {
                    is CredentialHealth.Ok -> GlyphRenderer.tick() to "OK"
                    is CredentialHealth.Warning -> GlyphRenderer.warn() to health.message
                    is CredentialHealth.Missing -> GlyphRenderer.cross() to health.message
                    is CredentialHealth.ExpiresIn -> GlyphRenderer.warn() to health.message
                }
            val line = "  $icon  $displayName: $message"
            when (health) {
                is CredentialHealth.Ok -> println(line)
                is CredentialHealth.Warning -> {
                    hasIssues = true
                    System.err.println(line)
                }
                is CredentialHealth.Missing -> {
                    hasIssues = true
                    System.err.println(line)
                }
                is CredentialHealth.ExpiresIn -> {
                    hasIssues = true
                    System.err.println(line)
                }
            }
        }
        if (hasIssues) {
            println()
            println("Tip: run 'unidrive -p <profile> auth' to fix credential issues.")
        }
    }

    /**
     * UD-261: fetch the authoritative quota.used for providers that declare
     * Capability.QuotaExact. Returns null when the provider doesn't expose a
     * quota (S3, SFTP, WebDAV-without-RFC-4331), or when authentication /
     * network fails — caller falls back to the enumerated remoteSize sum.
     *
     * Pre-fix `buildAccountRow` summed `entries.filter { !it.isFolder }
     * .sumOf { it.remoteSize }` which always under-reports OneDrive against
     * the real quota: enumerated DriveItem sizes don't include revision
     * history, recycle bin, or OneNote metadata. Field-observed delta on
     * `onedrive-test`: 164 GB enumerated vs 349 GB real (~46 % shortfall).
     *
     * Side-effect contract: status is a read-only diagnostic. We never call
     * `provider.authenticateAndLog()` against a profile whose offline health
     * check isn't `Ok` — for Internxt that path would prompt for email /
     * password / 2FA mid-render, which is the wrong UX for a status query.
     * Interactive auth happens via `unidrive auth`; here a non-Ok health
     * just returns null and the caller renders the cached state with the
     * stale glyph from [buildAccountRow].
     */
    private fun fetchQuotaUsedIfSupported(
        profile: ProfileInfo,
        configDir: Path,
    ): Long? {
        if (!shouldProbeRemoteForStatus(parent.checkCredentialHealth(profile, configDir))) return null
        return try {
            val provider = parent.createProviderFor(profile, configDir)
            if (Capability.QuotaExact !in provider.capabilities()) return null
            runBlocking {
                provider.authenticateAndLog()
                if (!provider.isAuthenticated) return@runBlocking null
                provider.quota().used
            }
        } catch (_: Exception) {
            // Auth-fail / network-fail / provider-not-configured — caller
            // falls back to enumerated sum which is at least non-null.
            null
        }
    }

    private fun buildAccountRow(
        profile: ProfileInfo,
        baseDir: Path,
        configDir: Path,
        // UD-261: pre-fetched authoritative quota.used for QuotaExact providers.
        // null → fall back to the enumerated remoteSize sum (the pre-fix path).
        quotaUsed: Long? = null,
    ): AccountRow {
        val stateDbPath = configDir.resolve("state.db")
        val hasDb = Files.exists(stateDbPath)
        // UD-218: always show the profile name. The previous "default" alias for profiles named
        // identically to their type was ambiguous once a second profile of the same type existed
        // (observed on a 2-OneDrive-account setup: the `onedrive` profile showed as `default`
        // next to `onedrive-test`).
        val label = profile.name

        // #117: orphan profiles (dir on disk, no config.toml declaration) are
        // surfaced with an explicit [ORPHAN] marker. They have no provider type
        // to authenticate against; we read state.db if it exists so we can show
        // whatever was synced before the profile lost its config.toml section.
        if (profile.isOrphan) {
            return buildOrphanAccountRow(label, stateDbPath, hasDb)
        }

        // Tracking-set engine detection: if tracking.db is present the profile
        // is managed by the TS engine. We read real counts from tracking.db via
        // plain JDBC (no compile-time dep on :app:sync-tracking needed) and
        // surface them with a [TS] status label.  When both tracking.db AND
        // state.db exist, tracking.db takes precedence — the TS engine is
        // explicitly designed to co-exist with state.db, and showing TS figures
        // is more accurate for a profile that has switched engines.
        //
        // PR #198 P2: do NOT bypass the auth/stale health check — pass the
        // offline credential health through so a TS profile with missing or
        // stale credentials renders the auth problem instead of a blanket [TS].
        val trackingDbPath = configDir.resolve("tracking.db")
        val tsCounts = readTrackingDbCounts(trackingDbPath)
        if (tsCounts != null) {
            val tsHealth = parent.checkCredentialHealth(profile, configDir)
            return buildTrackingSetAccountRow(label, tsCounts, tsHealth)
        }

        val health = parent.checkCredentialHealth(profile, configDir)
        val effectiveCanAuth =
            health is org.krost.unidrive.CredentialHealth.Ok ||
                health is org.krost.unidrive.CredentialHealth.ExpiresIn
        val isExpiring = health is org.krost.unidrive.CredentialHealth.ExpiresIn

        if (!hasDb && !effectiveCanAuth) {
            return AccountRow(
                profileName = label,
                status = "dim",
                statusLabel = GlyphRenderer.inactiveLabel(),
                sparse = 0,
                cloudSize = "0 B",
                hydratedSize = "0 B",
                pendingSize = "0 B",
                lastSync = "never",
            )
        }

        if (!hasDb && effectiveCanAuth) {
            // ExpiresIn means the persisted token is past its expiry: render
            // the stale glyph so the user sees `[⚠ STALE]` and knows to run
            // `unidrive auth`. We deliberately don't echo the full expiry
            // message into the STATUS column — the column is 10 chars wide,
            // long messages truncate, and the BACKLOG entry's worked example
            // calls out `⚠︎ STALE` as the rendering. The full expiry
            // message lives in `health.message` for `--check-auth` output.
            val statusLabel = if (isExpiring) GlyphRenderer.warnLabel("STALE") else GlyphRenderer.okLabel()
            return AccountRow(
                profileName = label,
                status = if (isExpiring) "warn" else "ok",
                statusLabel = statusLabel,
                sparse = 0,
                cloudSize = "0 B",
                hydratedSize = "0 B",
                pendingSize = "0 B",
                lastSync = "never",
            )
        }

        try {
            val db = StateDatabase(stateDbPath)
            db.initialize()
            val entries = db.getAllEntries()
            val sparseCount = entries.count { !it.isHydrated && !it.isFolder }
            val totalRemoteSize = entries.filter { !it.isFolder }.sumOf { it.remoteSize }
            // UD-261: prefer the authoritative quota.used over the enumerated
            // sum for QuotaExact providers. quotaUsed is null when the provider
            // doesn't support quota or the auth/network call failed.
            val cloudSizeBytes = quotaUsed ?: totalRemoteSize
            // UD-756: split the historical "totalLocalSize" into HYDRATED (bytes
            // present locally that also exist remotely) and PENDING (bytes
            // present locally but not yet uploaded — UD-901 places these rows
            // with remoteId=null + isHydrated=true).
            val buckets = computeLocalSizeBuckets(entries)
            val hydratedBytes = buckets.hydratedBytes
            val pendingBytes = buckets.pendingBytes
            val lastSyncRaw = db.getSyncState("last_full_scan")
            db.close()
            val lastSyncFormatted = formatLastSync(lastSyncRaw)

            if (!effectiveCanAuth) {
                return AccountRow(
                    profileName = label,
                    status = "auth",
                    statusLabel = GlyphRenderer.authFailLabel(),
                    sparse = sparseCount,
                    cloudSize = CliProgressReporter.formatSize(cloudSizeBytes),
                    hydratedSize = CliProgressReporter.formatSize(hydratedBytes),
                    pendingSize = CliProgressReporter.formatSize(pendingBytes),
                    lastSync = lastSyncFormatted,
                )
            } else {
                // #232: a live `sync --watch` daemon refreshes last_full_scan
                // every poll, so an expiring token whose last scan is recent
                // renders OK (the daemon is refreshing it), not STALE. See
                // legacyRowStatus / STALE_GRACE; #157 (STALE on genuinely-stale
                // auth, i.e. no recent scan) is preserved.
                // #243 review: scale the STALE grace to the profile's configured
                // max_poll_interval so a slow-polling but healthy daemon isn't flagged.
                val (rowStatus, statusLabel) =
                    legacyRowStatus(isExpiring, lastSyncRaw, Instant.now(), staleGraceFor(baseDir))
                return AccountRow(
                    profileName = label,
                    status = rowStatus,
                    statusLabel = statusLabel,
                    sparse = sparseCount,
                    cloudSize = CliProgressReporter.formatSize(cloudSizeBytes),
                    hydratedSize = CliProgressReporter.formatSize(hydratedBytes),
                    pendingSize = CliProgressReporter.formatSize(pendingBytes),
                    lastSync = lastSyncFormatted,
                )
            }
        } catch (_: Exception) {
            return AccountRow(
                profileName = label,
                status = "err",
                statusLabel = GlyphRenderer.errLabel(),
                sparse = 0,
                cloudSize = "0 B",
                hydratedSize = "0 B",
                pendingSize = "0 B",
                lastSync = "unknown",
            )
        }
    }

    /**
     * Build an [AccountRow] for an orphan profile — a directory present on disk
     * with no matching `[providers.*]` config.toml section. We read state.db if
     * it exists (to surface whatever was previously synced), otherwise zeros.
     * The STATUS column always shows `[? ORPHAN]` regardless of what state.db
     * contains, because there is no provider type to authenticate or verify.
     */
    private fun buildOrphanAccountRow(
        label: String,
        stateDbPath: Path,
        hasDb: Boolean,
    ): AccountRow {
        if (!hasDb) {
            return AccountRow(
                profileName = label,
                status = "orphan",
                statusLabel = GlyphRenderer.orphanLabel(),
                sparse = 0,
                cloudSize = "0 B",
                hydratedSize = "0 B",
                pendingSize = "0 B",
                lastSync = "never",
            )
        }
        return try {
            val db = StateDatabase(stateDbPath)
            db.initialize()
            val entries = db.getAllEntries()
            val sparseCount = entries.count { !it.isHydrated && !it.isFolder }
            val totalRemoteSize = entries.filter { !it.isFolder }.sumOf { it.remoteSize }
            val buckets = computeLocalSizeBuckets(entries)
            val lastSyncRaw = db.getSyncState("last_full_scan")
            db.close()
            AccountRow(
                profileName = label,
                status = "orphan",
                statusLabel = GlyphRenderer.orphanLabel(),
                sparse = sparseCount,
                cloudSize = CliProgressReporter.formatSize(totalRemoteSize),
                hydratedSize = CliProgressReporter.formatSize(buckets.hydratedBytes),
                pendingSize = CliProgressReporter.formatSize(buckets.pendingBytes),
                lastSync = formatLastSync(lastSyncRaw),
            )
        } catch (_: Exception) {
            AccountRow(
                profileName = label,
                status = "orphan",
                statusLabel = GlyphRenderer.orphanLabel(),
                sparse = 0,
                cloudSize = "0 B",
                hydratedSize = "0 B",
                pendingSize = "0 B",
                lastSync = "unknown",
            )
        }
    }

    /**
     * Build an [AccountRow] for a tracking-set-managed profile.
     *
     * The STATUS column shows `[TS]` when credentials are healthy, signalling
     * that the figures come from `tracking.db` (the tracking-set engine), not
     * `state.db`. When [health] is not [CredentialHealth.Ok], the STATUS column
     * instead reflects the auth problem (stale/missing/warn) using the same
     * label logic as the legacy path — so a TS profile whose credentials are
     * broken does not falsely render as healthy. The TS counts (CLOUD, HYDRATED,
     * PENDING, LAST-SYNC) are always preserved from `tracking.db` regardless of
     * auth health.
     *
     * Operators wanting state-machine breakdowns should run `unidrive ts status`.
     */
    private fun buildTrackingSetAccountRow(
        label: String,
        counts: TrackingDbCounts,
        health: CredentialHealth,
    ): AccountRow {
        val (status, statusLabel) = trackingSetRowStatus(health)
        return AccountRow(
            profileName = label,
            status = status,
            statusLabel = statusLabel,
            sparse = 0,
            cloudSize = CliProgressReporter.formatSize(counts.cloudBytes),
            hydratedSize = CliProgressReporter.formatSize(counts.localBytes),
            pendingSize = if (counts.pending > 0) counts.pending.toString() else "0 B",
            lastSync = "ts:${counts.synced}/${counts.total}",
        )
    }

    private fun renderTable(
        groups: List<ProviderGroup>,
        ansi: Boolean,
    ) {
        // Column widths.
        // UD-756: dropped FILES (was 11) and added two size columns. Total inner
        // width grew from 95 → 102; with separators the table renders at ~110
        // chars (was ~103). FILES is the cheapest column to drop because the
        // sparse + hydrated + pending rows together already give the user a
        // truer picture of what state.db holds.
        val wName = 38
        val wStatus = 10
        val wSparse = 10
        val wCloud = 10
        val wHydrated = 10
        val wPending = 10
        val wSync = 14

        // Box-drawing helpers (ASCII-safe via GlyphRenderer for non-UTF-8 stdout)
        val h = GlyphRenderer.boxHorizontal()
        val v = GlyphRenderer.boxVertical()
        val tl = GlyphRenderer.boxTopLeft()
        val tr = GlyphRenderer.boxTopRight()
        val bl = GlyphRenderer.boxBottomLeft()
        val br = GlyphRenderer.boxBottomRight()
        val td = GlyphRenderer.boxTeeDown()
        val tu = GlyphRenderer.boxTeeUp()
        val teeR = GlyphRenderer.boxTeeRight()
        val teeL = GlyphRenderer.boxTeeLeft()
        val x = GlyphRenderer.boxCross()

        fun hLine(w: Int) = h.repeat(w)
        val topBorder = "$tl${hLine(
            wName,
        )}$td${hLine(
            wStatus,
        )}$td${hLine(wSparse)}$td${hLine(wCloud)}$td${hLine(wHydrated)}$td${hLine(wPending)}$td${hLine(wSync)}$tr"
        val midBorder = "$teeR${hLine(
            wName,
        )}$x${hLine(
            wStatus,
        )}$x${hLine(wSparse)}$x${hLine(wCloud)}$x${hLine(wHydrated)}$x${hLine(wPending)}$x${hLine(wSync)}$teeL"
        val botBorder = "$bl${hLine(
            wName,
        )}$tu${hLine(
            wStatus,
        )}$tu${hLine(wSparse)}$tu${hLine(wCloud)}$tu${hLine(wHydrated)}$tu${hLine(wPending)}$tu${hLine(wSync)}$br"

        fun cell(
            text: String,
            width: Int,
            rightAlign: Boolean = false,
        ): String {
            val truncated = if (text.length > width - 2) text.take(width - 2) else text
            return if (rightAlign) {
                " ${truncated.padStart(width - 2)} "
            } else {
                " ${truncated.padEnd(width - 2)} "
            }
        }

        fun row(
            name: String,
            status: String,
            sparse: String,
            cloud: String,
            hydrated: String,
            pending: String,
            sync: String,
        ): String =
            "$v${cell(
                name,
                wName,
            )}$v${cell(
                status,
                wStatus,
            )}$v${cell(
                sparse,
                wSparse,
                true,
            )}$v${cell(cloud, wCloud, true)}$v${cell(hydrated, wHydrated, true)}$v${cell(pending, wPending, true)}$v${cell(sync, wSync)}$v"

        fun colorize(
            line: String,
            color: String,
        ): String =
            when {
                !ansi -> line
                color == "green" -> AnsiHelper.green(line)
                color == "yellow" -> AnsiHelper.yellow(line)
                color == "dim" -> AnsiHelper.dim(line)
                else -> line
            }

        // Header
        println(topBorder)
        println(row("STORAGE PROVIDER / ACCOUNT", "STATUS", "SPARSE", "CLOUD", "HYDRATED", "PENDING", "LAST SYNC"))
        println(midBorder)

        // Render groups
        for ((gi, group) in groups.withIndex()) {
            // "ts" is treated as healthy (renders green) for group-level indicators:
            // the profile is actively managed by the tracking-set engine.
            val anyOk = group.accounts.any { it.status == "ok" || it.status == "ts" }
            val icon = if (anyOk) GlyphRenderer.cloud() else GlyphRenderer.warn()
            val allError = group.accounts.all { it.status != "ok" && it.status != "ts" }

            // Single-account provider with error: inline status on provider row
            if (group.accounts.size == 1 && allError) {
                val acct = group.accounts[0]
                val headerColor = if (acct.status == "dim") "dim" else "yellow"
                val provLine =
                    row(
                        "$icon ${group.displayName}",
                        acct.statusLabel,
                        "%,d".format(acct.sparse),
                        acct.cloudSize,
                        acct.hydratedSize,
                        acct.pendingSize,
                        acct.lastSync,
                    )
                println(colorize(provLine, headerColor))
            } else {
                // Provider header (empty data cells)
                val headerIcon = if (anyOk) GlyphRenderer.cloud() else GlyphRenderer.warn()
                val provHeader = row("$headerIcon ${group.displayName}", "", "", "", "", "", "")
                val headerColor = if (anyOk) "" else "yellow"
                println(colorize(provHeader, headerColor))

                // Account sub-rows
                for ((ai, acct) in group.accounts.withIndex()) {
                    val isLast = ai == group.accounts.size - 1
                    val connector = if (isLast) " ${GlyphRenderer.treeLast()} " else " ${GlyphRenderer.treeBranch()} "
                    val acctColor =
                        when (acct.status) {
                            "ok", "ts" -> "green"
                            "dim" -> "dim"
                            else -> "yellow"
                        }
                    val acctLine =
                        row(
                            "$connector${acct.profileName}",
                            acct.statusLabel,
                            "%,d".format(acct.sparse),
                            acct.cloudSize,
                            acct.hydratedSize,
                            acct.pendingSize,
                            acct.lastSync,
                        )
                    println(colorize(acctLine, acctColor))
                }
            }

            // Separator between groups (mid border), bottom border after last
            if (gi < groups.size - 1) println(midBorder)
        }

        println(botBorder)
    }

    /**
     * Discover all profiles to show in --all mode. Delegates to the pure
     * [discoverProfilesFromRaw] helper for testability — the helper does not
     * read `config.toml` itself so a unit test can hand it a synthesised
     * [org.krost.unidrive.sync.RawSyncConfig].
     */
    private fun discoverProfiles(baseDir: Path): List<ProfileInfo> {
        val configFile = baseDir.resolve("config.toml")
        val raw =
            if (Files.exists(configFile)) {
                SyncConfig.parseRaw(Files.readString(configFile))
            } else {
                SyncConfig.parseRaw("[general]\n")
            }
        return discoverProfilesFromRaw(raw, baseDir)
    }

    private fun providerDisplayName(type: String): String {
        // Prefer metadata from ServiceLoader-discovered providers
        val meta = ProviderRegistry.getMetadata(type)
        if (meta != null) return meta.displayName
        // Fallback for unknown provider types
        return type.replaceFirstChar { it.uppercase() }
    }

    private fun profileDisplayLabel(profile: ProfileInfo): String {
        val base = providerDisplayName(profile.type)
        return if (profile.name != profile.type) "$base (${profile.name})" else base
    }

    companion object {
        private val SYNC_TIME_FORMAT = DateTimeFormatter.ofPattern("d MMM HH:mm")

        fun formatLastSync(raw: String?): String {
            if (raw == null) return "never"
            return try {
                val instant = Instant.parse(raw)
                SYNC_TIME_FORMAT.format(instant.atZone(ZoneId.systemDefault()))
            } catch (_: Exception) {
                raw
            }
        }
    }
}

/**
 * Pure side-effect-gate for `unidrive status`. The status command is a
 * read-only diagnostic; the network probe must never be attempted against
 * a profile whose offline credential health says the persisted token is
 * missing, stale, or otherwise won't authenticate without interaction.
 *
 * For Internxt specifically, calling `provider.authenticateAndLog()` on a
 * profile with an expired JWT drops into `authService.authenticateInteractive()`
 * which prompts for email / password / 2FA on stdin — completely the wrong
 * UX mid-status-render. Interactive auth happens via `unidrive auth`; here
 * a non-Ok health just returns null from the quota probe and the caller
 * renders the cached state with a stale glyph.
 *
 * The only health value that licenses a network round-trip is [CredentialHealth.Ok].
 * [CredentialHealth.ExpiresIn] explicitly does not — it's the "token is past
 * expiry" signal that the BACKLOG entry wants visible in the STATUS column
 * as `[⚠ STALE]`, not silently fixed-up via an interactive auth prompt.
 */
internal fun shouldProbeRemoteForStatus(health: CredentialHealth): Boolean = health is CredentialHealth.Ok

/**
 * Pure helper: derive the (status-code, statusLabel) pair for a tracking-set
 * profile row given the offline [CredentialHealth] of the profile.
 *
 * A TS profile with healthy credentials → ("ts", "[TS]").
 * A TS profile with a stale / expired token → ("warn", "[⚠ STALE]").
 * A TS profile with missing or otherwise broken credentials → ("auth", "[✘ AUTH]").
 *
 * The TS counts from `tracking.db` are always preserved by the caller regardless
 * of which status is returned — only the STATUS column changes.
 *
 * Extracted as a top-level function so tests can drive it directly without a
 * live [StatusCommand] + [Main] wiring.
 */
internal fun trackingSetRowStatus(health: CredentialHealth): Pair<String, String> =
    when {
        health is CredentialHealth.Ok -> "ts" to GlyphRenderer.trackingSetLabel()
        health is CredentialHealth.ExpiresIn -> "warn" to GlyphRenderer.warnLabel("STALE")
        else -> "auth" to GlyphRenderer.authFailLabel()
    }

// #232: grace window FLOOR — a live `sync --watch` daemon rewrites last_full_scan on
// every poll (SyncEngine promotePendingCursor / gather), so a recent timestamp means it
// is actively refreshing credentials. This is the FLOOR; effectiveStaleGrace() scales it
// up for tuned profiles. A dead daemon's timestamp ages out and STALE reappears, so #157
// is not regressed.
internal val STALE_GRACE: Duration = Duration.ofMinutes(15)

// #232 (PR #243 review): the daemon's heartbeat is its poll interval, so the grace must
// comfortably exceed the configured max_poll_interval — otherwise a healthy idle daemon
// that polls less often than 15 min is re-labelled STALE before its next poll rewrites
// last_full_scan. Floor at STALE_GRACE (so the default 300s config keeps the documented
// 15-min grace: 2×300s = 10 min < 15 min) and otherwise allow two full poll intervals.
internal fun effectiveStaleGrace(maxPollIntervalSeconds: Int): Duration =
    maxOf(STALE_GRACE, Duration.ofSeconds(maxPollIntervalSeconds.toLong()).multipliedBy(2))

/**
 * Resolve the effective STALE grace for [profileName] from the `max_poll_interval` in
 * `config.toml` under [baseDir]. Falls back to the 300s default (→ 15-min grace) when the
 * config cannot be read or the profile does not resolve.
 */
internal fun staleGraceFor(baseDir: Path): Duration {
    val maxPoll =
        runCatching {
            val cfg = baseDir.resolve("config.toml")
            val raw =
                if (Files.exists(cfg)) SyncConfig.parseRaw(Files.readString(cfg)) else SyncConfig.parseRaw("[general]\n")
            // max_poll_interval is a [general] setting; RawSyncConfig.general is non-null.
            raw.general.max_poll_interval ?: 300
        }.getOrDefault(300)
    return effectiveStaleGrace(maxPoll)
}

/**
 * Pure helper: true when [lastSyncRaw] (an ISO-8601 instant the engine writes on
 * every scan) is within [STALE_GRACE] of [now]. Absent / blank / unparseable →
 * false (not recent).
 */
internal fun lastScanIsRecent(
    lastSyncRaw: String?,
    now: Instant,
    grace: Duration = STALE_GRACE,
): Boolean {
    if (lastSyncRaw.isNullOrBlank()) return false
    val ts = runCatching { Instant.parse(lastSyncRaw) }.getOrNull() ?: return false
    return Duration.between(ts, now) < grace
}

/**
 * Pure helper: derive the (status, statusLabel) pair for a legacy state.db
 * profile row. #232: a live daemon refreshes last_full_scan every poll, so an
 * expiring token whose last scan is recent renders OK (the daemon is refreshing
 * it); an expiring token with no recent scan still renders [⚠ STALE], preserving
 * #157 (STALE on genuinely-stale auth).
 *
 * Extracted as a top-level function so tests can drive it directly without a
 * live [StatusCommand] + [Main] wiring (mirrors [trackingSetRowStatus]).
 */
internal fun legacyRowStatus(
    isExpiring: Boolean,
    lastSyncRaw: String?,
    now: Instant,
    grace: Duration = STALE_GRACE,
): Pair<String, String> {
    val showStale = isExpiring && !lastScanIsRecent(lastSyncRaw, now, grace)
    return if (showStale) "warn" to GlyphRenderer.warnLabel("STALE") else "ok" to GlyphRenderer.okLabel()
}

/**
 * Pure helper extracted from [StatusCommand.discoverProfiles] so unit tests
 * can drive it with a synthesised [RawSyncConfig] instead of a real
 * `config.toml` on disk.
 *
 * Returns the profiles to render in `status --all`, in this order:
 *   1. Every section declared under `[providers.*]`, in `config.toml`
 *      declaration order. ktoml's `Map<String, RawProvider>` is a
 *      `LinkedHashMap` so iteration matches file order — verified by the
 *      `enumerates two same-type profiles in declaration order` test.
 *      Sections whose `type` is unknown to the provider classpath are
 *      skipped silently (a private provider not bundled in a public CLI
 *      build), but still reserved in `seen` so step 2 can't double-count.
 *   2. Legacy directories under [baseDir] that hold a `state.db` AND are
 *      declared in `config.toml` but somehow missed step 1. Sorted for
 *      deterministic output; in practice this step is empty because step 1
 *      already covers everything `in raw.providers`.
 *   3. Orphan directories — dirs under [baseDir] that exist on disk but have
 *      no matching `[providers.*]` section in config.toml. These appear after
 *      declared profiles, sorted alphabetically. Each is represented as a
 *      [ProfileInfo] with `isOrphan = true`. Common after `unidrive auth`
 *      against a type-resolved name (the auth creates the dir but does not
 *      write a config.toml section). Rendered with the `[ORPHAN]` status glyph.
 *   4. If nothing was discovered at all, fall back to the registered
 *      provider types so an out-of-the-box `status --all` (no config) still
 *      shows useful rows.
 */
internal fun discoverProfilesFromRaw(
    raw: RawSyncConfig,
    baseDir: Path,
): List<ProfileInfo> {
    val seen = mutableSetOf<String>()
    val profiles = mutableListOf<ProfileInfo>()

    // 1. Configured profiles in declaration order. ktoml emits a LinkedHashMap
    //    so `raw.providers.entries` iterates in file order; we honour that so
    //    the rendered table reads top-to-bottom the same way the user wrote
    //    the file.
    for ((name, _) in raw.providers) {
        try {
            profiles.add(SyncConfig.resolveProfile(name, raw))
            seen.add(name)
        } catch (_: IllegalArgumentException) {
            // Provider type not on classpath (e.g. private provider in public CLI), skip
            seen.add(name)
        }
    }

    // 2. Legacy dirs with state.db not in config but with a config section
    if (Files.isDirectory(baseDir)) {
        Files.list(baseDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) && Files.exists(it.resolve("state.db")) }
                .map { it.fileName.toString() }
                .filter { it !in seen && it in raw.providers }
                .sorted()
                .forEach { dirName ->
                    try {
                        val profile = SyncConfig.resolveProfile(dirName, raw)
                        profiles.add(profile)
                        seen.add(dirName)
                    } catch (_: IllegalArgumentException) {
                        // Config section exists but invalid, skip
                    }
                }
        }
    }

    // 3. Orphan dirs — directories present on disk with no config.toml section.
    //    The dir itself is the only signal; we use the dirname as both name and
    //    type (best-effort display) and mark isOrphan = true. The caller renders
    //    these with the [ORPHAN] status glyph.
    if (Files.isDirectory(baseDir)) {
        Files.list(baseDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { it !in seen && it !in raw.providers }
                .sorted()
                .forEach { dirName ->
                    // Skip hidden dirs (e.g. .lock, .tmp) and the config.toml file itself
                    if (!dirName.startsWith(".")) {
                        profiles.add(
                            ProfileInfo(
                                name = dirName,
                                type = dirName,
                                syncRoot = SyncConfig.defaultSyncRoot(dirName),
                                rawProvider = null,
                                isOrphan = true,
                            ),
                        )
                        seen.add(dirName)
                    }
                }
        }
    }

    // 4. Fallback: known types not yet seen (only if NO profiles discovered)
    if (profiles.isEmpty()) {
        for (type in SyncConfig.KNOWN_TYPES.sorted()) {
            if (type !in seen) {
                profiles.add(SyncConfig.resolveProfile(type, raw))
                seen.add(type)
            }
        }
    }

    return profiles
}

/**
 * Build a [ProfileInfo] for an orphan profile directory — a directory present
 * under [baseDir] with no matching `[providers.<name>]` section in config.toml.
 *
 * Uses the directory name as both the profile name and best-guess type. If the
 * dirname happens to match a known provider type, that type is used directly
 * (this matches the behaviour of [SyncConfig.resolveProfile] for the case where
 * `name in KNOWN_TYPES`). Otherwise the dirname is used as the type label for
 * display purposes only — no real provider operations are performed.
 */
internal fun buildOrphanProfile(dirName: String): ProfileInfo =
    ProfileInfo(
        name = dirName,
        type = dirName,
        syncRoot = SyncConfig.defaultSyncRoot(dirName),
        rawProvider = null,
        isOrphan = true,
    )

/**
 * PR #196 review fix: pure predicate for the precedence gate in
 * [StatusCommand.showSingleProviderStatus].
 *
 * Returns `true` when [requestedName] resolves to a configured or type-known
 * profile via the standard config path — meaning [Main.resolveCurrentProfile]
 * should be called first (not the orphan-dir fallback).  Specifically:
 *
 *   - [requestedName] appears as a key in [raw.providers], OR
 *   - [requestedName] is a known provider type (so [SyncConfig.resolveProfile]
 *     would construct a synthetic no-credentials profile rather than throw), OR
 *   - exactly one profile in [raw] has the given type
 *     ([tryResolveByType] would succeed via the catch branch in
 *     [Main.resolveCurrentProfile]).
 *
 * Returns `false` only when the name is genuinely absent from all config paths
 * (no section, no type match, no single-type disambiguation) — in that case
 * the caller may look for an orphan directory with that name (#117 addition).
 *
 * The distinction matters: an orphan dir named `onedrive` with a configured
 * `[providers.personal] type = "onedrive"` must NOT render as `[? ORPHAN]` —
 * `resolvesSingleProfileViaConfig` returns `true` here so the configured path
 * is taken instead.
 */
internal fun resolvesSingleProfileViaConfig(
    requestedName: String,
    raw: org.krost.unidrive.sync.RawSyncConfig,
): Boolean {
    // Fast path 1: explicit section in config.toml
    if (requestedName in raw.providers) return true
    // Fast path 2: requestedName IS a known provider type (e.g. "onedrive",
    // "internxt") — SyncConfig.resolveProfile returns a synthetic profile for
    // these even when there is no matching config section.
    if (requestedName in SyncConfig.KNOWN_TYPES) return true
    // Fast path 3: type-alias disambiguation (e.g. "-p Internxt" with one
    // internxt profile configured) — mirrors the tryResolveByType catch branch
    // in Main.resolveCurrentProfile.
    if (tryResolveByType(requestedName, raw) != null) return true
    return false
}
