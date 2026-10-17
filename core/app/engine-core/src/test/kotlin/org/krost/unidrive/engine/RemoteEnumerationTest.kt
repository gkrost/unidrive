package org.krost.unidrive.engine

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.EnumerationStatus
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// #560 U2b: the remote gather and the mount's enumeration run on their own, with
// explicit collaborators and no SyncEngine. The behaviour itself is pinned through
// SyncEngine by EnumerateRemoteIntoStateTest, EnumerateCorroborationGuardTest,
// RemoteGatherCollisionTest and EnumerationParityTest in :app:sync.
class RemoteEnumerationTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = ListingProvider()
    private val inFlight = mutableSetOf<String>()
    private val invalidations = mutableListOf<Pair<Set<String>, Boolean>>()
    private val invalidationMoves = mutableListOf<List<RemoteGather.RemoteMerge.Move>>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-remote-enumeration")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun cachePath(path: String): Path = tmp.resolve("cache").resolve(path.removePrefix("/"))

    private fun gather(
        localTopAliases: (Map<String, CloudItem>) -> Map<String, String> = { emptyMap() },
    ) = RemoteGather(
        provider = provider,
        db = db,
        guard =
            RemoteOperationGuard(
                standingScope = emptyList(),
                syncPaths = emptyList(),
                excludePatterns = emptyList(),
                maxConcurrentTransfers = 2,
                matchesGlob = { _, _ -> false },
            ),
        options = RemoteGather.Options(providerId = "test"),
        localTopAliases = localTopAliases,
        listener =
            object : RemoteGather.Listener {
                override fun onViewInvalidated(
                    changedPaths: Set<String>,
                    full: Boolean,
                    moved: List<RemoteGather.RemoteMerge.Move>,
                ) {
                    invalidations += changedPaths to full
                    invalidationMoves += moved
                }
            },
        log = LoggerFactory.getLogger(RemoteEnumerationTest::class.java),
    )

    private fun enumeration(
        gather: RemoteGather = gather(),
        mergeChunkRows: Int = RemoteEnumeration.MERGE_CHUNK_ROWS,
    ) = RemoteEnumeration(
        gather = gather,
        db = db,
        tracker = EnumerationTracker(),
        reapGuards = RemoteEnumeration.ReapGuards(uploadInFlight = { it in inFlight }, cachePathOf = ::cachePath),
        log = LoggerFactory.getLogger(RemoteEnumerationTest::class.java),
        mergeChunkRows = mergeChunkRows,
    )

    private fun file(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 1,
        isFolder = false,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
    )

    private fun cacheFile(path: String): Path {
        val p = cachePath(path)
        Files.createDirectories(p.parent)
        Files.writeString(p, "bytes")
        // Older than the row's last-synced watermark: not a dirty edit.
        Files.setLastModifiedTime(p, FileTime.fromMillis(0))
        return p
    }

    // ---- #147: the merge runs in transactions of mergeChunkRows rows ----

    @Test
    fun `a chunk without its top-level folder still keys an aliased path at its real-local path`() =
        runTest {
            // One row per transaction: the aliased child merges in a chunk of its own, so a
            // per-chunk alias derivation would see no top-level folder and key it at the
            // canonical name, where LocalScanner can never find it on disk.
            val aliased =
                gather(localTopAliases = { changes ->
                    if (changes.keys.any { it == "/Bilder" }) mapOf("Bilder" to "Pictures") else emptyMap()
                })
            provider.items = listOf(file("1", "/Bilder"), file("2", "/Bilder/x.jpg"))

            val result = enumeration(aliased, mergeChunkRows = 1).enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(2, result.upserted)
            assertEquals("1", db.getEntry("/Pictures")?.remoteId, "the aliased top folder lands at its local name")
            val child = db.getEntry("/Pictures/x.jpg")
            assertNotNull(child, "the child must not be keyed at its canonical path by its own chunk")
            assertEquals("/Bilder/x.jpg", child.remotePath, "with the canonical path recorded as remotePath")
            assertNull(db.getEntry("/Bilder/x.jpg"))
        }

    @Test
    fun `a child merges against the parent row its own pass just wrote across a chunk boundary`() =
        runTest {
            // #601 shape: the folder at /p was deleted remotely and re-created under the same
            // name with a new id; the stale row is in state.db. Parent and child land in
            // different transactions here — the child's parent guard must fall back to the DB
            // and see the fresh parent id, not the dead row's.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/p",
                    remoteId = "p-dead",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            provider.items =
                listOf(
                    file("p-new", "/p").copy(isFolder = true, size = 0),
                    file("c1", "/p/c.txt").copy(parentId = "p-new"),
                )

            val result = enumeration(mergeChunkRows = 1).enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals("p-new", db.getEntry("/p")?.remoteId)
            assertNotNull(db.getEntry("/p/c.txt"), "the child must not be skipped by the stale parent row")
        }

    @Test
    fun `a stale child of a parent deleted in an earlier chunk is still skipped`() =
        runTest {
            // #601 across a chunk boundary: /p is delivered deleted, its child still reports the
            // dead folder as parent. The guard must see the deletion in the FULL change set, not
            // fall back to the still-present row whose id equals the child's parentId.
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/p",
                    remoteId = "p-dead",
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            provider.items =
                listOf(
                    file("p-dead", "/p").copy(isFolder = true, size = 0, deleted = true),
                    file("c1", "/p/c.txt").copy(parentId = "p-dead"),
                )

            enumeration(mergeChunkRows = 1).enumerate(reset = false)

            assertNull(db.getEntry("/p/c.txt"), "the dead folder's child must not be resurrected")
        }

    @Test
    fun `a complete first listing lands in state, promotes the cursor and announces the paths`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            val enumeration = enumeration()

            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertTrue(result.complete)
            assertEquals(2, result.upserted)
            assertEquals("1", db.getEntry("/a.txt")?.remoteId)
            assertEquals("2", db.getEntry("/b.txt")?.remoteId)
            assertEquals("c1", db.getSyncState("delta_cursor"))
            assertEquals("0", db.getSyncState(RemoteEnumeration.FAILURE_STREAK_KEY))
            assertEquals(listOf(setOf("/a.txt", "/b.txt") to false), invalidations)
            val status = enumeration.status()
            assertEquals(EnumerationStatus.State.IDLE, status.state)
            assertFalse(status.first)
        }

    @Test
    fun `a full re-listing reaps a vanished path but never one whose upload is in flight`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"), file("3", "/c.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val bCache = cacheFile("/b.txt")
            val cCache = cacheFile("/c.txt")
            inFlight += "/c.txt"

            provider.items = listOf(file("1", "/a.txt"))
            val result = enumeration.enumerate(reset = true)

            assertTrue(result.ok)
            assertEquals(1, result.reaped)
            assertNull(db.getEntry("/b.txt"), "the vanished path is reaped")
            assertFalse(Files.exists(bCache), "and its cache copy evicted")
            assertNotNull(db.getEntry("/c.txt"), "an upload in flight defers the reap")
            assertTrue(Files.exists(cCache), "and keeps the only copy")
        }

    @Test
    fun `a provider failure counts the streak and leaves the cursor alone`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            provider.failure = ProviderException("listing failed")

            val result = enumeration.enumerate(reset = false)

            assertFalse(result.ok)
            assertEquals("1", db.getSyncState(RemoteEnumeration.FAILURE_STREAK_KEY))
            assertEquals("c1", db.getSyncState("delta_cursor"))
            assertEquals(EnumerationStatus.State.FAILED, enumeration.status().state)
            assertNotNull(db.getEntry("/a.txt"))
        }

    // ---- the status of an idle enumeration is answered from memory ----

    @Test
    fun `an idle status is answered while another thread holds state db's monitor`() =
        runTest {
            // A recording of a daemon showed 593 status requests queued on the monitor behind the readers of the upload
            // snapshot and the saves of a scan, 141 s together.
            provider.items = listOf(file("1", "/a.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                Thread {
                    synchronized(db) {
                        held.countDown()
                        release.await()
                    }
                }.apply { start() }
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS))

                val status = CompletableFuture.supplyAsync { enumeration.status() }.get(5, TimeUnit.SECONDS)

                assertEquals(EnumerationStatus.State.IDLE, status.state)
                assertFalse(status.first, "the cursor the last attempt left")
                assertNotNull(status.lastSuccessAtMs)
            } finally {
                release.countDown()
                holder.join()
            }
        }

    @Test
    fun `the status follows what each attempt leaves in state db, a failed one included`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            provider.unreachable = setOf("2")
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            assertEquals(1, enumeration.status().unreachableItems)
            assertEquals(true, enumeration.status().lastScanComplete)

            // The next listing places both: the figure goes. Both are read back from state.db, not remembered.
            provider.unreachable = emptySet()
            enumeration.enumerate(reset = true)
            assertNull(enumeration.status().unreachableItems)

            // An attempt that fails after the cursor was reset: the status is the failed one's, with the cursor it left.
            db.setSyncState("delta_cursor", "")
            provider.failure = ProviderException("listing failed")
            enumeration.enumerate(reset = false)
            val failed = enumeration.status()
            assertEquals(EnumerationStatus.State.FAILED, failed.state)
            assertTrue(failed.first, "the failed attempt settled the status from state.db, which has no cursor")
        }

    @Test
    fun `a pass that writes the keys outside the enumeration tells the status to read them again`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"))
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            assertFalse(enumeration.status().first)

            // The mirror's sync pass writes the same keys (SyncEngine.syncOnce settles after it).
            db.setSyncState("delta_cursor", "")
            db.setSyncState(RemoteGather.DROPPED_ITEMS_KEY, "4")
            enumeration.settleStatus()

            val status = enumeration.status()
            assertTrue(status.first)
            assertEquals(4, status.droppedItems)
        }

    @Test
    fun `two live remote items at one path keep one row and mark the path collided`() =
        runTest {
            provider.items = listOf(file("9", "/same.txt"), file("4", "/same.txt"))
            val gather = gather()

            enumeration(gather).enumerate(reset = false)

            assertTrue(gather.isCollided("/same.txt"))
            assertEquals("4", db.getEntry("/same.txt")?.remoteId, "the smaller id wins when neither is tracked")
            assertEquals("1", db.getSyncState(RemoteGather.REMOTE_COLLISIONS_KEY))
        }

    // ---- #595: what the view.invalidated push names --------------------------------------------------------------------------------

    @Test
    fun `a remote rename reports the old path beside the new one and carries the move hint`() =
        runTest {
            provider.items = listOf(file("1", "/before.txt"))
            enumeration().enumerate(reset = false)

            provider.items = listOf(file("1", "/after.txt"))
            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(2, result.upserted, "both ends of the move changed in the view")
            assertEquals("1", db.getEntry("/after.txt")?.remoteId)
            assertNull(db.getEntry("/before.txt"), "the row moved to the new path")
            val (paths, _) = invalidations.last()
            assertEquals(setOf("/before.txt", "/after.txt"), paths, "the old path must be named beside the new one")
            assertEquals(listOf(RemoteGather.RemoteMerge.Move("/before.txt", "/after.txt")), invalidationMoves.last())
        }

    @Test
    fun `a re-delivered unchanged delta invalidates nothing`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            enumeration().enumerate(reset = false)
            invalidations.clear()

            // The Internxt delta rewinds its cursor on purpose: the same items come back.
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(0, result.upserted, "an unchanged re-delivery upserts nothing the view can see")
            assertEquals(emptyList<Pair<Set<String>, Boolean>>(), invalidations, "no view.invalidated for unchanged paths")
            assertEquals("c2", db.getSyncState("delta_cursor"), "the cursor still advances")
        }

    @Test
    fun `a delta that clears download quarantine invalidates the error shown in the view`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"))
            enumeration().enumerate(reset = false)
            assertTrue(db.setDownloadQuarantine("1", java.time.Instant.now()))
            assertNotNull(db.getEntry("/a.txt")?.lastErrorAt)
            invalidations.clear()

            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertNull(db.getEntry("/a.txt")?.lastErrorAt)
            assertEquals(setOf("/a.txt"), invalidations.single().first)
        }

    @Test
    fun `a genuinely changed item still invalidates its path`() =
        runTest {
            provider.items = listOf(file("1", "/a.txt"))
            enumeration().enumerate(reset = false)
            invalidations.clear()

            provider.items = listOf(CloudItem(id = "1", name = "a.txt", path = "/a.txt", size = 42, isFolder = false, modified = null, created = null, hash = "deadbeef", mimeType = null))
            val result = enumeration().enumerate(reset = false)

            assertEquals(1, result.upserted)
            assertEquals(1, invalidations.size)
            assertEquals(setOf("/a.txt"), invalidations.single().first)
            assertEquals(42, db.getEntry("/a.txt")?.remoteSize)
        }

    // ── #601: the lagging-delta parent guard ────────────────────────────────

    // The #601 repro: the old folder generation was trashed, a new one was created under the
    // same path, and the lagging delta re-delivers the dead generation's child as EXISTS.
    private fun seedFolderRow(
        path: String,
        remoteId: String,
    ) {
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = path,
                remoteId = remoteId,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
    }

    @Test
    fun `a stale child of a trashed folder generation is not upserted under a re-created path`() =
        runTest {
            seedFolderRow("/again", remoteId = "new-folder")
            val deadGenerationChild = file("old-x", "/again/x.txt").copy(parentId = "trashed-folder")

            val merge = gather().updateRemoteEntries(mapOf("/again/x.txt" to deadGenerationChild))

            assertNull(db.getEntry("/again/x.txt"), "the dead generation's child must not resurrect under the new folder")
            assertTrue("/again/x.txt" !in merge.changedPaths)
        }

    @Test
    fun `a child whose parent id matches the live folder row is upserted as usual`() =
        runTest {
            seedFolderRow("/again", remoteId = "new-folder")
            val freshChild = file("new-x", "/again/x.txt").copy(parentId = "new-folder")

            val merge = gather().updateRemoteEntries(mapOf("/again/x.txt" to freshChild))

            assertEquals("new-x", db.getEntry("/again/x.txt")?.remoteId)
            assertTrue("/again/x.txt" in merge.changedPaths)
        }

    @Test
    fun `a child preceding its replacement parent in one delta uses that parent generation`() =
        runTest {
            seedFolderRow("/again", remoteId = "old-folder")
            val child = file("new-x", "/again/x.txt").copy(parentId = "new-folder")
            val replacementParent = file("new-folder", "/again").copy(isFolder = true)

            val merge = gather().updateRemoteEntries(linkedMapOf("/again/x.txt" to child, "/again" to replacementParent))

            assertEquals("new-x", db.getEntry("/again/x.txt")?.remoteId)
            assertTrue("/again/x.txt" in merge.changedPaths)
        }

    @Test
    fun `a delta item whose parent row is unknown still lands`() =
        runTest {
            // First enumeration: the child can arrive before its parent row exists.
            val orphan = file("x", "/fresh/x.txt").copy(parentId = "some-folder")

            val merge = gather().updateRemoteEntries(mapOf("/fresh/x.txt" to orphan))

            assertEquals("x", db.getEntry("/fresh/x.txt")?.remoteId, "unknown parent: no evidence of staleness")
            assertTrue("/fresh/x.txt" in merge.changedPaths)
        }

    @Test
    fun `root-level items pass the parent guard`() =
        runTest {
            // The drive root has no row in sync_entries, so there is nothing to mismatch against.
            val rootChild = file("r", "/root.txt").copy(parentId = "drive-root-id")

            gather().updateRemoteEntries(mapOf("/root.txt" to rootChild))

            assertEquals("r", db.getEntry("/root.txt")?.remoteId)
        }

    @Test
    fun `an item without a parent id passes the guard untouched`() =
        runTest {
            // Provider-neutral: OneDrive's mapper fills no parentId (null passes).
            seedFolderRow("/again", remoteId = "new-folder")
            val noParentId = file("x", "/again/x.txt")

            gather().updateRemoteEntries(mapOf("/again/x.txt" to noParentId))

            assertEquals("x", db.getEntry("/again/x.txt")?.remoteId)
        }

    // #878: an API failure with an HTTP status answers last_error_code http_<status>, and the reason
    // loses the response body.
    @Test
    fun `an api failure reports its status as the last error code`() =
        runTest {
            val e = enumeration()
            provider.failure = object : ProviderException("API error: 408 Request Timeout - {\"statusCode\":408}", httpStatus = 408) {}
            val result = e.enumerate(reset = false)

            assertFalse(result.ok)
            val s = e.status()
            assertEquals("http_408", s.lastErrorCode, "the status becomes the code")
            assertEquals("API error: 408 Request Timeout", s.lastError, "the body is dropped from the reason")
        }

    @Test
    fun `a gateway wrapper reports the HTTP status in its cause`() {
        val response = ProviderException("gateway answered 524", httpStatus = 524)
        assertEquals("http_524", codeOf(ProviderException("gateway unavailable", cause = response)))
    }

    // Stands in for kotlinx.serialization's class, which this module's classpath does not carry: matched by name.
    private class SerializationException(message: String) : RuntimeException(message)

    // #900: io means transport I/O only; an unreadable answer is decode, an unexpected exception is internal.
    private fun codeOf(failure: ProviderException): String? =
        kotlinx.coroutines.runBlocking {
            val e = enumeration()
            provider.failure = failure
            e.enumerate(reset = false)
            e.status().lastErrorCode
        }

    @Test
    fun `an unreadable answer reports decode, not io`() {
        val cause = SerializationException("Unexpected JSON token")
        assertEquals("decode", codeOf(ProviderException("cannot be read", cause = cause)))
    }

    @Test
    fun `an unexpected internal failure reports internal`() {
        assertEquals("internal", codeOf(ProviderException("boom", cause = IllegalStateException("x"))))
        assertEquals("internal", codeOf(ProviderException("boom")))
    }

    @Test
    fun `transport failures keep their transport class`() {
        assertEquals("io", codeOf(ProviderException("cut", cause = java.io.IOException("disk"))))
        assertEquals("timeout", codeOf(ProviderException("slow", cause = java.net.SocketTimeoutException("t"))))
    }

    // ---- the recovery latch: a mount daemon never runs the sync pass that used to clear it --------------------------------------

    private fun latch(): String? = db.getSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY)

    private fun folder(
        id: String,
        path: String,
    ) = file(id, path).copy(isFolder = true, size = 0)

    @Test
    fun `a complete full listing after an incomplete pass ends the recovery, and the next enumeration continues from the cursor`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(file("1", "/a.txt"), file("2", "/b.txt"))
            provider.complete = false
            val enumeration = enumeration()

            enumeration.enumerate(reset = false)
            assertEquals("true", latch(), "an incomplete pass sets the latch")

            provider.complete = true
            val recovery = enumeration.enumerate(reset = false)
            assertTrue(recovery.complete)
            assertEquals("false", latch(), "the complete full listing ends the recovery")

            provider.items = emptyList()
            enumeration.enumerate(reset = false)

            assertEquals(listOf(null, null, "c2"), provider.cursors, "the pass after the recovery is incremental: it asks from the cursor")
            assertEquals("false", latch())
        }

    @Test
    fun `an incomplete full listing keeps the recovery, and the drive is listed from zero again`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(file("1", "/a.txt"))
            provider.complete = false
            val enumeration = enumeration()

            enumeration.enumerate(reset = false)
            enumeration.enumerate(reset = false)

            assertEquals("true", latch())
            assertEquals(listOf<String?>(null, null), provider.cursors)
            assertNotNull(db.getEntry("/a.txt"), "an incomplete pass reaps nothing")
        }

    @Test
    fun `a full listing the remote-shrink guard distrusts keeps the recovery`() =
        runTest {
            provider.items = (1..60).map { file("id$it", "/f$it.txt") }
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")

            // 50 of 60 tracked rows vanish: not above the bulk threshold of 50, so they are reaped, but the listing
            // looks partial (10 of 60 observed, below the half the shrink guard demands).
            provider.items = (1..10).map { file("id$it", "/f$it.txt") }
            val shrunk = enumeration.enumerate(reset = false)

            assertEquals(50, shrunk.reaped)
            assertEquals("true", latch(), "a listing that looks partial is no trusted inventory")

            // The baseline is 10 rows now: below the floor of the guard, so the same listing is trusted.
            enumeration.enumerate(reset = false)
            assertEquals("false", latch())
        }

    @Test
    fun `a pass whose reaps the bulk guard deferred keeps the recovery until the listing corroborates it`() =
        runTest {
            provider.items = (1..200).map { file("id$it", "/f$it.txt") }
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")

            // 60 of 200 vanish: above max(50, 20% = 40), so the reaps wait for a second complete listing. 140 observed is
            // above half of the baseline, so the shrink guard is quiet: the deferral alone keeps the latch.
            provider.items = (1..140).map { file("id$it", "/f$it.txt") }
            val first = enumeration.enumerate(reset = false)

            assertEquals(0, first.reaped)
            assertNotNull(db.getEntry("/f150.txt"), "deferred, not reaped")
            assertEquals("true", latch(), "a deferred reap is no trusted inventory; the corroboration needs a full listing again")

            val second = enumeration.enumerate(reset = false)

            assertEquals(60, second.reaped)
            assertEquals("false", latch(), "corroborated and reaped: the listing is trusted")
        }

    @Test
    fun `the recovery latch is settled by one rule, and every condition of it is needed`() {
        val gather = gather()

        fun settle(
            full: Boolean = true,
            complete: Boolean = true,
            scoped: Boolean = false,
            shrinkWarning: String? = null,
            reapsDeferred: Boolean = false,
        ): Pair<Boolean, String?> {
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")
            db.setSyncState("last_gather_full", full.toString())
            db.setSyncState("pending_cursor_complete", complete.toString())
            return gather.settleRecoveryLatch(scoped, shrinkWarning, reapsDeferred) to latch()
        }

        assertEquals(true to "false", settle(), "a complete, full, unscoped listing that the guards accept")
        assertEquals(false to "true", settle(full = false), "an incremental pass proves nothing about absent paths")
        assertEquals(false to "true", settle(complete = false))
        assertEquals(false to "true", settle(scoped = true), "a listing of a part of the drive is no inventory of it")
        assertEquals(false to "true", settle(shrinkWarning = "looks partial"))
        assertEquals(false to "true", settle(reapsDeferred = true))
    }

    // ---- unreachable is not deleted -----------------------------------------------------------------------------------------------

    // /D (gone on the server) holds c.txt and the folder /D/S with e.txt; x.txt and k.txt are beside it.
    private val tree = listOf(folder("D", "/D"), file("c", "/D/c.txt"), folder("S", "/D/S"), file("e", "/D/S/e.txt"), file("x", "/x.txt"), file("k", "/k.txt"))

    @Test
    fun `items the provider reports unreachable keep their rows through a complete listing, and a vanished item is still reaped`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            // c.txt is on disk and carries the marks of a local edit.
            db.upsertEntry(checkNotNull(db.getEntry("/D/c.txt")).copy(isHydrated = true, localMtime = 1234L, localSize = 1L))
            val before = listOf("/D", "/D/c.txt", "/D/S", "/D/S/e.txt").associateWith { db.getEntry(it) }
            db.setSyncState(RemoteGather.INCOMPLETE_ENUMERATION_KEY, "true")
            invalidations.clear()

            // The listing no longer places c, S and e (the folder above them is gone for good) and the pass is complete.
            // x.txt really disappeared.
            provider.items = listOf(file("k", "/k.txt"))
            provider.unreachable = setOf("c", "S", "e")
            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertTrue(result.complete)
            assertEquals("true", db.getSyncState("pending_cursor_complete"))
            assertEquals("c2", db.getSyncState("delta_cursor"), "the cursor advanced")
            assertEquals("false", latch(), "and the recovery ended")
            for ((path, row) in before) assertEquals(row, db.getEntry(path), "$path is exactly as it was: not reaped, not flagged, not touched")
            assertNull(db.getEntry("/x.txt"), "an item that really disappeared is reaped as before")
            assertEquals(1, result.reaped)
            assertEquals(3, enumeration.status().unreachableItems, "and the figure is told")
            assertTrue(invalidations.none { (paths, _) -> paths.any { it.startsWith("/D") } }, "the view is told nothing about them: $invalidations")
        }

    @Test
    fun `without the unreachable ids the same listing reaps those rows`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(file("k", "/k.txt"))
            val result = enumeration.enumerate(reset = false)

            assertEquals(5, result.reaped, "D, c, S, e and x: the control for what the unreachable ids change")
            assertNull(db.getEntry("/D/c.txt"))
            assertNull(db.getEntry("/D"))
            assertNull(enumeration.status().unreachableItems, "absent when there are none")
        }

    @Test
    fun `the folder above an unreachable row is held with it, and swept once nothing is listed below it`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)

            provider.items = listOf(file("k", "/k.txt"))
            provider.unreachable = setOf("e")
            enumeration.enumerate(reset = false)

            assertNotNull(db.getEntry("/D/S/e.txt"))
            assertNotNull(db.getEntry("/D/S"), "the folder e hangs below")
            assertNotNull(db.getEntry("/D"), "and the one above it: the tree in state.db stays whole")
            assertNull(db.getEntry("/D/c.txt"), "c is neither listed nor unreachable: it is gone")

            provider.unreachable = emptySet()
            enumeration.enumerate(reset = false)

            assertNull(db.getEntry("/D/S/e.txt"), "the server stopped listing it")
            assertNull(db.getEntry("/D/S"))
            assertNull(db.getEntry("/D"))
        }

    @Test
    fun `an incomplete pass leaves every row and the latch alone however many ids it names`() =
        runTest {
            provider.items = tree
            val enumeration = enumeration()
            enumeration.enumerate(reset = false)
            val before = tree.associate { it.path to db.getEntry(it.path) }

            provider.items = listOf(file("k", "/k.txt"))
            provider.complete = false
            provider.unreachable = setOf("c")
            enumeration.enumerate(reset = false)

            assertEquals("true", latch())
            assertEquals("false", db.getSyncState("pending_cursor_complete"))
            for ((path, row) in before) {
                if (path != "/k.txt") assertEquals(row, db.getEntry(path), "$path: an incomplete pass reaps nothing")
            }
        }

    // ---- #926: a recovery that cannot succeed is bounded: the same dropped set twice is accepted --------------------------------

    private val a get() = file("1", "/a.txt")
    private val b get() = file("2", "/b.txt")
    private val c get() = file("3", "/c.txt")

    // Three tracked files, then an incomplete incremental pass that sets the latch: the next listing is a recovery listing.
    private suspend fun RemoteEnumeration.armedRecovery() {
        provider.fullListing = false
        provider.items = listOf(a, b, c)
        enumerate(reset = false)
        provider.items = listOf(a)
        provider.complete = false
        enumerate(reset = false)
        assertEquals("true", latch(), "the incomplete incremental pass sets the latch")
        assertEquals(listOf<String?>(null, "c1"), provider.cursors)
    }

    private fun dropped(): Int? = db.getSyncState(RemoteGather.DROPPED_ITEMS_KEY)?.toIntOrNull()

    @Test
    fun `two full listings that end incomplete with the same dropped set are accepted, and the pass after continues from the cursor`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()

            val first = enumeration.enumerate(reset = false)
            assertEquals("true", latch(), "the first listing with this dropped set is not enough")
            assertTrue(first.recoveryPending, "the next pass would list the drive again: the poller backs off on it")
            assertEquals(2, dropped())
            assertEquals(2, enumeration.status().droppedItems)

            val second = enumeration.enumerate(reset = false)
            assertEquals("false", latch(), "the same dropped set twice ends the recovery")
            assertFalse(second.recoveryPending)
            assertFalse(second.complete, "the pass itself stays incomplete: the sweep did not run")
            assertEquals(2, enumeration.status().droppedItems, "the count stays in the status")
            assertNotNull(db.getEntry("/b.txt"), "the rows of the dropped items stay as they are")
            assertNotNull(db.getEntry("/c.txt"))

            provider.complete = true
            enumeration.enumerate(reset = false)
            assertEquals(listOf<String?>(null, "c1", null, null, "c4"), provider.cursors, "the pass after the acceptance is incremental")
        }

    @Test
    fun `a dropped set that differs from the last one is not accepted until it repeats`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()

            enumeration.enumerate(reset = false) // dropped: b, c
            provider.items = listOf(a, b)
            enumeration.enumerate(reset = false) // dropped: c
            assertEquals("true", latch(), "another set is a new observation, not a repeat")
            assertEquals(1, dropped())

            enumeration.enumerate(reset = false) // dropped: c, again
            assertEquals("false", latch())
        }

    @Test
    fun `a complete full listing forgets the dropped set, so the next incomplete one starts counting again`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()
            enumeration.enumerate(reset = false) // dropped: b, c
            assertEquals(2, dropped())

            provider.items = listOf(a, b, c)
            provider.complete = true
            enumeration.enumerate(reset = false)
            assertEquals("false", latch())
            assertNull(enumeration.status().droppedItems, "a complete listing leaves nothing dropped")

            provider.items = listOf(a)
            provider.complete = false
            enumeration.enumerate(reset = true) // a full listing again, dropped: b, c
            assertEquals("true", latch(), "the earlier observation was forgotten: one listing is not enough")
            enumeration.enumerate(reset = false)
            assertEquals("false", latch())
        }

    @Test
    fun `a row written after the listing began is not part of the dropped set`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()
            // An upload through the mount lands while each listing runs: the listing cannot have seen it.
            provider.onDelta = {
                db.upsertEntry(
                    org.krost.unidrive.sync.model.SyncEntry(
                        path = "/up.txt", remoteId = "up", remoteHash = null, remoteSize = 1, remoteModified = null, localMtime = null,
                        localSize = null, isFolder = false, isPinned = false, isHydrated = false, lastSynced = Instant.now().plusSeconds(60),
                    ),
                )
            }

            enumeration.enumerate(reset = false)
            assertEquals(2, dropped(), "b and c, not the upload")
            enumeration.enumerate(reset = false)
            assertEquals("false", latch(), "the set is the same twice because the upload is left out")
        }

    @Test
    fun `an incomplete listing that misses nothing tracked is never accepted, however often it repeats`() =
        runTest {
            provider.fullListing = false
            provider.items = listOf(a)
            provider.complete = false
            val enumeration = enumeration()

            repeat(3) { enumeration.enumerate(reset = false) }

            assertEquals("true", latch(), "there is no dropped set to compare: the first scan stays in recovery")
            assertNull(enumeration.status().droppedItems)
            assertEquals(listOf<String?>(null, null, null), provider.cursors)
        }

    @Test
    fun `a pass says it lists again because of the recovery while it runs, not before and not after`() =
        runTest {
            val enumeration = enumeration()
            enumeration.armedRecovery()
            val during = mutableListOf<Boolean>()
            provider.onDelta = { during += enumeration.status().recovery }

            assertFalse(enumeration.status().recovery, "before")
            enumeration.enumerate(reset = false)
            assertEquals(listOf(true), during)
            assertFalse(enumeration.status().recovery, "after")

            provider.complete = true
            provider.items = listOf(a, b, c)
            enumeration.enumerate(reset = false) // still latched: a recovery pass, and a complete one
            enumeration.enumerate(reset = false) // the latch is gone: incremental
            assertEquals(listOf(true, true, false), during)
        }

    // One page per listing; every cursor names the listing that produced it.
    private class ListingProvider : CloudProvider {
        var items: List<CloudItem> = emptyList()
        var failure: ProviderException? = null

        // The completeness the provider reports, and the ids it could not place (DeltaPage.unreachableIds).
        var complete = true
        var unreachable: Set<String> = emptySet()

        // True: every delta lists the whole tree (localfs). False: a delta that is handed a cursor is incremental.
        var fullListing = true

        // The cursor of every delta() call, to tell a full listing (null) from an incremental one.
        val cursors = mutableListOf<String?>()

        // Runs inside every delta() call: what a test observes or does while a listing is in flight.
        var onDelta: (() -> Unit)? = null
        private var listings = 0

        override val id = "test"
        override val displayName = "test"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            failure?.let { throw it }
            onDelta?.invoke()
            listings++
            cursors += cursor
            // A full listing whatever the cursor unless fullListing is off: deltaIsFullListing below.
            return DeltaPage(items = items, cursor = "c$listings", hasMore = false, complete = complete, unreachableIds = unreachable)
        }

        override val deltaIsFullListing: Boolean get() = fullListing

        override suspend fun listChildren(path: String): List<CloudItem> = error("not used")

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun quota(): QuotaInfo = error("not used")
    }
}
