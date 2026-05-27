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
     * the locale name.  The local folder is adopted as the canonical cloud folder,
     * and a new local file under the alias (`/Bilder/new.jpg`) plans an upload
     * under the canonical path (`/Pictures/new.jpg`), not the alias path.
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

        // (a) No CreateRemoteFolder for /Bilder
        val mkdirs = actions.filterIsInstance<SyncAction.CreateRemoteFolder>().map { it.path }
        assertFalse(
            mkdirs.any { it == "/Bilder" },
            "Must NOT emit CreateRemoteFolder for the locale alias /Bilder; got mkdirs=$mkdirs",
        )

        // (b) No duplicate parallel tree: there must be no CreateRemoteFolder at all
        //     for any alias-derived path.
        assertFalse(
            mkdirs.any { it.startsWith("/Bilder") },
            "Must NOT create any folder under /Bilder; got mkdirs=$mkdirs",
        )

        // (c) The new local file under the alias folder uploads to the canonical path.
        val uploads = actions.filterIsInstance<SyncAction.Upload>().map { it.path }
        assertTrue(
            uploads.any { it == "/Pictures/new.jpg" },
            "Local /Bilder/new.jpg must upload as /Pictures/new.jpg; got uploads=$uploads",
        )
        assertFalse(
            uploads.any { it.startsWith("/Bilder") },
            "No upload must reference the alias path /Bilder; got uploads=$uploads",
        )

        // (d) The cloud folder /Pictures is adopted (CreatePlaceholder), not a conflict.
        val placeholders = actions.filterIsInstance<SyncAction.CreatePlaceholder>().map { it.path }
        assertTrue(
            placeholders.any { it == "/Pictures" },
            "Cloud /Pictures folder must be adopted as a placeholder; got placeholders=$placeholders",
        )

        // (e) Cloud file /Pictures/cloud-photo.jpg is downloaded (no Bilder-prefixed download).
        val downloads = actions.filterIsInstance<SyncAction.DownloadContent>().map { it.path }
        assertTrue(
            downloads.any { it == "/Pictures/cloud-photo.jpg" },
            "Cloud /Pictures/cloud-photo.jpg must be downloaded; got downloads=$downloads",
        )
        assertFalse(
            downloads.any { it.startsWith("/Bilder") },
            "No download must reference /Bilder; got downloads=$downloads",
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
     * `Bilder/b.jpg`) must be detected as a server-side move
     * (`MoveRemote(/Pictures/a.jpg → /Pictures/b.jpg)`) and NOT degrade to a
     * `DeleteRemote + Upload` re-upload pair.
     *
     * Pre-fix: [detectMoves] probed `resolveLocal(up.path)` = syncRoot/Pictures/b.jpg
     * which doesn't exist on disk (the real file is at syncRoot/Bilder/b.jpg), so
     * the upload was excluded from size-based move detection.
     */
    @Test
    fun `file_move_within_aliased_folder_is_detected_as_server_side_move`() {
        val fileSize = 42

        // DB: /Pictures/a.jpg is tracked as a synced file (remoteId + remoteSize)
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = "/Pictures/a.jpg",
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
        assertEquals("/Pictures/b.jpg", moves[0].path, "MoveRemote destination must be canonical path")
        assertEquals("/Pictures/a.jpg", moves[0].fromPath, "MoveRemote source must be canonical path")

        // The Delete + Upload pair must have been collapsed — must NOT appear separately.
        assertFalse(
            actions.any { it is SyncAction.DeleteRemote && it.path == "/Pictures/a.jpg" },
            "DeleteRemote for /Pictures/a.jpg must be collapsed into MoveRemote; got: $actions",
        )
        assertFalse(
            actions.any { it is SyncAction.Upload && it.path == "/Pictures/b.jpg" },
            "Upload for /Pictures/b.jpg must be collapsed into MoveRemote; got: $actions",
        )
    }

    /**
     * Invariant: the streaming path must alias local Bilder/... to
     * Pictures/... even when the canonical /Pictures folder entry lands
     * on a DIFFERENT page than the child items being reconciled.
     *
     * Scenario:
     *   page 1 → /Pictures (folder) only — establishes canonical name
     *   page 2 → /Pictures/photo.jpg (child) — but local has /Bilder/photo.jpg NEW
     *
     * Without the fix: resolveSlice(page2, localChanges) builds alias context
     * from page2 alone, which has no top-level /Pictures folder → alias is
     * empty → emits CreateRemoteFolder("/Bilder") + Upload("/Bilder/photo.jpg").
     *
     * With the fix: resolveSlice receives the full stable set of remote top-level
     * folder names ({"Pictures"}) — derived from ALL pages before slice emission —
     * so the alias fires and NO /Bilder-prefixed action is emitted.
     */
    @Test
    fun `streaming_reconcile_aliases_against_full_remote_top_level_not_just_page`() {
        // Local filesystem: /Bilder/photo.jpg exists (the aliased local file)
        mkLocalDir("/Bilder")
        mkLocalFile("/Bilder/photo.jpg", size = 77)

        val localChanges = mapOf(
            "/Bilder" to ChangeState.NEW,
            "/Bilder/photo.jpg" to ChangeState.NEW,
        )

        // page 1 carries the canonical /Pictures folder — establishes alias
        val page1Remote = mapOf(
            "/Pictures" to cloudItem("/Pictures", isFolder = true),
        )
        // page 2 carries the child item — NO /Pictures top-level entry
        val page2Remote = mapOf(
            "/Pictures/photo.jpg" to cloudItem("/Pictures/photo.jpg", size = 77),
        )

        // Full stable set of remote top-level names — known across all pages
        val stableTopLevelNames = setOf("Pictures")

        val reconciler = Reconciler(
            db, syncRoot, ConflictPolicy.KEEP_BOTH,
            xdgUserDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Bilder"),
        )

        // Simulate the streaming path: reconcile each page slice in isolation,
        // passing the FULL stable top-level name set (the fix) rather than
        // deriving it only from the current page's entries.
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

        val allActions = actionsPage1 + actionsPage2

        // No /Bilder-prefixed action must appear anywhere across both slices.
        val bilder = allActions.filter { it.path.startsWith("/Bilder") }
        assertTrue(
            bilder.isEmpty(),
            "No action must reference /Bilder across streaming slices; got: $bilder",
        )

        // The upload must reference the canonical path, not the alias.
        val uploads = allActions.filterIsInstance<SyncAction.Upload>().map { it.path }
        assertTrue(
            uploads.any { it == "/Pictures/photo.jpg" },
            "Local /Bilder/photo.jpg must upload as /Pictures/photo.jpg; got uploads=$uploads",
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
        assertFalse(mkdirs.any { it.startsWith("/Bilder") || it.startsWith("/Musik") },
            "No CreateRemoteFolder for either alias; got mkdirs=$mkdirs")

        val uploads = actions.filterIsInstance<SyncAction.Upload>().map { it.path }
        assertTrue(uploads.any { it == "/Pictures/photo.jpg" },
            "photo.jpg must upload to canonical path; got uploads=$uploads")
        assertTrue(uploads.any { it == "/Music/song.mp3" },
            "song.mp3 must upload to canonical path; got uploads=$uploads")
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
