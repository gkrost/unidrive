package org.krost.unidrive

import java.nio.file.Path

interface CloudProvider {
    val id: String
    val displayName: String

    /**
     * UD-007: writable so the default [logout] implementation can flip it.
     * Providers that own external resources (token files, network sessions,
     * delegated auth services) override the getter to compute from their
     * own state — see OneDrive's `tokenManager.isAuthenticated` delegation
     * and Internxt's `authService.isAuthenticated`. The setter on those
     * delegated implementations becomes a no-op via the override; the four
     * boilerplate providers (WebDAV, S3, Rclone, LocalFs) keep a simple
     * `override var isAuthenticated: Boolean = false` so the default
     * [logout] body can reset it without per-provider override.
     */
    var isAuthenticated: Boolean

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

    /**
     * Forget any cached credentials / authenticated state.
     *
     * UD-007: default implementation flips the in-memory flag. Providers that
     * own external resources (open API clients, token files, network
     * sessions) override to release them — typically calling `super.logout()`
     * is unnecessary because the override sets its own `isAuthenticated` via
     * its delegated state.
     */
    suspend fun logout() {
        isAuthenticated = false
    }

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
     *
     * #291: [ifMatchETag], when non-null, is the optimistic-concurrency token the engine
     * observed at plan time. Providers that support conditional writes (OneDrive's `If-Match`
     * on the content PUT / upload session) use it to detect a concurrent remote edit landing
     * between plan and apply — the write fails (Graph 412 Precondition Failed) instead of
     * blind-overwriting the other editor's change. It likewise precedes `onProgress` so
     * trailing-lambda call sites are unaffected. Providers without conditional-write semantics
     * (path-based or filesystem overwrite) ignore it; null means "no guard" (e.g. a NEW upload).
     */
    suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String? = null,
        ifMatchETag: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): CloudItem

    /**
     * Delete the remote item at [remotePath].
     *
     * #291: [ifMatchETag], when non-null, makes the delete conditional on the caller's view of
     * the item being current (OneDrive sends it as an `If-Match` header). A concurrent edit
     * between plan and apply flips the server-side eTag and the delete fails (412) instead of
     * destroying a version the engine never saw. Providers without conditional-delete semantics
     * ignore it; null preserves the legacy unconditional delete.
     */
    suspend fun delete(remotePath: String, ifMatchETag: String? = null)

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
     *
     * [scanContext], when supplied, switches the provider into resumable
     * mode: each successfully-fetched page is persisted via the context's
     * [ScanContext.persistPage] callback before the next is issued, and
     * [ScanContext.resumeMarker] (if non-null) tells the provider where
     * the previous interrupted scan left off. Providers without a resume
     * story may ignore it; the SPI default mirrors the existing
     * "accumulate in memory" behaviour. The engine clears the staged
     * inventory on a successful complete scan; partial scans leave it in
     * place for the next launch to resume from.
     */
    suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)? = null,
        scanContext: ScanContext? = null,
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

    /**
     * Register a coarse-grained wake-signal callback. Providers that have a
     * server-pushed change feed (Internxt's socket.io `NOTIFICATIONS_URL`)
     * invoke [callback] once per observed remote mutation that did NOT
     * originate from this client. The callback is a "something changed,
     * walk the delta" hint — not authoritative payload — so the receiver
     * is expected to debounce and then trigger the normal [delta] walk.
     *
     * Default implementation is a no-op; providers without a push channel
     * (or with one wired through a different surface, e.g. OneDrive's
     * webhook handled via [handleWebhookCallback]) inherit it.
     *
     * Called at most once per provider lifetime; the second call REPLACES
     * the first. Pass `{}` to detach. Implementations may invoke the
     * callback on any thread.
     */
    fun onRemoteChangeHint(callback: () -> Unit) { /* default: no-op */ }

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
