package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

class Reconciler(
    private val db: StateDatabase,
    private val syncRoot: Path,
    private val defaultPolicy: ConflictPolicy,
    private val conflictOverrides: Map<String, ConflictPolicy> = emptyMap(),
    private val excludePatterns: List<String> = emptyList(),
) {
    private val log = LoggerFactory.getLogger(Reconciler::class.java)

    fun reconcile(
        remoteChanges: Map<String, CloudItem>,
        localChanges: Map<String, ChangeState>,
    ): List<SyncAction> {
        // UD-240g: bookend the reconcile pass with log breadcrumbs. Before this,
        // the phase between `Delta: N items, hasMore=false` (gatherRemoteChanges)
        // and `reporter.onActionCount` (post-reconcile) emitted ZERO log lines —
        // a 67k-local + 19k-remote first-sync looked like a hang for many seconds
        // while ~86k single-row SELECTs ran. The duration line at exit gives
        // operators a baseline to spot future regressions.
        val reconcileStart = System.currentTimeMillis()
        log.info(
            "Reconcile started: {} remote, {} local",
            remoteChanges.size,
            localChanges.size,
        )
        val actions = mutableListOf<SyncAction>()
        val allPaths =
            (remoteChanges.keys + localChanges.keys)
                .filter { path -> excludePatterns.none { pattern -> matchesGlob(path, pattern) } }
        val pinRules = db.getPinRules()

        for (path in allPaths) {
            val remoteItem = remoteChanges[path]
            val localState = localChanges[path] ?: ChangeState.UNCHANGED
            val entry = db.getEntry(path)

            val remoteState =
                when {
                    remoteItem == null -> ChangeState.UNCHANGED
                    remoteItem.deleted -> ChangeState.DELETED
                    entry == null -> ChangeState.NEW
                    // UD-209b: a UD-901 pending-upload row (remoteId=null, no remote
                    // metadata yet) is not a "remote-modified" signal — the remote side
                    // hasn't been observed before. Treat as remote-NEW so the "both new"
                    // branch can adopt-or-download instead of falling through to the
                    // unhandled (NEW, MODIFIED) case and silently dropping the action.
                    entry.remoteId == null && entry.remoteHash == null -> ChangeState.NEW
                    remoteItem.hash != entry.remoteHash ||
                        remoteItem.modified != entry.remoteModified -> ChangeState.MODIFIED
                    else -> ChangeState.UNCHANGED
                }

            // Skip if nothing changed on either side
            if (remoteState == ChangeState.UNCHANGED && localState == ChangeState.UNCHANGED) continue

            val action = resolveAction(path, localState, remoteState, remoteItem, entry, pinRules)
            if (action != null) actions.add(action)
        }

        // Detect case collisions for new local files
        for ((path, state) in localChanges) {
            if (state != ChangeState.NEW) continue
            val existing = db.getEntryCaseInsensitive(path)
            if (existing != null && existing.path != path) {
                // Remove any previously generated Upload action for this path
                actions.removeAll { it.path == path && it is SyncAction.Upload }
                actions.add(
                    SyncAction.Conflict(
                        path = path,
                        localState = ChangeState.NEW,
                        remoteState = ChangeState.UNCHANGED,
                        remoteItem = null,
                        policy = policyForPath(path),
                    ),
                )
            }
        }

        // Detect case collisions between new local files in the same cycle
        val newLocalPaths = localChanges.filterValues { it == ChangeState.NEW }.keys.toList()
        val caseGroups = newLocalPaths.groupBy { it.lowercase() }
        for ((_, paths) in caseGroups) {
            if (paths.size > 1) {
                for (path in paths) {
                    actions.removeAll { it.path == path && it is SyncAction.Upload }
                    if (actions.none { it.path == path && it is SyncAction.Conflict }) {
                        actions.add(
                            SyncAction.Conflict(
                                path = path,
                                localState = ChangeState.NEW,
                                remoteState = ChangeState.UNCHANGED,
                                remoteItem = null,
                                policy = policyForPath(path),
                            ),
                        )
                    }
                }
            }
        }

        // UD-225: previously-failed downloads leave DB entries with isHydrated=false + 0-byte
        // local stubs. Reconciler's UNCHANGED+UNCHANGED skip (line 41) would silently drop
        // them on the next sync, and if the cursor gets promoted in between they are orphaned.
        // Before action sort, synthesise a DownloadContent for any such entry that no action
        // already covers.
        val coveredPaths = actions.mapTo(mutableSetOf()) { it.path }
        for (entry in db.getAllEntries()) {
            if (entry.isFolder || entry.isHydrated) continue
            if (entry.remoteSize <= 0) continue
            if (entry.path in coveredPaths) continue
            if (excludePatterns.any { matchesGlob(entry.path, it) }) continue
            val remoteItem =
                remoteChanges[entry.path] ?: CloudItem(
                    id = entry.remoteId ?: "",
                    name = entry.path.substringAfterLast('/'),
                    path = entry.path,
                    size = entry.remoteSize,
                    isFolder = false,
                    modified = entry.remoteModified,
                    created = null,
                    hash = entry.remoteHash,
                    mimeType = null,
                )
            actions.add(SyncAction.DownloadContent(entry.path, remoteItem))
        }

        // UD-901: previously-interrupted uploads leave DB entries with remoteId=null and
        // isHydrated=true (LocalScanner's pending-upload placeholder). On the first scan the
        // path also fires ChangeState.NEW so resolveAction emits Upload. On subsequent scans,
        // the file exists with no mtime/size delta — scanner reports nothing, the
        // UNCHANGED+UNCHANGED skip drops the path, and the upload never retries. Synthesise
        // an Upload here so the next sync cycle picks up where the last one left off. Mirrors
        // the UD-225 download-recovery loop above.
        for (entry in db.getAllEntries()) {
            if (entry.isFolder) continue
            if (entry.remoteId != null) continue
            if (!entry.isHydrated) continue
            if (entry.path in coveredPaths) continue
            if (excludePatterns.any { matchesGlob(entry.path, it) }) continue
            val localPath = safeResolveLocal(syncRoot, entry.path)
            if (!Files.isRegularFile(localPath)) continue
            actions.add(SyncAction.Upload(entry.path))
        }

        detectMoves(actions)
        detectRemoteRenames(actions, remoteChanges)

        val sorted = sortActions(actions)
        log.info(
            "Reconcile complete: {} actions in {}ms",
            sorted.size,
            System.currentTimeMillis() - reconcileStart,
        )
        return sorted
    }

    private fun resolveAction(
        path: String,
        localState: ChangeState,
        remoteState: ChangeState,
        remoteItem: CloudItem?,
        entry: SyncEntry?,
        pinRules: List<Pair<String, Boolean>>,
    ): SyncAction? =
        when {
            // Both deleted
            localState == ChangeState.DELETED && remoteState == ChangeState.DELETED ->
                SyncAction.RemoveEntry(path)

            // Both new
            localState == ChangeState.NEW && remoteState == ChangeState.NEW -> {
                val localPath = resolveLocal(path)
                when {
                    // Both created a folder — merge, no conflict
                    remoteItem != null && remoteItem.isFolder && Files.isDirectory(localPath) ->
                        SyncAction.CreatePlaceholder(path, remoteItem, shouldHydrate = false)
                    // File exists locally with matching size — adopt (pin rules may still hydrate)
                    remoteItem != null &&
                        !remoteItem.isFolder &&
                        Files.isRegularFile(localPath) &&
                        Files.size(localPath) == remoteItem.size ->
                        SyncAction.CreatePlaceholder(path, remoteItem, shouldHydrate = shouldPin(path, remoteItem, pinRules))
                    // Real conflict — sizes differ or other mismatch
                    else ->
                        SyncAction.Conflict(path, localState, remoteState, remoteItem, policyForPath(path))
                }
            }

            // Both modified
            localState == ChangeState.MODIFIED && remoteState == ChangeState.MODIFIED -> {
                SyncAction.Conflict(path, localState, remoteState, remoteItem, policyForPath(path))
            }

            // Delete vs modify conflicts
            localState == ChangeState.DELETED && remoteState == ChangeState.MODIFIED ->
                SyncAction.Conflict(path, localState, remoteState, remoteItem, policyForPath(path))
            localState == ChangeState.MODIFIED && remoteState == ChangeState.DELETED ->
                SyncAction.Conflict(path, localState, remoteState, remoteItem, policyForPath(path))

            // Remote only changes
            // UD-222: files always hydrate (real bytes, via Pass 2 concurrent DownloadContent);
            // folders emit a metadata-only CreatePlaceholder/UpdatePlaceholder in Pass 1. Previously
            // CreatePlaceholder defaulted to shouldPin(...) → false without pin rules → NUL-byte
            // stubs (346 GB burn on UD-712 run). Pin rules are reserved for future CfApi
            // (UD-401/402/403).
            localState == ChangeState.UNCHANGED && remoteState == ChangeState.NEW && remoteItem != null ->
                if (remoteItem.isFolder) {
                    SyncAction.CreatePlaceholder(path, remoteItem, shouldHydrate = false)
                } else {
                    SyncAction.DownloadContent(path, remoteItem)
                }
            localState == ChangeState.UNCHANGED && remoteState == ChangeState.MODIFIED && remoteItem != null ->
                if (remoteItem.isFolder) {
                    SyncAction.UpdatePlaceholder(path, remoteItem, wasHydrated = false)
                } else {
                    SyncAction.DownloadContent(path, remoteItem)
                }
            localState == ChangeState.UNCHANGED && remoteState == ChangeState.DELETED ->
                SyncAction.DeleteLocal(path)

            // Local only changes
            localState == ChangeState.NEW && remoteState == ChangeState.UNCHANGED -> {
                val local = resolveLocal(path)
                if (Files.isDirectory(local)) {
                    SyncAction.CreateRemoteFolder(path)
                } else {
                    SyncAction.Upload(path)
                }
            }
            localState == ChangeState.MODIFIED && remoteState == ChangeState.UNCHANGED ->
                SyncAction.Upload(path)
            localState == ChangeState.DELETED && remoteState == ChangeState.UNCHANGED ->
                // UD-901: a pending-upload row (entry.remoteId == null) that vanished
                // before its first upload has nothing to delete on the remote side —
                // just drop the placeholder row. Otherwise propagate the deletion.
                if (entry != null && entry.remoteId == null) {
                    SyncAction.RemoveEntry(path)
                } else {
                    SyncAction.DeleteRemote(path)
                }

            else -> null
        }

    private fun shouldPin(
        path: String,
        item: CloudItem,
        pinRules: List<Pair<String, Boolean>>,
    ): Boolean {
        if (item.isFolder) return false
        if (item.size == 0L) return true

        for ((pattern, pinned) in pinRules) {
            if (matchesGlob(path, pattern)) return pinned
        }
        return false
    }

    private fun policyForPath(path: String): ConflictPolicy {
        for ((prefix, policy) in conflictOverrides) {
            if (path.startsWith("/$prefix") || path.startsWith(prefix)) return policy
        }
        return defaultPolicy
    }

    private fun detectMoves(actions: MutableList<SyncAction>) {
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>()
        if (deletes.isEmpty()) return

        // Folder moves: DeleteRemote(oldFolder) + CreateRemoteFolder(newFolder)
        val folderCreates = actions.filterIsInstance<SyncAction.CreateRemoteFolder>()
        val matchedFolderDeletes = mutableSetOf<String>()
        for (del in deletes) {
            val entry = db.getEntry(del.path) ?: continue
            if (!entry.isFolder || entry.remoteId == null) continue

            val oldName = del.path.substringAfterLast("/")
            for (create in folderCreates) {
                // Same folder name, any parent — covers both renames (same parent) and cross-parent moves
                if (create.path.substringAfterLast("/") != oldName) continue
                actions.add(
                    SyncAction.MoveRemote(
                        path = create.path,
                        fromPath = del.path,
                        remoteId = entry.remoteId,
                    ),
                )
                // Remove the folder delete and create
                actions.remove(del)
                actions.remove(create)
                // Remove child deletes under old prefix — API move handles them
                val oldPrefix = del.path + "/"
                val newPrefix = create.path + "/"
                val movedNames =
                    actions
                        .filter { it is SyncAction.DeleteRemote && it.path.startsWith(oldPrefix) }
                        .map { it.path.removePrefix(oldPrefix) }
                        .toSet()
                actions.removeAll { it is SyncAction.DeleteRemote && it.path.startsWith(oldPrefix) }
                // Only remove new-prefix actions that have a matching old-prefix counterpart (moved files)
                // Keep uploads for files that are genuinely new (never existed under old folder)
                actions.removeAll { action ->
                    action.path.startsWith(newPrefix) &&
                        action.path.removePrefix(newPrefix) in movedNames
                }
                matchedFolderDeletes.add(del.path)
                break
            }
        }

        // File moves: DeleteRemote(oldFile) + Upload(newFile) with matching size
        val uploads = actions.filterIsInstance<SyncAction.Upload>()
        val matchedUploads = mutableSetOf<String>()
        for (del in deletes) {
            if (del.path in matchedFolderDeletes) continue
            val entry = db.getEntry(del.path) ?: continue
            if (entry.isFolder || entry.remoteId == null) continue

            for (up in uploads) {
                if (up.path in matchedUploads) continue
                val localPath = resolveLocal(up.path)
                if (!Files.isRegularFile(localPath)) continue
                if (Files.size(localPath) == entry.remoteSize) {
                    actions.remove(del)
                    actions.remove(up)
                    actions.add(
                        SyncAction.MoveRemote(
                            path = up.path,
                            fromPath = del.path,
                            remoteId = entry.remoteId,
                        ),
                    )
                    matchedUploads.add(up.path)
                    break
                }
            }
        }
    }

    private fun detectRemoteRenames(
        actions: MutableList<SyncAction>,
        remoteChanges: Map<String, CloudItem>,
    ) {
        // UD-222: renames of remote non-folders now arrive as DownloadContent (not
        // CreatePlaceholder) because hydration moved to Pass 2. Scan both action types.
        val candidates: List<SyncAction> =
            actions.filter {
                it is SyncAction.CreatePlaceholder || it is SyncAction.DownloadContent
            }
        if (candidates.isEmpty()) return

        for (candidate in candidates) {
            val remoteItem = remoteChanges[candidate.path] ?: continue
            val oldEntry = db.getEntryByRemoteId(remoteItem.id) ?: continue
            if (oldEntry.path == candidate.path) continue

            actions.remove(candidate)
            actions.removeAll { it is SyncAction.DeleteLocal && it.path == oldEntry.path }
            if (oldEntry.isFolder) {
                val oldPrefix = oldEntry.path + "/"
                actions.removeAll { action ->
                    action.path.startsWith(oldPrefix) &&
                        (action is SyncAction.CreatePlaceholder || action is SyncAction.DownloadContent)
                }
            }
            actions.add(
                SyncAction.MoveLocal(
                    path = candidate.path,
                    fromPath = oldEntry.path,
                    remoteItem = remoteItem,
                ),
            )
        }
    }

    private fun sortActions(actions: List<SyncAction>): List<SyncAction> =
        actions.sortedWith(
            compareBy(
                { actionPriority(it) },
                {
                    if (it is SyncAction.DeleteLocal || it is SyncAction.DeleteRemote) {
                        -it.path.count { c ->
                            c == '/'
                        }
                    } else {
                        it.path.count { c -> c == '/' }
                    }
                },
                { it.path },
            ),
        )

    private fun actionPriority(action: SyncAction): Int =
        when (action) {
            is SyncAction.CreatePlaceholder -> if (action.remoteItem.isFolder) 0 else 2
            is SyncAction.CreateRemoteFolder -> 0
            is SyncAction.UpdatePlaceholder -> 2
            is SyncAction.DownloadContent -> 2
            is SyncAction.Upload -> 2
            is SyncAction.DeleteLocal -> 1
            is SyncAction.DeleteRemote -> 1
            is SyncAction.MoveRemote -> 1
            is SyncAction.MoveLocal -> 1
            is SyncAction.Conflict -> 3
            is SyncAction.RemoveEntry -> 4
        }

    private fun resolveLocal(remotePath: String): java.nio.file.Path = safeResolveLocal(syncRoot, remotePath)

    companion object {
        fun matchesGlob(
            path: String,
            pattern: String,
        ): Boolean {
            val cleanPath = path.removePrefix("/")
            val cleanPattern = pattern.removePrefix("/")
            val regex = buildGlobRegex(cleanPattern)
            // If pattern has no '/', also match against just the filename (basename match)
            return if ('/' !in cleanPattern) {
                val baseName = cleanPath.substringAfterLast('/')
                Regex("^$regex$").matches(baseName)
            } else {
                Regex("^$regex$").matches(cleanPath)
            }
        }

        private fun buildGlobRegex(pattern: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                when {
                    // **/ at current position → zero or more path segments with trailing slash
                    pattern.startsWith("**/", i) -> {
                        sb.append("(.+/)?")
                        i += 3
                    }
                    // /** at end → slash + anything (including deeper paths)
                    pattern.startsWith("/**", i) && i + 3 == pattern.length -> {
                        sb.append("(/.*)?")
                        i += 3
                    }
                    // /** in middle → slash + anything + slash
                    pattern.startsWith("/**", i) -> {
                        sb.append("/(.+/)?")
                        i += 3
                    }
                    // ** alone (entire pattern or remaining) → match anything
                    pattern.startsWith("**", i) -> {
                        sb.append(".*")
                        i += 2
                    }
                    pattern[i] == '*' -> {
                        sb.append("[^/]*")
                        i++
                    }
                    pattern[i] == '?' -> {
                        sb.append("[^/]")
                        i++
                    }
                    pattern[i] == '.' -> {
                        sb.append("\\.")
                        i++
                    }
                    pattern[i] == '[' -> {
                        sb.append("\\[")
                        i++
                    }
                    pattern[i] == ']' -> {
                        sb.append("\\]")
                        i++
                    }
                    pattern[i] == '(' -> {
                        sb.append("\\(")
                        i++
                    }
                    pattern[i] == ')' -> {
                        sb.append("\\)")
                        i++
                    }
                    pattern[i] == '{' -> {
                        sb.append("\\{")
                        i++
                    }
                    pattern[i] == '}' -> {
                        sb.append("\\}")
                        i++
                    }
                    else -> {
                        sb.append(Regex.escape(pattern[i].toString()))
                        i++
                    }
                }
            }
            return sb.toString()
        }
    }
}
