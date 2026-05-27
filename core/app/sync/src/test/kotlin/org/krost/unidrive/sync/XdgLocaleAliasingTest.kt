package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * Tests for XDG-user-dir locale alias resolution (#115).
 *
 * All tests use in-memory fake data (no live network, no host filesystem
 * dependencies) — user-dirs.dirs content is injected via
 * [Reconciler.xdgUserDirsOverrides], and remoteChanges/localChanges are
 * built by hand.
 */
class XdgLocaleAliasingTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-xdg-alias-test")
        val dbPath = Files.createTempDirectory("unidrive-xdg-alias-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun cloudItem(
        path: String,
        isFolder: Boolean = false,
        size: Long = 0,
        hash: String? = if (isFolder) null else "abc",
    ) = CloudItem(
        id = "id-${path.replace('/', '-')}",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-01-01T00:00:00Z"),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = hash,
        mimeType = if (isFolder) null else "application/octet-stream",
    )

    /** Create a real directory inside the syncRoot for the given relative path. */
    private fun mkLocalDir(relPath: String) {
        Files.createDirectories(syncRoot.resolve(relPath.removePrefix("/")))
    }

    /** Create a real file inside the syncRoot for the given relative path. */
    private fun mkLocalFile(relPath: String, size: Int = 42) {
        val abs = syncRoot.resolve(relPath.removePrefix("/"))
        Files.createDirectories(abs.parent)
        Files.write(abs, ByteArray(size))
    }

    // ── Test 1 ───────────────────────────────────────────────────────────────

    /**
     * Invariant: when the cloud already holds a canonical XDG folder (e.g.
     * `/Pictures`) and the local sync root contains the locale-renamed equivalent
     * (e.g. `/Bilder`), the reconciler must NOT emit a `CreateRemoteFolder` for
     * the locale name.  The local folder is adopted as the canonical cloud folder.
     *
     * Transparent-adoption model (#115): every action's `path` stays the REAL
     * local path (`/Bilder/...`); REMOTE-write actions carry the canonical
     * remote path out-of-band via `remoteTarget`. So a new local file under the
     * alias (`/Bilder/new.jpg`) plans an Upload with `path=/Bilder/new.jpg` and
     * `remoteTarget=/Pictures/new.jpg`. A cloud file under the canonical
     * (`/Pictures/cloud-photo.jpg`) plans a DownloadContent with `path` reverse-
     * mapped to the real local folder (`/Bilder/cloud-photo.jpg`) so the bytes
     * land in the locale-named folder, while the CloudItem keeps the canonical
     * remote path for the provider fetch.
     *
     * Pre-fix behaviour: `/Bilder` was seen as a brand-new local folder →
     * `CreateRemoteFolder("/Bilder")` emitted; `/Bilder/new.jpg` uploaded to
     * `/Bilder/new.jpg` (folder-not-found on apply).
     */
    @Test
    fun `local_locale_folder_adopts_existing_cloud_canonical_no_duplicate_mkdir`() {
        // Cloud state: /Pictures (folder) + /Pictures/cloud-photo.jpg (file)
        val remoteChanges = mapOf(
            "/Pictures" to cloudItem("/Pictures", isFolder = true),
            "/Pictures/cloud-photo.jpg" to cloudItem("/Pictures/cloud-photo.jpg", size = 100),
        )

        // Local sync root: /Bilder (the German locale name for Pictures)
        //                  /Bilder/new.jpg  (a new local file to upload)
        mkLocalDir("/Bilder")
        mkLocalFile("/Bilder/new.jpg", size = 77)

        val localChanges = mapOf(
            "/Bilder" to ChangeState.NEW,
            "/Bilder/new.jpg" to ChangeState.NEW,
        )

        // user-dirs.dirs says XDG_PICTURES_DIR is "Bilder"
        val reconciler = Reconciler(
            db, syncRoot, ConflictPolicy.KEEP_BOTH,
            xdgUserDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Bilder"),
        )

        val actions = reconciler.reconcile(remoteChanges, localChanges)

        // (a) No CreateRemoteFolder at all for the adopted alias folder.
        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>()
        assertTrue(
            mkdirs.none { it.path == "/Bilder" || it.path.startsWith("/Bilder/") },
            "Must NOT emit CreateRemoteFolder for the adopted alias folder; got mkdirs=${mkdirs.map { it.path }}",
        )

        // (b) The new local file uploads with real-local path + canonical remoteTarget.
        val uploads = actions.filterIsInstance<SyncAction.Upload>()
        val newUpload = uploads.find { it.path == "/Bilder/new.jpg" }
        assertNotNull(
            newUpload,
            "Upload.path must stay the real local path /Bilder/new.jpg; got uploads=${uploads.map { it.path }}",
        )
        assertEquals(
            "/Pictures/new.jpg", newUpload.remoteTarget,
            "Upload.remoteTarget must be the canonical /Pictures/new.jpg",
        )

        // (c) The cloud folder is adopted (CreatePlaceholder) at the real-local path.
        val placeholders = actions.filterIsInstance<SyncAction.CreatePlaceholder>()
        val folderPh = placeholders.find { it.path == "/Bilder" }
        assertNotNull(
            folderPh,
            "Cloud folder must be adopted as a placeholder at the real-local /Bilder; " +
                "got placeholders=${placeholders.map { it.path }}",
        )
        assertEquals(
            "/Pictures", folderPh.remoteItem.path,
            "The adopted placeholder's CloudItem keeps the canonical remote path",
        )

        // (d) The cloud file downloads to the real-local /Bilder folder, while the
        //     CloudItem keeps the canonical remote path for the provider fetch.
        val downloads = actions.filterIsInstance<SyncAction.DownloadContent>()
        val dl = downloads.find { it.path == "/Bilder/cloud-photo.jpg" }
        assertNotNull(
            dl,
            "Cloud file must download to the real-local /Bilder/cloud-photo.jpg; " +
                "got downloads=${downloads.map { it.path }}",
        )
        assertEquals(
            "/Pictures/cloud-photo.jpg", dl.remoteItem.path,
            "DownloadContent's CloudItem keeps the canonical remote path",
        )
    }

    // ── Test 2 ───────────────────────────────────────────────────────────────

    /**
     * Regression guard: when the cloud has NO folder that is an alias of the
     * local folder name, the local folder must be created normally (alias logic
     * must not suppress a legitimately-new folder).
     */
    @Test
    fun `local_folder_with_no_cloud_alias_is_created_normally`() {
        // Cloud state: only /Documents — no Pictures/Bilder alias.
        val remoteChanges = mapOf(
            "/Documents" to cloudItem("/Documents", isFolder = true),
        )

        // Local: /Bilder (new folder not aliased to anything on cloud)
        mkLocalDir("/Bilder")
        mkLocalFile("/Bilder/holiday.jpg")

        val localChanges = mapOf(
            "/Bilder" to ChangeState.NEW,
            "/Bilder/holiday.jpg" to ChangeState.NEW,
        )

        // user-dirs.dirs says XDG_PICTURES_DIR is "Bilder" — but no Pictures on cloud
        val reconciler = Reconciler(
            db, syncRoot, ConflictPolicy.KEEP_BOTH,
            xdgUserDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Bilder"),
        )

        val actions = reconciler.reconcile(remoteChanges, localChanges)

        // /Bilder must get a CreateRemoteFolder because no cloud alias exists.
        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        assertTrue(
            mkdirs.any { it == "/Bilder" },
            "Local /Bilder (no cloud alias) must emit CreateRemoteFolder; got mkdirs=$mkdirs",
        )

        // The file under /Bilder must upload under /Bilder (no translation).
        val uploads = actions.filterIsInstance<SyncAction.Upload>().map { it.path }
        assertTrue(
            uploads.any { it == "/Bilder/holiday.jpg" },
            "Without cloud alias, /Bilder/holiday.jpg must upload as-is; got uploads=$uploads",
        )
    }

    // ── Additional edge-case tests ────────────────────────────────────────────

    /**
     * Edge case: static alias table works even without user-dirs.dirs override.
     * The table alone should resolve "Bilder" → "Pictures" when the cloud has
     * "/Pictures".
     */
    @Test
    fun `static alias table resolves locale name without user-dirs override`() {
        val remoteChanges = mapOf(
            "/Pictures" to cloudItem("/Pictures", isFolder = true),
        )
        mkLocalDir("/Bilder")

        val localChanges = mapOf("/Bilder" to ChangeState.NEW)

        // No xdgUserDirsOverrides — static table only
        val reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)

        val actions = reconciler.reconcile(remoteChanges, localChanges)

        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        assertFalse(
            mkdirs.any { it == "/Bilder" || it.startsWith("/Bilder") },
            "Static alias table must suppress CreateRemoteFolder for /Bilder; got mkdirs=$mkdirs",
        )
    }

    /**
     * Edge case: non-XDG folder is not affected.  A folder named "Archive"
     * is not in any alias group and must be created normally.
     */
    @Test
    fun `non_xdg_folder_is_not_aliased`() {
        val remoteChanges = mapOf(
            "/Pictures" to cloudItem("/Pictures", isFolder = true),
        )
        mkLocalDir("/Archive")
        mkLocalFile("/Archive/old.zip")

        val localChanges = mapOf(
            "/Archive" to ChangeState.NEW,
            "/Archive/old.zip" to ChangeState.NEW,
        )

        val reconciler = Reconciler(
            db, syncRoot, ConflictPolicy.KEEP_BOTH,
            xdgUserDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Bilder"),
        )

        val actions = reconciler.reconcile(remoteChanges, localChanges)

        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        assertTrue(
            mkdirs.any { it == "/Archive" },
            "Non-XDG /Archive folder must be created normally; got mkdirs=$mkdirs",
        )
    }

    // ── Test: nested alias name is NOT rewritten ──────────────────────────────

    /**
     * Invariant: alias translation is top-level only.  A path whose SECOND
     * component happens to match a known alias name (e.g. `/SomeDir/Bilder/photo.jpg`)
     * must NOT be rewritten — only `/Bilder/...` (top-level) is an alias.
     */
    @Test
    fun `nested_alias_name_is_not_rewritten`() {
        // Cloud state: /Pictures (canonical XDG folder) + /SomeDir (unrelated folder)
        val remoteChanges = mapOf(
            "/Pictures" to cloudItem("/Pictures", isFolder = true),
            "/SomeDir" to cloudItem("/SomeDir", isFolder = true),
        )
        mkLocalDir("/SomeDir/Bilder")
        mkLocalFile("/SomeDir/Bilder/photo.jpg")

        val localChanges = mapOf(
            "/SomeDir" to ChangeState.UNCHANGED,
            "/SomeDir/Bilder" to ChangeState.NEW,
            "/SomeDir/Bilder/photo.jpg" to ChangeState.NEW,
        )

        val reconciler = Reconciler(
            db, syncRoot, ConflictPolicy.KEEP_BOTH,
            xdgUserDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Bilder"),
        )

        val actions = reconciler.reconcile(remoteChanges, localChanges)

        // The nested /SomeDir/Bilder must be created as-is (not rewritten to /SomeDir/Pictures).
        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        assertTrue(
            mkdirs.any { it == "/SomeDir/Bilder" },
            "Nested /SomeDir/Bilder must be created normally (not rewritten); got mkdirs=$mkdirs",
        )
        assertFalse(
            mkdirs.any { it == "/SomeDir/Pictures" },
            "Must NOT rewrite non-top-level Bilder to /SomeDir/Pictures; got mkdirs=$mkdirs",
        )

        // The file must upload under its original path (no translation).
        val uploads = actions.filterIsInstance<SyncAction.Upload>().map { it.path }
        assertTrue(
            uploads.any { it == "/SomeDir/Bilder/photo.jpg" },
            "Nested path must upload as-is; got uploads=$uploads",
        )
        assertFalse(
            uploads.any { it == "/SomeDir/Pictures/photo.jpg" },
            "Must NOT rewrite nested path to /SomeDir/Pictures/photo.jpg; got uploads=$uploads",
        )
    }

    // ── Test: file move within aliased folder collapses to server-side move ──

    /**
     * Invariant: a file renamed inside an aliased folder (e.g. `Bilder/a.jpg` →
     * `Bilder/b.jpg`) must be detected as a server-side move and NOT degrade to
     * a `DeleteRemote + Upload` re-upload pair.
     *
     * Transparent-adoption model (#115): the DB row is keyed by the REAL local
     * path (`/Bilder/a.jpg`) and carries the canonical remote path
     * (`/Pictures/a.jpg`) in `remotePath`. detectMoves runs on real-local paths,
     * so it pairs DeleteRemote(/Bilder/a.jpg) + Upload(/Bilder/b.jpg) into a
     * MoveRemote whose `path`/`fromPath` are real-local and whose `remoteTarget`
     * is the canonical destination. The canonical remote SOURCE is derived in the
     * executor from the source row's remotePath.
     *
     * Pre-fix: [detectMoves] probed `resolveLocal(canonicalPath)` =
     * syncRoot/Pictures/b.jpg which doesn't exist on disk (the real file is at
     * syncRoot/Bilder/b.jpg), so the upload was excluded from size-based move
     * detection.
     */
    @Test
    fun `file_move_within_aliased_folder_is_detected_as_server_side_move`() {
        val fileSize = 42

        // DB: the row is keyed by the REAL local path /Bilder/a.jpg and carries
        // the canonical remote path /Pictures/a.jpg in remotePath.
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = "/Bilder/a.jpg",
                remotePath = "/Pictures/a.jpg",
                remoteId = "id-pictures-a",
                remoteHash = "hash-a",
                remoteSize = fileSize.toLong(),
                remoteModified = java.time.Instant.parse("2026-01-01T00:00:00Z"),
                localMtime = 1000000L,
                localSize = fileSize.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = java.time.Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )

        // Local filesystem: Bilder/b.jpg exists (same size — the renamed file)
        mkLocalFile("/Bilder/b.jpg", size = fileSize)

        // localChanges: a.jpg deleted, b.jpg appeared — both under the alias name
        val localChanges = mapOf(
            "/Bilder/a.jpg" to ChangeState.DELETED,
            "/Bilder/b.jpg" to ChangeState.NEW,
        )

        // Remote: /Pictures folder exists (establishes canonical name for alias resolution).
        // No delta for /Pictures/a.jpg — the file is unchanged on the cloud side.
        val remoteChanges = mapOf(
            "/Pictures" to cloudItem("/Pictures", isFolder = true),
        )

        val reconciler = Reconciler(
            db, syncRoot, ConflictPolicy.KEEP_BOTH,
            xdgUserDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Bilder"),
        )

        val actions = reconciler.reconcile(remoteChanges, localChanges)

        val moves = actions.filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(
            1, moves.size,
            "Expected one MoveRemote for alias-folder rename; got: $actions",
        )
        assertEquals("/Bilder/b.jpg", moves[0].path, "MoveRemote destination path stays real-local")
        assertEquals("/Bilder/a.jpg", moves[0].fromPath, "MoveRemote source path stays real-local")
        assertEquals(
            "/Pictures/b.jpg", moves[0].remoteTarget,
            "MoveRemote.remoteTarget must be the canonical destination",
        )

        // The Delete + Upload pair must have been collapsed — must NOT appear separately.
        assertFalse(
            actions.any { it is SyncAction.DeleteRemote && it.path == "/Bilder/a.jpg" },
            "DeleteRemote for /Bilder/a.jpg must be collapsed into MoveRemote; got: $actions",
        )
        assertFalse(
            actions.any { it is SyncAction.Upload && it.path == "/Bilder/b.jpg" },
            "Upload for /Bilder/b.jpg must be collapsed into MoveRemote; got: $actions",
        )
    }

    /**
     * Invariant: the streaming path must alias local Bilder/... to
     * Pictures/... regardless of how many pages separate the canonical
     * /Pictures folder entry from the child items being reconciled.
     *
     * Scenario (4 pages — defeats a 1-page lookahead):
     *   page 1 → /Bilder (local NEW) children: /Bilder/photo.jpg NEW
     *   page 2 → unrelated item (/Documents/file.txt)
     *   page 3 → another unrelated item (/Music/song.mp3)
     *   page 4 → /Pictures (canonical folder) — arrives LAST
     *
     * A 1-page lookahead fails here: when page 1's slice fires, the
     * lookahead has only seen page 2 (/Documents/...) — "Pictures" is
     * NOT in the accumulated set → alias is empty → emits
     * CreateRemoteFolder("/Bilder") + Upload("/Bilder/photo.jpg").
     *
     * With the fix (Option A — preliminary root listing): resolveSlice
     * receives the pre-fetched complete set {"Pictures"} for every page,
     * so the alias fires from page 1 onward and NO /Bilder-prefixed
     * action is emitted across any of the 4 slices.
     *
     * Transparent-adoption model (#115): the alias FIRING is now observed via
     * `remoteTarget`/suppressed CreateRemoteFolder, not via a rewritten action
     * `path` (which always stays real-local /Bilder). When the alias does NOT
     * fire (broken empty-set lookahead) the alias folder is created on remote
     * (CreateRemoteFolder("/Bilder")) and the Upload carries NO remoteTarget;
     * when it DOES fire, no CreateRemoteFolder for /Bilder is emitted and the
     * Upload carries remoteTarget=/Pictures/photo.jpg.
     */
    @Test
    fun `streaming_reconcile_aliases_canonical_arriving_pages_later`() {
        // Local filesystem: /Bilder/photo.jpg (the aliased local file)
        mkLocalDir("/Bilder")
        mkLocalFile("/Bilder/photo.jpg", size = 77)

        val localChanges = mapOf(
            "/Bilder" to ChangeState.NEW,
            "/Bilder/photo.jpg" to ChangeState.NEW,
        )

        // page 1: children under the aliased folder — NO top-level canonical entry
        val page1Remote = mapOf(
            "/Pictures/photo.jpg" to cloudItem("/Pictures/photo.jpg", size = 77),
        )
        // pages 2–3: unrelated top-level items (no "Pictures" among them)
        val page2Remote = mapOf(
            "/Documents/file.txt" to cloudItem("/Documents/file.txt", size = 10),
        )
        val page3Remote = mapOf(
            "/Music/song.mp3" to cloudItem("/Music/song.mp3", size = 50),
        )
        // page 4: the canonical /Pictures folder arrives LAST
        val page4Remote = mapOf(
            "/Pictures" to cloudItem("/Pictures", isFolder = true),
        )

        val reconciler = Reconciler(
            db, syncRoot, ConflictPolicy.KEEP_BOTH,
            xdgUserDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Bilder"),
        )

        // ── Part A: confirm the 1-page-lookahead approach FAILS ──────────────
        // Simulate the broken state: when page 1's slice fires under a
        // 1-page lookahead, the look-ahead page is page 2 (/Documents/...) —
        // it adds NO top-level folder name.  Pass emptySet() to model this.
        // With no alias firing, the alias folder is created on remote and the
        // upload carries no remoteTarget (regression sentinel).
        val brokenActionsPage1 = reconciler.resolveSlice(
            pageRemote = page1Remote,
            localChanges = localChanges,
            stableRemoteTopLevelNames = emptySet(),   // lookahead didn't reach /Pictures
        )
        val brokenMkdir = brokenActionsPage1.filterIsInstance<SyncAction.CreateRemoteFolder>()
        val brokenUploadTargets = brokenActionsPage1.filterIsInstance<SyncAction.Upload>().mapNotNull { it.remoteTarget }
        assertTrue(
            brokenMkdir.any { it.path == "/Bilder" } || brokenUploadTargets.isEmpty(),
            "Broken lookahead must fail to adopt: either mkdir /Bilder or no canonical remoteTarget " +
                "(regression sentinel); got brokenActionsPage1=$brokenActionsPage1",
        )

        // ── Part B: confirm the fix (complete pre-fetched set) PASSES ────────
        // The complete set is known upfront via provider.listChildren("/").
        val stableTopLevelNames = setOf("Pictures", "Documents", "Music")

        val actionsPage1 = reconciler.resolveSlice(
            pageRemote = page1Remote,
            localChanges = localChanges,
            stableRemoteTopLevelNames = stableTopLevelNames,
        )
        val actionsPage2 = reconciler.resolveSlice(
            pageRemote = page2Remote,
            localChanges = localChanges,
            stableRemoteTopLevelNames = stableTopLevelNames,
        )
        val actionsPage3 = reconciler.resolveSlice(
            pageRemote = page3Remote,
            localChanges = localChanges,
            stableRemoteTopLevelNames = stableTopLevelNames,
        )
        val actionsPage4 = reconciler.resolveSlice(
            pageRemote = page4Remote,
            localChanges = localChanges,
            stableRemoteTopLevelNames = stableTopLevelNames,
        )

        val allActions = actionsPage1 + actionsPage2 + actionsPage3 + actionsPage4

        // (a) No CreateRemoteFolder for the adopted alias folder across any slice.
        val mkdirs = allActions.filterIsInstance<SyncAction.CreateRemoteFolder>()
        assertTrue(
            mkdirs.none { it.path == "/Bilder" || it.path.startsWith("/Bilder/") },
            "Must NOT emit CreateRemoteFolder for the adopted alias /Bilder; got mkdirs=${mkdirs.map { it.path }}",
        )

        // (b) The upload keeps the real-local path and carries the canonical remoteTarget.
        val uploads = allActions.filterIsInstance<SyncAction.Upload>()
        val photoUpload = uploads.find { it.path == "/Bilder/photo.jpg" }
        assertNotNull(
            photoUpload,
            "Upload.path must stay real-local /Bilder/photo.jpg; got uploads=${uploads.map { it.path }}",
        )
        assertEquals(
            "/Pictures/photo.jpg", photoUpload.remoteTarget,
            "Upload.remoteTarget must be the canonical /Pictures/photo.jpg across all 4 slices",
        )
    }

    /**
     * Edge case: multiple XDG aliases in a single reconcile.  German locale:
     * Pictures→Bilder, Music→Musik — both already on cloud under English names.
     */
    @Test
    fun `multiple_xdg_aliases_resolved_in_one_reconcile`() {
        val remoteChanges = mapOf(
            "/Pictures" to cloudItem("/Pictures", isFolder = true),
            "/Music" to cloudItem("/Music", isFolder = true),
        )
        mkLocalDir("/Bilder")
        mkLocalDir("/Musik")
        mkLocalFile("/Bilder/photo.jpg")
        mkLocalFile("/Musik/song.mp3")

        val localChanges = mapOf(
            "/Bilder" to ChangeState.NEW,
            "/Musik" to ChangeState.NEW,
            "/Bilder/photo.jpg" to ChangeState.NEW,
            "/Musik/song.mp3" to ChangeState.NEW,
        )

        val reconciler = Reconciler(
            db, syncRoot, ConflictPolicy.KEEP_BOTH,
            xdgUserDirsOverrides = mapOf(
                "XDG_PICTURES_DIR" to "Bilder",
                "XDG_MUSIC_DIR" to "Musik",
            ),
        )

        val actions = reconciler.reconcile(remoteChanges, localChanges)

        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        assertFalse(mkdirs.any { it == "/Bilder" || it == "/Musik" || it.startsWith("/Bilder/") || it.startsWith("/Musik/") },
            "No CreateRemoteFolder for either adopted alias; got mkdirs=$mkdirs")

        // Uploads keep real-local paths; remoteTarget carries the canonical path.
        val uploads = actions.filterIsInstance<SyncAction.Upload>()
        val photo = uploads.find { it.path == "/Bilder/photo.jpg" }
        assertNotNull(photo, "photo.jpg upload must keep real-local /Bilder path; got=${uploads.map { it.path }}")
        assertEquals("/Pictures/photo.jpg", photo.remoteTarget, "photo.jpg remoteTarget must be canonical")
        val song = uploads.find { it.path == "/Musik/song.mp3" }
        assertNotNull(song, "song.mp3 upload must keep real-local /Musik path; got=${uploads.map { it.path }}")
        assertEquals("/Music/song.mp3", song.remoteTarget, "song.mp3 remoteTarget must be canonical")
    }
}

