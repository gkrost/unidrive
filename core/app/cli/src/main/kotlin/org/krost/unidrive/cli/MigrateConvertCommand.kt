package org.krost.unidrive.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.krost.unidrive.ProviderException
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.cli.LegacyInventory.PathClass
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.io.path.name

/**
 * #604 U5b: the one-time conversion of a legacy profile to its explicit mode. The command is
 * the only writer of the `mode` key on an existing profile; no ordinary startup assigns one.
 *
 * Contract (the issue's gate): writers must be quiesced (a live lock holder refuses it); an
 * idempotent journal (`conversion-journal.json` in the profile dir) records phases, the
 * original section, and every byte move, so an interrupted run resumes instead of repeating;
 * the new mode is published only after the byte work is done; nothing touches the network —
 * quarantined files are uploaded by the separate, explicit `migrate upload-quarantined`.
 * Every unique local byte survives: identical copies are retired, differing or local-only
 * copies are quarantined with a manifest, the hydration cache and state.db are never deleted.
 */
@Command(
    name = "convert",
    description = [
        "Convert a stopped legacy profile to an explicit mode. --mode mount retires the old",
        "sync_root (identical files retired, differing or local-only files quarantined with a",
        "manifest; upload them later with 'migrate upload-quarantined'). --mode mirror adopts",
        "the sync_root as the mirror root and resets the enumeration baseline so the first",
        "sync gathers fresh. Interrupted conversions resume; --restart starts over.",
    ],
    mixinStandardHelpOptions = true,
)
class MigrateConvertCommand : Runnable {
    @ParentCommand
    lateinit var migrate: MigrateCommand

    @Option(names = ["--mode"], required = true, description = ["Target mode: mount or mirror (fixed after conversion)"])
    lateinit var mode: String

    @Option(
        names = ["--sync-root"],
        description = ["What happens to the old sync_root: retire (move aside; mount target) or adopt (keep as the mirror root; mirror target)"],
    )
    var syncRootDisposition: String? = null

    @Option(names = ["--restart"], description = ["Discard an interrupted conversion journal and start over"])
    var restart: Boolean = false

    override fun run() {
        val main = migrate.parent
        val profile = main.resolveCurrentProfile()
        val setup =
            LegacyConversion.Setup(
                profileName = profile.name,
                providerType = profile.type,
                profileDir = main.providerConfigDir(),
                syncRoot = profile.syncRoot,
                dbPath = main.providerConfigDir().resolve("state.db"),
                configFile = main.configBaseDir().resolve("config.toml"),
                engineVersion = BuildInfo.versionString(),
            )
        System.exit(LegacyConversion.execute(setup, mode, syncRootDisposition, restart, main.verbose))
    }
}

/**
 * `unidrive -p <profile> migrate upload-quarantined` (#604): the one explicit command that
 * uploads the files a retire conversion quarantined, as conflict copies at their original
 * remote paths. Requires the conversion to be complete and the profile authenticated.
 */
@Command(
    name = "upload-quarantined",
    description = [
        "Upload the files a 'migrate convert --mode mount --sync-root retire' quarantined, as",
        "conflict copies at their original remote paths. Reads the conversion journal; touches",
        "nothing on disk except its own progress line in the journal.",
    ],
    mixinStandardHelpOptions = true,
)
class MigrateUploadQuarantinedCommand : Runnable {
    @ParentCommand
    lateinit var migrate: MigrateCommand

