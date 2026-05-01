package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class ReconcilerTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path
    private lateinit var reconciler: Reconciler

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-recon-test")
        val dbPath = Files.createTempDirectory("unidrive-recon-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
        hash: String? = "abc",
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = "text/plain",
        deleted = deleted,
    )

    private fun dbEntry(
        path: String,
        remoteHash: String = "abc",
        isHydrated: Boolean = false,
        isPinned: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = remoteHash,
        remoteSize = 100,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = 1711627200000,
        localSize = 100,
        isFolder = false,
        isPinned = isPinned,
        isHydrated = isHydrated,
        lastSynced = Instant.now(),
    )

    @Test
    fun `remote new file downloads content`() {
        // UD-222: remote-new non-folder always emits DownloadContent (Pass 2 concurrent).
        val remoteChanges = mapOf("/new.txt" to cloudItem("/new.txt"))
        val localChanges = emptyMap<String, ChangeState>()
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DownloadContent>(actions[0])
        assertEquals("/new.txt", actions[0].path)
    }

    @Test
    fun `remote new file with pin rule still downloads content`() {
        // UD-222: pin rules no longer gate hydration; all remote-new non-folders download.
        db.addPinRule("*.txt", pinned = true)
        val remoteChanges = mapOf("/doc.txt" to cloudItem("/doc.txt"))
        val localChanges = emptyMap<String, ChangeState>()
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        val action = actions[0]
        assertIs<SyncAction.DownloadContent>(action)
    }

    @Test
    fun `remote modified re-downloads content`() {
        // UD-222: remote-modified non-folder always emits DownloadContent (no NUL stubs).
        db.upsertEntry(dbEntry("/mod.txt", remoteHash = "old-hash"))
        val remoteChanges = mapOf("/mod.txt" to cloudItem("/mod.txt", hash = "new-hash"))
        val localChanges = emptyMap<String, ChangeState>()
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DownloadContent>(actions[0])
    }

    @Test
    fun `remote deleted removes local`() {
        db.upsertEntry(dbEntry("/del.txt"))
        val remoteChanges = mapOf("/del.txt" to cloudItem("/del.txt", deleted = true))
        val localChanges = emptyMap<String, ChangeState>()
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DeleteLocal>(actions[0])
    }

    @Test
    fun `local new file uploads`() {
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/local-new.txt" to ChangeState.NEW)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.Upload>(actions[0])
    }

    @Test
    fun `local modified uploads`() {
        db.upsertEntry(dbEntry("/mod.txt", isHydrated = true))
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/mod.txt" to ChangeState.MODIFIED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.Upload>(actions[0])
    }

    @Test
    fun `local deleted deletes remote`() {
        db.upsertEntry(dbEntry("/del.txt"))
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/del.txt" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DeleteRemote>(actions[0])
    }

    @Test
    fun `both modified with different remote hash is Conflict`() {
        // Invariant: if the remote hash has moved (between last-sync and now)
        // AND the local file has been modified, the reconciler refuses to
        // choose a winner and emits a Conflict. The user (or the configured
        // ConflictPolicy) resolves.
        //
        // UD-800: this test used to be named `both modified with same hash is
        // no-op` but the body asserted the OPPOSITE — hashes in the fixture
        // are "old" vs "new-hash" (different), and the assertion was Conflict.
        // The promised same-hash-no-op invariant is not implementable at this
        // layer — reconcile() sees `ChangeState.MODIFIED` from the local side
        // but does not have the local content hash. The "both-sides-settled-
        // on-the-same-content" no-op would need a pre-reconcile local hash
        // pass; filed as follow-up in the closed-note of UD-800.
        db.upsertEntry(dbEntry("/same.txt", remoteHash = "old"))
        val remoteChanges = mapOf("/same.txt" to cloudItem("/same.txt", hash = "new-hash"))
        val localChanges = mapOf("/same.txt" to ChangeState.MODIFIED)
        Files.createDirectories(syncRoot)
        Files.writeString(syncRoot.resolve("same.txt"), "content")
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.Conflict>(actions[0])
    }

    @Test
    fun `both modified with same remote hash emits Upload (local-only path)`() {
        // Companion to `both modified with different remote hash is Conflict`.
        // When the remote side's hash AND remoteModified both match the DB
        // entry's recorded values, reconcile() classifies remoteState as
        // UNCHANGED (see Reconciler.reconcile: the "MODIFIED" branch fires
        // only when hash OR modified differs). With localState=MODIFIED +
        // remoteState=UNCHANGED the resolveAction mapping emits Upload.
        //
        // UD-800: this is the invariant the previous test-name promised,
        // landed as a separate test so the actual behaviour is named and
        // asserted.
        val sameHash = "h-stable"
        val sameModified = java.time.Instant.parse("2026-03-28T12:00:00Z")
        db.upsertEntry(
            dbEntry("/same.txt", remoteHash = sameHash).copy(remoteModified = sameModified),
        )
        val remoteChanges =
            mapOf(
                "/same.txt" to
                    cloudItem("/same.txt", hash = sameHash).copy(modified = sameModified),
            )
        val localChanges = mapOf("/same.txt" to ChangeState.MODIFIED)
        Files.createDirectories(syncRoot)
        Files.writeString(syncRoot.resolve("same.txt"), "content")
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.Upload>(actions[0])
    }

    @Test
    fun `both deleted is remove-entry`() {
        db.upsertEntry(dbEntry("/gone.txt"))
        val remoteChanges = mapOf("/gone.txt" to cloudItem("/gone.txt", deleted = true))
        val localChanges = mapOf("/gone.txt" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.RemoveEntry>(actions[0])
    }

    @Test
    fun `both new is conflict`() {
        val remoteChanges = mapOf("/clash.txt" to cloudItem("/clash.txt"))
        val localChanges = mapOf("/clash.txt" to ChangeState.NEW)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.Conflict>(actions[0])
    }

    @Test
    fun `both new folders merge without conflict`() {
        // Invariant: both-new folders must emit at least one merge action AND no Conflict.
        // The previous assertion allowed a broken impl that silently dropped the folder
        // (empty action list) to pass. UD-812.
        val remoteChanges = mapOf("/shared" to cloudItem("/shared", isFolder = true))
        val localChanges = mapOf("/shared" to ChangeState.NEW)
        Files.createDirectories(syncRoot.resolve("shared"))
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        val folderActions = actions.filter { it.path == "/shared" }
        assertTrue(folderActions.isNotEmpty(), "both-new folder must produce at least one merge action")
        assertTrue(folderActions.none { it is SyncAction.Conflict })
    }

    @Test
    fun `case collision detected`() {
        db.upsertEntry(dbEntry("/Report.pdf"))
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/report.pdf" to ChangeState.NEW)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        val conflicts = actions.filterIsInstance<SyncAction.Conflict>()
        assertEquals(1, conflicts.size)
    }

    @Test
    fun `actions sorted folders first then files`() {
        val remoteChanges =
            mapOf(
                "/dir/file.txt" to cloudItem("/dir/file.txt"),
                "/dir" to cloudItem("/dir", isFolder = true),
            )
        val localChanges = emptyMap<String, ChangeState>()
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        val folderIdx = actions.indexOfFirst { it.path == "/dir" }
        val fileIdx = actions.indexOfFirst { it.path == "/dir/file.txt" }
        assertTrue(folderIdx < fileIdx)
    }

    @Test
    fun `remote deleted folder produces DeleteLocal`() {
        db.upsertEntry(
            SyncEntry(
                path = "/photos",
                remoteId = "id-photos",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 1711627200000,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        val remoteChanges = mapOf("/photos" to cloudItem("/photos", isFolder = true, deleted = true))
        val localChanges = emptyMap<String, ChangeState>()
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DeleteLocal>(actions[0])
        assertEquals("/photos", actions[0].path)
    }

    @Test
    fun `remote modified hydrated file re-downloads content`() {
        // UD-222: hydrated-or-not, a remote-modified non-folder always re-downloads.
        db.upsertEntry(
            SyncEntry(
                path = "/doc.txt",
                remoteId = "id-doc",
                remoteHash = "old-hash",
                remoteSize = 100,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 1711627200000,
                localSize = 100,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        val remoteChanges =
            mapOf(
                "/doc.txt" to
                    CloudItem(
                        id = "id-doc",
                        name = "doc.txt",
                        path = "/doc.txt",
                        size = 200,
                        isFolder = false,
                        modified = Instant.parse("2026-03-29T12:00:00Z"),
                        created = Instant.parse("2026-03-28T10:00:00Z"),
                        hash = "new-hash",
                        mimeType = "text/plain",
                    ),
            )
        val localChanges = emptyMap<String, ChangeState>()
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        val action = assertIs<SyncAction.DownloadContent>(actions[0])
        assertEquals(200, action.remoteItem.size)
    }

    @Test
    fun `local deleted remote modified is conflict`() {
        db.upsertEntry(dbEntry("/conflict.txt"))
        val remoteChanges = mapOf("/conflict.txt" to cloudItem("/conflict.txt", hash = "new-hash"))
        val localChanges = mapOf("/conflict.txt" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        val action = assertIs<SyncAction.Conflict>(actions[0])
        assertEquals(ChangeState.DELETED, action.localState)
        assertEquals(ChangeState.MODIFIED, action.remoteState)
    }

    @Test
    fun `remote rename detected via remoteId match`() {
        db.upsertEntry(
            SyncEntry(
                path = "/old-name.txt",
                remoteId = "id-rename",
                remoteHash = "abc",
                remoteSize = 100,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 1711627200000,
                localSize = 100,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        val renamedItem =
            CloudItem(
                id = "id-rename",
                name = "new-name.txt",
                path = "/new-name.txt",
                size = 100,
                isFolder = false,
                modified = Instant.parse("2026-03-28T12:00:00Z"),
                created = Instant.parse("2026-03-28T10:00:00Z"),
                hash = "abc",
                mimeType = "text/plain",
            )
        val remoteChanges = mapOf("/new-name.txt" to renamedItem)
        val localChanges = emptyMap<String, ChangeState>()
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        val moveActions = actions.filterIsInstance<SyncAction.MoveLocal>()
        assertEquals(1, moveActions.size)
        assertEquals("/new-name.txt", moveActions[0].path)
        assertEquals("/old-name.txt", moveActions[0].fromPath)
    }

    // ── Selective sync (exclude patterns) ────────────────────────────────────

    @Test
    fun `excluded path produces no actions`() {
        val r = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, excludePatterns = listOf("Videos/**"))
        val remoteChanges =
            mapOf(
                "/Videos/movie.mp4" to cloudItem("/Videos/movie.mp4"),
                "/Documents/doc.txt" to cloudItem("/Documents/doc.txt"),
            )
        val actions = r.reconcile(remoteChanges, emptyMap())
        assertEquals(1, actions.size)
        assertEquals("/Documents/doc.txt", actions[0].path)
    }

    @Test
    fun `exclude glob without slash matches by basename`() {
        val r = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, excludePatterns = listOf("*.tmp"))
        val remoteChanges =
            mapOf(
                "/work/scratch.tmp" to cloudItem("/work/scratch.tmp"),
                "/work/notes.txt" to cloudItem("/work/notes.txt"),
            )
        val actions = r.reconcile(remoteChanges, emptyMap())
        assertEquals(1, actions.size)
        assertEquals("/work/notes.txt", actions[0].path)
    }

    @Test
    fun `local change on excluded path produces no actions`() {
        val r = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, excludePatterns = listOf("Cache/**"))
        val localChanges =
            mapOf(
                "/Cache/data.bin" to ChangeState.NEW,
                "/Documents/report.pdf" to ChangeState.NEW,
            )
        Files.createDirectories(syncRoot.resolve("Documents"))
        Files.createFile(syncRoot.resolve("Documents/report.pdf"))
        val actions = r.reconcile(emptyMap(), localChanges)
        assertTrue(actions.none { it.path == "/Cache/data.bin" })
        assertTrue(actions.any { it.path == "/Documents/report.pdf" })
    }

    @Test
    fun `no exclude patterns — all paths reconciled`() {
        val r = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
        val remoteChanges =
            mapOf(
                "/Videos/movie.mp4" to cloudItem("/Videos/movie.mp4"),
                "/Documents/doc.txt" to cloudItem("/Documents/doc.txt"),
            )
        val actions = r.reconcile(remoteChanges, emptyMap())
        assertEquals(2, actions.size)
    }

    // UD-901 — pending-upload row interactions

    private fun pendingEntry(
        path: String,
        size: Long = 100,
    ) = SyncEntry(
        path = path,
        remoteId = null,
        remoteHash = null,
        remoteSize = 0,
        remoteModified = null,
        localMtime = 1711627200000,
        localSize = size,
        isFolder = false,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.EPOCH,
    )

    @Test
    fun `UD-901 NEW path with existing pending row still emits Upload`() {
        // Mirrors the scanner's first-pass behaviour: scan upserts a pending row
        // AND emits ChangeState.NEW. Reconciler must still produce SyncAction.Upload
        // even though entry != null at lookup time.
        db.upsertEntry(pendingEntry("/new.txt"))
        Files.writeString(syncRoot.resolve("new.txt"), "x")

        val actions =
            reconciler.reconcile(
                emptyMap(),
                mapOf("/new.txt" to ChangeState.NEW),
            )

        assertEquals(1, actions.size)
        assertIs<SyncAction.Upload>(actions[0])
        assertEquals("/new.txt", actions[0].path)
    }

    @Test
    fun `UD-901 deleted pending row emits RemoveEntry not DeleteRemote`() {
        // Pending-upload row never reached the remote, so deletion has nothing to
        // propagate to the cloud. Must emit RemoveEntry (DB cleanup only).
        db.upsertEntry(pendingEntry("/abandoned.txt"))

        val actions =
            reconciler.reconcile(
                emptyMap(),
                mapOf("/abandoned.txt" to ChangeState.DELETED),
            )

        assertEquals(1, actions.size)
        assertIs<SyncAction.RemoveEntry>(actions[0])
        assertEquals("/abandoned.txt", actions[0].path)
    }

    @Test
    fun `UD-901 interrupted upload retries on next reconcile`() {
        // Pending row exists, file still on disk, scanner emits no change (mtime/size
        // unchanged). The synthesis loop must promote it to a fresh Upload action so
        // the next sync cycle picks the upload back up.
        Files.writeString(syncRoot.resolve("retry.txt"), "still here")
        db.upsertEntry(pendingEntry("/retry.txt"))

        val actions =
            reconciler.reconcile(
                emptyMap(),
                emptyMap(),
            )

        val upload = actions.firstOrNull { it.path == "/retry.txt" }
        assertIs<SyncAction.Upload>(
            upload,
            "expected synthesised Upload for pending row with on-disk bytes; got $actions",
        )
    }

    @Test
    fun `UD-901 synthesis loop skips pending row whose local file vanished`() {
        // No bytes on disk → no point uploading. The DELETED-detection path runs
        // through the scanner; absent that, the synthesis loop must not emit a
        // doomed Upload (provider has nothing to upload from).
        db.upsertEntry(pendingEntry("/ghost.txt"))
        // file does not exist in syncRoot

        val actions = reconciler.reconcile(emptyMap(), emptyMap())

        assertTrue(
            actions.none { it.path == "/ghost.txt" },
            "synthesis loop must skip pending rows with no on-disk bytes; got $actions",
        )
    }
}
