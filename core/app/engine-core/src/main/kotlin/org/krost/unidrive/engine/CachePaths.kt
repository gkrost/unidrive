package org.krost.unidrive.engine

import org.slf4j.Logger
import java.nio.file.InvalidPathException
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
     * reads as a drive, a share or a parent folder. A segment the platform cannot name at all still throws
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
                resolved.toAbsolutePath().normalize().startsWith(root)
            } catch (_: java.io.IOError) {
                // A drive-relative name ("x:name" on Windows) of a drive that does not exist.
                false
            }
        if (!inside) throw SecurityException("path does not resolve inside the hydration cache: '${forLog(logicalPath)}'")
        return resolved
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