    override fun run() {
        val main = migrate.parent
        val profile = main.resolveCurrentProfile()
        val journal = LegacyConversion.Journal.load(main.providerConfigDir())
        val quarantined = journal?.quarantined.orEmpty()
        if (journal == null || "CONVERTED" !in journal.phases || quarantined.isEmpty()) {
            System.err.println("No quarantined files to upload (no completed retire conversion found).")
            System.exit(1)
        }
        val provider = main.createProvider()
        try {
            kotlinx.coroutines.runBlocking {
                provider.authenticateAndLog()
                var uploaded = 0
                for (entry in quarantined) {
                    val local = Path.of(entry.quarantinePath)
                    if (!Files.exists(local)) {
                        System.err.println("Skipping '${entry.remotePath}': the quarantined copy is gone.")
                        continue
                    }
                    uploadAsConflictCopy(provider, local, entry.remotePath)
                    uploaded++
                    println("Uploaded '${entry.remotePath}' as a conflict copy ($uploaded/${quarantined.size})")
                }
                println("Done: $uploaded of ${quarantined.size} quarantined file(s) uploaded as conflict copies.")
            }
        } finally {
            provider.close()
        }
    }

    /** Uploads [local] to [remotePath]; on a collision, retries under the provider's conflict-copy name. */
    private suspend fun uploadAsConflictCopy(
        provider: org.krost.unidrive.CloudProvider,
        local: Path,
        remotePath: String,
    ) {
        try {
            provider.upload(local, remotePath)
            return
        } catch (_: ProviderException) {
            // fall through to the conflict-copy naming
        }
        val date = java.time.LocalDate.now().toString()
        val dir = remotePath.substringBeforeLast('/', missingDelimiterValue = "/")
        val leaf = remotePath.substringAfterLast('/')
        val plain = leaf.substringBeforeLast('.', missingDelimiterValue = leaf)
        val ext = if (leaf.contains('.')) "." + leaf.substringAfterLast('.') else ""
        for (counter in 1..20) {
            val candidate = "$dir/${LegacyConversion.conflictLeaf(plain, ext, date, counter)}"
            try {
                provider.upload(local, candidate)
                return
            } catch (_: ProviderException) {
                // another conflict copy with the same counter exists — try the next
            }
        }
        throw ProviderException("could not place a conflict copy for $remotePath after 20 attempts")
    }
}

/**
 * The conversion engine behind [MigrateConvertCommand]. Internal and side-effect-explicit so
 * tests can drive it against fixture trees without picocli or System.exit.
 */
internal object LegacyConversion {
    const val JOURNAL_FILE = "conversion-journal.json"

    @Serializable
    data class MovedFile(
        val from: String,
        val to: String,
        val bytes: Long,
        val bucket: String,
    )

    @Serializable
    data class QuarantinedFile(
        val remotePath: String,
        val quarantinePath: String,
        val bytes: Long,
    )

    @Serializable
    data class Journal(
        val version: Int = 1,
        val engineVersion: String,
        val startedAt: String,
        val profileName: String,
        val targetMode: String,
        val disposition: String,
        val phases: List<String> = emptyList(),
        val originalSection: List<String> = emptyList(),
        val moves: List<MovedFile> = emptyList(),
        val quarantined: List<QuarantinedFile> = emptyList(),
        val conversionDir: String? = null,
        val baselineCleared: Boolean = false,
        val convertedAt: String? = null,
    ) {
        companion object {
            private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

            fun file(profileDir: Path): Path = profileDir.resolve(JOURNAL_FILE)

            fun load(profileDir: Path): Journal? {
                val f = file(profileDir)
                if (!Files.exists(f)) return null
                return json.decodeFromString(serializer(), Files.readString(f))
            }

            fun save(
                profileDir: Path,
                journal: Journal,
            ) {
                val f = file(profileDir)
                Files.createDirectories(profileDir)
                Files.writeString(f, json.encodeToString(serializer(), journal))
            }
        }

        fun withPhase(phase: String): Journal = copy(phases = phases + phase)
    }

    data class Setup(
        val profileName: String,
        val providerType: String,
        val profileDir: Path,
        val syncRoot: Path,
        val dbPath: Path,
        val configFile: Path,
        val engineVersion: String,
        /** The hydration cache to inventory; null = the profile's canonical cache directory. */
        val cacheDir: Path? = null,
    )

