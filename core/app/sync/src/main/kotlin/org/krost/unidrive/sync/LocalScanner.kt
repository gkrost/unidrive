package org.krost.unidrive.sync

import org.krost.unidrive.engine.localNameIssue
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.ScanHeartbeat
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

class LocalScanner(
    private val syncRoot: Path,
    private val db: StateDatabase,
    private val excludePatterns: List<String> = emptyList(),
    // #112: algorithm used to compute remoteHash strings for this provider.
    // Null means "no verifiable hash" — fall back to mtime+size only.
    private val hashAlgorithm: HashAlgorithm? = null,
    // Remote-style scope roots; empty = walk everything. Only in-scope subtrees and
    // the folders leading to them are walked, so local content outside the scope is
    // neither scanned nor given a pending-upload row.
    private val scope: List<String> = emptyList(),
) {
    private val log = LoggerFactory.getLogger(LocalScanner::class.java)
    private val scopeAncestors: Set<String> = SyncScope.ancestors(scope)

    private fun inScope(relativePath: String): Boolean = SyncScope.contains(relativePath, scope)

    private fun onScopePath(relativePath: String): Boolean = inScope(relativePath) || relativePath in scopeAncestors

    // UD-736: count of files where visitFileFailed swallowed an IOException so
    // the walk could continue. Reset at the start of each scan(). Caller can
    // read this after scan() returns to surface a "skipped N entries" notice.
    var lastScanSkipped: Int = 0
        private set

    // #503/#491: local siblings whose names are equal after NFC but differ in code points
    // cannot both live under one cloud name. Both are refused (owner decision, #491). Rebuilt
    // by every scan(): the clash exists for as long as the twins do.
    //  - blockedPaths: absolute on-disk paths of the refused members (files skipped,
    //    folders pruned with their whole subtree).
    //  - blockedKeys: their shared NFC remote-style paths. Never reported as changes and
    //    never DELETED: a state row for the key is left exactly as it is (nothing is deleted
    //    remotely or locally; a previously synced member just stops being uploaded).
    private val blockedPaths = mutableSetOf<Path>()
    private val blockedKeys = mutableSetOf<String>()

    private fun underBlockedKey(path: String): Boolean = blockedKeys.any { path == it || path.startsWith("$it/") }

    /** #503: whether [path] (an NFC path) is, or lies below, a name the last [scan] refused as an NFC clash. */
    fun isUnderNfcCollision(path: String): Boolean = underBlockedKey(path)

    /** #503: the NFC paths the last [scan] refused (empty when there is no clash). */
    val nfcCollisionKeys: Set<String> get() = blockedKeys.toSet()

    private fun refuseNfcTwins(dir: Path) {
        val byKey = LinkedHashMap<String, MutableList<Path>>()
        try {
            Files.newDirectoryStream(dir).use { ds ->
                for (child in ds) {
                    // Ordinal NFC comparison only; no case folding (sigma / final sigma are distinct).
                    byKey.getOrPut(PathNormalizer.nfc(child.fileName.toString())) { mutableListOf() }.add(child)
                }
            }
        } catch (e: IOException) {
            log.debug("#503: cannot list {} to check for NFC twins: {}", dir, e.message)
            return
        }
        for ((nfcName, members) in byKey) {
            if (members.map { it.fileName.toString() }.distinct().size < 2) continue
            val nfcPath = PathNormalizer.nfc("/" + syncRoot.relativize(dir.resolve(nfcName)).toString().replace('\\', '/'))
            if (isExcluded(nfcPath) || !onScopePath(nfcPath)) continue
            blockedPaths.addAll(members)
            blockedKeys.add(nfcPath)
            val names = members.map { it.fileName.toString() }.sorted()
            log.warn(
                "Local path collision at {}: {} are the same name for the cloud (Unicode normalisation, #491); " +
                    "neither is synced - rename one",
                nfcPath,
                if (names.size == 2) "${names[0]} and ${names[1]}" else names.joinToString(", "),
            )
        }
    }

    private fun isExcluded(relativePath: String): Boolean = excludePatterns.any { pattern -> Reconciler.matchesGlob(relativePath, pattern) }

    /**
     * Walks the sync root and reports what differs from state.db.
     *
     * With [detectDeletions] (a full sync) every alive row the walk did not see is also checked against
     * the file system and reported as DELETED, which loads all rows once and costs one exclude-pattern
     * run and a file-system call per row. The daemon's rescan (#504, #552) only uploads and never acts on
     * a deletion, so it passes `false`: rows are then looked up by path for the files the walk visits,
     * and the cost follows the files on disk instead of the rows in state.db.
     */
    fun scan(
        detectDeletions: Boolean = true,
        onProgress: ((Int) -> Unit)? = null,
    ): Map<String, ChangeState> {
        val changes = mutableMapOf<String, ChangeState>()
        blockedPaths.clear()
        blockedKeys.clear()
        val seenPaths = mutableSetOf<String>()
        var skipped = 0

        // Load all DB entries once — avoids N+1 queries during file tree walk. Without deletion detection the
        // walk asks for the few rows it needs instead (#552).
        val dbEntries = if (detectDeletions) db.getAllEntries().associateBy { it.path } else null

        fun knownEntry(path: String): SyncEntry? = if (dbEntries != null) dbEntries[path] else db.getEntry(path)

        // UD-742 / UD-352: heartbeat — fire onProgress every 5000 items OR
        // every 10s wall-clock since the last fire, whichever comes first.
        // Math lives in the shared ScanHeartbeat helper so the local + remote
        // scan paths stay in lockstep.
        var visited = 0
        val heartbeat = onProgress?.let { cb -> ScanHeartbeat(cb) }

        // UD-240h: wrap the walk in a single SQLite transaction. The walk's
        // visitFile pre-writes a UD-901 pending-upload row for every NEW file
        // it sees — on a 67k-file first sync that's 67k single-row INSERTs,
        // each its own transaction (LocalScanner runs in the engine's Gather
        // phase, before SyncEngine.kt:361's beginBatch). Wrapping here drops
        // those 67k commits to one. Failures roll back the whole walk's
        // UD-901 pre-writes — safer than partial rows for the next sync's
        // recovery loops to interpret.
        db.beginBatch()
        var batchCommitted = false
        try {

        if (Files.isDirectory(syncRoot)) refuseNfcTwins(syncRoot)
        if (Files.isDirectory(syncRoot)) Files.walkFileTree(
            syncRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (file in blockedPaths) return FileVisitResult.CONTINUE
                    // #171: canonicalize to NFC so an NFD on-disk name matches the
                    // NFC remote/state.db key in the reconciler.
                    val relativePath = PathNormalizer.nfc("/" + syncRoot.relativize(file).toString().replace('\\', '/'))
                    if (isExcluded(relativePath)) return FileVisitResult.CONTINUE
                    if (!inScope(relativePath)) return FileVisitResult.CONTINUE
                    // A tree can arrive via WSL or an extended Win32 path. Do not
                    // persist a pending upload Windows cannot resolve later (#526).
                    localNameIssue(relativePath)?.let { reason ->
                        skipped++
                        log.warn("Skipping local file {}: {}", relativePath, reason)
                        return FileVisitResult.CONTINUE
                    }
                    seenPaths.add(relativePath)
                    visited++

                    val entry = knownEntry(relativePath)
                    if (entry == null) {
                        changes[relativePath] = ChangeState.NEW
                        // UD-901: write a pending-upload row immediately so the file's
                        // localSize is visible to `status` before the upload completes.
                        // remoteId=null marks "not yet uploaded"; applyUpload() later
                        // upserts the same path, promoting the row to a fully-synced
                        // state once the byte transfer succeeds.
                        //
                        // UD-209b: don't claim isHydrated=true for a sparse leftover
                        // (interrupted-sync placeholder physically present but with no
                        // real bytes). Without this guard, the engine adopts the file
                        // as fully synced and never re-downloads — UD-222 invariant
                        // silently regressed by UD-901 otherwise.
                        val sparseLeftover = isSparseLeftover(file, attrs.size())
                        db.upsertEntry(
                            SyncEntry(
                                path = relativePath,
                                remoteId = null,
                                remoteHash = null,
                                remoteSize = 0,
                                remoteModified = null,
                                localMtime = attrs.lastModifiedTime().toMillis(),
                                localSize = attrs.size(),
                                isFolder = false,
                                isPinned = false,
                                isHydrated = !sparseLeftover,
                                lastSynced = Instant.EPOCH,
                            ),
                        )
                    } else if (entry.isHydrated) {
                        val currentMtime = attrs.lastModifiedTime().toMillis()
                        val currentSize = attrs.size()
                        if (currentMtime != entry.localMtime || currentSize != entry.localSize) {
                            // #112: mtime changed but size is the same — potential touch-only.
                            // Hash-compare to avoid a needless re-upload when content is identical.
                            // Only gate on size-unchanged to skip large-file hashing on real edits.
                            val isTouchOnly =
                                currentMtime != entry.localMtime &&
                                    currentSize == entry.localSize &&
                                    hashAlgorithm != null &&
                                    !entry.remoteHash.isNullOrEmpty()
                            // #396: no comparable remote hash (Internxt: hashAlgorithm() is null) —
                            // fall back to the SHA-256 the engine recorded when it wrote or sent
                            // these bytes. Rows without one behave exactly as before.
                            val isLocalHashTouchOnly =
                                !isTouchOnly &&
                                    currentMtime != entry.localMtime &&
                                    currentSize == entry.localSize &&
                                    entry.localHash != null
                            if (isTouchOnly) {
                                val localHash = HashVerifier.computeHash(file, hashAlgorithm)
                                if (localHash == entry.remoteHash) {
                                    // Content unchanged — refresh tracked mtime so this file
                                    // isn't re-hashed on every subsequent scan.
                                    db.upsertEntry(entry.copy(localMtime = currentMtime))
                                } else {
                                    changes[relativePath] = ChangeState.MODIFIED
                                }
                            } else if (isLocalHashTouchOnly) {
                                // An unreadable file (locked by another process) can't be verified:
                                // treat it as changed, as before, rather than abort the walk.
                                val unchanged =
                                    try {
                                        HashVerifier.computeSha256Hex(file) == entry.localHash
                                    } catch (e: IOException) {
                                        log.debug("#396: cannot hash {} to check a touch-only change: {}", file, e.message)
                                        false
                                    }
                                if (unchanged) {
                                    log.debug(
                                        "#396: {} mtime {} -> {} with identical content; treating as touch-only",
                                        relativePath,
                                        entry.localMtime,
                                        currentMtime,
                                    )
                                    db.upsertEntry(entry.copy(localMtime = currentMtime))
                                } else {
                                    changes[relativePath] = ChangeState.MODIFIED
                                }
                            } else {
                                changes[relativePath] = ChangeState.MODIFIED
                            }
                        }
                    } else if (entry.remoteId != null &&
                        !entry.isFolder &&
                        !looksLikePlaceholder(file, entry, shorterIsPartialDownload = false)
                    ) {
                        // Not hydrated, yet the file holds real bytes: the user saved into the
                        // placeholder stub. Report the edit so the recovery download cannot replace
                        // it. A stub (zero bytes, or zeros of the remote size) is skipped: its mtime
                        // is synthetic and the recovery download is what fills it.
                        changes[relativePath] = ChangeState.MODIFIED
                    }

                    heartbeat?.tick(visited)
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir == syncRoot) return FileVisitResult.CONTINUE
                    if (dir in blockedPaths) return FileVisitResult.SKIP_SUBTREE
                    // #171: canonicalize to NFC (see visitFile).
                    val relativePath = PathNormalizer.nfc("/" + syncRoot.relativize(dir).toString().replace('\\', '/'))
                    if (isExcluded(relativePath)) return FileVisitResult.SKIP_SUBTREE
                    if (!onScopePath(relativePath)) return FileVisitResult.SKIP_SUBTREE
                    refuseNfcTwins(dir)
                    seenPaths.add(relativePath)

                    if (knownEntry(relativePath) == null) {
                        changes[relativePath] = ChangeState.NEW
                    }
                    return FileVisitResult.CONTINUE
                }

                // UD-736: SimpleFileVisitor's default re-throws and aborts the
                // entire walk. That's catastrophic when one Cloud-Files-API
                // placeholder fails to recall (foreign client offline — UD-900),
                // a permission-denied entry shows up, or an in-flight rename
                // races us. Log and continue so the rest of the tree is still
                // visited; the entry just doesn't get marked seen, so the
                // existing DB-vs-disk reconciliation logic decides what to do
                // (DELETE or skip via Files.exists check).
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    skipped++
                    log.warn(
                        "Skipping unreadable file {} ({}: {})",
                        file,
                        exc.javaClass.simpleName,
                        exc.message,
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )

        lastScanSkipped = skipped

        if (dbEntries != null) {
            for (entry in dbEntries.values) {
                if (entry.path !in seenPaths) {
                    if (isExcluded(entry.path)) continue
                    if (!onScopePath(entry.path)) continue
                    if (underBlockedKey(entry.path)) continue
                    // #526: a name the filesystem cannot resolve (a trailing space, creatable through
                    // an extended-length path or a Linux tool) threw here and aborted the whole scan.
                    // Skip it with the walk's own warning shape and leave the row exactly as it is.
                    if (localNameIssue(entry.path) != null) {
                        log.warn("Skipping local file {}: the name cannot be represented on this filesystem", entry.path)
                        continue
                    }
                    val localPath = safeResolveLocal(syncRoot, entry.path)
                    if (!Files.exists(localPath)) {
                        changes[entry.path] = ChangeState.DELETED
                    }
                }
            }
        }

        db.commitBatch()
        batchCommitted = true
        return changes
        } finally {
            // UD-240h: roll back any UD-901 pre-writes if the walk threw past
            // commitBatch. Without this, autoCommit stays false and the
            // engine's later beginBatch silently piggybacks on our open tx.
            if (!batchCommitted) {
                try {
                    db.rollbackBatch()
                } catch (e: Exception) {
                    log.warn("UD-240h: rollbackBatch failed in scan() finally", e)
                }
            }
        }
    }

    // UD-209b: detect sparse-file leftovers (interrupted-sync placeholders) so they
    // don't get classified as fully-hydrated. Posix-only, returns false on Windows
    // and on any error so the safer "assume hydrated" default applies. Only worth
    // checking files larger than one filesystem page (4 KiB), since smaller files
    // can't be reliably detected as sparse on tmpfs/ext4 (minimum allocation unit).
    // Mirrors the production check in PlaceholderManager.isSparse.
    private fun isSparseLeftover(
        path: Path,
        size: Long,
    ): Boolean {
        if (size <= 4096L) return false
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) return false
        return try {
            val proc =
                ProcessBuilder("stat", "--format=%b", path.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
            val output =
                proc.inputStream
                    .bufferedReader()
                    .readLine()
                    ?.trim() ?: return false
            proc.waitFor()
            val blocks = output.toLongOrNull() ?: return false
            blocks * 512 < size
        } catch (_: Exception) {
            false
        }
    }
}
