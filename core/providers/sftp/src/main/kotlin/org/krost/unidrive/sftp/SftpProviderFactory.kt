package org.krost.unidrive.sftp

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SftpProviderFactory : ProviderFactory {
    override val id = "sftp"

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        val host = properties["host"]?.takeIf { it.isNotBlank() } ?: return "sftp provider"
        val port = properties["port"]?.takeIf { it.isNotBlank() } ?: "22"
        val user =
            properties["user"]?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.name") ?: "root"
        val parts = mutableListOf("$user@$host:$port")
        properties["identity"]?.takeIf { it.isNotBlank() }?.let { parts += "key=$it" }
        return "sftp (${parts.joinToString(", ")})"
    }

    override val metadata =
        ProviderMetadata(
            id = "sftp",
            displayName = "SFTP",
            description = "SSH File Transfer Protocol — any Linux/NAS server, Hetzner Storage Box",
            authType = "SSH key or password",
            encryption = "Transport-level (SSH)",
            jurisdiction = "Self-hosted (your server's location)",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Self-hosted",
            // UD-263: 4 leaves headroom against OpenSSH's MaxSessions=10
            // default; matches the in-wrapper Semaphore(4) — see
            // docs/providers/sftp-robustness.md §5. Concurrency is the
            // one dimension this provider gets right.
            maxConcurrentTransfers = 4,
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val host =
            properties["host"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException("sftp", "Required property 'host' is missing")

        val port = properties["port"]?.toIntOrNull() ?: 22
        val user =
            properties["user"]?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.name") ?: "root"
        val remotePath = properties["remote_path"] ?: ""
        val identityFile =
            properties["identity"]?.takeIf { it.isNotBlank() }?.let { raw ->
                val expanded =
                    if (raw.startsWith("~")) {
                        val home = System.getenv("HOME") ?: System.getProperty("user.home")
                        raw.replaceFirst("~", home)
                    } else {
                        raw
                    }
                Paths.get(expanded)
            } ?: SftpConfig.defaultIdentityFile()
        val password = properties["password"]
        val maxConcurrency = properties["max_concurrency"]?.toIntOrNull()?.coerceAtLeast(1) ?: 4

        val config =
            SftpConfig(
                host = host,
                port = port,
                username = user,
                identityFile = identityFile,
                password = password,
                remotePath = remotePath,
                tokenPath = tokenPath,
                maxConcurrency = maxConcurrency,
            )
        return SftpProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = properties["host"]?.isNotBlank() == true

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        if (properties["host"].isNullOrBlank()) {
            return CredentialHealth.Missing("Missing: host")
        }
        val identity =
            properties["identity"]?.takeIf { it.isNotBlank() }?.let { raw ->
                val expanded =
                    if (raw.startsWith("~")) {
                        val home = System.getenv("HOME") ?: System.getProperty("user.home")
                        raw.replaceFirst("~", home)
                    } else {
                        raw
                    }
                Paths.get(expanded)
            } ?: SftpConfig.defaultIdentityFile()
        val hasPassword = !properties["password"].isNullOrBlank()
        val hasKey = identity != null && Files.exists(identity)
        if (!hasPassword && !hasKey) {
            return CredentialHealth.Warning("No SSH key found at $identity and no password configured")
        }
        return CredentialHealth.Ok
    }
}
