package org.krost.unidrive

import org.slf4j.LoggerFactory

/**
 * UD-752: shared post-authenticate debug log. Lifted out of OneDriveProvider
 * and HiDriveProvider, where the same `Auth: {provider} authenticated /
 * not authenticated after initialize` lines were pasted verbatim.
 *
 * Calling this from the engine / CLI / MCP wrapper means every provider —
 * including Internxt, S3, SFTP, WebDAV, Rclone, LocalFs — gets a consistent
 * post-auth log line for free, without each provider needing to remember to
 * emit one.
 *
 * Production callers that previously did `provider.authenticate()` should
 * call this instead. Tests and integration tests can keep calling
 * `authenticate()` directly — the log is purely diagnostic.
 */
private val authLog = LoggerFactory.getLogger("org.krost.unidrive.Auth")

suspend fun CloudProvider.authenticateAndLog() {
    authenticate()
    if (isAuthenticated) {
        authLog.debug("Auth: {} authenticated", id)
    } else {
        authLog.warn("Auth: {} not authenticated after initialize", id)
    }
}
