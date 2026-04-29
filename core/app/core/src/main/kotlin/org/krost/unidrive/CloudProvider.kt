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

    suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): CloudItem

    suspend fun delete(remotePath: String)

    suspend fun createFolder(path: String): CloudItem

    suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem

    suspend fun delta(cursor: String?): DeltaPage

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
}

data class ShareInfo(
    val id: String,
    val url: String,
    val type: String,
    val scope: String,
    val hasPassword: Boolean = false,
    val expiration: String? = null,
)