// ── Unit tests for XdgLocaleDirAliases.build() ──────────────────────────────

class XdgLocaleDirAliasesTest {
    @Test
    fun `build returns NONE when remote is empty`() {
        val aliases = XdgLocaleDirAliases.build(emptySet())
        assertTrue(aliases.isEmpty)
        assertNull(aliases.canonicalFor("Bilder"))
    }

    @Test
    fun `build maps static alias to canonical when cloud has canonical`() {
        val aliases = XdgLocaleDirAliases.build(setOf("Pictures"))
        assertEquals("Pictures", aliases.canonicalFor("Bilder"))
        assertEquals("Pictures", aliases.canonicalFor("Imágenes"))
        assertEquals("Pictures", aliases.canonicalFor("Images"))
        assertNull(aliases.canonicalFor("Pictures")) // canonical itself is not an alias
    }

    @Test
    fun `build does not alias when canonical name is not on cloud`() {
        val aliases = XdgLocaleDirAliases.build(setOf("Documents"))
        assertNull(aliases.canonicalFor("Bilder")) // Pictures not on cloud
        assertEquals("Documents", aliases.canonicalFor("Dokumente"))
    }

    @Test
    fun `build does not alias a name that is itself a remote folder`() {
        // If the cloud has both "Pictures" and "Bilder" as distinct folders,
        // "Bilder" must NOT be aliased to "Pictures".
        val aliases = XdgLocaleDirAliases.build(setOf("Pictures", "Bilder"))
        assertNull(aliases.canonicalFor("Bilder"))
    }

