package org.krost.unidrive.internxt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.krost.unidrive.CloudItem
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import java.time.Instant

internal const val TREE_MARKER_STRATEGY: String = "tree"

// Staged rows record no strategy and the staging of a scan survives a switch of strategy, so the tree walk tags
// the rows it stages and resumes from those only. A folder row is tagged [TREE_STAGED_TAG] while the folder's
// own listing is outstanding and [TREE_DONE_TAG] once it is stored; the listing of a folder, the rows of its
// children and its done tag are stored in one transaction, so a tagged-pending folder never has children
// staged. A file row carries its modification time as the tag's suffix: the staging keeps no raw string, and the
// version token of a file is built from the string the server sent.
internal const val TREE_STAGED_TAG: String = "tree-listing"
internal const val TREE_DONE_TAG: String = "tree-listing|done"
private const val TREE_FILE_PREFIX: String = "tree-listing|"

internal fun treeFileTag(file: InternxtFile): String = TREE_FILE_PREFIX + file.modificationTime.orEmpty()

internal fun isTreeStaged(row: CloudItem): Boolean = row.hash == TREE_STAGED_TAG || row.hash?.startsWith(TREE_FILE_PREFIX) == true

/**
 * What the tree walk persists as the scan marker: the instant the walk began (the cursor of the final page, kept
 * across a resume), the scope roots it was started for, and the start folders whose listing is stored (a start
 * folder has no staged row of its own: the drive root has none, and the others are resolved again on resume).
 */
@Serializable
internal data class TreeMarker(
    val strategy: String,
    val startedAt: String,
    val roots: List<String>,
    val startsDone: List<String> = emptyList(),
)

private val treeMarkerJson = Json { encodeDefaults = true }

internal fun treeMarker(
    startedAt: String,
    roots: List<String>,
    startsDone: Collection<String>,
): String = treeMarkerJson.encodeToString(TreeMarker.serializer(), TreeMarker(TREE_MARKER_STRATEGY, startedAt, roots, startsDone.toList()))

internal fun decodeTreeMarker(raw: String?): TreeMarker? {
    if (raw.isNullOrBlank()) return null
    val marker =
        try {
            treeMarkerJson.decodeFromString<TreeMarker>(raw)
        } catch (_: IllegalArgumentException) {
            return null
        }
    if (marker.strategy != TREE_MARKER_STRATEGY) return null
    if (runCatching { Instant.parse(marker.startedAt) }.isFailure) return null
    return marker
}

/** What a resumed walk starts from: the rows it staged, which folders are listed, and the start folders listed. */
internal class TreeResume(
    val marker: TreeMarker,
    val files: List<InternxtFile>,
    val folders: List<InternxtFolder>,
    val listedFolders: Set<String>,
)

/**
 * The resume point of the stored scan, or null when the walk must start from the beginning: the marker is not
 * a tree marker, it was written for other scope roots, or it claims a finished start folder while no row this
 * strategy staged is there. A walk resumed without the rows it believes it has would leave out everything they
 * described, and a listing with items missing looks complete to the engine, which then reads their absence as
 * deletions. [staged] is every row the scan holds, whoever staged it.
 */
internal fun treeResumeOf(
    marker: String?,
    staged: List<CloudItem>,
    scopeRoots: List<String>,
): TreeResume? {
    val decoded = decodeTreeMarker(marker) ?: return null
    if (decoded.roots != scopeRoots) return null
    val mine = staged.filter(::isTreeStaged)
    if (decoded.startsDone.isNotEmpty() && mine.isEmpty()) return null
    val folderRows = mine.filter { it.isFolder }
    return TreeResume(
        marker = decoded,
        files = mine.filter { !it.isFolder }.map { it.toTreeResumedFile() },
        folders = folderRows.map { with(InternxtProvider) { it.toResumedFolder() } },
        listedFolders = folderRows.filter { it.hash == TREE_DONE_TAG }.mapTo(HashSet()) { it.id } + decoded.startsDone,
    )
}

private fun CloudItem.toTreeResumedFile(): InternxtFile =
    with(InternxtProvider) { toResumedFile() }.copy(modificationTime = hash?.removePrefix(TREE_FILE_PREFIX)?.takeIf { it.isNotEmpty() })
