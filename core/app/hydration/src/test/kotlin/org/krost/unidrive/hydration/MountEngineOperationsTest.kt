package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.*
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.audit.AuditLog
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * The mount operations (`ensureHydrated`, `uploadFromCache`, `deleteRemote`) on the mount front-end
 * of a `SyncEngine` host, the production wiring (`MountEngine.over`). Moved here from
 * `SyncEngineTest` with the operations (#560 U3), unchanged.
 */
class MountEngineOperationsTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-engine-test")
        db = StateDatabase(Files.createTempDirectory("unidrive-engine-db").resolve("state.db"))
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

    // ── #87 (WB-3): a folder delete forgets its subtree ─────────────────────────────────────────
    // The live case: a ~133k-item folder deleted in the mount left every row below it EXISTS in
    // state.db (only the folder's own row was tombstoned), so the next fresh mount listed the folder
    // again and planned the whole subtree for re-download.

    @Test
    fun `#87 deleting a folder forgets its subtree in one statement`() =
        runTest {
            db.upsertEntry(trackedRow("/big", isFolder = true))
            db.batch {
                for (i in 1..20_000) db.upsertEntry(trackedRow("/big/f$i.txt", isFolder = false))
            }

            engine.deleteRemote("/big")

            assertTrue(provider.deletedPaths.contains("/big"), "the folder is trashed remotely, once")
            assertEquals(
                org.krost.unidrive.sync.model.EntryStatus.DELETED,
                db.statusOf("/big"),
                "the folder's own row is tombstoned",
            )
            assertEquals(
                org.krost.unidrive.sync.model.EntryStatus.DELETED,
                db.statusOf("/big/f1.txt"),
                "the subtree is tombstoned by the delete itself",
            )
            assertEquals(
                org.krost.unidrive.sync.model.EntryStatus.DELETED,
                db.statusOf("/big/f20000.txt"),
                "the LAST row of the subtree is tombstoned too — nothing below the folder stayed EXISTS",
            )
            // The hydration-cache tree's eviction lives in HydrationImpl.rmdir (evictCacheTree) and is
            // pinned by the verb-level test there; deleteRemote itself only owns state.db.
        }

    @Test
    fun `#87 a folder whose name has a character outside the BMP forgets its subtree too`() =
        runTest {
            // A surrogate pair is two UTF-16 units but one SQLite character: a length-based
            // substr match tombstoned nothing below such a folder. The sibling whose name only
            // shares the prefix without the slash must stay alive.
            val folder = "/photos \uD83D\uDCF7"
            db.upsertEntry(trackedRow(folder, isFolder = true))
            db.upsertEntry(trackedRow("$folder/a.jpg", isFolder = false))
            db.upsertEntry(trackedRow("$folder/sub/b.jpg", isFolder = false))
            db.upsertEntry(trackedRow("${folder}0.txt", isFolder = false))
            db.upsertEntry(trackedRow("$folder.txt", isFolder = false))

            engine.deleteRemote(folder)

            assertEquals(org.krost.unidrive.sync.model.EntryStatus.DELETED, db.statusOf("$folder/a.jpg"))
            assertEquals(org.krost.unidrive.sync.model.EntryStatus.DELETED, db.statusOf("$folder/sub/b.jpg"))
            assertEquals(org.krost.unidrive.sync.model.EntryStatus.EXISTS, db.statusOf("${folder}0.txt"))
            assertEquals(org.krost.unidrive.sync.model.EntryStatus.EXISTS, db.statusOf("$folder.txt"))
        }
    @Test
    fun `#87 a delta that reports the trashed subtree leaves the tombstones alone`() =
        runTest {
            db.upsertEntry(trackedRow("/big", isFolder = true))
            db.upsertEntry(trackedRow("/big/f1.txt", isFolder = false))
            engine.deleteRemote("/big")

            // The trash has landed remotely: the next delta reports the items deleted. The delta path
            // skips deleted items and the tombstones stay exactly as the delete left them — neither
            // side undoes the other.
            provider.deltaItems = listOf(
                cloudItem("/big", deleted = true),
                cloudItem("/big/f1.txt", deleted = true),
            )
            provider.deltaCursor = "after-trash"
            engine.syncOnce()

            assertEquals(
                org.krost.unidrive.sync.model.EntryStatus.DELETED,
                db.statusOf("/big"),
            )
            assertEquals(
                org.krost.unidrive.sync.model.EntryStatus.DELETED,
                db.statusOf("/big/f1.txt"),
            )
        }

    // ── Hydration SPI: ensureHydrated / uploadFromCache ───────────────────────

    @Test
    fun `ensureHydrated downloads a missing file and returns the local cache path`() =
        runTest {
            // Use a dedicated temp cache root so the test does not pollute ~/.cache.
            val cacheRoot = Files.createTempDirectory("unidrive-cache-test")
            val engineWithCache =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                )

            // Seed remote content and an unhydrated DB entry.
            provider.files["/foo.txt"] = "hello".toByteArray()
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/foo.txt",
                    remoteId = "id-/foo.txt",
                    remoteHash = "hash-/foo.txt",
                    remoteSize = 5L,
                    remoteModified = java.time.Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = java.time.Instant.now(),
                ),
            )

            val cachePath = engineWithCache.ensureHydrated("/foo.txt")

            assertTrue(Files.exists(cachePath), "cache file must exist after ensureHydrated")
            assertEquals(5L, Files.size(cachePath), "cache file must contain the remote content")
            assertEquals(true, db.getEntry("/foo.txt")?.isHydrated, "DB row must be marked hydrated")
        }

    @Test
    fun `ensureHydrated is idempotent — warm path skips re-download`() =
        runTest {
            val cacheRoot = Files.createTempDirectory("unidrive-cache-test")
            val engineWithCache =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                )

            provider.files["/bar.txt"] = "world".toByteArray()
            // Pre-create the cache file and mark as hydrated — warm path.
            val expectedCachePath = cacheRoot.resolve("unidrive/hydration/default/bar.txt")
            Files.createDirectories(expectedCachePath.parent)
            Files.writeString(expectedCachePath, "world")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/bar.txt",
                    remoteId = "id-/bar.txt",
                    remoteHash = "hash-/bar.txt",
                    remoteSize = 5L,
                    remoteModified = java.time.Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = java.time.Instant.now(),
                ),
            )
            val callsBefore = provider.downloadByIdCalls.size + provider.downloadByPathCalls.size

            val cachePath = engineWithCache.ensureHydrated("/bar.txt")

            assertEquals(expectedCachePath, cachePath)
            val callsAfter = provider.downloadByIdCalls.size + provider.downloadByPathCalls.size
            assertEquals(
                callsBefore,
                callsAfter,
                "warm path must not call the provider download at all",
            )
        }

    @Test
    fun `uploadFromCache uploads the cache file and updates state`() =
        runTest {
            val cacheRoot = Files.createTempDirectory("unidrive-cache-test")
            val engineWithCache =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                )

            // Create a local cache file.
            val cacheFile = cacheRoot.resolve("foo.txt")
            Files.writeString(cacheFile, "hello")
            // Seed the DB entry (hydrated, has a remoteId).
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/foo.txt",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = java.time.Instant.now(),
                ),
            )

            engineWithCache.uploadFromCache("/foo.txt", cacheFile)

            assertTrue(
                provider.uploadedPaths.contains("/foo.txt"),
                "provider must have received the upload; got: ${provider.uploadedPaths}",
            )
            assertEquals(
                "hello",
                String(provider.files["/foo.txt"] ?: ByteArray(0)),
                "remote content must match the cache file",
            )
            val entry = db.getEntry("/foo.txt")
            assertNotNull(entry, "DB entry must exist after uploadFromCache")
            assertTrue(entry.isHydrated, "DB row must remain hydrated after upload")
        }

    @Test
    fun `uploadFromCache emits audit log on success`() =
        runTest {
            val cacheRoot = Files.createTempDirectory("unidrive-cache-test")
            val auditDir = Files.createTempDirectory("unidrive-audit-test")
            val auditLog = AuditLog(auditDir, profileName = "test")
            val engineWithAudit =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                    auditLog = auditLog,
                )

            val cacheFile = cacheRoot.resolve("foo.txt")
            Files.writeString(cacheFile, "hello")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/foo.txt",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = java.time.Instant.now(),
                ),
            )

            engineWithAudit.uploadFromCache("/foo.txt", cacheFile)

            // Verify that exactly one audit entry was written to today's file.
            val auditFile = auditFileIn(auditDir)
            assertTrue(Files.exists(auditFile), "audit file must exist after uploadFromCache")
            val lines = Files.readAllLines(auditFile).filter { it.isNotBlank() }
            assertEquals(1, lines.size, "exactly one audit entry must be emitted on success")
            assertTrue(lines[0].contains("\"Upload\""), "audit entry must record action=Upload")
            assertTrue(lines[0].contains("/foo.txt"), "audit entry must record the path")
            assertTrue(lines[0].contains("\"success\""), "audit entry must record result=success")
        }

    @Test
    fun `uploadFromCache emits audit log when provider throws`() =
        runTest {
            val cacheRoot = Files.createTempDirectory("unidrive-cache-test")
            val auditDir = Files.createTempDirectory("unidrive-audit-test")
            val auditLog = AuditLog(auditDir, profileName = "test")
            val engineWithAudit =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                    auditLog = auditLog,
                )

            provider.uploadFailCount = 1

            val cacheFile = cacheRoot.resolve("foo.txt")
            Files.writeString(cacheFile, "hello")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/foo.txt",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = java.time.Instant.now(),
                ),
            )

            assertFailsWith<Exception> {
                engineWithAudit.uploadFromCache("/foo.txt", cacheFile)
            }

            val auditFile = auditFileIn(auditDir)
            assertTrue(Files.exists(auditFile), "audit file must exist after failed uploadFromCache")
            val lines = Files.readAllLines(auditFile).filter { it.isNotBlank() }
            assertEquals(1, lines.size, "exactly one audit entry must be emitted on failure")
            assertTrue(lines[0].contains("\"Upload\""), "audit entry must record action=Upload")
            assertTrue(lines[0].contains("/foo.txt"), "audit entry must record the path")
            assertTrue(lines[0].contains("\"result\":\"failed:"), "audit entry must record a failed result")
        }

    // ── uploadFromCache: excluded-path guard (keep-local, surface (b) fix) ───

    @Test
    fun `uploadFromCache does not upload excluded desktop-junk paths`() =
        runTest {
            // Paths that match DEFAULT_EXCLUDE_PATTERNS: .directory.lock, Thumbs.db, *.tmp
            val excludedPaths = listOf(
                "/.directory.lock",
                "/sub/.directory.lock",
                "/Thumbs.db",
                "/pics/Thumbs.db",
                "/work/draft.tmp",
            )
            val cacheRoot = Files.createTempDirectory("unidrive-excl-test")
            val engineWithDefaults =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                )

            for (excludedPath in excludedPaths) {
                val cacheFile = Files.createTempFile("excl-cache", ".bin")
                Files.writeString(cacheFile, "junk")
                val uploadsBefore = provider.uploadedPaths.size

                engineWithDefaults.uploadFromCache(excludedPath, cacheFile)

                assertEquals(
                    uploadsBefore,
                    provider.uploadedPaths.size,
                    "provider.upload must NOT be called for excluded path $excludedPath",
                )
            }
        }

    @Test
    fun `uploadFromCache uploads non-excluded paths normally`() =
        runTest {
            val cacheRoot = Files.createTempDirectory("unidrive-excl-nonexcl-test")
            val engineWithDefaults =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                )

            val cacheFile = Files.createTempFile("real-cache", ".bin")
            Files.writeString(cacheFile, "real content")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/real.txt",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = java.time.Instant.now(),
                ),
            )

            engineWithDefaults.uploadFromCache("/real.txt", cacheFile)

            assertTrue(
                provider.uploadedPaths.contains("/real.txt"),
                "provider.upload MUST be called for a non-excluded path; got: ${provider.uploadedPaths}",
            )
        }

    @Test
    fun `uploadFromCache advances the local watermark for skipped excluded paths (no recovery replay)`() =
        runTest {
            // Skipping the upload must still advance localMtime: the co-daemon's
            // crash-recovery scanner replays open_write for any cache file whose mtime
            // exceeds HydrationImpl.lastSynced() (= localMtime); a skipped keep-local file
            // with a stale/absent watermark would be replayed on EVERY daemon restart.
            val cacheRoot = Files.createTempDirectory("unidrive-excl-watermark-test")
            val engineWithDefaults =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                )
            val excludedPath = "/sub/.directory.lock"
            val cacheFile = Files.createTempFile("excl-watermark", ".bin")
            Files.writeString(cacheFile, "junk")
            val cacheMtime = Files.getLastModifiedTime(cacheFile).toMillis()

            engineWithDefaults.uploadFromCache(excludedPath, cacheFile)

            val entry = db.getEntry(excludedPath)
            assertNotNull(entry, "skip path must persist a keep-local row so recovery has a watermark")
            assertEquals(
                cacheMtime,
                entry.localMtime,
                "localMtime watermark must equal the cache file mtime so crash-recovery does not replay the excluded file",
            )
            assertNull(entry.remoteId, "excluded keep-local file must NOT get a remoteId (never uploaded)")
            assertFalse(
                provider.uploadedPaths.contains(excludedPath),
                "provider.upload must not be called for the excluded path",
            )
        }

    @Test
    fun `ensureHydrated rejects a corrupted download when verifyIntegrity is enabled`() =
        runTest {
            // Minimal CloudProvider that returns Sha256Hex as its hash algorithm so
            // the integrity check is active, and serves fixed "hello" bytes on download.
            val corruptContent = "hello".toByteArray()
            val hashingProvider =
                object : CloudProvider {
                    override val id = "fake-hashing"
                    override val displayName = "Fake Hashing"
                    override var isAuthenticated = true

                    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

                    override fun hashAlgorithm(): HashAlgorithm? = HashAlgorithm.Sha256Hex

                    override suspend fun authenticate() {}

                    override suspend fun logout() {}

                    override suspend fun listChildren(path: String) = emptyList<CloudItem>()

                    override suspend fun getMetadata(path: String) =
                        CloudItem(
                            id = "id-$path",
                            name = path.substringAfterLast("/"),
                            path = path,
                            size = corruptContent.size.toLong(),
                            isFolder = false,
                            modified = Instant.now(),
                            created = Instant.now(),
                            hash = null,
                            mimeType = null,
                        )

                    override suspend fun download(
                        remotePath: String,
                        destination: Path,
                    ): Long {
                        Files.createDirectories(destination.parent)
                        Files.write(destination, corruptContent)
                        return corruptContent.size.toLong()
                    }

                    override suspend fun downloadById(
                        remoteId: String,
                        remotePath: String,
                        destination: Path,
                    ): Long = download(remotePath, destination)

                    override suspend fun upload(
                        localPath: Path,
                        remotePath: String,
                        existingRemoteId: String?,
                        ifMatchETag: String?,
                        onProgress: ((Long, Long) -> Unit)?,
                    ): CloudItem = getMetadata(remotePath)

                    override suspend fun delete(remotePath: String, ifMatchETag: String?) {}

                    override suspend fun createFolder(path: String): CloudItem = getMetadata(path)

                    override suspend fun move(
                        fromPath: String,
                        toPath: String,
                    ): CloudItem = getMetadata(toPath)

                    override suspend fun delta(
                        cursor: String?,
                        onPageProgress: ((Int) -> Unit)?,
                        scanContext: org.krost.unidrive.ScanContext?,
                    ) = DeltaPage(items = emptyList(), cursor = "c", hasMore = false)

                    override suspend fun quota() = QuotaInfo(total = 0, used = 0, remaining = 0)
                }

            val cacheRoot = Files.createTempDirectory("unidrive-cache-test")
            val engineWithIntegrity =
                SyncEngine(
                    provider = hashingProvider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    cacheRoot = cacheRoot,
                    verifyIntegrity = true,
                )

            // Remote content is "hello" but the DB entry records a bogus expected hash
            // that will never match the actual SHA-256 of "hello".
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/corrupt.txt",
                    remoteId = "id-/corrupt.txt",
                    remoteHash = "000000000000000000000000000000000000000000000000000000000000dead",
                    remoteSize = 5L,
                    remoteModified = java.time.Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = java.time.Instant.now(),
                ),
            )

            val expectedCachePath = cacheRoot.resolve("unidrive/hydration/default/corrupt.txt")

            assertFailsWith<IllegalStateException>(
                "ensureHydrated must throw when the downloaded file fails the integrity check",
            ) {
                engineWithIntegrity.ensureHydrated("/corrupt.txt")
            }
            assertFalse(
                Files.exists(expectedCachePath),
                "corrupted cache file must be deleted after integrity failure",
            )
        }

    // ── deleteRemote idempotency ──────────────────────────────────────────────

    @Test
    fun `deleteRemote_treats_folder_not_found_resolution_error_as_already_deleted`() =
        runTest {
            // Guards: SyncEngine.deleteRemote must NOT throw when the provider
            // throws ProviderException("Folder not found: <seg> in <path>") —
            // the typed path-resolution failure emitted by InternxtProvider.resolveFolder
            // when a parent folder is already gone on the remote.
            // markDeleted must still run — the postcondition is "path gone from cloud".
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/gone.txt",
                    remoteId = "rid-gone",
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = Instant.now(),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            provider.deleteThrow = ProviderException("Folder not found: gone in /gone.txt")

            engine.deleteRemote("/gone.txt")   // must not throw

            assertNull(db.getEntry("/gone.txt"), "markDeleted must have run: row must not be alive")
            val tombstone = db.recovery.allEntriesAnyStatus().find { it.path == "/gone.txt" }
            assertNotNull(tombstone, "DELETED tombstone must exist for reconciler tracking")
        }

    @Test
    fun `deleteRemote_treats_item_not_found_metadata_miss_as_already_deleted`() =
        runTest {
            // Guards: SyncEngine.deleteRemote must NOT throw when the provider
            // throws ProviderException("Item not found: <path>") —
            // the typed metadata-miss emitted by InternxtProvider.getMetadata
            // when the leaf item itself is absent (parent exists, leaf is gone).
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/gone-leaf.txt",
                    remoteId = "rid-gone-leaf",
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = Instant.now(),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            provider.deleteThrow = ProviderException("Item not found: /gone-leaf.txt")

            engine.deleteRemote("/gone-leaf.txt")   // must not throw

            assertNull(db.getEntry("/gone-leaf.txt"), "markDeleted must have run: row must not be alive")
            val tombstone = db.recovery.allEntriesAnyStatus().find { it.path == "/gone-leaf.txt" }
            assertNotNull(tombstone, "DELETED tombstone must exist for reconciler tracking")
        }

    @Test
    fun `deleteRemote_does_not_swallow_5xx_mentioning_404_in_message`() =
        runTest {
            // Guards: codex P2 — free-text substring matching on "not found" / "404"
            // can misclassify a real 5xx/proxy error as "already gone", silently
            // tombstoning a live file. This test proves the hole is closed:
            // a ProviderException whose MESSAGE contains "404" or "not found" but
            // does NOT carry a recognised typed not-found prefix must re-throw.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/live.txt",
                    remoteId = "rid-live",
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = Instant.now(),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            // 502 proxy error whose body happens to contain both "404" and "not found" —
            // the old free-text check would have swallowed this and tombstoned a live file.
            provider.deleteThrow = ProviderException("502 Bad Gateway: upstream /live.txt not found (404)")

            assertFailsWith<ProviderException> { engine.deleteRemote("/live.txt") }
            assertNotNull(db.getEntry("/live.txt"), "row must remain alive — 5xx must not tombstone a live file")
        }

    @Test
    fun `deleteRemote_rethrows_non_provider_exception`() =
        runTest {
            // Guards: isAlreadyGone only accepts ProviderException subtypes;
            // a bare RuntimeException (e.g. from a mock or unexpected code path)
            // must re-throw unchanged.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/network-err.txt",
                    remoteId = "rid-net",
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = Instant.now(),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            provider.deleteThrow = RuntimeException("Connection reset by peer")

            assertFailsWith<RuntimeException> { engine.deleteRemote("/network-err.txt") }
            assertNotNull(db.getEntry("/network-err.txt"), "row must remain alive after non-provider error")
        }

    @Test
    fun `deleteRemote_rethrows_non_not_found_provider_errors`() =
        runTest {
            // Guards: real provider errors (5xx, auth, throttle) must NOT be swallowed.
            // The row must remain alive — EIO is the correct outcome.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/server-error.txt",
                    remoteId = "rid-err",
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = Instant.now(),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            provider.deleteThrow = ProviderException("500 Internal Server Error")

            assertFailsWith<ProviderException> { engine.deleteRemote("/server-error.txt") }
            assertNotNull(db.getEntry("/server-error.txt"), "row must remain alive after real error")
        }
}
