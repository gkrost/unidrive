package org.krost.unidrive.io

import java.io.IOException
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * Makes a folder or a file reachable by the current user only, set explicitly instead of inherited
 * from the parent folder.
 *
 * - POSIX: folder `rwx------`, file `rw-------` (through [setPosixPermissionsIfSupported]).
 * - Windows: a protected DACL (nothing inherited from the parent) granting full control to the
 *   current user, SYSTEM and Administrators only. On a folder the entries are object- and
 *   container-inheritable, so what is created in it later gets exactly that set, and Windows
 *   re-derives inherited entries. Existing descendants are also restricted explicitly, since a
 *   parent's DACL does not remove an explicit grant or a protected DACL on a child.
 *
 * Both are idempotent: a path that already has these permissions is left as it is
 * ([Outcome.Unchanged]), so callers can apply them at every start to folders an earlier version
 * created with inherited permissions. A failure is returned, never swallowed; [requireDirectory]
 * and [requireFile] throw it.
 */
public object OwnerOnly {
    public sealed interface Outcome {
        /** The permissions were replaced by the owner-only ones. */
        public data object Changed : Outcome

        /** The permissions already were the owner-only ones. */
        public data object Unchanged : Outcome

        /** The file system has neither POSIX permissions nor Windows ACLs. */
        public data class Unsupported(
            val reason: String,
        ) : Outcome

        /** Restricting failed; the permissions are whatever they were. */
        public data class Failed(
            val reason: String,
        ) : Outcome

        public val restricted: Boolean get() = this === Changed || this === Unchanged
    }

    private val ownerDirectory =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    private val ownerFile = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    public fun restrictDirectory(dir: Path): Outcome = restrict(dir, directory = true)

    public fun restrictFile(file: Path): Outcome = restrict(file, directory = false)

    /** [restrictDirectory], throwing [IOException] unless the folder ends up owner-only. */
    public fun requireDirectory(dir: Path): Outcome = restrictDirectory(dir).also { requireRestricted(dir, it) }

    /** [restrictFile], throwing [IOException] unless the file ends up owner-only. */
    public fun requireFile(file: Path): Outcome = restrictFile(file).also { requireRestricted(file, it) }

    /**
     * Null when the owner of [path] is one a folder for this user alone may have; otherwise why not.
     * Windows: the current user, or Administrators while this process is a member of Administrators
     * (an elevated process makes Administrators the owner of what it creates). POSIX: the current uid.
     * Throws [IOException] when the owner cannot be read.
     */
    public fun ownerProblem(path: Path): String? {
        if (isWindows(path)) {
            val owner = WindowsSecurity.read(path).ownerSid
            val user = WindowsSecurity.currentUserSid
            if (owner.equals(user, ignoreCase = true)) return null
            return if (windowsOwnerAccepted(owner, user, WindowsSecurity.processIsAdministrator)) {
                null
            } else {
                "it is owned by $owner, not by the current user $user"
            }
        }
        val owner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int
        val uid = posixUid()
        return if (owner == uid) null else "it is owned by uid $owner, not by uid $uid"
    }

    /** The real uid of this process (POSIX `getuid`). */
    public fun posixUid(): Int {
        val linker = Linker.nativeLinker()
        val getuid =
            linker.downcallHandle(
                linker.defaultLookup().find("getuid").orElseThrow { IOException("getuid not found") },
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
            )
        return getuid.invoke() as Int
    }

    internal fun windowsOwnerAccepted(
        ownerSid: String,
        userSid: String,
        processIsAdministrator: Boolean,
    ): Boolean =
        ownerSid.equals(userSid, ignoreCase = true) ||
            (ownerSid.equals(WindowsSecurity.ADMINISTRATORS_SID, ignoreCase = true) && processIsAdministrator)

    /** The owner-only DACL in SDDL for [userSid]; `SY` is SYSTEM, `BA` Administrators, `FA` full control. */
    internal fun ownerOnlySddl(
        userSid: String,
        directory: Boolean,
    ): String {
        val inherit = if (directory) "OICI" else ""
        return "D:P" + listOf(userSid, "SY", "BA").joinToString("") { "(A;$inherit;FA;;;$it)" }
    }

    /**
     * True when [current] is protected and holds exactly the access entries of [desired] (both as
     * Windows writes SDDL back). An inherited entry carries the `ID` flag and so never matches.
     */
    internal fun daclIsOwnerOnly(
        current: String,
        desired: String,
    ): Boolean {
        val flags = current.substringAfter("D:", "").substringBefore('(')
        return 'P' in flags && aces(current) == aces(desired)
    }

    private fun aces(dacl: String): Set<String> = Regex("""\(([^()]*)\)""").findAll(dacl).map { it.groupValues[1] }.toSet()

    private fun requireRestricted(
        path: Path,
        outcome: Outcome,
    ) {
        val reason =
            when (outcome) {
                is Outcome.Failed -> outcome.reason
                is Outcome.Unsupported -> outcome.reason
                else -> return
            }
        throw IOException("Could not restrict $path to the current user: $reason")
    }

    private fun isWindows(path: Path): Boolean =
        path.fileSystem == FileSystems.getDefault() &&
            System.getProperty("os.name", "").lowercase().contains("win")

    private fun restrict(
        path: Path,
        directory: Boolean,
    ): Outcome =
        try {
            if (isWindows(path)) restrictWindows(path, directory) else restrictPosix(path, directory)
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e.javaClass.name)
        } catch (e: LinkageError) {
            Outcome.Failed(e.message ?: e.javaClass.name)
        }

    private fun restrictPosix(
        path: Path,
        directory: Boolean,
    ): Outcome {
        val view =
            Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
                ?: return Outcome.Unsupported("no POSIX permissions or Windows ACLs on ${path.fileSystem}")
        if (view.readAttributes().permissions() == (if (directory) ownerDirectory else ownerFile)) return Outcome.Unchanged
        setPosixPermissionsIfSupported(path, ownerRwx = directory)
        return Outcome.Changed
    }

    private val desiredDirectoryDacl by lazy {
        WindowsSecurity.canonicalDacl(ownerOnlySddl(WindowsSecurity.currentUserSid, directory = true))
    }
    private val desiredFileDacl by lazy {
        WindowsSecurity.canonicalDacl(ownerOnlySddl(WindowsSecurity.currentUserSid, directory = false))
    }

    private fun restrictWindows(
        path: Path,
        directory: Boolean,
    ): Outcome {
        var changed = false
        fun restrictEntry(entry: Path, attrs: BasicFileAttributes) {
            if (attrs.isSymbolicLink || attrs.isOther) throw IOException("Refusing to restrict a link or junction: $entry")
            val outcome = restrictWindowsPath(entry, attrs.isDirectory)
            requireRestricted(entry, outcome)
            if (outcome == Outcome.Changed) changed = true
        }
        restrictEntry(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
        if (directory) {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != path) restrictEntry(dir, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    restrictEntry(file, attrs)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return if (changed) Outcome.Changed else Outcome.Unchanged
    }

    private fun restrictWindowsPath(
        path: Path,
        directory: Boolean,
    ): Outcome {
        val desired = if (directory) desiredDirectoryDacl else desiredFileDacl
        if (daclIsOwnerOnly(WindowsSecurity.read(path).dacl, desired)) return Outcome.Unchanged
        WindowsSecurity.setProtectedDacl(path, desired)
        val after = WindowsSecurity.read(path).dacl
        return if (daclIsOwnerOnly(after, desired)) Outcome.Changed else Outcome.Failed("the DACL reads back as $after")
    }
}
