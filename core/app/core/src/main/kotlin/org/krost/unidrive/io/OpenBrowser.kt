package org.krost.unidrive.io

/**
 * UD-348: shared "open this URL in the user's default browser"
 * helper for OAuth authorization-code flows.
 *
 * Pre-UD-348 OneDrive and HiDrive `TokenManager` each shipped a
 * 17-line word-for-word duplicate `openBrowser` (`Desktop.browse`
 * with platform-command fallback). Lifted here so any future
 * authorization-code provider (S3 SSO, WebDAV bearer-token flows,
 * Internxt OAuth flavours) inherits the helper without copy-paste.
 *
 * Tries `java.awt.Desktop.browse` first (works on Windows, macOS,
 * and most Linux desktops with a `Desktop` peer); falls back to
 * `xdg-open` / `open` / `cmd /c start` if the Desktop API is
 * unsupported on the current JVM-OS combo.
 */
public fun openBrowser(url: String) {
    val os = System.getProperty("os.name", "").lowercase()
    try {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            desktop.browse(java.net.URI(url))
            return
        }
    } catch (_: Exception) {
    }
    // Fallback: platform-specific command
    when {
        os.contains("win") -> Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", url))
        os.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", url))
        else -> Runtime.getRuntime().exec(arrayOf("xdg-open", url))
    }
}
