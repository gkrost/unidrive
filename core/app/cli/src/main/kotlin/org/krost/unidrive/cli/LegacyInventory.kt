package org.krost.unidrive.cli

import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.Reconciler
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncScope
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.sql.Connection
import java.sql.DriverManager

/**
 * #560 U5a: the read-only inventory of a legacy profile, the first step of its one-time conversion
 * (docs/adr/independent-profiles.md, "Migration"). It reports what a conversion would have to preserve: the
 * profile's config, its state.db, the bytes in the sync_root and in the hydration cache, upload staging and the
 * platform client's recovery files.
 *
 * **Read-only by contract.** The profile must be stopped: a held process lock refuses the run, and while it runs a
 * shared lock keeps `sync` and `daemon` from starting (no lock file or PID is written, no lock is ever broken).
 * state.db is read from a `VACUUM INTO` copy taken through a `mode=ro` connection, so nothing migrates the real
 * file. Local files are only read. No provider is constructed and no network is used.
 *
 * **Unknown is not clean.** A copy counts as identical to the cloud only on a content-hash match: the provider's
 * content hash in `remote_hash` (OneDrive's quickXor), or the SHA-256 `local_hash` of the bytes last exchanged with
 * the cloud. Internxt's `remote_hash` is a version token, not a content hash, and is never compared with bytes.
 */
internal object LegacyInventory {
    data class Inputs(
        val profileName: String,
        val providerType: String,
        val profileDir: Path,
        val syncRoot: Path,
        val cacheDir: Path,
        val syncPaths: List<String> = emptyList(),
        val excludePatterns: List<String> = emptyList(),
        // Allow-listed, secret-free config keys, in display order.
        val configFacts: List<Pair<String, String>> = emptyList(),
        // The platform client's state dir (Windows: %LOCALAPPDATA%\unidrive\state); null = not checked.
        val clientStateDir: Path? = null,
        val tempRoot: Path = Path.of(System.getProperty("java.io.tmpdir")),
    )

    sealed interface Outcome {
        data class Collected(val report: Report) : Outcome

        data class Refused(val message: String) : Outcome
    }

    /** Where a path's local bytes are. Rows without either copy are [NO_LOCAL_COPY]; files without a row [UNTRACKED]. */
    enum class PathClass(val label: String) {
        BOTH_IDENTICAL("in both, identical"),
        BOTH_DIFFERENT("in both, different"),
        SYNC_ROOT_ONLY("in sync_root only"),
        CACHE_ONLY("in cache only"),
        NO_LOCAL_COPY("row without a local copy"),
        UNTRACKED("file without a row"),
    }

    /** One local copy compared with the cloud version its row records. */
    enum class CloudMatch {
        /** Equal to the provider's content hash in `remote_hash`. */
        SAME_CONTENT_HASH,

        /** Equal to `local_hash`, the SHA-256 of the bytes last downloaded or uploaded. */
        SAME_RECORDED_HASH,
        DIFFERS,

        /** No content hash to compare with: metadata alone never proves a copy clean. */
        UNKNOWN,

        /** No cloud identity: no row, or a `local:` row that was never uploaded. */
        NOT_IN_CLOUD,
    }

    enum class RowKind { CLOUD, LOCAL }

    data class PathRecord(
        val path: String,
        val cls: PathClass,
        val rowKind: RowKind?,
        val hydrated: Boolean?,
        val cacheBacked: Boolean?,
        val syncRootBytes: Long?,
        val cacheBytes: Long?,
        val syncRootMatch: CloudMatch?,
        val cacheMatch: CloudMatch?,
        val excluded: Boolean,
        val outOfScope: Boolean,
        val tombstoned: Boolean,
        val risks: List<String>,
    )

