package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ChangeState
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class LocalScanner(
    private val syncRoot: Path,
    private val db: StateDatabase,
    private val excludePatterns: List<String> = emptyList(),
) {
    private fun isExcluded(relativePath: String): Boolean = excludePatterns.any { pattern -> Reconciler.matchesGlob(relativePath, pattern) }

    fun scan(): Map<String, ChangeState> {
        val changes = mutableMapOf<String, ChangeState>()
        val seenPaths = mutableSetOf<String>()

        // Load all DB entries once — avoids N+1 queries during file tree walk
        val dbEntries = db.getAllEntries().associateBy { it.path }

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

                    val entry = dbEntries[relativePath]
                    if (entry == null) {
                        changes[relativePath] = ChangeState.NEW
                    } else if (entry.isHydrated) {
                        val currentMtime = attrs.lastModifiedTime().toMillis()
                        val currentSize = attrs.size()
                        if (currentMtime != entry.localMtime || currentSize != entry.localSize) {
                            changes[relativePath] = ChangeState.MODIFIED
                        }
                    }
                    // Skip dehydrated files for modification check (mtime is synthetic)

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
            },
        )

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
