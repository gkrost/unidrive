package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A case collision between two local files is refused by the reconciler as a [SyncAction.Conflict]
 * with no remote item (local NEW, remote unchanged). Applying that conflict must never transfer
 * the file, whatever the conflict policy: last-writer-wins used to upload it (no remote mtime
 * means the local side wins), and on a case-insensitive remote the second member's upload then
 * overwrote the first member's cloud content.
 */
class RefusedCaseCollisionTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-collision-root")
        db = StateDatabase(Files.createTempDirectory("ud-collision-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(policy: ConflictPolicy) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = policy,
            reporter = ProgressReporter.Silent,
        )

    private suspend fun refuse(policy: ConflictPolicy) {
        Files.writeString(syncRoot.resolve("Report.pdf"), "local bytes")
        engine(policy).applyConflict(
            SyncAction.Conflict(
                path = "/Report.pdf",
                localState = ChangeState.NEW,
                remoteState = ChangeState.UNCHANGED,
                remoteItem = null,
                policy = policy,
            ),
        )
    }

    @Test
    fun `keep-both refuses the collided file without uploading it`() =
        runTest {
            refuse(ConflictPolicy.KEEP_BOTH)

            assertEquals(emptyList(), provider.uploadedPaths)
            assertTrue(Files.isRegularFile(syncRoot.resolve("Report.pdf")), "the local file stays")
        }

    @Test
    fun `last-writer-wins refuses the collided file without uploading it`() =
        runTest {
            refuse(ConflictPolicy.LAST_WRITER_WINS)

            assertEquals(emptyList(), provider.uploadedPaths)
            assertTrue(Files.isRegularFile(syncRoot.resolve("Report.pdf")), "the local file stays")
        }
}
