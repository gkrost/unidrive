package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The trash namespace of one profile: a virtual top-level folder that shows what the provider keeps in its trash
 * apart from the live tree, and that the engine never writes to.
 *
 * **What goes in.** A provider marks an item [CloudItem.trashed] when it is live but sits in the provider's trash
 * (Internxt: a folder above it was trashed). [place] gives such an item the path `/<root><original path>`, so the user
 * sees where it came from, and adds the root folder itself. The item keeps its id: an item that an earlier version
 * of the engine stored at its original path arrives under the new path with the same id, which the engine's rename
 * machinery turns into a move of the row, never into a delete and a create.
 *
 * **The root.** It has a stable internal key, [ROOT_ID] (the remote id of its row), and a display and on-disk name: the
 * profile setting `trash_folder_name`, [DEFAULT_NAME] when unset. It exists as a row only while something is in the trash.
 * A live top-level item that already has that name keeps it: the root takes the first free `<name> (2)`, `(3)`, ... and
 * the engine says so once.
 *
 * **Read-only.** [contains] is the one question the guarantee rests on: a path is in the namespace when it is the root
 * or lies below it. The engine never sends an upload, a delete, a move or a rename to the cloud for such a path
 * ([ReadOnlyTrashProvider], and the checks of the planner and the mount verbs before it); local changes there are
 * ignored; reading, hydrating and copying out stay allowed.
 */
class TrashNamespace(
    configuredName: String? = null,
    private val ignoreCase: Boolean = System.getProperty("os.name", "").lowercase().contains("win"),
    private val log: Logger = LoggerFactory.getLogger(TrashNamespace::class.java),
) {
    /** The name the profile asks for: its `trash_folder_name`, or [DEFAULT_NAME] when that is unset or not a usable name. */
    val configuredName: String =
        when {
            configuredName == null -> DEFAULT_NAME
            isUsableName(configuredName) -> PathNormalizer.nfc(configuredName.trim())
            else -> {
                log.warn("trash_folder_name '{}' is not a usable folder name; using '{}'", configuredName, DEFAULT_NAME)
                DEFAULT_NAME
            }
        }

    // The path of the root while the namespace exists ("/Papierkorb"), null while nothing is in the trash.
    @Volatile
    private var root: String? = null

    // The remote ids of the items placed under the root in this process. A provider call that names one of them by
    // id is refused whatever path it carries: a row that has not been moved yet still addresses the item by its old place.
    private val trashedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var warnedCollision: String? = null

    /** The path of the root folder, or null while the namespace does not exist. */
    fun rootPath(): String? = root

    /** True when [path] is the root or lies below it. */
    fun contains(path: String): Boolean {
        val r = root ?: return false
        if (path.length < r.length) return false
        if (!path.regionMatches(0, r, 0, r.length, ignoreCase)) return false
        return path.length == r.length || path[r.length] == '/'
    }

    /** True when [path] is the root folder itself. */
    fun isRoot(path: String): Boolean = root?.equals(path, ignoreCase) == true

    /** True when [remoteId] belongs to an item this process has placed in the namespace. */
    fun holdsId(remoteId: String?): Boolean = remoteId != null && (remoteId == ROOT_ID || remoteId in trashedIds)

    /** Reads the root's place from state.db: the row with the key [ROOT_ID], null when there is none. */
    fun refresh(db: StateDatabase) {
        root = db.getEntryByRemoteId(ROOT_ID)?.path
    }

    /**
     * The items of a page with the trashed ones placed under the root, and the root folder added. A page with
     * no trashed item comes back as it is. [topLevelNames] answers the names of the live top-level items that the page
     * does not carry (state.db's), so a collision with one of them is seen on an incremental page too.
     */
    fun place(
        items: List<CloudItem>,
        topLevelNames: () -> Collection<String>,
    ): List<CloudItem> {
        val inTrash = items.filter { it.trashed && !it.deleted }
        if (trashedIds.isNotEmpty()) for (item in items) if (!item.trashed && !item.deleted) trashedIds.remove(item.id)
        if (inTrash.isEmpty()) return items
        val name = rootName(items, topLevelNames)
        val rootPath = "/$name"
        root = rootPath
        val placed = items.map { item -> if (item.trashed && !item.deleted) relocate(item, rootPath) else item }
        for (item in inTrash) trashedIds.add(item.id)
        return placed +
            CloudItem(
                id = ROOT_ID,
                name = name,
                path = rootPath,
                size = 0,
                isFolder = true,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                trashed = true,
            )
    }

    private fun relocate(
        item: CloudItem,
        rootPath: String,
    ): CloudItem {
        val topLevel = item.path.count { it == '/' } <= 1
        return item.copy(path = rootPath + item.path, parentId = if (topLevel) ROOT_ID else item.parentId)
    }

    // The first of <name>, <name> (2), <name> (3), ... that no live top-level item has. The items of the page that are
    // trashed are below the root and never compete for the name; the root's own row is not a live item either.
    private fun rootName(
        items: List<CloudItem>,
        topLevelNames: () -> Collection<String>,
    ): String {
        val taken = HashSet<String>()
        for (item in items) {
            if (item.trashed || item.deleted || item.id == ROOT_ID) continue
            if (item.path.count { it == '/' } == 1) taken.add(fold(item.path.substring(1)))
        }
        val known = topLevelNames()
        known.mapTo(taken) { fold(it) }
        var candidate = configuredName
        var n = 1
        while (fold(candidate) in taken) {
            n++
            candidate = "$configuredName ($n)"
        }
        if (candidate != configuredName && warnedCollision != candidate) {
            warnedCollision = candidate
            log.warn("A top-level item is already named '{}': the trash is shown as '{}'", configuredName, candidate)
        }
        return candidate
    }

    private fun fold(name: String): String = if (ignoreCase) name.lowercase() else name

    companion object {
        /** The remote id of the root's row: the stable internal key, whatever the root is called. */
        const val ROOT_ID: String = "unidrive:trash"

        /** The name of the root when the profile does not set one. */
        const val DEFAULT_NAME: String = "Papierkorb"

        private const val MAX_NAME_CHARS = 100
        private val FORBIDDEN = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

        /**
         * Whether [name] can be the name of the root: one path segment, as it is written on every platform (no
         * separator, none of the characters Windows refuses, no control character, no `.` or `..`, no trailing
         * dot), not blank once trimmed, not longer than [MAX_NAME_CHARS].
         */
        fun isUsableName(name: String): Boolean {
            val n = name.trim()
            if (n.isEmpty() || n.length > MAX_NAME_CHARS) return false
            if (n == "." || n == ".." || n.endsWith('.')) return false
            return n.none { it in FORBIDDEN || it < ' ' || it == '\u007f' }
        }
    }
}
