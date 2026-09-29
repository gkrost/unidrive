package org.krost.unidrive.sync.testfixtures

import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaCursorExpiredException
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.TrashManager
import org.krost.unidrive.sync.VersionManager
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant

/**
 * A scripted provider for the dry-run purity harness. It can deep-copy itself
 * (so a twin run never touches the original) and it records every call, so the
 * harness can tell reads from mutations.
 */
class TwinProvider : CloudProvider {
    override val id = "twin"
    override val displayName = "Twin"
    override var isAuthenticated = true

    var supportsFastBootstrap = false
    var deltaItems: List<CloudItem> = emptyList()
    var incrementalItems: List<CloudItem> = emptyList()
    var deltaPages: List<Pair<List<CloudItem>, String>> = emptyList()
    var deltaCursor = "cursor-1"
    var expireResumedCursorOnce = false
    val files = linkedMapOf<String, ByteArray>()
    val calls = mutableListOf<String>()
    private var pageIndex = 0

    fun copy(): TwinProvider =
        TwinProvider().also {
            it.supportsFastBootstrap = supportsFastBootstrap
            it.deltaItems = deltaItems
            it.incrementalItems = incrementalItems
            it.deltaPages = deltaPages
            it.deltaCursor = deltaCursor
            it.expireResumedCursorOnce = expireResumedCursorOnce
            it.pageIndex = pageIndex
            files.forEach { (k, v) -> it.files[k] = v.copyOf() }
        }

    fun mutations(): List<String> = calls.filter { c -> MUTATING.any { c.startsWith("$it ") } }

    override fun capabilities(): Set<Capability> =
        buildSet {
            add(Capability.Delta)
            add(Capability.VerifyItem)
            if (supportsFastBootstrap) add(Capability.FastBootstrap)
        }

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<CloudItem> = deltaItems.filter { it.path.substringBeforeLast('/', "") == path.trimEnd('/') }

    override suspend fun getMetadata(path: String): CloudItem = deltaItems.first { it.path == path }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        calls += "download $remotePath"
        val bytes = files[remotePath] ?: ByteArray(0)
        Files.createDirectories(destination.parent)
        Files.write(destination, bytes)
        return bytes.size.toLong()
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = download(remotePath, destination)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        calls += "upload $remotePath"
        val bytes = Files.readAllBytes(localPath)
        files[remotePath] = bytes
        return item(remotePath, size = bytes.size.toLong(), hash = "uploaded")
    }

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) {
        calls += "delete $remotePath"
        files.remove(remotePath)
    }

    override suspend fun createFolder(path: String): CloudItem {
        calls += "createFolder $path"
        return item(path, folder = true)
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        calls += "move $fromPath $toPath"
        files.remove(fromPath)?.let { files[toPath] = it }
        return item(toPath)
    }

    override suspend fun deltaFromLatest(): CapabilityResult<DeltaPage> =
        if (supportsFastBootstrap) {
            CapabilityResult.Success(DeltaPage(items = emptyList(), cursor = deltaCursor, hasMore = false))
        } else {
            CapabilityResult.Unsupported(Capability.FastBootstrap, "twin provider opts out")
        }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        calls += "delta cursor=$cursor"
        if (expireResumedCursorOnce && cursor != null) {
            expireResumedCursorOnce = false
            throw DeltaCursorExpiredException("410 Gone (twin)")
        }
        if (deltaPages.isNotEmpty()) {
            val idx = pageIndex.coerceAtMost(deltaPages.size - 1)
            pageIndex = (pageIndex + 1).coerceAtMost(deltaPages.size)
            val (items, pageCursor) = deltaPages[idx]
            return DeltaPage(items = items, cursor = pageCursor, hasMore = idx < deltaPages.size - 1, complete = true)
        }
        val items = if (cursor == null) deltaItems else incrementalItems
        return DeltaPage(items = items, cursor = deltaCursor, hasMore = false, complete = true)
    }

    override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)

    companion object {
        private val MUTATING = listOf("upload", "delete", "createFolder", "move")

        fun item(
            path: String,
            size: Long = 10,
            folder: Boolean = false,
            hash: String? = if (folder) null else "hash-$path",
            deleted: Boolean = false,
        ) = CloudItem(
            id = "id-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = if (folder) 0 else size,
            isFolder = folder,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = hash,
            mimeType = null,
            deleted = deleted,
        )
    }
}

/** How the engine under test is built. Every field maps to one engine constructor argument. */
data class EngineOpts(
    val streaming: Boolean = false,
    val standingScope: List<String> = emptyList(),
    val syncPaths: List<String> = standingScope,
    val trash: Boolean = false,
    val versions: Boolean = false,
    val fastBootstrap: Boolean = false,
)

/** Everything a dry-run could leave behind, as a flat comparable map. */
class Capture(val items: Map<String, String>)

