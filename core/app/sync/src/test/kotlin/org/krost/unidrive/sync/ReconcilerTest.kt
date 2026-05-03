package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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

    // ── UD-240j: detectMoves folder-move dedup ──────────────────────────────
    // Captured live 2026-05-03 10:41: two MoveRemote actions emitted with the
    // SAME destination path, different sources, both failing at apply time
    // because the source folders don't exist on remote. detectMoves's folder-
    // move loop matched the same CreateRemoteFolder twice (once per delete
    // sharing the basename) — the matchedFolderCreates dedup wasn't there.

    @Test
    fun `UD-240j folder-move emits at most one MoveRemote per CreateRemoteFolder`() {
        // Two folder rows in DB sharing basename "Sample" — a stale one at root
        // (legacy path from an earlier sync_root configuration) and the current
        // one nested. Both have remoteId set (i.e. both look "real" to the
        // engine — DB doesn't have a way to distinguish stale).
        db.upsertEntry(
            SyncEntry(
                path = "/Sample",
                remoteId = "id-stale-Sample",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        db.upsertEntry(
            SyncEntry(
                path = "/Pictures/Sample",
                remoteId = "id-real-Sample",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        // Local: one new folder at the moved location.
        Files.createDirectories(syncRoot.resolve("Pictures/_Photos/Sample"))
        val localChanges =
            mapOf(
                "/Sample" to ChangeState.DELETED,
                "/Pictures/Sample" to ChangeState.DELETED,
                "/Pictures/_Photos/Sample" to ChangeState.NEW,
            )
        val remoteChanges = emptyMap<String, CloudItem>()

        val actions = reconciler.reconcile(remoteChanges, localChanges)
        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        val movesToTarget = moves.filter { it.path == "/Pictures/_Photos/Sample" }
        assertEquals(
            1,
            movesToTarget.size,
            "UD-240j: at most ONE MoveRemote per destination; got: ${moves.map { "${it.fromPath}->${it.path}" }}",
        )
        // The other Delete should survive untouched as DeleteRemote (the engine
        // will then propagate it as a real delete-remote, OR the user can
        // resolve via UD-205-class atomicity work — out of scope for UD-240j).
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>().map { it.path }
        assertEquals(1, deletes.size, "the unmatched delete must remain in actions; got: $deletes")
    }

    @Test
    fun `UD-240j folder-move dedup does not break the legitimate single-move case`() {
        // Regression guard: with exactly one DeleteRemote + matching CreateRemoteFolder,
        // detectMoves still emits MoveRemote.
        db.upsertEntry(
            SyncEntry(
                path = "/Pictures/Sample",
                remoteId = "id-Sample",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        Files.createDirectories(syncRoot.resolve("Pictures/_Photos/Sample"))
        val localChanges =
            mapOf(
                "/Pictures/Sample" to ChangeState.DELETED,
                "/Pictures/_Photos/Sample" to ChangeState.NEW,
            )
        val actions = reconciler.reconcile(emptyMap(), localChanges)
        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(1, moves.size, "expected one MoveRemote; got: $actions")
        assertEquals("/Pictures/Sample", moves[0].fromPath)
        assertEquals("/Pictures/_Photos/Sample", moves[0].path)
    }

    // ── UD-240k: folder-move structural-locality match ──────────────────────
    // Captured live 2026-05-03 10:51 (post-UD-240j): with two stale DB rows
    // sharing basename "Sample" — a top-level legacy /Sample and the user's
    // actual /userhome/Pictures/Sample — detectMoves picked /Sample as
    // the source for /userhome/Pictures/_Photos/Sample. UD-240j stopped
    // duplicates; UD-240k makes the surviving one actually right.

    @Test
    fun `UD-240k folder-move picks the close-relative source over a far-tree one`() {
        // Legacy stale row at root, no parent overlap with the destination.
        db.upsertEntry(
            SyncEntry(
                path = "/Sample",
                remoteId = "id-stale-Sample",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        // Real source — same parent tree as the destination.
        db.upsertEntry(
            SyncEntry(
                path = "/Pictures/Sample",
                remoteId = "id-real-Sample",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        Files.createDirectories(syncRoot.resolve("Pictures/_Photos/Sample"))
        val localChanges =
            mapOf(
                "/Sample" to ChangeState.DELETED,
                "/Pictures/Sample" to ChangeState.DELETED,
                "/Pictures/_Photos/Sample" to ChangeState.NEW,
            )
        val actions = reconciler.reconcile(emptyMap(), localChanges)
        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(1, moves.size, "expected one MoveRemote; got: $moves")
        assertEquals(
            "/Pictures/Sample",
            moves[0].fromPath,
            "must pick the close-relative source (shares /Pictures parent), NOT /Sample",
        )
        assertEquals("/Pictures/_Photos/Sample", moves[0].path)
        // The unmatched stale delete survives as a plain DeleteRemote — engine's
        // skip-on-not-found path will handle it gracefully if it's already gone
        // from remote.
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>().map { it.path }
        assertEquals(listOf("/Sample"), deletes)
    }

    @Test
    fun `UD-240k single cross-tree candidate is accepted as a move`() {
        // One delete, one create, no parent overlap (e.g. /a/docs → /b/docs
        // in the SyncEngineTest legacy fixture). Single-candidate scenario
        // has no ambiguity — accept the move.
        db.upsertEntry(
            SyncEntry(
                path = "/a/docs",
                remoteId = "id-a-docs",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        Files.createDirectories(syncRoot.resolve("b/docs"))
        val localChanges =
            mapOf(
                "/a/docs" to ChangeState.DELETED,
                "/b/docs" to ChangeState.NEW,
            )
        val actions = reconciler.reconcile(emptyMap(), localChanges)
        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(1, moves.size, "single cross-tree candidate must still produce a move; got: $moves")
        assertEquals("/a/docs", moves[0].fromPath)
        assertEquals("/b/docs", moves[0].path)
    }

    @Test
    fun `UD-240k two cross-tree candidates with no parent overlap reject the match`() {
        // Two stale rows sharing basename, both top-level, neither shares a
        // parent segment with the destination. Without structural evidence
        // we'd be guessing — better to refuse the match and let the engine
        // run the standalone delete + create paths.
        db.upsertEntry(
            SyncEntry(
                path = "/Lonely",
                remoteId = "id-lonely-1",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        db.upsertEntry(
            SyncEntry(
                path = "/Old/Lonely",
                remoteId = "id-lonely-2",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        Files.createDirectories(syncRoot.resolve("Far/Away/Lonely"))
        val localChanges =
            mapOf(
                "/Lonely" to ChangeState.DELETED,
                "/Old/Lonely" to ChangeState.DELETED,
                "/Far/Away/Lonely" to ChangeState.NEW,
            )
        val actions = reconciler.reconcile(emptyMap(), localChanges)
        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertTrue(
            moves.isEmpty(),
            "ambiguous zero-score cross-tree candidates → no move; got: $moves",
        )
        // Both deletes survive standalone.
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>().map { it.path }
        assertTrue("/Lonely" in deletes && "/Old/Lonely" in deletes, "got: $deletes")
        // Create survives standalone.
        assertTrue(actions.any { it is SyncAction.CreateRemoteFolder && it.path == "/Far/Away/Lonely" })
    }

    @Test
    fun `UD-240k legitimate single-move with shared parent still works`() {
        // Regression guard: rename in place should still produce MoveRemote.
        db.upsertEntry(
            SyncEntry(
                path = "/Pictures/Sample",
                remoteId = "id-Sample",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        Files.createDirectories(syncRoot.resolve("Pictures/_Photos/Sample"))
        val localChanges =
            mapOf(
                "/Pictures/Sample" to ChangeState.DELETED,
                "/Pictures/_Photos/Sample" to ChangeState.NEW,
            )
        val actions = reconciler.reconcile(emptyMap(), localChanges)
        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(1, moves.size)
        assertEquals("/Pictures/Sample", moves[0].fromPath)
        assertEquals("/Pictures/_Photos/Sample", moves[0].path)
    }

    @Test
    fun `UD-240k pure helpers parentSegments and commonPrefixSegments`() {
        // Pin the helper semantics so future refactors don't drift.
        assertEquals(listOf("A", "B", "C"), Reconciler.parentSegments("/A/B/C/file.bin"))
        assertEquals(emptyList(), Reconciler.parentSegments("/file.bin"))
        assertEquals(emptyList(), Reconciler.parentSegments("/"))
        assertEquals(emptyList(), Reconciler.parentSegments(""))

        assertEquals(
            2,
            Reconciler.commonPrefixSegments(listOf("A", "B", "C"), listOf("A", "B", "D")),
        )
        assertEquals(
            0,
            Reconciler.commonPrefixSegments(listOf("A", "B"), listOf("X", "Y")),
        )
        assertEquals(
            1,
            Reconciler.commonPrefixSegments(listOf("A"), listOf("A", "B")),
        )
        assertEquals(
            0,
            Reconciler.commonPrefixSegments(emptyList(), listOf("A", "B")),
        )
    }

    // ── UD-901a: recovery loops respect syncPath scope ──────────────────────
    // Pre-fix the UD-225 download-recovery and UD-901 upload-recovery loops
    // iterated `db.getAllEntries()` without any scope filter. A
    // `--sync-path /internal` invocation that should produce ~100 actions
    // surfaced 107,988 actions on the user's 2026-05-03 session — every
    // pending-upload row in the DB regardless of where it sat. Confirmed by
    // the daemon's `Reconciling... 106 / 106 items` (correct in-scope main
    // loop) followed by `Reconciled: 107988 actions` (recovery bypass).

    @Test
    fun `UD-901a UD-901 upload recovery respects syncPath scope`() {
        // Seed three pending-upload rows under different prefixes.
        val pendingPaths =
            listOf(
                "/internal/file-a.bin",
                "/Project Notes/file-b.bin",
                "/AnonA/file-c.bin",
            )
        for (p in pendingPaths) {
            // remoteId=null + isHydrated=true is the UD-901 pending-upload shape.
            db.upsertEntry(
                SyncEntry(
                    path = p,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = 1711627200000,
                    localSize = 100,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.EPOCH,
                ),
            )
            // Materialise the file on disk so Files.isRegularFile passes.
            val abs = syncRoot.resolve(p.removePrefix("/"))
            Files.createDirectories(abs.parent)
            Files.writeString(abs, "x".repeat(100))
        }

        val actions =
            reconciler.reconcile(
                remoteChanges = emptyMap(),
                localChanges = emptyMap(),
                syncPath = "/internal",
            )
        val uploads = actions.filterIsInstance<SyncAction.Upload>()
        assertEquals(
            1,
            uploads.size,
            "UD-901a: only the in-scope orphan should surface; got: ${uploads.map { it.path }}",
        )
        assertEquals("/internal/file-a.bin", uploads[0].path)
    }

    @Test
    fun `UD-901a UD-225 download recovery respects syncPath scope`() {
        // Seed three half-downloaded rows (isHydrated=false, remoteSize>0)
        // under different prefixes. UD-225's recovery loop should respect
        // syncPath the same way UD-901's does.
        val pendingPaths =
            listOf(
                "/internal/file-a.bin",
                "/Project Notes/file-b.bin",
                "/AnonA/file-c.bin",
            )
        for (p in pendingPaths) {
            db.upsertEntry(
                SyncEntry(
                    path = p,
                    remoteId = "id-$p",
                    remoteHash = "hash-$p",
                    remoteSize = 100,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = 1711627200000,
                    localSize = 0,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        }

        val actions =
            reconciler.reconcile(
                remoteChanges = emptyMap(),
                localChanges = emptyMap(),
                syncPath = "/internal",
            )
        val downloads = actions.filterIsInstance<SyncAction.DownloadContent>()
        assertEquals(
            1,
            downloads.size,
            "UD-901a: only the in-scope orphan should surface; got: ${downloads.map { it.path }}",
        )
        assertEquals("/internal/file-a.bin", downloads[0].path)
    }

    @Test
    fun `UD-901a no syncPath means all orphans are recovered as before`() {
        // Regression guard: when syncPath is null (the default), every orphan
        // continues to be recovered — i.e. the new filter is a strict
        // restriction added on top of existing behaviour, not a behavioural
        // change for the no-scope case.
        val pendingPaths = listOf("/x/a.bin", "/y/b.bin", "/z/c.bin")
        for (p in pendingPaths) {
            db.upsertEntry(
                SyncEntry(
                    path = p,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = 1711627200000,
                    localSize = 100,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.EPOCH,
                ),
            )
            val abs = syncRoot.resolve(p.removePrefix("/"))
            Files.createDirectories(abs.parent)
            Files.writeString(abs, "x".repeat(100))
        }

        val actions = reconciler.reconcile(emptyMap(), emptyMap())
        val uploads = actions.filterIsInstance<SyncAction.Upload>()
        assertEquals(3, uploads.size, "no-scope recovery must surface all 3 orphans")
    }

    @Test
    fun `UD-901a pathInSyncScope matches the engine scope filter exactly`() {
        // Direct unit test of the predicate so future refactors of the engine's
        // own filter (SyncEngine.kt:163) and this one stay in lockstep.
        // null syncPath → everything in scope.
        assertTrue(Reconciler.pathInSyncScope("/anything", null))
        // Exact match.
        assertTrue(Reconciler.pathInSyncScope("/internal", "/internal"))
        // Strict descendant.
        assertTrue(Reconciler.pathInSyncScope("/internal/sub/file.bin", "/internal"))
        // Sibling that shares a prefix is OUT of scope (the bug `/foo` matching
        // `/footer.txt` if we'd used startsWith naively).
        assertFalse(Reconciler.pathInSyncScope("/internal-other", "/internal"))
        assertFalse(Reconciler.pathInSyncScope("/footer.txt", "/foo"))
        // Out-of-tree.
        assertFalse(Reconciler.pathInSyncScope("/Project Notes/x", "/internal"))
    }

    // ── UD-901b: orphan upload synthesises parent CreateRemoteFolder ────────
    // Pre-fix the UD-901 recovery loop emitted Upload but not the prerequisite
    // CreateRemoteFolder for the parent tree. When a pending-upload row's
    // parent doesn't exist on remote (e.g. previous run was killed before its
    // folder creates landed), Pass 2 fires the upload, the provider's
    // resolveFolder walks the path-segments looking for the parent, doesn't
    // find it, throws "Folder not found." Confirmed live 2026-05-03 00:25:
    // every file under /Project Notes failed identically — the orphan
    // rows would stay in DB forever, every retry failing the same way.

    @Test
    fun `UD-901b orphan upload emits CreateRemoteFolder chain for missing ancestors`() {
        // Single orphan at /a/b/c/file.bin; no folder rows in DB; no remote
        // changes. Expect CreateRemoteFolder for /a, /a/b, /a/b/c, then Upload.
        val orphanPath = "/a/b/c/file.bin"
        db.upsertEntry(
            SyncEntry(
                path = orphanPath,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 1711627200000,
                localSize = 100,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.EPOCH,
            ),
        )
        val abs = syncRoot.resolve(orphanPath.removePrefix("/"))
        Files.createDirectories(abs.parent)
        Files.writeString(abs, "x".repeat(100))

        val actions = reconciler.reconcile(emptyMap(), emptyMap())
        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        val uploads = actions.filterIsInstance<SyncAction.Upload>().map { it.path }
        assertEquals(
            listOf("/a", "/a/b", "/a/b/c"),
            mkdirs.sortedBy { it.length },
            "must emit CreateRemoteFolder for every missing ancestor; got: $mkdirs",
        )
        assertEquals(listOf(orphanPath), uploads)
    }

    @Test
    fun `UD-901b orphan upload skips ancestors already on remote`() {
        // Pre-existing folder /a (remoteId set) + orphan at /a/b/file.bin.
        // /a's remoteId proves it's on remote — must NOT re-emit. Only /a/b
        // gets a CreateRemoteFolder.
        db.upsertEntry(
            SyncEntry(
                path = "/a",
                remoteId = "id-folder-a",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 1711627200000,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        db.upsertEntry(
            SyncEntry(
                path = "/a/b/file.bin",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 1711627200000,
                localSize = 100,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.EPOCH,
            ),
        )
        val abs = syncRoot.resolve("a/b/file.bin")
        Files.createDirectories(abs.parent)
        Files.writeString(abs, "x".repeat(100))

        val actions = reconciler.reconcile(emptyMap(), emptyMap())
        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        assertEquals(
            listOf("/a/b"),
            mkdirs,
            "/a is already on remote; must NOT re-emit. Only /a/b is missing.",
        )
    }

    @Test
    fun `UD-901b multiple orphans sharing ancestors emit each ancestor once`() {
        // Three orphans under /a/shared/* — all need /a and /a/shared.
        // Expect exactly two CreateRemoteFolder, not six.
        for (leaf in listOf("file1.bin", "file2.bin", "file3.bin")) {
            db.upsertEntry(
                SyncEntry(
                    path = "/a/shared/$leaf",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = 1711627200000,
                    localSize = 100,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.EPOCH,
                ),
            )
            val abs = syncRoot.resolve("a/shared/$leaf")
            Files.createDirectories(abs.parent)
            Files.writeString(abs, "x".repeat(100))
        }

        val actions = reconciler.reconcile(emptyMap(), emptyMap())
        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        val uploads = actions.filterIsInstance<SyncAction.Upload>()
        assertEquals(2, mkdirs.size, "exactly /a and /a/shared, no duplicates; got: $mkdirs")
        assertTrue("/a" in mkdirs && "/a/shared" in mkdirs)
        assertEquals(3, uploads.size)
    }

    @Test
    fun `UD-901b sortActions places ancestor mkdirs before uploads`() {
        // Pass 1's sequential apply runs CreateRemoteFolder before Upload because
        // sortActions assigns priority 0 to CreateRemoteFolder vs 2 to Upload.
        // Within priority 0, slash-count ascending puts shallow parents first.
        // This test pins the contract: in the returned action list, every
        // CreateRemoteFolder appears before the Upload it's a prerequisite for.
        db.upsertEntry(
            SyncEntry(
                path = "/x/y/z/file.bin",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 1711627200000,
                localSize = 100,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.EPOCH,
            ),
        )
        val abs = syncRoot.resolve("x/y/z/file.bin")
        Files.createDirectories(abs.parent)
        Files.writeString(abs, "x".repeat(100))

        val actions = reconciler.reconcile(emptyMap(), emptyMap())
        val uploadIdx = actions.indexOfFirst { it is SyncAction.Upload }
        val mkdirIdxs =
            actions.withIndex()
                .filter { it.value is SyncAction.CreateRemoteFolder }
                .map { it.index }
        assertTrue(uploadIdx > 0, "expected at least one mkdir before upload; actions=$actions")
        for (mkdirIdx in mkdirIdxs) {
            assertTrue(
                mkdirIdx < uploadIdx,
                "every CreateRemoteFolder must precede the Upload; actions=$actions",
            )
        }
        // Within mkdirs, shallower path comes first.
        val mkdirPaths = mkdirIdxs.map { (actions[it] as SyncAction.CreateRemoteFolder).path }
        assertEquals(
            listOf("/x", "/x/y", "/x/y/z"),
            mkdirPaths,
            "mkdirs must be ordered shallow→deep so Pass-1 sequential apply creates parents before children",
        )
    }

    // ── UD-240i: detectMoves perf bound + file-move correctness ─────────────
    // Pre-fix detectMoves did Files.isRegularFile + Files.size on every
    // (DeleteRemote, Upload) pair — O(D × U) Windows GetFileAttributesEx
    // syscalls. Captured live 2026-05-02 17:48 via jstack on PID 12764: ~5M
    // syscalls / ~4 min wall-clock on a 67k-upload first-sync. Post-fix the
    // probe is invoked at most once per Upload, regardless of how many
    // DeleteRemote actions the reconcile pass generates.

    @Test
    fun `UD-240i detectMoves probe is bounded by upload count, not delete x upload`() {
        val probeCount = AtomicInteger(0)
        val countingProbe: (Path) -> LocalFileInfo? = { _ ->
            probeCount.incrementAndGet()
            null // every upload "doesn't exist" so the inner loop runs to completion pre-fix
        }
        val r =
            Reconciler(
                db,
                syncRoot,
                ConflictPolicy.KEEP_BOTH,
                localFsProbe = countingProbe,
            )

        // Seed DB with 50 entries that resolveAction will turn into DeleteRemote
        // (DB has them, remote no longer reports them, local marks them DELETED).
        val deleteCount = 50
        repeat(deleteCount) { i ->
            db.upsertEntry(dbEntry("/old$i.bin"))
        }
        val uploadCount = 500
        val localChanges =
            buildMap<String, ChangeState> {
                repeat(deleteCount) { i -> put("/old$i.bin", ChangeState.DELETED) }
                repeat(uploadCount) { i -> put("/new$i.bin", ChangeState.NEW) }
            }
        val remoteChanges = emptyMap<String, CloudItem>()

        r.reconcile(remoteChanges, localChanges)

        // Pre-fix this would be deleteCount × uploadCount = 25,000.
        // Post-fix: <= uploadCount (one probe per upload, in a single pre-pass).
        assertTrue(
            probeCount.get() <= uploadCount,
            "UD-240i: detectMoves should issue at most $uploadCount probe calls " +
                "(one per Upload), got ${probeCount.get()} — algorithm regressed " +
                "to O(D × U) syscalls. With D=$deleteCount and U=$uploadCount that " +
                "extrapolates to a ~5M-syscall storm on a real 67k-upload first-sync.",
        )
    }

    @Test
    fun `UD-240i detectMoves still produces MoveRemote on size match`() {
        // Correctness regression: a (DeleteRemote + same-sized Upload) pair must
        // still resolve to MoveRemote after the bySize-map refactor.
        db.upsertEntry(dbEntry("/old.bin"))
        Files.writeString(syncRoot.resolve("new.bin"), "x".repeat(100))

        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges =
            mapOf(
                "/old.bin" to ChangeState.DELETED,
                "/new.bin" to ChangeState.NEW,
            )

        val actions = reconciler.reconcile(remoteChanges, localChanges)
        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(1, moves.size, "expected one MoveRemote, got: $actions")
        assertEquals("/new.bin", moves[0].path)
        assertEquals("/old.bin", moves[0].fromPath)
        // The Delete + Upload should have been removed from the action list.
        assertTrue(actions.none { it is SyncAction.DeleteRemote && it.path == "/old.bin" })
        assertTrue(actions.none { it is SyncAction.Upload && it.path == "/new.bin" })
    }

    @Test
    fun `UD-240i detectMoves does not match different-size upload`() {
        // Negative case: size mismatch must NOT produce a move; the original
        // DeleteRemote and Upload survive the pass.
        db.upsertEntry(dbEntry("/old.bin"))
        Files.writeString(syncRoot.resolve("new.bin"), "x".repeat(42)) // dbEntry is 100 bytes

        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges =
            mapOf(
                "/old.bin" to ChangeState.DELETED,
                "/new.bin" to ChangeState.NEW,
            )

        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertTrue(actions.none { it is SyncAction.MoveRemote }, "no move expected: $actions")
        assertTrue(actions.any { it is SyncAction.DeleteRemote && it.path == "/old.bin" })
        assertTrue(actions.any { it is SyncAction.Upload && it.path == "/new.bin" })
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
