package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.onedrive.OneDriveProvider
import org.krost.unidrive.sync.ConflictLog
import org.krost.unidrive.sync.IpcProgressReporter
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.LocalWatcher
import org.krost.unidrive.sync.NotifyProgressReporter
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SubscriptionRenewalScheduler
import org.krost.unidrive.sync.SubscriptionStore
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncDirection
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.ThrottledProvider
import org.krost.unidrive.sync.TrashManager
import org.krost.unidrive.sync.computePollInterval
import org.krost.unidrive.sync.pollStateName
import org.krost.unidrive.xtra.XtraEncryptedProvider
import org.krost.unidrive.xtra.XtraKeyManager
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.seconds

@Command(name = "sync", description = ["Sync files with cloud provider"], mixinStandardHelpOptions = true)
open class SyncCommand : Runnable {
    // UD-236: refresh/apply override these via subclass to drive partial syncs.
    // refresh = skipTransfers=true (Pass 1 only, defer byte transfers).
    // apply = skipRemoteGather=true (no provider.delta(), let recovery loops drive).
    // sync = both false (current behaviour).
    open var skipTransfers: Boolean = false
    open var skipRemoteGather: Boolean = false


    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--watch", "-w"], description = ["Run continuously, polling for changes (adaptive interval)"])
    var watch: Boolean = false

    @Option(names = ["--dry-run"], description = ["Show what would be synced without making changes"])
    var dryRun: Boolean = false

    @Option(names = ["--reset"], description = ["Clear sync state and rescan everything from scratch"])
    var reset: Boolean = false

    @Option(names = ["--sync-path"], description = ["Limit sync to a subtree, e.g. --sync-path /Documents"])
    var syncPath: String? = null

    // UD-243: picocli @ArgGroup(exclusive = true) rejects `--upload-only
    // --download-only` together at parse time with exit 2, instead of letting
    // the flags slide through to the engine. `multiplicity = "0..1"` keeps
    // both-absent (the default bidirectional case) legal. The nested class is
    // an implementation detail — callers read `uploadOnly` / `downloadOnly`
    // via the backing-field delegates below.
    @ArgGroup(exclusive = true, multiplicity = "0..1")
    var direction: Direction? = null

    class Direction {
        @Option(names = ["--upload-only"], description = ["Only sync local changes to remote (skip downloads)"])
        var uploadOnly: Boolean = false

        @Option(names = ["--download-only"], description = ["Only sync remote changes to local (skip uploads)"])
        var downloadOnly: Boolean = false
    }

    val uploadOnly: Boolean
        get() = direction?.uploadOnly == true

    val downloadOnly: Boolean
        get() = direction?.downloadOnly == true

    @Option(names = ["--force-delete"], description = ["Override deletion safeguard (max_delete_percentage)"])
    var forceDelete: Boolean = false

    @Option(
        names = ["--propagate-deletes"],
        description = [
            "UD-737: with --upload-only, also propagate local-side deletions to remote.",
            "Default for --upload-only is to skip del-remote entirely (push-additive semantics).",
        ],
    )
    var propagateDeletes: Boolean = false

    @Option(names = ["--exclude"], description = ["Exclude paths matching glob pattern (repeatable)"])
    var cliExcludePatterns: List<String> = emptyList()

    @Option(
        names = ["--fast-bootstrap"],
        description = [
            "UD-223: skip first-sync enumeration by adopting the remote's current state",
            "as the cursor. Items already present on the remote before this moment stay",
            "invisible until they next mutate. OneDrive only; other providers warn + ignore.",
        ],
    )
    var fastBootstrap: Boolean = false

    @Spec
    lateinit var spec: CommandSpec

    private val log = LoggerFactory.getLogger(SyncCommand::class.java)