class TwinResult(
    /** Category (for example `db:sync_entries`, `fs:sync`, `provider:mutation`) to the differences found. */
    val impurities: Map<String, List<String>>,
    /** The exception the first dry-run pass threw, if any. A guard may throw; that is not an impurity. */
    val threw: String?,
)

/**
 * A self-contained sync world: local tree, state.db, log dir, cache dir and a scripted provider.
 * [dryRunTwin] clones it and dry-runs the clone, so the original is never disturbed and the
 * scenario can carry on with real passes afterwards.
 */
class World(
    private val base: Path,
    val provider: TwinProvider = TwinProvider(),
    createSyncRoot: Boolean = true,
) {
    val syncRoot: Path = base.resolve("sync")
    val logDir: Path = base.resolve("logs")
    val cacheDir: Path = base.resolve("cache")
    val dbFile: Path = base.resolve("state.db")
    val db: StateDatabase

    init {
        Files.createDirectories(base)
        Files.createDirectories(logDir)
        Files.createDirectories(cacheDir)
        if (createSyncRoot) Files.createDirectories(syncRoot)
        db = StateDatabase(dbFile)
        db.initialize()
    }

    fun engine(
        opts: EngineOpts = EngineOpts(),
        db: StateDatabase = this.db,
    ): SyncEngine =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            failureLogPath = logDir.resolve("failures.jsonl"),
            skippedOpsLogPath = logDir.resolve("skipped-ops.jsonl"),
            syncPaths = opts.syncPaths,
            standingScope = opts.standingScope,
            streamingReconciliation = opts.streaming,
            fastBootstrap = opts.fastBootstrap,
            trashManager = if (opts.trash) TrashManager(syncRoot) else null,
            versionManager = if (opts.versions) VersionManager(syncRoot) else null,
            cacheRoot = cacheDir,
        )

    /** A real pass runs on the world's database. A dry-run runs on a throwaway snapshot of it, as the CLI does. */
    suspend fun pass(
        opts: EngineOpts = EngineOpts(),
        dryRun: Boolean = false,
        skipTransfers: Boolean = false,
    ) {
        if (!dryRun) {
            engine(opts).syncOnce(skipTransfers = skipTransfers)
            return
        }
        val preview = StateDatabase.snapshotOf(dbFile, tempRoot = base.resolve("tmp"))
        try {
            engine(opts, preview).syncOnce(dryRun = true, skipTransfers = skipTransfers)
        } finally {
            preview.close()
        }
    }

    fun close() = db.close()

    fun capture(includeMutations: Boolean): Capture {
        val out = sortedMapOf<String, String>()
        dumpDb(out)
        dumpTree("sync", syncRoot, out)
        dumpTree("logs", logDir, out)
        dumpTree("cache", cacheDir, out)
        provider.files.forEach { (path, bytes) -> out["prov/file/$path"] = sha(bytes) }
        if (includeMutations) provider.mutations().takeIf { it.isNotEmpty() }?.let { out["prov/mutation"] = it.joinToString("; ") }
        return Capture(out)
    }

    /** The exact string the engine stores as `sync_root`. */
    internal fun rootString(): String = syncRoot.toAbsolutePath().normalize().toString()

    private fun dumpDb(out: MutableMap<String, String>) {
        val uri = "jdbc:sqlite:file:${dbFile.toAbsolutePath().toString().replace('\\', '/')}?mode=ro"
        DriverManager.getConnection(uri).use { c ->
            val tables =
                c.createStatement().use { st ->
                    st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            for (table in tables) {
                c.createStatement().use { st ->
                    st.executeQuery("SELECT * FROM \"$table\"").use { rs ->
                        val md = rs.metaData
                        while (rs.next()) {
                            val cols = (1..md.columnCount).map { md.getColumnName(it) to rs.getString(it) }
                            val row = cols.joinToString(";") { (n, v) -> "$n=$v" }
                            if (table == "sync_state") {
                                val value = cols.getOrNull(1)?.second.orEmpty()
                                // The clone lives at another path; compare the stored root as a placeholder.
                                out["db/sync_state/${cols[0].second}"] = if (cols[0].second == "sync_root" && value == rootString()) "<syncRoot>" else value
                            } else {
                                out["db/$table/$row"] = ""
                            }
                        }
                    }
                }
            }
        }
    }

    private fun dumpTree(
        label: String,
        dir: Path,
        out: MutableMap<String, String>,
    ) {
        if (!Files.exists(dir)) {
            out["fs/$label/."] = "missing"
            return
        }
        out["fs/$label/."] = "dir"
        Files.walk(dir).use { s ->
            s.filter { it != dir }.forEach { p ->
                val rel = dir.relativize(p).toString().replace('\\', '/')
                out["fs/$label/$rel"] =
                    if (Files.isDirectory(p)) {
                        "dir"
                    } else {
                        val size = Files.size(p)
                        val digest = if (size <= 64 * 1024) sha(Files.readAllBytes(p)) else "big"
                        "file;size=$size;mtime=${Files.getLastModifiedTime(p).toMillis()};sha=$digest"
                    }
            }
        }
    }

    /** Copies the whole world (tree, logs, cache, a consistent state.db snapshot, the provider) under [target]. */
    fun cloneInto(target: Path): World {
        Files.createDirectories(target)
        copyTree(syncRoot, target.resolve("sync"))
        copyTree(logDir, target.resolve("logs"))
        copyTree(cacheDir, target.resolve("cache"))
        val uri = "jdbc:sqlite:file:${dbFile.toAbsolutePath().toString().replace('\\', '/')}?mode=ro"
        DriverManager.getConnection(uri).use { c ->
            c.createStatement().use { it.execute("VACUUM INTO '${target.resolve("state.db").toAbsolutePath().toString().replace('\\', '/')}'") }
        }
        val clone = World(target, provider.copy(), createSyncRoot = Files.exists(syncRoot))
        // The engine refuses to run when the stored sync_root differs from the current one; point the copy at itself.
        clone.db.getSyncState("sync_root")?.takeIf { it.isNotEmpty() }?.let { clone.db.setSyncState("sync_root", clone.rootString()) }
        return clone
    }

    private fun copyTree(
        from: Path,
        to: Path,
    ) {
        if (!Files.exists(from)) return
        Files.walk(from).use { s ->
            s.forEach { p ->
                val dest = to.resolve(from.relativize(p).toString())
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    Files.copy(p, dest, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    companion object {
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

private val VOLATILE_STATE_KEYS = setOf("last_full_scan", "last_scan_secs_local", "last_scan_secs_remote", "scan_in_progress_started_at")

private fun category(key: String): String =
    when {
        key.startsWith("db/sync_state/") -> "db:sync_state.${key.removePrefix("db/sync_state/")}"
        key.startsWith("db/") -> "db:${key.removePrefix("db/").substringBefore('/')}"
        key.startsWith("fs/") -> "fs:${key.removePrefix("fs/").substringBefore('/')}"
        key == "prov/mutation" -> "provider:mutation"
        else -> "provider:content"
    }

/** Category to differences between two captures; empty when they are equal. */
internal fun diffOf(
    before: Capture,
    after: Capture,
): MutableMap<String, MutableList<String>> = diff(before, after)

private fun diff(
    before: Capture,
    after: Capture,
): MutableMap<String, MutableList<String>> {
    val out = sortedMapOf<String, MutableList<String>>()
    for (k in before.items.keys + after.items.keys) {
        val b = before.items[k]
        val a = after.items[k]
        if (b != a) out.getOrPut(category(k)) { mutableListOf() } += "$k: ${b ?: "<absent>"} -> ${a ?: "<absent>"}".take(220)
    }
    return out
}

/** Drops timestamps and durations, which differ between two identical runs. */
private fun normalized(c: Capture): Map<String, String> =
    c.items
        .mapKeys { (k, _) -> k.replace(Regex("last_synced=[^;]*"), "last_synced=~") }
        .mapValues { (k, v) ->
            when {
                k.startsWith("db/sync_state/") && k.removePrefix("db/sync_state/") in VOLATILE_STATE_KEYS -> "~"
                k.startsWith("fs/") -> v.replace(Regex("mtime=\\d+"), "mtime=~")
                else -> v
            }
        }

/**
 * Dry-runs a clone of this world twice and reports what changed on the clone. The original is not
 * touched. The result has no impurities exactly when a dry-run is pure and idempotent for this state
 * and [opts].
 */
suspend fun World.dryRunTwin(
    opts: EngineOpts,
    cloneDir: Path,
): TwinResult {
    val before = capture(includeMutations = false)
    val clone = cloneInto(cloneDir)
    try {
        var threw: String? = null
        try {
            clone.pass(opts, dryRun = true)
        } catch (e: Exception) {
            threw = "${e.javaClass.simpleName}: ${e.message?.take(140)}"
        }
        val after1 = clone.capture(includeMutations = true)
        val impurities = diff(before, after1)
        try {
            clone.pass(opts, dryRun = true)
        } catch (_: Exception) {
        }
        val after2 = clone.capture(includeMutations = true)
        val n1 = normalized(after1)
        val n2 = normalized(after2)
        val changed = (n1.keys + n2.keys).filter { n1[it] != n2[it] }
        if (changed.isNotEmpty()) impurities["idempotence"] = changed.take(5).map { "$it: ${n1[it]} -> ${n2[it]}".take(220) }.toMutableList()
        return TwinResult(impurities, threw)
    } finally {
        clone.close()
    }
}
