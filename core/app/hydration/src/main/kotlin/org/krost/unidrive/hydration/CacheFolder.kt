package org.krost.unidrive.hydration

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Whether [candidate], a cache path a client hands back to `hydration.open_write`, names a file strictly inside
 * [cacheDir], the profile's hydration cache folder. Compared on normalised absolute paths and, as far as the path
 * exists, on real paths: a link inside the folder that leads out of it does not count, another spelling of a file
 * inside does (letter case on Windows, a short name, a redundant segment). When [cacheDir] does not exist yet,
 * nothing below it can either, and the spelling decides.
 */
internal fun isInsideCacheFolder(
    cacheDir: Path,
    candidate: Path,
): Boolean {
    val root = cacheDir.toAbsolutePath().normalize()
    val path =
        try {
            candidate.toAbsolutePath()
        } catch (_: java.io.IOError) {
            return false
        }
    val realRoot =
        try {
            root.toRealPath()
        } catch (_: NoSuchFileException) {
            val normalised = path.normalize()
            return normalised != root && normalised.startsWith(root)
        } catch (_: IOException) {
            return false
        }
    // The deepest part of [path] that exists (the file itself when it does), through its real path, and the names
    // below it as given.
    var existing: Path = path
    val rest = ArrayList<Path>()
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        val name = existing.fileName ?: return false
        rest.add(name)
        existing = existing.parent ?: return false
    }
    var real =
        try {
            existing.toRealPath()
        } catch (_: IOException) {
            return false
        }
    for (name in rest.asReversed()) real = real.resolve(name)
    real = real.normalize()
    return real != realRoot && real.startsWith(realRoot)
}

/** [text] for a log line: control characters shown as `?`, at most 200 characters. */
internal fun forLogLine(text: String): String {
    val shown = if (text.length > 200) text.take(200) + "..." else text
    return buildString(shown.length) {
        for (c in shown) append(if (c.code < 0x20 || c.code == 0x7F) '?' else c)
    }
}
