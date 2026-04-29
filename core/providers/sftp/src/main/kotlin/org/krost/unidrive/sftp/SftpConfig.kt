package org.krost.unidrive.sftp

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration for an SFTP provider.
 *
 * Authentication uses one of:
 * - [identityFile]: path to a PEM/OpenSSH private key (preferred)
 * - [password]: password authentication (fallback if [identityFile] is null)
 *
 * [remotePath] is the base directory on the server used as the sync root.
 * Defaults to the server's default SFTP home ("").
 *
 * [knownHostsFile] defaults to ~/.ssh/known_hosts. Pass `null` to disable host
 * key verification entirely (accept any server key) — only appropriate for
 * first-time connection tests or explicit TOFU flows, not for production sync.
 * If a non-null path is given but the file does not exist, the connection is
 * rejected (fail-closed).
 */
data class SftpConfig(
    val host: String,
    val port: Int = 22,
    val username: String = System.getProperty("user.name") ?: "root",
    val identityFile: Path? = defaultIdentityFile(),
    val password: String? = null,
    val remotePath: String = "",
    val knownHostsFile: Path? = defaultKnownHostsFile(),
    val tokenPath: Path = defaultTokenPath(),
    /**
     * Maximum number of concurrent SFTP subsystem channels on the SSH session.
     * Synology NAS (and other embedded SSH servers) reject parallel channel opens
     * beyond ~10; default 4 is safe for those devices while still enabling parallelism.
     * Set to 1 for full serialization (original behavior).
     */
    val maxConcurrency: Int = 4,
) {
    companion object {
        fun defaultIdentityFile(): Path? {
            val home = System.getenv("HOME") ?: System.getProperty("user.home") ?: return null
            val ed25519 = Paths.get(home, ".ssh", "id_ed25519")
            val rsa = Paths.get(home, ".ssh", "id_rsa")
            return when {
                java.nio.file.Files
                    .exists(ed25519) -> ed25519
                java.nio.file.Files
                    .exists(rsa) -> rsa
                else -> null
            }
        }

        fun defaultKnownHostsFile(): Path? {
            val home = System.getenv("HOME") ?: System.getProperty("user.home") ?: return null
            return Paths.get(home, ".ssh", "known_hosts")
        }

        fun defaultTokenPath(): Path {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return Paths.get(home, ".config", "unidrive", "sftp")
        }
    }
}
