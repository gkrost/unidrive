package org.krost.unidrive.sync

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
) {
    private val log = LoggerFactory.getLogger(LocalScanner::class.java)

    // UD-736: count of files where visitFileFailed swallowed an IOException so
    // the walk could continue. Reset at the start of each scan(). Caller can
    // read this after scan() returns to surface a "skipped N entries" notice.
    var lastScanSkipped: Int = 0
        private set

    private fun isExcluded(relativePath: String): Boolean = excludePatterns.any { pattern -> Reconciler.matchesGlob(relativePath, pattern) }

    fun scan(onProgress: ((Int) -> Unit)? = null): Map<String, ChangeState> {
        val changes = mutableMapOf<String, ChangeState>()
        val seenPaths = mutableSetOf<String>()
        var skipped = 0

        // Load all DB entries once — avoids N+1 queries during file tree walk
        val dbEntries = db.getAllEntries().associateBy { it.path }

        // UD-742: heartbeat — fire onProgress every 5000 items OR every 10s
        // wall-clock since the last fire, whichever comes first. Both thresholds
        // are relative to the previous fire so a fast walk fires by item-count
        // and a slow walk fires by wall-clock.
        var visited = 0
        var lastFireItems = 0
        var lastFireMs = System.currentTimeMillis()
        val heartbeatIntervalMs = 10_000L
        val heartbeatItemThreshold = 5_000

        fun maybeFireHeartbeat() {
            if (onProgress == null) return
            val now = System.currentTimeMillis()
            if (visited - lastFireItems >= heartbeatItemThreshold || now - lastFireMs >= heartbeatIntervalMs) {
                onProgress.invoke(visited)
                lastFireItems = visited
                lastFireMs = now
            }
        }

        // Walk local filesystem
        Files.walkFileTree(
            syncRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val relativePath = "/" + syncRoot.relativize(file).toString().replace('\\', '/')
                    if (isExcluded(relativePath)) return FileVisitResult.CONTINUE
                    seenPaths.add(relativePath)
                    visited++

                    val entry = dbEntries[relativePath]
                    if (entry == null) {
                        changes[relativePath] = ChangeState.NEW
                        // UD-901: write a pending-upload row immediately so the file's
                        // localSize is visible to `status` before the upload completes.
                        // remoteId=null marks "not yet uploaded"; isHydrated=true because
                        // the bytes are physically on disk. applyUpload() later upserts
                        // the same path, promoting the row to a fully-synced state once
                        // the byte transfer succeeds.
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
                                isHydrated = true,
                                lastSynced = Instant.EPOCH,
                            ),
                        )
                    } else if (entry.isHydrated) {
                        val currentMtime = attrs.lastModifiedTime().toMillis()
                        val currentSize = attrs.size()
                        if (currentMtime != entry.localMtime || currentSize != entry.localSize) {
                            changes[relativePath] = ChangeState.MODIFIED
                        }
                    }
                    // Skip dehydrated files for modification check (mtime is synthetic)

                    maybeFireHeartbeat()
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir == syncRoot) return FileVisitResult.CONTINUE
                    val relativePath = "/" + syncRoot.relativize(dir).toString().replace('\\', '/')
                    if (isExcluded(relativePath)) return FileVisitResult.SKIP_SUBTREE
                    seenPaths.add(relativePath)

                    if (dbEntries[relativePath] == null) {
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

        // Check for deletions: entries in DB but not on disk
        for (entry in dbEntries.values) {
            if (entry.path !in seenPaths) {
                if (isExcluded(entry.path)) continue
                val localPath = safeResolveLocal(syncRoot, entry.path)
                if (!Files.exists(localPath)) {
                    changes[entry.path] = ChangeState.DELETED
                }
            }
        }

        return changes
    }
}
