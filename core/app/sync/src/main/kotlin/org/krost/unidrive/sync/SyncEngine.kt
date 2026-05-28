package org.krost.unidrive.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.DeltaCursorExpiredException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.http.Priority
import org.krost.unidrive.sync.model.*
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.PatternSyntaxException

open class SyncEngine(
    private val provider: CloudProvider,
    private val db: StateDatabase,
    private val syncRoot: Path,
    private val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    private val conflictOverrides: Map<String, ConflictPolicy> = emptyMap(),
    private val excludePatterns: List<String> = emptyList(),
    private val reporter: ProgressReporter = ProgressReporter.Silent,
    private val failureLogPath: Path? = null,
    private val conflictLog: ConflictLog? = null,
    private val syncPath: String? = null,
    private val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
    // UD-737: --upload-only is push-additive by default — local deletes do NOT
    // propagate to remote. Set to true to opt back in to legacy "local is
    // source of truth" semantics. No effect outside SyncDirection.UPLOAD.
    private val propagateDeletes: Boolean = false,
    private val maxDeletePercentage: Int = 50,
    // UD-265: two new axes on the deletion safeguard. maxDeletePercentage stays
    // for back-compat (still trips); these add an absolute cap (catches
    // wide-blast on large drives where any sane percentage is still a
    // catastrophe) and a per-top-level-subtree percentage (catches
    // 100%-of-/Documents/Foo runs that are 0.1% of the whole drive). Either
    // axis tripping aborts apply / warns in dry-run; --force-delete bypasses
    // all three. 0 disables the corresponding check.
    private val maxDeleteAbsolute: Int = 50,
    private val maxDeletePerSubtreePercent: Int = 80,
    private val verifyIntegrity: Boolean = false,
    private val providerId: String = "",
    private val useTrash: Boolean = true,
    private val includeShared: Boolean = false,
    private val echoSuppress: ((String) -> Unit)? = null,
    private val echoUnsuppress: ((String) -> Unit)? = null,
    private val placeholder: PlaceholderManager = PlaceholderManager(syncRoot),
    private val trashManager: TrashManager? = null,
    private val trashRetentionDays: Int = 30,
    private val versionManager: VersionManager? = null,
    private val maxVersions: Int = 5,
    private val versionRetentionDays: Int = 90,
    private val fastBootstrap: Boolean = false,
    // UD-113: optional structured audit log of mutations (Download/Upload/Delete/Move/
    // CreateRemoteFolder). Wired from CLI/MCP startup; null in tests that don't need it.
    private val auditLog: org.krost.unidrive.sync.audit.AuditLog? = null,
    // UD-256: operator opt-in for full-tree bidirectional after the profile has been
    // used with --sync-path. When true, the persisted effective_scope is cleared and
    // this run reconciles against the entire cloud (which is what the user wants when
    // they're consciously taking the profile out of scoped mode). When false (the
    // default), a bare bidirectional apply on a profile with non-empty persisted
    // scope is refused with a guidance message — the 2026-05-16 405-cloud-delete
    // incident on inxt_gernot_krost_posteo would have been caught here.
    private val allowFullTreeReconciliation: Boolean = false,
    // UD-264: opt-out for the top-level-never-hydrated guard. When false (default)
    // the engine SKIPS any del-remote action whose top-level cloud folder has
    // never held a hydrated descendant locally — exactly the 2026-05-16 incident
    // shape on `inxt_gernot_krost_posteo`, where state.db indexed 280k entries
    // but no descendant of /Documents/CyberLink/ etc. ever had local_mtime or
    // is_hydrated=1 (the official Internxt client wrote them to cloud; the user
    // never downloaded them through unidrive). When true the guard still logs
    // skipped paths to skipped-ops.jsonl but does NOT drop the action — for the
    // rare case where the operator genuinely wants to purge a never-touched
    // top-level (e.g. final cleanup after rename).
    private val ignoreTopLevelGuard: Boolean = false,
    // UD-264: sibling of failureLogPath. When non-null and a delete is dropped
    // (or would have been dropped, if ignoreTopLevelGuard=true), append a JSON
    // line: {"ts":"...","action":"del-remote","path":"...","reason":"..."}.
    // Null in tests that don't care about audit output.
    private val skippedOpsLogPath: Path? = null,
    // Streaming reconciliation: when true, the engine runs the local scan
    // first, then drives provider.delta() page-by-page, reconciling each
    // page against the full local map via Reconciler.resolveSlice. Safe-now
    // actions (DownloadContent, Upload, Create*, explicit Move) accumulate
    // into the action list as each page lands; deletion-bearing actions
    // (DeleteLocal, DeleteRemote, RemoveEntry, Conflict×DELETED) buffer
    // until scan-end so detectMissingAfterFullSync still gates them. The
    // executor pipeline (Pass 1 / Pass 2) remains the existing single-list
    // shape — the channel + executor coroutine wiring lands separately.
    // Default false; flipped by the CLI flag + TOML key.
    private val streamingReconciliation: Boolean = false,
    // Root directory for the hydration cache used by ensureHydrated /
    // uploadFromCache. Null means resolve via XDG_CACHE_HOME (or ~/.cache).
    // Injected in tests so the cache stays inside the temp directory.
    private val cacheRoot: Path? = null,
    // Per-account namespace for the hydration cache subtree. MUST be unique
    // per profile (use profile.name, not profile.type) so two accounts of the
    // same provider type (e.g. two `onedrive` profiles) don't collide on
    // identical remote paths under one shared cache dir. Distinct from
    // [providerId], which is the provider TYPE used for ProviderRegistry
    // metadata lookups (concurrency cap, capability flags) and must stay the
    // type. Defaults to [providerId] so callers that don't set it keep the
    // pre-existing layout; the CLI sync/daemon paths pass profile.name.
    private val cacheKey: String = providerId,
    // Called once after enumerateRemoteIntoState mutates state.db with the set of
    // changed paths (upserted + reaped). No-op default keeps callers that don't
    // need the signal unaffected. Only invoked when something actually changed
    // (upserted > 0 || reaped > 0). app:hydration is the intended wiring site;
    // keeping this as a plain lambda avoids a circular import (HydrationEvent lives
    // in app:hydration which depends on app:sync, not the other way around).
    private val viewInvalidationSink: (changedPaths: Set<String>) -> Unit = {},
    xdgUserDirsOverridesForTest: Map<String, String>? = null,
) {
    private val log = LoggerFactory.getLogger(SyncEngine::class.java)
    private val effectiveExcludePatterns =
        validateExcludePatterns(
            (SyncConfig.DEFAULT_EXCLUDE_PATTERNS + excludePatterns).distinct(),
        )
    private val scanner = LocalScanner(syncRoot, db, effectiveExcludePatterns, provider.hashAlgorithm())

    // #115: read once at construction — a locale change requires a daemon
    // restart. Shared by the reconciler (alias detection) and updateRemoteEntries
    // (canonical→real-local reverse map for newly-arrived aliased rows).
    private val xdgUserDirsOverrides: Map<String, String> =
        xdgUserDirsOverridesForTest ?: parseUserDirsFile(
            Paths.get(
                System.getenv("HOME") ?: System.getProperty("user.home"),
                ".config", "user-dirs.dirs",
            ),
        )

    private val reconciler = Reconciler(
        db, syncRoot, conflictPolicy, conflictOverrides, effectiveExcludePatterns,
        // #115: wire real user-dirs.dirs content so the reconciler can map locale-
        // aliased local folder names to their cloud-canonical equivalents.
        xdgUserDirsOverrides = xdgUserDirsOverrides,
    )

    // Debounce state for remote-change wake hints (Internxt notifications WS).
    // The provider may emit many frames per second during a folder-tree
    // mutation; we coalesce them into one wake by cancelling-and-restarting
    // a single delay job per hint and firing the listener only after the
    // quiet window elapses.
    @Volatile
    private var remoteWakeDebounceJob: kotlinx.coroutines.Job? = null

    // Paths a prior COMPLETE enumeration flagged missing-but-deferred under the
    // bulk-disappearance corroboration guard (mount-view-refresh-design.md §3.2).
    // Carried in-memory across enumerations on a long-lived daemon engine; a path
    // is reaped only once a second consecutive complete enumeration still shows it
    // missing. Resets on restart (conservative: re-defers).
    private var deferredMissing: Set<String> = emptySet()

    // Paths the engine itself uploaded, mapped to the upload instant. Consulted by
    // detectMissingAfterFullSync to defer the absence-implies-deletion verdict for a
    // path whose remote write the eventually-consistent delta feed hasn't reflected
    // yet — the re-upload churn class. Daemon-scoped in-memory state (same lifetime as
    // deferredMissing); pruned past RECENT_UPLOAD_REAP_GRACE so a genuinely-deleted
    // path is reaped once the window lapses and the map can't grow unbounded.
    private val recentlyUploaded = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    private fun markRecentlyUploaded(path: String) {
        recentlyUploaded[path] = Instant.now()
    }

    private fun pruneRecentlyUploaded() {
        val cutoff = Instant.now().minus(RECENT_UPLOAD_REAP_GRACE)
        recentlyUploaded.entries.removeIf { it.value.isBefore(cutoff) }
    }

    // Single-flight guard for enumerateRemoteIntoState across ALL callers: the
    // --poll-interval poller, the sync.enumerate verb (EnumerateRpcHandler), and
    // mount-routed refresh.run (RefreshRpcHandler calls the engine directly).
    // Per-handler guards don't serialize across handlers, so two passes could
    // otherwise race delta_cursor promotion and deferredMissing corroboration
    // state. A caller that loses the CAS is a no-op (skipped=true).
    private val enumerateInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Wire the provider's server-pushed change feed (Internxt's socket.io
     * `NOTIFICATIONS_URL`) into the watch loop. The provider emits one
     * raw hint per observed remote mutation; this method debounces a
     * burst into a single [listener] invocation after a quiet window
     * ([REMOTE_WAKE_DEBOUNCE_MS]) so 50 frame arrivals from one folder-
     * tree change wake the poll loop ONCE instead of 50 times.
     *
     * [scope] owns the debounce coroutine. When [scope] is cancelled
     * (daemon shutdown) any pending debounced fire is dropped.
     *
     * Providers without a push channel inherit the [CloudProvider.onRemoteChangeHint]
     * default no-op; this call is then a registration into a black hole,
     * which is fine.
     */
    fun registerRemoteWakeListener(
        scope: kotlinx.coroutines.CoroutineScope,
        listener: () -> Unit,
    ) {
        provider.onRemoteChangeHint {
            // Cancel any pending debounce and restart. The current hint
            // resets the quiet-window clock; only when the quiet window
            // elapses without a fresh hint do we fire the listener.
            remoteWakeDebounceJob?.cancel()
            remoteWakeDebounceJob =
                scope.launch {
                    kotlinx.coroutines.delay(REMOTE_WAKE_DEBOUNCE_MS)
                    try {
                        listener()
                    } catch (e: Exception) {
                        log.warn("Remote-wake listener threw", e)
                    }
                }
        }
    }

    private inline fun <T> withEchoSuppression(
        path: String,
        block: () -> T,
    ): T {
        echoSuppress?.invoke(path)
        try {
            return block()
        } finally {
            echoUnsuppress?.invoke(path)
        }
    }

    /**
     * Hydrate a single remote path into the local hydration cache. Idempotent —
     * if the path is already hydrated and the cache file exists, returns the
     * existing cache path without re-downloading. Throws on unrecoverable errors
     * (e.g. [PermanentDownloadFailureException] for a 404; IO errors; unknown
     * path).
     *
     * Cache layout: `<cacheRoot>/unidrive/hydration/<cacheKey>/<path>` where
     * `cacheRoot` is [cacheRoot] when set, otherwise `XDG_CACHE_HOME` or
     * `~/.cache`, and `cacheKey` is the per-account namespace (profile.name).
     *
     * Integrity failure throws (not warns) because FUSE-passthrough exposes the
     * cache directly to userspace reads — a silently accepted corrupt file would
     * be immediately visible to the user, unlike [applyDownload]'s
     * local-placeholder path where a warning is recoverable on the next sync.
     */
    suspend fun ensureHydrated(path: String): Path {
        val entry = db.getEntry(path)
            ?: throw IllegalArgumentException("Unknown remote path: $path")
        val cachePath = resolveCachePath(path)
        // Trust the warm cache only if it is actually COMPLETE. The isHydrated flag
        // plus mere file-existence is not enough for a REMOTE-backed file: a stale
        // isHydrated over a 0-byte/truncated cache (crash mid-download, an
        // externally-cleared cache, a reset/enumeration window) would otherwise be
        // served as-is, silently yielding empty/short reads — which a file-manager
        // copy turns into a corrupt 0-byte destination. Re-download whenever the
        // cached size doesn't match the known remote size.
        //
        // Local-only rows (remoteId == null: created/edited through the mount, not
        // yet uploaded — remoteSize is 0 while the cache holds the just-written
        // bytes) have NO remote to compare against or re-download from; the cache is
        // the only copy, so always trust them on the warm path.
        if (entry.isHydrated && Files.exists(cachePath) &&
            (entry.remoteId == null ||
                runCatching { Files.size(cachePath) }.getOrDefault(-1L) == entry.remoteSize)
        ) {
            return cachePath
        }
        // Construct a minimal CloudItem so downloadByIdOrPath can route by id
        // (fast path) or fall back to path-based (when remoteId is null).
        val remoteItem = CloudItem(
            id = entry.remoteId ?: "",
            name = path.substringAfterLast("/"),
            path = path,
            size = entry.remoteSize,
            isFolder = false,
            modified = entry.remoteModified ?: Instant.now(),
            created = entry.remoteModified ?: Instant.now(),
            hash = entry.remoteHash,
            mimeType = null,
        )
        Files.createDirectories(cachePath.parent)
        val downloadedSize = downloadByIdOrPath(remoteItem, path, cachePath)
        if (verifyIntegrity) {
            val verified = HashVerifier.verify(cachePath, entry.remoteHash, algorithm = provider.hashAlgorithm())
            if (!verified) {
                Files.deleteIfExists(cachePath)
                throw IllegalStateException("Integrity check failed for hydration cache: $path")
            }
        }
        // Persist the freshly-downloaded size as remoteSize. The provider validated the
        // download against the authoritative remote length (throwing on a short read), so
        // this is the current truth. Without it a remote that changed size since the last
        // enumeration leaves remoteSize stale, and the openForRead size guard would EIO a
        // perfectly valid re-download.
        db.upsertEntry(
            (db.getEntry(path) ?: entry).copy(
                isHydrated = true,
                remoteSize = downloadedSize,
                localMtime = Files.getLastModifiedTime(cachePath).toMillis(),
                localSize = Files.size(cachePath),
                lastSynced = Instant.now(),
            ),
        )
        return cachePath
    }

    private fun isExcluded(path: String): Boolean =
        effectiveExcludePatterns.any { Reconciler.matchesGlob(path, it) }

    suspend fun uploadFromCache(
        path: String,
        cachePath: Path,
    ) {
        require(Files.exists(cachePath)) { "Cache path missing: $cachePath" }
        if (isExcluded(path)) {
            log.info("Skipping upload of excluded path (keep-local): {}", path)
            // Keep-local files are never uploaded, but the local watermark must still
            // advance. HydrationImpl.lastSynced() reports localMtime as the watermark,
            // and the co-daemon's crash-recovery scanner replays open_write for any
            // cache file whose mtime exceeds it — so without this, every daemon restart
            // re-replays excluded files forever. remoteId stays null (nothing uploaded).
            val mtime = Files.getLastModifiedTime(cachePath).toMillis()
            val size = Files.size(cachePath)
            val existing = db.getEntry(path)
            db.upsertEntry(
                existing?.copy(
                    localMtime = mtime,
                    localSize = size,
                    isHydrated = true,
                ) ?: SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = mtime,
                    localSize = size,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            return
        }
        val existingEntry = db.getEntry(path)
        val prevHash = existingEntry?.remoteHash
        val existingRemoteId = existingEntry?.remoteId
        // #115: if the existing row is a locale-aliased one, its content lives
        // at the canonical remote path; the FUSE write-back must upload there,
        // not at the alias `path`. Non-aliased rows have remotePath == null →
        // upload at `path`, byte-identical to pre-#115.
        val remotePath = existingEntry?.remotePath ?: path
        val sizeForLog = Files.size(cachePath)
        val result =
            try {
                provider.upload(cachePath, remotePath, existingRemoteId = existingRemoteId) { transferred, total ->
                    reporter.onTransferProgress(path, transferred, total)
                }
            } catch (e: Exception) {
                auditLog?.emit(
                    action = "Upload",
                    path = path,
                    size = sizeForLog,
                    oldHash = prevHash,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
        // Defer the absence sweep's deletion verdict while the delta feed catches up
        // to this just-written remote item (see markRecentlyUploaded). Keyed by the
        // REMOTE path so the absence sweep (remote namespace) matches.
        markRecentlyUploaded(remotePath)
        val mtime = Files.getLastModifiedTime(cachePath).toMillis()
        val size = Files.size(cachePath)
        val existing = db.getEntry(path)
        db.upsertEntry(
            existing?.copy(
                remoteId = result.id,
                remoteHash = result.hash,
                remoteSize = result.size,
                remoteModified = result.modified,
                localMtime = mtime,
                localSize = size,
                isHydrated = true,
                lastSynced = Instant.now(),
            ) ?: SyncEntry(
                path = path,
                remoteId = result.id,
                remoteHash = result.hash,
                remoteSize = result.size,
                remoteModified = result.modified,
                localMtime = mtime,
                localSize = size,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        auditLog?.emit(
            action = "Upload",
            path = path,
            size = size,
            oldHash = prevHash,
            newHash = result.hash,
            result = "success",
        )
    }

    /**
     * Resolves the cache file path for a given path within the hydration cache.
     * Exposed so test fixtures and the future `app:hydration` dehydrate cleanup hook
     * can resolve the same paths without duplicating layout logic.
     */
    fun resolveCachePath(path: String): Path {
        val effectiveRoot = cacheRoot
            ?: (System.getenv("XDG_CACHE_HOME")?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".cache"))
        return effectiveRoot
            .resolve("unidrive/hydration")
            .resolve(cacheKey.ifBlank { "default" })
            .resolve(path.trimStart('/'))
    }

    /**
     * Create a folder on the remote provider and record it in state.db.
     * Used by the hydration SPI (HydrationImpl.mkdir) to back FUSE mkdir
     * requests. Separate code path from the legacy applyActions loop.
     *
     * Throws ProviderException on cloud-side failure. state.db is only
     * updated after the provider call succeeds.
     */
    suspend fun createRemoteFolder(path: String): CloudItem {
        val item = provider.createFolder(path)
        db.insertFolder(path = path, remoteId = item.id, mtime = item.modified ?: Instant.now())
        return item
    }

    /**
     * Delete a path on the remote provider and update state.db.
     * Handles both files and folders — provider distinguishes by
     * remoteId/path. Caller (HydrationImpl.unlink or .rmdir) is
     * responsible for type-checking.
     *
     * Idempotent on "remote already gone": if [provider.delete] throws a
     * [ProviderException] that [isAlreadyGone] recognises as a typed not-found
     * signal, the deletion is treated as already complete — the exception is
     * swallowed and markDeleted still runs, since the postcondition "path no
     * longer on cloud" is satisfied. Every other exception (auth, 5xx, network,
     * throttle) is re-thrown unchanged so real failures surface as EIO rather
     * than being silently eaten.
     *
     * The idempotency gate is type-gated, not free-text: only the two specific
     * provider-originated not-found shapes (path-resolution failure, direct
     * metadata miss) are recognised. A 5xx/proxy error whose body happens to
     * contain "404" or "not found" does NOT satisfy [isAlreadyGone] and will
     * re-throw as expected.
     *
     * state.db is only updated after the provider call succeeds (or is
     * determined to be a no-op because the remote is already gone).
     */
    suspend fun deleteRemote(path: String) {
        try {
            provider.delete(path)
        } catch (e: Exception) {
            if (isAlreadyGone(e)) {
                // Remote is already gone; fall through to markDeleted below.
            } else {
                throw e
            }
        }
        db.markDeleted(path)
    }

    /**
     * Returns true if and only if [e] is a typed provider signal that the
     * remote path is already gone — making deleteRemote idempotent on that
     * outcome. Two specific shapes qualify:
     *
     * 1. **Path-resolution failure** — InternxtProvider.resolveFolder walks
     *    the path tree and throws `ProviderException("Folder not found: <seg>
     *    in <path>")` when a parent folder no longer exists on the remote. This
     *    was the shape observed in the live bug.
     *
     * 2. **Direct metadata miss** — InternxtProvider.getMetadata throws
     *    `ProviderException("Item not found: <path>")` when the target itself
     *    is absent (parent exists, but the leaf is gone).
     *
     * OneDrive's provider handles HTTP 404 internally and never propagates it
     * here — OneDriveProvider.delete returns normally when Graph returns 404.
     *
     * Anything that is NOT a [ProviderException] (e.g. a bare RuntimeException,
     * IOException) returns false and will be re-thrown. A [ProviderException]
     * with a different message prefix (e.g. a 5xx body that happens to contain
     * "not found" or "404") also returns false — the anchored prefix match
     * closes the free-text misclassification hole.
     */
    private fun isAlreadyGone(e: Throwable): Boolean {
        if (e !is ProviderException) return false
        val msg = e.message ?: return false
        return msg.startsWith("Folder not found: ") || msg.startsWith("Item not found: ")
    }

    /**
     * Rename a remote item from [oldPath] to [newPath] and update state.db.
     * Used by the hydration SPI (HydrationImpl.rename) to back FUSE rename
     * requests. Pre-flight checks (source-exists, destination-doesn't-exist,
     * destination-parent-exists) live in HydrationImpl; this entry point
     * trusts its caller and performs the remote move plus the state.db
     * row update unconditionally.
     *
     * Throws ProviderException on cloud-side failure. state.db is only
     * updated after the provider call succeeds. For folders, the path
     * rewrite also moves all descendant rows under the new prefix
     * (db.renamePrefix), matching the rename's recursive semantics on
     * both OneDrive and Internxt.
     */
    suspend fun renameRemote(oldPath: String, newPath: String) {
        provider.move(oldPath, newPath)
        db.renamePrefix(oldPath, newPath)
    }

    // The remote item at [path], or null only when the provider proves it ABSENT.
    // The mount's rename/unlink use this to tell a genuinely-never-uploaded local:
    // row from a "ghost" — a local: row whose content actually landed on the cloud
    // (an upload whose response was lost) and must therefore be moved/deleted
    // remotely, not handled locally. A genuine not-found maps to null; transient
    // failures (auth expiry, throttling, 5xx, network) are PROPAGATED, never read
    // as absence — otherwise a ghost probed during a blip would fall to the
    // local-only path and silently skip the cloud move/delete (orphan/duplicate).
    // isAlreadyGone covers the typed Internxt "not found"; statusCode 404 covers
    // OneDrive's GraphApiException.
    open suspend fun remoteItemOrNull(path: String): CloudItem? =
        try {
            provider.getMetadata(path)
        } catch (e: Exception) {
            if (isAlreadyGone(e) || statusCodeOf(e) == 404 || (e.cause?.let { statusCodeOf(it) } == 404)) {
                null
            } else {
                throw e
            }
        }

    // Reflectively read a `getStatusCode(): Int` off a provider exception without a
    // provider-module classpath dependency (mirrors the hydration SPI helper).
    private fun statusCodeOf(e: Throwable): Int? =
        runCatching {
            val getter = e.javaClass.methods.firstOrNull { it.name == "getStatusCode" && it.parameterCount == 0 }
            getter?.invoke(e) as? Int
        }.getOrNull()

    /**
     * One-way remote→state.db refresh for view consumers (the FUSE mount). Reuses the remote
     * gather + state.db upsert, but NEVER scans sync_root, NEVER plans/executes a local→remote
     * delete, and NEVER evaluates the empty-sync_root / max_delete_* guards. Remote-observed
     * deletions flip state.db rows only on a COMPLETE enumeration (see reaping below). See
     * docs/dev/specs/mount-view-refresh-design.md.
     */
    open suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult {
        // Single-flight: a caller that loses the CAS returns immediately as a no-op so
        // it can never run a concurrent pass against the same engine/DB state (cursor
        // promotion, corroboration). The winning pass is already refreshing the view.
        if (!enumerateInFlight.compareAndSet(false, true)) {
            return EnumerateResult(ok = true, skipped = true)
        }
        return try {
            enumerateRemoteIntoStateLocked(reset)
        } finally {
            enumerateInFlight.set(false)
        }
    }

    private suspend fun enumerateRemoteIntoStateLocked(reset: Boolean): EnumerateResult {
        // reset clears only delta_cursor (NOT db.resetAll) so a gather that then fails never
        // leaves the mount serving an empty view. A reset forces a full re-enumeration whose
        // complete-reap below sweeps stale rows (mark-and-sweep), with no empty-view window.
        if (reset) db.setSyncState("delta_cursor", "")
        val remoteChanges: Map<String, CloudItem> =
            try {
                gatherRemoteChanges()
            } catch (e: ProviderException) {
                return EnumerateResult(ok = false, error = e.message)
            }
        // Completeness is recorded in sync_state by the gather, not on its return value.
        val complete = db.getSyncState("pending_cursor_complete")?.equals("true", ignoreCase = true) ?: true
        val canonicalToLocalTop = buildCanonicalToLocalTopMap(remoteChanges)
        val upsertedViewPaths = remoteChanges.filterValues { !it.deleted }.keys
            .mapTo(mutableSetOf()) { applyReverseTop(it, canonicalToLocalTop) }
        val upserted = upsertedViewPaths.size
        // Bulk-disappearance corroboration guard (spec §3.2). A complete enumeration that
        // would flip more than max(50, 20% of tracked rows) to deleted is suspicious (e.g.
        // Internxt /files lag); reap only paths a PRIOR complete enumeration also saw
        // missing, defer the rest, and carry the candidate set to the next enumeration.
        val trackedRows = db.getEntryCount()
        val bulkThreshold = maxOf(BULK_REAP_ABSOLUTE, (trackedRows * BULK_REAP_FRACTION).toInt())
        var reaped = 0
        val reapedViewPaths = mutableSetOf<String>()
        var nextDeferred = emptySet<String>()
        db.batch {
            updateRemoteEntries(remoteChanges)
            if (complete) {
                // Reap ONLY on a complete enumeration (spec §3.1). The deleted items are
                // already present in remoteChanges: gatherRemoteChanges runs
                // detectMissingAfterFullSync on a full (cursor-null) gather, injecting
                // deleted=true CloudItems for DB rows absent from the live set; incremental
                // gathers carry provider tombstones the same way. updateRemoteEntries skips
                // them, so we flip state.db here directly — never via provider.delete.
                val missingNow = remoteChanges.filterValues { it.deleted }.keys
                val bulk = missingNow.size > bulkThreshold
                val toReap =
                    if (bulk) missingNow.intersect(deferredMissing) else missingNow
                for (path in toReap) {
                    db.markDeleted(path)
                    runCatching { Files.deleteIfExists(resolveCachePath(path)) }
                    reapedViewPaths.add(applyReverseTop(path, canonicalToLocalTop))
                    reaped++
                }
                if (bulk) {
                    val deferred = missingNow - toReap
                    nextDeferred = missingNow
                    if (deferred.isNotEmpty()) {
                        log.warn(
                            "enumerate: bulk disappearance ({} paths > threshold {}); " +
                                "deferring {} uncorroborated path(s) to the next complete enumeration",
                            missingNow.size,
                            bulkThreshold,
                            deferred.size,
                        )
                    }
                }
            }
        }
        // A bulk disappearance must be corroborated by CONSECUTIVE complete enumerations.
        // On a complete pass, carry this pass's candidate set forward. On an INCOMPLETE
        // pass, RESET the deferred set: an incomplete pass means we had no clean run, so
        // a later complete pass must re-defer rather than reap against stale corroboration
        // state (conservative — favors not reaping, since reaping evicts cache + marks
        // deleted).
        deferredMissing = if (complete) nextDeferred else emptySet()
        promotePendingCursor()
        // Notify the view-invalidation sink once, with all paths that changed in state.db
        // during this pass. Only fires when something actually changed so quiescent polls
        // do not produce spurious cache-invalidation traffic. The sink is a plain lambda so
        // app:hydration can wire HydrationEvent.ViewInvalidated without creating a circular
        // import (app:hydration depends on app:sync, not vice versa).
        if (upserted > 0 || reaped > 0) {
            val changedPaths: Set<String> = upsertedViewPaths + reapedViewPaths
            viewInvalidationSink(changedPaths)
        }
        return EnumerateResult(ok = true, upserted = upserted, reaped = reaped, complete = complete)
    }

    open suspend fun syncOnce(
        dryRun: Boolean = false,
        forceDelete: Boolean = false,
        // UD-254: classifies WHY a sync pass started so post-incident log review
        // can separate a normal watch poll from e.g. a rescan-after-retry burst.
        reason: SyncReason = SyncReason.MANUAL,
        // UD-236: refresh-mode cut. Run Gather + Reconcile + Pass 1 (placeholder
        // ops, deletes, moves, mkdirs — all metadata) and SKIP Pass 2 (the actual
        // byte transfers). Pending transfers persist as remoteId=null (upload
        // pending) or isHydrated=false (download pending) DB rows that the next
        // sync — or a follow-up `unidrive apply` — picks up via the UD-225/UD-901
        // recovery loops in Reconciler.reconcile.
        //
        // pending_cursor is NOT promoted to delta_cursor when transfers are
        // skipped — apply will do the promotion when the bytes actually move.
        skipTransfers: Boolean = false,
        // UD-236: apply-mode cut. SKIP the remote Gather phase (no provider.delta()
        // call). Local scan still runs (cheap; catches local edits made since the
        // previous refresh). The recovery loops in Reconciler emit actions for
        // any unhydrated / no-remoteId DB rows from the prior refresh — apply's
        // reason for being is to drain those.
        skipRemoteGather: Boolean = false,
    ) {
        // UD-254: short random scan id pushed into MDC so every DEBUG/WARN line
        // emitted inside this pass inherits it (e.g. InternxtProvider's
        // "Scanning files: N"). A single grep "scan=<id>" gives the slice
        // belonging to one sync pass.
        val scanId =
            java.util.UUID
                .randomUUID()
                .toString()
                .substring(0, 8)
        val priorScanMdc = org.slf4j.MDC.get("scan")
        org.slf4j.MDC.put("scan", scanId)
        val startTime = System.currentTimeMillis()
        log.info("Scan started scan={} reason={} dryRun={}", scanId, reason, dryRun)
        try {
            doSyncOnce(dryRun, forceDelete, scanId, reason, startTime, skipTransfers, skipRemoteGather)
        } finally {
            val duration = System.currentTimeMillis() - startTime
            log.info("Scan ended scan={} reason={} duration={}ms", scanId, reason, duration)
            if (priorScanMdc == null) {
                org.slf4j.MDC.remove("scan")
            } else {
                org.slf4j.MDC.put("scan", priorScanMdc)
            }
        }
    }

    private suspend fun doSyncOnce(
        dryRun: Boolean,
        forceDelete: Boolean,
        @Suppress("UNUSED_PARAMETER") scanId: String,
        @Suppress("UNUSED_PARAMETER") reason: SyncReason,
        startTime: Long,
        skipTransfers: Boolean = false,
        skipRemoteGather: Boolean = false,
    ) {
        val downloaded = AtomicInteger(0)
        val uploaded = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        // Lifted up from Pass 2 so the streaming-reconciliation gather can
        // dispatch transfers concurrently with the scan and share the same
        // failure counter + auth-failure latch. On the non-streaming path
        // only Pass 2 increments them, so the move is behaviour-preserving.
        val transferFailures = AtomicInteger(0)
        val authFailure =
            java.util.concurrent.atomic
                .AtomicReference<AuthenticationException?>(null)
        // Paths the streaming-gather executor already dispatched. Pass 2
        // skips them so a Download/Upload doesn't fire twice (waste of an
        // API round-trip + potential file-locked-by-prior-write race on
        // Windows). Concurrent set: producer is the streaming executor,
        // consumer is Pass 2's launch loop.
        val executedPaths =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()
        // UD-263: per-provider transfer concurrency cap. Lifted to the top
        // of doSyncOnce so both Pass 2 AND the streaming-gather executor
        // share one semaphore — they pick from a single concurrency
        // budget rather than each having their own. Audit values flow
        // from docs/providers/<id>-robustness.md §5 → ProviderMetadata
        // → here. Memory-pressure protection on big files is delegated to
        // the provider's HttpRetryBudget (UD-232).
        val perProviderConcurrency =
            org.krost.unidrive.ProviderRegistry
                .getMetadata(providerId)
                ?.maxConcurrentTransfers ?: 4
        val transferSemaphore = kotlinx.coroutines.sync.Semaphore(perProviderConcurrency)

        // UD-299: detect sync_root drift between runs. state.db is per-profile
        // (not per-(profile, sync_root)), so editing sync_root in config.toml
        // leaves the DB indexing the old tree. Every absent old-root path then
        // becomes a DeleteRemote in the next plan. Refuse to run when the
        // stored sync_root differs from the current one — `--reset` is the
        // bypass (it wipes state.db, so the next run records the new root).
        val currentRoot =
            syncRoot
                .toAbsolutePath()
                .normalize()
                .toString()
        val storedRoot = db.getSyncState("sync_root")
        if (storedRoot.isNullOrEmpty()) {
            db.setSyncState("sync_root", currentRoot)
        } else if (!sameSyncRoot(storedRoot, currentRoot)) {
            throw IllegalStateException(
                "sync_root changed from '$storedRoot' to '$currentRoot'. " +
                    "The state DB still indexes the old tree and would produce " +
                    "spurious del-remote actions. Run with --reset to wipe state " +
                    "and re-sync from scratch, or revert sync_root in config.toml.",
            )
        }

        // UD-256: scope-persistence guard against the 2026-05-16
        // delete-the-cloud-by-bidirectional-on-partial-local pattern.
        //
        // A profile that has ever been operated with `--sync-path` accumulates
        // a persisted `effective_scope` (sync_state key, TAB-separated list of
        // normalised paths). On every run:
        //  - If `syncPath` is set this run: UNION it into the persisted scope
        //    (whether the scope was previously empty or not). The run proceeds
        //    with the runtime scope filter as before.
        //  - If `syncPath` is NULL and the persisted scope is non-empty and the
        //    run is bidirectional-apply: REFUSE unless --full-tree was passed.
        //    The reconciler would otherwise treat every cloud path outside the
        //    persisted scope as "user-deleted-locally" and propagate DELETE.
        //  - If `--full-tree` was passed: clear the persisted scope (the user
        //    is consciously taking the profile out of scoped mode) and proceed.
        //  - Dry-run is allowed-with-warning, not refused, so the operator can
        //    inspect what a `--full-tree` would do without an unrecoverable
        //    commitment.
        //  - Upload-only and download-only are not refused — UD-737 already
        //    blocks delete propagation in those modes, so the catastrophe
        //    pattern can't trigger from the directions alone.
        //
        // UD-256 / PR #45 review (Codex P1): **never mutate effective_scope
        // in dry-run.** Dry-run is contractually side-effect-free (see
        // UD-738's in-memory-shadow handling of `--reset --dry-run`). Earlier
        // versions of this block wrote to sync_state unconditionally — so a
        // `--full-tree --dry-run` preview would permanently clear the guard,
        // and a subsequent bare bidirectional apply on the same partial local
        // tree would no longer be refused. Same hazard applied to
        // `--sync-path X --dry-run`: previewing a scope addition silently
        // committed it. Both writes are now gated on `!dryRun`. The refusal
        // / warning branches are pure reads and stay structured as before.
        val priorScope = loadEffectiveScope()
        if (allowFullTreeReconciliation) {
            if (priorScope.isNotEmpty() && !dryRun) {
                log.info(
                    "UD-256: --full-tree clears persisted effective_scope ({} entries)",
                    priorScope.size,
                )
                db.setSyncState("effective_scope", "")
            } else if (priorScope.isNotEmpty() && dryRun) {
                log.info(
                    "UD-256: --full-tree --dry-run previewing whole-cloud reconciliation; persisted effective_scope ({} entries) left untouched",
                    priorScope.size,
                )
            }
        } else if (syncPath != null) {
            val unioned = (priorScope + syncPath).distinct()
            if (unioned.size != priorScope.size && !dryRun) {
                log.info(
                    "UD-256: persisting effective_scope += '{}' (now {} entry/entries)",
                    syncPath,
                    unioned.size,
                )
                db.setSyncState("effective_scope", unioned.joinToString("\t"))
            } else if (unioned.size != priorScope.size && dryRun) {
                log.info(
                    "UD-256: --dry-run with new --sync-path '{}' — would extend effective_scope to {} entries (not persisted)",
                    syncPath,
                    unioned.size,
                )
            }
        } else if (priorScope.isNotEmpty() && syncDirection == SyncDirection.BIDIRECTIONAL) {
            val msg =
                "UD-256: this profile has been used with scoped operations " +
                    "(--sync-path) in the past. Persisted effective_scope: " +
                    priorScope.joinToString(", ") { "'$it'" } + ". " +
                    "Running un-scoped bidirectional reconciliation would treat " +
                    "every cloud path outside the persisted scope as a deletion " +
                    "candidate. Either: (a) pass --sync-path <one of the above> " +
                    "to operate within the existing scope, or (b) pass --full-tree " +
                    "to clear the persisted scope and re-enable whole-cloud " +
                    "reconciliation (DANGER — this is the path that produced the " +
                    "2026-05-16 405-folder-delete incident; only use when the " +
                    "local sync_root is known to mirror the entire cloud)."
            if (dryRun) {
                reporter.onWarning(msg)
            } else {
                throw IllegalStateException(msg)
            }
        }

        trashManager?.purge(trashRetentionDays)
        versionManager?.pruneByAge(versionRetentionDays)

        // UD-297: empty-local + populated-DB sanity check. Catches the
        // wrong-sync_root case (user pointed at an empty directory while
        // the state DB knows about thousands of remote entries) before
        // the reconciler turns it into a wall of del-remote actions.
        // Fires in dry-run too — that's where the user is most likely
        // to notice and the system has the least to lose by being loud.
        //
        // Gate on the HYDRATED entry count, not the total. A state.db
        // populated by previous failed gather passes (Internxt "dance"
        // pattern: many delta walks, no successful downloads) is full of
        // unhydrated rows that represent cloud-side items the user still
        // needs to download — refusing to run there blocks the UD-225
        // recovery loop from doing its job. The original concern (mass
        // DeleteRemote when sync_root is mis-pointed) only applies when
        // hydrated entries are missing locally; the Reconciler rewrites
        // unhydrated-FILE DELETED localChanges into DownloadContent via
        // its UD-225a recovery downgrade, and drops DeleteRemote actions
        // for unhydrated-FOLDER rows in a post-detectMoves filter, so
        // the only way DeleteRemote reaches the apply phase is via
        // hydrated rows.
        // Live repro 2026-05-20: 171 386 file rows all is_hydrated=0,
        // sync_root empty, old guard refused with a misleading
        // "--force-delete" hint that would have catastrophically wiped
        // the cloud side.
        //
        // #137: --download-only is exempt. In download-only mode the
        // destructive local→remote-delete direction is already gated by
        // the direction filter, so the mis-pointed-sync_root mass-delete
        // risk does NOT apply. A download-only user who intentionally
        // wiped local and wants the cloud copy back (a legitimate
        // rehydrate) must not be blocked here.
        val hydratedEntryCount = db.getHydratedEntryCount()
        if (!forceDelete && syncDirection != SyncDirection.DOWNLOAD &&
            hydratedEntryCount > 10 && isSyncRootEffectivelyEmpty()
        ) {
            val msg =
                "Local sync_root '$syncRoot' is empty, but state DB knows " +
                    "$hydratedEntryCount previously-hydrated entries (of " +
                    "${db.getEntryCount()} total). sync_root probably points at " +
                    "the wrong directory. To rehydrate from cloud, re-run with " +
                    "--download-only. To proceed with a bidirectional sync after " +
                    "an intentional local wipe, re-run with --force-delete."
            if (dryRun) {
                reporter.onWarning(msg)
            } else {
                throw IllegalStateException(msg)
            }
        }

        // #137: create sync_root only after the guard — an aborted (guard-fired)
        // run must not leave an empty sync_root dir behind.
        java.nio.file.Files.createDirectories(syncRoot)

        // UD-747 (UD-744 slice): pass the previous run's wall-clock seconds
        // for each phase to the reporter so the heartbeat can render a
        // bucketed ETA. First-run / `--reset` scans simply have no key in
        // sync_state and the reporter falls back to throughput-only output.
        // UD-748: also pass the previous run's *final item count* for each
        // phase so the bucket helper can use progress-fraction extrapolation
        // when the current run is faster/slower than last time.
        db.getSyncState("last_scan_secs_remote")?.toLongOrNull()?.let {
            reporter.onScanHistoricalHint("remote", it)
        }
        db.getSyncState("last_scan_count_remote")?.toIntOrNull()?.let {
            reporter.onScanCountHint("remote", it)
        }
        // Surface the cursor THIS scan will filter on, plus whether the prior
        // gather that produced it ran clean. delta_cursor is the active filter
        // (null on first sync / `--reset`); pending_cursor_complete=false means
        // a prior incomplete pass left the cursor pinned at its older value.
        reporter.onScanCursorHint(
            phase = "remote",
            cursor = db.getSyncState("delta_cursor")?.ifBlank { null },
            complete = db.getSyncState("pending_cursor_complete")?.toBooleanStrictOrNull() ?: true,
        )
        // Remote-shrink guard inputs (sync path only): snapshot the pre-gather
        // tracked-row baseline and whether this pass is expected to be a full
        // enumeration (delta_cursor unset, matching gatherRemoteChanges). The
        // actual verdict — which a 410 cursor-expiry recovery can upgrade inside
        // the gather — is read back after the gather below.
        val preGatherTrackedRows = db.getEntryCount()
        val fullEnumerationExpected = db.getSyncState("delta_cursor").isNullOrEmpty()
        // A full enumeration against an established baseline must run NON-streaming
        // so the remote-shrink guard can abort before any transfer is dispatched —
        // the streaming gather dispatches safe-now uploads/downloads mid-scan, which
        // would otherwise execute before a partial listing is detected.
        val shrinkGateMayApply = fullEnumerationExpected && preGatherTrackedRows >= ENUM_TRUST_MIN_BASELINE
        val remotePhaseStart = System.currentTimeMillis()
        reporter.onScanProgress("remote", 0)
        // Streaming reconciliation reorders the phases: local scan first,
        // then per-page remote gather reconciles each page against the
        // full local map (spec §1 decision 1 — "Local scan completes
        // before streaming remote loop starts"). The non-streaming path
        // keeps the historical remote-then-local order so a flag flip
        // doesn't change the timing for tests + telemetry baselines.
        val localChangesForStreaming: Map<String, ChangeState>?
        val streamingActions: List<SyncAction>?
        val allRemoteChanges: Map<String, CloudItem>
        if (streamingReconciliation && !skipRemoteGather && !shrinkGateMayApply) {
            db.getSyncState("last_scan_secs_local")?.toLongOrNull()?.let {
                reporter.onScanHistoricalHint("local", it)
            }
            db.getSyncState("last_scan_count_local")?.toIntOrNull()?.let {
                reporter.onScanCountHint("local", it)
            }
            val localPhaseStart = System.currentTimeMillis()
            reporter.onScanProgress("local", 0)
            val allLocalChangesPre =
                scanner.scan { count ->
                    reporter.onScanProgress("local", count)
                }
            val localChangesPre =
                if (syncPath != null) {
                    val ancestors = syncPathAncestors(syncPath)
                    allLocalChangesPre.filterKeys {
                        it.startsWith(syncPath) || it == syncPath || it in ancestors
                    }
                } else {
                    allLocalChangesPre
                }
            reporter.onScanProgress("local", localChangesPre.size)
            val localScanSecs = (System.currentTimeMillis() - localPhaseStart) / 1000
            db.setSyncState("last_scan_secs_local", localScanSecs.toString())
            db.setSyncState("last_scan_count_local", localChangesPre.size.toString())
            localChangesForStreaming = localChangesPre

            val (remoteMap, actions) =
                gatherStreamingChanges(
                    localChanges = localChangesPre,
                    downloaded = downloaded,
                    uploaded = uploaded,
                    transferFailures = transferFailures,
                    authFailure = authFailure,
                    executedPaths = executedPaths,
                    transferSemaphore = transferSemaphore,
                )
            allRemoteChanges = remoteMap
            streamingActions = actions
        } else {
            localChangesForStreaming = null
            streamingActions = null
            // UD-236: skipRemoteGather (apply mode) bypasses provider.delta() entirely.
            // The recovery loops in Reconciler.reconcile pick up any pending UD-225/UD-901
            // rows from a prior refresh and emit DownloadContent / Upload actions for them.
            allRemoteChanges =
                if (skipRemoteGather) {
                    log.info("Apply mode: skipping remote gather; recovery loops will surface pending entries")
                    emptyMap()
                } else {
                    gatherRemoteChanges()
                }
        }

        val remoteChanges =
            if (syncPath != null) {
                allRemoteChanges.filterKeys { it.startsWith(syncPath) || it == syncPath }
            } else {
                allRemoteChanges
            }
        reporter.onScanProgress("remote", remoteChanges.size)
        // UD-747 / UD-748: persist the wall-clock + final count for next
        // run's ETA computation.
        val remoteScanSecs = (System.currentTimeMillis() - remotePhaseStart) / 1000
        db.setSyncState("last_scan_secs_remote", remoteScanSecs.toString())
        db.setSyncState("last_scan_count_remote", remoteChanges.size.toString())

        // Remote-shrink guard: abort a full, unscoped enumeration that observed far
        // fewer live remote items than the pre-gather baseline — a partial listing
        // (flaky provider, swallowed transient errors) that would otherwise plan
        // spurious mass uploads/deletes (and --force-delete would apply them).
        // Uses the gather's ACTUAL full-enumeration verdict (a 410 cursor-expiry
        // recovery upgrades an incremental pass to a full re-enumeration inside the
        // gather), falling back to the pre-gather expectation. allRemoteChanges is
        // the unscoped gather result; remoteChanges may be syncPath-filtered.
        // skipRemoteGather (apply mode) has no fresh listing to judge.
        val actualFullEnumeration =
            db.getSyncState("last_gather_full")?.toBooleanStrictOrNull() ?: fullEnumerationExpected
        if (actualFullEnumeration && syncPath == null && !skipRemoteGather) {
            val observedAlive = allRemoteChanges.values.count { !it.deleted }
            remoteShrinkWarningOrNull(observedAlive, preGatherTrackedRows)?.let { msg ->
                if (dryRun) {
                    reporter.onWarning(msg)
                } else {
                    throw IllegalStateException(msg)
                }
            }
        }

        // Streaming-reconciliation auto-flip: after the first successful
        // streaming scan, persist the sentinel so subsequent runs default to
        // streaming without re-opting via CLI/TOML. "Successful" here means
        // the streaming path ran AND the gather reported all pages complete
        // (pending_cursor_complete=true, written by the just-completed gather).
        // CLI/TOML override always wins on the next launch — the sentinel
        // sits at the lowest tier of [SyncConfig.resolveStreamingReconciliation].
        if (streamingReconciliation && !skipRemoteGather &&
            db.getSyncState("pending_cursor_complete")?.equals("true", ignoreCase = true) == true
        ) {
            db.setSyncState(SyncConfig.STREAMING_RECONCILIATION_SENTINEL_KEY, "true")
        }

        // Local-changes resolution: streaming captured these above so the
        // per-page reconcile could see them; non-streaming runs the scan
        // here on the historical order.
        val localChanges: Map<String, ChangeState>
        if (localChangesForStreaming != null) {
            localChanges = localChangesForStreaming
        } else {
            db.getSyncState("last_scan_secs_local")?.toLongOrNull()?.let {
                reporter.onScanHistoricalHint("local", it)
            }
            db.getSyncState("last_scan_count_local")?.toIntOrNull()?.let {
                reporter.onScanCountHint("local", it)
            }
            val localPhaseStart = System.currentTimeMillis()
            reporter.onScanProgress("local", 0)
            val allLocalChanges =
                scanner.scan { count ->
                    // UD-742: scanner emits this every 5k items / 10s during long walks.
                    reporter.onScanProgress("local", count)
                }
            localChanges =
                if (syncPath != null) {
                    val ancestors = syncPathAncestors(syncPath)
                    allLocalChanges.filterKeys { it.startsWith(syncPath) || it == syncPath || it in ancestors }
                } else {
                    allLocalChanges
                }
            reporter.onScanProgress("local", localChanges.size)
            val localScanSecs = (System.currentTimeMillis() - localPhaseStart) / 1000
            db.setSyncState("last_scan_secs_local", localScanSecs.toString())
            db.setSyncState("last_scan_count_local", localChanges.size.toString())
        }

        // UD-240g: pass reporter so the phase emits a
        // heartbeat instead of going silent for many seconds on big first-syncs;
        // UD-901a: pass syncPath so the recovery loops respect scope and don't
        // resurrect orphans outside the user's requested subtree).
        //
        // Streaming reconciliation already ran resolveSlice per page and
        // accumulated through StreamingReconcileBuffer; finalize against
        // the union of streamed safe-now + deferred-drained actions here
        // so the recovery loops, case-collision detection, move detection,
        // and final sort run exactly once against the full action set.
        // Upload-direction completeness gate: when the just-completed gather is
        // INCOMPLETE, a local-present/remote-absent path may be an un-enumerated
        // subtree rather than a genuinely-new file, so the reconciler must defer
        // new-local creates (mirror of the delete-side "reap only on complete").
        // apply mode (skipRemoteGather) has no fresh listing, so it is never gated.
        val enumerationComplete =
            skipRemoteGather || (db.getSyncState("pending_cursor_complete")?.toBooleanStrictOrNull() ?: true)
        val reconciledActions =
            if (streamingActions != null) {
                reconciler.finalizeStreaming(streamingActions, remoteChanges, localChanges, syncPath,
                    downloadOnly = syncDirection == SyncDirection.DOWNLOAD,
                    enumerationComplete = enumerationComplete)
            } else {
                reconciler.reconcile(remoteChanges, localChanges, reporter, syncPath,
                    downloadOnly = syncDirection == SyncDirection.DOWNLOAD,
                    enumerationComplete = enumerationComplete)
            }
        logUnhydratedFolderSkips()

        db.batch {
            updateRemoteEntries(allRemoteChanges)
        }

        // UD-264: top-level-never-hydrated guard. For every DeleteRemote action,
        // check whether *any* descendant under the action's top-level cloud
        // folder has ever been hydrated locally (is_hydrated=1 OR local_mtime
        // IS NOT NULL). If not, the top-level has never been touched by this
        // unidrive install — propagating deletes outward would mirror the
        // 2026-05-16 incident shape, where state.db indexed 280k entries via
        // delta but no descendant of /Documents/CyberLink/, /.userhome/win11/
        // etc. ever held a hydrated local row (the user used the official
        // Internxt client to write them to cloud; unidrive only ever saw them
        // through delta). forceDelete bypasses; --ignore-top-level-guard logs
        // but does not skip. Skipped paths are appended to skipped-ops.jsonl
        // for post-mortem visibility.
        val allActions =
            if (forceDelete) {
                reconciledActions
            } else {
                applyTopLevelHydrationGuard(reconciledActions)
            }

        val actions =
            when (syncDirection) {
                SyncDirection.UPLOAD ->
                    allActions.filter {
                        // UD-737: DeleteRemote only flows in upload-direction
                        // when --propagate-deletes is explicitly set. The flag
                        // name `--upload-only` reads as a one-way uploader,
                        // and the default is push-additive: only Upload /
                        // CreateRemoteFolder / MoveRemote land on remote.
                        it is SyncAction.Upload ||
                            (propagateDeletes && it is SyncAction.DeleteRemote) ||
                            it is SyncAction.CreateRemoteFolder ||
                            it is SyncAction.MoveRemote ||
                            it is SyncAction.Conflict ||
                            it is SyncAction.RemoveEntry
                    }
                SyncDirection.DOWNLOAD ->
                    allActions.filter {
                        it is SyncAction.CreatePlaceholder ||
                            it is SyncAction.UpdatePlaceholder ||
                            it is SyncAction.DownloadContent ||
                            it is SyncAction.DeleteLocal ||
                            it is SyncAction.MoveLocal ||
                            it is SyncAction.Conflict ||
                            it is SyncAction.RemoveEntry
                    }
                SyncDirection.BIDIRECTIONAL -> allActions
            }

        // UD-201: pass both pre-filter (reconciler verdict) and post-filter
        // (executor input) counts so reporters can distinguish "reconciler
        // decided N actions" from "executor will run M after --upload-only
        // / --download-only filtering." Behaviour unchanged; only the
        // signal passed to the reporter is richer.
        val filterReason: String? =
            if (actions.size != allActions.size) {
                when (syncDirection) {
                    SyncDirection.UPLOAD -> "--upload-only"
                    SyncDirection.DOWNLOAD -> "--download-only"
                    SyncDirection.BIDIRECTIONAL -> null
                }
            } else {
                null
            }
        reporter.onActionCount(actions.size, allActions.size, filterReason)

        if (actions.isEmpty()) {
            // UD-260: promote pending cursor (see promotePendingCursor for the
            // best-effort cursor-advance semantic on incomplete gathers).
            promotePendingCursor()
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(0, 0, 0, duration)
            return
        }

        // UD-298: legacy whole-inventory percentage check. Still in place for
        // back-compat — preserves the same threshold name and behaviour that
        // operators have come to rely on. Evaluates in dry-run as a warning
        // (UD-298 reframe) and throws otherwise.
        //
        // UD-265: two additional axes on top of the legacy check.
        //   1) maxDeleteAbsolute (default 50): trips on any run planning > N
        //      deletes, regardless of inventory size. Catches "wide blast"
        //      runs on large drives where 0.14 % of 280k entries (the
        //      2026-05-16 incident: 405 deletes) is still a catastrophe.
        //   2) maxDeletePerSubtreePercent (default 80): trips when any single
        //      top-level cloud folder affected by deletes has > N% of its
        //      tracked entries marked for deletion. Catches "delete 100 % of
        //      /Documents/CyberLink/" runs that are tiny fractions of the
        //      whole drive but catastrophic within their subtree.
        //
        // Any single axis tripping aborts apply / warns in dry-run.
        // forceDelete bypasses all three. 0 disables the corresponding axis.
        if (!forceDelete) {
            val deleteActions = actions.filter { it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal }
            val deleteCount = deleteActions.size
            val totalEntries = db.getEntryCount()

            // UD-298 legacy whole-inventory percentage axis.
            if (maxDeletePercentage in 1..99 && totalEntries > 0 && deleteCount > 10) {
                val pct = deleteCount * 100 / totalEntries
                if (pct > maxDeletePercentage) {
                    val msg =
                        "Deletion safeguard: $deleteCount of $totalEntries files ($pct%) would be deleted, " +
                            "exceeding max_delete_percentage=$maxDeletePercentage%. " +
                            "sync_root='$syncRoot'. Use --force-delete to override."
                    if (dryRun) {
                        reporter.onWarning(msg)
                    } else {
                        throw IllegalStateException(msg)
                    }
                }
            }

            // UD-265 axis 1: absolute count cap.
            if (maxDeleteAbsolute > 0 && deleteCount > maxDeleteAbsolute) {
                val msg =
                    "UD-265 Deletion safeguard: $deleteCount deletes planned, " +
                        "exceeding max_delete_absolute=$maxDeleteAbsolute. " +
                        "sync_root='$syncRoot'. Use --force-delete to override."
                if (dryRun) {
                    reporter.onWarning(msg)
                } else {
                    throw IllegalStateException(msg)
                }
            }

            // UD-265 axis 2: per-top-level-subtree percentage cap. Group
            // delete actions by their top-level segment, count the tracked
            // entries under that top-level in state.db, and compare. Skip
            // subtrees with fewer than 5 tracked entries — small folders
            // produce noisy 100% trips on legitimate cleanups.
            if (maxDeletePerSubtreePercent in 1..99 && deleteCount > 0) {
                val byTopLevel = deleteActions.groupBy { topLevelOf(it.path) }
                for ((top, group) in byTopLevel) {
                    if (top == null) continue
                    val tracked = db.countEntriesUnderTopLevel(top)
                    if (tracked < 5) continue
                    val pct = group.size * 100 / tracked
                    if (pct > maxDeletePerSubtreePercent) {
                        val msg =
                            "UD-265 Deletion safeguard: ${group.size} of $tracked entries " +
                                "under top-level '$top' ($pct%) would be deleted, exceeding " +
                                "max_delete_per_subtree_percent=$maxDeletePerSubtreePercent%. " +
                                "sync_root='$syncRoot'. Use --force-delete to override."
                        if (dryRun) {
                            reporter.onWarning(msg)
                        } else {
                            throw IllegalStateException(msg)
                        }
                    }
                }
            }
        }

        if (dryRun) {
            val counts = mutableMapOf<String, Int>()
            actions.forEachIndexed { index, action ->
                val label = actionLabel(action)
                reporter.onActionProgress(index + 1, actions.size, label, displayPath(action))
                counts[label] = (counts[label] ?: 0) + 1
                when (action) {
                    is SyncAction.DownloadContent -> downloaded.incrementAndGet()
                    is SyncAction.CreatePlaceholder -> if (action.shouldHydrate) downloaded.incrementAndGet()
                    is SyncAction.UpdatePlaceholder -> if (action.wasHydrated) downloaded.incrementAndGet()
                    is SyncAction.Upload -> uploaded.incrementAndGet()
                    is SyncAction.Conflict -> conflicts.incrementAndGet()
                    else -> {}
                }
            }
            // UD-260: promote pending cursor (see promotePendingCursor for the
            // best-effort cursor-advance semantic on incomplete gathers).
            promotePendingCursor()
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(downloaded.get(), uploaded.get(), conflicts.get(), duration, counts)
            return
        }

        // perProviderConcurrency + transferSemaphore are now declared at
        // the top of doSyncOnce so the streaming-gather executor and Pass 2
        // share one concurrency budget. The per-provider audit values
        // (docs/providers/<id>-robustness.md §5 → ProviderMetadata) still
        // drive the cap.
        log.info(
            "Pass 2 transfer semaphore: provider={} maxConcurrentTransfers={}",
            providerId.ifBlank { "<unknown>" },
            perProviderConcurrency,
        )

        var consecutiveFailures = 0
        val completedActions = AtomicInteger(0)
        // UD-745: count of Pass 1 actions that hit a non-recoverable failure
        // (mkdir/move/delete/conflict). Combined with `transferFailures`
        // below for the headline `failed` count in onSyncComplete.
        val passOneFailures = AtomicInteger(0)

        // Batched into one SQLite transaction — avoids one fsync per action.
        // Wrap in Priority.Foreground so the provider's throttle coordinator
        // gates the corresponding Drive REST calls in the foreground lane and
        // any concurrent background scan traffic yields.
        val sequentialActions =
            topologicalApplyOrder(
                actions.filter {
                    it !is SyncAction.DownloadContent && it !is SyncAction.Upload
                },
            )
        db.beginBatch()
        try {
            withContext(Priority.Foreground) {
                for (action in sequentialActions) {
                    try {
                        when (action) {
                            is SyncAction.CreatePlaceholder -> {
                                applyCreatePlaceholder(action)
                                if (action.shouldHydrate) downloaded.incrementAndGet()
                            }
                            is SyncAction.UpdatePlaceholder -> {
                                applyUpdatePlaceholder(action)
                                if (action.wasHydrated) downloaded.incrementAndGet()
                            }
                            is SyncAction.MoveRemote -> applyMoveRemote(action)
                            is SyncAction.MoveLocal -> applyMoveLocal(action)
                            is SyncAction.DeleteLocal -> applyDeleteLocal(action)
                            is SyncAction.DeleteRemote -> applyDeleteRemote(action)
                            is SyncAction.CreateRemoteFolder -> applyCreateRemoteFolder(action)
                            is SyncAction.Conflict -> {
                                applyConflict(action)
                                conflicts.incrementAndGet()
                            }
                            is SyncAction.RemoveEntry -> applyRemoveEntry(action)
                            else -> {}
                        }
                        consecutiveFailures = 0
                    } catch (e: AuthenticationException) {
                        // UD-253: include exception class + full stack for auth failures.
                        // UD-203: append `requestId=<id>` when the provider's exception
                        // carries one, so the ERROR log line points at a Graph / S3 /
                        // Internxt support trace.
                        log.error(
                            "Authentication failed, stopping sync: {}: {}{}",
                            e.javaClass.simpleName,
                            e.message,
                            org.krost.unidrive.requestIdSuffix(e),
                            e,
                        )
                        throw e
                    } catch (e: Exception) {
                        consecutiveFailures++
                        passOneFailures.incrementAndGet()
                        // UD-253: class name + throwable (SLF4J renders stack trace when the
                        // last arg is a Throwable) so WARNs are self-diagnosing in the log.
                        // UD-203: requestIdSuffix(e) renders ` requestId=<id>` when the
                        // caught exception is a ProviderException with a non-null id,
                        // empty string otherwise — same line shape as before for non-
                        // provider failures.
                        log.warn(
                            "Action failed for {} ({} consecutive): {}: {}{}",
                            action.path,
                            consecutiveFailures,
                            e.javaClass.simpleName,
                            e.message,
                            org.krost.unidrive.requestIdSuffix(e),
                            e,
                        )
                        reporter.onWarning("Failed: ${action.path} - ${e.message}")
                        logFailure(action, e)
                        // UD-248: previously, hitting 3 consecutive action failures
                        // threw ProviderException which tore down the whole pass and
                        // made the watch loop restart syncOnce (re-enumerating the
                        // entire remote tree — expensive for 22k-file profiles).
                        // Now we skip the failing action and continue with the rest.
                        // The watch loop's own cycle-failure backoff handles the
                        // truly-broken case (every action fails → next cycle delays
                        // longer). Catastrophic outage still trips at
                        // CONSECUTIVE_SYNC_FAILURE_HARD_CAP, far above the 3-in-a-row
                        // threshold that was firing on transient provider 500s.
                        if (consecutiveFailures >= CONSECUTIVE_SYNC_FAILURE_HARD_CAP) {
                            log.error(
                                "Stopping sync pass: {} consecutive action failures " +
                                    "(last: {} on {}) — treating as upstream outage",
                                consecutiveFailures,
                                e.javaClass.simpleName,
                                action.path,
                            )
                            throw ProviderException(
                                "Stopping sync after $consecutiveFailures consecutive failures",
                                e,
                            )
                        }
                        // Exponential backoff capped at 10s so the pass doesn't
                        // stall indefinitely on a failure cluster.
                        delay(minOf(2_000L * consecutiveFailures, 10_000L))
                    }
                    reporter.onActionProgress(completedActions.incrementAndGet(), actions.size, actionLabel(action), displayPath(action))
                }
            }
            db.commitBatch()
        } catch (e: Exception) {
            db.rollbackBatch()
            throw e
        }

        // UD-236: refresh-mode short-circuit. Pass 1 (metadata) is done; transfers
        // remain pending in the DB as remoteId=null / isHydrated=false rows. The
        // pending_cursor stays unpromoted so the next `apply` (or `sync`) finalises.
        if (skipTransfers) {
            val pendingTransfers = actions.count { it is SyncAction.DownloadContent || it is SyncAction.Upload }
            log.info(
                "Refresh mode: skipping Pass 2; {} pending transfer(s) deferred (downloads + uploads)",
                pendingTransfers,
            )
            val duration = System.currentTimeMillis() - startTime
            reporter.onSyncComplete(
                downloaded = 0,
                uploaded = 0,
                conflicts = conflicts.get(),
                durationMs = duration,
                actionCounts = actions.groupingBy { actionLabel(it) }.eachCount(),
                failed = passOneFailures.get(),
            )
            return
        }

        // UD-222: Pass 2 now carries all hydration for new/modified remote files. Failures are
        // tracked so we (a) rethrow AuthenticationException after the scope exits cleanly, and
        // (b) skip cursor promotion when any transfer failed — otherwise Graph's delta would
        // advance past the failed items and they'd never retry.
        val transferActions =
            actions.filter {
                it is SyncAction.DownloadContent || it is SyncAction.Upload
            }
        // transferFailures + authFailure are now declared at the top of
        // doSyncOnce so the streaming-gather executor (gatherStreamingChanges)
        // can share them. On the non-streaming path nothing else mutates
        // them before this point, so the move is behaviour-preserving.
        try {
            coroutineScope {
                for (action in transferActions) {
                    // Streaming-gather executor may have already dispatched
                    // this transfer mid-scan; skip the second dispatch so we
                    // don't redo the API round-trip or hit a Windows
                    // file-locked-by-prior-write race.
                    if (action.path in executedPaths) continue
                    when (action) {
                        is SyncAction.DownloadContent -> {
                            launch {
                                withContext(Priority.Foreground) {
                                    transferSemaphore.withPermit {
                                        try {
                                            applyDownload(action)
                                            downloaded.incrementAndGet()
                                        } catch (e: AuthenticationException) {
                                            // UD-253: include exception class + full stack.
                                            log.error(
                                                "Authentication failed during download of {}: {}: {}",
                                                action.path,
                                                e.javaClass.simpleName,
                                                e.message,
                                                e,
                                            )
                                            restoreToPlaceholder(action.path, action.remoteItem)
                                            transferFailures.incrementAndGet()
                                            authFailure.compareAndSet(null, e)
                                            this@coroutineScope.cancel()
                                        } catch (e: CancellationException) {
                                            restoreToPlaceholder(action.path, action.remoteItem)
                                            throw e
                                        } catch (e: PermanentDownloadFailureException) {
                                            handlePermanentDownloadFailure(action, e)
                                            transferFailures.incrementAndGet()
                                        } catch (e: Exception) {
                                            // UD-253: class name + throwable (SLF4J stack trace).
                                            log.warn(
                                                "Download failed for {}: {}: {}",
                                                action.path,
                                                e.javaClass.simpleName,
                                                e.message,
                                                e,
                                            )
                                            reporter.onWarning("Failed: ${action.path} - ${e.message}")
                                            logFailure(action, e)
                                            restoreToPlaceholder(action.path, action.remoteItem)
                                            transferFailures.incrementAndGet()
                                        } finally {
                                            reporter.onActionProgress(
                                                completedActions.incrementAndGet(),
                                                actions.size,
                                                actionLabel(action),
                                                displayPath(action),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        is SyncAction.Upload -> {
                            launch {
                                withContext(Priority.Foreground) {
                                    transferSemaphore.withPermit {
                                        try {
                                            applyUpload(action)
                                            uploaded.incrementAndGet()
                                        } catch (e: AuthenticationException) {
                                            // UD-253: include exception class + full stack.
                                            log.error(
                                                "Authentication failed during upload of {}: {}: {}",
                                                action.path,
                                                e.javaClass.simpleName,
                                                e.message,
                                                e,
                                            )
                                            transferFailures.incrementAndGet()
                                            authFailure.compareAndSet(null, e)
                                            this@coroutineScope.cancel()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            // UD-253: class name + throwable (SLF4J stack trace).
                                            log.warn(
                                                "Upload failed for {}: {}: {}",
                                                action.path,
                                                e.javaClass.simpleName,
                                                e.message,
                                                e,
                                            )
                                            reporter.onWarning("Failed: ${action.path} - ${e.message}")
                                            logFailure(action, e)
                                            transferFailures.incrementAndGet()
                                        } finally {
                                            reporter.onActionProgress(
                                                completedActions.incrementAndGet(),
                                                actions.size,
                                                actionLabel(action),
                                                displayPath(action),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: CancellationException) {
            // Scope cancelled by an AuthenticationException in a child job — the rethrow happens
            // just below so callers see the auth failure, not the cancellation.
            if (authFailure.get() == null) throw e
        }

        // UD-222: AuthenticationException trumps everything — surface it to the caller so token
        // refresh / re-auth flows trigger instead of a silent "0 downloaded, many failed" result.
        authFailure.get()?.let { throw it }

        // Promote unconditionally. The original gate was `transferFailures == 0`,
        // which protected against "cursor advances past a failed download and the
        // item is never re-seen by future deltas". That concern is now handled by
        // the UD-225 / UD-901 recovery loops in Reconciler.reconcile, which scan
        // db.getAllEntries() every pass and synthesise DownloadContent for any
        // isHydrated=false row and Upload for any remoteId=null row that no live
        // action already covers.
        //
        // Holding the cursor on transfer failures looked safe in isolation but
        // produced an inescapable first-sync loop on busy drives: a 200 k-item
        // gather is essentially guaranteed to have *some* transient transfer
        // failure (Internxt 503s, network blips, partial uploads), so every run
        // ended with the cursor pinned at null → next run re-enumerated from
        // scratch → same outcome. Live repro 2026-05-20 def535f1: 14.8 h scan,
        // 218 k items, pending_cursor written, delta_cursor never set; the
        // following run did the full enum again.
        promotePendingCursor()

        val duration = System.currentTimeMillis() - startTime
        reporter.onSyncComplete(
            downloaded.get(),
            uploaded.get(),
            conflicts.get(),
            duration,
            failed = passOneFailures.get() + transferFailures.get(),
        )
    }

    private suspend fun gatherRemoteChanges(): Map<String, CloudItem> = withContext(Priority.Background) {
        val storedCursor = db.getSyncState("delta_cursor")
        val cursor = storedCursor?.ifEmpty { null }
        var isFullSync = cursor == null
        var changes = mutableMapOf<String, CloudItem>()

        // UD-223 fast-bootstrap: on first-sync only, adopt the remote's current
        // cursor without enumerating. Provider must declare FastBootstrap; otherwise
        // log a warning and fall through. On success, the subsequent full-sync
        // deletion sweep (detectMissingAfterFullSync) is skipped — no enumeration
        // means no authoritative item set to diff against, so we must NOT treat
        // absence as deletion.
        if (fastBootstrap && isFullSync) {
            if (Capability.FastBootstrap in provider.capabilities()) {
                when (val result = provider.deltaFromLatest()) {
                    is CapabilityResult.Success -> {
                        val page = result.value
                        for (item in page.items) {
                            val resolved = resolveItemPath(item) ?: continue
                            changes[resolved.path] = resolved
                        }
                        // UD-223: promote the cursor directly. Bootstrap guarantees no transfers
                        // fire on this run (the action list is empty by construction), so the
                        // usual "pending → delta after zero failures" dance is skipped —
                        // otherwise syncOnce's `if (actions.isEmpty()) return` short-circuits
                        // the promotion and the next run re-bootstraps forever.
                        db.setSyncState("delta_cursor", page.cursor)
                        db.setSyncState("last_full_scan", Instant.now().toString())
                        // Invalidate any in-progress scan checkpoint. The offsets persisted
                        // in `scan_in_progress_marker` correspond to the previous gather's
                        // cursor (cursor=null full enum, or an earlier delta cursor); they
                        // index into a different result set than what the new cursor=now
                        // delta will return. Resuming with the old offsets would silently
                        // page past items modified in the seam. Clearing the staging slice
                        // is cheap (no live `sync_entries` rows touched).
                        db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)?.let { staleScanId ->
                            log.info(
                                "UD-223 fast-bootstrap: invalidating stale scan checkpoint scan={} marker={}",
                                staleScanId,
                                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER),
                            )
                            db.completeScan(staleScanId)
                        }
                        val stamp = Instant.now().toString()
                        val msg =
                            "UD-223 fast-bootstrap: adopted remote cursor as of $stamp. " +
                                "Items that already exist on the remote will stay invisible until they next mutate. " +
                                "Upload-direction sync is unaffected."
                        log.warn(msg)
                        reporter.onWarning(msg)
                        return@withContext changes
                    }
                    is CapabilityResult.Unsupported -> {
                        log.warn(
                            "UD-223 fast-bootstrap requested but provider '{}' does not support it ({}). " +
                                "Falling back to full first-sync enumeration.",
                            providerId,
                            result.reason,
                        )
                    }
                }
            } else {
                log.warn(
                    "UD-223 fast-bootstrap requested but provider '{}' does not declare the capability. " +
                        "Falling back to full first-sync enumeration.",
                    providerId,
                )
            }
        }

        // If includeShared is requested but the provider doesn't actually support
        // delta-with-shared, silently fall back to plain delta — this is the
        // long-standing behaviour preserved across the UD-301 refactor.
        val useShared =
            includeShared &&
                Capability.DeltaShared in provider.capabilities()

        // UD-352: forward per-page progress to reporter.onScanProgress("remote", N).
        // The provider's delta() invokes this callback on each accumulated page
        // (where supported); the engine just fans it out to the reporter so the
        // heartbeat fires inside the gather loop instead of only at start/end.
        // Snapshot/all-at-once providers (HiDrive, rclone) leave this unused —
        // the engine still emits a final tick once gather returns. Note that
        // [provider.deltaWithShared] does not yet accept the callback (its
        // pagination path is OneDrive-only and ALREADY emits progress via the
        // outer loop in this method); only the plain [provider.delta] path
        // forwards it.
        val onPageProgress: (Int) -> Unit = { itemsSoFar ->
            reporter.onScanProgress("remote", itemsSoFar)
        }

        // UD-360: providers signal partial gathers via DeltaPage.complete=false.
        // We track that across pages and skip the absence-implies-deletion sweep
        // (detectMissingAfterFullSync) when any page was incomplete — otherwise
        // a transient subtree error on the provider side would synthesize bogus
        // DeleteLocal actions for every file under that subtree.
        var allComplete = true

        // Resumable-scan lifecycle: a non-null activeScanId means this gather
        // pass either resumes a prior daemon's interrupted scan or has just
        // started a fresh one. The scanContext threads the engine's staging
        // hooks into provider.delta(); on a successful return the staging
        // slice is cleared. A throw mid-scan leaves both the slice and the
        // checkpoint intact so the next launch can pick up from there.
        val activeScan = db.getActiveScan(staleThreshold = java.time.Duration.ofHours(SCAN_CHECKPOINT_STALE_HOURS))
        val resumedItems: List<CloudItem> =
            if (activeScan != null) db.loadStagedItems(activeScan.scanId) else emptyList()
        val scanId =
            activeScan?.scanId
                ?: db.beginScan(initialMarker = null)
        if (activeScan != null) {
            log.info(
                "Resuming Internxt-style scan id={} marker={} ({} previously-staged items)",
                scanId,
                activeScan.marker,
                resumedItems.size,
            )
        }
        val scanContext =
            org.krost.unidrive.ScanContext(
                resumeMarker = activeScan?.marker,
                resumedItems = resumedItems,
                persistPage = { items, marker -> db.persistScanPage(scanId, items, marker) },
            )

        suspend fun nextPage(c: String?): DeltaPage {
            val page =
                if (useShared) {
                    when (val r = provider.deltaWithShared(c)) {
                        is CapabilityResult.Success -> r.value
                        is CapabilityResult.Unsupported -> provider.delta(c, onPageProgress, scanContext)
                    }
                } else {
                    provider.delta(c, onPageProgress, scanContext)
                }
            // UD-751: single canonical "Delta: N items, hasMore=X" line, lifted out
            // of the five providers that used to emit the same data per-page.
            log.debug("Delta: {} items, hasMore={}", page.items.size, page.hasMore)
            if (!page.complete) {
                allComplete = false
            }
            return page
        }

        // Persist the running completeness flag alongside the cursor.
        // Promotion now happens unconditionally (see promotePendingCursor):
        // pinning the cursor at its prior value on an incomplete sweep
        // forced a full re-scan every launch on a hot account whenever any
        // subtree returned 500/503. The flag is still recorded so the doctor
        // surface and warnings can tell the user the last scan was incomplete
        // and recommend `--reset` if the skipped subtree matters.
        //
        // The monotonicity floor (cursor never regresses below the prior
        // value) lives inside each provider's delta() — only providers know
        // whether their cursor is timestamp-comparable (Internxt) or opaque
        // (OneDrive's @odata.nextLink URLs), and only providers can do the
        // comparison correctly.
        fun persistPendingCursor(cursor: String) {
            db.setSyncState("pending_cursor", cursor)
            db.setSyncState("pending_cursor_complete", if (allComplete) "true" else "false")
        }

        // #110: a persisted delta cursor can age out (OneDrive Graph 410 Gone).
        // Catch DeltaCursorExpiredException specifically — NOT the generic
        // ProviderException — so non-410 failures keep their existing behaviour.
        // On 410: clear the cursor, discard partial results, and re-run a full
        // enumeration from cursor=null so genuine deletes in the stale window
        // are reaped and unchanged paths are spared. If the recovery pass itself
        // throws ProviderException the exception propagates to the caller which
        // treats it as an enumeration failure (deletes suppressed) — no infinite
        // loop, no recursive recovery.
        try {
            var page = nextPage(cursor)
            for (item in page.items) {
                val resolved = resolveItemPath(item) ?: continue
                changes[resolved.path] = resolved
            }
            persistPendingCursor(page.cursor)
            // UD-742: heartbeat after each remote page. Internxt paginates 50/page,
            // so a 113k-item drive emits ~2260 update events — cheap, and the
            // reporter is responsible for throttling display (CliProgressReporter
            // overwrites the same line via printInline).
            reporter.onScanProgress("remote", changes.size)

            while (page.hasMore) {
                page = nextPage(page.cursor)
                for (item in page.items) {
                    val resolved = resolveItemPath(item) ?: continue
                    changes[resolved.path] = resolved
                }
                persistPendingCursor(page.cursor)
                reporter.onScanProgress("remote", changes.size)
            }
        } catch (e: DeltaCursorExpiredException) {
            // The resumed cursor aged out / the drive re-keyed (Graph 410). Clear the
            // persisted cursor and re-enumerate the FULL inventory from a null cursor
            // (incremental = false), so genuine deletes during the stale window are
            // reaped and unchanged paths are not.
            log.warn(
                "#110: delta cursor expired ({}); clearing it and re-enumerating the full inventory.",
                e.message,
            )
            db.setSyncState("delta_cursor", "")
            // Abandon the stale scan context and start a fresh one for the full re-enum.
            db.completeScan(scanId)
            val recoveryScanId = db.beginScan(initialMarker = null)
            val recoveryScanContext =
                org.krost.unidrive.ScanContext(
                    resumeMarker = null,
                    resumedItems = emptyList(),
                    persistPage = { items, marker -> db.persistScanPage(recoveryScanId, items, marker) },
                )
            suspend fun nextPageRecovery(c: String?): DeltaPage {
                val p =
                    if (useShared) {
                        when (val r = provider.deltaWithShared(c)) {
                            is CapabilityResult.Success -> r.value
                            is CapabilityResult.Unsupported -> provider.delta(c, onPageProgress, recoveryScanContext)
                        }
                    } else {
                        provider.delta(c, onPageProgress, recoveryScanContext)
                    }
                log.debug("Delta (recovery): {} items, hasMore={}", p.items.size, p.hasMore)
                if (!p.complete) allComplete = false
                return p
            }
            // Reset all mutable accumulation state for the recovery pass.
            changes = mutableMapOf()
            allComplete = true
            isFullSync = true
            // Recovery: full enumeration from null cursor. Any ProviderException here
            // propagates to the caller (treated as an enumeration failure, deletes
            // suppressed) — never recover recursively.
            var rPage = nextPageRecovery(null)
            for (item in rPage.items) {
                val resolved = resolveItemPath(item) ?: continue
                changes[resolved.path] = resolved
            }
            persistPendingCursor(rPage.cursor)
            reporter.onScanProgress("remote", changes.size)
            while (rPage.hasMore) {
                rPage = nextPageRecovery(rPage.cursor)
                for (item in rPage.items) {
                    val resolved = resolveItemPath(item) ?: continue
                    changes[resolved.path] = resolved
                }
                persistPendingCursor(rPage.cursor)
                reporter.onScanProgress("remote", changes.size)
            }
            if (allComplete) {
                db.completeScan(recoveryScanId)
            }
        }

        // Staged inventory has been consumed by this gather pass — the live
        // sync_entries write happens downstream via updateRemoteEntries — so
        // the per-page durability slice can be cleared. A daemon crash now,
        // or on any subsequent step, will retry from the freshly-persisted
        // delta_cursor (or pending_cursor if no transfers ran), not from the
        // staged offsets.
        //
        // Cross-session resume: when the gather returned `complete=false`
        // (e.g. Internxt's 503 subtree skip or a partial ancestor-uuid drop),
        // the marker + staged rows are deliberately preserved so the NEXT
        // daemon launch can pick up at the same offset rather than restarting
        // at 0. Pairs with the best-effort `delta_cursor` advance in
        // `promotePendingCursor` — the cursor moves forward by `max(updatedAt)`
        // over completed pages while the offset doesn't regress, so a
        // throttle-cliff'd account makes monotonic progress across restarts
        // even if no individual run reaches `complete=true`. The stale-
        // threshold check inside `getActiveScan` is the safety net against
        // an indefinitely-preserved marker drifting past Internxt's
        // change-detection window.
        if (allComplete) {
            db.completeScan(scanId)
        }

        if (isFullSync && allComplete) {
            detectMissingAfterFullSync(changes)
        } else if (isFullSync) {
            val msg =
                "UD-360: at least one delta page returned complete=false; " +
                    "skipping detectMissingAfterFullSync to avoid spurious del-local actions. " +
                    "The missing inventory will be picked up on the next sync run."
            log.warn(msg)
            reporter.onWarning(msg)
        }
        // Record the gather's ACTUAL full-enumeration verdict (a 410 recovery above
        // can upgrade an incremental pass to full) so the sync-path remote-shrink
        // guard judges the real mode, not just the pre-gather cursor state.
        db.setSyncState("last_gather_full", isFullSync.toString())
        changes
    }

    private suspend fun dispatchStreamingDownload(
        action: SyncAction.DownloadContent,
        downloaded: AtomicInteger,
        transferFailures: AtomicInteger,
        authFailure: java.util.concurrent.atomic.AtomicReference<AuthenticationException?>,
        executedPaths: MutableSet<String>,
        gatherScope: kotlinx.coroutines.CoroutineScope,
        transferSemaphore: kotlinx.coroutines.sync.Semaphore,
    ) {
        withContext(Priority.Foreground) {
            transferSemaphore.withPermit {
                try {
                    applyDownload(action)
                    downloaded.incrementAndGet()
                    executedPaths.add(action.path)
                } catch (e: AuthenticationException) {
                    log.error(
                        "Authentication failed during streaming download of {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    restoreToPlaceholder(action.path, action.remoteItem)
                    transferFailures.incrementAndGet()
                    executedPaths.add(action.path)
                    authFailure.compareAndSet(null, e)
                    gatherScope.cancel()
                } catch (e: CancellationException) {
                    restoreToPlaceholder(action.path, action.remoteItem)
                    throw e
                } catch (e: PermanentDownloadFailureException) {
                    handlePermanentDownloadFailure(action, e)
                    transferFailures.incrementAndGet()
                    executedPaths.add(action.path)
                } catch (e: Exception) {
                    log.warn(
                        "Streaming download failed for {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    reporter.onWarning("Failed: ${action.path} - ${e.message}")
                    logFailure(action, e)
                    restoreToPlaceholder(action.path, action.remoteItem)
                    transferFailures.incrementAndGet()
                    // UD-225 recovery picks failed downloads up on the next
                    // pass; mark executed so Pass 2 of *this* run doesn't
                    // double-dispatch.
                    executedPaths.add(action.path)
                }
            }
        }
    }

    private suspend fun dispatchStreamingUpload(
        action: SyncAction.Upload,
        uploaded: AtomicInteger,
        transferFailures: AtomicInteger,
        authFailure: java.util.concurrent.atomic.AtomicReference<AuthenticationException?>,
        executedPaths: MutableSet<String>,
        gatherScope: kotlinx.coroutines.CoroutineScope,
        transferSemaphore: kotlinx.coroutines.sync.Semaphore,
    ) {
        withContext(Priority.Foreground) {
            transferSemaphore.withPermit {
                try {
                    applyUpload(action)
                    uploaded.incrementAndGet()
                    executedPaths.add(action.path)
                } catch (e: AuthenticationException) {
                    log.error(
                        "Authentication failed during streaming upload of {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    transferFailures.incrementAndGet()
                    executedPaths.add(action.path)
                    authFailure.compareAndSet(null, e)
                    gatherScope.cancel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "Streaming upload failed for {}: {}: {}",
                        action.path,
                        e.javaClass.simpleName,
                        e.message,
                        e,
                    )
                    reporter.onWarning("Failed: ${action.path} - ${e.message}")
                    logFailure(action, e)
                    transferFailures.incrementAndGet()
                    // UD-901 recovery picks failed uploads up on the next
                    // pass; mark executed so Pass 2 of *this* run doesn't
                    // double-dispatch.
                    executedPaths.add(action.path)
                }
            }
        }
    }

    private suspend fun gatherStreamingChanges(
        localChanges: Map<String, ChangeState>,
        downloaded: AtomicInteger,
        uploaded: AtomicInteger,
        transferFailures: AtomicInteger,
        authFailure: java.util.concurrent.atomic.AtomicReference<AuthenticationException?>,
        executedPaths: MutableSet<String>,
        transferSemaphore: kotlinx.coroutines.sync.Semaphore,
    ): Pair<Map<String, CloudItem>, List<SyncAction>> = withContext(Priority.Background) {
        val storedCursor = db.getSyncState("delta_cursor")
        val cursor = storedCursor?.ifEmpty { null }
        val isFullSync = cursor == null
        val changes = mutableMapOf<String, CloudItem>()
        val buffer = StreamingReconcileBuffer()
        val safeAccumulator = mutableListOf<SyncAction>()
        // resolveSlice processes the full localChanges map on every delta page,
        // so a MODIFIED-local file absent from a given page's remote delta
        // re-emits its Upload (and likewise a DownloadContent can recur across
        // pages). Without a precheck the executor would launch one dispatch per
        // recurrence — K concurrent applyUpload/applyDownload of the same path
        // across K pages. executedPaths only fills in post-completion, so it
        // can't dedup the in-flight sends. Claim the path here, before the send,
        // so two pages can never both forward the same transfer. Lives for the
        // gather only; Pass 2 dedups separately via executedPaths.
        val sentToExecutor =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()

        // UD-223 fast-bootstrap mirror: bootstrap adopts the cursor with
        // zero enumeration, so there's nothing to stream — fall through
        // to the same map-only path as the non-streaming gather.
        if (fastBootstrap && isFullSync && Capability.FastBootstrap in provider.capabilities()) {
            when (val result = provider.deltaFromLatest()) {
                is CapabilityResult.Success -> {
                    val page = result.value
                    for (item in page.items) {
                        val resolved = resolveItemPath(item) ?: continue
                        changes[resolved.path] = resolved
                    }
                    db.setSyncState("delta_cursor", page.cursor)
                    db.setSyncState("last_full_scan", Instant.now().toString())
                    // See the non-streaming `gatherRemoteChanges` fast-bootstrap path
                    // for rationale. Stale `scan_in_progress_*` offsets index into a
                    // different result set than the new cursor=now delta returns.
                    db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)?.let { staleScanId ->
                        log.info(
                            "UD-223 fast-bootstrap: invalidating stale scan checkpoint scan={} marker={}",
                            staleScanId,
                            db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER),
                        )
                        db.completeScan(staleScanId)
                    }
                    val stamp = Instant.now().toString()
                    val msg =
                        "UD-223 fast-bootstrap: adopted remote cursor as of $stamp. " +
                            "Items that already exist on the remote will stay invisible until they next mutate. " +
                            "Upload-direction sync is unaffected."
                    log.warn(msg)
                    reporter.onWarning(msg)
                    return@withContext changes to emptyList()
                }
                is CapabilityResult.Unsupported -> {
                    log.warn(
                        "UD-223 fast-bootstrap requested but provider '{}' does not support it ({}). " +
                            "Falling back to streaming first-sync enumeration.",
                        providerId,
                        result.reason,
                    )
                }
            }
        }

        val useShared =
            includeShared &&
                Capability.DeltaShared in provider.capabilities()

        val onPageProgress: (Int) -> Unit = { itemsSoFar ->
            reporter.onScanProgress("remote", itemsSoFar)
        }

        var allComplete = true
        val activeScan = db.getActiveScan(staleThreshold = java.time.Duration.ofHours(SCAN_CHECKPOINT_STALE_HOURS))
        val resumedItems: List<CloudItem> =
            if (activeScan != null) db.loadStagedItems(activeScan.scanId) else emptyList()
        val scanId =
            activeScan?.scanId
                ?: db.beginScan(initialMarker = null)
        if (activeScan != null) {
            log.info(
                "Resuming streaming-reconciliation scan id={} marker={} ({} previously-staged items)",
                scanId,
                activeScan.marker,
                resumedItems.size,
            )
        }
        val scanContext =
            org.krost.unidrive.ScanContext(
                resumeMarker = activeScan?.marker,
                resumedItems = resumedItems,
                persistPage = { items, marker -> db.persistScanPage(scanId, items, marker) },
            )

        suspend fun nextPage(c: String?): DeltaPage {
            val page =
                if (useShared) {
                    when (val r = provider.deltaWithShared(c)) {
                        is CapabilityResult.Success -> r.value
                        is CapabilityResult.Unsupported -> provider.delta(c, onPageProgress, scanContext)
                    }
                } else {
                    provider.delta(c, onPageProgress, scanContext)
                }
            log.debug("Delta (streaming): {} items, hasMore={}", page.items.size, page.hasMore)
            if (!page.complete) {
                allComplete = false
            }
            return page
        }

        fun persistPendingCursor(cursor: String) {
            db.setSyncState("pending_cursor", cursor)
            db.setSyncState("pending_cursor_complete", if (allComplete) "true" else "false")
        }

        // #115 streaming fix (page-order-independent): obtain the complete set of
        // remote top-level folder names with ONE cheap listing of the root's direct
        // children BEFORE the streaming loop starts.  XDG aliasing only concerns
        // top-level folders, so this single call is sufficient and does not defeat
        // streaming (we are NOT fetching the full tree — just root/children).
        //
        // This replaces the previous 1-page-lookahead accumulator
        // (accumulatedTopLevelNames), which failed when the canonical folder
        // (e.g. /Pictures) arrived more than one page after the slice carrying the
        // aliasable children (e.g. /Bilder/...) — remote delta/enumeration order
        // is NOT guaranteed to be parent-first.  With a stable pre-fetched set,
        // every resolveSlice() call receives the same complete set regardless of
        // which page the canonical folder's delta entry falls on.
        val stableRemoteTopLevelNames: Set<String> =
            try {
                provider
                    .listChildren("/")
                    .filter { it.isFolder && !it.deleted }
                    .mapTo(mutableSetOf()) { it.name }
            } catch (e: Exception) {
                // Non-fatal: if the preliminary listing fails (e.g. network glitch,
                // provider doesn't support it), fall back to an empty set so
                // resolveSlice() derives alias context from each page's own items
                // (pre-fix behaviour — still better than crashing the sync).
                log.warn(
                    "#115: preliminary root-children listing failed ({}); " +
                        "XDG aliasing may not fire for streaming pages whose canonical " +
                        "folder entry falls on a later page.",
                    e.message,
                )
                emptySet()
            }

        // Per-page reconciliation with 1-page rename-coalescing lookahead
        // (spec §3) on a bounded backpressure channel (spec §4).
        //
        // Producer coroutine: drives nextPage() in a loop, runs the
        // lookahead merge, and sends ready-to-reconcile page slices into
        // the channel. When the consumer falls behind, the send() suspends
        // — which suspends nextPage() — which back-pressures the
        // provider's delta() and its internal speculativeFetchPages
        // through coroutineScope semantics.
        //
        // Consumer coroutine: receives page slices, runs resolveSlice
        // against the full local map, classifies through
        // StreamingReconcileBuffer, accumulates safe-now actions, and
        // flips the staged-row state-machine on scan_staging.
        //
        // Channel capacity (STREAMING_RECONCILE_CHANNEL_CAPACITY = 4)
        // matches the per-provider transfer concurrency floor per spec
        // §4 decision 2. Backpressure pauses the scan instead of buffering
        // unboundedly: peak memory is bounded by 4 × page-size irrespective
        // of total drive size.
        data class HeldItem(val resolved: CloudItem, val originalId: String)
        data class PageSlice(
            val slice: Map<String, CloudItem>,
            val ids: List<String>,
            // The complete set of remote top-level folder names, pre-fetched
            // before the streaming loop (page-order-independent).  Passed to
            // resolveSlice() so XDG-alias context is built from the FULL stable
            // set regardless of which delta page the canonical folder entry
            // (e.g. /Pictures) falls on.
            val stableRemoteTopLevelNames: Set<String>,
        )

        val pageChannel =
            kotlinx.coroutines.channels
                .Channel<PageSlice>(capacity = STREAMING_RECONCILE_CHANNEL_CAPACITY)

        // Per-page streaming dispatch: as each page reconciles, its safe-now
        // DownloadContent and Upload actions are forwarded to an executor
        // coroutine that dispatches them concurrently with the still-running
        // gather. Time-to-first-byte goes from "full enum then transfers"
        // to "first transfer fires ~30 s after start, the rest stream in
        // as pages reconcile." The executor uses the same applyDownload /
        // applyUpload paths as Pass 2 and bumps the shared counters, so
        // the final onSyncComplete totals reflect the streamed transfers.
        // Pass 2 checks `executedPaths` to skip re-dispatch on the same
        // path. Capacity matches the page-channel: bounded memory under
        // backpressure.
        val executorChannel =
            kotlinx.coroutines.channels
                .Channel<SyncAction>(capacity = STREAMING_RECONCILE_CHANNEL_CAPACITY)

        coroutineScope {
            // Reference to this outer scope. The executor launches dispatch
            // jobs into it (rather than into a child scope) so an auth-
            // failure cancel here propagates to consumer + producer too,
            // mirroring Pass 2's `this@coroutineScope.cancel()` shape.
            val gatherScope = this

            // Consumer: reconcile slices as they arrive. Forward safe-now
            // transfer actions to the executor; the rest land in
            // safeAccumulator for the engine's final Pass 1 / Pass 2 sweep.
            val consumerJob =
                launch {
                    for (pageSlice in pageChannel) {
                        if (pageSlice.slice.isEmpty()) continue
                        val pageActions =
                            reconciler.resolveSlice(
                                pageSlice.slice,
                                localChanges,
                                syncPath,
                                pageSlice.stableRemoteTopLevelNames,
                                downloadOnly = syncDirection == SyncDirection.DOWNLOAD,
                            )
                        val safeNow = buffer.classify(pageActions)
                        safeAccumulator.addAll(safeNow)
                        for (action in safeNow) {
                            if (action is SyncAction.DownloadContent || action is SyncAction.Upload) {
                                // #200(b): a NEW-local upload must NOT dispatch mid-gather —
                                // enumeration completeness isn't known until scan-end. It stays in
                                // safeAccumulator, so finalizeStreaming's gate either keeps it
                                // (complete → uploaded in Pass 2) or defers it (incomplete),
                                // never duplicating an un-enumerated remote file. Downloads and
                                // MODIFIED/replace uploads still stream concurrently.
                                if (action is SyncAction.Upload && localChanges[action.path] == ChangeState.NEW) continue
                                // Claim the path atomically before forwarding so a transfer
                                // re-emitted on a later page (MODIFIED upload / recurring
                                // download) is sent to the executor exactly once. add()
                                // returns false when already claimed; concurrent set so two
                                // page-slices can't both win the claim.
                                if (!sentToExecutor.add(action.path)) continue
                                executorChannel.send(action)
                            }
                        }
                        if (pageSlice.ids.isNotEmpty()) {
                            db.markStagedReconciled(scanId, pageSlice.ids)
                        }
                    }
                    // Producer is done and we drained the page channel; the
                    // executor can stop accepting new work and finish its
                    // in-flight items.
                    executorChannel.close()
                }

            // Executor: dispatch safe-now transfers concurrently with the
            // gather. Mirrors the Pass 2 dispatch shape (foreground priority,
            // shared semaphore, restore-to-placeholder on download failure)
            // so a streamed transfer is indistinguishable from a Pass 2
            // dispatch in terms of side effects. Launches into `gatherScope`
            // (not into a child scope) so an auth failure cancels consumer
            // + producer too, matching Pass 2's behaviour.
            val executorJob =
                launch {
                    for (action in executorChannel) {
                        when (action) {
                            is SyncAction.DownloadContent ->
                                gatherScope.launch {
                                    dispatchStreamingDownload(
                                        action = action,
                                        downloaded = downloaded,
                                        transferFailures = transferFailures,
                                        authFailure = authFailure,
                                        executedPaths = executedPaths,
                                        gatherScope = gatherScope,
                                        transferSemaphore = transferSemaphore,
                                    )
                                }
                            is SyncAction.Upload ->
                                gatherScope.launch {
                                    dispatchStreamingUpload(
                                        action = action,
                                        uploaded = uploaded,
                                        transferFailures = transferFailures,
                                        authFailure = authFailure,
                                        executedPaths = executedPaths,
                                        gatherScope = gatherScope,
                                        transferSemaphore = transferSemaphore,
                                    )
                                }
                            else -> {
                                // Defensive: classify() only surfaces
                                // Download/Upload to us. Ignore the rest so
                                // a future SyncAction variant doesn't crash.
                            }
                        }
                    }
                }

            // Producer: page-fetch loop with 1-page rename-coalescing lookahead.
            launch {
                var heldItems: MutableMap<String, HeldItem>? = null

                suspend fun flushHeld() {
                    val held = heldItems ?: return
                    heldItems = null
                    if (held.isEmpty()) return
                    val slice = LinkedHashMap<String, CloudItem>()
                    val ids = mutableListOf<String>()
                    for ((_, item) in held) {
                        slice[item.resolved.path] = item.resolved
                        ids.add(item.originalId)
                        changes[item.resolved.path] = item.resolved
                    }
                    reporter.onScanProgress("remote", changes.size)
                    // Pass the pre-fetched complete top-level set (page-order-independent).
                    pageChannel.send(PageSlice(slice, ids, stableRemoteTopLevelNames))
                }

                suspend fun ingestPage(page: DeltaPage) {
                    persistPendingCursor(page.cursor)

                    // Resolve and stage the new page's items keyed by remote id.
                    // resolveItemPath may collapse to null for unreachable deletes;
                    // those entries are dropped from the lookahead (the next sync
                    // will surface them once their context is in the local DB).
                    val newItems = LinkedHashMap<String, HeldItem>()
                    for (item in page.items) {
                        val resolved = resolveItemPath(item) ?: continue
                        newItems[resolved.id] = HeldItem(resolved, resolved.id)
                    }

                    // Merge step: any id in newItems that also lives in the held
                    // page gets its held entry's path overridden with the new
                    // page's path (later page wins).
                    val held = heldItems
                    if (held != null) {
                        for ((id, item) in newItems) {
                            val priorHeld = held[id] ?: continue
                            held[id] =
                                HeldItem(
                                    resolved =
                                        priorHeld.resolved.copy(
                                            path = item.resolved.path,
                                            name = item.resolved.name,
                                        ),
                                    originalId = priorHeld.originalId,
                                )
                        }
                    }

                    // Release the held page for reconciliation (now with any
                    // merged paths applied) and replace it with the new page.
                    flushHeld()
                    heldItems = newItems
                }

                try {
                    var page = nextPage(cursor)
                    ingestPage(page)
                    while (page.hasMore) {
                        page = nextPage(page.cursor)
                        ingestPage(page)
                    }
                    // Final flush of whatever's still held after the last page.
                    flushHeld()
                } finally {
                    pageChannel.close()
                }
            }

            consumerJob.join()
            // executorJob.join() is implicit at coroutineScope exit, but be
            // explicit: scope-exit will wait for it anyway. The consumer
            // closed executorChannel above so the executor's `for` loop
            // exits cleanly once it drains in-flight items.
            executorJob.join()
        }
        // AuthenticationException latched by the executor — surface to the
        // caller exactly like Pass 2 does so token refresh / re-auth flows
        // trigger instead of a silent "0 downloaded, many failed" result.
        // Pass 2 does the same rethrow after its own scope, but doing it
        // here too means a streaming-only auth failure (no Pass 2 actions
        // left to dispatch) still propagates.
        authFailure.get()?.let { throw it }

        if (allComplete) {
            db.completeScan(scanId)
        }

        if (isFullSync && allComplete) {
            detectMissingAfterFullSync(changes)
        } else if (isFullSync) {
            val msg =
                "UD-360: at least one delta page returned complete=false; " +
                    "skipping detectMissingAfterFullSync to avoid spurious del-local actions. " +
                    "The missing inventory will be picked up on the next sync run."
            log.warn(msg)
            reporter.onWarning(msg)
        }
        // Record the gather's full-enumeration verdict (see gatherRemoteChanges).
        db.setSyncState("last_gather_full", isFullSync.toString())

        val deferred = buffer.drainDeferred()
        changes to (safeAccumulator + deferred)
    }

    private fun promotePendingCursor() {
        val pendingCursor = db.getSyncState("pending_cursor") ?: return
        db.setSyncState("delta_cursor", pendingCursor)
        val complete = db.getSyncState("pending_cursor_complete") ?: "true"
        if (complete == "true") {
            db.setSyncState("last_full_scan", Instant.now().toString())
        }
    }

    private fun resolveItemPath(item: CloudItem): CloudItem? {
        // #171: canonicalize the remote path to NFC so it matches the NFC local key
        // in the reconciler (copy only when the form actually changes).
        if (item.path != "/" && item.path.isNotEmpty()) {
            val n = PathNormalizer.nfc(item.path)
            return if (n == item.path) item else item.copy(path = n)
        }
        // #183: access-revoked tombstone — Graph `@microsoft.graph.removed` state="removed",
        // no parentReference, so path resolved to "/". The item still physically exists on the
        // remote; the local file MUST be kept. Retire the DB row via TRASHED (removed from the
        // alive view, no longer an unreapable orphan) and return null so the item doesn't flow
        // into the normal reconciler path.
        if (!item.deleted && item.accessRevoked) {
            val retired = db.setStatusTrashed(item.id)
            if (retired) {
                log.info(
                    "#183: access-revoked tombstone id={}: DB row retired (TRASHED), local file preserved",
                    item.id,
                )
            } else {
                log.debug(
                    "#183: access-revoked tombstone id={}: no alive row found (already retired or never tracked)",
                    item.id,
                )
            }
            return null
        }
        if (!item.deleted) return null // non-deleted root item without a known path, skip
        val entry = db.getEntryByRemoteId(item.id) ?: return null
        log.debug("Resolved deleted item id={} to path={}", item.id, entry.path)
        return item.copy(path = entry.path, name = entry.path.substringAfterLast("/"))
    }

    private fun detectMissingAfterFullSync(remoteChanges: MutableMap<String, CloudItem>) {
        val seenRemoteIds = remoteChanges.values.mapTo(mutableSetOf()) { it.id }
        pruneRecentlyUploaded()

        for (entry in db.getAllEntries()) {
            if (entry.remoteId == null) continue
            if (entry.remoteId in seenRemoteIds) continue
            // #115: the delta and the recently-uploaded marks live in the REMOTE
            // namespace, so test against the row's effective remote path
            // (`remotePath ?: path`). For a non-aliased row this is just
            // entry.path — byte-identical to pre-#115 behaviour.
            val effectiveRemote = entry.remotePath ?: entry.path
            if (effectiveRemote in remoteChanges) continue

            val uploadedAt = recentlyUploaded[effectiveRemote]
            if (uploadedAt != null) {
                log.debug(
                    "Full sync: DB entry {} (remoteId={}) not in delta but uploaded {}s ago; " +
                        "within the recent-upload grace window, deferring the deletion verdict",
                    entry.path,
                    entry.remoteId,
                    java.time.Duration.between(uploadedAt, Instant.now()).seconds,
                )
                continue
            }

            log.debug("Full sync: DB entry {} (remoteId={}) not in delta, marking deleted", entry.path, entry.remoteId)
            // Key the synthesized tombstone at the effective remote path so it
            // flows through the reconciler's canonical→real-local reverse map
            // alongside genuine deltas.
            remoteChanges[effectiveRemote] =
                CloudItem(
                    id = entry.remoteId,
                    name = effectiveRemote.substringAfterLast("/"),
                    path = effectiveRemote,
                    size = 0,
                    isFolder = entry.isFolder,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                    deleted = true,
                )
        }
    }

    private suspend fun applyCreatePlaceholder(action: SyncAction.CreatePlaceholder) {
        // UD-222: under the new routing (Reconciler), CreatePlaceholder is only emitted for
        //   - folders (mkdir)
        //   - "both new" adopt (local file already matches remote size)
        //   - conflict-resolution internal invocations that want a downloaded file
        // Non-folder remote-new/remote-modified goes through DownloadContent in Pass 2. The
        // `shouldHydrate` field is vestigial — download now triggers whenever the local side
        // lacks real content, so that sparse leftovers from an interrupted sync get re-hydrated
        // instead of silently adopted as NUL bytes.
        val item = action.remoteItem
        val localPath = placeholder.resolveLocal(action.path)
        val sizeMatch = !item.isFolder && Files.isRegularFile(localPath) && Files.size(localPath) == item.size
        val hasRealContent = sizeMatch && !placeholder.isSparse(localPath, item.size)
        val shouldDownload = !item.isFolder && item.size > 0 && !hasRealContent

        withEchoSuppression(action.path) {
            if (item.isFolder) {
                placeholder.createFolder(action.path, item.modified)
            } else if (!sizeMatch) {
                placeholder.createPlaceholder(action.path, item.size, item.modified)
            }

            if (shouldDownload) {
                // UD-225b: prefer id-based dispatch — path-based download triggers
                // a per-segment folder traversal in resolveFolder which can fail
                // on transient 503s or sanitization edge cases (UD-317).
                // #115: the REMOTE source is item.path (canonical); the local
                // destination is localPath (resolved from the real-local action.path).
                downloadByIdOrPath(item, item.path, localPath)
                placeholder.restoreMtime(action.path, item.modified)
            }
        }

        val isHydrated = hasRealContent || item.size == 0L || shouldDownload
        db.upsertEntry(entryFromCloudItem(item, action.path, isHydrated))
    }

    private suspend fun applyUpdatePlaceholder(action: SyncAction.UpdatePlaceholder) {
        val item = action.remoteItem
        if (versionManager != null && action.wasHydrated && !item.isFolder) {
            val localPath = placeholder.resolveLocal(action.path)
            if (Files.isRegularFile(localPath) && Files.size(localPath) > 0) {
                versionManager.snapshot(action.path)
                versionManager.pruneByCount(action.path, maxVersions)
            }
        }
        withEchoSuppression(action.path) {
            if (action.wasHydrated && !item.isFolder) {
                try {
                    // UD-225b: id-based dispatch (see applyCreatePlaceholder).
                    // #115: remote source = item.path (canonical); dest = real-local.
                    downloadByIdOrPath(item, item.path, placeholder.resolveLocal(action.path))
                    placeholder.restoreMtime(action.path, item.modified)
                } catch (e: Exception) {
                    restoreToPlaceholder(action.path, item)
                    throw e
                }
            } else if (!item.isFolder) {
                placeholder.updatePlaceholderMetadata(action.path, item.size, item.modified)
            }
        }

        db.upsertEntry(entryFromCloudItem(item, action.path, action.wasHydrated))
    }

    internal suspend fun downloadByIdOrPath(
        item: CloudItem,
        remotePath: String,
        destination: java.nio.file.Path,
    ): Long =
        if (item.id.isNotEmpty()) {
            provider.downloadById(item.id, remotePath, destination)
        } else {
            provider.download(remotePath, destination)
        }

    private suspend fun applyDownload(action: SyncAction.DownloadContent) {
        log.debug("Download: {} ({} bytes)", action.path, action.remoteItem.size)
        val localPath = placeholder.resolveLocal(action.path)
        if (versionManager != null && Files.isRegularFile(localPath) && Files.size(localPath) > 0) {
            versionManager.snapshot(action.path)
            versionManager.pruneByCount(action.path, maxVersions)
        }
        // UD-113: capture pre-action hash for the audit oldHash field.
        val prevHash = db.getEntry(action.path)?.remoteHash
        try {
            withEchoSuppression(action.path) {
                // UD-225b: id-based dispatch — see applyCreatePlaceholder for rationale.
                // #115: remote source = remoteItem.path (canonical); local
                // destination = localPath (resolved from real-local action.path).
                downloadByIdOrPath(action.remoteItem, action.remoteItem.path, localPath)
                placeholder.restoreMtime(action.path, action.remoteItem.modified)
            }

            if (verifyIntegrity) {
                val verified = HashVerifier.verify(localPath, action.remoteItem.hash, algorithm = provider.hashAlgorithm())
                if (!verified) {
                    reporter.onWarning("Integrity check failed: ${action.path}")
                }
            }

            db.upsertEntry(entryFromCloudItem(action.remoteItem, action.path, isHydrated = true))
            auditLog?.emit(
                action = "Download",
                path = action.path,
                size = action.remoteItem.size,
                oldHash = prevHash,
                newHash = action.remoteItem.hash,
                result = "success",
            )
        } catch (e: Exception) {
            auditLog?.emit(
                action = "Download",
                path = action.path,
                size = action.remoteItem.size,
                oldHash = prevHash,
                result = "failed:${e.javaClass.simpleName}: ${e.message}",
            )
            throw e
        }
    }

    private suspend fun applyUpload(action: SyncAction.Upload) {
        // #115: LOCAL read uses action.path (the real local path); the REMOTE
        // upload target is action.remoteTarget ?: action.path. For a non-aliased
        // upload remoteTarget is null, so remotePath == action.path and this is
        // byte-identical to pre-#115 behaviour.
        val localPath = placeholder.resolveLocal(action.path)
        val remotePath = action.remoteTarget ?: action.path
        val sizeForLog = if (Files.isRegularFile(localPath)) Files.size(localPath) else -1L
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Upload: {} -> {} ({} bytes)", action.path, remotePath, sizeForLog)
        // UD-113: capture pre-action remote hash so the audit entry's oldHash reflects
        // the version we replaced (or null for a fresh upload).
        val prevHash = db.getEntry(action.path)?.remoteHash
        val result =
            try {
                // UD-366: action.remoteId carries the existing remote UUID for MODIFIED uploads;
                // null for NEW. Internxt routes through PUT /files/{uuid} when non-null.
                provider.upload(localPath, remotePath, existingRemoteId = action.remoteId) { transferred, total ->
                    reporter.onTransferProgress(action.path, transferred, total)
                }
            } catch (e: Exception) {
                auditLog?.emit(
                    action = "Upload",
                    path = action.path,
                    size = if (sizeForLog >= 0) sizeForLog else null,
                    oldHash = prevHash,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
        // The eventually-consistent delta feed may not list this just-written remote
        // item on the next full enumeration; mark it so the absence sweep defers its
        // deletion verdict instead of reaping + re-uploading in a loop. Marked by
        // the REMOTE path because the absence sweep reasons in the remote namespace.
        markRecentlyUploaded(remotePath)
        val mtime = Files.getLastModifiedTime(localPath).toMillis()
        val size = Files.size(localPath)
        db.upsertEntry(
            SyncEntry(
                path = action.path,
                // #115: persist the canonical remote path so the next sync's
                // delta (keyed at the canonical) matches this row by effective
                // remote path — no re-upload, no spurious MoveLocal.
                remotePath = action.remoteTarget,
                remoteId = result.id,
                remoteHash = result.hash,
                remoteSize = result.size,
                remoteModified = result.modified,
                localMtime = mtime,
                localSize = size,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        // UD-113: success path. Failure path emits inside the try/catch above.
        auditLog?.emit(
            action = "Upload",
            path = action.path,
            size = size,
            oldHash = prevHash,
            newHash = result.hash,
            result = "success",
        )
    }

    private suspend fun applyMoveRemote(action: SyncAction.MoveRemote) {
        // #115: action.fromPath / action.path are REAL-LOCAL (DB + resolveLocal
        // ops below key on them). The REMOTE move runs in the canonical
        // namespace: source = source row's `remotePath ?: fromPath`, dest =
        // action.remoteTarget ?: action.path. Non-aliased rows have remotePath
        // null and remoteTarget null → identical to pre-#115 behaviour.
        val oldEntry = db.getEntry(action.fromPath)
        val remoteFrom = oldEntry?.remotePath ?: action.fromPath
        val remoteTo = action.remoteTarget ?: action.path
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Move: {} -> {} (remote {} -> {})", action.fromPath, action.path, remoteFrom, remoteTo)
        val isFolder = oldEntry?.isFolder ?: false
        val result =
            try {
                provider.move(remoteFrom, remoteTo)
            } catch (e: Exception) {
                auditLog?.emit(
                    action = "Move",
                    path = action.path,
                    fromPath = action.fromPath,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
        val localPath = placeholder.resolveLocal(action.path)
        val mtime = if (Files.exists(localPath)) Files.getLastModifiedTime(localPath).toMillis() else 0L
        val size =
            if (isFolder) {
                0L
            } else if (Files.exists(localPath)) {
                Files.size(localPath)
            } else {
                oldEntry?.remoteSize ?: 0L
            }
        db.deleteEntry(action.fromPath)
        // UD-901d: renamePrefix() must run BEFORE the destination upsert.
        // renamePrefix's UD-901c cleanup phase deletes every row at the
        // new prefix (including `newPrefix.removeSuffix("/")` — the folder
        // root row itself) to clear out pre-existing LocalScanner UD-901
        // placeholder rows that would otherwise collide with the UPDATE.
        // If we upserted first and renamed second, the cleanup would wipe
        // the canonical destination row we just wrote, leaving the moved
        // folder with no remoteId/metadata in state.db — every subsequent
        // scan would treat the folder as untracked and try to re-create
        // it remotely. Upserting AFTER the rename keeps cleanup honest
        // and the canonical row intact.
        if (isFolder) {
            db.renamePrefix(action.fromPath, action.path)
        }
        db.upsertEntry(
            SyncEntry(
                path = action.path,
                // #115: persist the canonical remote destination so the next
                // sync's delta (canonical-keyed) matches this row by effective
                // remote path.
                remotePath = action.remoteTarget,
                remoteId = result.id,
                remoteHash = result.hash,
                remoteSize = oldEntry?.remoteSize ?: result.size,
                remoteModified = result.modified,
                localMtime = mtime,
                localSize = size,
                isFolder = isFolder,
                isPinned = false,
                isHydrated = oldEntry?.isHydrated ?: true,
                lastSynced = Instant.now(),
            ),
        )
        // UD-113: success path for the remote-side rename/move.
        auditLog?.emit(
            action = "Move",
            path = action.path,
            fromPath = action.fromPath,
            oldHash = oldEntry?.remoteHash,
            newHash = result.hash,
            result = "success",
        )
    }

    private fun applyMoveLocal(action: SyncAction.MoveLocal) {
        val oldLocal = placeholder.resolveLocal(action.fromPath)
        val newLocal = placeholder.resolveLocal(action.path)
        val oldEntry = db.getEntry(action.fromPath)
        val isFolder = action.remoteItem.isFolder

        // Suppress both old and new paths to avoid echo events from the move
        withEchoSuppression(action.fromPath) {
            withEchoSuppression(action.path) {
                Files.createDirectories(newLocal.parent)
                if (Files.exists(oldLocal)) {
                    Files.move(oldLocal, newLocal, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else if (!isFolder) {
                    placeholder.createPlaceholder(action.path, action.remoteItem.size, action.remoteItem.modified)
                } else {
                    Files.createDirectories(newLocal)
                }
            }
        }

        db.deleteEntry(action.fromPath)
        // UD-901d: renamePrefix() must run BEFORE the destination upsert —
        // see applyMoveRemote for the full rationale. Same bug class:
        // renamePrefix's UD-901c cleanup deletes the destination root row
        // it doesn't know was meant to be canonical.
        if (isFolder) {
            db.renamePrefix(action.fromPath, action.path)
        }
        db.upsertEntry(entryFromCloudItem(action.remoteItem, action.path, oldEntry?.isHydrated ?: false))
    }

    private fun applyDeleteLocal(action: SyncAction.DeleteLocal) {
        withEchoSuppression(action.path) {
            if (trashManager != null) {
                trashManager.trash(action.path)
            } else if (useTrash) {
                Trash.trash(placeholder.resolveLocal(action.path))
            } else {
                placeholder.deleteLocal(action.path)
            }
        }
        // The cloud cascaded a delete to us; flip the DB row to TRASHED rather
        // than hard-delete so recovery can answer "what was recently deleted?"
        // with one SELECT. Pre-redesign rows without a real remote_id fall
        // back to hard delete (nothing to tombstone).
        val priorEntry = db.getEntry(action.path)
        val remoteId = priorEntry?.remoteId
        if (remoteId != null) {
            db.setStatusTrashed(remoteId)
        } else {
            db.deleteEntry(action.path)
        }
    }

    private suspend fun applyDeleteRemote(action: SyncAction.DeleteRemote) {
        // UD-113: capture pre-delete row for the audit entry.
        val priorEntry = db.getEntry(action.path)
        // #115: DeleteRemote.path is real-local (DB lookup above keys on it).
        // The REMOTE delete target is the row's effective remote path
        // (`remotePath ?: path`). Non-aliased rows have remotePath null →
        // delete at action.path, byte-identical to pre-#115.
        val remotePath = priorEntry?.remotePath ?: action.path
        // UD-753: per-operation log at the engine (was repeated across provider services).
        log.debug("Delete: {} (remote {})", action.path, remotePath)
        var auditResult = "success"
        try {
            provider.delete(remotePath)
        } catch (e: ProviderException) {
            // Only a typed already-gone signal (Folder/Item not found) is a
            // safe no-op delete that may fall through and tombstone the row.
            // Every other ProviderException (transient 5xx/429, connection
            // reset, auth, throttle) must re-throw so the row is left untouched
            // and DeleteRemote re-emits on the next sync — otherwise a tombstoned
            // row never re-emits and the remote file is orphaned forever.
            if (!isAlreadyGone(e)) {
                auditLog?.emit(
                    action = "Delete",
                    path = action.path,
                    size = priorEntry?.remoteSize,
                    oldHash = priorEntry?.remoteHash,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
            log.debug("DeleteRemote skipped for ${action.path}: ${e.message}")
            auditResult = "skipped:${e.message}"
        } catch (e: Exception) {
            auditLog?.emit(
                action = "Delete",
                path = action.path,
                size = priorEntry?.remoteSize,
                oldHash = priorEntry?.remoteHash,
                result = "failed:${e.javaClass.simpleName}: ${e.message}",
            )
            throw e
        }
        // Provider-initiated cloud delete — flip to TRASHED so the row survives
        // as a queryable tombstone. Internxt's `delete` routes through the
        // recycle bin (`POST /storage/trash/add`); a recovery SELECT on TRASHED
        // rows + a batched untrash PATCH is the documented restore path.
        val remoteId = priorEntry?.remoteId
        if (remoteId != null) {
            db.setStatusTrashed(remoteId)
        } else {
            // No remote_id on file (pre-redesign synthetic, or stale row that
            // never carried one). Nothing to tombstone — fall through to
            // hard delete so the path doesn't dangle.
            db.deleteEntry(action.path)
        }
        auditLog?.emit(
            action = "Delete",
            path = action.path,
            size = priorEntry?.remoteSize,
            oldHash = priorEntry?.remoteHash,
            result = auditResult,
        )
    }

    private fun applyRemoveEntry(action: SyncAction.RemoveEntry) {
        val entry = db.getEntry(action.path)
        val remoteId = entry?.remoteId
        if (remoteId != null) {
            db.setStatusTrashed(remoteId)
        } else {
            db.deleteEntry(action.path)
        }
    }

    private suspend fun applyCreateRemoteFolder(action: SyncAction.CreateRemoteFolder) {
        // #115: REMOTE create target = remoteTarget ?: path; LOCAL mtime read
        // uses action.path. Non-aliased: remoteTarget null → identical to today.
        val remotePath = action.remoteTarget ?: action.path
        val result =
            try {
                provider.createFolder(remotePath)
            } catch (e: Exception) {
                auditLog?.emit(
                    action = "CreateRemoteFolder",
                    path = action.path,
                    result = "failed:${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
        db.upsertEntry(
            SyncEntry(
                path = action.path,
                remotePath = action.remoteTarget,
                remoteId = result.id,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = result.modified,
                localMtime = Files.getLastModifiedTime(placeholder.resolveLocal(action.path)).toMillis(),
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        // UD-113: success path.
        auditLog?.emit(
            action = "CreateRemoteFolder",
            path = action.path,
            result = "success",
        )
    }

    private suspend fun applyConflict(action: SyncAction.Conflict) {
        log.warn("Conflict on ${action.path}: local=${action.localState}, remote=${action.remoteState}")

        when (action.policy) {
            ConflictPolicy.KEEP_BOTH -> {
                applyKeepBoth(action)
                conflictLog?.record(
                    path = action.path,
                    localState = action.localState.name,
                    remoteState = action.remoteState.name,
                    policy = "KEEP_BOTH",
                    loserFile = null,
                )
            }
            ConflictPolicy.LAST_WRITER_WINS -> applyLastWriterWins(action)
        }
    }

    private suspend fun applyKeepBoth(action: SyncAction.Conflict) {
        val timestamp =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now())

        val localPath = placeholder.resolveLocal(action.path)
        val ext = action.path.substringAfterLast(".", "")
        val base = action.path.substringBeforeLast(".")
        val conflictSuffix =
            if (ext.isNotEmpty()) {
                ".conflict-remote-$timestamp.$ext"
            } else {
                ".conflict-remote-$timestamp"
            }

        if (action.remoteItem != null && !action.remoteItem.deleted) {
            val conflictPath = "$base$conflictSuffix"
            val conflictLocal = placeholder.resolveLocal(conflictPath)
            withEchoSuppression(conflictPath) {
                // UD-225b: id-based dispatch (see applyCreatePlaceholder).
                downloadByIdOrPath(action.remoteItem, action.remoteItem.path, conflictLocal)
            }
            if (action.localState == ChangeState.NEW || action.localState == ChangeState.MODIFIED) {
                if (Files.exists(localPath)) {
                    val result =
                        // UD-366: COPY-MERGE conflict — remote already has a UUID; overwrite
                        // it in place rather than POSTing a duplicate that 409s.
                        provider.upload(
                            localPath,
                            action.path,
                            existingRemoteId = action.remoteItem.id,
                        ) { transferred, total ->
                            reporter.onTransferProgress(action.path, transferred, total)
                        }
                    val mtime = Files.getLastModifiedTime(localPath).toMillis()
                    db.upsertEntry(
                        SyncEntry(
                            path = action.path,
                            remoteId = result.id,
                            remoteHash = result.hash,
                            remoteSize = result.size,
                            remoteModified = result.modified,
                            localMtime = mtime,
                            localSize = Files.size(localPath),
                            isFolder = false,
                            isPinned = false,
                            isHydrated = true,
                            lastSynced = Instant.now(),
                        ),
                    )
                }
            }
        } else if (action.localState == ChangeState.DELETED && action.remoteItem != null) {
            // UD-222: remote wins the conflict — download real bytes, not a NUL stub.
            applyCreatePlaceholder(
                SyncAction.CreatePlaceholder(action.path, action.remoteItem, shouldHydrate = !action.remoteItem.isFolder),
            )
        } else if (action.remoteState == ChangeState.DELETED && Files.exists(localPath)) {
            val result =
                provider.upload(localPath, action.path) { transferred, total ->
                    reporter.onTransferProgress(action.path, transferred, total)
                }
            val mtime = Files.getLastModifiedTime(localPath).toMillis()
            db.upsertEntry(
                SyncEntry(
                    path = action.path,
                    remoteId = result.id,
                    remoteHash = result.hash,
                    remoteSize = result.size,
                    remoteModified = result.modified,
                    localMtime = mtime,
                    localSize = Files.size(localPath),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
        }
    }

    private suspend fun applyLastWriterWins(action: SyncAction.Conflict) {
        val localPath = placeholder.resolveLocal(action.path)
        val localMtime = if (Files.exists(localPath)) Files.getLastModifiedTime(localPath).toInstant() else null
        val remoteMtime = action.remoteItem?.modified

        val remoteWins =
            when {
                localMtime == null -> true
                remoteMtime == null -> false
                else -> remoteMtime.isAfter(localMtime)
            }

        val loserFile = if (remoteWins && Files.exists(localPath)) localPath else null
        conflictLog?.record(
            path = action.path,
            localState = action.localState.name,
            remoteState = action.remoteState.name,
            policy = "LAST_WRITER_WINS",
            loserFile = loserFile,
        )

        if (remoteWins && action.remoteItem != null && !action.remoteItem.deleted) {
            applyCreatePlaceholder(SyncAction.CreatePlaceholder(action.path, action.remoteItem, shouldHydrate = true))
        } else if (!remoteWins && Files.exists(localPath)) {
            // UD-366: conflict-loser is the remote — overwrite it in place rather than
            // POSTing a duplicate. action.remoteItem is non-null on this branch because
            // remoteWins=false implies a remote modification was the conflict trigger.
            applyUpload(SyncAction.Upload(action.path, remoteId = action.remoteItem?.id))
        }
    }

    private fun entryFromCloudItem(
        item: CloudItem,
        path: String,
        isHydrated: Boolean,
    ) = SyncEntry(
        path = path,
        // #115: when the action's local [path] differs from the remote item's
        // canonical path, this is a locale-aliased row — persist the canonical
        // as remotePath so the next delta matches by effective remote path.
        // Equal paths (the universal case) leave remotePath null → byte-identical.
        remotePath = if (item.path != path) item.path else null,
        remoteId = item.id,
        remoteHash = item.hash,
        remoteSize = item.size,
        remoteModified = item.modified,
        localMtime = placeholder.localMtime(path),
        localSize = placeholder.localSize(path),
        isFolder = item.isFolder,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = Instant.now(),
    )

    private fun buildCanonicalToLocalTopMap(remoteChanges: Map<String, CloudItem>): Map<String, String> {
        val topLevelNames = remoteChanges.keys
            .filter { it.count { c -> c == '/' } == 1 && !remoteChanges[it]!!.deleted }
            .map { it.removePrefix("/") }
            .toSet()
        val aliases = XdgLocaleDirAliases.build(
            remoteTopLevelNames = topLevelNames,
            userDirsOverrides = xdgUserDirsOverrides,
        )
        if (aliases.isEmpty) return emptyMap()
        val rev = HashMap<String, String>()
        for ((localTop, canonical) in aliases.localToCanonicalMap()) {
            if (canonical in rev.values) continue
            if (Files.isDirectory(syncRoot.resolve(localTop))) rev[canonical] = localTop
        }
        return rev
    }

    private fun applyReverseTop(path: String, canonicalToLocalTop: Map<String, String>): String {
        if (canonicalToLocalTop.isEmpty()) return path
        val noSlash = path.removePrefix("/")
        val slash = noSlash.indexOf('/')
        val top = if (slash < 0) noSlash else noSlash.substring(0, slash)
        val localTop = canonicalToLocalTop[top] ?: return path
        val rest = if (slash < 0) "" else noSlash.substring(slash)
        return "/$localTop$rest"
    }

    private fun updateRemoteEntries(remoteChanges: Map<String, CloudItem>) {
        // #115: the remoteChanges keys are canonical remote paths. Build a
        // canonical→real-local reverse map so a newly-arrived aliased item is
        // persisted at its REAL-LOCAL path (with the canonical in remote_path),
        // not as a phantom canonical-keyed row that LocalScanner can never find
        // on disk. Existing rows are matched by effective remote path so the
        // merge preserves their real-local path + remote_path. No alias active
        // → both helpers are identity and this is byte-identical to pre-#115.
        val remoteToLocalTop = buildCanonicalToLocalTopMap(remoteChanges)
        for ((path, item) in remoteChanges) {
            if (item.deleted) continue // skip deleted items
            val realLocalPath = applyReverseTop(path, remoteToLocalTop)
            val isAliased = realLocalPath != path
            // Match an existing row by effective remote path (handles aliased
            // rows keyed at their real-local path).
            val existing = db.getEntryByRemotePath(path)
            val merged =
                existing?.copy(
                    remoteId = item.id,
                    remoteHash = item.hash,
                    remoteSize = item.size,
                    remoteModified = item.modified,
                    lastSynced = Instant.now(),
                    // A fresh delta event for a previously-quarantined row
                    // means the cloud is reporting it alive again — drop the
                    // quarantine and let the next reconcile re-emit the
                    // download. Belt-and-braces with
                    // StateDatabase.clearDownloadQuarantine below: that call
                    // wins on the canonical SQL UPDATE; this copy ensures
                    // any consumer reading the merged value inside this loop
                    // sees the cleared state too.
                    downloadQuarantined = false,
                    lastErrorAt = null,
                    // preserve path, remotePath, localMtime, localSize, isHydrated, isFolder
                ) ?: SyncEntry(
                    // #115: key a newly-arrived aliased item at its real-local
                    // path; record the canonical in remotePath so LocalScanner
                    // finds the row and the next delta matches by effective
                    // remote path.
                    path = realLocalPath,
                    remotePath = if (isAliased) path else null,
                    remoteId = item.id,
                    remoteHash = item.hash,
                    remoteSize = item.size,
                    remoteModified = item.modified,
                    localMtime = null,
                    localSize = null,
                    isFolder = item.isFolder,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                )
            db.upsertEntry(merged)
            // Belt-and-braces (matches the .copy() above): explicitly clear
            // the quarantine flag on the canonical row in case a future
            // upsertEntry call path mutates the row without going through
            // the merged.copy() construction above.
            if (existing != null && existing.downloadQuarantined && existing.remoteId != null) {
                db.clearDownloadQuarantine(existing.remoteId)
            }
        }
    }

    private fun actionLabel(action: SyncAction): String =
        when (action) {
            is SyncAction.CreatePlaceholder ->
                when {
                    action.remoteItem.isFolder -> "mkdir"
                    Files.isRegularFile(placeholder.resolveLocal(action.path)) &&
                        Files.size(placeholder.resolveLocal(action.path)) == action.remoteItem.size -> "adopt"
                    else -> "placeholder"
                }
            is SyncAction.UpdatePlaceholder -> "update"
            is SyncAction.DownloadContent -> "down"
            is SyncAction.MoveRemote -> "move"
            is SyncAction.MoveLocal -> "move"
            is SyncAction.Upload -> "up"
            is SyncAction.DeleteLocal -> "del-local"
            is SyncAction.DeleteRemote -> "del-remote"
            is SyncAction.CreateRemoteFolder -> "mkdir-remote"
            is SyncAction.Conflict -> "CONFLICT"
            is SyncAction.RemoveEntry -> "cleanup"
        }

    // UD-740: display path used in the user-facing progress line. For move
    // actions, expand to `from -> to` so the operator can see what was
    // renamed where; for everything else it's just `action.path`. The
    // failures.jsonl JSON layer keeps `path` clean (destination) and adds
    // `from_path` when relevant — see logFailure.
    private fun displayPath(action: SyncAction): String =
        when (action) {
            is SyncAction.MoveRemote -> "${action.fromPath} -> ${action.path}"
            is SyncAction.MoveLocal -> "${action.fromPath} -> ${action.path}"
            else -> action.path
        }

    private fun restoreToPlaceholder(
        remotePath: String,
        item: CloudItem,
    ) {
        try {
            withEchoSuppression(remotePath) {
                placeholder.createPlaceholder(remotePath, item.size, item.modified)
            }
            // UD-222: if no prior DB entry existed (first-time download that failed), create one
            // marked non-hydrated so the next sync re-attempts. Without this, a failed first-time
            // download left zero trace in the DB and the cursor-promotion guard was the only
            // safety net.
            val entry = db.getEntry(remotePath)
            val next =
                entry?.copy(isHydrated = false, remoteSize = item.size, remoteModified = item.modified)
                    ?: entryFromCloudItem(item, remotePath, isHydrated = false)
            db.upsertEntry(next)
        } catch (e: Exception) {
            // UD-253: class name + throwable for diagnostics.
            log.warn(
                "Could not restore placeholder for {} after cancel: {}: {}",
                remotePath,
                e.javaClass.simpleName,
                e.message,
                e,
            )
        }
    }

    private fun syncPathAncestors(path: String): Set<String> {
        val parts = path.trimStart('/').split('/')
        val ancestors = mutableSetOf<String>()
        for (i in 1 until parts.size) {
            ancestors.add("/" + parts.subList(0, i).joinToString("/"))
        }
        return ancestors
    }

    // UD-256: read the persisted `effective_scope` list (TAB-separated, no entries
    // contain TAB — state.db inspection 2026-05-17 confirmed zero paths with
    // control characters). Empty string / missing key both mean "no scope ever
    // persisted" → return empty list (no constraint).
    private fun loadEffectiveScope(): List<String> {
        val raw = db.getSyncState("effective_scope") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split("\t").filter { it.isNotEmpty() }
    }

    // UD-264: extract the top-level cloud-path segment from an absolute path.
    // `/Documents/CyberLink/Foo` -> `/Documents`. Root-only paths (`/`, ``) or
    // single-segment paths under root (`/Foo`) return their own value as the
    // top-level. Returns null for paths that don't start with `/`, which never
    // happens in production but keeps callers honest.
    private fun topLevelOf(path: String): String? {
        if (!path.startsWith("/")) return null
        val trimmed = path.trimStart('/')
        if (trimmed.isEmpty()) return null
        val firstSlash = trimmed.indexOf('/')
        return "/" + if (firstSlash < 0) trimmed else trimmed.substring(0, firstSlash)
    }

    // UD-264: filter `del-remote` actions against the top-level-never-hydrated
    // guard. For each candidate, identify its top-level segment; if state.db
    // has zero rows under that top-level with is_hydrated=1 OR local_mtime
    // IS NOT NULL, the top-level has never been locally hydrated and the
    // delete is dropped (or, with --ignore-top-level-guard, kept but logged).
    // Cache per (top-level, run) so a wide-blast plan doesn't hammer the DB.
    // DeleteLocal is NOT covered here — the guard's purpose is to refuse
    // *cloud-side* deletes triggered by a partial-local-tree reconciliation.
    private fun applyTopLevelHydrationGuard(actions: List<SyncAction>): List<SyncAction> {
        if (actions.none { it is SyncAction.DeleteRemote }) return actions
        val hydrationCache = mutableMapOf<String, Boolean>()
        val kept = mutableListOf<SyncAction>()
        var skipped = 0
        for (action in actions) {
            if (action !is SyncAction.DeleteRemote) {
                kept.add(action)
                continue
            }
            val top = topLevelOf(action.path)
            if (top == null) {
                kept.add(action)
                continue
            }
            val everHydrated =
                hydrationCache.getOrPut(top) {
                    db.hasHydratedDescendant(top)
                }
            if (everHydrated) {
                kept.add(action)
            } else {
                // Log to skipped-ops.jsonl regardless of opt-out — operators
                // need the audit trail either way.
                logSkippedOp(action, "top_level_never_hydrated")
                if (ignoreTopLevelGuard) {
                    // Opt-out: keep the action in the plan (still logged).
                    kept.add(action)
                } else {
                    skipped++
                    log.warn(
                        "UD-264: skipping del-remote for {} — top-level '{}' has never had a hydrated descendant",
                        action.path,
                        top,
                    )
                }
            }
        }
        if (skipped > 0) {
            log.warn(
                "UD-264: skipped {} del-remote action(s) for never-hydrated top-level subtrees " +
                    "(see skipped-ops.jsonl). Pass --ignore-top-level-guard to override.",
                skipped,
            )
        }
        return kept
    }

    private fun handlePermanentDownloadFailure(
        action: SyncAction.DownloadContent,
        e: PermanentDownloadFailureException,
    ) {
        log.warn(
            "Permanent download failure for {}: {}: {} — quarantining row",
            action.path,
            e.javaClass.simpleName,
            e.message,
        )
        reporter.onWarning("Permanent failure: ${action.path} - ${e.message}")
        logFailure(action, e)
        restoreToPlaceholder(action.path, action.remoteItem)
        val remoteId = action.remoteItem.id
        if (remoteId.isNotEmpty()) {
            db.setDownloadQuarantine(remoteId, Instant.now())
        }
    }

    private fun logUnhydratedFolderSkips() {
        val skipped = reconciler.lastUnhydratedFolderDeletes
        if (skipped.isEmpty()) return
        for (path in skipped) {
            logSkippedOp(SyncAction.DeleteRemote(path), "unhydrated_folder")
        }
        log.warn(
            "skipped {} del-remote action(s) for unhydrated folder rows (see skipped-ops.jsonl)",
            skipped.size,
        )
    }

    // UD-264: append a JSON line to skipped-ops.jsonl. Goes through
    // kotlinx.serialization.json so the output is always valid JSONL even
    // when an action's path contains JSON-special chars (`"`, `\`, newline).
    // PR #46 Codex P2 (2026-05-17): the prior hand-built triple-quoted
    // template broke parsing for valid Linux/cloud filenames — failures.jsonl
    // already had ~119 PARSE_ERR entries from the same bug class in
    // logFailure (separate ticket to follow). The audit-of-record observed a
    // path like `/\ninternxt-cli.desktop` in the wild, so this is not
    // hypothetical. No-op if no log path was wired (CLI sets it; tests that
    // don't care leave it null).
    //
    // The JSON-formatting is lifted into [formatSkippedOpJson] so a unit
    // test can exercise nasty paths without going through LocalScanner (which
    // would reject them at the OS-path layer on Windows).
    private fun logSkippedOp(
        action: SyncAction,
        reason: String,
    ) {
        val path = skippedOpsLogPath ?: return
        val line = formatSkippedOpJson(actionLabel(action), action.path, reason, Instant.now())
        Files.createDirectories(path.parent)
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    // UD-297: literally-empty syncRoot detector. Narrow on purpose — any
    // child entry (file, folder, hidden) makes this return false. The
    // broader "high deletion percentage" case is UD-298.
    private fun isSyncRootEffectivelyEmpty(): Boolean {
        if (!Files.exists(syncRoot)) return true
        if (!Files.isDirectory(syncRoot)) return true
        return Files.newDirectoryStream(syncRoot).use { !it.iterator().hasNext() }
    }

    // UD-299: case-insensitive compare on Windows (drive-letter case + NTFS
    // semantics), exact compare elsewhere. Both inputs are already
    // .toAbsolutePath().normalize() so separator and dot-segment normalisation
    // are taken care of.
    private fun sameSyncRoot(
        a: String,
        b: String,
    ): Boolean {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        return if (isWindows) a.equals(b, ignoreCase = true) else a == b
    }

    private fun logFailure(
        action: SyncAction,
        error: Exception,
    ) {
        val path = failureLogPath ?: return
        val kind = actionLabel(action)
        val msg = (error.message ?: error.javaClass.simpleName).replace("\"", "\\\"")
        // UD-740: include `from_path` for move actions so post-mortem readers
        // can see the rename source. `path` keeps its destination meaning.
        val fromSegment =
            when (action) {
                is SyncAction.MoveRemote -> ""","from_path":"${action.fromPath}""""
                is SyncAction.MoveLocal -> ""","from_path":"${action.fromPath}""""
                else -> ""
            }
        val line = """{"ts":"${Instant.now()}","action":"$kind","path":"${action.path}"$fromSegment,"error":"$msg"}"""
        Files.createDirectories(path.parent)
        Files.writeString(path, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun validateExcludePatterns(patterns: List<String>): List<String> {
        val valid = mutableListOf<String>()
        for (pattern in patterns) {
            try {
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
                valid.add(pattern)
            } catch (e: PatternSyntaxException) {
                log.warn("Invalid exclude pattern '{}', skipping: {}", pattern, e.message)
            }
        }
        return valid
    }

    companion object {
        const val CONSECUTIVE_SYNC_FAILURE_HARD_CAP: Int = 20

        // Bulk-disappearance corroboration guard (mount-view-refresh-design.md §3.2):
        // a complete enumeration flipping more than max(absolute, fraction × tracked
        // rows) to deleted defers reaping until a second complete enumeration corroborates.
        const val BULK_REAP_ABSOLUTE: Int = 50
        const val BULK_REAP_FRACTION: Double = 0.20

        // Remote-shrink trust gate. A *full* enumeration is trusted as complete
        // only if it observed at least ENUM_TRUST_MIN_FRACTION of the tracked
        // baseline; a silently-partial provider walk (transient errors swallowed
        // upstream, short pages) can report complete=true while having dropped
        // whole subtrees, and trusting it would let detectMissingAfterFullSync
        // synthesize a mass-delete cascade for every unobserved row. Accounts
        // below ENUM_TRUST_MIN_BASELINE rows are exempt so a legitimately small or
        // near-empty drive is never pinned incomplete by the gate.
        const val ENUM_TRUST_MIN_FRACTION: Double = 0.5
        const val ENUM_TRUST_MIN_BASELINE: Int = 50

        /**
         * Remote-shrink trust gate. Returns an operator-facing warning when a FULL
         * enumeration observed implausibly few live remote items ([observedAlive])
         * versus the tracked [baseline] — the signature of a silently-partial walk
         * masquerading as complete — otherwise null. Accounts below
         * [ENUM_TRUST_MIN_BASELINE] rows are exempt so a legitimately small or
         * near-empty drive is never blocked. Pure function of the two counts so the
         * threshold is unit-testable without a live gather.
         */
        internal fun remoteShrinkWarningOrNull(observedAlive: Int, baseline: Int): String? {
            if (baseline < ENUM_TRUST_MIN_BASELINE) return null
            if (observedAlive >= baseline * ENUM_TRUST_MIN_FRACTION) return null
            return "Remote enumeration looks partial: a full scan observed only $observedAlive " +
                "live remote item(s) vs $baseline previously-tracked " +
                "(< ${(ENUM_TRUST_MIN_FRACTION * 100).toInt()}%). Refusing to reconcile to avoid " +
                "spurious mass uploads/deletes from an incomplete listing. Do NOT use " +
                "--force-delete (it would apply the bad plan). Re-run when the connection is " +
                "healthy; if you really removed most remote files, rebuild with --reset."
        }

        @JvmField
        val RECENT_UPLOAD_REAP_GRACE: java.time.Duration = java.time.Duration.ofSeconds(120)

        const val SCAN_CHECKPOINT_STALE_HOURS: Long = 6L

        const val REMOTE_WAKE_DEBOUNCE_MS: Long = 5_000L

        const val STREAMING_RECONCILE_CHANNEL_CAPACITY: Int = 4

        internal fun formatSkippedOpJson(
            action: String,
            path: String,
            reason: String,
            ts: Instant,
        ): String =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("ts", kotlinx.serialization.json.JsonPrimitive(ts.toString()))
                    put("action", kotlinx.serialization.json.JsonPrimitive(action))
                    put("path", kotlinx.serialization.json.JsonPrimitive(path))
                    put("reason", kotlinx.serialization.json.JsonPrimitive(reason))
                }.toString()

        fun defaultHydrationCacheRoot(): Path =
            System.getenv("XDG_CACHE_HOME")?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".cache")

        fun hydrationCacheRoot(cacheRoot: Path, cacheKey: String): Path =
            cacheRoot
                .resolve("unidrive/hydration")
                .resolve(cacheKey.ifBlank { "default" })
    }
}
