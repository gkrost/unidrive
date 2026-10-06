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
// RemoteGatherCollisionTest and MountOperationsParityTest in :app:sync.
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

    private fun gather() =
        RemoteGather(
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

    private fun enumeration(gather: RemoteGather = gather()) =
        RemoteEnumeration(
            gather = gather,
            db = db,
            tracker = EnumerationTracker(),
            reapGuards = RemoteEnumeration.ReapGuards(uploadInFlight = { it in inFlight }, cachePathOf = ::cachePath),
            log = LoggerFactory.getLogger(RemoteEnumerationTest::class.java),
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

    // One page per listing; every cursor names the listing that produced it.
    private class ListingProvider : CloudProvider {
        var items: List<CloudItem> = emptyList()
        var failure: ProviderException? = null
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
            listings++
            // A full listing whatever the cursor: deltaIsFullListing below.
            return DeltaPage(items = items, cursor = "c$listings", hasMore = false)
        }

        override val deltaIsFullListing: Boolean get() = true

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
