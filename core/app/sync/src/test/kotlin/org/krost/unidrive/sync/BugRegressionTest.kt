package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * Regression tests for bugs found during code audit.
 * Each test demonstrates a specific bug — they should FAIL before the fix
 * and PASS after the fix is applied.
 */
class BugRegressionTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: SyncEngineTest.FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-bug-test")
        val dbPath = Files.createTempDirectory("unidrive-bug-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = SyncEngineTest.FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
        hash: String? = "hash-$path",
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = "application/octet-stream",
        deleted = deleted,
    )

    // ========================================================================
    // Bundle 3a: Download failure leaves corrupted file on disk
    // SyncEngine.kt:164-168 — generic Exception catch doesn't restore placeholder
    // ========================================================================

    @Test
    fun `download failure should restore file to placeholder state`() =
        runTest {
            // Set up: remote file exists, pin rule forces download (Pass 1 hydration)
            provider.files["/doc.txt"] = "real content".toByteArray()
            provider.deltaItems = listOf(cloudItem("/doc.txt", size = 12))
            provider.deltaCursor = "cursor-1"
            db.addPinRule("*.txt", pinned = true)

            // First sync succeeds — file is hydrated
            engine().syncOnce()
            val entryBefore = db.getEntry("/doc.txt")
            assertNotNull(entryBefore)
            assertTrue(entryBefore.isHydrated)

            // Remote file modified, but download will fail
            provider.files["/doc.txt"] = "new content here!!".toByteArray()
            provider.deltaItems = listOf(cloudItem("/doc.txt", size = 18, hash = "new-hash"))
            provider.deltaCursor = "cursor-2"
            provider.downloadFailCount = 1

            engine().syncOnce()

            // BUG: After a download failure, the corrupted local file stays on disk.
            // The file should be restored to a placeholder (0-byte stub under UD-222 — the
            // previous NUL-sized stub was indistinguishable from corruption on open).
            val entryAfter = db.getEntry("/doc.txt")
            assertNotNull(entryAfter)
            // After fix: file should NOT be hydrated since download failed
            assertFalse(entryAfter.isHydrated, "Failed download should leave file as non-hydrated placeholder")
            assertEquals(0, Files.size(syncRoot.resolve("doc.txt")), "UD-222: placeholders are 0-byte stubs; next sync re-downloads")
            assertEquals(18, entryAfter.remoteSize, "DB entry should still track remote size for next sync")
        }

    // ========================================================================
    // Bundle 6: Tilde expansion replaces ALL ~ characters
    // SyncConfig.kt:144,176,200-201 — replace("~", home) not replaceFirst
    // ========================================================================

    @Test
    fun `tilde expansion only replaces leading tilde`() {
        val toml =
            """
            [general]
            sync_root = "/home/~user/data"
            """.trimIndent()

        val config = SyncConfig.parse(toml, "onedrive")
        val root = config.syncRoot.toString()

        // BUG: Currently produces "/home/alice/home/user/data" (where
        // /home/alice is `user.home`) because replace("~", home) replaces
        // ALL tildes. Expected: "/home/~user/data" — no leading ~ in the
        // input, so no expansion.
        assertFalse(root.contains("home/home"), "Tilde expansion should not replace non-leading tildes: $root")
    }

    @Test
    fun `tilde expansion on leading tilde works correctly`() {
        val toml =
            """
            [general]
            sync_root = "~/MyDrive"
            """.trimIndent()

        val config = SyncConfig.parse(toml, "onedrive")
        val root = config.syncRoot.toString()

        // This should work: leading ~ expanded to home dir.
        // Use user.home so the assertion works on Linux (/home/), macOS (/Users/), and Windows (C:\Users\).
        val home = System.getProperty("user.home")
        assertTrue(root.endsWith("MyDrive"))
        assertTrue(root.startsWith(home), "Expected root to start with user.home ($home), got: $root")
    }

    @Test
    fun `duplicate sync root detection handles tilde correctly`() {
        val toml =
            """
            [providers.a]
            type = "onedrive"
            sync_root = "/home/~user/same"

            [providers.b]
            type = "onedrive"
            sync_root = "/home/~user/same"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)

        // BUG: If ~ is replaced everywhere, "/home/~user/same" becomes
        // "/home/<user.home>/home/user/same" — still matches itself, so
        // duplicate detection passes. But the path value is wrong.
        val error = SyncConfig.detectDuplicateSyncRoots(raw)
        assertNotNull(error, "Duplicate sync roots should be detected")
    }

    // ========================================================================
    // Bundle 11: Case collision detection misses same-cycle conflicts
    // Reconciler.kt:47-61 — only checks against DB entries
    // ========================================================================

    @Test
    fun `case collision between two new local files in same cycle detected`() {
        // Two new local files with different-case names appear in the same cycle
        val localChanges =
            mapOf(
                "/Report.pdf" to ChangeState.NEW,
                "/report.pdf" to ChangeState.NEW,
            )
        val actions = reconciler().reconcile(emptyMap(), localChanges)

        // BUG: Currently both get Upload actions — neither is caught as a collision.
        // Expected: at least one should be a Conflict action.
        val uploads = actions.filterIsInstance<SyncAction.Upload>()
        val conflicts = actions.filterIsInstance<SyncAction.Conflict>()

        // At least one of the two files should be flagged as a conflict
        assertTrue(
            conflicts.isNotEmpty() || uploads.size < 2,
            "Case collision between two new local files should be detected: " +
                "uploads=${uploads.map { it.path }}, conflicts=${conflicts.map { it.path }}",
        )
    }

    private fun reconciler() = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)

    // ========================================================================
    // Bundle 4: Provider move() returns bogus CloudItem (size=0, isFolder=false)
    // This tests the sync engine's handling of such a result
    // ========================================================================

    @Test
    fun `move remote action preserves size from old entry not provider result`() =
        runTest {
            // Set up: file synced locally
            provider.files["/original.txt"] = "content".toByteArray()
            provider.deltaItems = listOf(cloudItem("/original.txt", size = 7))
            provider.deltaCursor = "cursor-1"
            engine().syncOnce()

            // Move local file to new path. Remote still reports old path.
            // Scanner: /original.txt → DELETED, /moved.txt → NEW
            // Remote: /original.txt → UNCHANGED
            // Reconcile: DeleteRemote(/original.txt) + Upload(/moved.txt)
            // detectMoves: matches by size → MoveRemote
            Files.move(syncRoot.resolve("original.txt"), syncRoot.resolve("moved.txt"))

            // Remote still reports old path — no change detected remotely
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            engine().syncOnce()

            // FakeCloudProvider.move() returns createFolder() with size=0
            // applyMoveRemote uses result.size for remoteSize — BUG: should use old entry's size
            val movedEntry = db.getEntry("/moved.txt")
            assertNotNull(movedEntry, "Moved file should have a DB entry")
            // BUG: Currently remoteSize is 0 because provider.move() returns bogus result.
            // After fix, it should be 7 (from old entry).
            assertEquals(7, movedEntry.remoteSize, "Moved entry should preserve original size, not use bogus provider result")
        }
}
