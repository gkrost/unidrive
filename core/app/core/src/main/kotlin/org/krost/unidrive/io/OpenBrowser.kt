package org.krost.unidrive.io

import java.io.IOException

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
 * Uses the platform's opener directly (`rundll32` / `open` /
 * `xdg-open`) and does not touch `java.awt.Desktop` any more:
 * a jlink runtime image without `java.desktop` (~10 MB saved) would
 * throw `NoClassDefFoundError` on the old Desktop-first path — an
 * `Error`, which the previous `catch (Exception)` did not catch, so
 * the fallback never ran exactly where it was needed. `rundll32`
 * also avoids `cmd /c start`'s `&`-splits an OAuth URL's query
 * string into commands.
 *
 * Best effort: if no opener can be launched the failure is reported
 * on stderr and the flow continues — every caller prints the URL
 * itself so the user can paste it into a browser.
 */
public fun openBrowser(url: String) {
    val os = System.getProperty("os.name", "").lowercase()
    val cmd = when {
        os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
        os.contains("mac") -> arrayOf("open", url)
        else -> arrayOf("xdg-open", url)
    }
    try {
        Runtime.getRuntime().exec(cmd)
    } catch (e: IOException) {
        System.err.println("Could not open a browser (${e.message}); paste the printed URL manually.")
    }
}
