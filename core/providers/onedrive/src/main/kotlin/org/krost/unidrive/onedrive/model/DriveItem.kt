package org.krost.unidrive.onedrive.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    @SerialName("@microsoft.graph.removed") val removed: JsonObject? = null,
    @SerialName("deleted") val deleted: JsonObject? = null,
) {
    val isFolder: Boolean get() = folder != null
    val isFile: Boolean get() = file != null

    val isPersonalVault: Boolean
        get() {
            val noFacets = folder == null && file == null
            val zeroSize = size == 0L
            val nameMatches = name != null && VAULT_NAMES.any { it.equals(name, ignoreCase = true) }
            return nameMatches || (noFacets && zeroSize && !name.isNullOrBlank())
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
