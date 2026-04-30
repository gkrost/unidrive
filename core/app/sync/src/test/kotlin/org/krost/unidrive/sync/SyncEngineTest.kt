package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class SyncEngineTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-engine-test")
        val dbPath = Files.createTempDirectory("unidrive-engine-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `initial sync downloads remote files`() =
        runTest {
            // UD-222: remote-new files are hydrated (real bytes) by default, not NUL-byte stubs.
            provider.files["/doc.txt"] = ByteArray(1024) { (it and 0xff).toByte() }
            provider.files["/pics/photo.jpg"] = ByteArray(2048) { ((it * 3) and 0xff).toByte() }
            provider.deltaItems =
                listOf(
                    cloudItem("/doc.txt", size = 1024),
                    cloudItem("/pics", isFolder = true),
                    cloudItem("/pics/photo.jpg", size = 2048),
                )

            engine.syncOnce()

            assertTrue(Files.exists(syncRoot.resolve("doc.txt")))
            assertEquals(1024, Files.size(syncRoot.resolve("doc.txt")))
            assertTrue(Files.isDirectory(syncRoot.resolve("pics")))
            assertTrue(Files.exists(syncRoot.resolve("pics/photo.jpg")))
            assertEquals(2048, Files.size(syncRoot.resolve("pics/photo.jpg")))

            assertNotNull(db.getEntry("/doc.txt"))
            assertNotNull(db.getEntry("/pics"))
            assertNotNull(db.getEntry("/pics/photo.jpg"))
        }

    @Test
    fun `sync uploads new local files`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()

            Files.writeString(syncRoot.resolve("local.txt"), "hello")

            engine.syncOnce()

            assertTrue(provider.uploadedPaths.contains("/local.txt"))
            assertNotNull(db.getEntry("/local.txt"))
        }

    @Test
    fun `sync deletes local file when remote deleted`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/will-delete.txt", size = 100))
            engine.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("will-delete.txt")))

            provider.deltaItems = listOf(cloudItem("/will-delete.txt", deleted = true))
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("will-delete.txt")))
            assertNull(db.getEntry("/will-delete.txt"))
        }

    @Test
    fun `sync deletes remote file when local deleted`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/to-remove.txt", size = 100))
            engine.syncOnce()

            Files.delete(syncRoot.resolve("to-remove.txt"))

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            assertTrue(provider.deletedPaths.contains("/to-remove.txt"))
        }

    @Test
    fun `zero-byte remote files are auto-hydrated`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/empty.txt", size = 0))
            engine.syncOnce()

            val entry = db.getEntry("/empty.txt")
            assertNotNull(entry)
            assertTrue(entry.isHydrated)
        }

    @Test
    fun `delta cursor persisted after successful sync`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/a.txt", size = 10))
            provider.deltaCursor = "saved-cursor"
            engine.syncOnce()

            assertEquals("saved-cursor", db.getSyncState("delta_cursor"))
        }

    @Test
    fun `full sync detects deletion when item missing from delta`() =
        runTest {
            // First sync: item appears in delta
            provider.deltaItems = listOf(cloudItem("/tracked.txt", size = 50))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertNotNull(db.getEntry("/tracked.txt"))
            assertTrue(Files.exists(syncRoot.resolve("tracked.txt")))

            // Reset cursor to force full resync; item absent from full delta = deleted
            db.setSyncState("delta_cursor", "")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            engine.syncOnce()

            assertNull(db.getEntry("/tracked.txt"))
            assertFalse(Files.exists(syncRoot.resolve("tracked.txt")))
        }

    @Test
    fun `remote modification re-downloads local file`() =
        runTest {
            // UD-222: remote-modified re-hydrates (always true for non-folders under new semantics).
            provider.files["/doc.txt"] = ByteArray(100)
            provider.deltaItems = listOf(cloudItem("/doc.txt", size = 100))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertEquals(100, Files.size(syncRoot.resolve("doc.txt")))

            // Remote file grew
            provider.files["/doc.txt"] = ByteArray(500)
            provider.deltaItems =
                listOf(
                    cloudItem("/doc.txt", size = 500).copy(
                        hash = "new-hash",
                        modified = Instant.parse("2026-03-29T12:00:00Z"),
                    ),
                )
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            assertEquals(500, Files.size(syncRoot.resolve("doc.txt")))
            val entry = db.getEntry("/doc.txt")
            assertNotNull(entry)
            assertEquals(500, entry.remoteSize)
        }

    @Test
    fun `remote folder deletion removes folder and children locally`() =
        runTest {
            provider.deltaItems =
                listOf(
                    cloudItem("/folder", isFolder = true),
                    cloudItem("/folder/a.txt", size = 10),
                    cloudItem("/folder/b.txt", size = 20),
                )
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertTrue(Files.isDirectory(syncRoot.resolve("folder")))
            assertTrue(Files.exists(syncRoot.resolve("folder/a.txt")))

            // Delete folder and children from remote
            provider.deltaItems =
                listOf(
                    cloudItem("/folder", isFolder = true, deleted = true),
                    cloudItem("/folder/a.txt", deleted = true),
                    cloudItem("/folder/b.txt", deleted = true),
                )
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("folder/a.txt")))
            assertFalse(Files.exists(syncRoot.resolve("folder/b.txt")))
            assertFalse(Files.exists(syncRoot.resolve("folder")))
            assertNull(db.getEntry("/folder"))
            assertNull(db.getEntry("/folder/a.txt"))
        }

    @Test
    fun `incremental sync does not delete items absent from delta`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/stable.txt", size = 50))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertNotNull(db.getEntry("/stable.txt"))

            // Incremental sync: delta returns nothing — item is just unchanged
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            engine.syncOnce()

            // Item must survive — incremental delta omits unchanged items
            assertNotNull(db.getEntry("/stable.txt"))
            assertTrue(Files.exists(syncRoot.resolve("stable.txt")))
        }

    @Test
    fun `full resync with item present in delta keeps it`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/keep.txt", size = 50))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()

            // Reset cursor for full resync; item IS in the full delta
            db.setSyncState("delta_cursor", "")
            provider.deltaItems = listOf(cloudItem("/keep.txt", size = 50))
            provider.deltaCursor = "cursor-2"

            engine.syncOnce()

            assertNotNull(db.getEntry("/keep.txt"))
            assertTrue(Files.exists(syncRoot.resolve("keep.txt")))
        }

    @Test
    fun `deleted item with no path resolved from DB by remoteId`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/gone.txt", size = 100))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("gone.txt")))

            // Simulate how OneDrive personal returns deleted items: path="/" name="" id matches
            provider.deltaItems =
                listOf(
                    CloudItem(
                        id = "id-/gone.txt",
                        name = "",
                        path = "/",
                        size = 0,
                        isFolder = false,
                        modified = null,
                        created = null,
                        hash = null,
                        mimeType = null,
                        deleted = true,
                    ),
                )
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("gone.txt")))
            assertNull(db.getEntry("/gone.txt"))
        }

    @Test
    fun `concurrent transfers complete all downloads`() =
        runTest {
            // 20 small files — all should be downloaded despite concurrent execution
            provider.deltaItems = (1..20).map { cloudItem("/file$it.txt", size = 512) }
            engine.syncOnce()
            // All 20 placeholders created (size-tiered engine handles the small tier)
            assertEquals(20, (1..20).count { db.getEntry("/file$it.txt") != null })
        }

    @Test
    fun `concurrent uploads complete all uploads`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()

            // Create 10 local files
            (1..10).forEach { Files.writeString(syncRoot.resolve("up$it.txt"), "content-$it") }

            engine.syncOnce()

            assertEquals(10, provider.uploadedPaths.size)
            assertTrue((1..10).all { provider.uploadedPaths.contains("/up$it.txt") })
        }

    @Test
    fun `local folder move across parents emits MoveRemote not delete-plus-create`() =
        runTest {
            // Establish initial state: folder /a/docs and a file inside it
            provider.deltaItems =
                listOf(
                    cloudItem("/a", isFolder = true),
                    cloudItem("/a/docs", isFolder = true),
                    cloudItem("/a/docs/file.txt", size = 42),
                )
            engine.syncOnce()

            // User moves /a/docs → /b/docs locally (cross-parent move)
            val aDir = syncRoot.resolve("a")
            val bDir = syncRoot.resolve("b")
            Files.createDirectories(bDir)
            Files.move(aDir.resolve("docs"), bDir.resolve("docs"))

            provider.deltaItems = emptyList() // no remote changes
            engine.syncOnce()

            // Should have issued a MoveRemote, not a DeleteRemote + CreateRemoteFolder
            assertTrue(
                provider.movedPaths.any { (from, to) -> from == "/a/docs" && to == "/b/docs" },
                "Expected MoveRemote from /a/docs to /b/docs, got: ${provider.movedPaths}",
            )
            assertFalse(
                provider.deletedPaths.contains("/a/docs"),
                "DeleteRemote should not be issued when move is detected",
            )
        }

    // UD-297 — empty-local + populated-DB sanity check

    private class RecordingReporter : ProgressReporter {
        val warnings = mutableListOf<String>()

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(total: Int) {}

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {}

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
        ) {}

        override fun onWarning(message: String) {
            warnings.add(message)
        }
    }

    private fun engineWithReporter(reporter: ProgressReporter) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = reporter,
        )

    private fun seedDbEntries(count: Int) {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        for (i in 0 until count) {
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/seeded-$i.txt",
                    remoteId = "id-$i",
                    remoteHash = "hash-$i",
                    remoteSize = 100,
                    remoteModified = now,
                    localMtime = now.toEpochMilli(),
                    localSize = 100,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = now,
                ),
            )
        }
        // Seed a non-null delta cursor so isFullSync is false and
        // detectMissingAfterFullSync doesn't convert the seeded entries into
        // remote-deleted CloudItems (which would turn DeleteRemote actions
        // into RemoveEntry pairs and bypass the safeguards under test).
        db.setSyncState("delta_cursor", "seeded-cursor")
    }

    @Test
    fun `UD-297 dry-run with empty sync_root and populated DB warns instead of throwing`() =
        runTest {
            seedDbEntries(50)
            provider.deltaItems = emptyList()
            val reporter = RecordingReporter()
            engineWithReporter(reporter).syncOnce(dryRun = true)
            assertTrue(
                reporter.warnings.any { it.contains("sync_root") && it.contains("is empty") },
                "expected empty-sync_root warning, got: ${reporter.warnings}",
            )
        }

    @Test
    fun `UD-297 non-dry-run with empty sync_root and populated DB throws`() =
        runTest {
            seedDbEntries(50)
            provider.deltaItems = emptyList()
            val ex =
                assertFailsWith<IllegalStateException> {
                    engineWithReporter(ProgressReporter.Silent).syncOnce(dryRun = false)
                }
            assertTrue(ex.message!!.contains("sync_root"))
            assertTrue(ex.message!!.contains("is empty"))
        }

    @Test
    fun `UD-297 force-delete bypasses empty-sync_root guard`() =
        runTest {
            seedDbEntries(50)
            provider.deltaItems = emptyList()
            // forceDelete should suppress the abort even though local is empty.
            // The deletes themselves are still subject to the percentage safeguard,
            // but force-delete bypasses that too.
            engineWithReporter(ProgressReporter.Silent).syncOnce(dryRun = false, forceDelete = true)
            // No exception means the guard was bypassed; specifics of what got
            // deleted are out of scope here.
        }

    @Test
    fun `UD-297 sync_root with at least one file does not trigger empty-guard`() =
        runTest {
            seedDbEntries(50)
            Files.writeString(syncRoot.resolve("any.txt"), "x")
            provider.deltaItems = emptyList()
            val reporter = RecordingReporter()
            try {
                engineWithReporter(reporter).syncOnce(dryRun = true)
            } catch (_: IllegalStateException) {
                // Tolerate other guards (UD-298 not in scope here).
            }
            assertFalse(
                reporter.warnings.any { it.contains("is empty") },
                "empty-sync_root warning fired even though sync_root is not empty",
            )
        }

    @Test
    fun `UD-297 fresh sync with empty DB and empty sync_root does not warn`() =
        runTest {
            // No seedDbEntries — DB starts empty
            provider.deltaItems = emptyList()
            val reporter = RecordingReporter()
            engineWithReporter(reporter).syncOnce(dryRun = true)
            assertFalse(
                reporter.warnings.any { it.contains("is empty") },
                "empty-sync_root warning fired on a fresh sync where there's nothing to delete",
            )
        }

    // UD-298 — apply percentage deletion safeguard in dry-run as warning

    @Test
    fun `UD-298 dry-run with high deletion percentage warns instead of throwing`() =
        runTest {
            // Seed DB with 100 entries; populate sync_root with ONE file so UD-297
            // does not preempt this. Provider has nothing remote-side, so 99 of
            // the seeded DB entries get marked DELETED -> DeleteRemote actions.
            seedDbEntries(100)
            Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
            provider.deltaItems = emptyList()

            val reporter = RecordingReporter()
            engineWithReporter(reporter).syncOnce(dryRun = true)

            assertTrue(
                reporter.warnings.any { it.contains("Deletion safeguard") && it.contains("sync_root") },
                "expected Deletion safeguard warning with sync_root mention, got: ${reporter.warnings}",
            )
        }

    @Test
    fun `UD-298 non-dry-run with high deletion percentage still throws`() =
        runTest {
            seedDbEntries(100)
            Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
            provider.deltaItems = emptyList()

            val ex =
                assertFailsWith<IllegalStateException> {
                    engineWithReporter(ProgressReporter.Silent).syncOnce(dryRun = false)
                }
            assertTrue(ex.message!!.contains("Deletion safeguard"))
            assertTrue(ex.message!!.contains("sync_root"))
        }

    @Test
    fun `UD-298 force-delete bypasses percentage safeguard in dry-run too`() =
        runTest {
            seedDbEntries(100)
            Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
            provider.deltaItems = emptyList()

            val reporter = RecordingReporter()
            engineWithReporter(reporter).syncOnce(dryRun = true, forceDelete = true)

            assertFalse(
                reporter.warnings.any { it.contains("Deletion safeguard") },
                "force-delete should suppress safeguard warning, got: ${reporter.warnings}",
            )
        }

    // UD-299 — track sync_root, refuse run when changed

    private fun engineWithSyncRoot(root: Path) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = root,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    @Test
    fun `UD-299 first sync stores sync_root in state DB`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()
            val stored = db.getSyncState("sync_root")
            assertEquals(syncRoot.toAbsolutePath().normalize().toString(), stored)
        }

    @Test
    fun `UD-299 same sync_root on subsequent run does not throw`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()
            // Second run with the same engine + same syncRoot — must not throw.
            engine.syncOnce()
        }

    @Test
    fun `UD-299 different sync_root throws with both paths in message`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()

            val newRoot = Files.createTempDirectory("unidrive-engine-test-new")
            val ex =
                assertFailsWith<IllegalStateException> {
                    engineWithSyncRoot(newRoot).syncOnce()
                }
            assertTrue(ex.message!!.contains("sync_root changed from"))
            assertTrue(ex.message!!.contains(syncRoot.toAbsolutePath().normalize().toString()))
            assertTrue(ex.message!!.contains(newRoot.toAbsolutePath().normalize().toString()))
            assertTrue(ex.message!!.contains("--reset"))
        }

    @Test
    fun `UD-299 after reset different sync_root runs and stores new root`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()

            val newRoot = Files.createTempDirectory("unidrive-engine-test-new")
            // Simulate `--reset` — the SyncCommand wires this up by calling
            // db.resetAll() before the engine starts.
            db.resetAll()
            engineWithSyncRoot(newRoot).syncOnce()

            val stored = db.getSyncState("sync_root")
            assertEquals(newRoot.toAbsolutePath().normalize().toString(), stored)
        }

    // UD-223 Part A — fast-bootstrap

    private fun bootstrapEngine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            fastBootstrap = true,
        )

    @Test
    fun `UD-223 fast-bootstrap calls deltaFromLatest on first sync and skips enumeration`() =
        runTest {
            provider.supportsFastBootstrap = true
            provider.deltaCursor = "latest-cursor"
            // deltaItems populated, but bootstrap must NOT enumerate them
            provider.deltaItems = listOf(cloudItem("/pre-existing.txt", size = 1))

            bootstrapEngine().syncOnce()

            assertEquals(1, provider.deltaFromLatestCalls, "expected exactly one deltaFromLatest call")
            assertEquals(0, provider.deltaCalls, "enumeration delta must not run during bootstrap")
            assertEquals("latest-cursor", db.getSyncState("delta_cursor"))
            // The pre-existing remote item must NOT have been materialised locally —
            // that's the whole point of the bootstrap trade-off.
            assertFalse(Files.exists(syncRoot.resolve("pre-existing.txt")))
        }

    @Test
    fun `UD-223 fast-bootstrap on second sync no-ops and resumes normal delta`() =
        runTest {
            provider.supportsFastBootstrap = true

            // Sync once non-bootstrapped with a real item so the cursor is promoted —
            // `syncOnce` early-returns without promotion when actions are empty, so the
            // seed needs to produce at least one action.
            provider.deltaCursor = "seed"
            provider.deltaItems = listOf(cloudItem("/seed.txt", size = 1))
            provider.files["/seed.txt"] = ByteArray(1)
            engine.syncOnce()
            assertEquals("seed", db.getSyncState("delta_cursor"))

            // Now a bootstrap-flagged run must skip the bootstrap path (cursor present)
            // and fall through to the normal delta() path.
            provider.deltaFromLatestCalls = 0
            provider.deltaCalls = 0
            provider.deltaCursor = "next"
            provider.deltaItems = emptyList()
            bootstrapEngine().syncOnce()

            assertEquals(0, provider.deltaFromLatestCalls, "bootstrap must only fire on first sync")
            assertEquals(1, provider.deltaCalls)
        }

    @Test
    fun `UD-223 fast-bootstrap falls back to enumeration when provider lacks capability`() =
        runTest {
            provider.supportsFastBootstrap = false // default; capability absent
            provider.deltaCursor = "fallback-cursor"
            provider.deltaItems = listOf(cloudItem("/enumerated.txt", size = 5))
            provider.files["/enumerated.txt"] = ByteArray(5)

            bootstrapEngine().syncOnce()

            // The bootstrap attempt is short-circuited by the capability check (never
            // invokes deltaFromLatest), then normal enumeration runs.
            assertEquals(0, provider.deltaFromLatestCalls)
            assertEquals(1, provider.deltaCalls)
            assertTrue(Files.exists(syncRoot.resolve("enumerated.txt")))
            assertEquals("fallback-cursor", db.getSyncState("delta_cursor"))
        }

    @Test
    fun `dry-run persists remote state and reuses cursor`() =
        runTest {
            // First dry-run: remote has one file
            provider.deltaItems = listOf(cloudItem("/test.txt", size = 100))
            provider.deltaCursor = "cursor-first"
            provider.files["/test.txt"] = ByteArray(100)
            // Ensure no prior cursor
            db.setSyncState("delta_cursor", "")
            db.setSyncState("pending_cursor", "")

            engine.syncOnce(dryRun = true)

            // Cursor promoted (pending -> delta) because actions empty
            assertEquals("cursor-first", db.getSyncState("delta_cursor"))
            // Remote entry cached
            val entry = db.getEntry("/test.txt")
            assertNotNull(entry)
            assertEquals("id-/test.txt", entry.remoteId)
            assertEquals(100, entry.remoteSize)
            assertFalse(entry.isHydrated) // local side unchanged
            // No local file created
            assertFalse(Files.exists(syncRoot.resolve("test.txt")))
            // Delta called exactly once
            assertEquals(1, provider.deltaCalls)

            // Second dry-run: provider returns same cursor, no new items
            provider.deltaCursor = "cursor-second"
            provider.deltaItems = emptyList()
            // Reset counter
            provider.deltaCalls = 0

            engine.syncOnce(dryRun = true)

            // Cursor updated to second cursor
            assertEquals("cursor-second", db.getSyncState("delta_cursor"))
            // Delta called with previous cursor
            assertEquals(1, provider.deltaCalls)
            // Remote entry still present
            assertNotNull(db.getEntry("/test.txt"))
        }

    // -- Fake provider for testing --

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$path",
        mimeType = "application/octet-stream",
        deleted = deleted,
    )

    class FakeCloudProvider : CloudProvider {
        override val id = "fake"
        override val displayName = "Fake"
        override val isAuthenticated = true

        var supportsFastBootstrap = false
        var deltaFromLatestCalls = 0
        var deltaCalls = 0

        override fun capabilities(): Set<org.krost.unidrive.Capability> =
            buildSet {
                add(org.krost.unidrive.Capability.Delta)
                add(org.krost.unidrive.Capability.VerifyItem)
                if (supportsFastBootstrap) add(org.krost.unidrive.Capability.FastBootstrap)
            }

        override suspend fun deltaFromLatest(): org.krost.unidrive.CapabilityResult<DeltaPage> {
            deltaFromLatestCalls++
            return if (supportsFastBootstrap) {
                org.krost.unidrive.CapabilityResult.Success(
                    DeltaPage(items = emptyList(), cursor = deltaCursor, hasMore = false),
                )
            } else {
                org.krost.unidrive.CapabilityResult.Unsupported(
                    org.krost.unidrive.Capability.FastBootstrap,
                    "fake provider opts out",
                )
            }
        }

        var deltaItems = listOf<CloudItem>()
        var deltaCursor = "cursor-1"
        val uploadedPaths = mutableListOf<String>()
        val deletedPaths = mutableListOf<String>()
        val files = mutableMapOf<String, ByteArray>()

        var downloadFailCount = 0
        var uploadFailCount = 0
        var deleteFailCount = 0
        var deltaFailCount = 0
        var authFailOnDownload = false

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String) = deltaItems.first { it.path == path }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            if (authFailOnDownload) throw AuthenticationException("Token expired")
            if (downloadFailCount > 0) {
                downloadFailCount--
                throw ProviderException("Network timeout on download")
            }
            val content = files[remotePath] ?: ByteArray(0)
            Files.createDirectories(destination.parent)
            Files.write(destination, content)
            return content.size.toLong()
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            if (uploadFailCount > 0) {
                uploadFailCount--
                throw ProviderException("Network timeout on upload")
            }
            uploadedPaths.add(remotePath)
            val content = Files.readAllBytes(localPath)
            files[remotePath] = content
            return CloudItem(
                id = "id-$remotePath",
                name = remotePath.substringAfterLast("/"),
                path = remotePath,
                size = content.size.toLong(),
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "uploaded",
                mimeType = null,
            )
        }

        override suspend fun delete(remotePath: String) {
            if (deleteFailCount > 0) {
                deleteFailCount--
                throw ProviderException("Network timeout on delete")
            }
            deletedPaths.add(remotePath)
        }

        override suspend fun createFolder(path: String) =
            CloudItem(
                id = "id-$path",
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.now(),
                created = Instant.now(),
                hash = null,
                mimeType = null,
            )

        val movedPaths = mutableListOf<Pair<String, String>>() // (fromPath, toPath)

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem {
            movedPaths.add(Pair(fromPath, toPath))
            return createFolder(toPath)
        }

        override suspend fun delta(cursor: String?): DeltaPage {
            deltaCalls++
            if (deltaFailCount > 0) {
                deltaFailCount--
                throw ProviderException("Network timeout on delta")
            }
            return DeltaPage(items = deltaItems, cursor = deltaCursor, hasMore = false)
        }

        override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)

        val remoteIdExists = mutableMapOf<String, Boolean>()

        override suspend fun verifyItemExists(remoteId: String): org.krost.unidrive.CapabilityResult<Boolean> =
            org.krost.unidrive.CapabilityResult
                .Success(remoteIdExists[remoteId] ?: true)
    }
}
