package org.krost.unidrive.sync.ignore

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** What [IgnoreFileSource.probe] found at a rule file's path. */
sealed interface RuleFileProbe {
    /** No rule file there (nothing, or a directory of that name). */
    data object Absent : RuleFileProbe

    /** A regular file. Size and modification time are its stamp: a different stamp means "read it again". */
    data class Present(
        val size: Long,
        val modified: Long,
    ) : RuleFileProbe

    /** There, but not to be read: a symbolic link, or a file outside the root. Treated as absent, and reported. */
    data class NotFollowed(
        val reason: String,
    ) : RuleFileProbe

    /** There, but it cannot be checked. Everything below its directory is held back (ignored) and it is reported. */
    data class Unreadable(
        val reason: String,
    ) : RuleFileProbe
}

/**
 * Where an [IgnoreTree] reads rule files. Paths are relative to the sync root, `/`-separated and canonical.
 *
 * The mount front-end needs a source of its own: reading a rule file through a cloud placeholder must not hydrate it.
 */
interface IgnoreFileSource {
    fun probe(path: String): RuleFileProbe

    /** The file's bytes, or null when it has more than [maxBytes] bytes. Throws [IOException] when it cannot be read. */
    fun read(
        path: String,
        maxBytes: Long,
    ): ByteArray?
}

/**
 * Rule files on the local file system below [root]. A symbolic link is never followed (git does not follow a
 * symlinked `.gitignore` either), and a file whose real path is outside the root (reached through a linked
 * directory or a junction) is not read.
 */
class FileSystemIgnoreSource(
    root: Path,
) : IgnoreFileSource {
    private val root: Path = root.toAbsolutePath().normalize()
    private val realRoot: Path by lazy { this.root.toRealPath() }

    override fun probe(path: String): RuleFileProbe {
        val file = resolve(path) ?: return RuleFileProbe.Absent
        val attrs =
            try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (ignored: NoSuchFileException) {
                return RuleFileProbe.Absent
            } catch (e: IOException) {
                // A path through a regular file (ENOTDIR on POSIX, a plain FileSystemException there): no such file.
                val parentIsDir = Files.isDirectory(file.parent ?: root, LinkOption.NOFOLLOW_LINKS)
                return if (parentIsDir) RuleFileProbe.Unreadable(e.toString()) else RuleFileProbe.Absent
            }
        return when {
            attrs.isSymbolicLink -> RuleFileProbe.NotFollowed("a symbolic link")
            attrs.isDirectory -> RuleFileProbe.Absent
            !attrs.isRegularFile -> RuleFileProbe.NotFollowed("not a regular file")
            else -> contained(file) ?: RuleFileProbe.Present(attrs.size(), attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS))
        }
    }

    override fun read(
        path: String,
        maxBytes: Long,
    ): ByteArray? {
        val file = resolve(path) ?: throw NoSuchFileException(path)
        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
            val limit = maxBytes.coerceAtMost(Int.MAX_VALUE - 1L).toInt()
            val bytes = input.readNBytes(limit + 1)
            return if (bytes.size > limit) null else bytes
        }
    }

    /** Null when [file]'s real path is inside the root, otherwise why it is not followed. */
    private fun contained(file: Path): RuleFileProbe? {
        val real =
            try {
                file.toRealPath()
            } catch (e: IOException) {
                return RuleFileProbe.Unreadable(e.toString())
            }
        return if (real.startsWith(realRoot)) null else RuleFileProbe.NotFollowed("outside the sync root")
    }

    /** Null for a name this file system cannot spell (it cannot exist here). */
    private fun resolve(path: String): Path? {
        // On Windows a backslash would be a separator and a colon a drive or a stream: no such name exists there.
        if (File.separatorChar == '\\' && (path.contains('\\') || path.contains(':'))) return null
        return try {
            root.resolve(path)
        } catch (ignored: InvalidPathException) {
            null
        }
    }
}
