package org.krost.unidrive.localfs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** True on Windows, where access denial goes through ACL entries instead of POSIX bits. */
internal fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")

/**
 * Makes [path] unreadable for the current user the platform's way (a deny ACE for the file
 * owner on Windows, mode 000 on POSIX), returning the restore thunk — or null when the
 * platform cannot deny access this way: no ACL view, a POSIX test running as root, or an
 * environment where the applied deny does not actually deny (an elevated Windows runner
 * reads through its own deny ACE). The deny is verified with a real probe; the caller
 * skips the test on null.
 */
internal fun denyRead(path: Path): (() -> Unit)? {
    val restore =
        try {
            if (isWindows()) {
                val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return null
                val original = view.acl
                val deny =
                    AclEntry
                        .newBuilder()
                        .setType(AclEntryType.DENY)
                        .setPrincipal(view.owner)
                        .setPermissions(
                            AclEntryPermission.READ_DATA,
                            AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.READ_ACL,
                        ).build()
                view.acl = listOf(deny) + original
                ({ view.setAcl(original) })
            } else {
                if (System.getProperty("user.name") == "root") return null // root reads through mode 000
                val original = Files.getPosixFilePermissions(path)
                Files.setPosixFilePermissions(path, emptySet())
                ({ Files.setPosixFilePermissions(path, original); Unit })
            }
        } catch (_: Exception) {
            return null
        }
    // The applied deny must actually deny: probe with the same operation the code under
    // test performs, and skip the test where the environment reads through the deny.
    return if (runCatching { Files.newDirectoryStream(path).use { } }.isSuccess) {
        restore()
        null
    } else {
        restore
    }
}

/**
 * Makes [path] unwritable for the current user (a deny ACE for the file owner on Windows,
 * write bits off on POSIX), returning the restore thunk — or null when the platform cannot
 * deny access this way, verified the same way as [denyRead]. The caller skips the test on
 * null.
 */
internal fun denyWrite(path: Path): (() -> Unit)? {
    val restore =
        try {
            if (isWindows()) {
                val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return null
                val original = view.acl
                val deny =
                    AclEntry
                        .newBuilder()
                        .setType(AclEntryType.DENY)
                        .setPrincipal(view.owner)
                        .setPermissions(
                            AclEntryPermission.WRITE_DATA,
                            AclEntryPermission.APPEND_DATA,
                            AclEntryPermission.DELETE,
                        ).build()
                view.acl = listOf(deny) + original
                ({ view.setAcl(original) })
            } else {
                if (System.getProperty("user.name") == "root") return null // root writes through mode bits
                val original = Files.getPosixFilePermissions(path)
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r-xr-xr-x"))
                ({ Files.setPosixFilePermissions(path, original); Unit })
            }
        } catch (_: Exception) {
            return null
        }
    return if (runCatching { Files.createTempFile(path, ".deny-probe", null).let { Files.deleteIfExists(it) } }.isSuccess) {
        restore()
        null
    } else {
        restore
    }
}
