package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.InternxtProvider.Companion.isLive
import org.krost.unidrive.internxt.model.InternxtFolder

/**
 * Which items of a listing sit in the trash.
 *
 * Internxt trashes a folder and leaves what is in it as it was: the items below keep reporting `EXISTS` (and name the
 * trashed folder as their parent), so a listing of the live items carries them like any other. A folder is **in the
 * trash** when its own status is not `EXISTS` or when a folder above it is not; an item is in the trash when its parent
 * folder is. The status is the server's own word for it, and it is read the same way for a folder the listing returned
 * and for one fetched by its meta, which answers HTTP 200 for a trashed folder like for a live one.
 *
 * [folderMap] holds every folder the paths run through: the folders of the listing, the trashed folders it saw, the
 * ones fetched. [trashedFolders] collects the folders that are not live and that some item hangs below, in the order they
 * were met: what the trash namespace needs to show the way to those items.
 */
internal class TrashChains(
    private val folderMap: Map<String, InternxtFolder>,
    private val rootUuid: String,
) {
    private val memo = HashMap<String, Boolean>()
    private val above = LinkedHashMap<String, InternxtFolder>()

    /** The folders that are not live and that an item asked about hangs below, directly or through other folders. */
    val trashedFolders: Collection<InternxtFolder> get() = above.values

    /** True when a folder [folderUuid], or a folder above it, is not live. A folder this map does not hold cannot be told: false. */
    fun contains(folderUuid: String?): Boolean {
        val chain = ArrayList<String>()
        val seen = HashSet<String>()
        var uuid = folderUuid
        var inTrash = false
        while (uuid != null && uuid != rootUuid) {
            val known = memo[uuid]
            if (known != null) {
                inTrash = known
                break
            }
            val folder = folderMap[uuid]
            // A parent chain that comes back to itself is damage; it is not walked twice.
            if (folder == null || !seen.add(uuid)) break
            chain.add(uuid)
            uuid = folder.parentUuid
        }
        // From the top of the chain down: a folder is in the trash when it is not live or the one above it is.
        for (member in chain.asReversed()) {
            val folder = folderMap.getValue(member)
            if (!folder.isLive()) {
                inTrash = true
                above.putIfAbsent(member, folder)
            }
            memo[member] = inTrash
        }
        return inTrash
    }
}