    data class DbFacts(
        val schemaVersion: String?,
        val rowsByStatus: Map<String, Int>,
        val aliveFiles: Int,
        val aliveFolders: Int,
        val localIdRows: Int,
        // `StateDatabase.pendingUploadPaths()`: alive files with a `local:` id and is_hydrated. Matches rows written
        // through the mount AND rows written by LocalScanner (#560 corrections table).
        val pendingUploads: List<String>,
        // The mount's replay subset: a cache copy exists, in scope, not excluded (`HydrationImpl.replayable`).
        val pendingMountReplayable: List<String>,
        // The mirror's kind: the bytes are in the sync_root, which `sync` uploads from.
        val pendingMirror: List<String>,
        val pendingWithoutBytes: List<String>,
        // `local:` file rows LocalScanner did not claim as hydrated (sparse leftovers).
        val localUnhydrated: List<String>,
        val uploadRefused: List<String>,
        val lastErrorAt: List<String>,
        val downloadQuarantined: Int,
        // Over alive hydrated files: "true" / "false" / "null".
        val cacheBacked: Map<String, Int>,
        val tombstones: Int,
        val scanInProgress: Boolean,
    )

    data class LocalTree(val files: Int, val bytes: Long, val unreadable: Int)

    data class StagingFacts(
        val uploadTombstoneFiles: Int,
        val uploadTombstoneBytes: Long,
        val cacheTempFiles: Int,
        val cacheTempBytes: Long,
    )

    /** A file or directory reported by name and size only; its content is never read. */
    data class SizedEntry(val name: String, val isDirectory: Boolean, val bytes: Long, val files: Int)

    data class LockFacts(val lockFilePresent: Boolean, val recordedHolderPid: Long?)

    data class Report(
        val inputs: Inputs,
        val contentHash: String?,
        val lock: LockFacts,
        val db: DbFacts?,
        val syncRootTree: LocalTree,
        val cacheTree: LocalTree,
        // Every path with a local copy, plus rows at risk without one. Cloud-only rows are only counted.
        val files: List<PathRecord>,
        val classCounts: Map<PathClass, Int>,
        val classBytes: Map<PathClass, Long>,
        val staging: StagingFacts,
        val profileEntries: List<SizedEntry>,
        val clientFiles: List<SizedEntry>,
    ) {
        val atRisk: List<PathRecord> get() = files.filter { it.risks.isNotEmpty() }

        fun record(path: String): PathRecord = files.firstOrNull { it.path == path } ?: error("no record for $path")
    }

    /**
     * The provider's content-hash algorithm, by type, so the inventory never constructs a provider (no credentials,
     * no network). Must agree with `CloudProvider.hashAlgorithm()`: OneDrive stores quickXor in `remote_hash`;
     * Internxt stores a version token there, localfs nothing.
     */
    fun contentHashAlgorithm(providerType: String): HashAlgorithm? =
        when (providerType.lowercase()) {
            "onedrive" -> HashAlgorithm.QuickXor
            else -> null
        }

    fun collect(inputs: Inputs): Outcome {
        val lock = ProcessLock(inputs.profileDir.resolve(".lock"))
        val hold = lock.tryHoldReadOnly() ?: return Outcome.Refused(heldMessage(inputs.profileName, lock.readHolderInfo()))
        try {
            val lockFacts = LockFacts(Files.exists(inputs.profileDir.resolve(".lock")), lock.readHolderPid())
            val rows =
                try {
                    readState(inputs)
                } catch (e: Exception) {
                    return Outcome.Refused(
                        "state.db of profile '${inputs.profileName}' could not be read consistently: ${e.message}. Nothing was changed.",
                    )
                }
            val report = classify(inputs, lockFacts, rows)
            if (!hold.stillFree()) {
                return Outcome.Refused(
                    "A unidrive process started for profile '${inputs.profileName}' during the inventory, so the report " +
                        "would not be consistent. Nothing was changed; stop it and run the inventory again.",
                )
            }
            return Outcome.Collected(report)
        } finally {
            hold.close()
        }
    }

