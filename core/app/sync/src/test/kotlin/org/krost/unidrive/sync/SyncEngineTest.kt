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
    fun `refresh skips Pass 2 — local file gets pending row but is not uploaded`() =
        runTest {
            // First sync establishes baseline (no items, no actions).
            provider.deltaItems = emptyList()
            engine.syncOnce()

            // Add a local file. A normal sync would upload it; refresh just records the intent.
            Files.writeString(syncRoot.resolve("pending-up.txt"), "draft")
            engine.syncOnce(skipTransfers = true)

            // Pending-upload row exists (UD-901 pre-write) but provider was never called.
            val entry = db.getEntry("/pending-up.txt")
            assertNotNull(entry, "refresh must persist a pending-upload row for the new local file")
            assertNull(entry.remoteId, "remoteId stays null until apply uploads")
            assertTrue(provider.uploadedPaths.isEmpty(), "refresh must NOT call provider.upload")
        }

    @Test
    fun `apply drains pending uploads from a prior refresh`() =
        runTest {
            // Refresh records a pending upload.
            provider.deltaItems = emptyList()
            engine.syncOnce()
            Files.writeString(syncRoot.resolve("apply-me.txt"), "go")
            engine.syncOnce(skipTransfers = true)
            assertTrue(provider.uploadedPaths.isEmpty())

            // Apply: skip remote gather, let recovery loops surface the pending UD-901 row.
            engine.syncOnce(skipRemoteGather = true)

            // The upload must have happened.
            assertTrue(provider.uploadedPaths.contains("/apply-me.txt"))
            val finalEntry = db.getEntry("/apply-me.txt")
            assertNotNull(finalEntry?.remoteId, "apply must promote the pending row to a fully-synced state")
        }

    @Test
    fun `apply is no-op when nothing pending`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()
            // Apply with no pending entries — must complete without contacting provider for transfers.
            val uploadedBefore = provider.uploadedPaths.size
            engine.syncOnce(skipRemoteGather = true)
            assertEquals(uploadedBefore, provider.uploadedPaths.size, "apply with no pending entries must not upload anything")
        }

    @Test
    fun `UD-366 modify-upload forwards existing remoteId to provider for replace-in-place`() =
        runTest {
            // First upload registers the file with a remoteId in the DB.
            provider.deltaItems = emptyList()
            engine.syncOnce()
            Files.writeString(syncRoot.resolve("mod.txt"), "v1")
            engine.syncOnce()
            val firstRemoteId = db.getEntry("/mod.txt")?.remoteId
            assertNotNull(firstRemoteId)
            // NEW upload: existingRemoteId must be null.
            assertNull(provider.lastUploadExistingRemoteId)

            // Modify locally and re-sync. The reconciler emits MODIFIED+UNCHANGED →
            // SyncAction.Upload(remoteId = entry.remoteId). The dispatcher must forward
            // it as upload(existingRemoteId = firstRemoteId).
            Thread.sleep(20) // ensure mtime advances on filesystems with second-level resolution
            Files.writeString(syncRoot.resolve("mod.txt"), "v2-longer-content-to-bump-size")
            engine.syncOnce()

            assertEquals(firstRemoteId, provider.lastUploadExistingRemoteId)
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
    fun `UD-225b applyDownload routes through downloadById when remoteItem has id`() =
        runTest {
            // The fast/robust path: when the action carries a non-empty remoteId,
            // applyDownload (and the other DownloadContent dispatch sites) must
            // call provider.downloadById, not provider.download. Path-based
            // dispatch on Internxt triggers per-segment folder traversal and
            // fails ~44 % of files when an intermediate folder name can't be
            // resolved (live 2026-05-03 incident on the UD-225a recovery sync).
            provider.files["/folder/file.txt"] = ByteArray(50)
            provider.deltaItems = listOf(cloudItem("/folder/file.txt", size = 50))

            engine.syncOnce()

            assertTrue(
                provider.downloadByIdCalls.any { (id, path) -> id == "id-/folder/file.txt" && path == "/folder/file.txt" },
                "expected downloadById dispatch, got byId=${provider.downloadByIdCalls} byPath=${provider.downloadByPathCalls}",
            )
            assertTrue(
                provider.downloadByPathCalls.isEmpty(),
                "path-based download must NOT fire when remoteItem.id is set; got: ${provider.downloadByPathCalls}",
            )
        }

    @Test
    fun `UD-225b applyDownload falls back to path-based when remoteItem id is empty`() =
        runTest {
            // Defensive: synthesised CloudItems with no remoteId (an entry whose
            // remote_id was null in the DB) fall through to path-based download.
            // Should be unreachable in practice post-UD-225a (which routes
            // pending-upload rows to RemoveEntry instead), but pin the safety
            // valve so a future refactor doesn't accidentally NPE on empty id.
            provider.files["/orphan.txt"] = ByteArray(20)
            provider.deltaItems = listOf(cloudItem("/orphan.txt", size = 20).copy(id = ""))

            engine.syncOnce()

            assertTrue(
                provider.downloadByPathCalls.contains("/orphan.txt"),
                "expected path-based fallback, got byId=${provider.downloadByIdCalls} byPath=${provider.downloadByPathCalls}",
            )
            assertTrue(provider.downloadByIdCalls.isEmpty(), "downloadById must NOT fire when id is empty")
        }

    @Test
    fun `UD-360 partial delta gather suppresses detectMissingAfterFullSync`() =
        runTest {
            // First sync: item appears in delta and is hydrated locally.
            provider.deltaItems = listOf(cloudItem("/tracked.txt", size = 50))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertNotNull(db.getEntry("/tracked.txt"))
            assertTrue(Files.exists(syncRoot.resolve("tracked.txt")))

            // Reset cursor to force a full resync. The item is absent from the
            // delta — but the provider signals complete=false (e.g. an Internxt
            // subtree returned 503 and got skipped). Without UD-360, the engine
            // would interpret the absence as a remote-deletion and emit del-local.
            // With UD-360, the deletion sweep is suppressed and the local file
            // survives until a complete delta succeeds on a later run.
            db.setSyncState("delta_cursor", "")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            provider.deltaComplete = false

            val reporter = RecordingReporter()
            engineWithReporter(reporter).syncOnce()

            // File survives — this is the regression UD-360 prevents.
            assertNotNull(db.getEntry("/tracked.txt"), "DB entry must NOT be removed on partial delta")
            assertTrue(
                Files.exists(syncRoot.resolve("tracked.txt")),
                "local file must NOT be deleted on partial delta",
            )
            assertTrue(
                reporter.warnings.any { it.contains("UD-360") && it.contains("complete=false") },
                "expected UD-360 partial-gather warning, got: ${reporter.warnings}",
            )
        }

    @Test
    fun `UD-360 complete=true preserves the absence-implies-deletion sweep`() =
        runTest {
            // Inverse of the test above: complete=true (the default) MUST keep
            // detectMissingAfterFullSync's behaviour. Pin this so a future
            // refactor can't accidentally always-suppress the deletion sweep.
            provider.deltaItems = listOf(cloudItem("/tracked.txt", size = 50))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertNotNull(db.getEntry("/tracked.txt"))

            db.setSyncState("delta_cursor", "")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            provider.deltaComplete = true

            engine.syncOnce()

            assertNull(db.getEntry("/tracked.txt"))
            assertFalse(Files.exists(syncRoot.resolve("tracked.txt")))
        }

    @Test
    fun `UD-901e incomplete delta gather does NOT promote pending_cursor`() =
        runTest {
            // Codex review (PR #13) caught that the 3 cursor-promotion sites
            // (empty-action / dry-run / success) unconditionally promoted
            // pending_cursor → delta_cursor regardless of whether the gather
            // pass was complete. Internxt's UD-360 subtree-skip path returns
            // DeltaPage(complete=false) on 500/503, with the new latest
            // updatedAt as the cursor. Promoting that cursor silently advances
            // delta_cursor past items in the skipped subtree, so they're never
            // re-enumerated.
            //
            // Invariant under test: after a delta pass where any page returned
            // complete=false, delta_cursor MUST remain at its previous value,
            // not advance to the partial gather's new cursor.
            //
            // First, establish a baseline cursor so we can detect movement.
            provider.deltaItems = listOf(cloudItem("/tracked.txt", size = 50))
            provider.deltaCursor = "cursor-baseline"
            provider.deltaComplete = true
            engine.syncOnce()
            assertEquals(
                "cursor-baseline",
                db.getSyncState("delta_cursor"),
                "delta_cursor must advance on a complete gather",
            )

            // Second pass: provider returns complete=false with a new cursor
            // (mimicking Internxt's "I skipped a folder on 503; here's the
            // new latest-updatedAt I saw before the skip"). delta_cursor
            // must NOT advance to this new value.
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-partial"
            provider.deltaComplete = false

            engine.syncOnce()

            assertEquals(
                "cursor-baseline",
                db.getSyncState("delta_cursor"),
                "delta_cursor must NOT advance when the gather was incomplete — " +
                    "promoting cursor-partial would skip items in the failed subtree on next run",
            )
            // Sanity: the pending cursor is still written (so a follow-up
            // complete gather can promote it), and the completeness flag
            // reflects the partial state.
            assertEquals("cursor-partial", db.getSyncState("pending_cursor"))
            assertEquals("false", db.getSyncState("pending_cursor_complete"))

            // Third pass: provider recovers, complete=true. The freshly
            // complete cursor IS promoted.
            provider.deltaCursor = "cursor-recovered"
            provider.deltaComplete = true
            engine.syncOnce()
            assertEquals(
                "cursor-recovered",
                db.getSyncState("delta_cursor"),
                "delta_cursor must advance once a complete gather lands",
            )
            assertEquals("true", db.getSyncState("pending_cursor_complete"))
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

    @Test
    fun `UD-901d folder MoveRemote preserves destination folder row with canonical remoteId`() =
        runTest {
            // Codex review (PR #13) caught that applyMoveRemote's call order — deleteEntry
            // → upsertEntry(destination) → renamePrefix — let renamePrefix's UD-901c
            // cleanup-DELETE wipe the just-upserted destination root row. The moved
            // folder ended up with no remoteId/metadata in the DB on every folder
            // rename/move, and the next scan treated it as untracked.
            //
            // Invariant under test: after a folder move-remote, the destination folder
            // row carries the canonical remoteId from provider.move() AND the descendant
            // rows survive at the new prefix.
            provider.deltaItems =
                listOf(
                    cloudItem("/a", isFolder = true),
                    cloudItem("/a/docs", isFolder = true),
                    cloudItem("/a/docs/file.txt", size = 42),
                )
            engine.syncOnce()

            // Sanity: pre-move state.
            val preMoveDocs = db.getEntry("/a/docs")
            assertNotNull(preMoveDocs)
            assertTrue(preMoveDocs.isFolder)

            // Local cross-parent move triggers a remote-side move.
            val aDir = syncRoot.resolve("a")
            val bDir = syncRoot.resolve("b")
            Files.createDirectories(bDir)
            Files.move(aDir.resolve("docs"), bDir.resolve("docs"))
            provider.deltaItems = emptyList()
            engine.syncOnce()

            // Source path is gone.
            assertNull(db.getEntry("/a/docs"), "source row must be deleted after move")

            // Destination folder row exists AND carries the canonical remoteId
            // from FakeCloudProvider.move() (which returns CloudItem(id = "id-$path")).
            val movedDocs = db.getEntry("/b/docs")
            assertNotNull(movedDocs, "destination folder row must survive the rename — was being wiped by renamePrefix")
            assertTrue(movedDocs.isFolder)
            assertEquals(
                "id-/b/docs",
                movedDocs.remoteId,
                "destination folder row must carry the remoteId returned by provider.move(), not be reset to null",
            )

            // Descendant rows survive at the new prefix (the renamePrefix UPDATE
            // moves them; this was already working pre-fix, regression-guard it).
            val movedFile = db.getEntry("/b/docs/file.txt")
            assertNotNull(movedFile, "descendant rows must survive the rename")
            assertEquals(42, movedFile.remoteSize)
        }

    // UD-297 — empty-local + populated-DB sanity check

    private class RecordingReporter : ProgressReporter {
        val warnings = mutableListOf<String>()

        // UD-740: capture action progress events for tests that assert on the
        // display path (move src -> dst rendering).
        data class ActionEvent(
            val label: String,
            val path: String,
        )

        val actions = mutableListOf<ActionEvent>()

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {}

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {
            actions.add(ActionEvent(action, path))
        }

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        // UD-745: capture failed count for tests asserting summary semantics.
        var lastFailed: Int = 0
            private set

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {
            lastFailed = failed
        }

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

    // UD-264 — top-level-never-hydrated del-remote guard

    /**
     * Seed unhydrated *folder* rows under [topLevel]. Reconciler emits
     * DeleteRemote for unhydrated folder entries that are missing locally
     * (the unhydrated-file branch in [Reconciler] converts to DownloadContent
     * instead; UD-264 specifically defends against the folder shape — the
     * 2026-05-16 incident deleted 405 folder rows). Cursor is non-null so the
     * detectMissingAfterFullSync branch doesn't intercept.
     */
    private fun seedUnhydratedFolders(
        topLevel: String,
        count: Int,
    ) {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        // Seed the top-level folder row itself + N descendants. None hydrated.
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = topLevel,
                remoteId = "id-tl-$topLevel",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = now,
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = now,
            ),
        )
        for (i in 0 until count) {
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "$topLevel/sub-$i",
                    remoteId = "id-$topLevel-$i",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = now,
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = now,
                ),
            )
        }
        db.setSyncState("delta_cursor", "seeded-cursor")
    }

    private fun engineWithGuards(
        reporter: ProgressReporter = ProgressReporter.Silent,
        ignoreTopLevelGuard: Boolean = false,
        skippedOpsLogPath: Path? = null,
        maxDeleteAbsolute: Int = 10_000,
        maxDeletePerSubtreePercent: Int = 0, // disabled by default for UD-264 fixtures
        maxDeletePercentage: Int = 0, // disabled by default for UD-264 fixtures
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = reporter,
        maxDeletePercentage = maxDeletePercentage,
        maxDeleteAbsolute = maxDeleteAbsolute,
        maxDeletePerSubtreePercent = maxDeletePerSubtreePercent,
        ignoreTopLevelGuard = ignoreTopLevelGuard,
        skippedOpsLogPath = skippedOpsLogPath,
    )

    @Test
    fun `UD-264 del-remote for never-hydrated top-level is skipped`() =
        runTest {
            // Three unhydrated folders under /Documents; sync_root has one file
            // so UD-297 doesn't preempt. Provider returns no delta.
            seedUnhydratedFolders("/Documents", count = 3)
            Files.writeString(syncRoot.resolve("placeholder.txt"), "x")
            provider.deltaItems = emptyList()

            val logPath = Files.createTempDirectory("unidrive-skipped").resolve("skipped-ops.jsonl")
            val reporter = RecordingReporter()
            engineWithGuards(reporter = reporter, skippedOpsLogPath = logPath).syncOnce(dryRun = true)

            // All deletes were filtered out, so no DeleteRemote events surface.
            assertTrue(
                reporter.actions.none { it.label == "del-remote" && it.path.startsWith("/Documents") },
                "expected del-remote for /Documents/* to be filtered, got: ${reporter.actions}",
            )
            // Audit log captures the skip with the documented reason.
            assertTrue(Files.exists(logPath), "skipped-ops.jsonl should be written")
            val logBody = Files.readString(logPath)
            assertTrue(
                logBody.contains("top_level_never_hydrated"),
                "skipped-ops.jsonl should record the reason, got: $logBody",
            )
            assertTrue(
                logBody.contains("\"path\":\"/Documents"),
                "skipped-ops.jsonl should record the skipped path, got: $logBody",
            )
        }

    @Test
    fun `UD-264 del-remote for top-level WITH hydrated descendant is propagated`() =
        runTest {
            // Three unhydrated folders under /Documents, PLUS one hydrated
            // descendant file. The hydrated descendant is the user signal
            // that this subtree IS known to unidrive — guard must allow
            // delete propagation through.
            seedUnhydratedFolders("/Documents", count = 3)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/Documents/hydrated-file.txt",
                    remoteId = "id-hyd",
                    remoteHash = "h",
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
            // Place the hydrated file on disk so reconciler doesn't propose
            // a separate DeleteRemote for it.
            Files.createDirectories(syncRoot.resolve("Documents"))
            Files.writeString(syncRoot.resolve("Documents/hydrated-file.txt"), "x".repeat(100))
            provider.deltaItems = emptyList()

            val reporter = RecordingReporter()
            engineWithGuards(reporter = reporter).syncOnce(dryRun = true)

            // del-remote for /Documents/sub-* must show up — guard did not strip them.
            assertTrue(
                reporter.actions.any { it.label == "del-remote" && it.path.startsWith("/Documents/sub-") },
                "expected del-remote for /Documents/sub-* to flow through, got: ${reporter.actions}",
            )
        }

    @Test
    fun `UD-264 --ignore-top-level-guard lets the delete through and still logs`() =
        runTest {
            seedUnhydratedFolders("/Documents", count = 3)
            Files.writeString(syncRoot.resolve("placeholder.txt"), "x")
            provider.deltaItems = emptyList()

            val logPath = Files.createTempDirectory("unidrive-skipped").resolve("skipped-ops.jsonl")
            val reporter = RecordingReporter()
            engineWithGuards(
                reporter = reporter,
                ignoreTopLevelGuard = true,
                skippedOpsLogPath = logPath,
            ).syncOnce(dryRun = true)

            // Opt-out keeps the deletes in the plan.
            assertTrue(
                reporter.actions.any { it.label == "del-remote" && it.path.startsWith("/Documents") },
                "ignore-top-level-guard should let del-remote flow, got: ${reporter.actions}",
            )
            // ... but still logs them for audit.
            assertTrue(Files.exists(logPath), "skipped-ops.jsonl should be written even on opt-out")
            val logBody = Files.readString(logPath)
            assertTrue(
                logBody.contains("top_level_never_hydrated"),
                "opt-out path still writes the audit line, got: $logBody",
            )
        }

    // UD-265 — two-axis deletion safeguard (absolute + per-subtree)

    @Test
    fun `UD-265 planning more than maxDeleteAbsolute aborts in apply`() =
        runTest {
            // 60 hydrated DB entries, sync_root has one file so UD-297 stays
            // quiet. With maxDeleteAbsolute=50 (default), the 59-delete plan
            // trips the absolute axis. Whole-inventory percentage is at 59/60
            // ~ 98% which would ALSO trip UD-298, so we set
            // maxDeletePercentage=0 to disable that axis and isolate UD-265.
            seedDbEntries(60)
            Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
            provider.deltaItems = emptyList()

            val ex =
                assertFailsWith<IllegalStateException> {
                    engineWithGuards(maxDeleteAbsolute = 50, maxDeletePercentage = 0)
                        .syncOnce(dryRun = false)
                }
            assertTrue(ex.message!!.contains("max_delete_absolute"))
        }

    @Test
    fun `UD-265 planning more than maxDeleteAbsolute warns in dry-run`() =
        runTest {
            seedDbEntries(60)
            Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
            provider.deltaItems = emptyList()

            val reporter = RecordingReporter()
            engineWithGuards(reporter = reporter, maxDeleteAbsolute = 50, maxDeletePercentage = 0)
                .syncOnce(dryRun = true)

            assertTrue(
                reporter.warnings.any { it.contains("max_delete_absolute") },
                "expected dry-run warning for absolute cap, got: ${reporter.warnings}",
            )
        }

    @Test
    fun `UD-265 per-subtree percentage trips when one top-level is mostly deleted`() =
        runTest {
            // 20 entries under /Documents (all hydrated); only one of them
            // exists locally → 19 DeleteRemote actions = 95 % of /Documents.
            // Total inventory is only 20 entries so we also need to bypass
            // UD-298 (max_delete_percentage). maxDeleteAbsolute=10000 so the
            // absolute axis stays out of the way too — we want to isolate the
            // per-subtree axis. Place 9 unrelated hydrated entries under
            // /Other to demonstrate the per-subtree (not whole-inventory)
            // semantics.
            val now = Instant.parse("2026-01-01T00:00:00Z")
            // Use unique sizes per entry so the reconciler's move-detection
            // heuristic (matching DeleteRemote + Upload of equal size) doesn't
            // collapse the 19 /Documents deletes into moves to /Other.
            for (i in 0 until 20) {
                db.upsertEntry(
                    org.krost.unidrive.sync.model.SyncEntry(
                        path = "/Documents/file-$i.txt",
                        remoteId = "id-doc-$i",
                        remoteHash = "h-$i",
                        remoteSize = (1000 + i).toLong(),
                        remoteModified = now,
                        localMtime = now.toEpochMilli(),
                        localSize = (1000 + i).toLong(),
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = now,
                    ),
                )
            }
            // /Other entries get sizes that don't collide with /Documents.
            for (i in 0 until 9) {
                db.upsertEntry(
                    org.krost.unidrive.sync.model.SyncEntry(
                        path = "/Other/file-$i.txt",
                        remoteId = "id-other-$i",
                        remoteHash = "ho-$i",
                        remoteSize = (5000 + i).toLong(),
                        remoteModified = now,
                        localMtime = now.toEpochMilli(),
                        localSize = (5000 + i).toLong(),
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = now,
                    ),
                )
            }
            db.setSyncState("delta_cursor", "seeded-cursor")

            // Keep one /Documents file and all of /Other on disk at the
            // matching sizes so they reconcile as UNCHANGED and we only see
            // 19 /Documents/* deletes flow.
            Files.createDirectories(syncRoot.resolve("Documents"))
            Files.writeString(syncRoot.resolve("Documents/file-0.txt"), "x".repeat(1000))
            Files.createDirectories(syncRoot.resolve("Other"))
            for (i in 0 until 9) {
                Files.writeString(syncRoot.resolve("Other/file-$i.txt"), "x".repeat(5000 + i))
            }
            provider.deltaItems = emptyList()

            val ex =
                assertFailsWith<IllegalStateException> {
                    engineWithGuards(
                        maxDeleteAbsolute = 10_000,
                        maxDeletePerSubtreePercent = 80,
                        maxDeletePercentage = 0,
                    ).syncOnce(dryRun = false)
                }
            assertTrue(
                ex.message!!.contains("max_delete_per_subtree_percent"),
                "expected per-subtree message, got: ${ex.message}",
            )
            assertTrue(ex.message!!.contains("/Documents"))
        }

    @Test
    fun `UD-265 force-delete bypasses both new axes`() =
        runTest {
            seedDbEntries(60)
            Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
            provider.deltaItems = emptyList()

            // No exception even though the absolute cap is well under the
            // 59-delete plan.
            engineWithGuards(maxDeleteAbsolute = 50, maxDeletePercentage = 0)
                .syncOnce(dryRun = false, forceDelete = true)
        }

    @Test
    fun `UD-265 existing maxDeletePercentage axis still trips`() =
        runTest {
            // Same fixture as the UD-298 test — 99 of 100 entries deleted —
            // but explicitly request the maxDeletePercentage axis only by
            // setting maxDeleteAbsolute high. This documents the back-compat
            // contract: the legacy axis stays operative.
            seedDbEntries(100)
            Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
            provider.deltaItems = emptyList()

            val ex =
                assertFailsWith<IllegalStateException> {
                    engineWithGuards(
                        maxDeleteAbsolute = 10_000,
                        maxDeletePerSubtreePercent = 0,
                        maxDeletePercentage = 50,
                    ).syncOnce(dryRun = false)
                }
            assertTrue(ex.message!!.contains("max_delete_percentage"))
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

    // UD-737 — --upload-only is push-additive by default; --propagate-deletes opts back in

    private fun engineUploadOnly(propagateDeletes: Boolean) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            syncDirection = SyncDirection.UPLOAD,
            propagateDeletes = propagateDeletes,
        )

    @Test
    fun `UD-737 upload-only without propagate-deletes drops local-delete from plan`() =
        runTest {
            // Establish the file on both sides via a normal first-sync
            provider.files["/will-stay-on-remote.txt"] = ByteArray(10)
            provider.deltaItems = listOf(cloudItem("/will-stay-on-remote.txt", size = 10))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("will-stay-on-remote.txt")))

            // Delete locally — under the OLD semantics this would propagate to remote.
            Files.delete(syncRoot.resolve("will-stay-on-remote.txt"))

            // Empty delta (no remote-side change) so the engine sees only the
            // local-deletion candidate.
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            engineUploadOnly(propagateDeletes = false).syncOnce()

            // No call to provider.delete should have happened
            assertFalse(
                provider.deletedPaths.contains("/will-stay-on-remote.txt"),
                "expected del-remote suppressed under --upload-only default; got: ${provider.deletedPaths}",
            )
        }

    @Test
    fun `UD-737 upload-only with propagate-deletes propagates local-delete to remote`() =
        runTest {
            provider.files["/will-be-deleted.txt"] = ByteArray(10)
            provider.deltaItems = listOf(cloudItem("/will-be-deleted.txt", size = 10))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()

            Files.delete(syncRoot.resolve("will-be-deleted.txt"))

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            engineUploadOnly(propagateDeletes = true).syncOnce()

            assertTrue(
                provider.deletedPaths.contains("/will-be-deleted.txt"),
                "expected del-remote when --propagate-deletes set; got: ${provider.deletedPaths}",
            )
        }

    @Test
    fun `UD-737 upload-only still uploads new local files regardless of propagate-deletes`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()

            Files.writeString(syncRoot.resolve("new.txt"), "fresh")

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"

            engineUploadOnly(propagateDeletes = false).syncOnce()

            assertTrue(
                provider.uploadedPaths.contains("/new.txt"),
                "expected Upload action under --upload-only default; got: ${provider.uploadedPaths}",
            )
        }

    // UD-740 — move action displays src -> dst in the user-facing progress line

    @Test
    fun `UD-740 move action progress contains both fromPath and toPath separated by arrow`() =
        runTest {
            // Establish initial state: a folder with a file
            provider.deltaItems =
                listOf(
                    cloudItem("/a", isFolder = true),
                    cloudItem("/a/docs", isFolder = true),
                    cloudItem("/a/docs/file.txt", size = 42),
                )
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()

            // User moves /a/docs -> /b/docs (cross-parent)
            val aDir = syncRoot.resolve("a")
            val bDir = syncRoot.resolve("b")
            Files.createDirectories(bDir)
            Files.move(aDir.resolve("docs"), bDir.resolve("docs"))

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            val reporter = RecordingReporter()
            engineWithReporter(reporter).syncOnce()

            // The MoveRemote action's progress event must surface BOTH paths
            // joined by "->" (UD-740). Pre-fix, only the destination was shown.
            val moveEvents = reporter.actions.filter { it.label == "move" }
            assertTrue(moveEvents.isNotEmpty(), "expected at least one move event; got: ${reporter.actions}")
            assertTrue(
                moveEvents.any { it.path.contains("/a/docs") && it.path.contains("/b/docs") && it.path.contains("->") },
                "expected move event with 'from -> to' shape; got: $moveEvents",
            )
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
        override var isAuthenticated = true

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
            downloadByPathCalls.add(remotePath)
            val content = files[remotePath] ?: ByteArray(0)
            Files.createDirectories(destination.parent)
            Files.write(destination, content)
            return content.size.toLong()
        }

        // UD-225b: track id-vs-path dispatch separately so tests can verify the
        // engine takes the fast/robust path when the action carries a remoteId.
        // Default base impl in CloudProvider delegates to download(); this
        // override lets us count both call paths distinctly.
        val downloadByIdCalls = mutableListOf<Pair<String, String>>() // (remoteId, remotePath)
        val downloadByPathCalls = mutableListOf<String>()

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long {
            if (authFailOnDownload) throw AuthenticationException("Token expired")
            if (downloadFailCount > 0) {
                downloadFailCount--
                throw ProviderException("Network timeout on download")
            }
            downloadByIdCalls.add(remoteId to remotePath)
            val content = files[remotePath] ?: ByteArray(0)
            Files.createDirectories(destination.parent)
            Files.write(destination, content)
            return content.size.toLong()
        }

        var lastUploadExistingRemoteId: String? = null
            private set

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            lastUploadExistingRemoteId = existingRemoteId
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

        // UD-360: when set, delta() returns DeltaPage(complete=false) so that
        // tests can exercise the engine's partial-gather suppression path.
        var deltaComplete = true

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        ): DeltaPage {
            deltaCalls++
            if (deltaFailCount > 0) {
                deltaFailCount--
                throw ProviderException("Network timeout on delta")
            }
            return DeltaPage(
                items = deltaItems,
                cursor = deltaCursor,
                hasMore = false,
                complete = deltaComplete,
            )
        }

        override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)

        val remoteIdExists = mutableMapOf<String, Boolean>()

        override suspend fun verifyItemExists(remoteId: String): org.krost.unidrive.CapabilityResult<Boolean> =
            org.krost.unidrive.CapabilityResult
                .Success(remoteIdExists[remoteId] ?: true)
    }

    // UD-256 — persist --sync-path scope across runs; refuse bare bidirectional
    // when prior scoped runs left an effective_scope behind.

    private fun engineForScope(
        reporter: ProgressReporter = ProgressReporter.Silent,
        syncPath: String? = null,
        syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
        allowFullTreeReconciliation: Boolean = false,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = reporter,
        syncPath = syncPath,
        syncDirection = syncDirection,
        allowFullTreeReconciliation = allowFullTreeReconciliation,
    )

    @Test
    fun `UD-256 first run with --sync-path persists the scope into sync_state`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            assertEquals("/Documents", db.getSyncState("effective_scope"))
        }

    @Test
    fun `UD-256 second --sync-path run unions into the persisted scope`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            engineForScope(syncPath = "/Photos").syncOnce()
            val stored = db.getSyncState("effective_scope")!!.split("\t").toSet()
            assertEquals(setOf("/Documents", "/Photos"), stored)
        }

    @Test
    fun `UD-256 repeating the same --sync-path does not duplicate the entry`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            engineForScope(syncPath = "/Documents").syncOnce()
            assertEquals("/Documents", db.getSyncState("effective_scope"))
        }

    @Test
    fun `UD-256 bare bidirectional apply on a profile with persisted scope throws`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            val ex =
                assertFailsWith<IllegalStateException> {
                    engineForScope().syncOnce(dryRun = false)
                }
            assertTrue(ex.message!!.contains("UD-256"))
            assertTrue(ex.message!!.contains("/Documents"))
            assertTrue(ex.message!!.contains("--sync-path") || ex.message!!.contains("--full-tree"))
        }

    @Test
    fun `UD-256 bare bidirectional dry-run on scoped profile warns instead of throwing`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            val reporter = RecordingReporter()
            engineForScope(reporter = reporter).syncOnce(dryRun = true)
            assertTrue(
                reporter.warnings.any { it.contains("UD-256") && it.contains("/Documents") },
                "expected UD-256 warning, got: ${reporter.warnings}",
            )
        }

    @Test
    fun `UD-256 --full-tree clears persisted scope and allows the run`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            engineForScope(allowFullTreeReconciliation = true).syncOnce(dryRun = false)
            // Cleared (stored as empty string, treated as no scope on read).
            assertEquals("", db.getSyncState("effective_scope"))
        }

    @Test
    fun `UD-256 a --sync-path run on a scoped profile is never refused`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            // Running with a --sync-path (even a different one) must succeed —
            // the user is operating inside scoped mode, which is the safe path.
            engineForScope(syncPath = "/Photos").syncOnce(dryRun = false)
        }

    @Test
    fun `UD-256 fresh profile with no persisted scope does not refuse bare bidirectional`() =
        runTest {
            provider.deltaItems = emptyList()
            // No prior --sync-path run; effective_scope key is absent.
            engineForScope().syncOnce(dryRun = false)
        }

    @Test
    fun `UD-256 upload-only with persisted scope is not refused`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            // UPLOAD direction is push-additive per UD-737; the delete-the-cloud
            // pattern cannot trigger, so UD-256 does not refuse the run.
            engineForScope(syncDirection = SyncDirection.UPLOAD).syncOnce(dryRun = false)
        }

    @Test
    fun `UD-256 download-only with persisted scope is not refused`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            engineForScope(syncDirection = SyncDirection.DOWNLOAD).syncOnce(dryRun = false)
        }

    // PR #45 Codex P1 regressions: dry-run must NEVER mutate effective_scope.
    // Previously --full-tree --dry-run permanently cleared the guard; --sync-path
    // X --dry-run silently committed a new scope. Both are now read-only in
    // dry-run.

    @Test
    fun `UD-256 PR45-Codex --full-tree --dry-run does NOT clear persisted scope`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            val before = db.getSyncState("effective_scope")
            engineForScope(allowFullTreeReconciliation = true).syncOnce(dryRun = true)
            val after = db.getSyncState("effective_scope")
            assertEquals(
                before,
                after,
                "dry-run --full-tree must not mutate effective_scope; before=$before after=$after",
            )
        }

    @Test
    fun `UD-256 PR45-Codex --sync-path --dry-run does NOT extend persisted scope`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            val before = db.getSyncState("effective_scope")
            engineForScope(syncPath = "/Photos").syncOnce(dryRun = true)
            val after = db.getSyncState("effective_scope")
            assertEquals(
                before,
                after,
                "dry-run --sync-path must not mutate effective_scope; before=$before after=$after",
            )
        }

    @Test
    fun `UD-256 PR45-Codex first --sync-path --dry-run on fresh profile does NOT persist anything`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce(dryRun = true)
            assertEquals(
                null,
                db.getSyncState("effective_scope"),
                "first --sync-path run in dry-run must not seed effective_scope",
            )
        }
}
