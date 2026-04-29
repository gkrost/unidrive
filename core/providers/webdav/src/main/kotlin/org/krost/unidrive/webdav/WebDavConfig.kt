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
    /**
     * UD-277: per-PUT request-timeout floor. Small files always get at least
     * this much, regardless of file size. 10 minutes — same as the old
     * `HttpDefaults.REQUEST_TIMEOUT_MS` flat cap for metadata-plane verbs.
     */
    val uploadFloorTimeoutMs: Long = 600_000L,
    /**
     * UD-277: minimum sustained upload throughput (bytes per second) the
     * size-adaptive policy assumes. Default 50 KiB/s — bounds a 277 MB file
     * (the empirical large-file size from the 2026-04-29 traffic baseline)
     * to ~90 minutes total, well above Synology DSM's ~22-minute server-side
     * abort, but tight enough to catch a runaway 1-byte/sec slow-loris write.
     * Set to 0 to opt out (fall back to UD-285's [Long.MAX_VALUE] behaviour).
     */
    val uploadMinThroughputBytesPerSecond: Long = 50L * 1024,
    /** UD-277: same shape, applied to GET. */
    val downloadFloorTimeoutMs: Long = 600_000L,
    val downloadMinThroughputBytesPerSecond: Long = 50L * 1024,
    /**
     * UD-327: per-file size cap in bytes the target server enforces.
     * Null = unknown / no cap. Synology DSM defaults to 4 GiB unless the
     * admin reconfigured `/etc/nginx/app.d/server.webdav.conf` — set this
     * explicitly via the profile's `max_file_size_bytes` knob to surface
     * oversized files at relocate-planner time instead of after a 10-minute
     * wasted PUT.
     */
    val maxFileSizeBytes: Long? = null,
) {
    companion object {
        fun defaultTokenPath(): Path {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return Paths.get(home, ".config", "unidrive", "webdav")
        }
    }
}
