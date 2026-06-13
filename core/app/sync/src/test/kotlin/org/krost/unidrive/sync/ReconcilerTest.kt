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

    // #200(b): an INCOMPLETE enumeration must not let local-present/remote-absent
    // paths drive remote creates — they may be un-enumerated subtrees, not new files.
    @Test
    fun `incomplete enumeration defers new-local creates`() {
        Files.createDirectory(syncRoot.resolve("newdir")) // NEW-local dir → CreateRemoteFolder
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf(
            "/local-new.txt" to ChangeState.NEW, // → Upload
            "/newdir" to ChangeState.NEW, // → CreateRemoteFolder
        )
        val actions = reconciler.reconcile(remoteChanges, localChanges, enumerationComplete = false)
        assertTrue(actions.none { it is SyncAction.Upload }, "new-local Upload must be deferred on an incomplete enumeration")
        assertTrue(actions.none { it is SyncAction.CreateRemoteFolder }, "new-local CreateRemoteFolder must be deferred")
    }

    // Inverse: a COMPLETE enumeration must NOT over-defer — new-local creates emit.
    @Test
    fun `complete enumeration emits new-local creates`() {
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/local-new.txt" to ChangeState.NEW)
        val actions = reconciler.reconcile(remoteChanges, localChanges, enumerationComplete = true)
        assertTrue(actions.any { it is SyncAction.Upload }, "complete enumeration must still upload genuinely-new files")
    }

    // The defer targets only NEW-local creates: a MODIFIED upload (replace an
    // existing remote) is not deferred even when the enumeration is incomplete.
    @Test
    fun `incomplete enumeration does not defer modified upload`() {
        db.upsertEntry(dbEntry("/mod.txt", isHydrated = true))
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/mod.txt" to ChangeState.MODIFIED)
        val actions = reconciler.reconcile(remoteChanges, localChanges, enumerationComplete = false)
        assertTrue(actions.any { it is SyncAction.Upload }, "MODIFIED upload must NOT be deferred")
    }

    // #200(b) streaming finalize: the same gate runs in finalizeStreaming, where
    // streamed (held) NEW-local uploads are emitted on a complete enumeration and
    // dropped on an incomplete one.
    @Test
    fun `finalizeStreaming defers new-local creates on incomplete enumeration`() {
        val streamed = listOf<SyncAction>(
            SyncAction.Upload("/local-new.txt"),
            SyncAction.CreateRemoteFolder("/newdir"),
        )
        val fullLocal = mapOf("/local-new.txt" to ChangeState.NEW, "/newdir" to ChangeState.NEW)
        val actions = reconciler.finalizeStreaming(
            streamed, emptyMap(), fullLocal, enumerationComplete = false,
        )
        assertTrue(actions.none { it is SyncAction.Upload }, "streamed new-local Upload must be deferred")
        assertTrue(actions.none { it is SyncAction.CreateRemoteFolder }, "streamed new-local CreateRemoteFolder must be deferred")
    }

    @Test
    fun `finalizeStreaming keeps new-local creates on complete enumeration`() {
        val streamed = listOf<SyncAction>(SyncAction.Upload("/local-new.txt"))
        val fullLocal = mapOf("/local-new.txt" to ChangeState.NEW)
        val actions = reconciler.finalizeStreaming(
            streamed, emptyMap(), fullLocal, enumerationComplete = true,
        )
        assertTrue(actions.any { it is SyncAction.Upload }, "complete enumeration keeps the streamed upload")
    }

    @Test
    fun `UD-373 matchesGlob compiles each distinct pattern exactly once (cached across calls)`() {
        // Reset the spy counter so this test sees only its own invocations even if other
        // tests in the suite ran first and warmed the cache for unrelated patterns.
        // Use patterns NOT in DEFAULT_EXCLUDE_PATTERNS to avoid cache-pre-warm from other
        // tests (e.g. LocalScannerTest) that exercise the default set before this test runs.
        Reconciler.buildGlobRegexInvocations.set(0)
        // Same pattern, many different paths → single buildGlobRegex call.
        repeat(50) { i ->
            Reconciler.matchesGlob("/dir/file-$i.zzz373", "**/*.zzz373")
        }
        // A second distinct pattern → second compile.
        repeat(50) { i ->
            Reconciler.matchesGlob("/dir/file-$i.qqq373", "**/*.qqq373")
        }
        // The first pattern again — must not recompile (cache hit).
        Reconciler.matchesGlob("/dir/file-99.zzz373", "**/*.zzz373")
        assertEquals(2L, Reconciler.buildGlobRegexInvocations.get())
    }

    @Test
    fun `UD-373 matchesGlob preserves basename-vs-full-path semantics under cache`() {
        // Pattern without `/` matches against basename only — verify the cache doesn't break
        // the path-side branching at matchesGlob.
        assertTrue(Reconciler.matchesGlob("/deep/nested/path/file.log", "*.log"))
        assertTrue(Reconciler.matchesGlob("/file.log", "*.log"))
        // Pattern with `/` matches the full path.
        assertTrue(Reconciler.matchesGlob("/_INBOX/foo.txt", "_INBOX/*.txt"))
        assertEquals(false, Reconciler.matchesGlob("/other/foo.txt", "_INBOX/*.txt"))
    }

    @Test
    fun `UD-366 local modified uploads carry existing remoteId for replace-in-place`() {
        // The MODIFIED+UNCHANGED branch must plumb entry.remoteId into SyncAction.Upload
        // so InternxtProvider can route through PUT /files/{uuid} instead of POSTing a
        // duplicate that 409s. NEW uploads (no entry) keep remoteId=null.
        db.upsertEntry(dbEntry("/mod.txt", isHydrated = true))
        val actions =
            reconciler.reconcile(
                emptyMap(),
                mapOf("/mod.txt" to ChangeState.MODIFIED),
            )
        val upload = actions.single() as SyncAction.Upload
        assertEquals("id-/mod.txt", upload.remoteId)
    }

    @Test
    fun `UD-366 local-new uploads have null remoteId (no replace-in-place attempt)`() {
        val actions =
            reconciler.reconcile(
                emptyMap(),
                mapOf("/new.txt" to ChangeState.NEW),
            )
        val upload = actions.single() as SyncAction.Upload
        assertEquals(null, upload.remoteId)
    }

    @Test
    fun `local deleted deletes remote`() {
        // Hydrated entry + local-missing → real user delete → propagate.
        db.upsertEntry(dbEntry("/del.txt", isHydrated = true))
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/del.txt" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DeleteRemote>(actions[0])
    }

    @Test
    fun `UD-225a unhydrated row + local-missing + remoteItem in delta emits DownloadContent`() {
        // Unhydrated DB row + missing local file + remoteItem present in the
        // delta. Pre-fix the main loop emitted DeleteRemote, the engine's
        // --download-only filter dropped it, and the file stayed unreachable.
        // With UD-225a, the main loop emits DownloadContent using the delta's
        // remoteItem.
        db.upsertEntry(dbEntry("/_INBOX/secret.pdf", isHydrated = false))
        val remoteChanges = mapOf("/_INBOX/secret.pdf" to cloudItem("/_INBOX/secret.pdf"))
        val localChanges = mapOf("/_INBOX/secret.pdf" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DownloadContent>(actions[0])
        assertEquals("/_INBOX/secret.pdf", actions[0].path)
    }

    @Test
    fun `UD-225a unhydrated row + local-missing + remoteItem ABSENT synthesises CloudItem from DB`() {
        // The actual 2026-05-03 incident shape: the provider's delta returned
        // 3,123 items but Internxt's `/folders` cursor window dropped the
        // parent folder, so InternxtProvider's path resolution collapsed
        // /_INBOX/* paths to drive root and the engine's syncPath filter
        // eliminated all of them — 0 entries in remoteChanges, 1,550 entries
        // in localChanges (DELETED). Pre-UD-225a the main loop emitted 1,550
        // DeleteRemote actions, the engine's --download-only filter dropped
        // them, files unreachable. With UD-225a, the main loop synthesises a
        // CloudItem from the DB row's remote_id/size/modified — same shape as
        // UD-225's recovery loop already uses for the UNCHANGED+UNCHANGED skip.
        db.upsertEntry(dbEntry("/_INBOX/orphan.pdf", isHydrated = false))
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/_INBOX/orphan.pdf" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size, "expected exactly one DownloadContent")
        val action = actions[0]
        assertIs<SyncAction.DownloadContent>(action)
        assertEquals("/_INBOX/orphan.pdf", action.path)
        // Verify the synthesised CloudItem carries the DB's remoteId so Pass 2's
        // downloadById can fetch from the provider even when the path was
        // missing from the delta.
        assertEquals("id-/_INBOX/orphan.pdf", (action as SyncAction.DownloadContent).remoteItem.id)
        assertEquals(100L, action.remoteItem.size)
    }

    @Test
    fun `unhydrated folder row + local-missing produces no DeleteRemote`() {
        // Regression smoke (b): pure-Reconciler shape. localChanges carries
        // DELETED for a folder path, remoteChanges is empty, the DB row is an
        // unhydrated folder. Pre-fix the (DELETED, UNCHANGED) branch fell
        // through to SyncAction.DeleteRemote because the recovery downgrade
        // at the resolveAction level excluded folders — planning destruction
        // of every cloud folder the user never visited on a sparse-hydration
        // profile. dropUnhydratedFolderDeletes strips surviving DeleteRemote
        // actions for unhydrated folder rows after move-detection has had a
        // chance to consume them.
        val folderEntry = SyncEntry(
            path = "/foo/bar",
            remoteId = "folder-uuid",
            remoteHash = null,
            remoteSize = 0,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = true,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )
        db.upsertEntry(folderEntry)
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/foo/bar" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertTrue(
            actions.none { it is SyncAction.DeleteRemote },
            "expected no DeleteRemote actions, got $actions",
        )
    }

    @Test
    fun `100 unhydrated folder rows + empty syncRoot produce zero DeleteRemote actions`() {
        // Regression smoke (a): seed 100 unhydrated folder rows (sparse-
        // hydration profile — folders enumerated by delta but never
        // materialised locally), walk through LocalScanner + Reconciler
        // against an empty syncRoot. Pre-fix this planned deletion of every
        // cloud folder the user never visited.
        val scanner = LocalScanner(syncRoot, db)
        repeat(100) { i ->
            db.upsertEntry(
                SyncEntry(
                    path = "/folder-$i",
                    remoteId = "folder-uuid-$i",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        }
        val localChanges = scanner.scan()
        val actions = reconciler.reconcile(emptyMap(), localChanges)
        val deleteRemotes = actions.filterIsInstance<SyncAction.DeleteRemote>()
        assertEquals(
            0,
            deleteRemotes.size,
            "expected zero DeleteRemote actions for unhydrated folder sweep, got ${deleteRemotes.map { it.path }}",
        )
    }

    @Test
    fun `UD-225a regression pin - hydrated row + local-missing still deletes remote`() {
        // Inverse pin so a future refactor can't accidentally always-rehydrate.
        // Hydrated rows mean the file WAS once present locally; if it's gone
        // now and remote is unchanged, that's user intent → DeleteRemote.
        db.upsertEntry(dbEntry("/hydrated-real-delete.txt", isHydrated = true))
        val remoteChanges = mapOf("/hydrated-real-delete.txt" to cloudItem("/hydrated-real-delete.txt"))
        val localChanges = mapOf("/hydrated-real-delete.txt" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DeleteRemote>(actions[0])
    }

    // #160 — download-only rehydrate: hydrated-but-locally-missing rows must re-download

    @Test
    fun `#160 download-only hydrated-row local-missing with remoteItem in delta emits DownloadContent`() {
        // Core invariant: in download-only mode a hydrated row whose local file is
        // absent (localChanges = DELETED, unchanged cloud delta) must produce a
        // DownloadContent (re-download), NOT a DeleteRemote that the direction
        // filter would discard — making the file unreachable forever.
        db.upsertEntry(dbEntry("/doc.txt", isHydrated = true))
        val remoteChanges = mapOf("/doc.txt" to cloudItem("/doc.txt"))
        val localChanges = mapOf("/doc.txt" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges, downloadOnly = true)
        assertEquals(1, actions.size, "expected exactly one action, got $actions")
        assertIs<SyncAction.DownloadContent>(actions[0])
        assertEquals("/doc.txt", actions[0].path)
    }

    @Test
    fun `#160 download-only hydrated-row local-missing with ABSENT delta synthesises CloudItem`() {
        // Common rehydrate scenario: the cloud delta is unchanged (no new remote
        // events), so remoteChanges is empty. The reconciler must synthesise a
        // CloudItem from the DB row's metadata and emit DownloadContent.
        db.upsertEntry(dbEntry("/photo.jpg", isHydrated = true))
        val remoteChanges = emptyMap<String, CloudItem>()
        val localChanges = mapOf("/photo.jpg" to ChangeState.DELETED)
        val actions = reconciler.reconcile(remoteChanges, localChanges, downloadOnly = true)
        assertEquals(1, actions.size, "expected exactly one action when delta is absent, got $actions")
        val action = actions[0]
        assertIs<SyncAction.DownloadContent>(action)
        assertEquals("/photo.jpg", action.path)
        // Synthesised CloudItem must carry the DB's remoteId so Pass 2 can
        // downloadById without a path-based delta lookup.
        assertEquals("id-/photo.jpg", action.remoteItem.id)
        assertEquals(100L, action.remoteItem.size)
    }

    @Test
    fun `#160 bidirectional unchanged - hydrated-row local-missing still emits DeleteRemote`() {
        // Bidirectional invariant must not regress: a locally-deleted hydrated file
        // in bidirectional mode is a user delete that propagates to remote.
        db.upsertEntry(dbEntry("/deleted-by-user.txt", isHydrated = true))
        val remoteChanges = mapOf("/deleted-by-user.txt" to cloudItem("/deleted-by-user.txt"))
        val localChanges = mapOf("/deleted-by-user.txt" to ChangeState.DELETED)
        // downloadOnly=false (the default) — bidirectional behaviour must be unchanged.
        val actions = reconciler.reconcile(remoteChanges, localChanges, downloadOnly = false)
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
        // The unmatched delete targets an unhydrated folder row, so the
        // post-detectMoves unhydrated-folder filter drops it. Pre-fix the
        // unmatched delete survived and got dispatched as a real cloud
        // del-remote — exactly the sparse-hydration data-risk class.
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>().map { it.path }
        assertEquals(0, deletes.size, "unmatched delete for an unhydrated folder row must be dropped; got: $deletes")
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
        // The unmatched stale delete targets an unhydrated folder row, so
        // the post-detectMoves filter drops it. Pre-fix it survived and got
        // dispatched as a real cloud del-remote.
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>().map { it.path }
        assertEquals(emptyList(), deletes, "unmatched delete for unhydrated folder must be dropped")
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
        // Both deletes target unhydrated folder rows, so the post-detectMoves
        // filter drops them — safer than emitting cloud del-remote for paths
        // the user never had locally.
        val deletes = actions.filterIsInstance<SyncAction.DeleteRemote>().map { it.path }
        assertTrue(deletes.isEmpty(), "unhydrated-folder deletes must be dropped, got: $deletes")
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
        // UD-225a: hydrated source — non-hydrated rows now trigger UD-225a's
        // rehydrate path instead of DeleteRemote, which would change what
        // detectMoves sees and break this test's invariant.
        val deleteCount = 50
        repeat(deleteCount) { i ->
            db.upsertEntry(dbEntry("/old$i.bin", isHydrated = true))
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
        db.upsertEntry(dbEntry("/old.bin", isHydrated = true)) // UD-225a: hydrated source for legitimate move semantics
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
        db.upsertEntry(dbEntry("/old.bin", isHydrated = true)) // UD-225a: hydrated source for legitimate move semantics
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

    // ── #296: detectMoves file-move needs a structural anchor, not size alone ──
    // Deleting /a/report.pdf and creating an unrelated same-size /b/photo.jpg in
    // the same pass used to be collapsed into a remote rename: the cloud kept
    // report.pdf's bytes under the new name and photo.jpg's real content was
    // never uploaded. A move candidate now needs same basename (cross-folder
    // move) or same parent (rename in place) on top of the size match, and 2+
    // anchored candidates are ambiguous — fall back to delete + upload.

    @Test
    fun `detectMoves does not pair unrelated same-size files across folders`() {
        db.upsertEntry(dbEntry("/a/report.pdf", isHydrated = true))
        Files.createDirectories(syncRoot.resolve("b"))
        Files.writeString(syncRoot.resolve("b/photo.jpg"), "x".repeat(100))

        val localChanges =
            mapOf(
                "/a/report.pdf" to ChangeState.DELETED,
                "/b/photo.jpg" to ChangeState.NEW,
            )

        val actions = reconciler.reconcile(emptyMap(), localChanges)
        assertTrue(actions.none { it is SyncAction.MoveRemote }, "no move expected: $actions")
        assertTrue(actions.any { it is SyncAction.DeleteRemote && it.path == "/a/report.pdf" })
        assertTrue(actions.any { it is SyncAction.Upload && it.path == "/b/photo.jpg" })
    }

    @Test
    fun `detectMoves pairs same-basename same-size files across folders`() {
        db.upsertEntry(dbEntry("/a/x.pdf", isHydrated = true))
        Files.createDirectories(syncRoot.resolve("b"))
        Files.writeString(syncRoot.resolve("b/x.pdf"), "x".repeat(100))

        val localChanges =
            mapOf(
                "/a/x.pdf" to ChangeState.DELETED,
                "/b/x.pdf" to ChangeState.NEW,
            )

        val actions = reconciler.reconcile(emptyMap(), localChanges)
        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(1, moves.size, "expected one MoveRemote, got: $actions")
        assertEquals("/b/x.pdf", moves[0].path)
        assertEquals("/a/x.pdf", moves[0].fromPath)
        assertTrue(actions.none { it is SyncAction.DeleteRemote && it.path == "/a/x.pdf" })
        assertTrue(actions.none { it is SyncAction.Upload && it.path == "/b/x.pdf" })
    }

    @Test
    fun `detectMoves rejects ambiguous same-size same-basename tie`() {
        db.upsertEntry(dbEntry("/a/x.pdf", isHydrated = true))
        Files.createDirectories(syncRoot.resolve("b"))
        Files.createDirectories(syncRoot.resolve("c"))
        Files.writeString(syncRoot.resolve("b/x.pdf"), "x".repeat(100))
        Files.writeString(syncRoot.resolve("c/x.pdf"), "y".repeat(100))

        val localChanges =
            mapOf(
                "/a/x.pdf" to ChangeState.DELETED,
                "/b/x.pdf" to ChangeState.NEW,
                "/c/x.pdf" to ChangeState.NEW,
            )

        val actions = reconciler.reconcile(emptyMap(), localChanges)
        assertTrue(actions.none { it is SyncAction.MoveRemote }, "ambiguous tie must not move: $actions")
        assertTrue(actions.any { it is SyncAction.DeleteRemote && it.path == "/a/x.pdf" })
        assertTrue(actions.any { it is SyncAction.Upload && it.path == "/b/x.pdf" })
        assertTrue(actions.any { it is SyncAction.Upload && it.path == "/c/x.pdf" })
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

    // ── Restore-from-trash (un-trash on scan) ───────────────────────────────

    @Test
    fun `restore from trash — local copy gone schedules re-download`() {
        // A previously-EXISTS row got trashed (cloud delete). Local file is
        // also gone. The cloud now reports the item alive again — the
        // reconciler must flip status back to EXISTS and schedule
        // DownloadContent for the missing local copy.
        db.upsertEntry(dbEntry("/restore.txt"))
        assertTrue(db.setStatusTrashed("id-/restore.txt"))
        assertNull(db.getEntry("/restore.txt"), "trashed row is not in alive view pre-restore")
        // syncRoot does NOT contain the local file (default tearDown state).

        val remoteChanges = mapOf("/restore.txt" to cloudItem("/restore.txt"))
        val actions = reconciler.reconcile(remoteChanges, emptyMap())

        // Status flipped back to EXISTS.
        assertNotNull(db.getEntry("/restore.txt"), "row is back in alive view post-restore")
        // Re-download scheduled.
        assertEquals(1, actions.size)
        assertIs<SyncAction.DownloadContent>(actions[0])
        assertEquals("/restore.txt", actions[0].path)
    }

    @Test
    fun `restore from trash — local copy present hash matches is no-op`() {
        // Pre-redesign incident shape: trashed row, but the local copy
        // survived (user never deleted it locally; only the cloud copy was
        // trashed). When the cloud reports the item alive again, the
        // reconciler flips status but emits NO action — local + cloud
        // already match.
        db.upsertEntry(dbEntry("/keep.txt", isHydrated = true))
        assertTrue(db.setStatusTrashed("id-/keep.txt"))
        // Create the local file with matching size (100 bytes — dbEntry default).
        val localPath = syncRoot.resolve("keep.txt")
        Files.write(localPath, ByteArray(100))

        val remoteChanges = mapOf("/keep.txt" to cloudItem("/keep.txt"))
        // LocalScanner would have reported localState=NEW here (the alive
        // view didn't have the row pre-flip, so the scanner had no
        // dbEntry hit). The reconciler must NOT translate that NEW into
        // an Upload — the resurrected-path skip prevents the "Local only
        // changes" branch from running on the flipped row.
        val actions = reconciler.reconcile(remoteChanges, mapOf("/keep.txt" to ChangeState.NEW))

        // Row is alive.
        assertNotNull(db.getEntry("/keep.txt"))
        // No action.
        assertTrue(
            actions.isEmpty(),
            "local matches cloud — un-trash must be a no-op; got $actions",
        )
    }

    @Test
    fun `restore from trash — folder flip is metadata only, no DownloadContent`() {
        // Folders never download content; the alive flip is sufficient.
        val folder =
            SyncEntry(
                path = "/folder",
                remoteId = "id-/folder",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            )
        db.upsertEntry(folder)
        assertTrue(db.setStatusTrashed("id-/folder"))

        val remoteChanges = mapOf(
            "/folder" to cloudItem("/folder", isFolder = true, hash = null),
        )
        val actions = reconciler.reconcile(remoteChanges, emptyMap())

        // Row alive again, no DownloadContent emitted for a folder.
        assertNotNull(db.getEntry("/folder"))
        assertTrue(
            actions.none { it is SyncAction.DownloadContent },
            "folder restore must not schedule a DownloadContent; got $actions",
        )
    }

    @Test
    fun `cloud-reports-deleted on alive row still emits DeleteLocal (engine flips status)`() {
        // Reconciler's behaviour: a previously-EXISTS row that the cloud
        // now reports as deleted (no local-side change) emits DeleteLocal.
        // The status flip happens at applyDeleteLocal in SyncEngine — this
        // test pins the reconciler half (it must NOT skip the path just
        // because the new schema added tombstones).
        db.upsertEntry(dbEntry("/gone.txt"))
        val remoteChanges = mapOf("/gone.txt" to cloudItem("/gone.txt", deleted = true))
        val actions = reconciler.reconcile(remoteChanges, emptyMap())
        assertEquals(1, actions.size)
        assertIs<SyncAction.DeleteLocal>(actions[0])
    }

    // ---- StreamingReconcileBuffer ----
    //
    // The buffer is an engine-internal layer that splits per-page reconciler
    // verdicts into safe-now (additive) and deferred (deletion-bearing)
    // slices. Tests live alongside ReconcilerTest because the buffer's
    // contract is "given a Reconciler.reconcile output, classify it" —
    // the action shapes belong to the reconciler's vocabulary.

    @Test
    fun `streaming buffer routes additive actions to safe-now`() {
        val buf = StreamingReconcileBuffer()
        val download = SyncAction.DownloadContent("/new.txt", cloudItem("/new.txt"))
        val upload = SyncAction.Upload("/local-new.txt")
        val mkdir = SyncAction.CreateRemoteFolder("/local-dir")
        val placeholder =
            SyncAction.CreatePlaceholder("/p.txt", cloudItem("/p.txt"), shouldHydrate = false)
        val update =
            SyncAction.UpdatePlaceholder("/u.txt", cloudItem("/u.txt"), wasHydrated = false)
        val safeNow = buf.classify(listOf(download, upload, mkdir, placeholder, update))
        assertEquals(5, safeNow.size, "all additive actions fire per page")
        assertEquals(0, buf.drainDeferred().size, "no deferred actions for additive verdict")
    }

    @Test
    fun `streaming buffer routes deletion-bearing actions to deferred`() {
        val buf = StreamingReconcileBuffer()
        val delLocal = SyncAction.DeleteLocal("/gone.txt")
        val delRemote = SyncAction.DeleteRemote("/local-rm.txt")
        val removeEntry = SyncAction.RemoveEntry("/both-gone.txt")
        val deletedConflict =
            SyncAction.Conflict(
                "/dm.txt",
                ChangeState.DELETED,
                ChangeState.MODIFIED,
                cloudItem("/dm.txt"),
                ConflictPolicy.KEEP_BOTH,
            )
        val safeNow =
            buf.classify(listOf(delLocal, delRemote, removeEntry, deletedConflict))
        assertEquals(0, safeNow.size, "deletion-bearing actions never fire per page")
        val deferred = buf.drainDeferred()
        assertEquals(4, deferred.size)
        assertTrue(deferred.any { it is SyncAction.DeleteLocal && it.path == "/gone.txt" })
        assertTrue(deferred.any { it is SyncAction.DeleteRemote && it.path == "/local-rm.txt" })
        assertTrue(deferred.any { it is SyncAction.RemoveEntry && it.path == "/both-gone.txt" })
        assertTrue(deferred.any { it is SyncAction.Conflict && it.path == "/dm.txt" })
    }

    @Test
    fun `streaming buffer keeps MODIFIED+MODIFIED conflicts safe-now`() {
        // Only DELETED-bearing conflicts defer; normal merge conflicts
        // surface as each page lands.
        val buf = StreamingReconcileBuffer()
        val mergeConflict =
            SyncAction.Conflict(
                "/m.txt",
                ChangeState.MODIFIED,
                ChangeState.MODIFIED,
                cloudItem("/m.txt"),
                ConflictPolicy.KEEP_BOTH,
            )
        val safeNow = buf.classify(listOf(mergeConflict))
        assertEquals(1, safeNow.size)
        assertEquals(0, buf.drainDeferred().size)
    }

    @Test
    fun `streaming buffer routes Move actions to safe-now (paired within page)`() {
        val buf = StreamingReconcileBuffer()
        val moveLocal =
            SyncAction.MoveLocal("/new/path.txt", "/old/path.txt", cloudItem("/new/path.txt"))
        val moveRemote = SyncAction.MoveRemote("/new/up.txt", "/old/up.txt", "remote-id")
        val safeNow = buf.classify(listOf(moveLocal, moveRemote))
        assertEquals(2, safeNow.size)
        assertEquals(0, buf.drainDeferred().size)
    }

    @Test
    fun `streaming buffer last-write-wins on (path, action-class) deferred slot`() {
        // Same path, same action-class across two pages: the later page
        // overwrites the earlier. Lets a streaming rename detector
        // supersede an inferred DeleteLocal with a MoveLocal when the
        // matching remote arrives on the next page.
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/x.txt")))
        buf.classify(listOf(SyncAction.DeleteLocal("/x.txt"))) // same shape — replaces
        val deferred = buf.drainDeferred()
        assertEquals(1, deferred.size)
    }

    @Test
    fun `streaming buffer touchedPaths is union of safe-fired + deferred`() {
        // detectMissingAfterFullSync at scan-end needs the union so
        // already-fired-or-buffered paths don't synthesise a phantom
        // DeleteLocal a second time.
        val buf = StreamingReconcileBuffer()
        buf.classify(
            listOf(
                SyncAction.DownloadContent("/safe.txt", cloudItem("/safe.txt")),
                SyncAction.DeleteLocal("/deferred.txt"),
            ),
        )
        val touched = buf.touchedPaths()
        assertEquals(setOf("/safe.txt", "/deferred.txt"), touched)
    }

    @Test
    fun `streaming buffer drainDeferred clears the slot for re-use`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/x.txt")))
        assertEquals(1, buf.drainDeferred().size)
        // Second drain returns nothing — the slot is fresh.
        assertEquals(0, buf.drainDeferred().size)
    }

    @Test
    fun `resolveSlice on remote-new page emits DownloadContent`() {
        // Per-page reconciliation of a page that contains only remote-new
        // items: same per-path verdict as the single-shot reconcile, but
        // without recovery loops or final sort firing.
        val pageRemote = mapOf("/a.txt" to cloudItem("/a.txt"))
        val actions = reconciler.resolveSlice(pageRemote, emptyMap(), null)
        assertEquals(1, actions.size)
        assertIs<SyncAction.DownloadContent>(actions[0])
        assertEquals("/a.txt", actions[0].path)
    }

    @Test
    fun `resolveSlice with empty page returns empty`() {
        val actions = reconciler.resolveSlice(emptyMap(), emptyMap(), null)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `resolveSlice respects excludePatterns`() {
        val excluded = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, excludePatterns = listOf("**.tmp"))
        val pageRemote =
            mapOf(
                "/keep.txt" to cloudItem("/keep.txt"),
                "/skip.tmp" to cloudItem("/skip.tmp"),
            )
        val actions = excluded.resolveSlice(pageRemote, emptyMap(), null)
        assertEquals(1, actions.size)
        assertEquals("/keep.txt", actions[0].path)
    }

    @Test
    fun `resolveSlice respects syncPath scope filter`() {
        val pageRemote =
            mapOf(
                "/in/x.txt" to cloudItem("/in/x.txt"),
                "/out/y.txt" to cloudItem("/out/y.txt"),
            )
        val actions = reconciler.resolveSlice(pageRemote, emptyMap(), syncPath = "/in")
        assertEquals(1, actions.size)
        assertEquals("/in/x.txt", actions[0].path)
    }

    @Test
    fun `finalizeStreaming surfaces UD-901 pending-upload recovery`() {
        // A hydrated DB row with remoteId=null + the corresponding local
        // file represents an interrupted upload. The recovery loop at the
        // bottom of [reconcile] (and now [finalizeStreaming]) emits an
        // Upload so the next sync drains it.
        Files.createFile(syncRoot.resolve("orphan.bin"))
        db.upsertEntry(
            SyncEntry(
                path = "/orphan.bin",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 1711627200000,
                localSize = 0,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        // Streaming would have emitted nothing for this path (no remote
        // page touches it, no local change). Finalize fills the gap.
        val finalized = reconciler.finalizeStreaming(emptyList(), emptyMap(), emptyMap(), null)
        assertEquals(1, finalized.size)
        assertIs<SyncAction.Upload>(finalized[0])
        assertEquals("/orphan.bin", finalized[0].path)
    }

    @Test
    fun `resolveSlice on coalesced page sees the latest path for a renamed remote`() {
        // Spec §3 scenario: the lookahead buffer in gatherStreamingChanges
        // collapses page-N's /old.txt (id=id-X) with page-N+1's /new.txt
        // (id=id-X) before reconciliation. The merged page that reaches
        // resolveSlice carries the new path with the original id; the
        // reconciler then needs to surface this as a rename — which it
        // does by emitting a DownloadContent/CreatePlaceholder at the
        // new path, which finalizeStreaming's detectRemoteRenames then
        // turns into a MoveLocal against the DB row for the old path.
        db.upsertEntry(dbEntry("/old.txt").copy(remoteId = "id-X", remoteHash = "h"))
        // Coalesced slice from the lookahead: id-X now at /new.txt.
        val coalesced =
            mapOf(
                "/new.txt" to
                    CloudItem(
                        id = "id-X",
                        name = "new.txt",
                        path = "/new.txt",
                        size = 100,
                        isFolder = false,
                        modified = Instant.parse("2026-03-28T12:00:00Z"),
                        created = null,
                        hash = "h2",
                        mimeType = null,
                    ),
            )
        val pageActions = reconciler.resolveSlice(coalesced, emptyMap(), null)
        val finalized = reconciler.finalizeStreaming(pageActions, coalesced, emptyMap(), null)
        // After finalize there should be a single MoveLocal, NOT a
        // DownloadContent for /new.txt plus a DeleteLocal of /old.txt.
        val moves = finalized.filterIsInstance<SyncAction.MoveLocal>()
        assertEquals(1, moves.size)
        assertEquals("/new.txt", moves[0].path)
        assertEquals("/old.txt", moves[0].fromPath)
        assertTrue(finalized.none { it is SyncAction.DownloadContent && it.path == "/new.txt" })
    }

    @Test
    fun `finalizeStreaming runs cross-page move detection on safe-fired creates`() {
        // Page 1 streamed a DeleteRemote(/old.txt); page 2 streamed an
        // Upload(/new.txt) of matching size. Per-page reconcile saw each
        // half separately and emitted both verbatim. finalizeStreaming
        // runs detectMoves over the union and converts the pair into a
        // MoveRemote — same shape the single-shot reconciler produces
        // when both halves land in the same call.
        Files.createFile(syncRoot.resolve("new.txt"))
        Files.write(syncRoot.resolve("new.txt"), ByteArray(100))
        db.upsertEntry(dbEntry("/old.txt"))
        val streamed =
            listOf(
                SyncAction.DeleteRemote("/old.txt"),
                SyncAction.Upload("/new.txt"),
            )
        val finalized = reconciler.finalizeStreaming(streamed, emptyMap(), emptyMap(), null)
        assertEquals(1, finalized.size)
        val move = assertIs<SyncAction.MoveRemote>(finalized[0])
        assertEquals("/new.txt", move.path)
        assertEquals("/old.txt", move.fromPath)
    }

    @Test
    fun `default exclude patterns match their target paths at root and nested`() {
        fun m(path: String, pat: String) = Reconciler.matchesGlob(path, pat)
        // ~$* — the $ must be escaped (else-branch Regex.escape), root + nested
        assertTrue(m("/~\$report.docx", "**/~\$*"))
        assertTrue(m("/Documents/~\$report.docx", "**/~\$*"))
        // **/ matches both root-level and nested
        assertTrue(m("/.directory.lock", "**/.directory.lock"))
        assertTrue(m("/a/b/.directory.lock", "**/.directory.lock"))
        // *.swp matches dotfile-prefixed swap (vim writes .name.swp)
        assertTrue(m("/.notes.txt.swp", "**/*.swp"))
        // negative: a real file must NOT match a junk pattern
        assertFalse(m("/report.docx", "**/~\$*"))
        assertFalse(m("/important.txt", "**/*.tmp"))
    }
}
