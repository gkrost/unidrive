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

    /**
     * Validates and normalises a configured `sync_path` for [profile]. Unlike
     * the CLI, config must spell absolute remote paths: each entry starts with
     * `/`, uses forward slashes, and has no `..` segment or control character.
     */
    fun fromConfig(
        raw: List<String>?,
        profile: String,
    ): List<String> {
        if (raw == null) return emptyList()
        for (entry in raw) {
            val problem =
                when {
                    entry.isBlank() -> "is empty"
                    !entry.startsWith("/") -> "is not an absolute remote path (it must start with '/')"
                    '\\' in entry -> "contains a backslash (remote paths use '/')"
                    entry.any { it.isISOControl() } -> "contains a control character"
                    entry.split('/').any { it == ".." } -> "contains a '..' segment"
                    else -> null
                }
            if (problem != null) {
                throw IllegalArgumentException("config.toml [providers.$profile] sync_path entry '$entry' $problem")
            }
        }
        return normalize(raw)
    }

    fun contains(
        path: String,
        roots: List<String>,
    ): Boolean = roots.isEmpty() || roots.any { path == it || path.startsWith("$it/") }

    /**
     * Top-level on-disk entries under [syncRoot] that lie outside [roots]: the
     * content the engine leaves alone. Folders leading to a root are entered, not
     * reported; anything else outside the scope is reported as one path without
     * descending. [isExcluded] takes the remote-style path (`/name`).
     */
    fun outOfScopeLocal(
        syncRoot: java.nio.file.Path,
        roots: List<String>,
        isExcluded: (String) -> Boolean = { false },
    ): List<String> {
        if (roots.isEmpty() || !java.nio.file.Files.isDirectory(syncRoot)) return emptyList()
        val ancestors = ancestors(roots)
        val found = mutableListOf<String>()

        fun walk(dir: java.nio.file.Path, remoteDir: String) {
            val children =
                java.nio.file.Files.list(dir).use { stream -> stream.toList() }
                    .sortedBy { it.fileName.toString() }
            for (child in children) {
                val remote = (if (remoteDir == "/") "" else remoteDir) + "/" + child.fileName
                when {
                    isExcluded(remote) -> Unit
                    contains(remote, roots) -> Unit
                    remote in ancestors && java.nio.file.Files.isDirectory(child) -> walk(child, remote)
                    else -> found += remote
                }
            }
        }
        walk(syncRoot, "/")
        return found
    }

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
