package org.krost.unidrive

import java.nio.file.Path

interface CloudProvider {
    val id: String
    val displayName: String
    val isAuthenticated: Boolean

    /** Whether enough credentials/config exist to attempt [authenticate]. */
    val canAuthenticate: Boolean get() = false

    /**
     * Truthful declaration of what this adapter implements. Every adapter MUST
     * override this — there is no default. Optional methods whose capability is
     * not in this set return [CapabilityResult.Unsupported] by contract.
     *
     * See ADR-0005 / UD-301.
     */
    fun capabilities(): Set<Capability>

    suspend fun authenticate()

    suspend fun logout()

    suspend fun listChildren(path: String): List<CloudItem>

    suspend fun getMetadata(path: String): CloudItem

    suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long

    /** Download by known remote ID, skipping metadata lookup if the provider supports it. */
    suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = download(remotePath, destination)

    /**
     * Upload a local file to [remotePath].
     *
     * UD-366: providers that support replace-in-place (e.g. Internxt's `PUT /files/{uuid}`)
     * use [existingRemoteId] when non-null to overwrite an existing remote entry instead of
     * creating a new one. The reconciler passes [SyncEntry.remoteId] for MODIFIED actions;
     * NEW uploads pass null. Providers without explicit replace semantics (path-based PUT
     * like OneDrive, or filesystem overwrite) ignore the parameter.
     *
     * `existingRemoteId` precedes `onProgress` in the signature so trailing-lambda call sites
     * `provider.upload(localPath, remotePath) { transferred, total -> … }` keep binding the
     * lambda to `onProgress` (Kotlin trailing-lambda convention — see memory
     * `feedback_kotlin_default_param_ordering`).
     */
    suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): CloudItem

    suspend fun delete(remotePath: String)

    suspend fun createFolder(path: String): CloudItem

    /**
     * UD-368: create multiple folders in a single round-trip when the underlying API
     * supports it (e.g. Internxt's polymorphic `POST /folders` bulk form, capped at 5
     * per call). Default implementation loops [createFolder] one at a time so providers
     * without bulk semantics keep working unchanged.
     *
     * Returns one [CloudItem] per requested path, in the same order. Implementations
     * that issue bulk calls are responsible for chunking against the server's per-call
     * cap and for handling partial-success / 409-conflict semantics per their backend.
     */
    suspend fun createFolders(paths: List<String>): List<CloudItem> = paths.map { createFolder(it) }

    suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem

    /**
     * Walk the remote and return the change set since [cursor].
     *
     * UD-352: [onPageProgress], when supplied, fires periodically as pages
     * are accumulated so the engine can render a count-climbing heartbeat
     * during long delta walks (Internxt's 63 k-item profile sat silent for
     * minutes pre-fix). Providers paginate at very different granularities;
     * the contract is "fire often enough that a 10 s+ scan emits at least
     * once" — see [ScanHeartbeat] for the canonical math. Providers whose
     * remote-listing API is a single all-at-once call (rclone subprocess,
     * HiDrive's recursive endpoint) may legitimately leave this null — the
     * engine still emits a final-count tick once `delta()` returns. Default
     * argument keeps existing call sites and test stubs source-compatible.
     */
    suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)? = null,
    ): DeltaPage

    /**
     * Delta that includes shared items. Providers that declare
     * [Capability.DeltaShared] must override. Default returns
     * [CapabilityResult.Unsupported] so callers fall back to [delta] explicitly.
     */
    suspend fun deltaWithShared(cursor: String?): CapabilityResult<DeltaPage> =
        CapabilityResult.Unsupported(Capability.DeltaShared, "Provider does not support delta-with-shared enumeration")

    /**
     * Fast first-sync bootstrap: return a cursor representing the remote
     * state *now* without enumerating existing items. The returned
     * [DeltaPage] has an empty or near-empty items list and a cursor that
     * subsequent [delta] calls can use to receive only future changes.
     *
     * Trade-off: items that exist on the remote before the bootstrap
     * timestamp are invisible to unidrive until they mutate. Callers
     * should surface that loudly.
     *
     * Providers that declare [Capability.FastBootstrap] must override.
     * OneDrive / Graph implements this via `?token=latest`. See UD-223.
     */
    suspend fun deltaFromLatest(): CapabilityResult<DeltaPage> =
        CapabilityResult.Unsupported(Capability.FastBootstrap, "Provider does not support token=latest bootstrap")

    suspend fun quota(): QuotaInfo

    /**
     * UD-327: per-file size cap in bytes the target server enforces.
     *
     * Returns null when the provider has no documented or configured cap
     * (default for most providers — files larger than 4 GiB upload fine).
     * Returns a positive byte count when:
     *  - the user explicitly configured one in the profile TOML, OR
     *  - the provider has a documented hard cap (Synology DSM WebDAV defaults
     *    to 4 GiB unless the admin reconfigured nginx).
     *
     * Used by `CloudRelocator.preflightOversized` to surface oversized files
     * at planner-time, before a wasted PUT runs to its full timeout.
     */
    fun maxFileSizeBytes(): Long? = null

    /**
     * Check if a remote item still exists by ID. Default is [Unsupported] —
     * callers that *require* existence verification must check
     * [capabilities] first. This replaces the previous dangerous default of
     * always returning `true`.
     */
    suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> =
        CapabilityResult.Unsupported(Capability.VerifyItem, "Provider cannot verify item existence by ID")

    /** Generate a shareable link for a file/folder. */
    suspend fun share(
        path: String,
        expiryHours: Int = 24,
        password: String? = null,
    ): CapabilityResult<String> = CapabilityResult.Unsupported(Capability.Share, "Provider does not support generating share links")

    /** List active share links for a file/folder. */
    suspend fun listShares(path: String): CapabilityResult<List<ShareInfo>> =
        CapabilityResult.Unsupported(Capability.ListShares, "Provider does not support listing share links")

    /** Revoke a share link by permission ID. */
    suspend fun revokeShare(
        path: String,
        shareId: String,
    ): CapabilityResult<Unit> = CapabilityResult.Unsupported(Capability.RevokeShare, "Provider does not support revoking share links")

    /**
     * Handle webhook notification callback. Called by local HTTP server when
     * the provider sends a change notification. Default is [Unsupported].
     */
    suspend fun handleWebhookCallback(notification: ByteArray): CapabilityResult<Unit> =
        CapabilityResult.Unsupported(Capability.Webhook, "Provider does not support webhook callbacks")

    fun close() {}

    /**
     * Algorithm this provider uses for `remoteHash` strings. Returning
     * null means the provider has no verifiable hash; callers MUST
     * treat that as "skip verification" rather than "verification
     * passed". Default null.
     */
    fun hashAlgorithm(): HashAlgorithm? = null

    /**
     * Provider-specific status fields to render in `unidrive status`
     * after the shared fields (quota, tracked files, etc.). Empty
     * list (the default) means this provider contributes no extras.
     */
    fun statusFields(): List<StatusField> = emptyList()

    /**
     * Optional warning surfaced when relocating large data INTO this
     * provider. `planSize` is the total byte count being moved.
     * Returning null (the default) means "no provider-specific
     * warning". Used by `relocate` to flag known transport ceilings
     * (e.g. WebDAV's nginx-mod_dav throughput cliff).
     */
    fun transportWarning(planSize: Long): String? = null
}

data class ShareInfo(
    val id: String,
    val url: String,
    val type: String,
    val scope: String,
    val hasPassword: Boolean = false,
    val expiration: String? = null,
)
