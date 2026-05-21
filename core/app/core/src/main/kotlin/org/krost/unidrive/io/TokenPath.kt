package org.krost.unidrive.io

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Default per-profile token storage directory: `$HOME/.config/unidrive/<providerId>`.
 *
 * UD-009: lifted from 5 provider-local copies (SFTP, WebDAV, S3, OneDrive,
 * Internxt) that all had byte-identical implementations differing only in the
 * trailing path segment. The caller passes its own `providerId` (typically
 * `factory.id`) so this helper does not need to know which providers exist.
 *
 * Honouring `XDG_CONFIG_HOME` is deliberately out of scope — see UD-009 §"Out
 * of scope". When XDG compliance becomes a goal, swap the body, not the API.
 */
fun defaultTokenPath(providerId: String): Path {
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    return Paths.get(home, ".config", "unidrive", providerId)
}
