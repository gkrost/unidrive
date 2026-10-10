package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.sync.setProfileMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #604 U5b: the legacy-profile conversion. The gate the tests pin: every unique local byte
 * survives (retire moves them, quarantine names them), the mode is published only after the
 * byte work, an interrupted run resumes, and nothing runs without the preconditions.
 */
class MigrateConvertTest {
    private fun fixture(): Triple<Path, Path, Path> {
        val configDir = Files.createTempDirectory("u5-config")
        val profileDir = Files.createTempDirectory("u5-profile")
        val syncRoot = Files.createTempDirectory("u5-root")
        val config = configDir.resolve("config.toml")
        config.writeText("[general]\n\n[providers.p]\ntype = \"localfs\"\nsync_root = \"${syncRoot.toString().replace("\\", "\\\\")}\"\n")
        return Triple(config, profileDir, syncRoot)
    }

    private fun setup(
        config: Path,
        profileDir: Path,
        syncRoot: Path,
        cacheDir: Path? = null,
    ) = LegacyConversion.Setup(
        profileName = "p",
        providerType = "localfs",
        profileDir = profileDir,
        syncRoot = syncRoot,
        dbPath = profileDir.resolve("state.db"),
        configFile = config,
        engineVersion = "0.0.1",
        cacheDir = cacheDir,
    )

    // ── setProfileMode ───────────────────────────────────────────────────────

    @Test
    fun `setProfileMode inserts after the type line`() {
        val result = setProfileMode("[general]\n\n[providers.p]\ntype = \"localfs\"\n", "p", "mount")
        val lines = result.lines()
        val typeIdx = lines.indexOfFirst { it.trim().startsWith("type") }
        assertEquals("mode = \"mount\"", lines[typeIdx + 1].trim())
    }

    @Test
    fun `setProfileMode replaces an existing mode in place`() {
        val result = setProfileMode("[providers.p]\ntype = \"localfs\"\nmode = \"mount\"\nsync_root = \"/x\"\n", "p", "mirror")
        assertEquals(1, result.lines().count { it.trim().startsWith("mode") }, "no duplicate mode line")
        assertTrue("mode = \"mirror\"" in result)
        assertTrue("sync_root = \"/x\"" in result, "neighbouring keys survive")
    }

    @Test
    fun `setProfileMode refuses a missing section`() {
        assertFailsWith<IllegalArgumentException> { setProfileMode("[general]\n", "missing", "mount") }
    }

    // ── the conflict-copy leaf name ──────────────────────────────────────────

    @Test
    fun `conflict leaf names match the engine shape`() {
        // The extension stays on the outside, like the conflict copies observed in the cloud (#594):
        // "go1.27.1.windows-amd64 (conflict 2026-10-06).msi".
        assertEquals("a (conflict 2026-10-08).txt", LegacyConversion.conflictLeaf("a", ".txt", "2026-10-08", 1))
        assertEquals("a (conflict 2026-10-08) (2)", LegacyConversion.conflictLeaf("a", "", "2026-10-08", 2))
    }

    // ── mount conversion with retire ─────────────────────────────────────────

    @Test
    fun `a mount conversion retires the sync_root and publishes the mode`() {
        val (config, profileDir, syncRoot) = fixture()
        Files.createDirectories(syncRoot.resolve("sub"))
        syncRoot.resolve("keep.txt").writeText("one")
        syncRoot.resolve("sub").resolve("dir.txt").writeText("two-22")

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "retire", restart = false, verbose = false)

        assertEquals(0, code)
        assertTrue(config.readText().contains("mode = \"mount\""), "the mode is published")
        assertTrue(!Files.exists(syncRoot.resolve("keep.txt")), "the root is emptied of files")
        assertTrue(Files.isDirectory(syncRoot), "the root itself survives, empty")

        val conversionDir =
            Files.list(profileDir).use { s ->
                // Only the conversion DIRECTORY: the journal file also starts with "conversion-",
                // and the listing order is unspecified (it bit on Linux).
                s.filter { it.fileName.toString().startsWith("conversion-") && Files.isDirectory(it) }.findFirst().orElseThrow()
            }
        assertEquals("one", Files.readString(conversionDir.resolve("quarantine/keep.txt")))
        assertEquals("two-22", Files.readString(conversionDir.resolve("quarantine/sub/dir.txt")))
        assertTrue(Files.exists(conversionDir.resolve("manifest.json")), "the manifest is written")

