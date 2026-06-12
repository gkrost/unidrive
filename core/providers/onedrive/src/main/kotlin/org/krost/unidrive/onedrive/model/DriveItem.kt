package org.krost.unidrive.onedrive.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DriveItem(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("size") val size: Long = 0,
    @SerialName("lastModifiedDateTime") val lastModifiedDateTime: String? = null,
    @SerialName("createdDateTime") val createdDateTime: String? = null,
    @SerialName("webUrl") val webUrl: String? = null,
    @SerialName("@microsoft.graph.downloadUrl") val downloadUrl: String? = null,
    @SerialName("folder") val folder: FolderDetails? = null,
    @SerialName("file") val file: FileDetails? = null,
    @SerialName("parentReference") val parentReference: ParentReference? = null,
    @SerialName("fileSystemInfo") val fileSystemInfo: FileSystemInfo? = null,
    @SerialName("remoteItem") val remoteItem: RemoteItem? = null,
    @SerialName("@microsoft.graph.removed") val removed: Removed? = null,
    @SerialName("deleted") val deleted: Deleted? = null,
    // Optimistic-concurrency tag; changes on any metadata or content mutation. Threaded
    // back to PATCH calls via the `If-Match` header so a concurrent editor can be
    // detected (Graph returns 412 Precondition Failed instead of silently overwriting).
    @SerialName("eTag") val eTag: String? = null,
) {
    val isFolder: Boolean get() = folder != null
    val isFile: Boolean get() = file != null

    val isPersonalVault: Boolean
        get() {
            // #287: deletion tombstones short-circuit — a hard-deleted zero-byte
            // file arrives facet-less with size 0, the same shape as the vault
            // stub, and dropping it means the remote delete never propagates.
            if (removed != null || deleted != null) return false
            val noFacets = folder == null && file == null
            val zeroSize = size == 0L
            val nameMatches = name != null && VAULT_NAMES.any { it.equals(name, ignoreCase = true) }
            // #332: the genuine vault stub always lacks facets and reports size 0.
            // Requiring that signature even alongside a name match keeps an
            // ordinary user item that merely shares a vault display name from
            // being silently dropped.
            return noFacets && zeroSize && (nameMatches || !name.isNullOrBlank())
        }

    companion object {
        /**
         * Locale-specific display names for OneDrive Personal Vault. Verified against the
         * localised OneDrive clients; extend as new locales surface.
         *
         * - en: "Personal Vault"
         * - de: "Persönlicher Tresor"
         * - fr: "Coffre-fort personnel"
         * - es: "Almacén personal"
         * - it: "Cassaforte personale"
         * - pt-BR: "Cofre Pessoal"
         * - ja: "個人用 Vault"
         * - zh-Hans: "个人保管库"
         */
        internal val VAULT_NAMES: Set<String> =
            setOf(
                "Personal Vault",
                "Persönlicher Tresor",
                "Coffre-fort personnel",
                "Almacén personal",
                "Cassaforte personale",
                "Cofre Pessoal",
                "個人用 Vault",
                "个人保管库",
            )
    }
}

@Serializable
data class FolderDetails(
    @SerialName("childCount") val childCount: Int = 0,
)

@Serializable
data class FileDetails(
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("hashes") val hashes: FileHashes? = null,
)

@Serializable
data class FileHashes(
    @SerialName("quickXorHash") val quickXorHash: String? = null,
    @SerialName("sha256Hash") val sha256Hash: String? = null,
    @SerialName("crc64Hash") val crc64Hash: String? = null,
)

@Serializable
data class ParentReference(
    @SerialName("id") val id: String? = null,
    @SerialName("driveId") val driveId: String? = null,
    @SerialName("driveType") val driveType: String? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class FileSystemInfo(
    @SerialName("createdDateTime") val createdDateTime: String? = null,
    @SerialName("lastModifiedDateTime") val lastModifiedDateTime: String? = null,
)

/**
 * `@microsoft.graph.removed` facet on a delta response item. The `state`
 * field distinguishes a hard delete (`"deleted"` — item is in the recycle
 * bin and will be permanently purged after Graph's TTL) from a soft removal
 * (`"removed"` — the item is no longer accessible to this user, e.g. a
 * shared-with-me link revoked, or moved out of scope — could come back).
 * Both still mean "the local copy should go" for sync purposes, but the
 * distinction matters for diagnostics and for reasoning about whether the
 * deletion is reversible.
 */
@Serializable
data class Removed(
    @SerialName("state") val state: String? = null,
)

/**
 * `deleted` facet on a delta or item response. Present whenever the item
 * itself has been deleted; `state` is typically `"deleted"`. Distinct from
 * `@microsoft.graph.removed` in that it can appear on non-delta endpoints.
 */
@Serializable
data class Deleted(
    @SerialName("state") val state: String? = null,
)

@Serializable
data class RemoteItem(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("driveId") val driveId: String? = null,
    @SerialName("folder") val folder: FolderDetails? = null,
    @SerialName("file") val file: FileDetails? = null,
)

@Serializable
data class Drive(
    @SerialName("id") val id: String,
    @SerialName("driveType") val driveType: String,
    @SerialName("owner") val owner: IdentitySet? = null,
    @SerialName("quota") val quota: Quota? = null,
)

@Serializable
data class IdentitySet(
    @SerialName("user") val user: Identity? = null,
)

@Serializable
data class Identity(
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("id") val id: String? = null,
)

@Serializable
data class Quota(
    @SerialName("total") val total: Long = 0,
    @SerialName("used") val used: Long = 0,
    @SerialName("remaining") val remaining: Long = 0,
    @SerialName("deleted") val deleted: Long = 0,
)

@Serializable
data class GraphMe(
    @SerialName("id") val id: String? = null,
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("userPrincipalName") val userPrincipalName: String? = null,
    @SerialName("mail") val mail: String? = null,
    @SerialName("givenName") val givenName: String? = null,
    @SerialName("surname") val surname: String? = null,
)
