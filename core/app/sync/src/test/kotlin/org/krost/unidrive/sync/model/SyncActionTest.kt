package org.krost.unidrive.sync.model

import org.krost.unidrive.CloudItem
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyncActionTest {
    private val testItem =
        CloudItem(
            id = "1",
            name = "test.txt",
            path = "/test.txt",
            size = 100,
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "abc123",
            mimeType = "text/plain",
        )

    @Test
    fun `actions expose path`() {
        val actions: List<SyncAction> =
            listOf(
                SyncAction.CreatePlaceholder("/a.txt", testItem, false),
                SyncAction.Upload("/b.txt"),
                SyncAction.DeleteLocal("/c.txt"),
                SyncAction.DeleteRemote("/d.txt"),
                SyncAction.RemoveEntry("/e.txt"),
            )
        assertEquals(listOf("/a.txt", "/b.txt", "/c.txt", "/d.txt", "/e.txt"), actions.map { it.path })
    }

    @Test
    fun `conflict carries policy and states`() {
        val conflict =
            SyncAction.Conflict(
                path = "/doc.txt",
                localState = ChangeState.MODIFIED,
                remoteState = ChangeState.MODIFIED,
                remoteItem = testItem,
                policy = ConflictPolicy.KEEP_BOTH,
            )
        assertIs<SyncAction>(conflict)
        assertEquals(ConflictPolicy.KEEP_BOTH, conflict.policy)
        assertEquals(ChangeState.MODIFIED, conflict.localState)
    }
}
