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
    ): Path = Pass(cacheDir).resolveInside(logicalPath)

    /**
     * Whether [candidate] (a cache path a client hands back) names a file strictly inside [cacheDir]. A spelling
     * that does not lead into the folder on normalised absolute paths does not count, however the tree resolves —
     * the same first check [resolveInside] makes. Inside it, the path is compared as far as it exists on real
     * paths: a link that leads out of the folder does not count, another spelling of a file inside does (letter
     * case on Windows, a redundant `.`). When [cacheDir] does not exist yet, nothing below it can either, and the
     * spelling decides.
     */
    fun isInside(
        cacheDir: Path,
        candidate: Path,
    ): Boolean = Pass(cacheDir).isInside(candidate)

    /**
     * The containment answers of one pass over the cache folder: the rules and the results of [isInside] and
     * [resolveInside], asked per row and remembered, instead of re-walked per candidate path.
     *
     * [isInside] answers every question from scratch: it walks a candidate up to its deepest existing prefix, and
     * on Windows every absent component it steps over is an internally thrown exception (a pair with the JDK's
     * translation of it). A pass over rows repeats that walk for every row, and repeats the probes of every
     * ancestor the rows share — the replay (#729) walked 137,534 rows that way for 341,451 recorded exceptions and
     * 13–17 minutes per start. This object remembers instead: the folder's real path is taken once, and every path
     * the pass probes is kept, present (through the real path it resolved to) or absent, so a second row below an
     * ancestor already probed costs a lookup. Absent answers are kept for the same reason: the row whose cache
     * copy is gone is the common case the walk used to pay for.
     *
     * The answers are those of the moment the pass probed a path, and of the folder's state at that moment. A pass
     * must not outlive the tree it examines (the replay walks the cache once, before the daemon serves writes), so
     * a path created or relinked while the pass runs is still answered as the pass found it.
     */
    class Pass(
        cacheDir: Path,
    ) {
        private val root: Path = cacheDir.toAbsolutePath().normalize()

        // The folder's own state, resolved the first time a caller's containment is actually evaluated: its real
        // path, or (when the folder is absent) the answer that the spelling decides.
        private var folderProbed = false
        private var realFolder: Path? = null
        private var folderMissing = false

        // Probed paths, through the real path they resolved to, and paths probed and found absent.
        private val present = HashMap<Path, Path>()
        private val absent = HashSet<Path>()

        /**
         * The cache file of [logicalPath] below the folder, normalised; the rules and the [SecurityException] of
         * [CachePaths.resolveInside], answered through this pass.
         */
        fun resolveInside(logicalPath: String): Path {
            val resolved = root.resolve(logicalPath.trimStart('/')).normalize()
            val inside =
                try {
                    val absolute = resolved.toAbsolutePath().normalize()
                    absolute.startsWith(root) && (absolute == root || isInside(resolved))
                } catch (_: java.io.IOError) {
                    // A drive-relative name ("x:name" on Windows) of a drive that does not exist.
                    false
                }
            if (!inside) throw SecurityException("path does not resolve inside the hydration cache: '${CachePaths.forLog(logicalPath)}'")
            return resolved
        }

        /** Whether [candidate] names a file strictly inside the folder — [CachePaths.isInside] through this pass. */
        fun isInside(candidate: Path): Boolean {
            val path =
                try {
                    candidate.toAbsolutePath()
                } catch (_: java.io.IOError) {
                    return false
                }
            val normalised = path.normalize()
            // Before any filesystem access, as [CachePaths.resolveInside] does: a spelling that does not lead into
            // the folder does not count, however the tree resolves, so a miss costs a comparison.
            if (normalised == root || !normalised.startsWith(root)) return false
            val realFolder = folderReal() ?: return folderMissing // absent folder: the spelling decides
            // The deepest part of [path] that exists (the file itself when it does), through its real path, and the
            // names below it as given. Probing is what this pass spares its callers: a path it has already looked
            // at is answered from [present] or [absent], without touching the filesystem again.
            val below = ArrayList<Path>()
            var real: Path
            var current: Path = path
            while (true) {
                val found = probe(current)
                if (found == Probe.UNRESOLVED) return false
                if (found == Probe.EXISTS) {
                    real = present.getValue(current)
                    break
                }
                val name = current.fileName ?: return false
                below.add(name)
                current = current.parent ?: return false
            }
            for (name in below.asReversed()) real = real.resolve(name)
            real = real.normalize()
            return real != realFolder && real.startsWith(realFolder)
        }

        /** What the pass found when it probed a path. */
        private enum class Probe { EXISTS, ABSENT, UNRESOLVED }

        /**
         * What the pass finds at [path], remembered so a second caller does not touch the filesystem again:
         * [Probe.EXISTS] (the real path is in [present]), [Probe.ABSENT], or [Probe.UNRESOLVED] for a path that is
         * there but cannot be resolved — what the walk gives up on, as [CachePaths.isInside] does.
         */
        private fun probe(path: Path): Probe {
            present[path]?.let { return Probe.EXISTS }
            if (path in absent) return Probe.ABSENT
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                absent.add(path)
                return Probe.ABSENT
            }
            val real =
                try {
                    path.toRealPath()
                } catch (_: IOException) {
                    return Probe.UNRESOLVED
                }
            present[path] = real
            return Probe.EXISTS
        }

        // The folder's real path, or null when it is absent ([folderMissing] decides the lexical answer) or cannot
        // be read at all (no path below it can be checked, as in [CachePaths.isInside]).
        private fun folderReal(): Path? {
            if (!folderProbed) {
                folderProbed = true
                var real: Path? = null
                var missing = false
                try {
                    real = root.toRealPath()
                } catch (_: NoSuchFileException) {
                    missing = true
                } catch (_: IOException) {
                    // Unreadable: nothing below it can be checked either.
                }
                realFolder = real
                folderMissing = missing
            }
            return realFolder
        }
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
