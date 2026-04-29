package org.krost.unidrive.rclone

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Path

class RcloneProviderFactory : ProviderFactory {
    override val id = "rclone"

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        val parts = mutableListOf<String>()
        properties["rclone_remote"]?.takeIf { it.isNotBlank() }?.let { parts += "remote=$it" }
        properties["rclone_path"]?.takeIf { it.isNotBlank() }?.let { parts += "path=$it" }
        properties["rclone_binary"]?.takeIf { it.isNotBlank() && it != "rclone" }?.let { parts += "binary=$it" }
        return if (parts.isEmpty()) "rclone provider" else "rclone (${parts.joinToString(", ")})"
    }

    override val metadata =
        ProviderMetadata(
            id = "rclone",
            displayName = "Rclone",
            description = "Any rclone-configured remote (70+ cloud backends: Google Drive, Dropbox, B2, pCloud, Mega, Azure, etc.)",
            authType = "External (rclone config)",
            encryption = "Varies by backend",
            jurisdiction = "Varies by backend",
            gdprCompliant = false,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Self-hosted",
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val rawRemote =
            properties["rclone_remote"]
                ?: throw ConfigurationException("rclone", "Required property 'rclone_remote' is missing")
        // Normalise to rclone's `remote:` path-syntax convention. TOML users
        // write the remote name as it appears in `rclone.conf` ([minio]); the
        // colon is syntactic, not part of the name. RcloneCliService.remotePath
        // concatenates `config.remote` with the path, so the colon must be
        // present on the config. Accept both `"minio"` and `"minio:"`.
        val remote = if (rawRemote.endsWith(":")) rawRemote else "$rawRemote:"
        val path = properties["rclone_path"] ?: ""
        val binary = properties["rclone_binary"] ?: "rclone"
        val configPath = properties["rclone_config"]

        val config =
            RcloneConfig(
                remote = remote,
                path = path,
                rcloneBinary = binary,
                rcloneConfigPath = configPath,
                tokenPath = tokenPath,
            )
        return RcloneProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = !properties["rclone_remote"].isNullOrBlank()

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        if (properties["rclone_remote"].isNullOrBlank()) {
            return CredentialHealth.Missing("Missing: rclone_remote")
        }
        return CredentialHealth.Ok
    }
}
