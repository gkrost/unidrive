package org.krost.unidrive.engine

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Items a provider reports as trashed are shown under the trash namespace, and an item that an earlier version stored at
 * its original place arrives there with the SAME id: the engine moves its row, with its hydration state and its cache
 * copy, instead of deleting it and creating another.
 *
 * Live, a first scan of one account listed 5,536 files and 266 folders below trashed folders at their original paths,
 * among the live items. The rows exist; this is what happens to them, and to a file the user edited, when the next
 * full listing puts them where they belong. The provider here fails every write it is asked for, so a test that passes
 * also shows that nothing was sent to the cloud.
 */
class RemoteEnumerationTrashTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = ListingProvider()
    private val trash = TrashNamespace(ignoreCase = false)
    private val moves = mutableListOf<RemoteGather.RemoteMerge.Move>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-trash-enumeration")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun cachePath(path: String): Path = tmp.resolve("cache").resolve(path.removePrefix("/"))

    private val reapGuards = RemoteEnumeration.ReapGuards(cachePathOf = ::cachePath)

    private fun guard() =
        RemoteOperationGuard(
            standingScope = emptyList(),
            syncPaths = emptyList(),
            excludePatterns = emptyList(),
            maxConcurrentTransfers = 2,
            matchesGlob = { _, _ -> false },
            trash = trash,
        )

    private val enumeration: RemoteEnumeration by lazy {
        RemoteEnumeration(
            gather =
                RemoteGather(
                    provider = provider,
                    db = db,
                    guard = guard(),
                    options = RemoteGather.Options(providerId = "test"),
                    listener =
                        object : RemoteGather.Listener {
                            override fun onViewInvalidated(
                                changedPaths: Set<String>,
                                full: Boolean,
                                moved: List<RemoteGather.RemoteMerge.Move>,
                            ) {
                                moves += moved
                            }
                        },
                    log = LoggerFactory.getLogger(RemoteEnumerationTrashTest::class.java),
                ),
            db = db,
            tracker = EnumerationTracker(),
            reapGuards = reapGuards,
            log = LoggerFactory.getLogger(RemoteEnumerationTrashTest::class.java),
        )
    }

    private fun item(
        id: String,
        path: String,
        isFolder: Boolean = false,
        trashed: Boolean = false,
        parentId: String? = null,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = if (isFolder) 0 else 7,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = null,
        hash = if (isFolder) null else "hash-$id",
        mimeType = null,
        parentId = parentId,
        trashed = trashed,
    )

    // What an earlier version stored: the live items, the trashed ones among them at their original places, no folder for them.
    private fun listedBefore() =
        listOf(
            item("keep", "/Live/keep.txt"),
            item("ph", "/OldStuff/placeholder.txt", parentId = "t1"),
            item("ed", "/OldStuff/edited.txt", parentId = "t1"),
        )

    // What the provider says now: the same ids, flagged, and the trashed folder that the items hang below.
    private fun listedNow() =
        listOf(
            item("keep", "/Live/keep.txt"),
            item("t1", "/OldStuff", isFolder = true, trashed = true, parentId = "drive-root"),
            item("ph", "/OldStuff/placeholder.txt", trashed = true, parentId = "t1"),
            item("ed", "/OldStuff/edited.txt", trashed = true, parentId = "t1"),
        )

    /** The file the user edited in the mount, as the hydration layer leaves it: hydrated, its bytes in the cache, newer than the row. */
    private fun editInTheCache(
        path: String,
        text: String,
    ) {
        val row = assertNotNull(db.getEntry(path))
        db.upsertEntry(row.copy(isHydrated = true, localMtime = 1_000, localSize = 4, cacheBacked = true))
        val cache = cachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, text)
        Files.setLastModifiedTime(cache, FileTime.from(Instant.now().plusSeconds(60)))
    }

    private fun alive(): List<SyncEntry> = db.getAllEntries().sortedBy { it.path }

    // ---- the first listing ---------------------------------------------------------------------------------------

    @Test
    fun `a first listing puts the trashed items under the root, with the root as a folder row of its own key`() =
        runTest {
            provider.items = listedNow()

            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(
                listOf("/Live/keep.txt", "/Papierkorb", "/Papierkorb/OldStuff", "/Papierkorb/OldStuff/edited.txt", "/Papierkorb/OldStuff/placeholder.txt"),
                alive().map { it.path },
            )
            val root = assertNotNull(db.getEntry("/Papierkorb"))
            assertEquals(TrashNamespace.ROOT_ID, root.remoteId)
            assertTrue(root.isFolder)
            assertEquals("/Papierkorb", trash.rootPath())
            assertNull(db.getEntry("/OldStuff/edited.txt"), "not among the live items")
        }

    @Test
    fun `a listing with nothing in the trash has no root`() =
        runTest {
            provider.items = listOf(item("keep", "/Live/keep.txt"))

            enumeration.enumerate(reset = false)

            assertNull(db.getEntry("/Papierkorb"))
            assertNull(trash.rootPath())
        }

    // ---- the migration -------------------------------------------------------------------------------------------

    @Test
    fun `rows an earlier version stored at the original path move under the root with the same id, never delete and create`() =
        runTest {
            provider.items = listedBefore()
            enumeration.enumerate(reset = false)
            val before = alive().associateBy { it.remoteId }
            assertEquals("/OldStuff/placeholder.txt", before.getValue("ph").path, "precondition: stored at the original place")

            provider.items = listedNow()
            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok && result.complete)
            assertEquals(0, result.reaped, "a row that moved is not a row that is gone")
            val after = alive().associateBy { it.remoteId }
            assertEquals("/Papierkorb/OldStuff/placeholder.txt", after.getValue("ph").path)
            assertEquals("/Papierkorb/OldStuff/edited.txt", after.getValue("ed").path)
            assertEquals("/Live/keep.txt", after.getValue("keep").path, "a live item stays")
            assertEquals(setOf("keep", "t1", "ph", "ed", TrashNamespace.ROOT_ID), after.keys, "one row per id, no duplicate, nothing left behind")
            assertNull(db.getEntry("/OldStuff/placeholder.txt"))
            assertNull(db.getEntry("/OldStuff/edited.txt"))
            assertEquals(
                setOf(
                    RemoteGather.RemoteMerge.Move("/OldStuff/placeholder.txt", "/Papierkorb/OldStuff/placeholder.txt"),
                    RemoteGather.RemoteMerge.Move("/OldStuff/edited.txt", "/Papierkorb/OldStuff/edited.txt"),
                ),
                moves.toSet(),
                "the view is told they moved, so a client moves the placeholder instead of dropping and re-creating it",
            )
        }

    @Test
    fun `an untouched placeholder moves as a placeholder, with no cache copy made or lost`() =
        runTest {
            provider.items = listedBefore()
            enumeration.enumerate(reset = false)

            provider.items = listedNow()
            enumeration.enumerate(reset = false)

            val row = assertNotNull(db.getEntryByRemoteId("ph"))
            assertEquals("/Papierkorb/OldStuff/placeholder.txt", row.path)
            assertFalse(row.isHydrated, "still a placeholder")
            assertEquals(7, row.remoteSize)
            assertEquals("hash-ph", row.remoteHash)
            assertFalse(Files.exists(cachePath("/OldStuff/placeholder.txt")))
            assertFalse(Files.exists(cachePath("/Papierkorb/OldStuff/placeholder.txt")))
        }

    @Test
    fun `a file the user modified keeps its bytes, its hydration state and its claim to an upload at the new path`() =
        runTest {
            provider.items = listedBefore()
            enumeration.enumerate(reset = false)
            editInTheCache("/OldStuff/edited.txt", "my edit, not uploaded yet")

            provider.items = listedNow()
            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(0, result.reaped)
            val row = assertNotNull(db.getEntryByRemoteId("ed"))
            assertEquals("/Papierkorb/OldStuff/edited.txt", row.path)
            assertTrue(row.isHydrated, "the row keeps its hydration state")
            assertEquals(1_000L, row.localMtime, "and its baseline, so the edit still reads as newer than it")
            val moved = cachePath("/Papierkorb/OldStuff/edited.txt")
            assertTrue(Files.exists(moved), "the cache copy followed the row")
            assertEquals("my edit, not uploaded yet", Files.readString(moved), "the user's bytes are intact")
            assertFalse(Files.exists(cachePath("/OldStuff/edited.txt")), "no orphan is left under the old path")
            assertTrue(reapGuards.holdsUnsyncedEdit(row.path, row), "the edit is still known as owed, at its new path: nothing may reap it")
        }

    @Test
    fun `a cache copy already at the new path is never replaced`() =
        runTest {
            provider.items = listedBefore()
            enumeration.enumerate(reset = false)
            editInTheCache("/OldStuff/edited.txt", "from the old place")
            val there = cachePath("/Papierkorb/OldStuff/edited.txt")
            Files.createDirectories(there.parent)
            Files.writeString(there, "already here")

            provider.items = listedNow()
            enumeration.enumerate(reset = false)

            assertEquals("already here", Files.readString(there), "a copy at the destination is kept")
            assertEquals("from the old place", Files.readString(cachePath("/OldStuff/edited.txt")), "and the other is not deleted either")
        }

    @Test
    fun `an upload that names the migrated item is refused whatever place it carries, and nothing reaches the provider`() =
        runTest {
            provider.items = listedBefore()
            enumeration.enumerate(reset = false)
            editInTheCache("/OldStuff/edited.txt", "my edit")
            provider.items = listedNow()
            enumeration.enumerate(reset = false)
            val guarded = ReadOnlyTrashProvider(provider, trash)
            val local = Files.createTempFile("edited", ".txt")

            // A queued upload still carries the old place and the id of the row it was queued for.
            assertFailsWith<TrashReadOnlyException> { guarded.upload(local, "/OldStuff/edited.txt", existingRemoteId = "ed") }
            assertFailsWith<TrashReadOnlyException> { guarded.upload(local, "/Papierkorb/OldStuff/edited.txt", existingRemoteId = null) }
            assertFailsWith<TrashReadOnlyException> { guarded.deleteById("ed", "/OldStuff/edited.txt") }
            assertFailsWith<TrashReadOnlyException> { guarded.moveById("ed", "/OldStuff/edited.txt", "/Elsewhere/edited.txt") }
            assertEquals(0, provider.writes, "the provider was never asked to write")
        }

    @Test
    fun `the next listing changes nothing and a settled trash is quiet`() =
        runTest {
            provider.items = listedBefore()
            enumeration.enumerate(reset = false)
            provider.items = listedNow()
            enumeration.enumerate(reset = false)
            moves.clear()

            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals(0, result.upserted, "the root is re-delivered every time and changes nothing")
            assertEquals(0, result.reaped)
            assertEquals(emptyList(), moves)
        }

    // ---- leaving the trash ---------------------------------------------------------------------------------------

    @Test
    fun `an item that is restored moves back with the same id and its cache copy, and the empty trash has no root`() =
        runTest {
            provider.items = listedBefore()
            enumeration.enumerate(reset = false)
            editInTheCache("/OldStuff/edited.txt", "edit")
            provider.items = listedNow()
            enumeration.enumerate(reset = false)
            assertEquals("/Papierkorb", trash.rootPath())

            provider.items = listedBefore() + item("t1", "/OldStuff", isFolder = true, parentId = "drive-root")
            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertEquals("/OldStuff/edited.txt", db.getEntryByRemoteId("ed")?.path)
            assertEquals("/OldStuff", db.getEntryByRemoteId("t1")?.path)
            assertNull(db.getEntry("/Papierkorb"), "the root is reaped with the last item gone from the trash")
            assertNull(trash.rootPath(), "and the name is free again")
            assertEquals("edit", Files.readString(cachePath("/OldStuff/edited.txt")), "the cache copy went back with the row")
        }

    // One page per listing, a full listing whatever the cursor. Every write fails the test.
    private class ListingProvider : CloudProvider {
        var items: List<CloudItem> = emptyList()
        var writes = 0
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
            listings++
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
        ): CloudItem = fail()

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = fail()

        override suspend fun createFolder(path: String): CloudItem = fail()

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = fail()

        override suspend fun quota(): QuotaInfo = error("not used")

        private fun fail(): Nothing {
            writes++
            error("a write reached the provider")
        }
    }
}
