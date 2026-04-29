package org.krost.unidrive.localfs

import org.krost.unidrive.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class LocalFsProviderFactory : ProviderFactory {
    override val id = "localfs"

    override val metadata =
        ProviderMetadata(
            id = "localfs",
            displayName = "Local Filesystem",
            description = "Local directory — backup to second drive, Docker testing, relocate between paths",
            authType = "None (filesystem access)",
            encryption = "None (local I/O)",
            jurisdiction = "Local machine",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Local",
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val rootRaw =
            properties["root_path"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException("localfs", "Required property 'root_path' is missing")
        val rootPath = Paths.get(rootRaw).toAbsolutePath().normalize()
        val config = LocalFsConfig(rootPath = rootPath, tokenPath = tokenPath)
        return LocalFsProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean {
        val rootRaw = properties["root_path"]?.takeIf { it.isNotBlank() } ?: return false
        return Files.exists(Paths.get(rootRaw))
    }

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        val rootRaw =
            properties["root_path"]?.takeIf { it.isNotBlank() }
                ?: return CredentialHealth.Missing("Missing: root_path")
        val rootPath = Paths.get(rootRaw)
        if (!Files.exists(rootPath)) {
            return CredentialHealth.Missing("root_path does not exist: $rootRaw")
        }
        if (!Files.isReadable(rootPath)) {
            return CredentialHealth.Warning("root_path is not readable: $rootRaw")
        }
        return CredentialHealth.Ok
    }

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        val rootPath = properties["root_path"]?.takeIf { it.isNotBlank() } ?: return "localfs provider"
        return "localfs ($rootPath)"
    }
}
