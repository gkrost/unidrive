package org.krost.unidrive.sync

/**
 * Remote subtree scope: a list of absolute remote paths. An empty list means
 * unscoped (the whole drive). Every entry is matched on a path boundary, so
 * `/foo` never admits `/footer.txt`.
 */
object SyncScope {
    /**
     * Do-what-I-mean path cleanup: backslashes become slashes, separator runs
     * collapse, a leading slash is added and a trailing one dropped. Returns
     * null for null, empty, and the drive root.
     */
    fun normalizePath(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        var s = raw.replace('\\', '/')
        s = s.replace(Regex("/+"), "/")
        if (!s.startsWith("/")) s = "/$s"
        if (s.length > 1 && s.endsWith("/")) s = s.removeSuffix("/")
        return if (s == "/") null else s
    }

    /**
     * Normalises every entry, ignores empty ones, and drops entries nested
     * under another, then returns the rest sorted. A root entry (`/`) makes the
     * whole list unscoped.
     */
    fun normalize(raw: List<String>): List<String> {
        val normalized = mutableListOf<String>()
        for (entry in raw) {
            if (entry.isEmpty()) continue
            normalizePath(entry)?.let { normalized += it } ?: return emptyList()
        }
        val kept = mutableListOf<String>()
        for (path in normalized.distinct().sortedBy { it.length }) {
            if (kept.none { contains(path, listOf(it)) }) kept += path
        }
        return kept.sorted()
    }

    fun contains(
        path: String,
        roots: List<String>,
    ): Boolean = roots.isEmpty() || roots.any { path == it || path.startsWith("$it/") }

    /** Strict ancestors of every root, e.g. `/a/b` yields `/a`. */
    fun ancestors(roots: List<String>): Set<String> {
        val result = mutableSetOf<String>()
        for (root in roots) {
            val parts = root.trimStart('/').split('/')
            for (i in 1 until parts.size) {
                result.add("/" + parts.subList(0, i).joinToString("/"))
            }
        }
        return result
    }
}