    @Test
    fun `translatePath rewrites top-level component only`() {
        val aliases = XdgLocaleDirAliases.build(setOf("Pictures"))
        assertEquals("/Pictures/Urlaub/photo.jpg", aliases.translatePath("/Bilder/Urlaub/photo.jpg"))
        assertEquals("/Pictures", aliases.translatePath("/Bilder"))
        // Non-aliased path unchanged
        assertEquals("/Documents/file.txt", aliases.translatePath("/Documents/file.txt"))
    }

    @Test
    fun `translatePath is identity when isEmpty`() {
        assertTrue(XdgLocaleDirAliases.NONE.isEmpty)
        assertEquals("/Bilder/photo.jpg", XdgLocaleDirAliases.NONE.translatePath("/Bilder/photo.jpg"))
    }

    @Test
    fun `static_alias_table_covers_name_in_injected_groups`() {
        // A hypothetical locale name "Kuvat" (Finnish) injected via aliasGroups —
        // exercises the static-table lookup path, not the userDirsOverrides loop.
        val customGroups = listOf(setOf("Pictures", "Bilder", "Kuvat"))
        val aliases = XdgLocaleDirAliases.build(
            remoteTopLevelNames = setOf("Pictures"),
            aliasGroups = customGroups,
        )
        assertEquals("Pictures", aliases.canonicalFor("Kuvat"))
    }

