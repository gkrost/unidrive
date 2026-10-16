package org.krost.unidrive.localfs

import org.krost.unidrive.AddRefusal
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.PromptSpec
import org.slf4j.LoggerFactory
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
            // #655: quota() probes the machine's FileStore — machine storage, not an
            // account plan — so daemon.status carries no quota snapshot for localfs.
            hasQuota = false,
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
        rootRefusal(rootRaw, root, properties["allow_drive_root"] == "true", create = true)?.let {
            throw ConfigurationException(id, "${it.token}: ${it.message}")
        }
        if (root.parent == null) {
            LoggerFactory.getLogger(LocalFsProviderFactory::class.java)
                .warn("localfs: allow_drive_root is set; syncing the filesystem root '{}', system folders excluded by the walk", root)
        }
        return LocalFsProvider(root)
    }

    // #917: `profile add` refuses an unusable root with the same tokens, without creating the folder.
    override fun preflightAdd(properties: Map<String, String?>): AddRefusal? {
        val rootRaw = properties["root_path"]?.takeIf { it.isNotBlank() } ?: return null
        val root = Paths.get(expandTilde(rootRaw)).toAbsolutePath().normalize()
        return rootRefusal(rootRaw, root, properties["allow_drive_root"] == "true", create = false)
    }

    private fun rootRefusal(
        rootRaw: String,
        root: Path,
        allowDriveRoot: Boolean,
        create: Boolean,
    ): AddRefusal? {
        // #910: a filesystem/drive root (X:\, /) holds the system folders and, for a two-way
        // mirror, everything else on the volume — refuse it unless the profile says otherwise.
        if (root.parent == null && !allowDriveRoot) {
            return AddRefusal(
                "root_is_drive_root",
                "'$rootRaw' is a drive/filesystem root. A two-way mirror would pull in the " +
                    "system folders and everything else on the volume. Point root_path at a dedicated folder, " +
                    "or set allow_drive_root=true to accept it anyway.",
            )
        }
        return when (val token = RootAccess.validate(root, create)) {
            null -> null
            "root_not_creatable" -> AddRefusal(token, "'$rootRaw' does not exist and cannot be created")
            "root_not_directory" -> AddRefusal(token, "'$rootRaw' exists but is not a directory")
            "root_not_readable" -> AddRefusal(token, "'$rootRaw' cannot be read by this user")
            "root_not_writable" ->
                AddRefusal(token, "'$rootRaw' cannot be written to by this user (the localfs account is a two-way mirror)")
            else -> AddRefusal(token, "'$rootRaw' is not usable as a sync root")
        }
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
