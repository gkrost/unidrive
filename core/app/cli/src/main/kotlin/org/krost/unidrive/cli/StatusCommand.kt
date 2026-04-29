package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Shared data classes for table rendering
data class AccountRow(
    val profileName: String,
    val status: String, // "ok", "err", "auth", "dim"
    val statusLabel: String, // "[✔ OK]", "[✘ ERR]", "[✘ AUTH]", "[– –]"
    val files: Int,
    val sparse: Int,
    val cloudSize: String,
    val localSize: String,
    val lastSync: String,
)

data class ProviderGroup(
    val type: String,
    val displayName: String,
    val accounts: List<AccountRow>,
)

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

    override fun run() {
        if (checkAuth) {
            showCredentialHealthReport()
            return
        }
        if (audit) {
            showAuditReport()
            return
        }
        if (all) {
            showMultiProviderStatus()
        } else {
            showSingleProviderStatus()
        }
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
                    provider.authenticate()
                    provider.quota()
                }
            } else {
                null
            }

        val includeShared =
            if (profile.type == "onedrive") {
                profile.rawProvider?.include_shared == true
            } else {
                null
            }

        return AuditReport(
            profileName = profile.name,
            providerDisplayName = providerDisplayName(profile.type),
            quota = quota,
            trackedFiles = trackedFiles,
            trackedBytes = trackedBytes,
            nonHydrated = nonHydrated,
            pendingCursor = pendingCursor,
            lastFullScan = lastFullScan,
            includeShared = includeShared,
        )
    }

    private fun showSingleProviderStatus() {
        val profile = parent.resolveCurrentProfile()
        val baseDir = parent.configBaseDir()
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
     */
    private fun fetchQuotaUsedIfSupported(
        profile: ProfileInfo,
        configDir: Path,
    ): Long? =
        try {
            val provider = parent.createProviderFor(profile, configDir)
            if (Capability.QuotaExact !in provider.capabilities()) return null
            runBlocking {
                provider.authenticate()
                if (!provider.isAuthenticated) return@runBlocking null
                provider.quota().used
            }
        } catch (_: Exception) {
            // Auth-fail / network-fail / provider-not-configured — caller
            // falls back to enumerated sum which is at least non-null.
            null
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

        val health = parent.checkCredentialHealth(profile, configDir)
        val effectiveCanAuth =
            health is org.krost.unidrive.CredentialHealth.Ok ||
                health is org.krost.unidrive.CredentialHealth.ExpiresIn
        val isExpiring = health is org.krost.unidrive.CredentialHealth.ExpiresIn
        val expiryMessage = (health as? org.krost.unidrive.CredentialHealth.ExpiresIn)?.message

        if (!hasDb && !effectiveCanAuth) {
            return AccountRow(
                profileName = label,
                status = "dim",
                statusLabel = GlyphRenderer.inactiveLabel(),
                files = 0,
                sparse = 0,
                cloudSize = "0 B",
                localSize = "0 B",
                lastSync = "never",
            )
        }

        if (!hasDb && effectiveCanAuth) {
            val statusLabel = if (isExpiring) GlyphRenderer.warnLabel(expiryMessage ?: "expiring") else GlyphRenderer.okLabel()
            return AccountRow(
                profileName = label,
                status = if (isExpiring) "warn" else "ok",
                statusLabel = statusLabel,
                files = 0,
                sparse = 0,
                cloudSize = "0 B",
                localSize = "0 B",
                lastSync = "never",
            )
        }

        try {
            val db = StateDatabase(stateDbPath)
            db.initialize()
            val entries = db.getAllEntries()
            val fileCount = entries.count { !it.isFolder }
            val sparseCount = entries.count { !it.isHydrated && !it.isFolder }
            val totalRemoteSize = entries.filter { !it.isFolder }.sumOf { it.remoteSize }
            // UD-261: prefer the authoritative quota.used over the enumerated
            // sum for QuotaExact providers. quotaUsed is null when the provider
            // doesn't support quota or the auth/network call failed.
            val cloudSizeBytes = quotaUsed ?: totalRemoteSize
            val totalLocalSize = entries.filter { it.isHydrated }.sumOf { it.localSize ?: 0L }
            val lastSyncRaw = db.getSyncState("last_full_scan")
            db.close()
            val lastSyncFormatted = formatLastSync(lastSyncRaw)

            if (!effectiveCanAuth) {
                return AccountRow(
                    profileName = label,
                    status = "auth",
                    statusLabel = GlyphRenderer.authFailLabel(),
                    files = fileCount,
                    sparse = sparseCount,
                    cloudSize = CliProgressReporter.formatSize(cloudSizeBytes),
                    localSize = CliProgressReporter.formatSize(totalLocalSize),
                    lastSync = lastSyncFormatted,
                )
            } else {
                val statusLabel = if (isExpiring) GlyphRenderer.warnLabel(expiryMessage ?: "expiring") else GlyphRenderer.okLabel()
                return AccountRow(
                    profileName = label,
                    status = if (isExpiring) "warn" else "ok",
                    statusLabel = statusLabel,
                    files = fileCount,
                    sparse = sparseCount,
                    cloudSize = CliProgressReporter.formatSize(cloudSizeBytes),
                    localSize = CliProgressReporter.formatSize(totalLocalSize),
                    lastSync = lastSyncFormatted,
                )
            }
        } catch (_: Exception) {
            return AccountRow(
                profileName = label,
                status = "err",
                statusLabel = GlyphRenderer.errLabel(),
                files = 0,
                sparse = 0,
                cloudSize = "0 B",
                localSize = "0 B",
                lastSync = "unknown",
            )
        }
    }

    private fun renderTable(
        groups: List<ProviderGroup>,
        ansi: Boolean,
    ) {
        // Column widths
        val wName = 38
        val wStatus = 10
        val wFiles = 11
        val wSparse = 10
        val wCloud = 10
        val wLocal = 10
        val wSync = 16

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
        )}$td${hLine(wFiles)}$td${hLine(wSparse)}$td${hLine(wCloud)}$td${hLine(wLocal)}$td${hLine(wSync)}$tr"
        val midBorder = "$teeR${hLine(
            wName,
        )}$x${hLine(
            wStatus,
        )}$x${hLine(wFiles)}$x${hLine(wSparse)}$x${hLine(wCloud)}$x${hLine(wLocal)}$x${hLine(wSync)}$teeL"
        val botBorder = "$bl${hLine(
            wName,
        )}$tu${hLine(
            wStatus,
        )}$tu${hLine(wFiles)}$tu${hLine(wSparse)}$tu${hLine(wCloud)}$tu${hLine(wLocal)}$tu${hLine(wSync)}$br"

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
            files: String,
            sparse: String,
            cloud: String,
            local: String,
            sync: String,
        ): String =
            "$v${cell(
                name,
                wName,
            )}$v${cell(
                status,
                wStatus,
            )}$v${cell(
                files,
                wFiles,
                true,
            )}$v${cell(
                sparse,
                wSparse,
                true,
            )}$v${cell(cloud, wCloud, true)}$v${cell(local, wLocal, true)}$v${cell(sync, wSync)}$v"

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
        println(row("STORAGE PROVIDER / ACCOUNT", "STATUS", "FILES", "SPARSE", "CLOUD", "LOCAL", "LAST SYNC"))
        println(midBorder)

        // Render groups
        for ((gi, group) in groups.withIndex()) {
            val anyOk = group.accounts.any { it.status == "ok" }
            val icon = if (anyOk) GlyphRenderer.cloud() else GlyphRenderer.warn()
            val allError = group.accounts.all { it.status != "ok" }

            // Single-account provider with error: inline status on provider row
            if (group.accounts.size == 1 && allError) {
                val acct = group.accounts[0]
                val headerColor = if (acct.status == "dim") "dim" else "yellow"
                val provLine =
                    row(
                        "$icon ${group.displayName}",
                        acct.statusLabel,
                        "%,d".format(acct.files),
                        "%,d".format(acct.sparse),
                        acct.cloudSize,
                        acct.localSize,
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
                            "ok" -> "green"
                            "dim" -> "dim"
                            else -> "yellow"
                        }
                    val acctLine =
                        row(
                            "$connector${acct.profileName}",
                            acct.statusLabel,
                            "%,d".format(acct.files),
                            "%,d".format(acct.sparse),
                            acct.cloudSize,
                            acct.localSize,
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
     * Discover all profiles to show in --all mode:
     * 1. Configured profiles from config.toml [providers.*]
     * 2. Legacy dirs with state.db not already in config
     * 3. Fallback to known types if nothing discovered
     */
    private fun discoverProfiles(baseDir: Path): List<ProfileInfo> {
        val configFile = baseDir.resolve("config.toml")
        val raw =
            if (Files.exists(configFile)) {
                SyncConfig.parseRaw(Files.readString(configFile))
            } else {
                SyncConfig.parseRaw("[general]\n")
            }

        val seen = mutableSetOf<String>()
        val profiles = mutableListOf<ProfileInfo>()

        // 1. Configured profiles (skip unknown provider types gracefully)
        for (name in raw.providers.keys) {
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
                        } catch (e: IllegalArgumentException) {
                            // Config section exists but invalid, skip
                        }
                    }
            }
        }

        // 3. Fallback: known types not yet seen (only if NO profiles discovered)
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
