package org.krost.unidrive.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The deletion half of `profile remove`: removes a profile folder's contents without ever following a link,
 * and without touching the local sync root or mount root.
 */
internal object ProfilePurge {
    fun deleteQuietly(
        path: Path,
        warnings: MutableList<String>,
    ): Boolean =
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            warnings += "${path.fileName}: could not be deleted (${e.javaClass.simpleName})"
            false
        }

    /** The local root that must survive the removal, as a lexical and (when it exists) a resolved path. */
    class ProtectedRoot(
        private val lexical: Path?,
        private val real: Path?,
    ) {
        /** [dir] is the root's own path, without resolving links. */
        fun isLexicalRoot(dir: Path): Boolean = dir.toAbsolutePath().normalize() == lexical

        /** [dir] is the root itself. */
        fun isRoot(dir: Path): Boolean = matches(dir) { it == lexical || it == real }

        /** [dir] holds the root somewhere below it (or is the root). */
        fun covers(dir: Path): Boolean =
            matches(dir) { d -> (lexical?.startsWith(d) ?: false) || (real?.startsWith(d) ?: false) }

        private fun matches(
            dir: Path,
            test: (Path) -> Boolean,
        ): Boolean {
            val lex = dir.toAbsolutePath().normalize()
            return test(lex) || runCatching { test(dir.toRealPath()) }.getOrDefault(false)
        }

        companion object {
            fun of(syncRoot: String): ProtectedRoot {
                val lex = runCatching { Path.of(syncRoot).toAbsolutePath().normalize() }.getOrNull()
                val real = lex?.let { runCatching { it.toRealPath() }.getOrNull() }
                return ProtectedRoot(lex, real)
            }
        }
    }

    /**
     * Deletes [path] and everything below it except the protected root and what holds it. Returns true when [path] is gone.
     * A failed delete is recorded in [warnings] and never aborts the rest. The process lock files of the top
     * folder are left for the caller, which still holds the lock.
     */
    fun purge(
        path: Path,
        root: ProtectedRoot,
        warnings: MutableList<String>,
        isTop: Boolean = false,
    ): Boolean {
        val attrs =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                warnings += "state: ${path.fileName}: could not be read (${e.javaClass.simpleName})"
                return false
            }
        // A link, junction or other reparse point is a leaf: only the entry goes, never what it points to.
        if (isReparsePoint(path, attrs)) return deleteLeaf(path, root, warnings)
        val isDir = attrs.isDirectory
        if (isDir && root.isRoot(path)) return false
        if (isDir) {
            val children =
                try {
                    Files.list(path).use { it.toList() }
                } catch (e: IOException) {
                    warnings += "state: ${path.fileName}: could not be listed (${e.javaClass.simpleName})"
                    return false
                }
            var all = true
            for (child in children) {
                if (isTop && (child.fileName.toString() == ".lock" || child.fileName.toString() == ".lock.pid")) {
                    all = false
                    continue
                }
                if (!purge(child, root, warnings)) all = false
            }
            if (!all || root.covers(path)) return false
            if (isTop) return false // the caller deletes the folder once the lock is released
        }
        return deleteEntry(path, warnings)
    }

    /** Deletes the link entry [path] unless it is the protected root itself. */
    private fun deleteLeaf(
        path: Path,
        root: ProtectedRoot,
        warnings: MutableList<String>,
    ): Boolean = if (root.isLexicalRoot(path)) false else deleteEntry(path, warnings)

    private fun deleteEntry(
        path: Path,
        warnings: MutableList<String>,
    ): Boolean =
        try {
            Files.deleteIfExists(path)
            true
        } catch (e: IOException) {
            warnings += "state: ${path.fileName}: could not be deleted (${e.javaClass.simpleName})"
            false
        }

    /**
     * True for a symbolic link, any other special entry, or a directory that is redirected: its real path differs from its
     * parent's real path plus its own name, which is how a Windows junction (a mount point, which `isDirectory` reports as a
     * plain directory) shows. Comparing against the parent's real path keeps 8.3 names and letter case out of the test.
     */
    private fun isReparsePoint(
        path: Path,
        attrs: BasicFileAttributes,
    ): Boolean {
        if (attrs.isSymbolicLink || attrs.isOther) return true
        if (!attrs.isDirectory) return false
        return try {
            val parent = path.toAbsolutePath().parent ?: return false
            path.toRealPath() != parent.toRealPath().resolve(path.fileName)
        } catch (_: IOException) {
            // Cannot prove it is a plain directory: do not recurse into it.
            true
        }
    }
}
