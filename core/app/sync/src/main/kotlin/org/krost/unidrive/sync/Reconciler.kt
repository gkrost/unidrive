package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * UD-240i: lightweight stand-in for `Files.isRegularFile` + `Files.size`
 * that combines both into a single Windows `GetFileAttributesEx` syscall.
 * Returning `null` means "not a regular file" (or stat failed) — the
 * same negative outcome as `!Files.isRegularFile`. Tests inject a
 * counting / synthetic probe; production calls [Reconciler.probeRealFs].
 */
data class LocalFileInfo(
    val size: Long,
)

class Reconciler(
    private val db: StateDatabase,
    private val syncRoot: Path,
    private val defaultPolicy: ConflictPolicy,
    private val conflictOverrides: Map<String, ConflictPolicy> = emptyMap(),
    private val excludePatterns: List<String> = emptyList(),
    // UD-240i: seam for tests + future caching. detectMoves used to call
    // `Files.isRegularFile` + `Files.size` directly inside an O(D × U)
    // double loop — on a 67k-upload first-sync that was ~5M Windows
    // GetFileAttributesEx syscalls. Routing through this probe lets the
    // regression test inject a counting stub and assert the bound, and
    // the algorithm fix groups uploads by size from a single pre-pass.
    private val localFsProbe: (Path) -> LocalFileInfo? = ::probeRealFs,
) {
    private val log = LoggerFactory.getLogger(Reconciler::class.java)

    fun reconcile(
        remoteChanges: Map<String, CloudItem>,
        localChanges: Map<String, ChangeState>,
        // UD-240g: defaulted so existing callers (tests, anything pre-UD-240g)
        // stay source-compatible. SyncEngine wires the live reporter through
        // so the CLI and IPC clients see reconcile-phase movement.
        reporter: ProgressReporter = ProgressReporter.Silent,
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

        // UD-240g: bulk-load DB entries once at the top of reconcile and route every
        // subsequent lookup through in-memory maps. The pre-fix path issued one
        // db.getEntry(path) per (remote ∪ local) path PLUS two full-table scans PLUS
        // per-action lookups in detectMoves / detectRemoteRenames — ~86k single-row
        // SELECTs on a 67k-local + 19k-remote first-sync. SQLite's per-statement
        // overhead on Windows dominated wall time; the silent reconcile pass that
        // motivated this ticket was 90% I/O syscalls, not query plan.
        //
        // entryByLcPath replaces db.getEntryCaseInsensitive (SQLite COLLATE NOCASE
        // is ASCII-only; lowercase(Locale.ROOT) folds Unicode too — a slight
        // behavioural improvement on case-insensitive filesystems with non-ASCII
        // names, fully covered by existing case-collision tests with ASCII paths).
        val allDbEntries = db.getAllEntries()
        val entryByPath = allDbEntries.associateBy { it.path }
        val entryByLcPath = allDbEntries.associateBy { it.path.lowercase(Locale.ROOT) }
        val entryByRemoteId = allDbEntries.mapNotNull { e -> e.remoteId?.let { it to e } }.toMap()

        val actions = mutableListOf<SyncAction>()
        val allPaths =
            (remoteChanges.keys + localChanges.keys)
                .filter { path -> excludePatterns.none { pattern -> matchesGlob(path, pattern) } }
        val pinRules = db.getPinRules()

        // UD-240g: heartbeat into reporter every 5k items / 10s during the main
        // path walk (mirrors LocalScanner / SyncEngine.gatherRemoteChanges). The
        // bulk-load above made reconcile fast enough that on small drives the
        // heartbeat may never fire — that's fine; the bookend `total` event
        // below still tells the reporter the phase happened.
        val totalPaths = allPaths.size
        reporter.onReconcileProgress(0, totalPaths)
        val heartbeat = ScanHeartbeat({ count -> reporter.onReconcileProgress(count, totalPaths) })
        var processed = 0

        for (path in allPaths) {
            val remoteItem = remoteChanges[path]
            val localState = localChanges[path] ?: ChangeState.UNCHANGED
            val entry = entryByPath[path]

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
            processed++
            heartbeat.tick(processed)
        }
        reporter.onReconcileProgress(totalPaths, totalPaths)

        // Detect case collisions for new local files
        for ((path, state) in localChanges) {
            if (state != ChangeState.NEW) continue
            val existing = entryByLcPath[path.lowercase(Locale.ROOT)]
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
        for (entry in allDbEntries) {
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
        for (entry in allDbEntries) {
            if (entry.isFolder) continue
            if (entry.remoteId != null) continue
            if (!entry.isHydrated) continue
            if (entry.path in coveredPaths) continue
            if (excludePatterns.any { matchesGlob(entry.path, it) }) continue
            val localPath = safeResolveLocal(syncRoot, entry.path)
            if (!Files.isRegularFile(localPath)) continue
            actions.add(SyncAction.Upload(entry.path))
        }

        detectMoves(actions, entryByPath)
        detectRemoteRenames(actions, remoteChanges, entryByRemoteId)

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

    private fun detectMoves(
        actions: MutableList<SyncAction>,
        entryByPath: Map<String, SyncEntry>,
    ) {
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>()
        if (deletes.isEmpty()) return

        // Folder moves: DeleteRemote(oldFolder) + CreateRemoteFolder(newFolder)
        val folderCreates = actions.filterIsInstance<SyncAction.CreateRemoteFolder>()
        val matchedFolderDeletes = mutableSetOf<String>()
        for (del in deletes) {
            val entry = entryByPath[del.path] ?: continue
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

        // File moves: DeleteRemote(oldFile) + Upload(newFile) with matching size.
        // UD-240i: the original implementation walked every (delete × upload) pair
        // and called Files.isRegularFile + Files.size on the upload's local path
        // once per pair — O(D × U) Windows GetFileAttributesEx syscalls. Captured
        // live 2026-05-02 17:48 via jstack on PID 12764: ~5M syscalls / ~4 min on
        // a 67k-upload first-sync. The fix is a single pre-pass over uploads,
        // grouping them by size in a Map<Long, List<Upload>>; each delete then
        // does an O(1) bySize lookup and picks the first un-matched candidate.
        // Total probe calls drops from D × U to U.
        val uploads = actions.filterIsInstance<SyncAction.Upload>()
        val uploadsBySize: Map<Long, List<SyncAction.Upload>> =
            uploads
                .mapNotNull { up ->
                    val info = localFsProbe(resolveLocal(up.path)) ?: return@mapNotNull null
                    up to info.size
                }.groupBy({ it.second }, { it.first })

        val matchedUploads = mutableSetOf<String>()
        for (del in deletes) {
            if (del.path in matchedFolderDeletes) continue
            val entry = entryByPath[del.path] ?: continue
            if (entry.isFolder || entry.remoteId == null) continue

            val candidate =
                uploadsBySize[entry.remoteSize]
                    ?.firstOrNull { it.path !in matchedUploads } ?: continue
            actions.remove(del)
            actions.remove(candidate)
            actions.add(
                SyncAction.MoveRemote(
                    path = candidate.path,
                    fromPath = del.path,
                    remoteId = entry.remoteId,
                ),
            )
            matchedUploads.add(candidate.path)
        }
    }

    private fun detectRemoteRenames(
        actions: MutableList<SyncAction>,
        remoteChanges: Map<String, CloudItem>,
        entryByRemoteId: Map<String, SyncEntry>,
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
            val oldEntry = entryByRemoteId[remoteItem.id] ?: continue
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
        /**
         * UD-240i: combined isRegularFile + size in a single
         * `Files.readAttributes` call (one Windows GetFileAttributesEx
         * syscall). Mirrors the previous `if (!Files.isRegularFile(p))
         * continue; Files.size(p)` pair without changing behaviour:
         *   - returns null on non-regular path, ENOENT, or any IOException
         *     (matches the old "skip on bad path" semantic)
         *   - returns size for regular files only
         * Production default for [Reconciler.localFsProbe].
         */
        fun probeRealFs(p: Path): LocalFileInfo? =
            try {
                val attrs = Files.readAttributes(p, BasicFileAttributes::class.java)
                if (attrs.isRegularFile) LocalFileInfo(attrs.size()) else null
            } catch (_: java.io.IOException) {
                null
            }

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