    private data class Refusal(val message: String)

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The conflict-copy leaf name, matching the engine's "(conflict <date>)" shape (UD-366). */
    internal fun conflictLeaf(
        plain: String,
        ext: String,
        date: String,
        counter: Int,
    ): String {
        val base = "$plain (conflict $date)" + if (counter <= 1) "" else " ($counter)"
        return base + ext
    }

    /** Release part of a `VERSION+COMMIT` build string, as comparable segments. */
    private fun releaseOf(version: String): List<Int> =
        version
            .substringBefore('+')
            .removePrefix("v")
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

    private fun isNewerEngine(journalVersion: String, runningVersion: String): Boolean {
        val j = releaseOf(journalVersion)
        val r = releaseOf(runningVersion)
        for (i in 0 until maxOf(j.size, r.size)) {
            val a = j.getOrElse(i) { 0 }
            val b = r.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun sectionLines(
        configText: String,
        name: String,
    ): List<String> {
        val lines = configText.lines()
        val start = lines.indexOfFirst { it.trim() == "[providers.$name]" }
        if (start < 0) return emptyList()
        val end =
            lines
                .drop(start + 1)
                .indexOfFirst { it.trim().startsWith("[") }
                .let { if (it < 0) lines.size else start + 1 + it }
        return lines.subList(start, end)
    }

    private fun hasModeLine(section: List<String>): Boolean = section.any { it.trim().startsWith("mode") }

    // ── the conversion ───────────────────────────────────────────────────────

    /**
     * Runs the conversion; returns the process exit code (0 ok, 1 refused/failed). Every
     * refusal happens before the first mutation; a failure mid-way leaves the journal and
     * the moved files as the resume point and mutates nothing further.
     */
    internal fun execute(
        setup: Setup,
        rawMode: String,
        rawDisposition: String?,
        restart: Boolean,
        verbose: Boolean,
    ): Int {
        val targetMode =
            when (rawMode.lowercase()) {
                "mount" -> "mount"
                "mirror" -> "mirror"
                else -> {
                    System.err.println("Error: --mode must be 'mount' or 'mirror', got '$rawMode'.")
                    return 1
                }
            }
        val disposition =
            when (rawDisposition?.lowercase()) {
                null, "" -> null
                "retire" -> "retire"
                "adopt" -> "adopt"
                else -> {
                    System.err.println("Error: --sync-root must be 'retire' or 'adopt', got '$rawDisposition'.")
                    return 1
                }
            }
        if (disposition == "adopt" && targetMode != "mirror") {
            System.err.println("Error: adopting the sync_root means the profile becomes a mirror profile; use --mode mirror.")
            return 1
        }
        if (disposition == "retire" && targetMode != "mount") {
            System.err.println("Error: retiring the sync_root belongs to a mount conversion; use --mode mount (a mirror keeps its sync_root).")
            return 1
        }

        // Refusal 1: writers quiesced. Any LIVE lock holder (daemon, sync watcher, mount client's
        // engine-side daemon) means the coordinated state is being mutated concurrently.
        val pidFile = setup.profileDir.resolve(".lock").resolveSibling(".lock.pid")
        when (val lock = readLockPid(pidFile)) {
            is LockPidReadResult.Present -> {
                if (isLockHolderAlive(lock.contents.pid)) {
                    System.err.println(
                        "Error: profile '${setup.profileName}' is in use by PID ${lock.contents.pid} " +
                            "(${lock.contents.modeToken ?: "unknown mode"}). Stop it first: " +
                            "'unidrive daemon stop -p ${setup.profileName}' (and 'unidrive-mount stop' on the client).",
                    )
                    return 1
                }
            }
            is LockPidReadResult.Malformed -> {
                System.err.println("Error: the profile lock sidecar is malformed ('${lock.raw.take(60)}') — remove it after checking no unidrive process runs for this profile.")
                return 1
            }
            LockPidReadResult.Absent -> Unit
        }

        // Load the journal before checking mode. Publication and recording its PUBLISH phase are
        // separate durable writes: a crash in between leaves mode in config but an unfinished
        // journal. That is a resume point, not an already-converted profile.
        var loaded = Journal.load(setup.profileDir)
        if (loaded != null && isNewerEngine(loaded.engineVersion, setup.engineVersion)) {
            System.err.println(
                "Error: the conversion journal was written by a newer engine (${loaded.engineVersion} > " +
                    "${setup.engineVersion}); upgrade this CLI before resuming.",
            )
            return 1
        }
        if (loaded != null && loaded.profileName != setup.profileName) {
            System.err.println("Error: the conversion journal belongs to profile '${loaded.profileName}', not '${setup.profileName}'.")
            return 1
        }
        if (loaded != null && "CONVERTED" in loaded.phases) {
            System.err.println("Error: profile '${setup.profileName}' was already converted at ${loaded.convertedAt}.")
            return 1
        }

        // Refusal 2: a mode without an unfinished journal means the profile has already been
        // converted. A matching unfinished journal is allowed through so a crash immediately
        // after config publication can finish its durable tail.
        val configText =
            if (Files.exists(setup.configFile)) Files.readString(setup.configFile) else "[general]\n"
        val section = sectionLines(configText, setup.profileName)
        if (section.isEmpty()) {
            System.err.println("Error: no [providers.${setup.profileName}] section in ${setup.configFile} — nothing to convert.")
            return 1
        }
        if (hasModeLine(section) && loaded == null) {
            System.err.println("Error: profile '${setup.profileName}' already declares a mode — nothing to convert.")
            return 1
        }

        // Phase INVENTORY: the same read-only classification `migrate inventory` reports. It runs
        // before the journal is created so a refusal below leaves nothing behind (not even a PLAN
        // journal that would pin a later, different --mode to this attempt).
        val cacheDir =
            setup.cacheDir
                ?: org.krost.unidrive.sync.SyncEngine.hydrationCacheRoot(
                    org.krost.unidrive.sync.SyncEngine.defaultHydrationCacheRoot(),
                    setup.profileName,
                )
        val inventory =
            when (val outcome = LegacyInventory.collect(legacyInventoryInputs(setup, cacheDir))) {
                is LegacyInventory.Outcome.Refused -> {
                    System.err.println(outcome.message)
                    return 1
                }
                is LegacyInventory.Outcome.Collected -> outcome.report
            }

        // Refusal 3: a legacy hybrid profile cannot become a mirror. Its hydrated rows whose bytes
        // live only in the hydration cache are absent from the sync root, so the first mirror sync
        // would read them as local deletes and plan DeleteRemote. Mirror and mount profiles are
        // independent and no migration of hybrid state is supported (#459, #680).
        // Which rows count: cache_backed = true always (the mount wrote the bytes to the cache). A
        // NULL row counts unless its sync_root file is present: only the mount engine writes
        // cache_backed, so every row a plain mirror profile ever hydrated is NULL, and with its
        // sync_root file present it is a mirror row (#706). A NULL row with no copy in the sync
        // root - whether its bytes sit in the cache or are gone entirely - is absent from the sync
        // root, so the first mirror sync would read it as a local delete and plan DeleteRemote.
        // cache_backed = false (a sync-root baseline the mount adopted) passes, as before.
        // A conversion that already published its mode (resume) passed this check before.
        if (targetMode == "mirror" && (loaded == null || "PUBLISH" !in loaded.phases)) {
            val flagged = inventory.db?.cacheBacked?.get("true") ?: 0
            val nullHydrated = inventory.db?.cacheBacked?.get("null") ?: 0
            // NULL rows the mirror re-adopts: their sync_root file is present, so no local byte is missing.
            val nullWithSyncRoot =
                inventory.files.count {
                    it.hydrated == true && it.cacheBacked == null && it.syncRootBytes != null
                }
            val cacheBackedHydrated = flagged + (nullHydrated - nullWithSyncRoot).coerceAtLeast(0)
            if (cacheBackedHydrated > 0) {
                System.err.println(
                    "Error: profile '${setup.profileName}' is a legacy hybrid profile with $cacheBackedHydrated hydrated " +
                        "file(s) whose bytes are absent from the sync_root (marked cache-backed, or a NULL row with no " +
                        "sync_root copy). Converting such a profile to a mirror is unsupported: the first mirror sync " +
                        "would read them as local deletes, mirror and mount profiles are independent, and no migration " +
                        "of hybrid state is supported in this phase. Convert it to a mount instead " +
                        "('migrate convert --mode mount'), or create a new mirror profile. Nothing was changed.",
                )
                return 1
            }
        }

        // Journal: resume an interrupted run, or start over on --restart.
        if (loaded != null && restart) {
            Files.list(setup.profileDir).use { stream ->
                stream
                    .filter { it.fileName.toString().startsWith("conversion-") && Files.isDirectory(it) }
                    .forEach { dir ->
                        runCatching { Files.move(dir, setup.profileDir.resolve("conversion-archive-${System.currentTimeMillis()}")) }
                    }
            }
            loaded = null
        }
        val resumed = loaded
        var journal: Journal =
            if (resumed != null) {
                resumed
            } else {
                val fresh =
                    Journal(
                        engineVersion = setup.engineVersion,
                        startedAt = Instant.now().toString(),
                        profileName = setup.profileName,
                        targetMode = targetMode,
                        disposition = disposition ?: if (targetMode == "mount") "retire" else "adopt",
                        originalSection = section,
                    ).withPhase("PLAN")
                Journal.save(setup.profileDir, fresh)
                fresh
            }
        if (resumed != null && journal.targetMode != targetMode) {
            System.err.println(
                "Error: the interrupted conversion targets mode '${journal.targetMode}'; " +
                    "resume with --mode ${journal.targetMode} (or --restart to start over).",
            )
            return 1
        }

        if ("INVENTORY" !in journal.phases) {
            journal = journal.withPhase("INVENTORY")
            Journal.save(setup.profileDir, journal)
        }

        val conversionDir = journal.conversionDir?.let { Path.of(it) }

        // Phase PRESERVE (mount target): retire the sync_root into the conversion directory.
        var retired = 0
        var quarantinedCount = 0
        if (targetMode == "mount") {
            val rootRecords =
                inventory.files.filter {
                    it.syncRootBytes != null &&
                        it.cls in setOf(PathClass.BOTH_IDENTICAL, PathClass.BOTH_DIFFERENT, PathClass.SYNC_ROOT_ONLY, PathClass.UNTRACKED)
                }
            if (rootRecords.isNotEmpty() && disposition == null) {
                System.err.println(
                    "Error: the sync_root holds ${rootRecords.size} file(s). A mount conversion must say what happens to " +
                        "them: --sync-root retire moves identical copies aside and quarantines the rest (upload them later " +
                        "with 'migrate upload-quarantined'); converting to a mirror instead adopts the folder (--mode mirror).",
                )
                return 1
            }
            if (rootRecords.isNotEmpty()) {
                var dir = conversionDir ?: setup.profileDir.resolve("conversion-${Instant.now().toString().replace(":", "").take(15)}")
                Files.createDirectories(dir)
                journal = journal.copy(conversionDir = dir.toString())
                for (record in rootRecords) {
                    val source = setup.syncRoot.resolve(record.path.removePrefix("/"))
                    if (!Files.exists(source)) continue // already moved by an interrupted run
                    val bucket = if (record.cls == PathClass.BOTH_IDENTICAL) "retired" else "quarantine"
                    val destination = dir.resolve(bucket).resolve(record.path.removePrefix("/"))
                    if (Files.exists(destination)) {
                        System.err.println("Error: the resume destination already exists with different content: $destination")
                        return 1
                    }
                    Files.createDirectories(destination.parent)
                    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
                    journal =
                        journal.copy(
                            moves =
                                journal.moves + MovedFile(
                                    from = source.toString(),
                                    to = destination.toString(),
                                    bytes = record.syncRootBytes ?: 0L,
                                    bucket = bucket,
                                ),
                            quarantined =
                                if (bucket == "quarantine") {
                                    journal.quarantined + QuarantinedFile(
                                        remotePath = record.path,
                                        quarantinePath = destination.toString(),
                                        bytes = record.syncRootBytes ?: 0L,
                                    )
                                } else {
                                    journal.quarantined
                                },
                        )
                    Journal.save(setup.profileDir, journal)
                    if (bucket == "retired") retired++ else quarantinedCount++
                    if (verbose) println("  moved ${record.path} -> $bucket")
                }
                deleteEmptyDirs(setup.syncRoot, keepRoot = true)
                val manifest = dir.resolve("manifest.json")
                Files.writeString(
                    manifest,
                    Json { prettyPrint = true }.encodeToString(
                        Journal.serializer(),
                        journal,
                    ),
                )
                if ("PRESERVE" !in journal.phases) journal = journal.withPhase("PRESERVE")
                Journal.save(setup.profileDir, journal)
                println("Retired the sync_root: $retired identical file(s) moved aside, $quarantinedCount quarantined in $dir")
            } else {
                println("The sync_root holds no files — nothing to retire.")
                if ("PRESERVE" !in journal.phases) journal = journal.withPhase("PRESERVE")
                Journal.save(setup.profileDir, journal)
            }
        }

        // Phase PUBLISH: the mode becomes visible only after the byte work is done.
        if ("PUBLISH" !in journal.phases) {
            val updated = org.krost.unidrive.sync.setProfileMode(Files.readString(setup.configFile), setup.profileName, targetMode)
            Files.writeString(setup.configFile, updated)
            journal = journal.withPhase("PUBLISH")
            Journal.save(setup.profileDir, journal)
        }

        // Mirror adopt: reset the enumeration baseline so the first sync gathers fresh and the
        // absence of a cloud row can never be read as a delete.
        if (targetMode == "mirror" && !journal.baselineCleared) {
            if (Files.exists(setup.dbPath)) {
                val db = StateDatabase(setup.dbPath)
                try {
                    db.initialize()
                    db.clearEnumerationBaseline()
                } finally {
                    db.close()
                }
            }
            journal = journal.copy(baselineCleared = true)
            Journal.save(setup.profileDir, journal)
        }

        journal = journal.withPhase("CONVERTED").copy(convertedAt = Instant.now().toString())
        Journal.save(setup.profileDir, journal)
        println("Profile '${setup.profileName}' converted to mode '$targetMode'. Journal: ${Journal.file(setup.profileDir)}")
        if (targetMode == "mount" && quarantinedCount > 0) {
            println("Next: review the quarantined files, then 'unidrive -p ${setup.profileName} migrate upload-quarantined'.")
        }
        return 0
    }

    private fun legacyInventoryInputs(
        setup: Setup,
        cacheDir: Path,
    ): LegacyInventory.Inputs =
        LegacyInventory.Inputs(
            profileName = setup.profileName,
            providerType = setup.providerType,
            profileDir = setup.profileDir,
            syncRoot = setup.syncRoot,
            cacheDir = cacheDir,
            syncPaths = emptyList(),
            excludePatterns = SyncConfig.DEFAULT_EXCLUDE_PATTERNS,
            configFacts = emptyList(),
            clientStateDir = MigrateInventoryCommand.defaultClientStateDir(),
        )

    /** Deletes now-empty directories under [root], deepest first; [root] itself is kept. */
    private fun deleteEmptyDirs(
        root: Path,
        keepRoot: Boolean,
    ) {
        Files
            .walk(root)
            .use { walked ->
                walked
                    .sorted(Comparator.reverseOrder())
                    .filter { Files.isDirectory(it) }
                    .forEach { d ->
                        if (!(keepRoot && d == root)) {
                            runCatching { Files.delete(d) }
                        }
                    }
            }
    }
}
