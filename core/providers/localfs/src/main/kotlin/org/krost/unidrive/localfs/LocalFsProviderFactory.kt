package org.krost.unidrive.localfs

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.PromptSpec
import java.nio.file.Path
import java.nio.file.Paths

/**
 * SPI factory for the [LocalFsProvider]. Registered via
 * `META-INF/services/org.krost.unidrive.ProviderFactory` and discovered by
 * `ProviderRegistry` at runtime, exactly like the cloud provider modules.
 *
 * Required config: `root_path` — the local directory to treat as the remote.
 * (Main maps `rp.sync_root ?: rp.root_path` onto the `root_path` property, so a
 * profile that sets only `root_path` lands here unambiguously while the daemon's
 * own `sync_root` stays the separate local hydration mirror.)
 */
class LocalFsProviderFactory : ProviderFactory {
    override val id: String = "localfs"

    override val metadata: ProviderMetadata =
        ProviderMetadata(
            id = "localfs",
            displayName = "Local Filesystem",
            description = "Syncs against a local directory. No account, no network — for offline development and testing.",
            authType = "None",
            encryption = "None (local files)",
            jurisdiction = "Local machine",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Local",
            maxConcurrentTransfers = 8,
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val rootRaw =
            properties["root_path"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException(
                    id,
                    "missing required 'root_path' (the local directory to treat as the remote)",
                )
        val root = Paths.get(expandTilde(rootRaw)).toAbsolutePath().normalize()
        return LocalFsProvider(root)
    }

    /** localfs has no credentials, but it's only usable once root_path is configured. */
    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = !properties["root_path"].isNullOrBlank()

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String = "localfs -> ${properties["root_path"] ?: "(unset root_path)"}"

    override fun credentialPrompts(): List<PromptSpec> =
        listOf(
            PromptSpec(
                key = "root_path",
                label = "Local directory to treat as the remote",
                isMasked = false,
                required = true,
            ),
        )

    private fun expandTilde(p: String): String =
        if (p == "~" || p.startsWith("~/") || p.startsWith("~\\")) {
            System.getProperty("user.home") + p.substring(1)
        } else {
            p
        }
}
