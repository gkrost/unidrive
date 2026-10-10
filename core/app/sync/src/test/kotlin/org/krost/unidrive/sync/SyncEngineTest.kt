package org.krost.unidrive.sync

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.sync.audit.AuditLog
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class SyncEngineTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var dbPath: java.nio.file.Path
    private lateinit var engine: SyncEngine
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-engine-test")
        dbPath = Files.createTempDirectory("unidrive-engine-db").resolve("state.db")
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

    /**
     * Runs [block] the way `sync --dry-run` does: the engine builders it calls get a throwaway snapshot of the
     * state database, never the real one. The real database is restored afterwards.
     */
    private suspend fun <R> preview(block: suspend () -> R): R {
        val real = db
        val snapshot = StateDatabase.snapshotOf(dbPath)
        db = snapshot
        try {
            return block()
        } finally {
            db = real
            snapshot.close()
        }
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

    /**
     * #417 (engine half): the Reconciler compares a delta item's modified time to the row for
     * equality, so the engine must store exactly what the provider returned for the write and a
     * delta that echoes it must plan nothing. If a provider returns a stale timestamp here the
     * next sync downloads the file it just uploaded (fixed provider-side in InternxtProvider).
     */
    @Test
    fun `#417 delta echoing the modified time of an in-place replace plans nothing for that path`() =
        runTest {
            provider.deltaItems = emptyList()
            engine.syncOnce()
            Files.writeString(syncRoot.resolve("mod.txt"), "v1")
            engine.syncOnce()
            val firstRemoteId = db.getEntry("/mod.txt")?.remoteId
            assertNotNull(firstRemoteId)

            Thread.sleep(20) // ensure mtime advances on filesystems with second-level resolution
            Files.writeString(syncRoot.resolve("mod.txt"), "v2-longer-content-to-bump-size")
            // The server stamped the replace with its own time; the provider reports exactly that.
            val serverModified = Instant.parse("2026-05-03T16:36:29Z")
            provider.uploadModified = serverModified
            engine.syncOnce()
            assertEquals(firstRemoteId, provider.lastUploadExistingRemoteId, "the edit must go through replace-in-place")
            val row = db.getEntry("/mod.txt")
            assertNotNull(row)
            assertEquals(serverModified, row.remoteModified, "the row must carry the provider-reported modified time")

            // Next sync: the delta re-reports the item exactly as the server now lists it.
            val capturing = CapturingActionCountReporter()
            val engine2 =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = capturing,
                )
            provider.deltaItems =
                listOf(
                    cloudItem("/mod.txt", size = row.remoteSize)
                        .copy(id = row.remoteId!!, hash = row.remoteHash, modified = serverModified),
                )
            val uploadsBefore = provider.uploadedPaths.size
            val downloadsBefore = provider.downloadByIdCalls.size + provider.downloadByPathCalls.size
            engine2.syncOnce()

            assertEquals(0, capturing.lastPreFilterTotal, "an echo of our own replace must plan zero actions")
            assertEquals(uploadsBefore, provider.uploadedPaths.size, "no re-upload")
            assertEquals(
                downloadsBefore,
                provider.downloadByIdCalls.size + provider.downloadByPathCalls.size,
                "no download of the file we just uploaded",
            )
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
    fun `#419 re-delivered remote delete of a trashed and locally gone row is not planned again`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/will-delete.txt", size = 100))
            engine.syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("will-delete.txt")))

            // Run 1 sees the deletion: file removed locally, row becomes a TRASHED tombstone.
            provider.deltaItems = listOf(cloudItem("/will-delete.txt", deleted = true))
            provider.deltaCursor = "cursor-2"
            engine.syncOnce()
            assertFalse(Files.exists(syncRoot.resolve("will-delete.txt")))
            assertEquals(1, db.recovery.trashedEntries().size, "the deleted row is a TRASHED tombstone")

            // The rewound Internxt cursor re-delivers the same deleted item on the next run. A
            // dry-run must not claim there is anything left to delete locally.
            val reporter = RecordingReporter()
            preview {
                engineWithReporter(reporter).syncOnce(dryRun = true)
            }

            assertTrue(
                reporter.actions.none { it.label == "del-local" },
                "no del-local for a trashed, gone row; got ${reporter.actions}",
            )
            assertEquals(1, db.recovery.trashedEntries().size, "the tombstone is untouched")
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

    // #526: a local name ending in a space (only creatable through an extended-length path or a
    // Linux/WSL tool) used to abort the whole sync from the planner and poison doctor, the replay
    // and the rescan. The scanner skips such names, the replay/rescan/mirror paths guard their
    // resolves, and the transfer stage catches per item — a stale pre-guard row must not abort
    // the run (this test runs on Windows, where Path.resolve of a trailing-space component throws).
    @Test
    fun `#526 a stale row with a trailing-space name does not abort the sync`() =
        runTest {
            db.upsertEntry(
                SyncEntry(
                    path = "/trailing-space ",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 5,
                    remoteModified = Instant.now(),
                    localMtime = Instant.now().toEpochMilli(),
                    localSize = 5,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            Files.writeString(syncRoot.resolve("sibling.txt"), "healthy")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "c526"

            engine.syncOnce()

            assertEquals("c526", db.getSyncState("delta_cursor"), "the run completed and promoted the cursor")
        }

    // ── #523: a whole-drive gather that skipped a folder must not look complete afterwards —
    // the idle enumeration status carries the last gather's completeness for the status UI and
    // daemon.status (the per-folder timer itself is already the #518 330 s listing timer).

    @Test
    fun `#523 an incomplete gather surfaces lastScanComplete=false in the idle status`() =
        runTest {
            provider.files["/a.txt"] = ByteArray(10)
            provider.deltaItems = listOf(cloudItem("/a.txt", size = 10))
            provider.deltaCursor = "c523"
            provider.deltaComplete = false

            engine.syncOnce()

            assertEquals(false, engine.enumerationStatus().lastScanComplete, "the skipped folder is not hidden: the status says the last gather was incomplete")

            // A later complete gather clears it.
            provider.deltaComplete = true
            engine.syncOnce()
            assertEquals(true, engine.enumerationStatus().lastScanComplete)
        }

    // ── #531: after --fast-bootstrap, folders that exist remotely but were never enumerated stay
    // unknown to state.db — the planner emitted mkdir-remote for them every run and each one 409'd
    // (nameAlreadyExists), counting a failure on every sync. The apply now resolves the conflict:
    // a create that fails with the folder already present adopts the existing folder's remote id.

    @Test
    fun `#531 a mkdir-remote for a folder that already exists remotely is adopted, not failed`() =
        runTest {
            provider.remoteFoldersExisting.add("/docs")
            provider.remoteFoldersExisting.add("/docs/deep")
            Files.createDirectories(syncRoot.resolve("docs/deep"))
            Files.writeString(syncRoot.resolve("docs/a.txt"), "a")
            Files.writeString(syncRoot.resolve("docs/deep/b.txt"), "b")
            provider.deltaItems = emptyList()
            provider.deltaCursor = "c531"

            // The mirror pass's mkdir-remote: a folder that is NEW locally and unknown to
            // state.db is created remotely through applyCreateRemoteFolder, which adopts the
            // existing remote id instead of failing (#531: a fast-bootstrap adopted cursor
            // never enumerated the folder).
            engine.syncOnce()

            assertEquals("exists-/docs", db.getEntry("/docs")?.remoteId, "the top-level folder adopted the existing remote id")
            assertEquals("exists-/docs/deep", db.getEntry("/docs/deep")?.remoteId, "the nested folder adopted too — any depth, any run")
            assertFalse(provider.createdFolders.contains("/docs"), "no create was issued for the existing folder")
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

    // #503 review: a name refused as an NFC clash (#491) is out of sync in both directions. The scan reports no change for
    // it, so a remote edit of the cloud copy must not be downloaded over the local member that carries the name.
    @Test
    fun `a remote edit of a name in an NFC clash is not downloaded over the local file`() =
        runTest {
            val composed = "caf" + '\u00E9' + ".txt"
            val decomposed = "cafe" + '\u0301' + ".txt"
            provider.files["/$composed"] = "cloud v1".toByteArray()
            provider.deltaItems = listOf(cloudItem("/$composed", size = 8))
            provider.deltaCursor = "cursor-1"
            engine.syncOnce()
            assertEquals("cloud v1", Files.readString(syncRoot.resolve(composed)))

            // Locally: the composed file is edited and its decomposed twin appears; in the cloud: the copy changes.
            Files.writeString(syncRoot.resolve(composed), "my local edit")
            Files.writeString(syncRoot.resolve(decomposed), "the twin")
            provider.files["/$composed"] = "edited in the cloud".toByteArray()
            provider.deltaItems =
                listOf(cloudItem("/$composed", size = 19).copy(hash = "new-hash", modified = Instant.parse("2026-03-29T12:00:00Z")))
            provider.deltaCursor = "cursor-2"
            provider.uploadedPaths.clear()
            engine.syncOnce()

            assertEquals("my local edit", Files.readString(syncRoot.resolve(composed)), "the local member must not be overwritten")
            assertEquals("the twin", Files.readString(syncRoot.resolve(decomposed)))
            assertEquals(emptyList(), provider.uploadedPaths, "neither member is uploaded while the clash exists")
            assertEquals("edited in the cloud", String(provider.files.getValue("/$composed")), "the cloud copy is untouched")
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
        var lastActionCounts: Map<String, Int> = emptyMap()
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
            lastActionCounts = actionCounts
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
            preview {
                engineWithReporter(reporter).syncOnce(dryRun = true)
            }
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
                preview {
                    engineWithReporter(reporter).syncOnce(dryRun = true)
                }
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
            preview {
                engineWithReporter(reporter).syncOnce(dryRun = true)
            }
            assertFalse(
                reporter.warnings.any { it.contains("is empty") },
                "empty-sync_root warning fired on a fresh sync where there's nothing to delete",
            )
        }

    @Test
    fun `UD-297 empty sync_root with only unhydrated DB entries does not block`() =
        runTest {
            // Internxt "dance" repro 2026-05-20: previous failed gather passes
            // populated state.db with 171k file rows, all is_hydrated=0, and
            // never managed to actually download anything. sync_root is empty
            // because nothing was ever downloaded. Pre-fix the empty-local
            // guard refused with a "--force-delete" hint that would have
            // catastrophically wiped the cloud side; post-fix the guard
            // gates on the HYDRATED count so the UD-225 recovery loop can
            // re-hydrate the cloud inventory locally instead.
            val now = Instant.parse("2026-01-01T00:00:00Z")
            for (i in 0 until 100) {
                db.upsertEntry(
                    org.krost.unidrive.sync.model.SyncEntry(
                        path = "/unhydrated-$i.txt",
                        remoteId = "id-$i",
                        remoteHash = "hash-$i",
                        remoteSize = 100,
                        remoteModified = now,
                        localMtime = 0L,
                        localSize = 0L,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = false,
                        lastSynced = now,
                    ),
                )
            }
            db.setSyncState("delta_cursor", "seeded-cursor")
            provider.deltaItems = emptyList()

            // Must not throw — engine proceeds, UD-225 recovery loop will
            // emit DownloadContent for each unhydrated row.
            engineWithReporter(ProgressReporter.Silent).syncOnce(dryRun = false)
        }

    // #137 — download-only rehydrate must not be blocked by the empty-sync_root guard

    private fun engineWithDirection(direction: SyncDirection) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            syncDirection = direction,
        )

    @Test
    fun `#137 download-only with empty sync_root and hydrated DB does not trigger empty-sync_root guard`() =
        runTest {
            // Rehydrate scenario: user intentionally wiped local, wants cloud copy back.
            // Guard must not fire — the destructive local→remote-delete direction is
            // already gated by the direction filter in download-only mode.
            seedDbEntries(50)
            provider.deltaItems = emptyList()
            // Must not throw — download-only rehydrate proceeds without guard intervention.
            engineWithDirection(SyncDirection.DOWNLOAD).syncOnce(dryRun = false)
        }

    @Test
    fun `#160 download-only with hydrated rows locally missing re-downloads files`() =
        runTest {
            // #160 invariant: a download-only run where state.db has a hydrated row
            // whose local file is absent and the cloud delta is unchanged must
            // re-download the file. Before the fix: DeleteRemote was dropped by the
            // direction filter and the file stayed unreachable forever.
            val filePath = "/rehydrate-me.txt"
            val fileContent = "content to rehydrate".toByteArray()
            provider.files[filePath] = fileContent
            // Seed a hydrated DB entry — simulates a previously-synced file.
            val now = Instant.parse("2026-01-01T00:00:00Z")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = filePath,
                    remoteId = "id-rehydrate",
                    remoteHash = "hash-rehydrate",
                    remoteSize = fileContent.size.toLong(),
                    remoteModified = now,
                    localMtime = now.toEpochMilli(),
                    localSize = fileContent.size.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = now,
                ),
            )
            db.setSyncState("delta_cursor", "existing-cursor")
            // Local file is absent (intentionally wiped). Cloud delta carries no
            // change for this path (unchanged remote) — the common rehydrate shape.
            provider.deltaItems = emptyList()
            // Run download-only — must re-download the locally-missing hydrated row.
            engineWithDirection(SyncDirection.DOWNLOAD).syncOnce(dryRun = false)
            // File must be restored in sync_root.
            val localFile = syncRoot.resolve(filePath.trimStart('/'))
            assertTrue(Files.exists(localFile), "rehydrate-me.txt must be downloaded back to sync_root")
            assertEquals(fileContent.size.toLong(), Files.size(localFile))
        }

    @Test
    fun `#160 bidirectional unchanged - locally-deleted hydrated row propagates as DeleteRemote`() =
        runTest {
            // Bidirectional invariant must not regress: a locally-deleted hydrated
            // file in bidirectional mode is a user delete that propagates to remote.
            val filePath = "/user-deleted.txt"
            val now = Instant.parse("2026-01-01T00:00:00Z")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = filePath,
                    remoteId = "id-user-deleted",
                    remoteHash = "hash-x",
                    remoteSize = 50,
                    remoteModified = now,
                    localMtime = now.toEpochMilli(),
                    localSize = 50,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = now,
                ),
            )
            db.setSyncState("delta_cursor", "existing-cursor")
            // Local file absent; cloud delta carries no change.
            provider.deltaItems = emptyList()
            // Seed sync_root with at least one file so the empty-sync_root guard doesn't
            // fire (guard needs >10 hydrated entries; we only have 1 here).
            Files.writeString(syncRoot.resolve("other.txt"), "keep")
            engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false)
            // Remote delete must have been called for the locally-deleted path.
            assertTrue(
                provider.deletedPaths.contains(filePath),
                "bidirectional locally-deleted hydrated row must propagate as DeleteRemote; deleted=${provider.deletedPaths}",
            )
        }

    @Test
    fun `sync_summary_counts_the_deletes_that_were_applied`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/gone.txt",
                    remoteId = "id-gone",
                    remoteHash = "hash-x",
                    remoteSize = 50,
                    remoteModified = now,
                    localMtime = now.toEpochMilli(),
                    localSize = 50,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = now,
                ),
            )
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            Files.writeString(syncRoot.resolve("other.txt"), "keep")
            val reporter = RecordingReporter()
            engineWithReporter(reporter).syncOnce(dryRun = false)
            assertEquals(1, reporter.lastActionCounts["del-remote"], "counts=${reporter.lastActionCounts}")
        }

    @Test
    fun `sync_summary_does_not_count_a_delete_that_was_not_applied`() =
        runTest {
            val reporter = RecordingReporter()
            engineWithReporter(reporter).syncOnce(dryRun = false)
            assertEquals(null, reporter.lastActionCounts["del-remote"], "counts=${reporter.lastActionCounts}")
        }

    @Test
    fun `empty remote directory is reaped after its last file is deleted`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            // A tracked file under /dir whose local copy is gone → DeleteRemote.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/dir/only.txt",
                    remoteId = "id-only",
                    remoteHash = "h",
                    remoteSize = 10,
                    remoteModified = now,
                    localMtime = now.toEpochMilli(),
                    localSize = 10,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = now,
                ),
            )
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            // After the file delete, /dir lists no children → reapable.
            provider.childrenByParent["/dir"] = emptyList()
            Files.writeString(syncRoot.resolve("other.txt"), "keep") // satisfy empty-root guard

            engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false)

            assertTrue(provider.deletedPaths.contains("/dir/only.txt"), "the file must be deleted")
            assertTrue(
                provider.deletedPaths.contains("/dir"),
                "the now-empty directory must be reaped; deleted=${provider.deletedPaths}",
            )
        }

    @Test
    fun `remote directory with a surviving sibling is NOT reaped`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/dir/gone.txt",
                    remoteId = "id-gone",
                    remoteHash = "h",
                    remoteSize = 10,
                    remoteModified = now,
                    localMtime = now.toEpochMilli(),
                    localSize = 10,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = now,
                ),
            )
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            // /dir still has a surviving child after the delete → must NOT be reaped.
            provider.childrenByParent["/dir"] = listOf(cloudItem("/dir/keep.txt", size = 5))
            Files.writeString(syncRoot.resolve("other.txt"), "keep")

            engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false)

            assertTrue(provider.deletedPaths.contains("/dir/gone.txt"), "the file must be deleted")
            assertFalse(
                provider.deletedPaths.contains("/dir"),
                "a directory with a surviving child must NOT be reaped; deleted=${provider.deletedPaths}",
            )
        }

    // -- #419: reaper vs. a folder the same run already deleted --

    /** A `provider.listChildren`/`delete` failure that carries an HTTP status, like InternxtApiException. */
    private class StatusProviderException(
        message: String,
        val statusCode: Int,
    ) : ProviderException(message)

    private fun trackedRow(
        path: String,
        isFolder: Boolean,
    ): org.krost.unidrive.sync.model.SyncEntry {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        return org.krost.unidrive.sync.model.SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = if (isFolder) null else "h",
            remoteSize = if (isFolder) 0 else 10,
            remoteModified = now,
            localMtime = now.toEpochMilli(),
            localSize = if (isFolder) 0 else 10,
            isFolder = isFolder,
            isPinned = false,
            isHydrated = true,
            lastSynced = now,
        )
    }

    /** Runs [block] with a capture appender on the SyncEngine logger and returns the WARN lines it saw. */
    private suspend fun engineWarnsDuring(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SyncEngine::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }
    }

    @Test
    fun `#530 a failed transfer keeps its stack trace out of the console loggers`() =
        runTest {
            // The console appender's threshold is WARN, so a WARN carrying the throwable printed
            // the full JVM stack trace to stdout for every expected per-file failure (the live
            // pass printed 66 trace lines for three expected HTTP 400s). The invariant: per-action
            // WARN lines carry the item and the provider's message only; the throwable lives on a
            // DEBUG event (the file appender takes DEBUG, the console drops it).
            provider.files["/flaky.txt"] = ByteArray(10)
            provider.deltaItems = listOf(cloudItem("/flaky.txt", size = 10))
            provider.deltaCursor = "cursor-530"
            provider.downloadFailCount = 1

            val logger = LoggerFactory.getLogger(SyncEngine::class.java) as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            try {
                engine.syncOnce()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }

            val warnsWithThrowable = appender.list.filter { it.level.levelStr == "WARN" && it.throwableProxy != null }
            assertTrue(
                warnsWithThrowable.isEmpty(),
                "no WARN event may carry a stack trace; got: ${warnsWithThrowable.map { it.formattedMessage }}",
            )
            val debugsWithThrowable = appender.list.filter { it.level.levelStr == "DEBUG" && it.throwableProxy != null }
            assertTrue(
                debugsWithThrowable.any { it.formattedMessage.contains("/flaky.txt") },
                "the throwable must live on a DEBUG event for the failed item; got: ${debugsWithThrowable.map { it.formattedMessage }}",
            )
            assertTrue(
                appender.list.filter { it.level.levelStr == "WARN" }.any { it.formattedMessage.contains("/flaky.txt") },
                "the one WARN line must still name the failed item; got: ${appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }}",
            )
        }

    @Test
    fun `#419 reaper does not touch a folder this run already deleted`() =
        runTest {
            // Deleting a local folder plans DeleteRemote for its files AND for the folder itself.
            // The folder is trashed by that DeleteRemote, so the empty-directory reaper (whose
            // candidates are the ancestors of the deleted files) must not list or delete it again.
            db.upsertEntry(trackedRow("/dir", isFolder = true))
            db.upsertEntry(trackedRow("/dir/only.txt", isFolder = false))
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            // Once trashed, the folder no longer lists: the live 404 the reaper used to WARN about.
            provider.listChildrenThrow["/dir"] = StatusProviderException("API error: 404 Not Found - {}", 404)
            Files.writeString(syncRoot.resolve("other.txt"), "keep") // satisfy empty-root guard

            val warns = engineWarnsDuring { engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false) }

            assertEquals(1, provider.deletedPaths.count { it == "/dir" }, "folder deleted exactly once; deleted=${provider.deletedPaths}")
            assertTrue(provider.deletedPaths.contains("/dir/only.txt"), "the file must be deleted; deleted=${provider.deletedPaths}")
            assertFalse("/dir" in provider.listChildrenCalls, "reaper must not probe a folder this run deleted; calls=${provider.listChildrenCalls}")
            assertTrue(warns.none { it.contains("reap") }, "no reap WARN expected; got $warns")
        }

    @Test
    fun `#419 reaper treats a 404 from listChildren as already gone`() =
        runTest {
            // The folder is NOT in this run's plan, but vanished remotely (trashed elsewhere): the
            // listing 404s. End state is what the reaper wanted, so it is not a warning.
            db.upsertEntry(trackedRow("/dir/gone.txt", isFolder = false))
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            provider.listChildrenThrow["/dir"] = StatusProviderException("API error: 404 Not Found - {}", 404)
            Files.writeString(syncRoot.resolve("other.txt"), "keep")

            val warns = engineWarnsDuring { engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false) }

            assertTrue("/dir" in provider.listChildrenCalls, "reaper must still probe /dir; calls=${provider.listChildrenCalls}")
            assertTrue(warns.none { it.contains("reap") }, "a 404 while reaping means already gone, not WARN; got $warns")
        }

    @Test
    fun `#419 reaper treats a 404 from delete as already gone`() =
        runTest {
            db.upsertEntry(trackedRow("/dir/gone.txt", isFolder = false))
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            provider.childrenByParent["/dir"] = emptyList() // empty, so the reaper tries to delete it
            provider.deleteThrowByPath["/dir"] = StatusProviderException("API error: 404 Not Found - {}", 404)
            Files.writeString(syncRoot.resolve("other.txt"), "keep")

            val warns = engineWarnsDuring { engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false) }

            assertTrue(provider.deletedPaths.contains("/dir/gone.txt"), "the file must be deleted; deleted=${provider.deletedPaths}")
            assertTrue(warns.none { it.contains("reap") }, "a 404 while reaping means already gone, not WARN; got $warns")
        }

    @Test
    fun `#419 reaper still warns on a real listing failure`() =
        runTest {
            db.upsertEntry(trackedRow("/dir/gone.txt", isFolder = false))
            db.setSyncState("delta_cursor", "existing-cursor")
            provider.deltaItems = emptyList()
            provider.listChildrenThrow["/dir"] = StatusProviderException("API error: 503 Service Unavailable - {}", 503)
            Files.writeString(syncRoot.resolve("other.txt"), "keep")

            val warns = engineWarnsDuring { engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false) }

            assertTrue(
                warns.any { it.startsWith("Failed to reap empty remote directory /dir") },
                "a genuine failure must still be a WARN; got $warns",
            )
        }

    @Test
    fun `#137 bidirectional with empty sync_root and hydrated DB still fires the empty-sync_root guard`() =
        runTest {
            // Data-safety preserved: bidirectional sync with empty local + hydrated DB
            // must still be refused to prevent a mass remote-delete.
            seedDbEntries(50)
            provider.deltaItems = emptyList()
            val ex =
                assertFailsWith<IllegalStateException> {
                    engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false)
                }
            assertTrue(ex.message!!.contains("sync_root"))
            assertTrue(ex.message!!.contains("is empty"))
        }

    @Test
    fun `#137 aborted guard run does not create empty sync_root directory`() =
        runTest {
            // An aborted (guard-fired) bidirectional run must not leave an empty
            // sync_root dir behind. createDirectories now runs after the guard.
            val guardSyncRoot = Files.createTempDirectory("unidrive-guard-test-parent")
                .resolve("never-created")
            val guardDb = StateDatabase(
                Files.createTempDirectory("unidrive-guard-db").resolve("state.db"),
            )
            guardDb.initialize()
            val now = Instant.parse("2026-01-01T00:00:00Z")
            for (i in 0 until 50) {
                guardDb.upsertEntry(
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
            guardDb.setSyncState("delta_cursor", "seeded-cursor")
            val guardEngine = SyncEngine(
                provider = FakeCloudProvider().also { it.deltaItems = emptyList() },
                db = guardDb,
                syncRoot = guardSyncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                syncDirection = SyncDirection.BIDIRECTIONAL,
            )
            assertFailsWith<IllegalStateException> {
                guardEngine.syncOnce(dryRun = false)
            }
            guardDb.close()
            assertFalse(
                Files.exists(guardSyncRoot),
                "aborted guard run must not create empty sync_root at $guardSyncRoot",
            )
        }

    // PR #195 P2-2 — watcher/guard/dir-create ordering: syncOnce creates sync_root
    // before returning so LocalWatcher.start() can walk it without throwing.

    @Test
    fun `#137 PR195-P2-2 syncOnce creates sync_root so a subsequent watcher start does not throw`() =
        runTest {
            // Fresh sync on a non-existent sync_root that passes the guard (empty DB).
            // After syncOnce, sync_root must exist — the watcher can walk it.
            val freshSyncRoot = Files.createTempDirectory("unidrive-p2test-parent").resolve("new-root")
            assertFalse(Files.exists(freshSyncRoot), "precondition: sync_root must not exist before syncOnce")
            val freshDb = StateDatabase(Files.createTempDirectory("unidrive-p2db").resolve("state.db"))
            freshDb.initialize()
            val freshEngine = SyncEngine(
                provider = FakeCloudProvider().also { it.deltaItems = emptyList() },
                db = freshDb,
                syncRoot = freshSyncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
            freshEngine.syncOnce(dryRun = false)
            assertTrue(
                Files.isDirectory(freshSyncRoot),
                "sync_root must exist after syncOnce so the watcher can start without throwing",
            )
            // Verify the watcher can start on the now-existing directory without throwing.
            val watcher = LocalWatcher(freshSyncRoot, debounceMs = 50L)
            try {
                watcher.start() // must not throw
            } finally {
                watcher.stop()
                freshDb.close()
            }
        }

    @Test
    fun `#137 PR195-P2-2 guard-aborted syncOnce does not create sync_root so watcher start throws`() =
        runTest {
            // Guard fires (hydrated DB entries + empty sync_root) → syncOnce throws
            // without creating sync_root. A watcher started on a non-existent
            // sync_root must throw — proving that if the guard fires the watcher
            // must not be started (which is the SyncCommand ordering invariant).
            val guardSyncRoot2 = Files.createTempDirectory("unidrive-p2guard-parent").resolve("absent")
            val guardDb2 = StateDatabase(Files.createTempDirectory("unidrive-p2guarddb").resolve("state.db"))
            guardDb2.initialize()
            val now = Instant.parse("2026-01-01T00:00:00Z")
            for (i in 0 until 50) {
                guardDb2.upsertEntry(
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
            guardDb2.setSyncState("delta_cursor", "seeded-cursor")
            val guardEngine2 = SyncEngine(
                provider = FakeCloudProvider().also { it.deltaItems = emptyList() },
                db = guardDb2,
                syncRoot = guardSyncRoot2,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                syncDirection = SyncDirection.BIDIRECTIONAL,
            )
            assertFailsWith<IllegalStateException> { guardEngine2.syncOnce(dryRun = false) }
            assertFalse(Files.exists(guardSyncRoot2), "guard-aborted run must not create sync_root")
            // Watcher started on the absent directory must throw, confirming that
            // starting it BEFORE syncOnce (the old bug) would have failed here.
            val watcher2 = LocalWatcher(guardSyncRoot2, debounceMs = 50L)
            assertFailsWith<java.io.IOException> { watcher2.start() }
            guardDb2.close()
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
            preview {
                engineWithReporter(reporter).syncOnce(dryRun = true)
            }

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
            preview {
                engineWithReporter(reporter).syncOnce(dryRun = true, forceDelete = true)
            }

            assertFalse(
                reporter.warnings.any { it.contains("Deletion safeguard") },
                "force-delete should suppress safeguard warning, got: ${reporter.warnings}",
            )
        }

    // #517 R3: the daemon's enumerate poller seeds its backoff at start from the
    // consecutive-failure streak in sync_state. Pin both halves of the contract.

    @Test
    fun `a failed gather bumps the enumerate failure streak`() =
        runTest {
            provider.deltaFailCount = 2

            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("1", db.getSyncState(SyncEngine.ENUMERATE_FAILURE_STREAK_KEY))
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("2", db.getSyncState(SyncEngine.ENUMERATE_FAILURE_STREAK_KEY))
        }

    @Test
    fun `a gather that comes back resets the enumerate failure streak`() =
        runTest {
            provider.deltaFailCount = 1

            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("1", db.getSyncState(SyncEngine.ENUMERATE_FAILURE_STREAK_KEY))

            // The fail count is spent; this gather returns and must clear the streak.
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("0", db.getSyncState(SyncEngine.ENUMERATE_FAILURE_STREAK_KEY))
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
    fun `del-remote for unhydrated folder rows is skipped and audited`() =
        runTest {
            // Three unhydrated folders under /Documents; sync_root has one file
            // so UD-297 doesn't preempt. Provider returns no delta.
            //
            // The Reconciler drops DeleteRemote actions for unhydrated folder
            // rows before they reach the action executor — a sparse-hydration
            // profile would otherwise plan destruction of every cloud folder
            // the user never visited (the 2026-05-16 incident shape on
            // `inxt_gernot_krost_posteo`). The engine writes the dropped paths
            // to `skipped-ops.jsonl` for operator audit; this test pins both
            // halves of that contract.
            seedUnhydratedFolders("/Documents", count = 3)
            Files.writeString(syncRoot.resolve("placeholder.txt"), "x")
            provider.deltaItems = emptyList()

            val logPath = Files.createTempDirectory("unidrive-skipped").resolve("skipped-ops.jsonl")
            val reporter = RecordingReporter()
            engineWithGuards(reporter = reporter, skippedOpsLogPath = logPath).syncOnce()

            // All deletes were filtered out, so no DeleteRemote events surface.
            assertTrue(
                reporter.actions.none { it.label == "del-remote" && it.path.startsWith("/Documents") },
                "expected del-remote for /Documents/* to be filtered, got: ${reporter.actions}",
            )
            // Audit log captures the skip with the documented reason.
            assertTrue(Files.exists(logPath), "skipped-ops.jsonl should be written")
            val logBody = Files.readString(logPath)
            assertTrue(
                logBody.contains("unhydrated_folder"),
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
    fun `del-remote for hydrated row under hydrated top-level is propagated`() =
        runTest {
            // A hydrated file row is locally missing — the user deleted it
            // from disk while the remote still has it. The Reconciler emits
            // DeleteRemote (hydrated rows ARE legitimate user-delete signals).
            // The unhydrated-folder filter does not fire because the row is a
            // file. UD-264's top-level guard does not fire because the row
            // itself is a hydrated descendant under its top-level. Result:
            // del-remote flows through.
            //
            // Pre-fix this test paired unhydrated folder rows with one
            // hydrated descendant and asserted del-remote for the unhydrated
            // folders flowed through; that was the data-risk case (Reconciler
            // now drops unhydrated folder deletes regardless of sibling
            // hydration). The replacement uses a hydrated file row, which is
            // the only legitimate del-remote signal in this corner.
            val now = Instant.parse("2026-03-28T12:00:00Z")
            // Seed the DB row with the hash and remoteId the cloudItem helper
            // will report, so the second scan sees remoteState=UNCHANGED for
            // the delta item.
            val filePath = "/Documents/hydrated-file.txt"
            val item = cloudItem(filePath, size = 100)
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = filePath,
                    remoteId = item.id,
                    remoteHash = item.hash,
                    remoteSize = item.size,
                    remoteModified = item.modified,
                    localMtime = now.toEpochMilli(),
                    localSize = item.size,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = now,
                ),
            )
            // No file on disk → LocalScanner reports DELETED. The remote still
            // has the file (provider delta reports it), so remoteState is
            // UNCHANGED and Reconciler emits DeleteRemote rather than the
            // (DELETED, DELETED) RemoveEntry cleanup path.
            Files.createDirectories(syncRoot.resolve("Documents"))
            Files.writeString(syncRoot.resolve("placeholder.txt"), "x")
            provider.deltaItems =
                listOf(
                    cloudItem("/Documents", isFolder = true),
                    item,
                )

            val reporter = RecordingReporter()
            preview {
                engineWithGuards(reporter = reporter).syncOnce(dryRun = true)
            }

            // del-remote for the hydrated row must flow through.
            assertTrue(
                reporter.actions.any { it.label == "del-remote" && it.path == "/Documents/hydrated-file.txt" },
                "expected del-remote for /Documents/hydrated-file.txt, got: ${reporter.actions}",
            )
        }

    @Test
    fun `--ignore-top-level-guard does not override the unhydrated folder filter`() =
        runTest {
            // The opt-out flag covers UD-264's top-level-never-hydrated guard
            // ONLY. Unhydrated-folder DeleteRemote actions are dropped at the
            // Reconciler layer and are not subject to the opt-out — the
            // data-risk is too large to expose behind a CLI knob. The audit
            // log still captures the drops under the `unhydrated_folder`
            // reason.
            seedUnhydratedFolders("/Documents", count = 3)
            Files.writeString(syncRoot.resolve("placeholder.txt"), "x")
            provider.deltaItems = emptyList()

            val logPath = Files.createTempDirectory("unidrive-skipped").resolve("skipped-ops.jsonl")
            val reporter = RecordingReporter()
            engineWithGuards(
                reporter = reporter,
                ignoreTopLevelGuard = true,
                skippedOpsLogPath = logPath,
            ).syncOnce()

            // Opt-out does NOT keep unhydrated-folder deletes in the plan.
            assertTrue(
                reporter.actions.none { it.label == "del-remote" && it.path.startsWith("/Documents") },
                "unhydrated-folder filter should drop the deletes regardless of opt-out, got: ${reporter.actions}",
            )
            // Audit log still records the drops.
            assertTrue(Files.exists(logPath), "skipped-ops.jsonl should be written")
            val logBody = Files.readString(logPath)
            assertTrue(
                logBody.contains("unhydrated_folder"),
                "audit line should use the unhydrated_folder reason, got: $logBody",
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
            preview {
                engineWithGuards(reporter = reporter, maxDeleteAbsolute = 50, maxDeletePercentage = 0)
                    .syncOnce(dryRun = true)
            }

            assertTrue(
                reporter.warnings.any { it.contains("max_delete_absolute") },
                "expected dry-run warning for absolute cap, got: ${reporter.warnings}",
            )
        }

    @Test
    fun `#108 daemon-mode safeguard trip defers deletes instead of wedging the cycle`() =
        runTest {
            // Same plan as the abort test, but a watch-poll (daemon) must NOT
            // throw — otherwise the watch loop retries the failing cycle forever
            // and the daemon "stays failed". It defers the deletes + warns +
            // continues; one-shot MANUAL still throws (covered by the abort test).
            seedDbEntries(60)
            Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
            provider.deltaItems = emptyList()

            val reporter = RecordingReporter()
            engineWithGuards(reporter = reporter, maxDeleteAbsolute = 50, maxDeletePercentage = 0)
                .syncOnce(dryRun = false, reason = SyncReason.WATCH_POLL)

            assertTrue(
                reporter.warnings.any { it.contains("max_delete_absolute") && it.contains("Deferring deletes") },
                "expected a daemon-mode deferral warning, got: ${reporter.warnings}",
            )
            // Deferred, not applied: the 59-delete plan did not reap the seeded rows.
            assertTrue(
                db.getAllEntries().size >= 50,
                "deferred deletes must not reap the seeded rows, got ${db.getAllEntries().size}",
            )
        }

    @Test
    fun `#108 a deferred daemon-mode delete holds the delta cursor so tombstones replay`() =
        runTest {
            // PR #241 review (P1): remote tombstones for locally-present, unchanged rows
            // reconcile to DeleteLocal. Under WATCH_POLL that plan is deferred when it trips
            // the cap — but the delta cursor MUST NOT advance, or the consumed tombstones
            // would never replay and the local copies would be stranded. seedDbEntries pins
            // delta_cursor='seeded-cursor'; the gather would otherwise promote 'cursor-advanced'.
            seedDbEntries(60)
            val seededMtime = Instant.parse("2026-01-01T00:00:00Z")
            for (i in 0 until 60) {
                val f = syncRoot.resolve("seeded-$i.txt")
                Files.writeString(f, "x".repeat(100))
                Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.from(seededMtime))
            }
            provider.deltaItems = (0 until 60).map { cloudItem("/seeded-$it.txt", size = 100, deleted = true) }
            provider.deltaCursor = "cursor-advanced"

            engineWithGuards(maxDeleteAbsolute = 50, maxDeletePercentage = 0)
                .syncOnce(dryRun = false, reason = SyncReason.WATCH_POLL)

            assertEquals(
                "seeded-cursor",
                db.getSyncState("delta_cursor"),
                "deferred deletes must hold the cursor, not promote 'cursor-advanced'",
            )
            assertTrue(
                Files.exists(syncRoot.resolve("seeded-0.txt")),
                "deferred DeleteLocal must not delete the local file",
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

    // #116 — fast-bootstrap adopt-on-name-match for top-level folders.
    //
    // Invariant: under fast-bootstrap the pre-existing cloud tree is invisible to
    // the planner, so the LOCAL scanner emits a CreateRemoteFolder for every
    // top-level folder — including ones whose name already exists on the cloud,
    // each of which would 409. The apply pass must adopt the existing remote folder
    // (capturing its remoteId) and SKIP the mkdir. If this test is removed or
    // loosened, fast-bootstrap silently regresses to N spurious 409s per reset.
    @Test
    fun `fast_bootstrap_adopts_matching_top_level_folders_instead_of_mkdir`() =
        runTest {
            provider.supportsFastBootstrap = true
            provider.deltaCursor = "latest-cursor"

            // Three top-level folders already exist on the cloud (invisible to the
            // bootstrap planner) — mirror the live evidence (/.safe, /Pictures, /dev).
            val remoteSafe = remoteFolder("/.safe", "remote-uuid-safe")
            val remotePictures = remoteFolder("/Pictures", "remote-uuid-pictures")
            val remoteDev = remoteFolder("/dev", "remote-uuid-dev")
            provider.childrenByParent["/"] = listOf(remoteSafe, remotePictures, remoteDev)

            // The same three folders exist locally and would each plan a mkdir-remote.
            Files.createDirectory(syncRoot.resolve(".safe"))
            Files.createDirectory(syncRoot.resolve("Pictures"))
            Files.createDirectory(syncRoot.resolve("dev"))

            bootstrapEngine().syncOnce()

            // (a) ZERO mkdir calls for the matching folders — no 409s.
            assertTrue(
                provider.createdFolders.isEmpty(),
                "fast-bootstrap must NOT issue CreateRemoteFolder for folders that already " +
                    "exist on the remote; got mkdir calls: ${provider.createdFolders}",
            )

            // (b) all three rows quietly adopted with the EXISTING remoteId.
            assertEquals("remote-uuid-safe", db.getEntry("/.safe")?.remoteId)
            assertEquals("remote-uuid-pictures", db.getEntry("/Pictures")?.remoteId)
            assertEquals("remote-uuid-dev", db.getEntry("/dev")?.remoteId)
        }

    // #116 — orthogonal invariant: adopt-on-match must NOT swallow genuinely-new
    // top-level folders. A local folder with no name match on the cloud still gets
    // its CreateRemoteFolder, so first-time uploads under fast-bootstrap still work.
    @Test
    fun `fast_bootstrap_still_creates_non_matching_top_level_folders`() =
        runTest {
            provider.supportsFastBootstrap = true
            provider.deltaCursor = "latest-cursor"

            // One folder matches an existing remote; one is brand new.
            provider.childrenByParent["/"] = listOf(remoteFolder("/Pictures", "remote-uuid-pictures"))
            Files.createDirectory(syncRoot.resolve("Pictures"))
            Files.createDirectory(syncRoot.resolve("BrandNew"))

            bootstrapEngine().syncOnce()

            assertEquals(
                listOf("/BrandNew"),
                provider.createdFolders.toList(),
                "the brand-new folder must still be created; the matching one adopted",
            )
            assertEquals("remote-uuid-pictures", db.getEntry("/Pictures")?.remoteId)
            assertNotNull(db.getEntry("/BrandNew")?.remoteId, "the created folder must get a remoteId")
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
    fun `modified local upload dispatched once across a multi page gather`() =
        runTest {
            // C8: resolveSlice processes the FULL localChanges map on every
            // delta page. A MODIFIED-local file absent from a page's remote
            // delta re-emits Upload(path) on EVERY page. The streaming
            // executor launches a dispatch per channel item with no
            // pre-send dedup, so before the fix the SAME path was applyUpload'd
            // once per page (K pages → K replace-PUTs racing on one remote id).
            // Invariant: the MODIFIED-local path is dispatched/uploaded EXACTLY
            // ONCE across a multi-page gather, regardless of how many pages it
            // is absent from.
            val baseline = Instant.parse("2026-01-01T00:00:00Z")

            // Established baseline: a hydrated, fully-synced row for the file.
            // localSize=10 so a 20-byte local file scans as MODIFIED (size
            // mismatch bypasses the touch-only hash gate). A non-null cursor
            // makes fullEnumerationExpected=false so the streaming path runs
            // (the full-enum-against-baseline shrink gate forces non-streaming).
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/modified.txt",
                    remoteId = "remote-id-modified",
                    remoteHash = "hash-modified",
                    remoteSize = 10,
                    remoteModified = baseline,
                    localMtime = baseline.toEpochMilli(),
                    localSize = 10,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = baseline,
                ),
            )
            db.setSyncState("delta_cursor", "baseline-cursor")

            // Local edit: 20 bytes (≠ tracked 10) → ChangeState.MODIFIED.
            Files.write(syncRoot.resolve("modified.txt"), ByteArray(20) { 0x41 })

            // Two remote delta pages, NEITHER carrying /modified.txt. Each page
            // has one unrelated item so the slice is non-empty (the consumer
            // skips empty slices) and resolveSlice runs — re-deriving the
            // MODIFIED upload from the full localChanges map each time.
            provider.deltaPages =
                listOf(
                    listOf(cloudItem("/other-page1.txt", size = 5)) to "page1-cursor",
                    listOf(cloudItem("/other-page2.txt", size = 5)) to "page2-cursor",
                )

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

            // Sanity: the gather genuinely spanned ≥2 pages.
            assertTrue(
                provider.deltaCalls >= 2,
                "expected a multi-page gather (got deltaCalls=${provider.deltaCalls})",
            )
            // The invariant: exactly one upload of the MODIFIED path. Before the
            // dedup, this was deltaPages.size (one per page the path is absent from).
            val modifiedUploads = provider.uploadedPaths.count { it == "/modified.txt" }
            assertEquals(
                1,
                modifiedUploads,
                "MODIFIED-local /modified.txt must be uploaded exactly once across the " +
                    "multi-page gather; got $modifiedUploads (all uploads: ${provider.uploadedPaths})",
            )
        }

    @Test
    fun `newer remote download on a later page is not dropped by the executor dedup`() =
        runTest {
            // Orthogonal to the upload-dedup invariant: the executor dedup must
            // NOT silently drop a genuinely-newer DownloadContent. If the remote
            // is edited again between page 1 and a later page, the file surfaces
            // twice with DIFFERENT metadata. A path-only claim would keep the
            // stale page-1 version and drop the fresher one (the page-1 download
            // adds the path to executedPaths, so Pass 2 skips the later action),
            // while the cursor advances past the later page — a lost remote
            // update. The claim is therefore content-aware for downloads, so the
            // fresher version still reaches the executor.
            val baseline = Instant.parse("2026-01-01T00:00:00Z")
            val v1Modified = Instant.parse("2026-02-01T00:00:00Z")
            val v2Modified = Instant.parse("2026-03-01T00:00:00Z")

            // Established baseline: a hydrated row whose local file matches the
            // tracked size/mtime, so the local side scans as UNCHANGED and each
            // page's remote metadata change yields a clean DownloadContent (not a
            // delete-vs-modify conflict).
            val localFile = syncRoot.resolve("edited.txt")
            Files.write(localFile, ByteArray(5))
            val localMtime = Files.getLastModifiedTime(localFile).toMillis()
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/edited.txt",
                    remoteId = "remote-id-edited",
                    remoteHash = "hash-v0",
                    remoteSize = 5,
                    remoteModified = baseline,
                    localMtime = localMtime,
                    localSize = 5,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = baseline,
                ),
            )
            db.setSyncState("delta_cursor", "baseline-cursor")
            provider.files["/edited.txt"] = ByteArray(5)

            val v1 =
                CloudItem(
                    id = "remote-id-edited",
                    name = "edited.txt",
                    path = "/edited.txt",
                    size = 5,
                    isFolder = false,
                    modified = v1Modified,
                    created = baseline,
                    hash = "hash-v1",
                    mimeType = "application/octet-stream",
                )
            val v2 = v1.copy(hash = "hash-v2", modified = v2Modified)

            // Same path on NON-adjacent pages with different remote metadata (a
            // second remote edit landed on a later page). The middle page holds
            // an unrelated item so the 1-page rename-coalescing lookahead flushes
            // v1 for reconciliation BEFORE v2 arrives — otherwise the lookahead
            // would merge the two same-id entries into one before resolveSlice
            // ever runs, and the dedup would never see two actions. With them a
            // page apart, both v1 and v2 reach resolveSlice as separate slices;
            // a path-only dedup would drop v2, the content-aware claim lets it
            // through.
            provider.deltaPages =
                listOf(
                    listOf(v1) to "page1-cursor",
                    listOf(cloudItem("/unrelated.txt", size = 3)) to "page2-cursor",
                    listOf(v2) to "page3-cursor",
                )
            provider.files["/unrelated.txt"] = ByteArray(3)

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

            assertTrue(
                provider.deltaCalls >= 2,
                "expected a multi-page gather (got deltaCalls=${provider.deltaCalls})",
            )
            // Both versions reach the provider — the fresher v2 is not dropped.
            // (Pre-fix path-only dedup would yield exactly 1 download here.)
            val edits = provider.downloadByIdCalls.count { it.second == "/edited.txt" } +
                provider.downloadByPathCalls.count { it == "/edited.txt" }
            assertEquals(
                2,
                edits,
                "a newer remote version of /edited.txt on a later page must still be " +
                    "downloaded; got $edits download(s) — the content-aware dedup must not " +
                    "collapse two distinct remote versions of the same path",
            )
        }

    // C8 follow-up (the bot's P1 on the content-aware dedup): the claim deliberately
    // lets two same-path DownloadContents through, but they must NOT run concurrently
    // to the same destination + DB row — a slower earlier-page transfer finishing last
    // would overwrite the fresher bytes/metadata. The executor serializes same-path
    // transfers in page (channel) order, so peak concurrency for the path is 1 and the
    // later/newer version always lands last. If this regresses, the overwrite race
    // returns.
    @Test
    fun `same-path streaming downloads are serialized so the newer version wins`() =
        runTest {
            val baseline = Instant.parse("2026-01-01T00:00:00Z")
            val v1Modified = Instant.parse("2026-02-01T00:00:00Z")
            val v2Modified = Instant.parse("2026-03-01T00:00:00Z")
            val localFile = syncRoot.resolve("edited.txt")
            Files.write(localFile, ByteArray(5))
            val localMtime = Files.getLastModifiedTime(localFile).toMillis()
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/edited.txt",
                    remoteId = "remote-id-edited",
                    remoteHash = "hash-v0",
                    remoteSize = 5,
                    remoteModified = baseline,
                    localMtime = localMtime,
                    localSize = 5,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = baseline,
                ),
            )
            db.setSyncState("delta_cursor", "baseline-cursor")
            provider.files["/edited.txt"] = ByteArray(5)
            val v1 =
                CloudItem(
                    id = "remote-id-edited",
                    name = "edited.txt",
                    path = "/edited.txt",
                    size = 5,
                    isFolder = false,
                    modified = v1Modified,
                    created = baseline,
                    hash = "hash-v1",
                    mimeType = "application/octet-stream",
                )
            val v2 = v1.copy(hash = "hash-v2", modified = v2Modified)
            // Same path a page apart (so both reach resolveSlice as separate slices).
            provider.deltaPages =
                listOf(
                    listOf(v1) to "page1-cursor",
                    listOf(cloudItem("/unrelated.txt", size = 3)) to "page2-cursor",
                    listOf(v2) to "page3-cursor",
                )
            provider.files["/unrelated.txt"] = ByteArray(3)
            // Open an overlap window: without serialization both downloads would be
            // in-flight together and peak concurrency would be 2.
            provider.downloadDelayMs = 50

            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                streamingReconciliation = true,
            ).syncOnce()

            // Both versions still download (content-aware claim) ...
            assertEquals(
                2,
                provider.downloadByIdCalls.count { it.second == "/edited.txt" },
                "both remote versions must download",
            )
            // ... but never at the same time: serialized in page order so the newer wins.
            assertEquals(
                1,
                provider.maxConcurrentDownloadsByPath["/edited.txt"],
                "same-path downloads must be serialized (peak concurrency 1); a race would show 2",
            )
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
    fun `dry-run reports unhydrated folder skips as a warning and writes no skipped-ops file`() =
        runTest {
            seedUnhydratedFolders("/Documents", count = 3)
            Files.writeString(syncRoot.resolve("placeholder.txt"), "x")
            provider.deltaItems = emptyList()
            val logPath = Files.createTempDirectory("unidrive-skipped").resolve("skipped-ops.jsonl")
            val reporter = RecordingReporter()

            preview { engineWithGuards(reporter = reporter, skippedOpsLogPath = logPath).syncOnce(dryRun = true) }

            assertFalse(Files.exists(logPath), "a dry-run records nothing")
            assertTrue(
                reporter.warnings.any { it.contains("unhydrated folder rows would be skipped") },
                "the preview must still tell the user what would be skipped, got: ${reporter.warnings}",
            )
        }

    @Test
    fun `dry-run reports expired trash and versions but deletes nothing`() =
        runTest {
            val trashFile = syncRoot.resolve(".unidrive-trash/20200101T000000Z/old.txt")
            val versionFile = syncRoot.resolve(".unidrive-versions/doc/20200101T000000Z")
            Files.createDirectories(trashFile.parent)
            Files.writeString(trashFile, "expired")
            Files.createDirectories(versionFile.parent)
            Files.writeString(versionFile, "old version")
            provider.deltaItems = emptyList()
            val reporter = RecordingReporter()

            preview {
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = reporter,
                    trashManager = TrashManager(syncRoot),
                    versionManager = VersionManager(syncRoot),
                ).syncOnce(dryRun = true)
            }

            assertTrue(Files.exists(trashFile) && Files.exists(versionFile), "a dry-run must not delete retention-managed files")
            assertTrue(reporter.warnings.any { it.contains("purge 1 expired trash") }, "got: ${reporter.warnings}")
            assertTrue(reporter.warnings.any { it.contains("prune 1 expired file version") }, "got: ${reporter.warnings}")

            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                trashManager = TrashManager(syncRoot),
                versionManager = VersionManager(syncRoot),
            ).syncOnce()
            assertFalse(Files.exists(trashFile), "the real run purges what the preview announced")
            assertFalse(Files.exists(versionFile), "the real run prunes what the preview announced")
        }

    @Test
    fun `dry-run does not create a missing sync_root but still plans the sync`() =
        runTest {
            Files.delete(syncRoot)
            provider.deltaItems = listOf(cloudItem("/a.txt", size = 10))
            provider.files["/a.txt"] = ByteArray(10)
            val reporter = RecordingReporter()

            preview { engineWithReporter(reporter).syncOnce(dryRun = true) }

            assertFalse(Files.exists(syncRoot), "a dry-run must not create sync_root")
            assertTrue(reporter.warnings.any { it.contains("sync_root") && it.contains("would create") }, "got: ${reporter.warnings}")
            assertTrue(reporter.actions.any { it.path == "/a.txt" }, "the plan must still list the download, got: ${reporter.actions}")

            engineWithReporter(ProgressReporter.Silent).syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("a.txt")), "the real run creates sync_root and downloads")
        }

    @Test
    fun `a streaming dry-run plans the same actions as a non-streaming one and transfers nothing`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/docs", isFolder = true), cloudItem("/docs/a.txt", size = 10), cloudItem("/b.txt", size = 10))
            listOf("/docs/a.txt", "/b.txt").forEach { provider.files[it] = ByteArray(10) }

            fun plan(streaming: Boolean): Set<Pair<String, String>> {
                val reporter = RecordingReporter()
                kotlinx.coroutines.runBlocking {
                    preview {
                        SyncEngine(
                            provider = provider,
                            db = db,
                            syncRoot = syncRoot,
                            conflictPolicy = ConflictPolicy.KEEP_BOTH,
                            reporter = reporter,
                            streamingReconciliation = streaming,
                        ).syncOnce(dryRun = true)
                    }
                }
                return reporter.actions.map { it.label to it.path }.toSet()
            }

            val plain = plan(streaming = false)
            val streamed = plan(streaming = true)

            assertTrue(plain.isNotEmpty(), "the scenario must plan something")
            assertEquals(plain, streamed, "streaming must not change the plan a dry-run shows")
            assertTrue(provider.downloadByIdCalls.isEmpty() && provider.downloadByPathCalls.isEmpty(), "no download in a dry-run")
            assertTrue(provider.uploadedPaths.isEmpty(), "no upload in a dry-run")
            assertFalse(Files.exists(syncRoot.resolve("b.txt")), "nothing may be written to the sync tree")
        }

    @Test
    fun `a dry-run on the real database is refused`() =
        runTest {
            provider.deltaItems = emptyList()
            val e = assertFailsWith<IllegalArgumentException> { engine.syncOnce(dryRun = true) }
            assertTrue("disposable" in e.message!!, e.message)
            assertEquals(0, provider.deltaCalls, "it must refuse before touching the provider")
        }

    @Test
    fun `dry-run leaves the real state untouched, so the next dry-run gathers from scratch`() =
        runTest {
            provider.deltaItems = listOf(cloudItem("/test.txt", size = 100))
            provider.deltaCursor = "cursor-first"
            provider.files["/test.txt"] = ByteArray(100)
            db.setSyncState("delta_cursor", "")
            db.setSyncState("pending_cursor", "")

            preview { engineWithReporter(ProgressReporter.Silent).syncOnce(dryRun = true) }

            assertEquals("", db.getSyncState("delta_cursor"), "a dry-run must not promote the cursor")
            assertNull(db.getSyncState("pending_cursor")?.takeIf { it.isNotEmpty() })
            assertNull(db.getEntry("/test.txt"), "a dry-run must not cache remote rows")
            assertFalse(Files.exists(syncRoot.resolve("test.txt")))
            assertEquals(1, provider.deltaCalls)

            provider.deltaCalls = 0
            preview { engineWithReporter(ProgressReporter.Silent).syncOnce(dryRun = true) }

            assertEquals(1, provider.deltaCalls, "the second dry-run gathers again, from scratch")
            assertEquals("", db.getSyncState("delta_cursor"))
            assertNull(db.getEntry("/test.txt"))
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

    // ── #115: transparent XDG locale-alias adoption — executor + round-trip ──

    /**
     * Executor path-split (#115 mandatory): an aliased Upload reads the REAL
     * local file (`/Bilder/x.jpg`) and writes to the CANONICAL remote
     * (`/Pictures/x.jpg`). Asserted at the executor/provider seam:
     *   - provider.upload received the canonical remote path,
     *   - the bytes it received are exactly the local /Bilder file's bytes,
     *   - the persisted row is keyed at the real-local path with the canonical
     *     in remote_path.
     *
     * Uses the static alias table (Bilder ∈ Pictures group) so the test is
     * independent of the host's ~/.config/user-dirs.dirs.
     */
    @Test
    fun `aliased upload reads real local file and writes to canonical remote`() =
        runTest {
            // The user's local sync root holds the German-named alias folder
            // /Bilder with a new file; the cloud holds the canonical /Pictures.
            val bytes = ByteArray(91) { ((it * 7 + 3) and 0xff).toByte() }
            Files.createDirectories(syncRoot.resolve("Bilder"))
            Files.write(syncRoot.resolve("Bilder/x.jpg"), bytes)

            // Cloud already holds the canonical /Pictures folder → adoption fires.
            provider.deltaItems = listOf(cloudItem("/Pictures", isFolder = true))
            engine.syncOnce()

            // (a) The REMOTE upload landed at the canonical path, never the alias.
            assertTrue(
                provider.uploadedPaths.contains("/Pictures/x.jpg"),
                "aliased upload must write to canonical /Pictures/x.jpg; got ${provider.uploadedPaths}",
            )
            assertFalse(
                provider.uploadedPaths.any { it.startsWith("/Bilder") },
                "no upload may reference the alias path /Bilder; got ${provider.uploadedPaths}",
            )

            // (b) The bytes uploaded are exactly the LOCAL /Bilder file's bytes
            //     (the executor read the real local path, not the canonical one).
            assertContentEquals(
                bytes, provider.files["/Pictures/x.jpg"],
                "executor must read the real local /Bilder/x.jpg bytes and upload them",
            )

            // (c) The persisted row is keyed real-local with the canonical in remote_path.
            val row = db.getEntry("/Bilder/x.jpg")
            assertNotNull(row, "row must be keyed at the real-local path /Bilder/x.jpg")
            assertEquals("/Pictures/x.jpg", row.remotePath, "row.remotePath must be the canonical")
            assertNotNull(row.remoteId, "row must carry the provider remote_id after upload")
            // No phantom canonical-keyed row.
            assertNull(
                db.getEntry("/Pictures/x.jpg"),
                "must NOT create a phantom row keyed at the canonical /Pictures/x.jpg",
            )
        }

    /**
     * Round-trip (#115 mandatory) — defeats BOTH single-path failure modes.
     *
     * After an aliased upload persists a row (path=/Bilder/x.jpg,
     * remotePath=/Pictures/x.jpg, real remote_id), a SUBSEQUENT reconcile where
     *   (a) LocalScanner re-scans and sees /Bilder/x.jpg unchanged on disk, and
     *   (b) the remote delta reports /Pictures/x.jpg unchanged
     * must produce an EMPTY plan: NO re-Upload, NO DeleteRemote, NO
     * DownloadContent, NO MoveLocal. This is the invariant the two single-path
     * keyings broke (re-upload churn / spurious DeleteRemote on failure mode 1;
     * spurious NEW + MoveLocal on failure mode 2).
     */
    @Test
    fun `round trip after aliased upload plans nothing`() =
        runTest {
            // First pass: the alias folder /Bilder exists locally with a new file;
            // the cloud holds the canonical /Pictures → adopt + upload to canonical.
            val bytes = ByteArray(64) { (it and 0xff).toByte() }
            Files.createDirectories(syncRoot.resolve("Bilder"))
            Files.write(syncRoot.resolve("Bilder/x.jpg"), bytes)
            provider.deltaItems = listOf(cloudItem("/Pictures", isFolder = true))
            engine.syncOnce()

            // Confirm the row is the aliased shape the round-trip protects.
            val row = db.getEntry("/Bilder/x.jpg")
            assertNotNull(row)
            assertEquals("/Pictures/x.jpg", row.remotePath)
            val uploadsBefore = provider.uploadedPaths.size
            val deletesBefore = provider.deletedPaths.size
            val downByIdBefore = provider.downloadByIdCalls.size
            val downByPathBefore = provider.downloadByPathCalls.size
            val movesBefore = provider.movedPaths.size

            // ── Subsequent reconcile ──────────────────────────────────────────
            // (a) /Bilder/x.jpg is unchanged on disk (LocalScanner sees no delta).
            // (b) the remote delta reports /Pictures/x.jpg unchanged: same id/hash
            //     as the persisted row, plus the canonical folder.
            val capturing = CapturingActionCountReporter()
            val engine2 =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = capturing,
                )
            provider.deltaItems =
                listOf(
                    cloudItem("/Pictures", isFolder = true),
                    // unchanged file: same remote_id + hash + modified as the
                    // persisted row, so remoteState resolves to UNCHANGED.
                    cloudItem("/Pictures/x.jpg", size = bytes.size.toLong())
                        .copy(id = row.remoteId!!, hash = row.remoteHash, modified = row.remoteModified),
                )
            engine2.syncOnce()

            // The plan must be EMPTY (reconciler verdict count == 0).
            assertEquals(
                0, capturing.lastPreFilterTotal,
                "round-trip reconcile must plan ZERO actions; the aliased row must be " +
                    "recognised as unchanged on both sides",
            )

            // And no provider side-effect of any reconcile-driven action class.
            assertEquals(uploadsBefore, provider.uploadedPaths.size, "NO re-Upload")
            assertEquals(deletesBefore, provider.deletedPaths.size, "NO DeleteRemote")
            assertEquals(downByIdBefore, provider.downloadByIdCalls.size, "NO id-based DownloadContent")
            assertEquals(downByPathBefore, provider.downloadByPathCalls.size, "NO path-based DownloadContent")
            assertEquals(movesBefore, provider.movedPaths.size, "NO MoveLocal/MoveRemote")

            // The local file must still live in the German-named folder (no MoveLocal
            // physically relocated it to /Pictures).
            assertTrue(
                Files.exists(syncRoot.resolve("Bilder/x.jpg")),
                "the file must remain in the locale-named /Bilder folder",
            )
            assertFalse(
                Files.exists(syncRoot.resolve("Pictures/x.jpg")),
                "no spurious MoveLocal may relocate the file to a /Pictures folder",
            )
        }

    // #123 — bulk create-folder: bounded concurrency + partial-failure tolerance.

    /** Captures onSyncComplete.failed and every onWarning message. */
    private class FailureCapturingReporter : ProgressReporter by ProgressReporter.Silent {
        var failed: Int = -1
            private set
        val warnings = java.util.Collections.synchronizedList(mutableListOf<String>())

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {
            this.failed = failed
        }

        override fun onWarning(message: String) {
            warnings.add(message)
        }
    }

    private fun engineWithReporterAndProvider(reporter: ProgressReporter) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = reporter,
        )

    @Test
    fun `one create-folder failure does not abort the rest of the plan`() =
        runTest {
            // Local-only nested tree → a run of CreateRemoteFolder actions.
            Files.createDirectories(syncRoot.resolve("proj/a/deep"))
            Files.createDirectories(syncRoot.resolve("proj/b"))
            Files.createDirectories(syncRoot.resolve("proj/c"))
            provider.deltaItems = emptyList()
            // Fail the create of /proj/a: its sibling subtree (/proj/b, /proj/c)
            // must still be created, and /proj itself must still be created.
            provider.createFolderFailPaths.add("/proj/a")

            val reporter = FailureCapturingReporter()
            engineWithReporterAndProvider(reporter).syncOnce(dryRun = false)

            assertTrue(provider.createdFolders.contains("/proj"), "root /proj still created")
            assertTrue(provider.createdFolders.contains("/proj/b"), "sibling /proj/b still created")
            assertTrue(provider.createdFolders.contains("/proj/c"), "sibling /proj/c still created")
            assertFalse(provider.createdFolders.contains("/proj/a"), "/proj/a create was forced to fail")
            // /proj/a/deep must be skipped: its parent's create failed.
            assertFalse(
                provider.createdFolders.contains("/proj/a/deep"),
                "child of a failed-parent create must be skipped, not attempted",
            )
            // The failure count is surfaced: /proj/a (failed) + /proj/a/deep (skipped) = 2.
            assertEquals(2, reporter.failed, "two folders failed/skipped; got ${reporter.failed}")
            assertTrue(
                reporter.warnings.any { it.contains("/proj/a") },
                "a warning naming the failed folder must be surfaced; got ${reporter.warnings}",
            )
        }

    @Test
    fun `KEEP_BOTH both-modified — remote takes canonical, local edit kept as conflict-local side copy`() =
        runTest {
            val now = Instant.parse("2026-01-01T00:00:00Z")
            // A previously-synced file.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/c.txt",
                    remoteId = "id-c",
                    remoteHash = "base",
                    remoteSize = 4,
                    remoteModified = now,
                    localMtime = now.toEpochMilli(),
                    localSize = 4,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = now,
                ),
            )
            db.setSyncState("delta_cursor", "existing-cursor")
            // Local edited (content + mtime differ from the snapshot).
            Files.writeString(syncRoot.resolve("c.txt"), "MINE")
            Files.setLastModifiedTime(
                syncRoot.resolve("c.txt"),
                java.nio.file.attribute.FileTime.fromMillis(now.toEpochMilli() + 60_000),
            )
            // Remote edited too (different size → remote MODIFIED) → MODIFIED/MODIFIED conflict.
            provider.files["/c.txt"] = "THEIRS".toByteArray()
            provider.deltaItems = listOf(cloudItem("/c.txt", size = 6))

            engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false)

            // Canonical path holds the REMOTE version (it is the tracked entity).
            assertEquals("THEIRS", Files.readString(syncRoot.resolve("c.txt")), "canonical must hold the remote version")
            // The user's OWN edit is preserved under a conflict-local side copy.
            val sideCopies =
                Files.list(syncRoot).use { stream ->
                    stream.filter { it.fileName.toString().contains(".conflict-local-") }.toList()
                }
            assertEquals(1, sideCopies.size, "exactly one conflict-local side copy must exist; got $sideCopies")
            assertEquals("MINE", Files.readString(sideCopies.single()), "the side copy must hold the user's own edit")
            // The side copy is uploaded in this same pass and tracked at its own path, so a later
            // delete of the canonical by another actor cannot reap the user's edit.
            assertNotNull(
                db.getEntry("/" + sideCopies.single().fileName.toString()),
                "the uploaded side copy must be tracked at its own path",
            )
        }

    private fun seedBothModifiedConflict() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = "/c.txt",
                remoteId = "id-c",
                remoteHash = "base",
                remoteSize = 4,
                remoteModified = now,
                localMtime = now.toEpochMilli(),
                localSize = 4,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = now,
            ),
        )
        db.setSyncState("delta_cursor", "existing-cursor")
        Files.writeString(syncRoot.resolve("c.txt"), "MINE")
        Files.setLastModifiedTime(
            syncRoot.resolve("c.txt"),
            java.nio.file.attribute.FileTime.fromMillis(now.toEpochMilli() + 60_000),
        )
        provider.files["/c.txt"] = "THEIRS".toByteArray()
        provider.deltaItems = listOf(cloudItem("/c.txt", size = 6))
    }

    @Test
    fun `keep_both_conflict_copy_is_uploaded_in_the_same_pass`() =
        runTest {
            seedBothModifiedConflict()

            engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false)

            val copy = provider.uploadedPaths.singleOrNull { it.contains(".conflict-local-") }
            assertNotNull(copy, "the side copy must be uploaded in this pass; uploaded=${provider.uploadedPaths}")
            assertEquals("MINE", String(provider.files[copy]!!), "the uploaded copy holds the user's own edit")
            assertNotNull(db.getEntry(copy), "the uploaded copy is recorded so the next scan does not re-upload it")
            assertEquals("THEIRS", Files.readString(syncRoot.resolve("c.txt")), "the canonical path still holds the remote version")
        }

    @Test
    fun `keep_both_conflict_copy_stays_local_when_its_upload_fails`() =
        runTest {
            seedBothModifiedConflict()
            provider.uploadFailCount = 1

            engineWithDirection(SyncDirection.BIDIRECTIONAL).syncOnce(dryRun = false)

            assertEquals("THEIRS", Files.readString(syncRoot.resolve("c.txt")), "the conflict is still resolved")
            val copies = Files.list(syncRoot).use { st -> st.filter { it.fileName.toString().contains(".conflict-local-") }.toList() }
            assertEquals("MINE", Files.readString(copies.single()), "the user's edit survives a failed copy upload")
        }

    @Test
    fun `keep_both_conflict_copy_is_not_uploaded_by_a_download_only_sync`() =
        runTest {
            seedBothModifiedConflict()

            engineWithDirection(SyncDirection.DOWNLOAD).syncOnce(dryRun = false)

            assertTrue(provider.uploadedPaths.none { it.contains(".conflict-local-") }, "uploaded=${provider.uploadedPaths}")
        }

    @Test
    fun `keep_both_conflict_copy_under_a_locale_alias_is_uploaded_next_to_the_canonical_remote_file`() =
        runTest {
            // Local /Bilder is the locale alias of the remote /Pictures (#115): rows are keyed at the
            // real-local path and carry the canonical remote path.
            val now = Instant.parse("2026-03-28T12:00:00Z")
            fun row(
                path: String,
                remotePath: String,
                isFolder: Boolean,
            ) = org.krost.unidrive.sync.model.SyncEntry(
                path = path,
                remotePath = remotePath,
                remoteId = "id-$remotePath",
                remoteHash = if (isFolder) null else "base",
                remoteSize = if (isFolder) 0 else 4,
                remoteModified = now,
                localMtime = now.toEpochMilli(),
                localSize = if (isFolder) null else 4,
                isFolder = isFolder,
                isPinned = false,
                isHydrated = true,
                lastSynced = now,
            )
            db.upsertEntry(row("/Bilder", "/Pictures", isFolder = true))
            db.upsertEntry(row("/Bilder/c.txt", "/Pictures/c.txt", isFolder = false))
            db.setSyncState("delta_cursor", "existing-cursor")
            Files.createDirectories(syncRoot.resolve("Bilder"))
            val local = syncRoot.resolve("Bilder/c.txt")
            Files.writeString(local, "MINE")
            Files.setLastModifiedTime(local, java.nio.file.attribute.FileTime.fromMillis(now.toEpochMilli() + 60_000))
            provider.files["/Pictures/c.txt"] = "THEIRS".toByteArray()
            provider.deltaItems =
                listOf(
                    cloudItem("/Pictures", size = 0, isFolder = true).copy(hash = null),
                    cloudItem("/Pictures/c.txt", size = 6),
                )

            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                syncDirection = SyncDirection.BIDIRECTIONAL,
                xdgUserDirsOverridesForTest = mapOf("XDG_PICTURES_DIR" to "Bilder"),
            ).syncOnce(dryRun = false)

            val copies = provider.uploadedPaths.filter { it.contains(".conflict-local-") }
            assertEquals(1, copies.size, "the side copy must be uploaded once; uploaded=${provider.uploadedPaths}")
            assertTrue(
                copies.single().startsWith("/Pictures/c.conflict-local-"),
                "the copy goes next to the canonical remote file, not under the local alias; uploaded=${provider.uploadedPaths}",
            )
            assertEquals("MINE", String(provider.files[copies.single()]!!), "the uploaded copy holds the user's own edit")
        }

    @Test
    fun `parent folders are created before their children under bounded concurrency`() =
        runTest {
            // A multi-level tree with several same-depth siblings exercises the
            // concurrent batches; the depth-barrier must keep parent→child order.
            Files.createDirectories(syncRoot.resolve("root/x1/y1"))
            Files.createDirectories(syncRoot.resolve("root/x1/y2"))
            Files.createDirectories(syncRoot.resolve("root/x2/y3"))
            Files.createDirectories(syncRoot.resolve("root/x3"))
            provider.deltaItems = emptyList()

            engineWithReporterAndProvider(ProgressReporter.Silent).syncOnce(dryRun = false)

            val order = provider.createdFolders.toList()
            // Every created folder's parent (if also created in this run) must
            // appear earlier in the completion-ordered list.
            for (path in order) {
                val parent = path.substringBeforeLast("/", "")
                if (parent.isNotEmpty() && order.contains(parent)) {
                    assertTrue(
                        order.indexOf(parent) < order.indexOf(path),
                        "parent $parent must be created before child $path; order=$order",
                    )
                }
            }
            // Sanity: the whole tree got created.
            for (p in listOf("/root", "/root/x1", "/root/x2", "/root/x3", "/root/x1/y1", "/root/x1/y2", "/root/x2/y3")) {
                assertTrue(order.contains(p), "expected $p to be created; order=$order")
            }
        }

    /** Captures the reconciler's pre-filter action count from onActionCount. */
    private class CapturingActionCountReporter : ProgressReporter by ProgressReporter.Silent {
        var lastPreFilterTotal: Int = -1
            private set

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {
            lastPreFilterTotal = preFilterTotal
        }
    }

    // -- Fake provider for testing --

    // #116: a pre-existing remote folder with an explicit remoteId, so an adopt
    // test can assert the captured id is the cloud's, not a freshly-minted one.
    private fun remoteFolder(
        path: String,
        remoteId: String,
    ) = CloudItem(
        id = remoteId,
        name = path.substringAfterLast("/"),
        path = path,
        size = 0,
        isFolder = true,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = null,
        mimeType = null,
    )

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

    // UD-256 — persist --sync-path scope across runs; refuse bare bidirectional
    // when prior scoped runs left an effective_scope behind.

    private fun engineForScope(
        reporter: ProgressReporter = ProgressReporter.Silent,
        syncPath: String? = null,
        syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
        allowFullTreeReconciliation: Boolean = false,
        syncPaths: List<String> = listOfNotNull(syncPath),
        standingScope: List<String> = emptyList(),
        viewInvalidationSink: (changedPaths: Set<String>, full: Boolean, moved: List<RemoteGather.RemoteMerge.Move>) -> Unit = { _, _, _ -> },
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = reporter,
        syncPaths = syncPaths,
        standingScope = standingScope,
        syncDirection = syncDirection,
        allowFullTreeReconciliation = allowFullTreeReconciliation,
        viewInvalidationSink = viewInvalidationSink,
    )

    @Test
    fun `sync-path scope does not match sibling paths sharing its prefix`() =
        runTest {
            provider.files["/_INBOX/a.txt"] = ByteArray(8)
            provider.files["/_INBOXX/b.txt"] = ByteArray(8)
            provider.files["/_INBOX-old/c.txt"] = ByteArray(8)
            provider.deltaItems =
                listOf(
                    cloudItem("/_INBOX", isFolder = true),
                    cloudItem("/_INBOX/a.txt", size = 8),
                    cloudItem("/_INBOXX", isFolder = true),
                    cloudItem("/_INBOXX/b.txt", size = 8),
                    cloudItem("/_INBOX-old", isFolder = true),
                    cloudItem("/_INBOX-old/c.txt", size = 8),
                )
            Files.createDirectories(syncRoot.resolve("_INBOXX"))
            Files.write(syncRoot.resolve("_INBOXX/local.txt"), ByteArray(8))

            engineForScope(syncPath = "/_INBOX", standingScope = listOf("/_INBOX")).syncOnce()

            assertTrue(Files.exists(syncRoot.resolve("_INBOX/a.txt")))
            assertFalse(Files.exists(syncRoot.resolve("_INBOXX/b.txt")))
            assertFalse(Files.exists(syncRoot.resolve("_INBOX-old")))
            assertNotNull(db.getEntry("/_INBOX/a.txt"))
            assertNull(db.getEntry("/_INBOXX/b.txt"))
            assertNull(db.getEntry("/_INBOX-old/c.txt"))
            assertFalse(provider.uploadedPaths.contains("/_INBOXX/local.txt"))
        }

    @Test
    fun `several sync-path roots all materialize and persist while siblings stay out`() =
        runTest {
            provider.files["/_INBOX/a.txt"] = ByteArray(8)
            provider.files["/gernot_ssh/id"] = ByteArray(8)
            provider.files["/other/c.txt"] = ByteArray(8)
            provider.deltaItems =
                listOf(
                    cloudItem("/_INBOX", isFolder = true),
                    cloudItem("/_INBOX/a.txt", size = 8),
                    cloudItem("/gernot_ssh", isFolder = true),
                    cloudItem("/gernot_ssh/id", size = 8),
                    cloudItem("/other", isFolder = true),
                    cloudItem("/other/c.txt", size = 8),
                )

            engineForScope(syncPaths = listOf("/_INBOX", "/gernot_ssh")).syncOnce()

            assertTrue(Files.exists(syncRoot.resolve("_INBOX/a.txt")))
            assertTrue(Files.exists(syncRoot.resolve("gernot_ssh/id")))
            assertFalse(Files.exists(syncRoot.resolve("other")))
            assertEquals(
                setOf("/_INBOX", "/gernot_ssh"),
                db.getSyncState("effective_scope")!!.split("\t").toSet(),
            )
        }

    private fun stageTwoTrees(
        aFiles: Int,
        bFiles: Int,
    ) {
        val items = mutableListOf(cloudItem("/a", isFolder = true), cloudItem("/b", isFolder = true))
        for (i in 0 until aFiles) {
            provider.files["/a/f$i.txt"] = "a$i".toByteArray()
            items += cloudItem("/a/f$i.txt", size = "a$i".length.toLong())
        }
        for (i in 0 until bFiles) {
            provider.files["/b/f$i.txt"] = "b$i".toByteArray()
            items += cloudItem("/b/f$i.txt", size = "b$i".length.toLong())
        }
        provider.deltaItems = items
    }

    @Test
    fun `narrowing the standing scope stops tracking outside rows and deletes nothing`() =
        runTest {
            stageTwoTrees(aFiles = 5, bFiles = 30)
            engineForScope().syncOnce()
            assertNotNull(db.getEntry("/b/f0.txt"))

            engineForScope(syncPaths = listOf("/a"), standingScope = listOf("/a")).syncOnce()

            assertNull(db.getEntry("/b/f0.txt"))
            assertNull(db.getEntry("/b/f29.txt"))
            assertNotNull(db.getEntry("/a/f0.txt"))
            assertEquals("/a", db.getSyncState("tracked_scope"))
            assertTrue(provider.deletedPaths.isEmpty(), "narrowing must not delete on the remote: ${provider.deletedPaths}")
            for (i in 0 until 30) {
                assertTrue(Files.exists(syncRoot.resolve("b/f$i.txt")), "b/f$i.txt must stay on disk")
            }
        }

    @Test
    fun `widening the standing scope re-enumerates and materializes the new subtree`() =
        runTest {
            stageTwoTrees(aFiles = 2, bFiles = 3)
            engineForScope(syncPaths = listOf("/a"), standingScope = listOf("/a")).syncOnce()
            assertFalse(Files.exists(syncRoot.resolve("b")))

            engineForScope(syncPaths = listOf("/a"), standingScope = listOf("/a")).syncOnce()
            assertEquals("false", db.getSyncState("last_gather_full"), "an unchanged scope must stay incremental")

            engineForScope(syncPaths = listOf("/a", "/b"), standingScope = listOf("/a", "/b")).syncOnce()

            assertEquals("true", db.getSyncState("last_gather_full"), "widening must force a full enumeration")
            assertTrue(Files.exists(syncRoot.resolve("b/f2.txt")))
            assertNotNull(db.getEntry("/b/f2.txt"))
            assertEquals("/a\t/b", db.getSyncState("tracked_scope"))
        }

    @Test
    fun `re-widening after narrowing keeps the files that stayed on disk`() =
        runTest {
            stageTwoTrees(aFiles = 2, bFiles = 4)
            engineForScope().syncOnce()
            engineForScope(syncPaths = listOf("/a"), standingScope = listOf("/a")).syncOnce()

            engineForScope(allowFullTreeReconciliation = true).syncOnce()

            for (i in 0 until 4) {
                assertEquals("b$i", Files.readString(syncRoot.resolve("b/f$i.txt")))
            }
            assertTrue(provider.deletedPaths.isEmpty(), "re-widening must not delete: ${provider.deletedPaths}")
            assertTrue(provider.uploadedPaths.isEmpty(), "re-widening must not re-upload: ${provider.uploadedPaths}")
            assertNotNull(db.getEntry("/b/f3.txt"))
            assertEquals("", db.getSyncState("tracked_scope"))
        }

    @Test
    fun `a per-run sync-path without a standing scope leaves tracking alone`() =
        runTest {
            stageTwoTrees(aFiles = 2, bFiles = 2)
            engineForScope().syncOnce()

            engineForScope(syncPath = "/a").syncOnce()

            assertNotNull(db.getEntry("/b/f0.txt"), "a one-off --sync-path must not untrack the rest of the drive")
            assertNull(db.getSyncState("tracked_scope")?.takeIf { it.isNotEmpty() })
        }

    @Test
    fun `dry-run reports a scope change without applying it`() =
        runTest {
            stageTwoTrees(aFiles = 2, bFiles = 2)
            engineForScope().syncOnce()

            preview {
                engineForScope(syncPaths = listOf("/a"), standingScope = listOf("/a")).syncOnce(dryRun = true)
            }

            assertNotNull(db.getEntry("/b/f0.txt"))
            assertNull(db.getSyncState("tracked_scope")?.takeIf { it.isNotEmpty() })
        }

    @Test
    fun `enumerate tracks only the standing scope`() =
        runTest {
            stageTwoTrees(aFiles = 2, bFiles = 2)

            val result = engineForScope(syncPaths = listOf("/a"), standingScope = listOf("/a")).enumerateRemoteIntoState(reset = false)

            assertTrue(result.ok)
            assertNotNull(db.getEntry("/a/f0.txt"))
            assertNull(db.getEntry("/b/f0.txt"))
            assertNull(db.getEntry("/b"))
        }

    // A scope transition untracks rows (narrowing) or re-enumerates from scratch
    // (widening) — neither is expressible as a per-path delta, so the mount must
    // be told the WHOLE view is invalidated, not just changed paths.
    @Test
    fun `a scope transition invalidates the whole view`() =
        runTest {
            stageTwoTrees(aFiles = 2, bFiles = 2)
            engineForScope().syncOnce()

            val invalidations = mutableListOf<Pair<Set<String>, Boolean>>()
            val result =
                engineForScope(
                    syncPaths = listOf("/a"),
                    standingScope = listOf("/a"),
                    viewInvalidationSink = { paths, full, _moved -> invalidations += paths to full },
                ).enumerateRemoteIntoState(reset = false)

            assertTrue(result.ok)
            assertTrue(
                invalidations.any { it.second },
                "narrowing untracked rows; the mount must receive a full-view invalidation, got $invalidations",
            )
        }

    @Test
    fun `a plain enumerate does not invalidate the whole view`() =
        runTest {
            stageTwoTrees(aFiles = 2, bFiles = 2)
            engineForScope().syncOnce()

            val invalidations = mutableListOf<Pair<Set<String>, Boolean>>()
            val result =
                engineForScope(
                    viewInvalidationSink = { paths, full, _moved -> invalidations += paths to full },
                ).enumerateRemoteIntoState(reset = false)

            assertTrue(result.ok)
            assertTrue(
                invalidations.none { it.second },
                "no scope transition happened, so no full-view invalidation may fire, got $invalidations",
            )
        }

    @Test
    fun `ancestor folders of a nested scope root stay tracked`() =
        runTest {
            provider.files["/a/b/x.txt"] = ByteArray(4)
            provider.files["/a/other.txt"] = ByteArray(4)
            provider.deltaItems =
                listOf(
                    cloudItem("/a", isFolder = true),
                    cloudItem("/a/b", isFolder = true),
                    cloudItem("/a/b/x.txt", size = 4),
                    cloudItem("/a/other.txt", size = 4),
                )

            engineForScope(syncPaths = listOf("/a/b"), standingScope = listOf("/a/b")).syncOnce()

            assertNotNull(db.getEntry("/a"), "the folder leading to the scope root stays tracked")
            assertNotNull(db.getEntry("/a/b/x.txt"))
            assertNull(db.getEntry("/a/other.txt"))
            assertFalse(Files.exists(syncRoot.resolve("a/other.txt")))
        }

    @Test
    fun `a standing scope is handed to the provider so it can enumerate just those roots`() =
        runTest {
            provider.deltaItems = emptyList()

            engineForScope(syncPaths = listOf("/_INBOX"), standingScope = listOf("/_INBOX")).syncOnce()

            assertEquals(listOf("/_INBOX"), provider.lastScanContext?.scopeRoots)
        }

    @Test
    fun `a per-run sync-path without a standing scope keeps the whole-drive enumeration`() =
        runTest {
            provider.deltaItems = emptyList()

            engineForScope(syncPath = "/_INBOX").syncOnce()

            assertEquals(emptyList(), provider.lastScanContext?.scopeRoots)
        }

    @Test
    fun `a per-run sync-path outside the standing scope is enumerated too`() =
        runTest {
            provider.deltaItems = emptyList()

            engineForScope(syncPaths = listOf("/b"), standingScope = listOf("/a")).syncOnce()

            assertEquals(listOf("/a", "/b"), provider.lastScanContext?.scopeRoots)
        }

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
    fun `UD-256 a refresh of a profile with persisted scope continues within it instead of refusing`() =
        runTest {
            // #532: refresh has no --sync-path; a profile scoped by earlier runs was permanently
            // unusable — every refresh died on the guard with error provider_error. A refresh
            // (skipTransfers) plans the persisted boundary: out-of-scope paths stay invisible to
            // the reconciler exactly as under --sync-path, so the run proceeds. The persisted
            // scope itself must be untouched.
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            val before = db.getSyncState("effective_scope")

            val reporter = RecordingReporter()
            val refreshEngine = engineForScope(reporter = reporter)
            refreshEngine.syncOnce(skipTransfers = true)

            assertEquals(before, db.getSyncState("effective_scope"), "a refresh must not widen or clear the persisted scope")
        }

    @Test
    fun `UD-256 bare bidirectional dry-run on scoped profile warns instead of throwing`() =
        runTest {
            provider.deltaItems = emptyList()
            engineForScope(syncPath = "/Documents").syncOnce()
            val reporter = RecordingReporter()
            preview {
                engineForScope(reporter = reporter).syncOnce(dryRun = true)
            }
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
            preview {
                engineForScope(allowFullTreeReconciliation = true).syncOnce(dryRun = true)
            }
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
            preview {
                engineForScope(syncPath = "/Photos").syncOnce(dryRun = true)
            }
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
            preview {
                engineForScope(syncPath = "/Documents").syncOnce(dryRun = true)
            }
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
    fun `permanent download failure quarantines row and stops retry on next pass`() =
        runTest {
            // Live evidence motivating the quarantine flag: a single zero-byte
            // file (`/Annika.txt`, bucket entry 69ee2e863da99643eebb3b8a) was
            // retried 1,248 times over 8 hours after the user deleted it from
            // Internxt. The 404 body is the stable `{"error":"Bucket entry …
            // not found"}` shape; treating it as transient meant the UD-225
            // recovery loop re-emitted a DownloadContent on every pass.
            //
            // Smoke: fake provider returns the permanent-failure signal on
            // first download. Assert the engine records the failure exactly
            // once on attempt 1 and emits ZERO Download actions on attempt 2
            // for the same row.
            provider.deltaItems = listOf(cloudItem("/dead.txt", size = 100))
            provider.permanentDownloadFailurePaths.add("/dead.txt")

            // First pass: the engine tries to download, the provider throws,
            // the engine quarantines the row.
            val reporter1 = RecordingReporter()
            engineWithGuards(reporter = reporter1).syncOnce()

            assertEquals(
                1,
                reporter1.actions.count { it.label == "down" && it.path == "/dead.txt" },
                "first pass must attempt exactly one download, got: ${reporter1.actions}",
            )
            val quarantined = db.getEntry("/dead.txt")
            assertNotNull(quarantined, "row should still exist (quarantined, not removed)")
            assertTrue(quarantined.downloadQuarantined, "row must be quarantined after permanent failure")
            assertNotNull(quarantined.lastErrorAt, "lastErrorAt must be stamped")

            // Second pass with no new delta — the recovery loop must NOT
            // resurrect the quarantined row. The cursor has not advanced (the
            // failure does not promote it), so deltaFromLatest may be called
            // again on supportsFastBootstrap providers; counting reporter
            // actions for the quarantined path is the load-bearing assertion.
            val reporter2 = RecordingReporter()
            engineWithGuards(reporter = reporter2).syncOnce()

            assertEquals(
                0,
                reporter2.actions.count { it.label == "down" && it.path == "/dead.txt" },
                "second pass must NOT re-emit Download for the quarantined row, got: ${reporter2.actions}",
            )
        }

    @Test
    fun `#230 an invalid-named remote folder is quarantined, not a mkdir-every-pass failure`() =
        runTest {
            // PR #242 review: applyDownload guards file downloads; folders + adopt placeholders
            // reach createFolder/createPlaceholder via applyCreatePlaceholder. A Windows-invalid
            // folder name must be quarantined here too, not fail the mkdir every poll cycle.
            // localNameIssue's cross-platform rules are unit-tested in PlaceholderManagerTest;
            // this gates on Windows, where such names are actually unrepresentable.
            org.junit.Assume.assumeTrue(
                "folder-name representability is a Windows-FS constraint",
                System.getProperty("os.name", "").lowercase().contains("win"),
            )
            provider.deltaItems = listOf(cloudItem("/CON", isFolder = true))

            // Must NOT throw (pre-fix: createFolder('/CON') threw a reserved-name error every pass).
            engine.syncOnce(dryRun = false)

            val entry = db.getEntry("/CON")
            assertNotNull(entry, "the invalid-named folder row should be tracked")
            assertTrue(
                entry.downloadQuarantined,
                "invalid folder name must be quarantined (recovery loop skips it thereafter)",
            )
            assertFalse(
                Files.exists(syncRoot.resolve("CON")),
                "an unrepresentable folder name must not be created locally",
            )
        }

    @Test
    fun `#600 a 280-character cloud name is quarantined once while normal names sync beside it`() =
        runTest {
            // A cloud name longer than any filesystem's component limit used to keep the row
            // alive in state while the client hid the entry on every mount (windows#196) —
            // no quarantine, no refusal, no state to explain it. The length rule in
            // localNameIssue (#600) makes it unrepresentable on every platform (280 ASCII
            // characters are 280 UTF-16 units and 280 UTF-8 bytes), so the download's
            // unresolvable-name path (#230/#526) quarantines the row. A normal neighbour
            // must be created beside it, and no pass may abort.
            val longPath = "/" + "l".repeat(280) + ".txt"
            provider.deltaItems = listOf(cloudItem("/normal.txt", size = 5), cloudItem(longPath, size = 100))

            val reporter1 = RecordingReporter()
            engineWithGuards(reporter = reporter1).syncOnce()

            assertTrue(Files.exists(syncRoot.resolve("normal.txt")), "the normal neighbour is created")
            val longRow = db.getEntry(longPath)
            assertNotNull(longRow, "the long row exists (quarantined, not removed)")
            assertTrue(longRow.downloadQuarantined, "the long name must be quarantined")
            assertNotNull(longRow.lastErrorAt, "the failure is stamped on the row")
            assertEquals(
                1,
                reporter1.actions.count { it.label == "down" && it.path == longPath },
                "exactly one download attempt for the long name",
            )

            val reporter2 = RecordingReporter()
            engineWithGuards(reporter = reporter2).syncOnce()

            assertEquals(
                0,
                reporter2.actions.count { it.label == "down" && it.path == longPath },
                "the quarantined long row is not retried on the next pass",
            )
        }

    @Test
    fun `fresh delta event clears download quarantine`() =
        runTest {
            // Establish quarantine.
            provider.deltaItems = listOf(cloudItem("/dead.txt", size = 100))
            provider.permanentDownloadFailurePaths.add("/dead.txt")
            engineWithGuards().syncOnce()
            assertTrue(db.getEntry("/dead.txt")!!.downloadQuarantined)

            // Cloud reports the row alive again (fresh delta event) — clear the
            // flag so the next reconcile re-emits the download.
            provider.permanentDownloadFailurePaths.clear()
            provider.deltaItems = listOf(cloudItem("/dead.txt", size = 100))
            provider.deltaCursor = "cursor-revived"
            engineWithGuards().syncOnce()

            assertFalse(
                db.getEntry("/dead.txt")!!.downloadQuarantined,
                "fresh delta event should clear the quarantine flag",
            )
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

    // Cross-account cache-collision guard: two profiles of the SAME provider
    // type (same providerId) but DIFFERENT accounts (different cacheKey =
    // profile.name) must resolve the same remote path to DISTINCT cache files.
    // Before cacheKey, both keyed on providerId (the shared type), so two
    // `onedrive` accounts collided on `hydration/onedrive/<path>` and could
    // clobber each other's content. If this test fails, the per-account cache
    // isolation has regressed — do not "fix" it by collapsing cacheKey.
    @Test
    fun `cache path is keyed per-account so same-type profiles do not collide`() {
        val cacheRoot = Files.createTempDirectory("unidrive-cache-collision-test")
        val accountA =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                providerId = "onedrive",
                cacheKey = "posteo_onedrive",
                cacheRoot = cacheRoot,
            )
        val accountB =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                providerId = "onedrive",
                cacheKey = "work_onedrive",
                cacheRoot = cacheRoot,
            )

        val pathA = accountA.resolveCachePath("/Documents/report.docx")
        val pathB = accountB.resolveCachePath("/Documents/report.docx")

        assertNotEquals(
            pathA,
            pathB,
            "two same-type accounts must NOT share a cache file for the same remote path",
        )
        // #239: normalize separators so the layout assertion holds on Windows (backslash paths).
        assertTrue(pathA.toString().replace('\\', '/').contains("/hydration/posteo_onedrive/"))
        assertTrue(pathB.toString().replace('\\', '/').contains("/hydration/work_onedrive/"))
    }

    // cacheKey defaults to providerId, preserving the pre-fix layout for any
    // caller (notably tests) that still constructs SyncEngine with only
    // providerId. Pins that the default isn't silently dropped to "default".
    @Test
    fun `cacheKey defaults to providerId when unset`() {
        val cacheRoot = Files.createTempDirectory("unidrive-cache-default-test")
        val engineTypeOnly =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                providerId = "internxt",
                cacheRoot = cacheRoot,
            )
        val path = engineTypeOnly.resolveCachePath("/foo.txt")
        assertTrue(
            // #239: normalize separators so the assertion holds on Windows.
            path.toString().replace('\\', '/').contains("/hydration/internxt/"),
            "with no explicit cacheKey, layout must fall back to providerId; got $path",
        )
    }

    // ── applyDeleteRemote transient-error gate ────────────────────────────────
    // Two orthogonal invariants for the apply-phase DeleteRemote path:
    //  (1) an already-gone remote tombstones the row WITHOUT throwing (correct
    //      idempotent behaviour — regression guard, passes pre-fix).
    //  (2) a transient/other ProviderException does NOT tombstone the row, so
    //      DeleteRemote re-emits on the next sync instead of orphaning the
    //      remote file forever (the data-safety fix — FAILS pre-fix).

    @Test
    fun `delete_of_already_gone_remote_tombstones_row_without_throwing`() =
        runTest {
            // Seed an alive, hydrated row (initial sync hydrates the file).
            provider.deltaItems = listOf(cloudItem("/already-gone.txt", size = 100))
            engine.syncOnce()
            assertNotNull(db.getEntry("/already-gone.txt"))

            // Local delete + empty remote delta → reconciler emits DeleteRemote.
            // Provider reports the remote is already gone via the typed
            // metadata-miss shape that isAlreadyGone recognises.
            Files.delete(syncRoot.resolve("already-gone.txt"))
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            provider.deleteThrow = ProviderException("Item not found: /already-gone.txt")

            // syncOnce must complete normally (no throw escaping the pass).
            engine.syncOnce()

            // Already-gone is correctly treated as a no-op delete: the row is
            // tombstoned (TRASHED) just like a successful delete.
            assertNull(db.getEntry("/already-gone.txt"), "alive view must no longer have the row")
            val trashed = db.recovery.trashedEntries().filter { it.path == "/already-gone.txt" }
            assertEquals(
                1,
                trashed.size,
                "already-gone remote must tombstone the row as TRASHED; got ${db.recovery.allEntriesAnyStatus()}",
            )
        }

    @Test
    fun `delete_with_transient_provider_error_rethrows_and_preserves_row`() =
        runTest {
            // Seed an alive, hydrated row.
            provider.deltaItems = listOf(cloudItem("/transient.txt", size = 100))
            engine.syncOnce()
            assertNotNull(db.getEntry("/transient.txt"))

            // Local delete + empty remote delta → reconciler emits DeleteRemote.
            // Provider fails with a TRANSIENT error whose message does NOT match
            // isAlreadyGone ("Folder not found: " / "Item not found: ").
            Files.delete(syncRoot.resolve("transient.txt"))
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-2"
            provider.deleteThrow = ProviderException("Service unavailable")

            // The apply loop's per-action handler logs the failure and continues;
            // syncOnce returns without crashing the whole pass.
            engine.syncOnce()

            // The provider saw the delete attempt …
            assertTrue(
                provider.deletedPaths.isEmpty(),
                "delete failed, so no path should have been recorded as deleted; got ${provider.deletedPaths}",
            )
            // … but the row MUST survive untouched: NOT tombstoned, still alive.
            assertNotNull(
                db.getEntry("/transient.txt"),
                "transient delete error must NOT tombstone the row — it must stay alive so DeleteRemote re-emits",
            )
            assertTrue(
                db.recovery.trashedEntries().none { it.path == "/transient.txt" },
                "transient error must not produce a TRASHED tombstone; got ${db.recovery.trashedEntries()}",
            )

            // Orphan-forever is fixed: a second pass (provider now healthy)
            // re-emits DeleteRemote and the remote is finally deleted.
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()
            assertTrue(
                provider.deletedPaths.contains("/transient.txt"),
                "DeleteRemote must re-emit on the next sync after a transient failure; got ${provider.deletedPaths}",
            )
            assertNull(db.getEntry("/transient.txt"), "row tombstoned once the delete finally succeeds")
        }

    // ---- #395: the narrowed-scope transition stays silent when nothing was untracked --------

    private inline fun captureEngineLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(SyncEngine::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.mapNotNull { it.formattedMessage }
    }

    @Test
    fun `a first run of a new profile does not announce untracking zero rows`() =
        runTest {
            provider.deltaItems = emptyList()
            val messages =
                captureEngineLogs {
                    preview {
                        engineForScope(standingScope = listOf("/Docs")).syncOnce(dryRun = true)
                    }
                }
            assertTrue(
                messages.none { it.contains("stopped tracking") },
                "a fresh profile has no rows to untrack, so the transition must be silent: $messages",
            )
        }

    @Test
    fun `narrowing over an existing tracked row still says how many it stopped tracking`() =
        runTest {
            db.upsertEntry(
                SyncEntry(
                    path = "/outside.txt",
                    remoteId = "id-outside",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = 0,
                    localSize = 0,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            provider.deltaItems = emptyList()
            val messages =
                captureEngineLogs {
                    preview {
                        engineForScope(standingScope = listOf("/Docs")).syncOnce(dryRun = true)
                    }
                }
            assertTrue(
                messages.any { it.contains("stopped tracking 1 row(s)") },
                "the counted message must stay when a row really was untracked: $messages",
            )
        }
}
