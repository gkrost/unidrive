package org.krost.unidrive.engine

import org.slf4j.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The containment rules of a profile's hydration cache folder (`<cacheRoot>/unidrive/hydration/<cacheKey>`): every
 * cache file the engine resolves for a logical path lies inside it. One place for the host that lays the cache out
 * (`SyncEngine.resolveCachePath`) and the passes over rows (the enumeration's reap, the cache budget).
 */
object CachePaths {
    // Paths already warned about by [forRow]: a pass that runs every poll interval logs each one once.
    private val warnedRows: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private const val LOG_MAX_CHARS = 200

    /**
     * The cache file of [logicalPath] below [cacheDir], normalised. Throws [SecurityException] when the result is
     * not [cacheDir] itself or inside it: a `..` segment that climbs out, or (on Windows) a segment the platform
     * reads as a drive, a share or a parent folder, or a link whose target lies outside the cache. Existing
     * ancestors are resolved through their real paths before a new file is accepted. A segment the platform cannot name at all still throws
     * [InvalidPathException], as before.
     */
    fun resolveInside(
        cacheDir: Path,
        logicalPath: String,
    ): Path {
        val resolved = cacheDir.resolve(logicalPath.trimStart('/')).normalize()
        val root = cacheDir.toAbsolutePath().normalize()
        val inside =
            try {
                val absolute = resolved.toAbsolutePath().normalize()
                absolute.startsWith(root) && (absolute == root || isInside(cacheDir, resolved))
            } catch (_: java.io.IOError) {
                // A drive-relative name ("x:name" on Windows) of a drive that does not exist.
                false
            }
        if (!inside) throw SecurityException("path does not resolve inside the hydration cache: '${forLog(logicalPath)}'")
        return resolved
    }

    /**
     * Whether [candidate] (a cache path a client hands back) names a file strictly inside [cacheDir]. Compared on
     * normalised absolute paths and, as far as the path exists, on real paths: a link inside the folder that leads
     * out of it does not count, another spelling of a file inside does (letter case on Windows, a short name, a
     * redundant `.`). When [cacheDir] does not exist yet, nothing below it can either, and the spelling decides.
     */
    fun isInside(
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
        // The deepest part of [path] that exists (the file itself when it does), through its real path, and the
        // names below it as given.
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

    /**
     * For passes over rows: the cache file of [path] through [resolve], or null when the path does not resolve
     * inside the cache ([SecurityException]) or names nothing the platform can hold ([InvalidPathException]). Such
     * a row has no cache copy to read, keep or evict; the pass skips its cache side. Logged at WARN once per path.
     */
    fun forRow(
        path: String,
        log: Logger,
        resolve: (String) -> Path,
    ): Path? =
        try {
            resolve(path)
        } catch (e: SecurityException) {
            warnOnce(path, log)
            null
        } catch (e: InvalidPathException) {
            warnOnce(path, log)
            null
        }

    private fun warnOnce(
        path: String,
        log: Logger,
    ) {
        if (warnedRows.add(path)) {
            log.warn("skipping the hydration cache of a row whose path does not resolve inside it: '{}'", forLog(path))
        }
    }

    /** [path] for a log line: control characters shown as `?`, at most [LOG_MAX_CHARS] characters. */
    fun forLog(path: String): String {
        val shown = if (path.length > LOG_MAX_CHARS) path.take(LOG_MAX_CHARS) + "..." else path
        return buildString(shown.length) {
            for (c in shown) append(if (c.code < 0x20 || c.code == 0x7F) '?' else c)
        }
    }
}
