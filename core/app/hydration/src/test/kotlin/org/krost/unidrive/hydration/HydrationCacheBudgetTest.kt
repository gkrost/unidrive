package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #450: the hydration cache has a per-profile budget and an eviction pass. The rules that matter most are
 * the ones that never evict: an open handle, a queued or in-flight upload (#301, #318), a row whose upload
 * is pending or failed (#136), a copy that was modified since it was recorded, a file no row owns. After
 * those, least recently used first. (#560 U6 retired the sync-root-copy ordering: every cache copy is the
 * row's only local file.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationCacheBudgetTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
        val syncRoot: Path,
    )

    private fun freshEnv(): Env {
        val provider = MemProvider()
        val db = StateDatabase(Files.createTempDirectory("ud-450-db").resolve("state.db"))
        db.initialize()
        val syncRoot = Files.createTempDirectory("ud-450-root")
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                cacheRoot = Files.createTempDirectory("ud-450-cache"),
                cacheKey = "ud-450-isolated-profile",
            )
        return Env(provider, db, engine, syncRoot)
    }

    private fun bytesOf(
        seed: Int,
        size: Int = 1000,
    ) = ByteArray(size) { (seed + it).toByte() }

    private fun Env.hydration(
        scope: TestScope,
        budget: Long,
        graceMs: Long = 0,
        delayMs: Long = 0,
    ) = HydrationImpl(engine, db, recoveryUploadScope = scope, cacheMaxBytes = budget, cacheAccessGraceMs = graceMs, evictionDelayMs = delayMs)

    /** Files a normal sync run put into the sync root, then read once through the mount (the cache copy stays). */
    private suspend fun Env.syncedAndRead(vararg names: String): Map<String, Path> {
        names.forEachIndexed { i, n -> provider.seed("/$n", bytesOf(i * 7 + 1)) }
        engine.syncOnce()
        return names.associateWith { engine.ensureHydrated("/$it") }
    }

    private fun age(
        file: Path,
        hoursAgo: Long,
    ) {
        // Modification and access time both: the eviction order falls back to the later of the two.
        val then = FileTime.from(Instant.now().minusSeconds(hoursAgo * 3600))
        Files.getFileAttributeView(file, java.nio.file.attribute.BasicFileAttributeView::class.java).setTimes(then, then, null)
    }

    // ── never evict ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a file with an open handle is never evicted`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("a.txt", "b.txt")
            val hydration = env.hydration(this, budget = 500)
            val opened = hydration.openForRead("conn", "h1", "/a.txt")
            assertTrue(opened is OpenResult.Ok)

            hydration.evictCache()

            assertTrue(Files.exists(cache.getValue("a.txt")), "a.txt is open: it must stay")
            assertFalse(Files.exists(cache.getValue("b.txt")), "b.txt is idle and over budget: it goes")

            hydration.closeHandle("conn", "h1")
            hydration.evictCache()
            assertFalse(Files.exists(cache.getValue("a.txt")), "closed, it is evictable")
        }

    @Test
    fun `a path with a queued or in-flight upload is never evicted, even when its copy is identical to the sync root`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("a.txt")
            val hydration = env.hydration(this, budget = 1)
            val gate = CompletableDeferred<Unit>().also { env.provider.uploadGate = it }
            // The mount saved the very bytes that are already there: identical to the sync-root copy.
            val cachePath = cache.getValue("a.txt")
            val opened = hydration.openForWrite("conn", "h-w", "/a.txt", cachePath, baseEtag = null)
            assertTrue(opened is OpenResult.Ok)
            hydration.closeHandle("conn", "h-w")
            assertTrue(hydration.hasUploadSlot("/a.txt"), "precondition: the upload is queued behind the gate")

            hydration.evictCache()
            assertTrue(Files.exists(cachePath), "the upload slot pins the cache copy")

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(hydration.hasUploadSlot("/a.txt"), "precondition: the upload finished")
            hydration.evictCache()
            assertFalse(Files.exists(cachePath), "once the upload landed the copy is evictable again")
        }

    @Test
    fun `a never-uploaded create and a failed upload are never evicted`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("failed.txt")
            val hydration = env.hydration(this, budget = 1)
            val created = hydration.create("conn", "h-c", "/new.txt")
            assertTrue(created is CreateResult.Ok)
            Files.write(created.cachePath, bytesOf(99, 2000))
            hydration.closeHandle("conn", "h-c")
            val failedRow = assertNotNull(env.db.getEntry("/failed.txt"))
            env.db.upsertEntry(failedRow.copy(lastErrorAt = Instant.now()))

            hydration.evictCache()

            assertTrue(Files.exists(created.cachePath), "the cache is the only copy of a file that never reached the cloud")
            assertTrue(Files.exists(cache.getValue("failed.txt")), "the row says the last upload failed: keep the bytes")
        }

    @Test
    fun `a cache file without a row is never evicted`() =
        runTest {
            val env = freshEnv()
            env.syncedAndRead("a.txt")
            val orphan = env.engine.resolveCachePath("/renamed-away.bin")
            Files.createDirectories(orphan.parent)
            Files.write(orphan, bytesOf(5, 5000))
            val hydration = env.hydration(this, budget = 1)

            hydration.evictCache()

            assertTrue(Files.exists(orphan), "no row owns it: its bytes may be a queued upload's only copy (#319)")
        }

    @Test
    fun `a copy modified since it was recorded is never evicted`() =
        runTest {
            val env = freshEnv()
            // Synced file read through the mount, then edited in the cache (same size, other bytes).
            val cache = env.syncedAndRead("synced.txt").getValue("synced.txt")
            Files.write(cache, bytesOf(200))
            // Mount-only file: no sync-root file, the cache copy is the row's local file, then edited.
            env.provider.seed("/mountonly.txt", bytesOf(3))
            env.db.upsertEntry(
                SyncEntry(
                    path = "/mountonly.txt",
                    remoteId = "id-/mountonly.txt",
                    remoteHash = "h-/mountonly.txt",
                    remoteSize = 1000,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            val mountOnly = env.engine.ensureHydrated("/mountonly.txt")
            assertEquals(true, env.db.getEntry("/mountonly.txt")?.cacheBacked, "precondition: the cache copy is the row's baseline")
            Files.write(mountOnly, bytesOf(201))
            Files.setLastModifiedTime(mountOnly, FileTime.from(Instant.now().plusSeconds(5)))
            val hydration = env.hydration(this, budget = 1)

            hydration.evictCache()

            assertTrue(Files.exists(cache), "differs from the sync-root file and from the recorded hash: an unsynced edit")
            assertTrue(Files.exists(mountOnly), "newer than the baseline that was recorded for it: an unsynced edit")
        }

    @Test
    fun `a file accessed within the grace window is not evicted`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("a.txt").getValue("a.txt")
            val hydration = env.hydration(this, budget = 1, graceMs = 60_000)
            hydration.openForRead("conn", "h1", "/a.txt")
            hydration.closeHandle("conn", "h1")

            hydration.evictCache()

            assertTrue(Files.exists(cache), "just served: the client may still be about to open the path")
        }

    // ── eviction ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `eviction goes oldest first and stops at the budget`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("a.txt", "b.txt", "c.txt")
            age(cache.getValue("a.txt"), 3)
            age(cache.getValue("b.txt"), 2)
            age(cache.getValue("c.txt"), 1)
            // #560 U6: the cache copy is the row's local file, so aging it means re-recording the
            // baseline — an mtime the row does not know is indistinguishable from an edit, and the
            // copy would be protected as a possible unsynced change instead of evictable.
            for ((name, path) in cache) {
                val row = assertNotNull(env.db.getEntry("/$name"))
                env.db.upsertEntry(row.copy(localMtime = Files.getLastModifiedTime(path).toMillis()))
            }
            val hydration = env.hydration(this, budget = 1000)

            val report = hydration.evictCache()

            assertFalse(Files.exists(cache.getValue("a.txt")))
            assertFalse(Files.exists(cache.getValue("b.txt")))
            assertTrue(Files.exists(cache.getValue("c.txt")), "the newest one fits the budget and stays")
            assertEquals(3000L, report.bytesBefore)
            assertEquals(1000L, report.bytesAfter)
            assertEquals(2, report.evictedFiles)
            assertEquals(1000L, hydration.cacheSizeBytes())
            // The mirror pass's sync-root files are untouched (the mount writes nothing there), and
            // the row's local copy is gone with the eviction: #560 U6 marks it not hydrated, and the
            // next open refills it from the remote.
            assertTrue(Files.exists(env.syncRoot.resolve("a.txt")))
            assertEquals(false, env.db.getEntry("/a.txt")?.isHydrated)
        }

    @Test
    fun `evicting copies marks their rows not hydrated - the cache copy is the row's only local file`() =
        runTest {
            val env = freshEnv()
            val redundant = env.syncedAndRead("synced.txt").getValue("synced.txt")
            env.provider.seed("/mountonly.txt", bytesOf(3))
            env.db.upsertEntry(
                SyncEntry(
                    path = "/mountonly.txt",
                    remoteId = "id-/mountonly.txt",
                    remoteHash = "h-/mountonly.txt",
                    remoteSize = 1000,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            val onlyLocal = env.engine.ensureHydrated("/mountonly.txt")
            val hydration = env.hydration(this, budget = 1)

            hydration.evictCache()

            assertFalse(Files.exists(redundant))
            assertFalse(Files.exists(onlyLocal))
            assertEquals(false, env.db.getEntry("/synced.txt")?.isHydrated, "the local bytes are gone: the next open refills them")
            assertEquals(false, env.db.getEntry("/mountonly.txt")?.isHydrated, "the local bytes are gone: the next open refills them")
        }

    @Test
    fun `evicting never touches a file left at the legacy sync-root path`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("a.txt").getValue("a.txt")
            // A row from before cache_backed existed, and the user has since edited the sync-root file.
            env.db.upsertEntry(assertNotNull(env.db.getEntry("/a.txt")).copy(cacheBacked = null))
            val syncFile = env.syncRoot.resolve("a.txt")
            Files.write(syncFile, bytesOf(77))
            Files.setLastModifiedTime(syncFile, FileTime.from(Instant.now().plusSeconds(10)))
            val hydration = env.hydration(this, budget = 1)

            hydration.evictCache()

            assertFalse(Files.exists(cache), "the copy equals the recorded baseline bytes: evictable")
            assertEquals(false, env.db.getEntry("/a.txt")?.isHydrated, "the cache copy was the row's local file; the next open refills it")
            assertContentEquals(bytesOf(77), Files.readAllBytes(syncFile))
        }

    @Test
    fun `no budget and a cache under budget evict nothing`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("a.txt", "b.txt")

            env.hydration(this, budget = 0).evictCache()
            env.hydration(this, budget = 10_000).evictCache()

            assertTrue(cache.values.all { Files.exists(it) })
        }

    @Test
    fun `closing a handle triggers a pass`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("a.txt").getValue("a.txt")
            val hydration = env.hydration(this, budget = 1)
            hydration.openForRead("conn", "h1", "/a.txt")
            assertTrue(Files.exists(cache))

            hydration.closeHandle("conn", "h1")
            advanceUntilIdle()

            assertFalse(Files.exists(cache), "the close scheduled an eviction pass that found the cache over budget")
        }

    @Test
    fun `the start sweep removes stale staging files and evicts what a stopped daemon left behind`() =
        runTest {
            val env = freshEnv()
            val cache = env.syncedAndRead("a.txt").getValue("a.txt")
            val staleDownload = cache.resolveSibling("big.bin.hydrating-0b6c1f5e")
            val staleCopy = cache.resolveSibling(".ud-serve-123.tmp")
            val freshDownload = cache.resolveSibling("live.bin.hydrating-77aa")
            for (f in listOf(staleDownload, staleCopy, freshDownload)) Files.write(f, bytesOf(1, 100))
            age(staleDownload, 5)
            age(staleCopy, 5)
            val hydration = env.hydration(this, budget = 500)

            hydration.sweepCache()

            assertFalse(Files.exists(staleDownload), "an interrupted download older than an hour")
            assertFalse(Files.exists(staleCopy))
            assertTrue(Files.exists(freshDownload), "a staging file of a download that may be running now")
            assertFalse(Files.exists(cache), "over budget at start: the leftover copy of a synced file goes")
            assertContentEquals(bytesOf(1), Files.readAllBytes(env.syncRoot.resolve("a.txt")), "the sync-root file is untouched")
        }
}
