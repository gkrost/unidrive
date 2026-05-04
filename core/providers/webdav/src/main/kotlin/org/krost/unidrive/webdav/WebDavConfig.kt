package org.krost.unidrive.webdav

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration for a WebDAV provider.
 *
 * [baseUrl] is the full URL of the WebDAV root, e.g.:
 *   "https://dav.example.com/remote.php/dav/files/user"  (Nextcloud)
 *   "https://dav.example.com/webdav/"                    (Synology DSM)
 *   "http://localhost:8080/"                             (local rclone serve webdav)
 *
 * [username] and [password] are used for HTTP Basic authentication.
 */
data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val tokenPath: Path = defaultTokenPath(),
    val trustAllCerts: Boolean = false,
    val uploadFloorTimeoutMs: Long = 600_000L,
    val uploadMinThroughputBytesPerSecond: Long = 50L * 1024,
    val downloadFloorTimeoutMs: Long = 600_000L,
    val downloadMinThroughputBytesPerSecond: Long = 50L * 1024,
    val maxFileSizeBytes: Long? = null,
) {
    companion object {
        fun defaultTokenPath(): Path {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return Paths.get(home, ".config", "unidrive", "webdav")
        }
    }
}
