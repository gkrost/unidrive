package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    // #115: XDG-user-dir locale aliasing.  Injectable user-dirs.dirs content
    // (XDG_*_DIR key → basename).  Empty map = rely on the static alias table
    // alone.  SyncEngine wires the real ~/.config/user-dirs.dirs content here
    // at construction time; tests inject synthetic maps so they are host-
    // independent.  Alias resolution happens at the start of each reconcile()
    // / resolveSlice() call using the remote top-level names from that call's
    // remoteChanges argument (canonical name = existing cloud folder).
    private val xdgUserDirsOverrides: Map<String, String> = emptyMap(),
) {
    private val log = LoggerFactory.getLogger(Reconciler::class.java)

    var lastUnhydratedFolderDeletes: List<String> = emptyList()
        private set

    fun reconcile(
        remoteChanges: Map<String, CloudItem>,
        localChanges: Map<String, ChangeState>,
        // UD-240g: defaulted so existing callers (tests, anything pre-UD-240g)
        // stay source-compatible. SyncEngine wires the live reporter through
        // so the CLI and IPC clients see reconcile-phase movement.
        reporter: ProgressReporter = ProgressReporter.Silent,
        // UD-901a: syncPath filter must reach the recovery loops below — they
        // iterate `db.getAllEntries()` directly, NOT the already-scope-filtered
        // remoteChanges/localChanges. Without this parameter a `--sync-path
        // /foo` invocation would still resurrect every pending-upload row in
        // the DB regardless of its path. Confirmed live 2026-05-03: 107k
        // unrelated orphans surfaced when the user asked for ~106 in-scope
        // changes.
        syncPath: String? = null,
        // #160: when true, a hydrated row whose local file is absent is planned
        // as a re-download instead of DeleteRemote. Only safe in download-only
        // mode — in bidirectional mode a locally-deleted hydrated file is a
        // legitimate user delete that must propagate to remote.
        downloadOnly: Boolean = false,
        // #200(b): when false, the remote enumeration that produced [remoteChanges]
        // was incomplete, so a local-present/remote-absent path may be an
        // un-enumerated subtree rather than a genuinely-new file. Defer new-local
        // creates (Upload/CreateRemoteFolder) until a complete enumeration confirms
        // remote-absence — mirror of the delete-side "reap only on complete".
        enumerationComplete: Boolean = true,
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
        // Un-trash pre-pass. A delta-reported alive item whose `remote_id`
        // matches a TRASHED row flips status back to EXISTS — the reconciler
        // then treats it as either a re-download (local copy gone) or a
        // no-op (local copy present and hash matches cloud). Local-modified-
        // while-TRASHED is explicitly out of scope (v1 last-write-wins).
        //
        // Done BEFORE loading alive entries so the main loop sees the row as
        // alive and doesn't double-process. `resurrectedPaths` tracks the
        // paths so the main loop can skip them after we've already emitted
        // (or deliberately not emitted) the right action.
        // #115: alias context for transparent locale-alias adoption. The action
        // `path` stays real-local everywhere; the canonical remote path is
        // carried out-of-band via SyncAction.remoteTarget. Remote-delta keys
        // (canonical) are reverse-mapped into the real-local namespace so they
        // pair with the alias-named local row / on-disk folder. Built before the
        // un-trash pre-pass so resurrected paths are already real-local-keyed
        // and the main loop's `path in resurrectedPaths` skip matches.
        val alias = buildAliasContext(remoteChanges)

        // Reverse-map remote-delta keys (canonical) into the real-local
        // namespace so the main loop reconciles against the alias-named local
        // folder. Identity when no alias is active.
        val remoteChangesLocal =
            if (alias.isEmpty) remoteChanges
            else remoteChanges.entries.associate { (k, v) -> alias.remoteToLocal(k) to v }

        val trashedByRemoteId =
            db.recovery.trashedEntries()
                .mapNotNull { e -> e.remoteId?.let { it to e } }
                .toMap()
        val resurrectedPaths = mutableSetOf<String>()
        val resurrectedActions = mutableListOf<SyncAction>()
        if (trashedByRemoteId.isNotEmpty()) {
            for ((path, item) in remoteChangesLocal) {
                if (item.deleted) continue
                trashedByRemoteId[item.id] ?: continue
                if (!db.setStatusExists(item.id)) continue
                resurrectedPaths.add(path)
                // Check local presence at the cloud's CURRENT path (the
                // trashed row's stored path may differ in a rename-during-
                // trash edge case; v1 is last-write-wins so the cloud path
                // wins). Folders never need re-download — the metadata flip
                // is enough. `path` here is real-local (reverse-mapped), so
                // resolveLocal lands on the alias-named on-disk folder.
                val localPath = resolveLocal(path)
                if (!item.isFolder && !Files.isRegularFile(localPath)) {
                    resurrectedActions.add(SyncAction.DownloadContent(path, item))
                }
                // Local copy present + hash matches → no-op (main loop's
                // UNCHANGED+UNCHANGED branch handles the absence of action).
            }
        }

        // entryByLcPath replaces db.getEntryCaseInsensitive (SQLite COLLATE NOCASE
        // is ASCII-only; lowercase(Locale.ROOT) folds Unicode too — a slight
        // behavioural improvement on case-insensitive filesystems with non-ASCII
        // names, fully covered by existing case-collision tests with ASCII paths).
        val allDbEntries = db.getAllEntries()
        val entryByPath = allDbEntries.associateBy { it.path }
        val entryByLcPath = allDbEntries.associateBy { it.path.lowercase(Locale.ROOT) }
        val entryByRemoteId = allDbEntries.mapNotNull { e -> e.remoteId?.let { it to e } }.toMap()

        val actions = mutableListOf<SyncAction>()
        actions.addAll(resurrectedActions)

        val allPaths =
            (remoteChangesLocal.keys + localChanges.keys)
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
            // Skip paths the un-trash pre-pass already handled; otherwise a
            // LocalScanner-reported NEW (file present, was not in alive view
            // pre-flip) would trip the "Local only changes" branch and emit
            // an Upload for an already-synced file.
            if (path in resurrectedPaths) continue
            val remoteItem = remoteChangesLocal[path]
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

            val action = resolveAction(path, localState, remoteState, remoteItem, entry, pinRules, alias, downloadOnly)
            if (action != null) actions.add(action)
            processed++
            heartbeat.tick(processed)
        }
        reporter.onReconcileProgress(totalPaths, totalPaths)

        for ((path, state) in localChanges) {
            if (state != ChangeState.NEW) continue
            val existing = entryByLcPath[path.lowercase(Locale.ROOT)]
            if (existing != null && existing.path != path) {
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
        // UD-901b: snapshot action count before the recovery loops so the post-pass below
        // can identify exactly which actions came from recovery and walk their ancestor
        // chains for missing-parent CreateRemoteFolder synthesis.
        val recoveryStartIdx = actions.size
        for (entry in allDbEntries) {
            if (entry.isFolder || entry.isHydrated) continue
            if (entry.remoteSize <= 0) continue
            if (entry.path in coveredPaths) continue
            if (excludePatterns.any { matchesGlob(entry.path, it) }) continue
            // UD-901a: respect syncPath scope; orphans outside the user's requested
            // subtree must NOT be silently surfaced.
            if (!pathInSyncScope(entry.path, syncPath)) continue
            // Permanent-failure quarantine: skip rows whose last download
            // returned a stable 404 ("Bucket entry … not found"). Without
            // this guard, UD-225 recovery re-emits a DownloadContent every
            // pass and the engine burns cycles retrying the same dead
            // identifier (live evidence: 1,248 retries over 8h). The flag
            // clears automatically when a fresh delta event re-reports the
            // same remote_id via SyncEngine.updateRemoteEntries.
            if (entry.downloadQuarantined) continue
            // #115: the entry's canonical remote path is `remotePath ?: path`;
            // the delta is keyed there. The DownloadContent `path` stays real-
            // local (entry.path) so the bytes land in the alias-named folder.
            val effectiveRemote = entry.remotePath ?: entry.path
            val remoteItem =
                remoteChanges[effectiveRemote] ?: CloudItem(
                    id = entry.remoteId ?: "",
                    name = entry.path.substringAfterLast('/'),
                    path = effectiveRemote,
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
            // UD-901a: same scope guard as the UD-225 loop above.
            if (!pathInSyncScope(entry.path, syncPath)) continue
            val localPath = safeResolveLocal(syncRoot, entry.path)
            if (!Files.isRegularFile(localPath)) continue
            // #115: preserve any persisted canonical remote path as remoteTarget
            // so the retry uploads to the same canonical the row was keyed at.
            actions.add(SyncAction.Upload(entry.path, remoteTarget = entry.remotePath))
        }

        // UD-901b: orphan recovery emits Upload (and DownloadContent) for files
        // whose parent folder may not exist on remote — when a previous sync
        // was killed before its CreateRemoteFolder ran. Without parent-folder
        // synthesis here, every retry fails identically with "Folder not
        // found" and the orphan stays in DB forever.
        //
        // Strategy: for each recovery-emitted action, walk its ancestor path
        // chain. Emit CreateRemoteFolder for any ancestor that is neither
        // already in the action set nor known to exist on remote (via an
        // entryByPath row with non-null remoteId). Internxt's createFolder
        // handles 409-conflict gracefully (UD-317), so emitting a folder
        // create that turns out to already exist is a cheap no-op rather
        // than a hard failure — we err on the side of emitting.
        //
        // Sort the synthesised mkdirs by depth so Pass 1's sequential apply
        // creates parents before children inside the same prefix family.
        // sortActions() at exit will re-sort by priority + slash count
        // anyway, but the explicit ordering here keeps the behaviour
        // testable without depending on sortActions's internals.
        val recoveryEmitted = actions.subList(recoveryStartIdx, actions.size).toList()
        if (recoveryEmitted.isNotEmpty()) {
            val ancestorsToCreate = mutableSetOf<String>()
            for (action in recoveryEmitted) {
                val parts = action.path.removePrefix("/").split('/')
                if (parts.size <= 1) continue // top-level item, no parent to create
                for (i in 1 until parts.size) {
                    val ancestor = "/" + parts.take(i).joinToString("/")
                    if (ancestor in coveredPaths) continue
                    if (ancestor in ancestorsToCreate) continue
                    val existing = entryByPath[ancestor]
                    if (existing != null && existing.remoteId != null) continue
                    ancestorsToCreate.add(ancestor)
                }
            }
            for (ancestor in ancestorsToCreate.sortedBy { it.count { c -> c == '/' } }) {
                actions.add(SyncAction.CreateRemoteFolder(ancestor, remoteTarget = aliasTarget(alias, ancestor)))
                coveredPaths.add(ancestor)
            }
        }

        detectMoves(actions, entryByPath, alias)
        detectRemoteRenames(actions, remoteChanges, entryByRemoteId, alias)
        lastUnhydratedFolderDeletes = dropUnhydratedFolderDeletes(actions, entryByPath)
        deferIncompleteRemoteCreates(actions, localChanges, enumerationComplete)

        val sorted = sortActions(actions)
        log.info(
            "Reconcile complete: {} actions in {}ms",
            sorted.size,
            System.currentTimeMillis() - reconcileStart,
        )
        return sorted
    }

    /**
     * Streaming reconciliation entry point — per-page slice.
     *
     * Runs only the main resolution loop (the `for (path in allPaths)` body
     * from [reconcile]) on the page's remote items plus the matching local
     * subset. Returns raw page actions without recovery-loop synthesis,
     * case-collision detection, move detection, or final sort — those run
     * once at scan-end via [finalizeStreaming] against the union of all
     * page actions plus the deferred-bucket flush.
     *
     * Why not call [reconcile] per page: the recovery loops iterate
     * `db.getAllEntries()` and synthesize Download/Upload actions for
     * every pending row. Running them per page would emit duplicate
     * actions for every entry on every page boundary. Move detection
     * also looks across the full action set; per-page detection misses
     * cross-page moves and double-counts intra-page ones.
     *
     * Local changes scope: pass the FULL local map (LocalScanner runs to
     * completion before streaming starts per spec §1 decision 1). The
     * slice's allPaths is `pageRemote.keys ∪ localChanges.keys` so a
     * remote-arrived path can pair with a local-changed path inside the
     * same page boundary.
     */
    fun resolveSlice(
        pageRemote: Map<String, CloudItem>,
        localChanges: Map<String, ChangeState>,
        syncPath: String? = null,
        // #115 streaming fix: stable set of ALL remote top-level folder names known
        // across all pages, supplied by the streaming gather before any slice fires.
        // When null (single-shot reconcile or test call without the argument), the
        // alias context falls back to deriving top-level names from pageRemote alone
        // — which is the pre-fix behaviour and is correct for non-streaming callers.
        stableRemoteTopLevelNames: Set<String>? = null,
        // #160: mirror of the same flag on reconcile() — re-download hydrated rows
        // whose local file is absent instead of emitting DeleteRemote.
        downloadOnly: Boolean = false,
    ): List<SyncAction> {
        val allDbEntries = db.getAllEntries()
        val entryByPath = allDbEntries.associateBy { it.path }
        val pinRules = db.getPinRules()

        // #115: alias context, using the stable full-scan top-level set when
        // provided by the streaming gather. Action paths stay real-local; the
        // canonical remote path is carried out-of-band via remoteTarget.
        val alias = buildAliasContext(pageRemote, stableRemoteTopLevelNames)

        // Reverse-map this page's remote-delta keys (canonical) into the
        // real-local namespace so they pair with the alias-named local row.
        val pageRemoteLocal =
            if (alias.isEmpty) pageRemote
            else pageRemote.entries.associate { (k, v) -> alias.remoteToLocal(k) to v }

        val actions = mutableListOf<SyncAction>()
        val allPaths =
            (pageRemoteLocal.keys + localChanges.keys)
                .filter { path -> excludePatterns.none { pattern -> matchesGlob(path, pattern) } }
                .filter { pathInSyncScope(it, syncPath) }

        for (path in allPaths) {
            val remoteItem = pageRemoteLocal[path]
            val localState = localChanges[path] ?: ChangeState.UNCHANGED
            val entry = entryByPath[path]

            val remoteState =
                when {
                    remoteItem == null -> ChangeState.UNCHANGED
                    remoteItem.deleted -> ChangeState.DELETED
                    entry == null -> ChangeState.NEW
                    entry.remoteId == null && entry.remoteHash == null -> ChangeState.NEW
                    remoteItem.hash != entry.remoteHash ||
                        remoteItem.modified != entry.remoteModified -> ChangeState.MODIFIED
                    else -> ChangeState.UNCHANGED
                }

            if (remoteState == ChangeState.UNCHANGED && localState == ChangeState.UNCHANGED) continue
            val action = resolveAction(path, localState, remoteState, remoteItem, entry, pinRules, alias, downloadOnly)
            if (action != null) actions.add(action)
        }
        return actions
    }

    /**
     * Streaming reconciliation entry point — scan-end finalization.
     *
     * Takes the union of [streamedActions] (safe-now + deferred drained
     * from [StreamingReconcileBuffer]) plus the accumulated [fullRemote]
     * map and runs the recovery loops, case-collision detection, move
     * detection, and final sort. Mirrors the bookkeeping at the bottom
     * of [reconcile] without re-running the main path loop (that's
     * already been done per page in [resolveSlice]).
     *
     * Recovery loops still iterate `db.getAllEntries()` once here — same
     * as today's single-shot [reconcile]. The `coveredPaths` set absorbs
     * everything [resolveSlice] already emitted so the synthesis only
     * fires for genuinely-uncovered orphans.
     */
    fun finalizeStreaming(
        streamedActions: List<SyncAction>,
        fullRemote: Map<String, CloudItem>,
        fullLocal: Map<String, ChangeState>,
        syncPath: String? = null,
        // #160: mirror of the same flag on reconcile(). The streamed actions
        // from resolveSlice already used this flag; finalizeStreaming receives
        // it only for completeness (the recovery loops don't call resolveAction).
        @Suppress("UNUSED_PARAMETER") downloadOnly: Boolean = false,
        // #200(b): mirror of the same flag on reconcile() — defer new-local creates
        // when the (cross-page) enumeration that produced fullRemote was incomplete.
        enumerationComplete: Boolean = true,
    ): List<SyncAction> {
        val allDbEntries = db.getAllEntries()
        val entryByPath = allDbEntries.associateBy { it.path }
        val entryByLcPath = allDbEntries.associateBy { it.path.lowercase(Locale.ROOT) }
        val entryByRemoteId = allDbEntries.mapNotNull { e -> e.remoteId?.let { it to e } }.toMap()

        val actions = streamedActions.toMutableList()
        val coveredPaths = actions.mapTo(mutableSetOf()) { it.path }

        // #115: alias context. Action paths stay real-local; the canonical
        // remote path is carried out-of-band via remoteTarget.
        val alias = buildAliasContext(fullRemote)

        // Case-collision detection on new local files — see [reconcile] for rationale.
        // fullLocal is real-local-keyed; no translation needed.
        for ((path, state) in fullLocal) {
            if (state != ChangeState.NEW) continue
            val existing = entryByLcPath[path.lowercase(Locale.ROOT)]
            if (existing != null && existing.path != path) {
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

        val newLocalPaths = fullLocal.filterValues { it == ChangeState.NEW }.keys.toList()
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

        val recoveryStartIdx = actions.size
        // UD-225 recovery — unhydrated DB rows surfacing as orphan downloads.
        for (entry in allDbEntries) {
            if (entry.isFolder || entry.isHydrated) continue
            if (entry.remoteSize <= 0) continue
            if (entry.path in coveredPaths) continue
            if (excludePatterns.any { matchesGlob(entry.path, it) }) continue
            if (!pathInSyncScope(entry.path, syncPath)) continue
            // #115: delta keyed at the canonical remote path; DownloadContent
            // path stays real-local so bytes land in the alias-named folder.
            val effectiveRemote = entry.remotePath ?: entry.path
            val remoteItem =
                fullRemote[effectiveRemote] ?: CloudItem(
                    id = entry.remoteId ?: "",
                    name = entry.path.substringAfterLast('/'),
                    path = effectiveRemote,
                    size = entry.remoteSize,
                    isFolder = false,
                    modified = entry.remoteModified,
                    created = null,
                    hash = entry.remoteHash,
                    mimeType = null,
                )
            actions.add(SyncAction.DownloadContent(entry.path, remoteItem))
        }

        // UD-901 recovery — pending-upload DB rows.
        for (entry in allDbEntries) {
            if (entry.isFolder) continue
            if (entry.remoteId != null) continue
            if (!entry.isHydrated) continue
            if (entry.path in coveredPaths) continue
            if (excludePatterns.any { matchesGlob(entry.path, it) }) continue
            if (!pathInSyncScope(entry.path, syncPath)) continue
            val localPath = safeResolveLocal(syncRoot, entry.path)
            if (!Files.isRegularFile(localPath)) continue
            // #115: preserve persisted canonical remote path as remoteTarget.
            actions.add(SyncAction.Upload(entry.path, remoteTarget = entry.remotePath))
        }

        // UD-901b — synthesize missing-parent CreateRemoteFolder for recovery-emitted actions.
        val recoveryEmitted = actions.subList(recoveryStartIdx, actions.size).toList()
        if (recoveryEmitted.isNotEmpty()) {
            val ancestorsToCreate = mutableSetOf<String>()
            for (action in recoveryEmitted) {
                val parts = action.path.removePrefix("/").split('/')
                if (parts.size <= 1) continue
                for (i in 1 until parts.size) {
                    val ancestor = "/" + parts.take(i).joinToString("/")
                    if (ancestor in coveredPaths) continue
                    if (ancestor in ancestorsToCreate) continue
                    val existing = entryByPath[ancestor]
                    if (existing != null && existing.remoteId != null) continue
                    ancestorsToCreate.add(ancestor)
                }
            }
            for (ancestor in ancestorsToCreate.sortedBy { it.count { c -> c == '/' } }) {
                actions.add(SyncAction.CreateRemoteFolder(ancestor, remoteTarget = aliasTarget(alias, ancestor)))
                coveredPaths.add(ancestor)
            }
        }

        // Cross-page move detection runs here on the combined action set —
        // per-page detection would miss renames that span page boundaries.
        detectMoves(actions, entryByPath, alias)
        detectRemoteRenames(actions, fullRemote, entryByRemoteId, alias)
        lastUnhydratedFolderDeletes = dropUnhydratedFolderDeletes(actions, entryByPath)
        deferIncompleteRemoteCreates(actions, fullLocal, enumerationComplete)

        return sortActions(actions)
    }

    // #200(b): when the remote enumeration was INCOMPLETE, a local-present/
    // remote-absent path may be a subtree we simply failed to enumerate, not a
    // genuinely-new local file — so emitting Upload/CreateRemoteFolder risks
    // duplicating a file that exists remotely but went unseen. Drop those new-local
    // creates; they retry on the next COMPLETE enumeration, which either confirms
    // remote-absence (→ upload) or sees the file (→ adopt). MODIFIED uploads
    // (replace an existing remote) and recovery-synthesised actions are not keyed
    // ChangeState.NEW in localChanges, so they are left intact. Mirror of the
    // delete-side "reap only on a complete enumeration".
    private fun deferIncompleteRemoteCreates(
        actions: MutableList<SyncAction>,
        localChanges: Map<String, ChangeState>,
        enumerationComplete: Boolean,
    ) {
        if (enumerationComplete) return
        val before = actions.size
        actions.removeAll { a ->
            (a is SyncAction.Upload || a is SyncAction.CreateRemoteFolder) &&
                localChanges[a.path] == ChangeState.NEW
        }
        val deferred = before - actions.size
        if (deferred > 0) {
            log.warn(
                "Enumeration incomplete: deferred {} new-local create(s) (Upload/CreateRemoteFolder) " +
                    "until a complete enumeration — avoids duplicating un-enumerated remote files",
                deferred,
            )
        }
    }

    // #115: alias-context for transparent locale-alias adoption.
    //
    // The action `path` is ALWAYS the real local sync_root-relative path
    // (LocalScanner keys/scans there); a separate canonical REMOTE path is
    // carried out-of-band (SyncEntry.remotePath / SyncAction.remoteTarget).
    // This context provides the two translators that bridge the two
    // namespaces without ever rewriting `path`:
    //
    //  - [localToRemote] maps a real-local path to its canonical remote path
    //    (top-level alias substitution, e.g. /Bilder/x → /Pictures/x). Used to
    //    populate remoteTarget on Upload / CreateRemoteFolder / MoveRemote and
    //    to compute a row's effective remote path. Identity when no alias.
    //
    //  - [remoteToLocal] maps a canonical remote path back to the real-local
    //    path (e.g. /Pictures/x → /Bilder/x) so a remote delta reported at the
    //    canonical key is reconciled against the alias-named local folder and
    //    LOCAL ops (Download / placeholder / delete-local) write to the right
    //    on-disk location. The reverse top-level is resolved against the
    //    on-disk folder so a host with /Bilder (not /Pictures) maps the
    //    canonical /Pictures back to /Bilder; absent any matching local alias
    //    folder it is identity (download into a canonical-named folder).
    private data class AliasContext(
        val isEmpty: Boolean,
        val localToRemote: (String) -> String,
        val remoteToLocal: (String) -> String,
    ) {
        companion object {
            val IDENTITY = AliasContext(isEmpty = true, localToRemote = { it }, remoteToLocal = { it })
        }
    }

    private fun buildAliasContext(
        remoteTopLevelMap: Map<String, CloudItem>,
        // #115 streaming fix: when non-null, use this pre-computed stable set of
        // top-level remote folder names instead of deriving it from remoteTopLevelMap.
        // This lets resolveSlice() alias against the FULL set of canonical names known
        // across ALL pages, not only the names present in the current page — avoiding
        // the bug where a page carrying only children (no top-level entry) produces an
        // empty alias context and emits CreateRemoteFolder for the locale alias tree.
        stableRemoteTopLevelNames: Set<String>? = null,
    ): AliasContext {
        val topLevelNames = stableRemoteTopLevelNames
            ?: remoteTopLevelMap.keys
                .filter { it.count { c -> c == '/' } == 1 && !remoteTopLevelMap[it]!!.deleted }
                .map { it.removePrefix("/") }
                .toSet()
        val xdgAliases = XdgLocaleDirAliases.build(
            remoteTopLevelNames = topLevelNames,
            userDirsOverrides = xdgUserDirsOverrides,
        )
        if (xdgAliases.isEmpty) return AliasContext.IDENTITY

        // Reverse top-level map (canonical → local alias). Built from the
        // local→canonical mapping, but only for alias folders that ACTUALLY
        // exist on disk under the sync root — that's the local folder the
        // canonical remote tree must reconcile against. Multiple locale
        // variants can share a canonical; the on-disk check disambiguates.
        val reverseTopLevel = HashMap<String, String>()
        for ((localTop, canonical) in xdgAliases.localToCanonicalMap()) {
            if (canonical in reverseTopLevel.values) continue // first on-disk wins
            if (Files.isDirectory(safeResolveLocal(syncRoot, "/$localTop"))) {
                reverseTopLevel[canonical] = localTop
            }
        }

        fun substituteTop(path: String, newTop: String): String {
            val noSlash = path.removePrefix("/")
            val slash = noSlash.indexOf('/')
            val rest = if (slash < 0) "" else noSlash.substring(slash)
            return "/$newTop$rest"
        }

        val localToRemote: (String) -> String = { path -> xdgAliases.translatePath(path) }
        val remoteToLocal: (String) -> String = { path ->
            val noSlash = path.removePrefix("/")
            val slash = noSlash.indexOf('/')
            val top = if (slash < 0) noSlash else noSlash.substring(0, slash)
            val localTop = reverseTopLevel[top]
            if (localTop != null) substituteTop(path, localTop) else path
        }
        return AliasContext(isEmpty = false, localToRemote = localToRemote, remoteToLocal = remoteToLocal)
    }

    // #115: canonical remote path for [path] under the current alias context,
    // or null when it equals [path] (non-aliased → remoteTarget left null so
    // behaviour is byte-identical to today).
    private fun aliasTarget(
        alias: AliasContext,
        path: String,
    ): String? {
        if (alias.isEmpty) return null
        val remote = alias.localToRemote(path)
        return if (remote != path) remote else null
    }

    // #115: a top-level alias folder (e.g. /Bilder) is a single path component.
    // Only the alias folder ITSELF is adopted (suppress CreateRemoteFolder);
    // genuine subfolders under it still get created on remote (under canonical).
    private fun isTopLevelAliasFolder(path: String): Boolean = path.removePrefix("/").count { it == '/' } == 0

    private fun resolveAction(
        path: String,
        localState: ChangeState,
        remoteState: ChangeState,
        remoteItem: CloudItem?,
        entry: SyncEntry?,
        pinRules: List<Pair<String, Boolean>>,
        // #115: alias context. `path` is ALWAYS the real-local path; LOCAL ops
        // resolve it directly via resolveLocal, REMOTE-write actions carry the
        // canonical remote path out-of-band via remoteTarget.
        alias: AliasContext = AliasContext.IDENTITY,
        // #160: when true, a hydrated row whose local file is absent is re-downloaded
        // instead of propagated as DeleteRemote. Only safe in download-only mode —
        // in bidirectional mode the same state is a legitimate user delete.
        downloadOnly: Boolean = false,
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
                    // #115: an XDG alias top-level folder whose canonical already
                    // exists on remote is ADOPTED — its remoteTarget resolves to
                    // the canonical, which already exists, so no CreateRemoteFolder
                    // is needed for the alias folder itself. Suppress it. Sub-paths
                    // under an alias still create their (non-aliased) subfolders.
                    val target = aliasTarget(alias, path)
                    if (target != null && isTopLevelAliasFolder(path)) {
                        null
                    } else {
                        SyncAction.CreateRemoteFolder(path, remoteTarget = target)
                    }
                } else {
                    SyncAction.Upload(path, remoteTarget = aliasTarget(alias, path))
                }
            }
            localState == ChangeState.MODIFIED && remoteState == ChangeState.UNCHANGED ->
                // UD-366: pass the existing remote UUID so the provider can route through
                // PUT /files/{uuid} (replace-in-place) rather than POSTing a duplicate that
                // 409s on Internxt.
                SyncAction.Upload(path, remoteId = entry?.remoteId, remoteTarget = aliasTarget(alias, path))
            localState == ChangeState.DELETED && remoteState == ChangeState.UNCHANGED ->
                when {
                    // UD-901: a pending-upload row (entry.remoteId == null) that vanished
                    // before its first upload has nothing to delete on the remote side —
                    // just drop the placeholder row.
                    entry != null && entry.remoteId == null -> SyncAction.RemoveEntry(path)
                    // UD-225a: unhydrated file row where the local stub was never
                    // created (or was wiped before content arrived). isHydrated=false
                    // means "this row represents a planned-but-unfulfilled local
                    // presence" — the local-missing state IS the original state, not
                    // user intent. Pre-fix the reconciler emitted DeleteRemote which
                    // (a) was filtered out in --download-only and made the placeholders
                    // unreachable forever, and (b) in bidirectional mode would destroy
                    // remote data based on a false inference.
                    //
                    // Use the delta's remoteItem if present; synthesise from DB
                    // metadata otherwise. The delta may not carry the row (live
                    // 2026-05-03 incident: Internxt's `/folders` cursor window
                    // omitted the parent folder, the provider's path resolution
                    // collapsed /_INBOX/* paths to drive root, and the engine's
                    // syncPath filter eliminated all of them — 0 entries in
                    // remoteChanges post-filter despite 1,550 unhydrated DB rows).
                    // The synthesised CloudItem mirrors UD-225's recovery-loop
                    // synthesis shape so Pass 2's downloadById picks up where
                    // either path leaves off.
                    //
                    // Sibling to UD-225 (closed) which fixed the same bug class for
                    // the UNCHANGED+UNCHANGED skip path. UD-225a fixes the
                    // DELETED+UNCHANGED case in the main loop directly so detectMoves
                    // (which converts DeleteRemote+Upload pairs into MoveRemote) is
                    // not corrupted by phantom DeleteRemote actions for unhydrated
                    // rows that were never user-deleted.
                    //
                    // Folders are not handled here; isHydrated semantics for folders
                    // are different (they represent on-disk directory presence, not
                    // file content). Unhydrated folder rows whose path is missing
                    // on disk fall through to DeleteRemote below; the
                    // post-detectMoves dropUnhydratedFolderDeletes filter strips
                    // those actions once move-detection has had a chance to pair
                    // them with a matching CreateRemoteFolder destination.
                    entry != null && !entry.isHydrated && !entry.isFolder -> {
                        // Sibling skip to the UD-225 recovery loop above: a
                        // quarantined row has already burned through the
                        // recovery cycle once and was confirmed permanently
                        // gone. Drop the action; the next delta event
                        // clears the flag.
                        if (entry.downloadQuarantined) {
                            null
                        } else {
                            val item =
                                remoteItem ?: CloudItem(
                                    id = entry.remoteId ?: "",
                                    name = path.substringAfterLast('/'),
                                    path = path,
                                    size = entry.remoteSize,
                                    isFolder = false,
                                    modified = entry.remoteModified,
                                    created = null,
                                    hash = entry.remoteHash,
                                    mimeType = null,
                                )
                            SyncAction.DownloadContent(path, item)
                        }
                    }
                    // #160: download-only mode — a hydrated row whose local file is
                    // absent must be re-downloaded, not propagated as DeleteRemote
                    // (which the direction filter would drop, making the file
                    // unreachable forever). In bidirectional mode the same state is
                    // a legitimate user delete and must propagate.
                    downloadOnly && entry != null && entry.isHydrated && !entry.isFolder -> {
                        val item =
                            remoteItem ?: CloudItem(
                                id = entry.remoteId ?: "",
                                name = path.substringAfterLast('/'),
                                path = path,
                                size = entry.remoteSize,
                                isFolder = false,
                                modified = entry.remoteModified,
                                created = null,
                                hash = entry.remoteHash,
                                mimeType = null,
                            )
                        SyncAction.DownloadContent(path, item)
                    }
                    // Otherwise: real user delete on a hydrated row → propagate.
                    else -> SyncAction.DeleteRemote(path)
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

    private fun dropUnhydratedFolderDeletes(
        actions: MutableList<SyncAction>,
        entryByPath: Map<String, SyncEntry>,
    ): List<String> {
        val dropped = mutableListOf<String>()
        actions.removeAll { action ->
            if (action !is SyncAction.DeleteRemote) return@removeAll false
            val entry = entryByPath[action.path] ?: return@removeAll false
            val match = entry.isFolder && !entry.isHydrated
            if (match) dropped.add(action.path)
            match
        }
        if (dropped.isNotEmpty()) {
            log.warn(
                "Dropped {} DeleteRemote action(s) for unhydrated folder rows: {}",
                dropped.size,
                dropped.take(5).joinToString(",") + if (dropped.size > 5) ",… (${dropped.size - 5} more)" else "",
            )
        }
        return dropped
    }

    private fun detectMoves(
        actions: MutableList<SyncAction>,
        entryByPath: Map<String, SyncEntry>,
        // #115: alias context. All action paths are real-local; the MoveRemote
        // this emits carries the canonical remote source (fromPath = the source
        // row's effective remote path) and canonical destination (remoteTarget).
        alias: AliasContext = AliasContext.IDENTITY,
    ) {
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>()
        if (deletes.isEmpty()) return

        // Folder moves: DeleteRemote(oldFolder) + CreateRemoteFolder(newFolder).
        //
        // UD-240j + UD-240k: the loop iterates CREATES (the user's intent — "I
        // just made this folder, where did it come from?") and picks the BEST
        // matching delete for each. "Best" = same basename AND longest common
        // parent-path with the create. A score of 0 (no shared parent segment)
        // is rejected: that's two independent operations the user happened to
        // perform with same-named folders, not a move.
        //
        // Pre-UD-240k the loop went deletes→creates with basename-only match.
        // With stale DB rows sharing a basename across totally unrelated subtrees
        // (legacy /Sample vs current /userhome/Pictures/Sample, both with
        // remoteId), set-iteration order picked one effectively at random —
        // routinely the wrong one. UD-240j (matchedFolderCreates dedup) capped
        // the false-positive count at 1; UD-240k makes that 1 actually correct.
        val folderCreates = actions.filterIsInstance<SyncAction.CreateRemoteFolder>()
        val matchedFolderDeletes = mutableSetOf<String>()
        val matchedFolderCreates = mutableSetOf<String>()
        for (create in folderCreates) {
            if (create.path in matchedFolderCreates) continue
            val newName = create.path.substringAfterLast("/")
            val createParentSegs = parentSegments(create.path)

            // Find the best matching delete: same basename, has a folder DB
            // row with remoteId, longest common parent-segment overlap with
            // this create.
            //
            // Tie-break rule:
            //   - 1 candidate                → accept (legitimate move,
            //     possibly cross-parent at top level like /a/X → /b/X)
            //   - >= 2 candidates with same max score == 0 → REJECT
            //     (ambiguous; without parent overlap we can't tell which
            //     stale row corresponds to the user's actual intent)
            //   - otherwise → pick the highest-scoring candidate, ties
            //     broken by iteration order
            data class Candidate(val del: SyncAction.DeleteRemote, val score: Int)
            val candidates = deletes
                .asSequence()
                .filter { it.path !in matchedFolderDeletes }
                .filter { it.path.substringAfterLast("/") == newName }
                .mapNotNull { d ->
                    val e = entryByPath[d.path] ?: return@mapNotNull null
                    if (!e.isFolder || e.remoteId == null) return@mapNotNull null
                    Candidate(d, commonPrefixSegments(parentSegments(d.path), createParentSegs))
                }
                .toList()
            if (candidates.isEmpty()) continue
            val maxScore = candidates.maxOf { it.score }
            // UD-240k ambiguous-zero guard: when several candidates share
            // basename but none have parent overlap with the destination,
            // we don't have a structural signal to pick between them.
            // Refusing to emit a MoveRemote is safer than guessing wrong;
            // the standalone DeleteRemote / CreateRemoteFolder will run
            // independently and the engine handles 404s gracefully.
            if (candidates.size > 1 && maxScore == 0) continue
            val best = candidates.first { it.score == maxScore }

            val del = best.del
            val entry = entryByPath[del.path]!! // proven non-null + isFolder + remoteId by filter above
            actions.add(
                SyncAction.MoveRemote(
                    path = create.path,
                    // #115: fromPath stays real-local (the source row's path) so
                    // the executor's DB ops + resolveLocal work; the canonical
                    // remote source is derived in the executor from the source
                    // row. remoteTarget carries the canonical destination.
                    fromPath = del.path,
                    remoteId = entry.remoteId!!,
                    remoteTarget = aliasTarget(alias, create.path),
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
            matchedFolderCreates.add(create.path)
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
                    // #115: up.path is real-local; probe it directly.
                    val info = localFsProbe(resolveLocal(up.path)) ?: return@mapNotNull null
                    up to info.size
                }.groupBy({ it.second }, { it.first })

        val matchedUploads = mutableSetOf<String>()
        for (del in deletes) {
            if (del.path in matchedFolderDeletes) continue
            val entry = entryByPath[del.path] ?: continue
            if (entry.isFolder || entry.remoteId == null) continue

            // #296: size alone is NOT evidence of a move. An unrelated new file
            // that happens to byte-match a deleted one (padded formats, templates,
            // camera files) used to be converted into a remote rename — the cloud
            // kept the deleted file's bytes under the new name and the new file's
            // real content was never uploaded. Require a structural anchor on top
            // of the size match, mirroring the folder branch above: same basename
            // (cross-folder move) OR same parent (rename in place — the #115
            // alias-folder rename invariant depends on this arm). Hash equality
            // would be stronger, but the reconciler doesn't know the provider's
            // hash algorithm; anchor + size is the v1.
            val delName = del.path.substringAfterLast("/")
            val delParent = del.path.substringBeforeLast("/")
            val candidates =
                uploadsBySize[entry.remoteSize]
                    ?.filter { up ->
                        up.path !in matchedUploads &&
                            (
                                up.path.substringAfterLast("/") == delName ||
                                    up.path.substringBeforeLast("/") == delParent
                            )
                    }.orEmpty()
            // 2+ anchored candidates → ambiguous; guessing wrong converts a real
            // file into a rename of unrelated bytes. Fall back to the independent
            // DeleteRemote + Upload — correct, just less efficient.
            val candidate = candidates.singleOrNull() ?: continue
            actions.remove(del)
            actions.remove(candidate)
            actions.add(
                SyncAction.MoveRemote(
                    path = candidate.path,
                    // #115: fromPath stays real-local; canonical remote source is
                    // derived in the executor from the source row. remoteTarget
                    // carries the canonical destination.
                    fromPath = del.path,
                    remoteId = entry.remoteId,
                    remoteTarget = aliasTarget(alias, candidate.path),
                ),
            )
            matchedUploads.add(candidate.path)
        }
    }

    private fun detectRemoteRenames(
        actions: MutableList<SyncAction>,
        remoteChanges: Map<String, CloudItem>,
        entryByRemoteId: Map<String, SyncEntry>,
        // #115: alias context. Candidate action paths are real-local; the remote
        // delta is canonical-keyed. We look the delta up by the candidate's
        // canonical path and compare the matched row's EFFECTIVE remote path
        // (remotePath ?: path) against the delta path — NOT against candidate.path.
        // This defeats single-path failure mode 2: an uploaded-to-canonical row
        // whose local path is the alias name (e.g. /Bilder/x.jpg ↔ /Pictures/x.jpg)
        // is NOT mistaken for a rename, so no spurious MoveLocal physically moves
        // the file out of the locale-named folder.
        alias: AliasContext = AliasContext.IDENTITY,
    ) {
        // UD-222: renames of remote non-folders now arrive as DownloadContent (not
        // CreatePlaceholder) because hydration moved to Pass 2. Scan both action types.
        val candidates: List<SyncAction> =
            actions.filter {
                it is SyncAction.CreatePlaceholder || it is SyncAction.DownloadContent
            }
        if (candidates.isEmpty()) return

        for (candidate in candidates) {
            // The remote delta key is canonical; the candidate path is real-local.
            val canonicalPath = if (alias.isEmpty) candidate.path else alias.localToRemote(candidate.path)
            val remoteItem = remoteChanges[canonicalPath] ?: continue
            val oldEntry = entryByRemoteId[remoteItem.id] ?: continue
            // Compare against the row's EFFECTIVE remote path so an aliased row
            // already living at this canonical path is treated as UNCHANGED, not
            // a rename. `remoteItem.path` is the canonical delta path.
            val oldEffectiveRemote = oldEntry.remotePath ?: oldEntry.path
            if (oldEffectiveRemote == remoteItem.path) continue

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
                    // candidate.path is real-local — the local move destination.
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
         * UD-240k: split a path into its parent's segment list.
         *
         *   `/A/B/C/file.bin` → `["A", "B", "C"]`
         *   `/file.bin`        → `[]`
         *   `/`                → `[]`
         *   `""`               → `[]`
         */
        fun parentSegments(path: String): List<String> {
            val trimmed = path.removePrefix("/")
            if (trimmed.isEmpty()) return emptyList()
            val parts = trimmed.split('/')
            return if (parts.size <= 1) emptyList() else parts.dropLast(1)
        }

        /**
         * UD-240k: number of leading segments two paths share.
         *
         *   `["A","B","C"]` vs `["A","B","D"]` → 2
         *   `["A","B"]`     vs `["X","Y"]`     → 0
         *   `["A"]`         vs `["A","B"]`     → 1
         */
        fun commonPrefixSegments(
            a: List<String>,
            b: List<String>,
        ): Int {
            var i = 0
            while (i < a.size && i < b.size && a[i] == b[i]) i++
            return i
        }

        /**
         * UD-901a: predicate matching the engine's own scope filter at
         * [SyncEngine.kt:163-168]. Encoded once here so the recovery
         * loops can reuse it without drifting from the main filter's
         * semantics.
         *
         * Returns true when:
         *   - syncPath is null (no scope = everything in scope), or
         *   - path equals syncPath (the scope's own root), or
         *   - path is a strict descendant of syncPath (path startsWith
         *     "$syncPath/").
         *
         * Note `startsWith("$syncPath/")` not `startsWith(syncPath)` —
         * otherwise `/foo` would match `/footer.txt` etc.
         */
        fun pathInSyncScope(
            path: String,
            syncPath: String?,
        ): Boolean {
            if (syncPath == null) return true
            return path == syncPath || path.startsWith("$syncPath/")
        }

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

        // UD-373: cache of compiled glob → Regex. Keyed by the cleaned pattern string
        // (post `removePrefix("/")`). Cardinality is bounded by the number of distinct
        // exclude/pin patterns the user configures (typically <50); no eviction needed.
        // ConcurrentHashMap because the daemon shares the companion across syncs.
        private val globCache = ConcurrentHashMap<String, Regex>()

        // Visible-for-test counter so UD-373's cache-hit assertion can verify that
        // buildGlobRegex is invoked exactly once per distinct pattern.
        internal val buildGlobRegexInvocations = java.util.concurrent.atomic.AtomicLong(0)

        fun matchesGlob(
            path: String,
            pattern: String,
        ): Boolean {
            val cleanPath = path.removePrefix("/")
            val cleanPattern = pattern.removePrefix("/")
            val regex =
                globCache.computeIfAbsent(cleanPattern) {
                    buildGlobRegexInvocations.incrementAndGet()
                    Regex("^${buildGlobRegex(it)}$")
                }
            // If pattern has no '/', also match against just the filename (basename match)
            return if ('/' !in cleanPattern) {
                regex.matches(cleanPath.substringAfterLast('/'))
            } else {
                regex.matches(cleanPath)
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