    private fun heldMessage(
        profile: String,
        holder: ProcessLock.HolderInfo?,
    ): String {
        val who =
            when (holder?.mode) {
                ProcessLock.Mode.DAEMON -> "`unidrive daemon`"
                ProcessLock.Mode.SYNC -> "`unidrive sync`"
                null -> "another unidrive process"
            }
        val pid = holder?.let { " (PID ${it.pid})" } ?: ""
        val stop =
            when (holder?.mode) {
                ProcessLock.Mode.DAEMON -> "Stop it with `unidrive -p $profile daemon stop`"
                ProcessLock.Mode.SYNC -> "Stop the sync first (Ctrl-C its terminal, or end the process)"
                null -> "Stop it first"
            }
        return "Profile '$profile' is in use by $who$pid. The inventory reads a stopped profile only and never breaks a lock. " +
            "$stop, then run `unidrive -p $profile migrate inventory` again. Nothing was read or changed."
    }

    // ── state.db ─────────────────────────────────────────────────────────────

    private class Row(
        val remoteId: String,
        val path: String,
        val isFolder: Boolean,
        val isHydrated: Boolean,
        val status: String,
        val remoteHash: String?,
        val localHash: String?,
        val cacheBacked: Boolean?,
        val uploadRefused: String?,
        val lastErrorAt: String?,
        val downloadQuarantined: Boolean,
    ) {
        val isLocal: Boolean get() = remoteId.startsWith("local:")
        val alive: Boolean get() = status == "EXISTS"
    }

    private class State(val rows: List<Row>, val schemaVersion: String?, val scanInProgress: Boolean)

