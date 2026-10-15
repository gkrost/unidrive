package org.krost.unidrive.engine

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.krost.unidrive.sync.SyncScope

/**
 * The checks every remote operation of one profile passes, whichever path
 * starts it: the mirror engine's sync pass and the mount's write verbs (#560 U2).
 *
 * - **Scope**: which remote paths state.db tracks — the standing scope plus any
 *   per-run `--sync-path`, and the folders leading to them. Empty = the whole drive.
 * - **Excludes**: the keep-local rule; an excluded path is never uploaded. Two sources: the profile's
 *   `exclude_patterns` (the old glob, unchanged) and, where the host passes them, the ignore rules ([ignore]).
 * - **Transfer budget**: one per-provider transfer cap shared by the sync pass and
 *   the hydration upload path (UD-263).
 * - **Trash namespace**: the virtual folder that shows what the provider keeps in its trash, which is read-only
 *   ([isReadOnlyPath], see [TrashNamespace]).
 *
 * Built once per engine; the instance holds the transfer semaphore, so the mirror
 * engine and the mount operations of one process must share the same instance.
 */
class RemoteOperationGuard(
    standingScope: List<String>,
    syncPaths: List<String>,
    // The effective exclude patterns (configured excludes union the defaults),
    // already validated by the caller.
    private val excludePatterns: List<String>,
    // The provider's transfer concurrency cap (ProviderMetadata.maxConcurrentTransfers).
    val maxConcurrentTransfers: Int,
    // The glob matcher of the exclude patterns. Injected: the matcher (with its
    // compiled-pattern cache) is shared with the planner and stays in :app:sync.
    private val matchesGlob: (path: String, pattern: String) -> Boolean,
    // #660: the ignore rules (rule files, account rules, the overridable content defaults), or null where the
    // host has none. Upload-side only: nothing here hides a remote item or holds a download back.
    private val ignore: UploadIgnoreCheck? = null,
    // The trash namespace of the profile: its name (profile key trash_folder_name) and, once something is in the trash,
    // where its root is. One per guard, so the sync pass, the mount verbs and the provider decorator all ask the same one.
    val trash: TrashNamespace = TrashNamespace(),
) {
    /** The normalised tracked scope; empty means the whole drive. */
    val trackScope: List<String> =
        if (standingScope.isEmpty()) emptyList() else SyncScope.normalize(standingScope + syncPaths)
    private val trackAncestors: Set<String> = SyncScope.ancestors(trackScope)

    /** True when state.db tracks [remotePath]: inside the scope, or a folder leading to it. */
    fun isTracked(remotePath: String): Boolean = SyncScope.contains(remotePath, trackScope) || remotePath in trackAncestors

    /**
     * True when [path] lies outside the standing sync scope (config
     * `sync_path`); empty scope means the whole drive, so nothing is out of
     * scope. The hydration write verbs use this to refuse writes that would
     * create or move cloud data the mounted view can never show.
     */
    fun isOutOfScope(path: String): Boolean = !SyncScope.contains(path, trackScope)

    /**
     * True when [path] is in the trash namespace: the root folder or anything below it. The engine never sends a write
     * for such a path to the cloud and ignores local changes there; the mount verbs that would write refuse it with
     * `read_only`, and the sync pass drops the actions that would. Reading and copying out are not writes and stay allowed.
     */
    fun isReadOnlyPath(path: String): Boolean = trash.contains(path)

    /**
     * True when [path] matches the effective exclude patterns (configured
     * excludes union the defaults) or an ignore rule. Keep-local rule: such paths are never
     * uploaded. Shared by the upload path and the hydration write verbs, which
     * must report an excluded write instead of letting it present as in-sync.
     * [isDir] matters to the ignore rules only (a `build/` rule names folders).
     */
    fun isExcludedPath(
        path: String,
        isDir: Boolean = false,
    ): Boolean = excludePatterns.any { matchesGlob(path, it) } || ignore?.isIgnored(path, isDir) == true

    /** #660: the first of the profile's exclude patterns [path] matches, or null (the ignore rules not asked). */
    fun matchingExcludePattern(path: String): String? = excludePatterns.firstOrNull { matchesGlob(path, it) }

    /** #660: true when [path] is a rule file of the ignore rules: its cache copy is what the rules are read from. */
    fun isIgnoreRuleFile(path: String): Boolean = ignore?.isRuleFile(path) == true

    /** #660: a rule file was written at [path]; its folder's rules are read again before the next decision. */
    fun ignoreRuleFileWritten(path: String) {
        ignore?.ruleFileWritten(path)
    }

    /**
     * The per-provider transfer budget. The sync pass hands it to its transfer
     * executors; everything else goes through [withTransferPermit].
     */
    val transferBudget: Semaphore = Semaphore(maxConcurrentTransfers)

    /**
     * Run [block] holding one permit of the per-provider transfer budget. The
     * hydration upload path goes through this so mount writes and sync passes
     * share one cap instead of each running unbounded.
     */
    suspend fun <T> withTransferPermit(block: suspend () -> T): T = transferBudget.withPermit { block() }
}

/**
 * #660: the ignore rules as the upload path asks them. Implemented in :app:sync (`UploadIgnore`), over the
 * rule files of the host's local copy; engine-core only asks. Paths are logical (`/a/b`).
 */
interface UploadIgnoreCheck {
    fun isIgnored(
        path: String,
        isDir: Boolean,
    ): Boolean

    fun isRuleFile(path: String): Boolean

    fun ruleFileWritten(path: String)
}