        val journal = LegacyConversion.Journal.load(profileDir)!!
        assertEquals(listOf("PLAN", "INVENTORY", "PRESERVE", "PUBLISH", "CONVERTED"), journal.phases)
        assertEquals(2, journal.quarantined.size, "both files are named for the explicit upload")
        assertTrue(journal.quarantined.all { it.remotePath.startsWith("/") })
    }

    @Test
    fun `a mount conversion without a disposition refuses while the root holds files`() {
        val (config, profileDir, syncRoot) = fixture()
        syncRoot.resolve("f.txt").writeText("x")

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", null, restart = false, verbose = false)

        assertEquals(1, code)
        assertTrue(!config.readText().contains("mode"), "nothing was published")
        assertTrue(Files.exists(syncRoot.resolve("f.txt")), "nothing was moved")
    }

    // ── mirror adoption ──────────────────────────────────────────────────────

    @Test
    fun `a mirror conversion adopts the root and resets the enumeration baseline`() {
        val (config, profileDir, syncRoot) = fixture()
        syncRoot.resolve("local-only.txt").writeText("local bytes")
        val db = StateDatabase(profileDir.resolve("state.db"))
        db.initialize()
        db.setSyncState("last_full_scan", "2026-10-01T00:00:00Z")
        db.setSyncState(StateDatabase.SCAN_IN_PROGRESS_ID, "scan-1")
        db.close()

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(0, code)
        assertTrue(config.readText().contains("mode = \"mirror\""), "the mode is published")
        assertEquals("local bytes", Files.readString(syncRoot.resolve("local-only.txt")), "adopt keeps every file in place")

        val after = StateDatabase(profileDir.resolve("state.db"))
        after.initialize()
        try {
            assertNull(after.getSyncState("last_full_scan"), "the stale baseline is gone")
            assertNull(after.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID), "the scan checkpoint is gone")
        } finally {
            after.close()
        }

        val journal = LegacyConversion.Journal.load(profileDir)!!
        assertTrue(journal.baselineCleared)
        assertTrue("CONVERTED" in journal.phases)
    }

    // ── legacy hybrid profiles (#680) ────────────────────────────────────────

    private fun hydratedRow(
        path: String,
        cacheBacked: Boolean?,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = null,
        remoteSize = 3,
        remoteModified = null,
        localMtime = 1L,
        localSize = 3L,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.parse("2026-10-01T00:00:00Z"),
        cacheBacked = cacheBacked,
    )

    private fun seed(
        profileDir: Path,
        vararg rows: SyncEntry,
    ) {
        val db = StateDatabase(profileDir.resolve("state.db"))
        db.initialize()
        try {
            rows.forEach { db.upsertEntry(it) }
        } finally {
            db.close()
        }
    }

    private fun dump(profileDir: Path): List<SyncEntry> {
        val db = StateDatabase(profileDir.resolve("state.db"))
        db.initialize()
        try {
            return db.getAllEntries().sortedBy { it.path }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a mirror conversion refuses a hybrid profile with cache-backed hydrated rows and changes nothing`() {
        val (config, profileDir, syncRoot) = fixture()
        seed(profileDir, hydratedRow("/cached-true.txt", true), hydratedRow("/cached-null.txt", null))
        val configBefore = config.readText()
        val rowsBefore = dump(profileDir)

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(1, code)
        assertEquals(configBefore, config.readText(), "config.toml is untouched")
        assertEquals(rowsBefore, dump(profileDir), "state.db rows are untouched")
        assertNull(LegacyConversion.Journal.load(profileDir), "no journal is left behind")

        // The refusal pinned nothing: the same profile still converts to a mount.
        val mountCode = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "retire", restart = false, verbose = false)
        assertEquals(0, mountCode)
        assertTrue(config.readText().contains("mode = \"mount\""))
    }

    @Test
    fun `a mirror conversion still succeeds when no hydrated row is cache-backed`() {
        val (config, profileDir, syncRoot) = fixture()
        seed(profileDir, hydratedRow("/synced.txt", false), hydratedRow("/dir", null, isFolder = true))

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(0, code)
        assertTrue(config.readText().contains("mode = \"mirror\""), "the mode is published")
    }

    @Test
    fun `a mount conversion is unaffected by cache-backed hydrated rows`() {
        val (config, profileDir, syncRoot) = fixture()
        seed(profileDir, hydratedRow("/cached-true.txt", true), hydratedRow("/cached-null.txt", null))

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "retire", restart = false, verbose = false)

        assertEquals(0, code)
        assertTrue(config.readText().contains("mode = \"mount\""), "the mode is published")
    }

    // ── #706: NULL cache_backed is what the mirror engine writes ─────────────

    private fun putFile(
        dir: Path,
        path: String,
        content: String,
    ) {
        val f = dir.resolve(path.trimStart('/'))
        Files.createDirectories(f.parent)
        f.writeText(content)
    }

    /** A hydrated file row the way the mirror engine leaves it: no cache_backed value. */
    private fun mirrorRow(path: String) = hydratedRow(path, null)

    @Test
    fun `a profile populated only by the mirror engine converts to a mirror`() {
        val (config, profileDir, syncRoot) = fixture()
        val cache = Files.createTempDirectory("u5-cache")
        val remote = Files.createTempDirectory("u5-remote")
        putFile(remote, "/down.txt", "downloaded by the mirror")
        putFile(remote, "/sub/nested.txt", "nested")
        val db = StateDatabase(profileDir.resolve("state.db"))
        db.initialize()
        try {
            val provider = org.krost.unidrive.localfs.LocalFsProvider(remote)
            val engine =
                org.krost.unidrive.sync.SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    cacheRoot = cache,
                )
            kotlinx.coroutines.runBlocking {
                provider.authenticate()
                engine.syncOnce() // downloads the remote files
                putFile(syncRoot, "/up.txt", "uploaded by the mirror")
                engine.syncOnce() // uploads the local file
            }
        } finally {
            db.close()
        }
        val hydrated = dump(profileDir).filter { !it.isFolder && it.isHydrated }
        assertEquals(listOf("/down.txt", "/sub/nested.txt", "/up.txt"), hydrated.map { it.path }, "the engine tracked every file")
        assertTrue(hydrated.all { it.cacheBacked == null }, "the mirror engine never writes cache_backed: ${hydrated.map { it.cacheBacked }}")

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot, cache), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(0, code)
        assertTrue(config.readText().contains("mode = \"mirror\""), "the mode is published")
        assertEquals("downloaded by the mirror", Files.readString(syncRoot.resolve("down.txt")), "adopt keeps every file")
    }

    @Test
    fun `a hybrid profile with a cache-only file is still refused`() {
        val (config, profileDir, syncRoot) = fixture()
        val cache = Files.createTempDirectory("u5-cache")
        seed(profileDir, hydratedRow("/mount-written.txt", true), mirrorRow("/mirror.txt"))
        putFile(cache, "/mount-written.txt", "bytes only in the cache")
        putFile(syncRoot, "/mirror.txt", "a mirror row")
        val configBefore = config.readText()

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot, cache), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(1, code)
        assertEquals(configBefore, config.readText(), "config.toml is untouched")
        assertNull(LegacyConversion.Journal.load(profileDir), "no journal is left behind")
    }

    @Test
    fun `a NULL row whose cache copy exists but whose sync_root file is missing is refused`() {
        val (config, profileDir, syncRoot) = fixture()
        val cache = Files.createTempDirectory("u5-cache")
        seed(profileDir, mirrorRow("/cache-only.txt"))
        putFile(cache, "/cache-only.txt", "bytes only in the cache")
        val configBefore = config.readText()

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot, cache), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(1, code)
        assertEquals(configBefore, config.readText(), "config.toml is untouched")
        assertNull(LegacyConversion.Journal.load(profileDir), "no journal is left behind")
    }

    @Test
    fun `a NULL row with its sync_root file present passes even when a cache copy exists`() {
        val (config, profileDir, syncRoot) = fixture()
        val cache = Files.createTempDirectory("u5-cache")
        seed(profileDir, mirrorRow("/both.txt"), mirrorRow("/root-only.txt"))
        putFile(syncRoot, "/both.txt", "same bytes")
        putFile(cache, "/both.txt", "same bytes")
        putFile(syncRoot, "/root-only.txt", "root bytes")

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot, cache), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(0, code)
        assertTrue(config.readText().contains("mode = \"mirror\""), "the mode is published")
    }

    @Test
    fun `a NULL hydrated row with no local copy at all is refused`() {
        val (config, profileDir, syncRoot) = fixture()
        val cache = Files.createTempDirectory("u5-cache")
        // Neither a sync_root file nor a cache copy: the row's bytes are absent from the sync root, so
        // the first mirror sync would read it as a local delete and plan DeleteRemote (#706).
        seed(profileDir, mirrorRow("/gone.txt"))
        val configBefore = config.readText()

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot, cache), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(1, code)
        assertEquals(configBefore, config.readText(), "config.toml is untouched")
        assertNull(LegacyConversion.Journal.load(profileDir), "no journal is left behind")
    }

    // ── resume and refusals ──────────────────────────────────────────────────

    @Test
    fun `an interrupted conversion resumes and does not repeat the moved file`() {
        val (config, profileDir, syncRoot) = fixture()
        syncRoot.resolve("moved.txt").writeText("already aside")
        syncRoot.resolve("remaining.txt").writeText("still here")

        // A journal from an interrupted run: one file already moved into the conversion dir.
        val conversionDir = Files.createDirectories(profileDir.resolve("conversion-test"))
        Files.createDirectories(conversionDir.resolve("quarantine"))
        Files.move(syncRoot.resolve("moved.txt"), conversionDir.resolve("quarantine/moved.txt"))
        LegacyConversion.Journal.save(
            profileDir,
            LegacyConversion.Journal(
                engineVersion = "0.0.1",
                startedAt = "2026-10-08T00:00:00Z",
                profileName = "p",
                targetMode = "mount",
                disposition = "retire",
                phases = listOf("PLAN", "INVENTORY"),
                conversionDir = conversionDir.toString(),
            ),
        )

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "retire", restart = false, verbose = false)

        assertEquals(0, code)
        assertEquals("still here", Files.readString(conversionDir.resolve("quarantine/remaining.txt")))
        assertEquals("already aside", Files.readString(conversionDir.resolve("quarantine/moved.txt")), "the moved file is not touched twice")
        val journal = LegacyConversion.Journal.load(profileDir)!!
        assertTrue("CONVERTED" in journal.phases)
    }

    @Test
    fun `a crash after publishing the mode resumes the journal tail`() {
        val (config, profileDir, syncRoot) = fixture()
        config.writeText(setProfileMode(config.readText(), "p", "mount"))
        LegacyConversion.Journal.save(
            profileDir,
            LegacyConversion.Journal(
                engineVersion = "0.0.1",
                startedAt = "2026-10-08T00:00:00Z",
                profileName = "p",
                targetMode = "mount",
                disposition = "retire",
                phases = listOf("PLAN", "INVENTORY", "PRESERVE"),
            ),
        )

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "retire", restart = false, verbose = false)

        assertEquals(0, code)
        assertTrue("CONVERTED" in LegacyConversion.Journal.load(profileDir)!!.phases)
    }

    @Test
    fun `resuming with a different mode is refused`() {
        val (config, profileDir, syncRoot) = fixture()
        syncRoot.resolve("f.txt").writeText("x")
        LegacyConversion.Journal.save(
            profileDir,
            LegacyConversion.Journal(
                engineVersion = "0.0.1",
                startedAt = "2026-10-08T00:00:00Z",
                profileName = "p",
                targetMode = "mount",
                disposition = "retire",
                phases = listOf("PLAN"),
            ),
        )

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mirror", "adopt", restart = false, verbose = false)

        assertEquals(1, code)
        assertTrue(!config.readText().contains("mode"), "nothing was published")
    }

    @Test
    fun `a journal written by a newer engine is refused`() {
        val (config, profileDir, syncRoot) = fixture()
        LegacyConversion.Journal.save(
            profileDir,
            LegacyConversion.Journal(
                engineVersion = "99.0.0",
                startedAt = "2026-10-08T00:00:00Z",
                profileName = "p",
                targetMode = "mount",
                disposition = "retire",
            ),
        )

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "retire", restart = false, verbose = false)

        assertEquals(1, code)
    }

    @Test
    fun `a profile that already declares a mode is refused`() {
        val (config, profileDir, syncRoot) = fixture()
        config.writeText("[general]\n\n[providers.p]\ntype = \"localfs\"\nmode = \"mount\"\n")

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "retire", restart = false, verbose = false)

        assertEquals(1, code)
    }

    @Test
    fun `a live lock holder refuses the conversion before anything moves`() {
        val (config, profileDir, syncRoot) = fixture()
        syncRoot.resolve("f.txt").writeText("x")
        val pidFile = profileDir.resolve(".lock.pid")
        pidFile.writeText("${ProcessHandle.current().pid()} daemon")

        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "retire", restart = false, verbose = false)

        assertEquals(1, code)
        assertTrue(Files.exists(syncRoot.resolve("f.txt")), "nothing was moved")
        assertTrue(!config.readText().contains("mode"), "nothing was published")
    }

    // ── adopt/retire mode mismatch ───────────────────────────────────────────

    @Test
    fun `adopt with a mount target is refused`() {
        val (config, profileDir, syncRoot) = fixture()
        val code = LegacyConversion.execute(setup(config, profileDir, syncRoot), "mount", "adopt", restart = false, verbose = false)
        assertEquals(1, code)
    }
}
