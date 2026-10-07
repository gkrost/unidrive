package org.krost.unidrive.sync

import org.krost.unidrive.io.OwnerOnly
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * The folder that holds the per-profile IPC sockets, reachable by the current user only.
 *
 * - Windows: `<java.io.tmpdir>/unidrive-ipc` (AF_UNIX sockets do not bind in %LOCALAPPDATA% itself).
 *   The temp folder may grant other local principals access, so the folder must be a plain directory
 *   (no link or junction) with an accepted owner ([OwnerOnly.ownerProblem]); its permissions are then
 *   set to owner-only explicitly, also when an earlier version created it with inherited ones.
 * - Linux/macOS without `/run/user/<uid>`: `<java.io.tmpdir>/unidrive-ipc-<uid>`. The name is fixed so
 *   clients find the socket; the folder is created with mode 700, and an existing one must be a real
 *   directory (not a symbolic link) owned by this uid with mode 700.
 */
internal object IpcSocketDir {
    const val WINDOWS_DIR_NAME = "unidrive-ipc"

    private const val PERMISSION_BITS = 0b111_111_111
    private const val OWNER_ONLY_MODE = 0b111_000_000

    fun posixFallbackName(uid: Int): String = "unidrive-ipc-$uid"

    /** What is known about an existing folder, read without following a symbolic link. */
    data class DirFacts(
        val isSymbolicLink: Boolean,
        val isDirectory: Boolean,
        val isOther: Boolean,
        val ownerUid: Int,
        val mode: Int,
    )

    interface PosixDirOps {
        /** Facts about [dir], or null when nothing is there. */
        fun facts(dir: Path): DirFacts?

        /** Create [dir] (not its parents) with mode 700; [FileAlreadyExistsException] when it exists. */
        fun createPrivate(dir: Path)
    }

    /** Null when [facts] describe a folder only [uid] can use; otherwise why not. */
    fun fallbackDirProblem(
        facts: DirFacts,
        uid: Int,
    ): String? =
        when {
            facts.isSymbolicLink -> "it is a symbolic link"
            !facts.isDirectory || facts.isOther -> "it is not a directory"
            facts.ownerUid != uid -> "it is owned by uid ${facts.ownerUid}, not by uid $uid"
            (facts.mode and PERMISSION_BITS) != OWNER_ONLY_MODE ->
                "its mode is ${"%03o".format(facts.mode and PERMISSION_BITS)}, not 700"
            else -> null
        }

    fun ensurePosixFallbackDir(
        tmpDir: Path,
        uid: Int,
        ops: PosixDirOps = RealPosixDirOps,
    ): Path {
        val dir = tmpDir.resolve(posixFallbackName(uid))
        if (ops.facts(dir) == null) {
            try {
                ops.createPrivate(dir)
            } catch (_: FileAlreadyExistsException) {
                // Appeared meanwhile: checked below like any existing folder.
            }
        }
        val facts = ops.facts(dir) ?: throw IllegalStateException("Could not create the IPC socket folder $dir")
        fallbackDirProblem(facts, uid)?.let {
            throw IllegalStateException("Refusing to use $dir as the IPC socket folder: $it. Remove it so it can be recreated.")
        }
        return dir
    }

    fun ensureWindowsDir(tmpDir: Path): Path {
        val dir = tmpDir.resolve(WINDOWS_DIR_NAME)
        Files.createDirectories(dir)
        val attrs = Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isDirectory || attrs.isSymbolicLink || attrs.isOther) {
            throw IllegalStateException("Refusing to use $dir as the IPC socket folder: it is a link or junction, not a directory. Remove it.")
        }
        OwnerOnly.ownerProblem(dir)?.let {
            throw IllegalStateException("Refusing to use $dir as the IPC socket folder: $it. Remove it.")
        }
        OwnerOnly.requireDirectory(dir)
        return dir
    }

    private object RealPosixDirOps : PosixDirOps {
        override fun facts(dir: Path): DirFacts? {
            val basic =
                try {
                    Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    return null
                }
            return DirFacts(
                isSymbolicLink = basic.isSymbolicLink,
                isDirectory = basic.isDirectory,
                isOther = basic.isOther,
                ownerUid = Files.getAttribute(dir, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int,
                mode = Files.getAttribute(dir, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int,
            )
        }

        override fun createPrivate(dir: Path) {
            val ownerOnly = PosixFilePermissions.fromString("rwx------")
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(ownerOnly))
            // The umask can only narrow the mode at creation; set it exactly.
            Files.setPosixFilePermissions(dir, ownerOnly)
        }
    }
}
