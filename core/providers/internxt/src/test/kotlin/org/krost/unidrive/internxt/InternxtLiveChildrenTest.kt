package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class InternxtLiveChildrenTest {
    @Test
    fun `live folder listing matches sync live-item predicate`() {
        val content =
            FolderContentResponse(
                children =
                    listOf(
                        InternxtFolder(uuid = "live-folder", plainName = "live-folder"),
                        InternxtFolder(uuid = "trashed-folder", plainName = "trashed-folder", status = "TRASHED"),
                        InternxtFolder(uuid = "removed-folder", plainName = "removed-folder", removed = true),
                        InternxtFolder(uuid = "deleted-folder", plainName = "deleted-folder", deleted = true),
                    ),
                files =
                    listOf(
                        InternxtFile(uuid = "live-file", plainName = "live-file.txt"),
                        InternxtFile(uuid = "trashed-file", plainName = "trashed-file.txt", status = "TRASHED"),
                        InternxtFile(uuid = "removed-file", plainName = "removed-file.txt", removed = true),
                        InternxtFile(uuid = "deleted-file", plainName = "deleted-file.txt", deleted = true),
                    ),
            )

        val filtered = InternxtProvider.filterLiveContent(content)

        assertEquals(listOf("live-folder"), filtered.children.map { it.uuid })
        assertEquals(listOf("live-file"), filtered.files.map { it.uuid })
    }
}
