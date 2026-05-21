package org.krost.unidrive.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * UD-347 (UD-344 sub-finding): POSIX-aware chmod for token / credential
 * file storage.
 *
 * Apply owner-only permissions to [path] when the underlying filesystem
 * supports the POSIX file attribute view (Linux, macOS). On Windows /
 * FAT / NTFS this is a no-op; token security is provided by the
 * directory ACL there instead.
 *
 * - `ownerRwx = true`  → `rwx------`  (used for the token directory)
 * - `ownerRwx = false` → `rw-------`  (used for the token file)
 *
 * **History.** Originally lived as three identical `internal fun
 * setPosixPermissionsIfSupported` copies — one per OAuth provider —
 * with two of the copies' KDoc literally saying
 * `See [org.krost.unidrive.onedrive.setPosixPermissionsIfSupported]`.
 * Lifted to `:app:core/io` under UD-347 so future credential-storage
 * code paths inherit the chmod without copy-paste.
 */
public fun setPosixPermissionsIfSupported(
    path: Path,
    ownerRwx: Boolean = false,
) {
    val view =
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            ?: return
    val perms =
        if (ownerRwx) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
        }
    view.setPermissions(perms)
}