    @Test
    fun `user_dirs_override_resolves_locale_name_absent_from_static_table`() {
        // "Kuvat" (Finnish) is NOT in the production static table.  The second
        // for-loop in XdgLocaleDirAliases.build() handles locale names found in
        // user-dirs.dirs but absent from the static groups — this test exercises
        // that path exclusively (aliasGroups defaults to XDG_ALIAS_GROUPS which
        // does NOT contain "Kuvat").
        val aliases = XdgLocaleDirAliases.build(
            remoteTopLevelNames = setOf("Pictures"),
            userDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Kuvat"),
        )
        assertEquals(
            "Pictures",
            aliases.canonicalFor("Kuvat"),
            "userDirsOverrides loop must resolve 'Kuvat' → 'Pictures' even though " +
                "'Kuvat' is absent from the static alias table",
        )
    }

    @Test
    fun `parseUserDirsFile returns empty map for non-existent path`() {
        val result = parseUserDirsFile(java.nio.file.Paths.get("/nonexistent/user-dirs.dirs"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseUserDirsFile parses standard format`() {
        val content = """
            # This is a comment
            XDG_PICTURES_DIR="${'$'}HOME/Bilder"
            XDG_MUSIC_DIR="${'$'}HOME/Musik"
            XDG_DOWNLOAD_DIR="${'$'}HOME/Downloads"
        """.trimIndent()
        val tmpFile = java.nio.file.Files.createTempFile("user-dirs", ".dirs")
        try {
            java.nio.file.Files.writeString(tmpFile, content)
            val result = parseUserDirsFile(tmpFile)
            assertEquals("Bilder", result["XDG_PICTURES_DIR"])
            assertEquals("Musik", result["XDG_MUSIC_DIR"])
            assertEquals("Downloads", result["XDG_DOWNLOAD_DIR"])
        } finally {
            java.nio.file.Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `parseUserDirsFile handles nested path extracting basename only`() {
        // Some distros write full paths: XDG_PICTURES_DIR="$HOME/Photos/Camera"
        val content = """XDG_PICTURES_DIR="${'$'}HOME/Photos/Camera""""
        val tmpFile = java.nio.file.Files.createTempFile("user-dirs", ".dirs")
        try {
            java.nio.file.Files.writeString(tmpFile, content)
            val result = parseUserDirsFile(tmpFile)
            assertEquals("Camera", result["XDG_PICTURES_DIR"])
        } finally {
            java.nio.file.Files.deleteIfExists(tmpFile)
        }
    }
}
