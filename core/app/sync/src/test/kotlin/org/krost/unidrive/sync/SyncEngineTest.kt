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
    fun `delta cursor advances even when some downloads fail`() =
        runTest {
            // Regression for the 2026-05-20 first-sync loop: when transferFailures > 0
            // the cursor used to stay pinned at null, so every subsequent run re-did
            // the full enumeration and re-failed on the same flaky items. On a busy
            // 200k-item drive that loop never terminated — every gather had *some*
            // transient transfer failure (503 / network blip / partial upload).
            //
            // The UD-225 recovery loop in Reconciler.reconcile re-queues unhydrated
            // entries on the next pass, so promoting the cursor on the failure path
            // is safe: failed items get retried via that loop, not via re-delta.
            provider.files["/ok.txt"] = ByteArray(10)
            provider.files["/flaky.txt"] = ByteArray(10)
            provider.deltaItems =
                listOf(
                    cloudItem("/ok.txt", size = 10),
                    cloudItem("/flaky.txt", size = 10),
                )
            provider.deltaCursor = "advanced-cursor"
            provider.downloadFailCount = 1 // first download attempt throws

            engine.syncOnce()

            assertEquals(
                "advanced-cursor",
                db.getSyncState("delta_cursor"),
                "cursor must advance even when at least one download failed — the UD-225 recovery " +
                    "loop will pick up the failed item on the next pass; pinning the cursor here " +
                    "produces an inescapable first-sync loop on drives where transfers always " +
                    "have some transient failure",
            )
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
    fun `incomplete delta gather still advances pending_cursor (best-effort)`() =
        runTest {
            // Best-effort cursor advance: when a delta gather returns
            // complete=false (e.g. Internxt's 500/503 subtree skip path),
            // pending_cursor and delta_cursor STILL advance to the provider's
            // max(updatedAt) seen across completed pages.
            //
            // The alternative — pinning the cursor at its prior value on
            // any incomplete sweep — forced a full re-scan from the prior
            // cursor on every launch whenever any subtree 503'd, which on
            // a hot account (~190k items) is a 7+ hour scan every launch.
            // Items in the skipped subtree still recover on their next
            // mutation (Internxt's updatedAt filter is tombstone-aware) or
            // via the user-facing --reset escape hatch. The
            // pending_cursor_complete flag is still recorded so the doctor
            // surface can warn the user about the incomplete sweep.
            //
            // Establish a baseline cursor.
            provider.deltaItems = listOf(cloudItem("/tracked.txt", size = 50))
            provider.deltaCursor = "2026-05-18T10:00:00.000Z"
            provider.deltaComplete = true
            engine.syncOnce()
            assertEquals(
                "2026-05-18T10:00:00.000Z",
                db.getSyncState("delta_cursor"),
                "delta_cursor must advance on a complete gather",
            )

            // Second pass: complete=false but with a fresher cursor.
            // delta_cursor MUST still advance, and the flag captures the
            // incompleteness for downstream UX (doctor warning).
            provider.deltaItems = emptyList()
            provider.deltaCursor = "2026-05-18T17:20:30.000Z"
            provider.deltaComplete = false

            engine.syncOnce()

            assertEquals(
                "2026-05-18T17:20:30.000Z",
                db.getSyncState("delta_cursor"),
                "delta_cursor must advance even on an incomplete gather — pinning the " +
                    "cursor at its prior value forces a full re-scan every launch",
            )
            assertEquals("2026-05-18T17:20:30.000Z", db.getSyncState("pending_cursor"))
            assertEquals(
                "false",
                db.getSyncState("pending_cursor_complete"),
                "completeness flag still recorded so doctor surface can warn",
            )

            // Third pass: provider recovers, complete=true. Cursor advances
            // and the completeness flag flips back to true.
            provider.deltaCursor = "2026-05-18T18:00:00.000Z"
            provider.deltaComplete = true
            engine.syncOnce()
            assertEquals(
                "2026-05-18T18:00:00.000Z",
                db.getSyncState("delta_cursor"),
                "delta_cursor must advance on a recovered complete gather",
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
    fun `UD-264 PR46-Codex formatSkippedOpJson is parseable for paths with JSON-special chars`() {
        // PR #46 Codex P2: paths can contain `"`, `\`, and newline. failures.jsonl
        // already had ~119 PARSE_ERR entries from the same bug class in logFailure
        // (the audit observed `/\ninternxt-cli.desktop` in the wild). The prior
        // hand-built triple-quoted template produced invalid JSON for these.
        //
        // We test the formatter directly because the OS rejects these characters
        // in real paths on Windows — LocalScanner throws InvalidPathException
        // before any code under test can run. The lifted formatter has no FS
        // dependency, so this unit test exercises exactly the hazard.
        val nasty = "/Doc \"with\" \\back and\nnewline\t\r"
        val ts = Instant.parse("2026-05-17T10:00:00Z")
        val line = SyncEngine.formatSkippedOpJson(action = "del-remote", path = nasty, reason = "top_level_never_hydrated", ts = ts)

        // The output must be a single line of valid JSON.
        assertTrue(!line.contains('\n'), "formatted line must not contain a raw newline; got: $line")
        val json = kotlinx.serialization.json.Json
        val obj = json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject

        // Required fields present with correct shapes.
        val storedPath = (obj["path"] as kotlinx.serialization.json.JsonPrimitive).content
        val storedReason = (obj["reason"] as kotlinx.serialization.json.JsonPrimitive).content
        val storedAction = (obj["action"] as kotlinx.serialization.json.JsonPrimitive).content
        val storedTs = (obj["ts"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals(nasty, storedPath, "path must round-trip exactly")
        assertEquals("top_level_never_hydrated", storedReason)
        assertEquals("del-remote", storedAction)
        assertEquals("2026-05-17T10:00:00Z", storedTs)
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
    fun `UD-223 fast-bootstrap clears stale scan_in_progress checkpoint`() =
        runTest {
            // Live repro 2026-05-20: fast-bootstrap set delta_cursor without
            // clearing scan_in_progress. The next regular sync resumed
            // pagination at offset 128274|20069 against a different cursor's
            // result set — silently paging past items modified in the seam
            // before they could be observed. Pin that the cleanup runs.
            //
            // Seed: a prior, never-completed scan left a checkpoint in
            // sync_state. Fast-bootstrap on next launch must wipe it.
            db.setSyncState(StateDatabase.SCAN_IN_PROGRESS_ID, "stale-scan-id")
            db.setSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER, "128274|20069")
            db.setSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT, "2026-05-20T06:12:07.478Z")

            provider.supportsFastBootstrap = true
            provider.deltaCursor = "latest-cursor"

            bootstrapEngine().syncOnce()

            assertNull(
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID),
                "scan_in_progress_id must be cleared by fast-bootstrap — the old offsets index into the wrong result set",
            )
            assertNull(
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER),
                "scan_in_progress_marker must be cleared",
            )
            assertNull(
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT),
                "scan_in_progress_started_at must be cleared",
            )
            assertEquals("latest-cursor", db.getSyncState("delta_cursor"))
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
    fun `streaming gather dispatches downloads inside the gather scope without double-dispatching in Pass 2`() =
        runTest {
            // Phase 3 (Internxt boosters plan): the streaming-gather executor
            // fires DownloadContent / Upload actions concurrently with page
            // ingestion so the user sees bytes flowing in ~30 s instead of
            // waiting for the full enum. Pass 2 must NOT re-dispatch a
            // path the executor already handled — otherwise we double the
            // API round-trips and risk a file-locked-by-prior-write race on
            // Windows.
            //
            // Pin: with streamingReconciliation=on, N remote files produce
            // exactly N download calls across the run (not 2N). Existing
            // FakeCloudProvider returns the delta in a single page; that's
            // fine — the streaming executor still processes that single
            // page, marks the paths as executed, and Pass 2 finds them all
            // already in executedPaths and skips.
            provider.files["/a.txt"] = ByteArray(10)
            provider.files["/b.txt"] = ByteArray(10)
            provider.files["/c.txt"] = ByteArray(10)
            provider.deltaItems =
                listOf(
                    cloudItem("/a.txt", size = 10),
                    cloudItem("/b.txt", size = 10),
                    cloudItem("/c.txt", size = 10),
                )
            provider.deltaCursor = "after-streaming"

            val streamingEngine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    streamingReconciliation = true,
                )
            streamingEngine.syncOnce()

            val totalDownloads = provider.downloadByIdCalls.size + provider.downloadByPathCalls.size
            assertEquals(
                3,
                totalDownloads,
                "expected exactly one download per file across streaming-executor + Pass 2 " +
                    "(got byId=${provider.downloadByIdCalls.size} byPath=${provider.downloadByPathCalls.size}); " +
                    "duplicate calls mean Pass 2 didn't honour executedPaths",
            )
            // All three files should be hydrated locally.
            for (name in listOf("a.txt", "b.txt", "c.txt")) {
                assertTrue(Files.exists(syncRoot.resolve(name)), "$name must be downloaded")
            }
            // Cursor advanced normally — streaming dispatch is orthogonal
            // to the cursor flow and must not interfere with promotion.
            assertEquals("after-streaming", db.getSyncState("delta_cursor"))
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

    // -- Resumable-scan integration tests --

    @Test
    fun `gather provisions a fresh ScanContext when no checkpoint exists`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/a.txt", size = 10))
            provider.deltaCursor = "fresh-cursor"

            engine.syncOnce()

            val ctx = provider.lastScanContext
            assertNotNull(ctx, "engine must thread a ScanContext through delta()")
            assertNull(ctx.resumeMarker, "fresh scan has no resume marker")
            assertTrue(ctx.resumedItems.isEmpty(), "fresh scan has no resumed items")
            // Successful gather clears the staging slice + checkpoint.
            assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
        }

    @Test
    fun `gather resumes from an existing checkpoint and surfaces staged items`() =
        runTest {
            // Pre-seed an in-progress scan: simulate a daemon that staged one
            // page and crashed before the next page or the completion sweep.
            val scanId = db.beginScan(initialMarker = "200|100")
            val stagedItem =
                CloudItem(
                    id = "prev-uuid",
                    name = "prev.txt",
                    path = "/prev.txt",
                    size = 50,
                    isFolder = false,
                    modified = Instant.parse("2026-05-17T10:00:00Z"),
                    created = null,
                    hash = "prev-hash",
                    mimeType = null,
                    parentId = null,
                )
            db.persistScanPage(scanId, listOf(stagedItem), marker = "200|100")

            provider.deltaItems = listOf(cloudItem("/new.txt", size = 20))
            provider.deltaCursor = "post-resume-cursor"
            provider.files["/new.txt"] = ByteArray(20)

            engine.syncOnce()

            val ctx = provider.lastScanContext
            assertNotNull(ctx, "engine must thread a ScanContext on resume too")
            assertEquals("200|100", ctx.resumeMarker, "resume marker must be the persisted boundary")
            assertEquals(1, ctx.resumedItems.size, "previously-staged rows must rehydrate")
            assertEquals("prev-uuid", ctx.resumedItems.single().id)
            // Successful gather clears the staging slice + checkpoint.
            assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
        }

    @Test
    fun `gather discards a stale checkpoint and starts fresh`() =
        runTest {
            // Pre-seed a checkpoint older than the staleness threshold so the
            // engine treats the persisted state as untrustworthy. The next
            // delta() call must see a null resumeMarker + empty resumedItems.
            val scanId = db.beginScan(initialMarker = "999|999")
            db.persistScanPage(
                scanId,
                listOf(cloudItem("/stale.txt", size = 1)),
                marker = "999|999",
            )
            db.setSyncState(
                StateDatabase.SCAN_IN_PROGRESS_STARTED_AT,
                Instant.now().minus(java.time.Duration.ofDays(1)).toString(),
            )

            provider.deltaItems = listOf(cloudItem("/fresh.txt", size = 5))
            provider.deltaCursor = "fresh-after-stale"
            provider.files["/fresh.txt"] = ByteArray(5)

            engine.syncOnce()

            val ctx = provider.lastScanContext
            assertNotNull(ctx)
            assertNull(ctx.resumeMarker, "stale checkpoint must be ignored")
            assertTrue(ctx.resumedItems.isEmpty(), "stale staged rows must not resurface")
            // Fresh scan id was minted; staging cleared after successful gather.
            assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
        }

    @Test
    fun `gather completes the scan checkpoint after a successful delta`() =
        runTest {
            // The fake drives 3 simulated pages through the staging callback
            // BEFORE returning its DeltaPage. After the gather completes the
            // engine clears the staging slice — even though the persistPage
            // calls landed rows, the post-gather completeScan() wipes them.
            provider.stagedPages =
                listOf(
                    listOf(cloudItem("/p1-a.txt", size = 1), cloudItem("/p1-b.txt", size = 2)) to "0|50",
                    listOf(cloudItem("/p2-a.txt", size = 3)) to "100|50",
                    listOf(cloudItem("/p3-a.txt", size = 4)) to "150|50",
                )
            provider.deltaItems = listOf(cloudItem("/final.txt", size = 99))
            provider.deltaCursor = "final-cursor"
            provider.files["/final.txt"] = ByteArray(99)

            engine.syncOnce()

            assertNull(
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID),
                "checkpoint cleared after successful complete delta",
            )
            assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER))
            assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT))
        }

    @Test
    fun `gather leaves staging in place when delta throws mid-scan`() =
        runTest {
            // Simulate a crash mid-pagination by setting the fake to persist a
            // page through the staging callback and THEN throw. The engine
            // surfaces the exception; staging + checkpoint persist so the
            // next launch can resume.
            provider.persistThenFail = true
            provider.stagedPages =
                listOf(
                    listOf(cloudItem("/p1.txt", size = 100)) to "50|0",
                )
            provider.deltaItems = emptyList()

            assertFailsWith<ProviderException> {
                engine.syncOnce()
            }

            // Checkpoint + staged row survive — next launch resumes.
            val scanIdAfter = db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)
            assertNotNull(scanIdAfter, "crash mid-scan must leave the checkpoint in place")
            assertEquals("50|0", db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER))
            assertEquals(1, db.loadStagedItems(scanIdAfter).size)
        }

    @Test
    fun `gather preserves the checkpoint when delta returns complete=false`() =
        runTest {
            // A clean-exit gather pass that returned complete=false (e.g. the
            // Internxt 503-subtree-skip or ancestor-uuid-drop path) must NOT
            // clear the checkpoint — otherwise the next launch restarts at
            // offset 0 even though offset N was reached and persisted. This
            // is the cross-session resume seam paired with the best-effort
            // `delta_cursor` advance in promotePendingCursor.
            //
            // stagedPages drive the persistPage callback (per-page durability);
            // the returned DeltaPage is empty so no transfers fire — the test
            // pins the checkpoint lifecycle, not the reconciler.
            provider.stagedPages =
                listOf(
                    listOf(cloudItem("/p1.txt", size = 100)) to "150|50",
                )
            provider.deltaItems = emptyList()
            provider.deltaCursor = "incomplete-cursor"
            provider.deltaComplete = false

            engine.syncOnce()

            val scanIdAfter = db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)
            assertNotNull(scanIdAfter, "incomplete gather must leave the checkpoint in place")
            assertEquals(
                "150|50",
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER),
                "marker survives so the next session resumes from the same offset",
            )
            assertEquals(
                1,
                db.loadStagedItems(scanIdAfter).size,
                "staged rows survive so the next delta() rehydrates them via resumedItems",
            )
            assertNotNull(
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT),
                "started_at survives — the stale-threshold check is the safety net",
            )
        }

    @Test
    fun `cross-session resume clears the checkpoint once delta returns complete=true`() =
        runTest {
            // Pre-seed a cross-session checkpoint (prior run exited with
            // complete=false; marker + staged rows survived). The new
            // session's delta() honours the resume hint via ScanContext,
            // returns complete=true, and the engine clears everything so
            // the third session starts fresh.
            val priorScanId = db.beginScan(initialMarker = "150|50")
            val priorStaged =
                CloudItem(
                    id = "prev-uuid",
                    name = "prev.txt",
                    path = "/prev.txt",
                    size = 50,
                    isFolder = false,
                    modified = Instant.parse("2026-05-17T10:00:00Z"),
                    created = null,
                    hash = "prev-hash",
                    mimeType = null,
                    parentId = null,
                )
            db.persistScanPage(priorScanId, listOf(priorStaged), marker = "150|50")

            provider.deltaItems = listOf(cloudItem("/new.txt", size = 20))
            provider.deltaCursor = "complete-after-resume"
            provider.deltaComplete = true
            provider.files["/new.txt"] = ByteArray(20)

            engine.syncOnce()

            val ctx = provider.lastScanContext
            assertNotNull(ctx)
            assertEquals("150|50", ctx.resumeMarker, "engine must surface the prior marker as a resume hint")
            assertEquals(1, ctx.resumedItems.size, "previously-staged rows feed back as resumedItems")
            assertNull(
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID),
                "successful complete=true gather clears the cross-session checkpoint",
            )
            assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER))
            assertEquals(0, db.loadStagedItems(priorScanId).size, "staged rows cleared with the scan id")
        }

    // ---- Remote-change wake-hint debounce (Internxt notifications WS) ----

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `remote wake debounce coalesces a burst into a single listener call`() =
        runTest {
            val hits = java.util.concurrent.atomic.AtomicInteger(0)
            engine.registerRemoteWakeListener(this) { hits.incrementAndGet() }
            val raw = provider.registeredRemoteChangeCallback
            assertNotNull(raw, "engine must wire its debounced wrapper through provider.onRemoteChangeHint")

            // 50 raw frame arrivals in tight succession (no virtual-time advance
            // between them).
            repeat(50) { raw() }

            // Within the quiet window — nothing has fired yet.
            assertEquals(0, hits.get(), "no listener fire before the debounce window elapses")

            // Advance just past the debounce window — exactly one fire.
            testScheduler.advanceTimeBy(SyncEngine.REMOTE_WAKE_DEBOUNCE_MS + 100)
            testScheduler.runCurrent()
            assertEquals(1, hits.get(), "50-frame burst must coalesce into exactly one listener invocation")
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `remote wake debounce rearms on every fresh hint`() =
        runTest {
            val hits = java.util.concurrent.atomic.AtomicInteger(0)
            engine.registerRemoteWakeListener(this) { hits.incrementAndGet() }
            val raw = provider.registeredRemoteChangeCallback!!

            // Hit, advance to nearly-fire, hit again — the second hit must
            // restart the clock so we don't fire prematurely.
            raw()
            testScheduler.advanceTimeBy(SyncEngine.REMOTE_WAKE_DEBOUNCE_MS - 1000)
            testScheduler.runCurrent()
            assertEquals(0, hits.get(), "must not fire before the window elapses")

            raw() // resets the clock
            testScheduler.advanceTimeBy(SyncEngine.REMOTE_WAKE_DEBOUNCE_MS - 1000)
            testScheduler.runCurrent()
            assertEquals(0, hits.get(), "second hit must reset the clock so we don't fire on the old schedule")

            testScheduler.advanceTimeBy(2_000)
            testScheduler.runCurrent()
            assertEquals(1, hits.get(), "exactly one fire after the final quiet window elapses")
        }

    @Test
    fun `remote wake debounce wires through provider hook`() =
        runTest {
            engine.registerRemoteWakeListener(this) { /* listener body irrelevant */ }
            assertNotNull(
                provider.registeredRemoteChangeCallback,
                "engine must register a callback with the provider — that's the only way " +
                    "the provider can ever signal remote change",
            )
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

        // Resumable-scan instrumentation: captures the ScanContext the engine
        // passed in (resume_marker, # of resumedItems) so tests can verify
        // start-from-scratch vs resume behavior.
        var lastScanContext: org.krost.unidrive.ScanContext? = null
            private set

        // Optional per-page persistence simulation: if set, delta() pushes
        // each "page" through the staging callback before returning, so a
        // test can assert what landed in the staging slice.
        var stagedPages: List<Pair<List<CloudItem>, String>> = emptyList()

        // Mid-scan-crash simulator: run the stagedPages persistPage loop,
        // THEN throw, mimicking a daemon that persisted partial pages before
        // the next API call failed.
        var persistThenFail: Boolean = false

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            deltaCalls++
            lastScanContext = scanContext
            if (deltaFailCount > 0) {
                deltaFailCount--
                throw ProviderException("Network timeout on delta")
            }
            // Drive the engine's persistPage callback so a test can pin that
            // staged rows land in scan_staging.
            if (scanContext != null) {
                for ((page, marker) in stagedPages) {
                    scanContext.persistPage(page, marker)
                }
            }
            if (persistThenFail) {
                throw ProviderException("Simulated mid-scan crash after partial persistence")
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

        // Captured callback from registerRemoteWakeListener — tests fire it
        // directly to simulate the provider observing remote events.
        var registeredRemoteChangeCallback: (() -> Unit)? = null

        override fun onRemoteChangeHint(callback: () -> Unit) {
            registeredRemoteChangeCallback = callback
        }
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

    // ── Cloud-delete status flips (acceptance: both flip sites covered) ─────

    @Test
    fun `cloud-delete flip site 1 — applyDeleteRemote flips status to TRASHED`() =
        runTest {
            // Seed an alive row that the engine will propagate-delete to the
            // cloud (local file vanished while cloud was unchanged → engine
            // emits DeleteRemote → provider.delete + status flip).
            provider.deltaItems = listOf(cloudItem("/will-trash.txt", size = 100))
            engine.syncOnce()
            assertNotNull(db.getEntry("/will-trash.txt"))

            // Local delete; remote unchanged; reconciler emits DeleteRemote;
            // engine calls provider.delete + setStatusTrashed.
            Files.delete(syncRoot.resolve("will-trash.txt"))
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            // Provider saw the delete.
            assertTrue(provider.deletedPaths.contains("/will-trash.txt"))
            // Alive view no longer has it.
            assertNull(db.getEntry("/will-trash.txt"))
            // But the row survives as TRASHED — answerable via Recovery.
            val trashed = db.recovery.trashedEntries()
            assertEquals(1, trashed.size, "row must survive as TRASHED tombstone; got $trashed")
            assertEquals("/will-trash.txt", trashed.single().path)
            assertEquals("id-/will-trash.txt", trashed.single().remoteId)
        }

    @Test
    fun `cloud-delete flip site 2 — applyDeleteLocal flips status to TRASHED on remote-reports-deleted`() =
        runTest {
            // Establish the alive row first.
            provider.deltaItems = listOf(cloudItem("/will-vanish.txt", size = 100))
            engine.syncOnce()
            assertNotNull(db.getEntry("/will-vanish.txt"))

            // Cloud reports the item deleted; local cascades and flips status.
            provider.deltaItems = listOf(cloudItem("/will-vanish.txt", deleted = true))
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()

            // Local file gone.
            assertFalse(Files.exists(syncRoot.resolve("will-vanish.txt")))
            // Alive view no longer has it.
            assertNull(db.getEntry("/will-vanish.txt"))
            // Row survives as TRASHED.
            val trashed = db.recovery.trashedEntries()
            assertEquals(1, trashed.size)
            assertEquals("/will-vanish.txt", trashed.single().path)
        }
}