    override fun run() {
        // UD-243: parse-time rejection of contradictory flag pairs. The
        // upload-only/download-only mutex is already enforced by the
        // @ArgGroup above; these remaining pairs share `--dry-run` and
        // therefore can't all be expressed as a single ArgGroup. Throwing
        // ParameterException still yields picocli exit 2 (the exception is
        // surfaced before any engine-side work runs).
        if (dryRun && forceDelete) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--dry-run and --force-delete are mutually exclusive: --force-delete is a no-op when no writes happen.",
            )
        }
        // UD-738: `--reset --dry-run` used to be rejected at parse time because
        // `--reset` clears state on disk and `--dry-run` is supposed to be
        // side-effect-free. Now reinterpreted as a *virtual* reset: open an
        // in-memory shadow DB for this run, leave the on-disk state.db
        // untouched. Wiring lives below where the DB is constructed.

        // UD-737: --propagate-deletes only makes sense as an opt-in modifier for
        // --upload-only. Without it, --upload-only's default is push-additive
        // (no del-remote). With it, locally-deleted entries propagate to remote.
        if (propagateDeletes && !uploadOnly) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--propagate-deletes is only valid together with --upload-only.",
            )
        }
        // UD-405: normalise --sync-path at the CLI boundary. PowerShell tab-
        // completion routinely produces backslash-prefixed values like
        // `'\Project Notes'`; without normalisation the engine's
        // scope filter (which compares against forward-slash paths) silently
        // matches nothing and the sync runs against zero in-scope items.
        // Mirrors UD-299's sync_root normalisation philosophy: do-what-I-mean
        // for paths instead of failing loud.
        syncPath = normalizeSyncPath(syncPath)
        val lock = parent.acquireProfileLock()
        val profile = parent.resolveCurrentProfile()
        val rawProvider = parent.createProvider()
        val config = parent.loadSyncConfig()
        val effectiveDirection =
            when {
                uploadOnly -> SyncDirection.UPLOAD
                downloadOnly -> SyncDirection.DOWNLOAD
                else -> config.syncDirection
            }
        // UD-251: default file logging is owned by logback.xml (writes to
        // ${LOCALAPPDATA}/unidrive/unidrive.log with MDC). Only attach an
        // additional programmatic appender when the user has explicitly set
        // `log_file` in config.toml to redirect output to a custom path.
        val logFile = config.logFile
        if (logFile != null) {
            java.nio.file.Files
                .createDirectories(logFile.parent)
            configureFileLogging(logFile)
        }

        val provider =
            config.maxBandwidthKbps
                ?.let { ThrottledProvider(rawProvider, it.toLong() * 1024) }
                ?: rawProvider

        val xtraProvider =
            if (profile.rawProvider?.xtra_encryption == true) {
                val keyDir = parent.providerConfigDir().resolve("xtra")
                val keyManager = XtraKeyManager(keyDir.resolve("key"))
                if (!Files.exists(keyDir.resolve("key"))) {
                    System.err.println("Error: xtra_encryption enabled but no keys found.")
                    System.err.println("Run: unidrive -p ${profile.name} vault xtra-init")
                    System.exit(1)
                }
                val envXtraPass = System.getenv("UNIDRIVE_XTRA_PASS")
                val xtraPass =
                    if (envXtraPass != null) {
                        envXtraPass.toCharArray()
                    } else {
                        val console = System.console()
                        if (console == null) {
                            System.err.println("Error: interactive terminal required for xtra decryption.")
                            System.err.println("Set UNIDRIVE_XTRA_PASS env var for non-interactive xtra access.")
                            System.exit(1)
                            throw IllegalStateException("unreachable")
                        }
                        console.readPassword("Xtra encryption passphrase: ")
                            ?: run {
                                System.err.println("Error: could not read passphrase.")
                                System.exit(1)
                                throw IllegalStateException("unreachable")
                            }
                    }
                keyManager.load(xtraPass)
                XtraEncryptedProvider(provider, keyManager)
            } else {
                null
            }
        val dbPath = parent.providerConfigDir().resolve("state.db")

        // UD-738: virtual-reset mode. With `--reset --dry-run`, plan against a
        // fresh in-memory DB while leaving state.db on disk untouched. Copy
        // the real DB's stored sync_root into the shadow so UD-299's drift
        // detector still has a baseline to compare against (otherwise the
        // shadow would always look like "first sync ever" and the detector
        // would silently no-op).
        val virtualReset = reset && dryRun
        val db: StateDatabase
        if (virtualReset) {
            // Read sync_root from the real DB before opening the shadow.
            val realSyncRoot =
                StateDatabase(dbPath).run {
                    initialize()
                    val v = getSyncState("sync_root")
                    close()
                    v
                }
            db = StateDatabase(dbPath, inMemory = true)
            db.initialize()
            if (!realSyncRoot.isNullOrEmpty()) {
                db.setSyncState("sync_root", realSyncRoot)
            }
            println("Sync state virtually reset (dry-run; on-disk state.db untouched).")
        } else {
            db = StateDatabase(dbPath)
            db.initialize()
            if (reset) {
                db.resetAll()
                println("Sync state cleared.")
            }
        }

        // Apply config pin patterns to DB
        applyConfigPinRules(db, config, profile.name)

        val cliReporter = CliProgressReporter(parent.verbose, dryRun)

        val notifyReporter =
            if (config.desktopNotifications && NotifyProgressReporter.isAvailable()) {
                NotifyProgressReporter(profile.name)
            } else {
                null
            }

        // UD-746 / UD-240a: advertise IPC for every sync run, not just
        // --watch. One-shot syncs (the common case for an operator running
        // `unidrive sync` from a shell) now expose their socket so the tray
        // and `unidrive status` can show live progress instead of "no
        // daemon." Profile lock (UD-272) ensures only one sync per profile
        // runs at a time, so socket creation can't collide.
        val socketPath = IpcServer.defaultSocketPath(profile.name)
        val ipcServer = IpcServer(socketPath)
        val ipcReporter = IpcProgressReporter(ipcServer, profile.name)
        val delegates = mutableListOf<ProgressReporter>(cliReporter, ipcReporter)
        if (notifyReporter != null) delegates.add(notifyReporter)
        val reporter: ProgressReporter = CompositeReporter(delegates)

        // UD-296: surface profile + provider type + sync_root + direction up
        // front so users can spot sync_root drift (wrong directory pointed at)
        // before the planner produces its first del-remote line. ASCII-only
        // for Windows console codepage hostility.
        val directionLabel = effectiveDirection.name.lowercase()
        val mode =
            buildString {
                append(directionLabel)
                if (dryRun) append(", dry-run")
                if (forceDelete) append(", force-delete")
                if (propagateDeletes) append(", propagate-deletes")
                if (watch) append(", watch")
            }
        // UD-741: include sync_path conditionally — silent when unset (the
        // common case), surfaced when set so the user can spot a typo'd
        // sub-path before the scan eats minutes of wall-clock.
        val syncPathSegment = if (syncPath != null) " sync_path=$syncPath" else ""
        println(
            "sync: profile=${profile.name} type=${profile.type} " +
                "sync_root=${config.syncRoot}$syncPathSegment mode=$mode",
        )

        // Create the watcher early so SyncEngine can suppress echo events.
        // In non-watch mode we still pass the callbacks (they become no-ops since
        // watcher is only started in watch mode).
        val watcher = if (watch) LocalWatcher(config.syncRoot) else null

        val dataDir =
            run {
                val os = System.getProperty("os.name", "").lowercase()
                if (os.contains("win")) {
                    val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
                    java.nio.file.Paths
                        .get(localAppData, "unidrive", profile.name)
                } else {
                    java.nio.file.Paths.get(
                        System.getenv("HOME") ?: System.getProperty("user.home"),
                        ".local",
                        "share",
                        "unidrive",
                        profile.name,
                    )
                }
            }
        val conflictLog =
            ConflictLog(
                logFile = parent.providerConfigDir().resolve("conflicts.jsonl"),
                backupDir = dataDir.resolve("conflict-backups"),
            )

        val trashManager = if (config.trashEmulation) TrashManager(config.syncRoot) else null
        val versionManager =
            if (config.fileVersioning) {
                org.krost.unidrive.sync
                    .VersionManager(config.syncRoot)
            } else {
                null
            }

        // UD-113: structured per-action audit log. JSONL date-stamped under
        // {profileConfigDir}/audit-YYYY-MM-DD.jsonl. Always-on; emit failures
        // are swallowed at WARN, never blocks the sync engine.
        val auditLog =
            org.krost.unidrive.sync.audit
                .AuditLog(parent.providerConfigDir(), profile.name)

        val engine =
            SyncEngine(
                provider = xtraProvider ?: provider,
                db = db,
                syncRoot = config.syncRoot,
                conflictPolicy = config.conflictPolicy,
                conflictOverrides = config.providerConflictOverrides(profile.name),
                excludePatterns = config.effectiveExcludePatterns(profile.name) + cliExcludePatterns,
                reporter = reporter,
                failureLogPath = parent.providerConfigDir().resolve("failures.jsonl"),
                conflictLog = conflictLog,
                syncPath = syncPath,
                syncDirection = effectiveDirection,
                propagateDeletes = propagateDeletes,
                maxDeletePercentage = config.maxDeletePercentage,
                verifyIntegrity = config.verifyIntegrity,
                providerId = profile.type,
                useTrash = config.useTrash,
                includeShared = profile.rawProvider?.include_shared == true,
                echoSuppress = watcher?.let { w -> { path: String -> w.suppress(path) } },
                echoUnsuppress = watcher?.let { w -> { path: String -> w.unsuppress(path) } },
                trashManager = trashManager,
                trashRetentionDays = config.trashRetentionDays,
                versionManager = versionManager,
                maxVersions = config.maxVersions,
                versionRetentionDays = config.versionRetentionDays,
                // UD-223: CLI flag wins over config.toml per-profile setting.
                fastBootstrap = fastBootstrap || profile.rawProvider?.fast_bootstrap == true,
                auditLog = auditLog,
            )

        Files.createDirectories(config.syncRoot)

        // Webhook subscription store (shared DB file, separate table)
        val subscriptionStore = SubscriptionStore(dbPath)
        subscriptionStore.initialize()

        try {
            // UD-254a: dropped MDCContext() here. Live JFR (2026-05-02) showed
            // `LogbackMDCAdapter.getPropertyMap` accounting for 80.63 % of all
            // allocation pressure — every coroutine resume on this hot path was
            // cloning the MDC HashMap (getCopyOfContextMap save + setContextMap
            // restore = 2 allocations per dispatch). With Pass-2 upload workers
            // suspending repeatedly through Ktor's HTTP/2 stack the count went
            // into the millions.
            //
            // UD-212 originally added MDCContext() so worker-thread log lines
            // would inherit the `profile=<name>` MDC tag. Trade now: worker
            // log lines lose `[profile]` / `[scan]` interpolation. Acceptable
            // because (a) one daemon = one profile (logs are unambiguous) and
            // (b) scan/profile are still on the synchronous main thread for
            // the engine's banner lines (`Scan started`/`Scan ended`). The
            // canonical follow-up — id-in-message logging on coroutine paths
            // per the lessons/mdc-in-suspend.md guidance — is the wider Slice
            // B in UD-254a's body and out of scope for this single-file fix.
            runBlocking {
                ipcServer.start(this)
                provider.authenticateAndLog()

                // Ensure webhook subscription if configured
                val rpConfig = profile.rawProvider
                // UD-303: coroutine-scoped scheduler that fires one renewal per
                // (lifetime − 24h) window instead of relying on every poll cycle
                // to re-check the expiry column. Lifecycle ties to this scope —
                // cancelled automatically when runBlocking unwinds.
                var renewalScheduler: SubscriptionRenewalScheduler? = null
                if (watch && rpConfig?.webhook == true) {
                    renewalScheduler =
                        SubscriptionRenewalScheduler(
                            scope = this,
                            store = subscriptionStore,
                            renew = { pn ->
                                // Scheduler-triggered renewal. Pass the scheduler back in so
                                // that after the Graph save we re-arm for the next window.
                                // `isScheduledAndValid` returns false here (the scheduled job
                                // is in its tail, having already consumed its delay), so we
                                // fall through to the real renewal path.
                                ensureSubscription(rawProvider, subscriptionStore, pn, rpConfig, renewalScheduler)
                            },
                        )
                    ensureSubscription(rawProvider, subscriptionStore, profile.name, rpConfig, renewalScheduler)
                }

                if (watch) {
                    println("Starting continuous sync to ${config.syncRoot}")
                    watcher!!.start()

                    Runtime.getRuntime().addShutdownHook(
                        Thread {
                            println("\nShutting down...")
                            watcher.stop()
                            ipcServer.close()
                            renewalScheduler?.cancelAll()
                            subscriptionStore.close()
                            db.close()
                        },
                    )

                    var cycleFailures = 0
                    var idleCycles = 3 // start in NORMAL state
                    var lastState = pollStateName(idleCycles)
                    var firstCycle = true
                    while (true) {
                        var hadChanges = false
                        try {
                            // Renew webhook subscription if needed
                            if (rpConfig?.webhook == true) {
                                ensureSubscription(rawProvider, subscriptionStore, profile.name, rpConfig, renewalScheduler)
                            }

                            ipcReporter.emitSyncStarted()
                            // UD-254: classify each pass. First iteration after daemon
                            // boot is BOOT; subsequent iterations are WATCH_POLL unless
                            // LocalWatcher fired a change (EVENT_DRIVEN, tracked below).
                            val reason =
                                when {
                                    firstCycle -> org.krost.unidrive.sync.SyncReason.BOOT
                                    else -> org.krost.unidrive.sync.SyncReason.WATCH_POLL
                                }
                            engine.syncOnce(forceDelete = forceDelete, reason = reason)
                            firstCycle = false
                            cycleFailures = 0
                            hadChanges = (cliReporter.lastDownloaded + cliReporter.lastUploaded + cliReporter.lastConflicts) > 0
                        } catch (e: AuthenticationException) {
                            ipcReporter.emitSyncError(e.message ?: "Authentication error")
                            System.err.println("Authentication error, stopping: ${e.message}")
                            break
                        } catch (e: Exception) {
                            ipcReporter.emitSyncError(e.message ?: "Sync failed")
                            cycleFailures++
                            val backoffMs = minOf(10_000L * (1L shl (cycleFailures - 1)), 600_000L)
                            System.err.println("Sync cycle failed (attempt $cycleFailures), retrying in ${backoffMs / 1000}s: ${e.message}")
                            delay(backoffMs)
                            continue
                        }

                        if (hadChanges) {
                            idleCycles = 0
                        } else {
                            idleCycles++
                        }

                        val adaptiveSeconds =
                            computePollInterval(idleCycles, config.minPollInterval, config.pollInterval, config.maxPollInterval)
                        val failureBackoffSeconds = if (cycleFailures > 0) minOf(10 * (1 shl (cycleFailures - 1)), 600) else 0
                        val intervalSeconds = maxOf(adaptiveSeconds, failureBackoffSeconds)

                        val currentState = pollStateName(idleCycles)
                        if (currentState != lastState) {
                            if (parent.verbose) println("Polling: $currentState (${intervalSeconds}s)")
                            ipcReporter.emitPollState(currentState, intervalSeconds, idleCycles)
                            lastState = currentState
                        }

                        val hasLocalChange = watcher.awaitChange(intervalSeconds.toLong().seconds)
                        if (hasLocalChange) idleCycles = 0

                        watcher.drainChanges()
                    }
                } else {
                    engine.syncOnce(
                        dryRun = dryRun,
                        forceDelete = forceDelete,
                        reason = org.krost.unidrive.sync.SyncReason.MANUAL,
                        // UD-236: refresh / apply set these via subclass.
                        skipTransfers = skipTransfers,
                        skipRemoteGather = skipRemoteGather,
                    )
                    // UD-406: cancel the IPC accept loop so runBlocking can return.
                    // ipcServer.start(this) at line ~366 above launches an infinite
                    // accept-loop coroutine into this scope; runBlocking { } would
                    // otherwise block forever waiting for it to complete. The
                    // finally{} below ALSO calls ipcServer.close(), but that runs
                    // only AFTER runBlocking returns — chicken-and-egg.
                    //
                    // close() is idempotent (acceptJob?.cancel() is a no-op on
                    // already-cancelled jobs), so the double-close is harmless.
                    ipcServer.close()
                }
            }
        } catch (e: AuthenticationException) {
            parent.handleAuthError(e, rawProvider)
        } finally {
            ipcServer.close()
            rawProvider.close()
            subscriptionStore.close()
            db.close()
            lock.unlock()
        }
    }

    /**
     * Ensures a valid webhook subscription exists for the given profile.
     * - If a stored subscription is still valid (>1h from expiry), reuses it.
     * - If it expires within 24h, attempts renewal.
     * - If expired, missing, or renewal fails, creates a new subscription.
     *
     * UD-303: when [scheduler] is non-null and it already has a live job for
     * this profile AND the persisted subscription still has >24h remaining,
     * short-circuit — no Graph call, no store read beyond what the scheduler
     * already did. The scheduler will fire its own `renew` callback (which
     * calls back into this method with `scheduler = null`) at (expiry − 24h).
     */
    private suspend fun ensureSubscription(
        rawProvider: CloudProvider,
        store: SubscriptionStore,
        profileName: String,
        rawProviderConfig: org.krost.unidrive.sync.RawProvider,
        scheduler: SubscriptionRenewalScheduler?,
    ) {
        val oneDrive = rawProvider as? OneDriveProvider ?: return
        val webhookUrl = rawProviderConfig.webhook_url ?: return

        // UD-303: per-cycle fast path — a scheduled renewal is already armed
        // for a subscription that's still comfortably valid.
        if (scheduler?.isScheduledAndValid(profileName) == true) {
            return
        }

        val now = Instant.now()
        val existing = store.get(profileName)
        if (existing != null) {
            val timeToExpiry = ChronoUnit.HOURS.between(now, existing.expiresAt)
            if (timeToExpiry > 24) {
                // Still valid, no action needed — but arm the scheduler so the
                // next per-cycle call hits the fast path above.
                scheduler?.schedule(profileName)
                return
            }
            if (timeToExpiry > 1) {
                // Expires within 24h but still valid — try renewal
                val newExpiry = now.plus(3, ChronoUnit.DAYS)
                val renewed = oneDrive.renewSubscription(existing.subscriptionId, newExpiry.toString())
                if (renewed) {
                    store.save(profileName, existing.subscriptionId, newExpiry)
                    log.debug("Webhook subscription renewed for profile {}", profileName)
                    scheduler?.schedule(profileName)
                    return
                }
                log.warn("Webhook subscription renewal failed for profile {}, creating new subscription", profileName)
            }
            // Expired or renewal failed — delete stale record
            store.delete(profileName)
        }

        // Create new subscription
        val expiry = now.plus(3, ChronoUnit.DAYS)
        val sub = oneDrive.createSubscription(webhookUrl, expiry.toString())
        if (sub != null) {
            val subExpiry =
                sub.expirationDateTime
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: expiry
            store.save(profileName, sub.id, subExpiry)
            log.debug("Webhook subscription created for profile {}: {}", profileName, sub.id)
            scheduler?.schedule(profileName)
        } else {
            log.warn("Failed to create webhook subscription for profile {}", profileName)
        }
    }

    private fun configureFileLogging(logFile: java.nio.file.Path) {
        val context = org.slf4j.LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
        val appender =
            ch.qos.logback.core.rolling
                .RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.context = context
        appender.name = "FILE"
        appender.file = logFile.toString()
        val policy =
            ch.qos.logback.core.rolling
                .FixedWindowRollingPolicy()
        policy.context = context
        policy.setParent(appender)
        policy.fileNamePattern = "$logFile.%i"
        policy.minIndex = 1
        policy.maxIndex = 3
        policy.start()
        appender.rollingPolicy = policy
        val triggering =
            ch.qos.logback.core.rolling
                .SizeBasedTriggeringPolicy<ch.qos.logback.classic.spi.ILoggingEvent>()
        triggering.context = context
        triggering.setMaxFileSize(
            ch.qos.logback.core.util.FileSize
                .valueOf("10MB"),
        )
        triggering.start()
        appender.triggeringPolicy = triggering
        val encoder =
            ch.qos.logback.classic.encoder
                .PatternLayoutEncoder()
        encoder.context = context
        // UD-251: match logback.xml pattern so redirected logs carry the same
        // build + profile MDC context as the default unidrive.log does.
        encoder.pattern =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{build:-???????}] [%X{profile:-*}] " +
            "[%thread] %logger{36} - %msg%n"
        encoder.start()
        appender.encoder = encoder
        appender.start()
        val root = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        root.addAppender(appender)
    }

    private fun applyConfigPinRules(
        db: StateDatabase,
        config: SyncConfig,
        providerId: String,
    ) {
        for (pattern in config.providerPinIncludes(providerId)) {
            db.addPinRule(pattern, pinned = true)
        }
        for (pattern in config.providerPinExcludes(providerId)) {
            db.addPinRule(pattern, pinned = false)
        }
    }

    private class CompositeReporter(
        private val delegates: List<ProgressReporter>,
    ) : ProgressReporter {
        override fun onScanProgress(
            phase: String,
            count: Int,
        ) = delegates.forEach { it.onScanProgress(phase, count) }

        override fun onReconcileProgress(
            processed: Int,
            total: Int,
        ) = delegates.forEach { it.onReconcileProgress(processed, total) }

        override fun onActionCount(total: Int) = delegates.forEach { it.onActionCount(total) }

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) = delegates.forEach {
            it.onActionProgress(index, total, action, path)
        }

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) = delegates.forEach {
            it.onTransferProgress(path, bytesTransferred, totalBytes)
        }

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) = delegates.forEach {
            it.onSyncComplete(downloaded, uploaded, conflicts, durationMs, actionCounts, failed)
        }

        override fun onWarning(message: String) = delegates.forEach { it.onWarning(message) }
    }

    companion object {
        /**
         * UD-405: normalise a `--sync-path` value (or its `sync_path` config
         * equivalent) to the forward-slash, leading-slash, no-trailing-slash
         * form the SyncEngine scope filter expects.
         *
         * Returns null if the input is null, empty, or normalises to "/"
         * (which is the same as "no scope" — every path startsWith "/").
         *
         * Examples:
         *   `\Project Notes` → `/Project Notes`
         *   `\internal\sub\`           → `/internal/sub`
         *   `internal`                 → `/internal`
         *   `/`                        → `null` (= no scope)
         *   `""`                       → `null`
         */
        internal fun normalizeSyncPath(raw: String?): String? {
            if (raw.isNullOrEmpty()) return null
            // Replace any backslashes with forward slashes (PowerShell-friendly).
            var s = raw.replace('\\', '/')
            // Collapse runs of '/' to a single '/' (handles "//", "\\\\", mixed).
            s = s.replace(Regex("/+"), "/")
            // Ensure leading '/'.
            if (!s.startsWith("/")) s = "/$s"
            // Strip trailing '/' (engine matches startsWith("$syncPath/")).
            if (s.length > 1 && s.endsWith("/")) s = s.removeSuffix("/")
            // A bare "/" is the whole tree — same as no scope.
            return if (s == "/") null else s
        }
    }
}
