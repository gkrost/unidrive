package org.krost.unidrive.tracking

import java.time.Instant

/**
 * Tracking-set engine state model.
 *
 * Core invariant: a path is `Untracked` until the client has actually
 * crossed the sync boundary for it (downloaded or uploaded) at least
 * once. **Untracked paths are invisible to deletion logic** — this is
 * what makes the `.safe/` delete-cascade structurally impossible.
 *
 * The spec is verbatim from the clean-room research pass.
 */
enum class TrackState {
    /** Exists locally and/or remotely; client has NEVER touched it. Invisible to deletion logic. */
    Untracked,

    /** Both sides match the client's snapshot. */
    TrackedSynced,

    /** Local diverged, remote matches snapshot. */
    TrackedLocalMod,

    /** Remote diverged, local matches snapshot. */
    TrackedRemoteMod,

    /** Both diverged — conflict. */
    TrackedBothMod,

    /** Local vanished since snapshot; intent to propagate delete to remote (subject to batch guard). */
    TrackedLocalGone,

    /** Remote vanished since snapshot; intent to propagate delete to local (subject to batch guard). */
    TrackedRemoteGone,

    /** Both gone — cleanup. */
    TrackedBothGone,

    /** Crash-recoverable in-flight: upload started, may have partial remote state. */
    PendingUpload,

    /** Crash-recoverable in-flight: download started, may have partial local state. */
    PendingDownload,

    /** Crash-recoverable in-flight: local delete propagated, awaiting remote ack. */
    PendingDeleteLocal,

    /** Crash-recoverable in-flight: remote delete propagated, awaiting local ack. */
    PendingDeleteRemote,
}

/**
 * What the client knows about a path. Path is presentation; the durable
 * identity (per spec Amendment 1) is `(providerId, remoteFileId)` once known.
 *
 * For a `PendingDownload` or `PendingUpload` row created BEFORE the first
 * round-trip completes, `remoteFileId` may be null — we still want to
 * resume / not double-act on it after a crash.
 *
 * Snapshot fields capture the last successful sync. `localHash` / `localSize`
 * are what the bytes looked like the last time we observed them; `remoteEtag` /
 * `remoteSize` are what the server reported. A path is `TrackedSynced` when
 * the live observations match these snapshot fields.
 */
data class TrackingRecord(
    val path: String,
    val providerId: String,
    val remoteFileId: String?,
    val state: TrackState,
    val localHash: String?,
    val localSize: Long?,
    val remoteEtag: String?,
    val remoteSize: Long?,
    val lastSynced: Instant,
)

/**
 * Live observation of a local path. `null` means "doesn't exist locally".
 *
 * `inode` is platform-specific (`fileKey()` on POSIX, NTFS file id on
 * Windows when we can get it); it's a future hook for hard-link detection
 * and rename heuristics. Allowed to be null on systems where the JDK
 * can't get one — spec Amendment 1's identity story still works via
 * content-hash matching.
 */
data class LocalObservation(
    val exists: Boolean,
    val hash: String?,
    val size: Long?,
    val mtime: Instant?,
    val inode: String?,
)

/**
 * Live observation of a remote path. `null`-like (exists=false) means the
 * provider reports no such item. `etag` is the server's opaque change
 * marker — when this differs from the tracking snapshot's etag, the
 * remote has changed.
 *
 * `remoteFileId` is the stable identity for spec Amendment 1. Once we have it,
 * we should keep it across path moves.
 */
data class RemoteObservation(
    val exists: Boolean,
    val remoteFileId: String?,
    val etag: String?,
    val size: Long?,
    val hash: String?,
    val serverMtime: Instant?,
)

/**
 * Opt-in adoption strategy for hashless remote providers (e.g. Internxt,
 * whose API does not return a content hash). The safe default is [OFF]:
 * when both hashes are absent, content identity is unprovable, so the
 * engine surfaces a [ReconcileAction.ReportCollision] instead of silently
 * adopting. Operators who are confident their local and remote copies are
 * in sync can pass [SIZE] or [NAME] to opt in to a weaker match.
 *
 * [OFF]  — default; null-hash entries always produce ReportCollision.
 * [SIZE] — adopt when both hashes are null AND sizes are equal.
 * [NAME] — adopt when both hashes are null AND the path is the same
 *           (i.e. both sides simply exist — the weakest match; useful
 *           for a clean bulk first-scan where name-equality is sufficient
 *           evidence that the file is the same).
 */
enum class AutoMatchMode { OFF, SIZE, NAME }

/**
 * The pure output of the reconciler for one path. Sealed so the engine's
 * apply loop is exhaustive and so action-shape regressions are
 * compile-time visible.
 *
 * Actions are intentionally low-resolution: no batching, no parallelism
 * concerns, no rollback — those live in [BatchGuard] / [TrackingEngine].
 */
sealed class ReconcileAction {
    abstract val path: String

    /** Nothing to do — both sides agree with snapshot, or path is untracked-pure-local. */
    data class NoOp(override val path: String) : ReconcileAction()

    /** Download remote bytes to local. Transitions state to `PendingDownload` → `TrackedSynced`. */
    data class DownloadRemote(override val path: String) : ReconcileAction()

    /** Upload local bytes to remote. Transitions state to `PendingUpload` → `TrackedSynced`. */
    data class UploadLocal(override val path: String) : ReconcileAction()

    /** Local vanished since snapshot — propagate to remote (subject to batch guard). */
    data class PropagateLocalDelete(override val path: String) : ReconcileAction()

    /** Remote vanished since snapshot — propagate to local (subject to batch guard). */
    data class PropagateRemoteDelete(override val path: String) : ReconcileAction()

    /**
     * Both sides diverged from snapshot — conflict. The engine does NOT
     * silently auto-resolve; it records the collision and the user must
     * `unidrive ts claim <path>` to choose a winner. (Spec Amendment 2.)
     */
    data class ReportCollision(override val path: String, val reason: String) : ReconcileAction()
}