    /** Null when the profile has no state.db. */
    private fun readState(inputs: Inputs): State? {
        val source = inputs.profileDir.resolve("state.db")
        if (!Files.exists(source)) return null
        Files.createDirectories(inputs.tempRoot)
        val dir = Files.createTempDirectory(inputs.tempRoot, "unidrive-inventory-")
        try {
            val copy = dir.resolve("state.db")
            // A read-only connection (Path.toUri: percent-encoded, see StateDatabase.snapshotOf) and VACUUM INTO: a
            // consistent copy that includes committed WAL content, and nothing runs against the real file.
            DriverManager.getConnection("jdbc:sqlite:${source.toAbsolutePath().toUri()}?mode=ro&busy_timeout=5000").use { c ->
                val target = copy.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                c.createStatement().use { it.execute("VACUUM INTO '$target'") }
            }
            // The schema guards of a read-only open (older or newer unidrive), against the copy.
            StateDatabase(copy, readOnly = true).apply {
                initialize()
                close()
            }
            DriverManager.getConnection("jdbc:sqlite:${copy.toAbsolutePath().toUri()}?mode=ro").use { c ->
                val state = c.prepareStatement("SELECT key, value FROM sync_state").use { st ->
                    st.executeQuery().use { rs ->
                        buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
                    }
                }
                return State(
                    rows = readRows(c),
                    schemaVersion = state["schema_version"],
                    scanInProgress = state[StateDatabase.SCAN_IN_PROGRESS_ID] != null,
                )
            }
        } finally {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private fun readRows(c: Connection): List<Row> {
        val columns = c.prepareStatement("PRAGMA table_info(sync_entries)").use { st ->
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
        }
        // Columns added by later migrations may be missing from an older v2 file: read them as NULL.
        fun col(name: String) = if (name in columns) name else "NULL AS $name"
        val sql =
            "SELECT remote_id, path, is_folder, is_hydrated, status, remote_hash, " +
                listOf("local_hash", "cache_backed", "upload_refused", "last_error_at", "download_quarantined")
                    .joinToString(", ") { col(it) } +
                " FROM sync_entries"
        val out = mutableListOf<Row>()
        c.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val cacheBacked = rs.getInt("cache_backed").let { if (rs.wasNull()) null else it == 1 }
                    out +=
                        Row(
                            remoteId = rs.getString("remote_id"),
                            path = rs.getString("path"),
                            isFolder = rs.getInt("is_folder") == 1,
                            isHydrated = rs.getInt("is_hydrated") != 0,
                            status = rs.getString("status"),
                            remoteHash = rs.getString("remote_hash"),
                            localHash = rs.getString("local_hash"),
                            cacheBacked = cacheBacked,
                            uploadRefused = rs.getString("upload_refused"),
                            lastErrorAt = rs.getString("last_error_at"),
                            downloadQuarantined = rs.getInt("download_quarantined") == 1,
                        )
                }
            }
        }
        return out
    }

    // ── local bytes ──────────────────────────────────────────────────────────

    private class Walk(val files: Map<String, Path>, val sizes: Map<String, Long>, val unreadable: Int)

    /** Regular files under [dir] keyed by their NFC sync path (`/a/b.txt`); [skip] subtrees are not entered. */
    private fun walk(
        dir: Path,
        skip: List<Path>,
        keep: (String) -> Boolean = { true },
    ): Walk {
        val files = sortedMapOf<String, Path>()
        val sizes = mutableMapOf<String, Long>()
        var unreadable = 0
        if (!Files.isDirectory(dir)) return Walk(files, sizes, 0)
        val base = dir.toAbsolutePath().normalize()
        // Only dirs nested in [dir]: one that contains it (a sync_root under the temp dir) skips nothing.
        val skipped = skip.map { it.toAbsolutePath().normalize() }.filter { it != base && it.startsWith(base) }
        Files.walkFileTree(
            base,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    d: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult = if (skipped.any { d.startsWith(it) }) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(
                    f: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile && keep(f.fileName.toString())) {
                        val key = PathNormalizer.nfc("/" + base.relativize(f).toString().replace('\\', '/'))
                        files[key] = f
                        sizes[key] = attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    f: Path,
                    exc: IOException,
                ): FileVisitResult {
                    unreadable++
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return Walk(files, sizes, unreadable)
    }

    // HydrationImpl.isStagingTemp: an interrupted download or serve copy, not a cache copy of a file.
    private fun isCacheTemp(name: String): Boolean = name.contains(".hydrating-") || (name.startsWith(".ud-serve-") && name.endsWith(".tmp"))

    private fun sha256(file: Path): String? = runCatching { HashVerifier.computeSha256Hex(file) }.getOrNull()

    private fun sizeOf(p: Path): Pair<Long, Int> {
        if (!Files.isDirectory(p)) return (runCatching { Files.size(p) }.getOrDefault(0L)) to 1
        val w = walk(p, emptyList())
        return w.sizes.values.sum() to w.files.size
    }

    private fun sizedEntries(
        dir: Path?,
        filter: (String) -> Boolean,
    ): List<SizedEntry> {
        if (dir == null || !Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.toList() }
            .filter { filter(it.fileName.toString()) }
            .sortedBy { it.fileName.toString() }
            .map { p ->
                val (bytes, files) = sizeOf(p)
                SizedEntry(p.fileName.toString(), Files.isDirectory(p), bytes, files)
            }
    }

    // ── classification ───────────────────────────────────────────────────────

    private fun classify(
        inputs: Inputs,
        lockFacts: LockFacts,
        state: State?,
    ): Report {
        val algorithm = contentHashAlgorithm(inputs.providerType)
        // Never count unidrive's own dirs as sync_root content, should they be nested in it.
        val internal = listOf(inputs.cacheDir, inputs.profileDir, inputs.tempRoot)
        val syncWalk = walk(inputs.syncRoot, internal)
        val cacheTemps = walk(inputs.cacheDir, emptyList()) { isCacheTemp(it) }
        val cacheWalk = walk(inputs.cacheDir, emptyList()) { !isCacheTemp(it) }

        val rows = state?.rows.orEmpty()
        val aliveFiles = rows.filter { it.alive && !it.isFolder }.associateBy { it.path }
        val tombstonedPaths = rows.filter { !it.alive && !it.isFolder }.map { it.path }.toSet()

        fun excluded(path: String) = inputs.excludePatterns.any { Reconciler.matchesGlob(path, it) }

        fun outOfScope(path: String) = !SyncScope.contains(path, inputs.syncPaths)

        val pending = aliveFiles.values.filter { it.isLocal && it.isHydrated }.map { it.path }.sorted()
        val pendingSet = pending.toSet()

        val shaCache = HashMap<Path, String?>()

        fun shaOf(p: Path) = shaCache.getOrPut(p) { sha256(p) }

        fun match(
            row: Row?,
            file: Path,
        ): CloudMatch =
            when {
                row == null || row.isLocal -> CloudMatch.NOT_IN_CLOUD
                algorithm != null && !row.remoteHash.isNullOrEmpty() ->
                    if (runCatching { HashVerifier.matches(file, row.remoteHash, algorithm) }.getOrDefault(false)) {
                        CloudMatch.SAME_CONTENT_HASH
                    } else {
                        CloudMatch.DIFFERS
                    }
                row.localHash != null ->
                    if (shaOf(file).equals(row.localHash, ignoreCase = true)) CloudMatch.SAME_RECORDED_HASH else CloudMatch.DIFFERS
                else -> CloudMatch.UNKNOWN
            }

        val records = mutableListOf<PathRecord>()
        val classCounts = sortedMapOf<PathClass, Int>()
        val classBytes = sortedMapOf<PathClass, Long>()
        val allPaths = (aliveFiles.keys + syncWalk.files.keys + cacheWalk.files.keys).toSortedSet()
        for (path in allPaths) {
            val row = aliveFiles[path]
            val s = syncWalk.files[path]
            val c = cacheWalk.files[path]
            val sSize = syncWalk.sizes[path]
            val cSize = cacheWalk.sizes[path]
            val cls =
                when {
                    row == null -> PathClass.UNTRACKED
                    s != null && c != null ->
                        if (sSize == cSize && shaOf(s) != null && shaOf(s) == shaOf(c)) PathClass.BOTH_IDENTICAL else PathClass.BOTH_DIFFERENT
                    s != null -> PathClass.SYNC_ROOT_ONLY
                    c != null -> PathClass.CACHE_ONLY
                    else -> PathClass.NO_LOCAL_COPY
                }
            val sMatch = s?.let { match(row, it) }
            val cMatch = c?.let { match(row, it) }
            val isExcluded = excluded(path)
            val isOutOfScope = outOfScope(path)
            val tombstoned = row == null && path in tombstonedPaths

            val risks = mutableListOf<String>()
            if (row == null) {
                risks +=
                    when {
                        isExcluded -> "excluded: kept local, never uploaded"
                        isOutOfScope -> "outside sync_path: never synced"
                        tombstoned -> "deleted in state.db, but the file is still on disk"
                        else -> "untracked: no row in state.db, never uploaded"
                    }
            } else if (row.isLocal) {
                when {
                    path in pendingSet && s == null && c == null -> risks += "pending upload, and no local copy was found"
                    path in pendingSet -> risks += "pending upload: never reached the cloud"
                    s != null || c != null -> risks += "never uploaded (local row not marked hydrated)"
                }
                if (isExcluded) risks += "excluded: kept local, never uploaded"
                if (row.lastErrorAt != null) risks += "last upload attempt failed"
            }
            if (row?.uploadRefused != null) risks += "upload refused by the provider"
            if (cls == PathClass.BOTH_DIFFERENT) risks += "the sync_root and cache copies differ"
            for ((where, m) in listOf("sync_root" to sMatch, "cache" to cMatch)) {
                when (m) {
                    CloudMatch.DIFFERS -> risks += "$where copy differs from the cloud version"
                    CloudMatch.UNKNOWN -> risks += "$where copy: no content hash to compare (unknown)"
                    else -> Unit
                }
            }
            if (cls == PathClass.CACHE_ONLY && cMatch != CloudMatch.SAME_CONTENT_HASH && cMatch != CloudMatch.SAME_RECORDED_HASH) {
                risks += "the only local copy is in the hydration cache"
            }

            classCounts.merge(cls, 1, Int::plus)
            classBytes.merge(cls, (sSize ?: 0L) + (cSize ?: 0L), Long::plus)
            if (s != null || c != null || risks.isNotEmpty()) {
                records +=
                    PathRecord(
                        path = path,
                        cls = cls,
                        rowKind = row?.let { if (it.isLocal) RowKind.LOCAL else RowKind.CLOUD },
                        hydrated = row?.isHydrated,
                        cacheBacked = row?.cacheBacked,
                        syncRootBytes = sSize,
                        cacheBytes = cSize,
                        syncRootMatch = sMatch,
                        cacheMatch = cMatch,
                        excluded = isExcluded,
                        outOfScope = isOutOfScope,
                        tombstoned = tombstoned,
                        risks = risks,
                    )
            }
        }

        val db =
            state?.let {
                val replayable = pending.filter { p -> cacheWalk.files.containsKey(p) && !excluded(p) && !outOfScope(p) }
                val mirror = pending.filter { p -> syncWalk.files.containsKey(p) && !excluded(p) && !outOfScope(p) }
                val noBytes = pending.filter { p -> !cacheWalk.files.containsKey(p) && !syncWalk.files.containsKey(p) }
                val alive = rows.filter { r -> r.alive }
                DbFacts(
                    schemaVersion = it.schemaVersion,
                    rowsByStatus = rows.groupingBy { r -> r.status }.eachCount().toSortedMap(),
                    aliveFiles = aliveFiles.size,
                    aliveFolders = alive.count { r -> r.isFolder },
                    localIdRows = alive.count { r -> r.isLocal },
                    pendingUploads = pending,
                    pendingMountReplayable = replayable,
                    pendingMirror = mirror,
                    pendingWithoutBytes = noBytes,
                    localUnhydrated = aliveFiles.values.filter { r -> r.isLocal && !r.isHydrated }.map { r -> r.path }.sorted(),
                    uploadRefused = alive.filter { r -> r.uploadRefused != null }.map { r -> r.path }.sorted(),
                    lastErrorAt = alive.filter { r -> r.lastErrorAt != null }.map { r -> r.path }.sorted(),
                    downloadQuarantined = alive.count { r -> r.downloadQuarantined },
                    cacheBacked =
                        aliveFiles.values.filter { r -> r.isHydrated }
                            .groupingBy { r -> r.cacheBacked?.toString() ?: "null" }.eachCount(),
                    tombstones = rows.count { r -> !r.alive },
                    scanInProgress = it.scanInProgress,
                )
            }

        val tombstoneDir = inputs.profileDir.resolve("upload-tombstones")
        val tombstoneWalk = walk(tombstoneDir, emptyList())
        return Report(
            inputs = inputs,
            contentHash =
                when (algorithm) {
                    HashAlgorithm.QuickXor -> "quickXor"
                    HashAlgorithm.Md5Hex -> "md5"
                    HashAlgorithm.Sha256Hex -> "sha256"
                    null -> null
                },
            lock = lockFacts,
            db = db,
            syncRootTree = LocalTree(syncWalk.files.size, syncWalk.sizes.values.sum(), syncWalk.unreadable),
            cacheTree = LocalTree(cacheWalk.files.size, cacheWalk.sizes.values.sum(), cacheWalk.unreadable),
            files = records,
            classCounts = classCounts,
            classBytes = classBytes,
            staging =
                StagingFacts(
                    uploadTombstoneFiles = tombstoneWalk.files.size,
                    uploadTombstoneBytes = tombstoneWalk.sizes.values.sum(),
                    cacheTempFiles = cacheTemps.files.size,
                    cacheTempBytes = cacheTemps.sizes.values.sum(),
                ),
            profileEntries = sizedEntries(inputs.profileDir) { true },
            clientFiles = sizedEntries(inputs.clientStateDir) { it.startsWith("${inputs.profileName}.") },
        )
    }
}
