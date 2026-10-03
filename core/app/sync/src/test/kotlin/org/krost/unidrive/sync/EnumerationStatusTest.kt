package org.krost.unidrive.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What a client learns about the enumeration from SyncEngine.enumerationStatus(): the engine wiring of the tracker,
// from the progress a provider reports to the totals a complete run leaves for the next one.
class EnumerationStatusTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: ReportingProvider
    private lateinit var engine: SyncEngine
    private var now = 5_000_000L

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-enumstatus-root")
        dbDir = Files.createTempDirectory("ud-enumstatus-db")
        cacheRoot = Files.createTempDirectory("ud-enumstatus-cache")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = ReportingProvider()
        engine = newEngine()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun newEngine(tracker: EnumerationTracker = EnumerationTracker(clock = { now })) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            reporter = ProgressReporter.Silent,
            cacheRoot = cacheRoot,
            cacheKey = "enum-status",
            enumerationTracker = tracker,
        )

    private fun item(
        path: String,
        isFolder: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = if (isFolder) 0L else 3L,
        isFolder = isFolder,
        modified = Instant.now(),
        created = Instant.now(),
        hash = if (isFolder) null else "h-$path",
        mimeType = null,
    )

    // ---- a running enumeration -----------------------------------------------------------------------------------------

    @Test
    fun `a running enumeration shows what the provider reports, the saving phase, and then the end`() =
        runTest {
            provider.remote = listOf(item("/a.txt"), item("/dir", isFolder = true), item("/dir/b.txt"))
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            provider.duringDelta = { _, _, ctx ->
                ctx?.onProgress?.invoke(
                    ScanProgress(items = 1_234, foldersDone = 10, foldersKnown = 40, foldersSkipped = 1, listing = ScanProgress.LISTING_TREE),
                )
                entered.complete(Unit)
                gate.await()
            }
            // Every batch the run commits (the scan checkpoint while listing, the result while saving).
            val seenInBatches = mutableListOf<EnumerationStatus>()
            db.batchCommitHook = { seenInBatches += engine.enumerationStatus() }

            val run = async { engine.enumerateRemoteIntoState(reset = false) }
            entered.await()
            now += 7_000
            val listing = engine.enumerationStatus()
            gate.complete(Unit)
            val result = run.await()
            db.batchCommitHook = null

            assertEquals(EnumerationStatus.State.RUNNING, listing.state)
            assertEquals(EnumerationStatus.Phase.LISTING, listing.phase)
            assertEquals(1, listing.attempt)
            assertTrue(listing.first, "no enumeration has completed: the cursor is empty")
            assertEquals(1_234, listing.items)
            assertEquals(10, listing.foldersDone)
            assertEquals(40, listing.foldersKnown)
            assertEquals(1, listing.foldersSkipped)
            assertEquals(ScanProgress.LISTING_TREE, listing.listing)
            assertEquals(now - 7_000, listing.startedAtMs)
            assertEquals(7_000L, listing.elapsedMs)
            assertNull(listing.lastSuccessAtMs)

            val saving = assertNotNull(seenInBatches.firstOrNull { it.phase == EnumerationStatus.Phase.SAVING }, "state.db was written")
            assertEquals(EnumerationStatus.State.RUNNING, saving.state)
            assertEquals(3, saving.items, "the items going to state.db, not the provider's running count")
            assertNull(saving.ratePerS)
            assertNull(saving.etaS)

            assertTrue(result.ok)
            val done = engine.enumerationStatus()
            assertEquals(EnumerationStatus.State.IDLE, done.state)
            assertEquals(0, done.attempt)
            assertFalse(done.first, "the cursor was promoted: the view is complete")
            assertEquals(now, done.lastSuccessAtMs)
            assertNull(done.items)
        }

    @Test
    fun `a provider that never reports progress still shows the items of the page callback`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            provider.duringDelta = { _, onPageProgress, _ ->
                onPageProgress?.invoke(5_000)
                entered.complete(Unit)
                gate.await()
            }

            val run = async { engine.enumerateRemoteIntoState(reset = false) }
            entered.await()
            val s = engine.enumerationStatus()
            gate.complete(Unit)
            run.await()

            assertEquals(EnumerationStatus.State.RUNNING, s.state)
            assertEquals(5_000, s.items)
            assertNull(s.listing, "the page callback names no listing")
            assertNull(s.foldersDone)
            assertNull(s.foldersKnown)
            assertNull(s.foldersSkipped)
        }

    @Test
    fun `a running enumeration is answered without state_db, which the saving batch holds`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            provider.duringDelta = { _, _, _ ->
                entered.complete(Unit)
                gate.await()
            }
            val run = async { engine.enumerateRemoteIntoState(reset = false) }
            entered.await()

            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = thread { synchronized(db) { locked.countDown(); release.await(30, TimeUnit.SECONDS) } }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            var status: EnumerationStatus? = null
            val asker = thread { status = engine.enumerationStatus() }
            asker.join(5_000)
            val answered = !asker.isAlive
            release.countDown()
            holder.join()
            asker.join()
            gate.complete(Unit)
            run.await()

            assertTrue(answered, "a status request must not wait for the database while an enumeration runs")
            assertEquals(EnumerationStatus.State.RUNNING, status?.state)
        }

    @Test
    fun `an enumeration that is skipped because another one runs is no attempt`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            provider.duringDelta = { _, _, _ ->
                entered.complete(Unit)
                gate.await()
            }
            val first = async { engine.enumerateRemoteIntoState(reset = false) }
            entered.await()

            val second = engine.enumerateRemoteIntoState(reset = false)

            assertTrue(second.skipped)
            assertEquals(1, engine.enumerationStatus().attempt)
            assertEquals(EnumerationStatus.State.RUNNING, engine.enumerationStatus().state)
            gate.complete(Unit)
            first.await()
            assertEquals(0, engine.enumerationStatus().attempt)
        }

    // ---- how an enumeration ends ---------------------------------------------------------------------------------------

    @Test
    fun `failed attempts are counted, carry a reason without paths, and a success starts over`() =
        runTest {
            provider.failure = ProviderException("cannot list /Secret/Plans: connection closed")

            val r1 = engine.enumerateRemoteIntoState(reset = false)
            val afterFirst = engine.enumerationStatus()
            val r2 = engine.enumerateRemoteIntoState(reset = false)
            val afterSecond = engine.enumerationStatus()
            provider.failure = null
            val r3 = engine.enumerateRemoteIntoState(reset = false)
            val afterSuccess = engine.enumerationStatus()

            assertFalse(r1.ok)
            assertFalse(r2.ok)
            assertTrue(r3.ok)
            assertEquals(EnumerationStatus.State.FAILED, afterFirst.state)
            assertEquals(1, afterFirst.attempt)
            assertEquals("cannot list <path>: connection closed", afterFirst.lastError)
            assertTrue(afterFirst.first, "nothing was ever enumerated")
            assertEquals(2, afterSecond.attempt)
            assertEquals(EnumerationStatus.State.IDLE, afterSuccess.state)
            assertEquals(0, afterSuccess.attempt)
            assertNull(afterSuccess.lastError)
            assertFalse(afterSuccess.first)
        }

    @Test
    fun `a failure that is not a provider failure is recorded and still thrown`() =
        runTest {
            provider.failure = IllegalStateException("state is gone")

            assertFailsWith<IllegalStateException> { engine.enumerateRemoteIntoState(reset = false) }

            val s = engine.enumerationStatus()
            assertEquals(EnumerationStatus.State.FAILED, s.state)
            assertEquals(1, s.attempt)
            assertEquals("state is gone", s.lastError)

            provider.failure = null
            val next = engine.enumerateRemoteIntoState(reset = false)
            assertTrue(next.ok && !next.skipped, "the engine accepts the next enumeration")
        }

    @Test
    fun `a cancelled enumeration is neither a failure nor a success`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            provider.duringDelta = { _, _, _ ->
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            val run = async { engine.enumerateRemoteIntoState(reset = false) }
            entered.await()

            run.cancelAndJoin()

            val s = engine.enumerationStatus()
            assertEquals(EnumerationStatus.State.IDLE, s.state)
            assertNull(s.lastError)
            assertNull(s.lastSuccessAtMs)
            assertEquals(1, s.attempt, "the attempt happened")
        }

    // ---- what a restart and a reset leave visible -----------------------------------------------------------------------

    @Test
    fun `an engine on an established database does not call the next enumeration the first, and knows the last scan`() =
        runTest {
            provider.remote = listOf(item("/a.txt"))
            engine.enumerateRemoteIntoState(reset = false)
            val scannedAt = Instant.parse(assertNotNull(db.getSyncState("last_full_scan"))).toEpochMilli()

            val restarted = newEngine(EnumerationTracker())
            val s = restarted.enumerationStatus()

            assertEquals(EnumerationStatus.State.IDLE, s.state)
            assertFalse(s.first, "the delta cursor is set")
            assertEquals(scannedAt, s.lastSuccessAtMs)
        }

    @Test
    fun `a reset makes the view incomplete again while it runs`() =
        runTest {
            provider.remote = listOf(item("/a.txt"))
            engine.enumerateRemoteIntoState(reset = false)
            var during: EnumerationStatus? = null
            provider.duringDelta = { _, _, _ -> during = engine.enumerationStatus() }

            engine.enumerateRemoteIntoState(reset = true)

            assertTrue(assertNotNull(during).first, "a reset clears the delta cursor")
            assertFalse(engine.enumerationStatus().first)
        }

    // ---- the totals a complete full enumeration leaves for the next one -------------------------------------------------

    @Test
    fun `a complete full enumeration records its totals and measures the next full one against them`() =
        runTest {
            provider.remote = listOf(item("/a.txt"), item("/dir", isFolder = true), item("/dir/b.txt"))
            provider.duringDelta = { _, _, _ -> now += 20_000 }

            val first = engine.enumerateRemoteIntoState(reset = false)

            assertTrue(first.ok && first.complete)
            assertEquals("3", db.getSyncState(SyncEngine.FULL_ENUMERATION_ITEMS_KEY))
            assertEquals("1", db.getSyncState(SyncEngine.FULL_ENUMERATION_FOLDERS_KEY))
            assertEquals("20000", db.getSyncState(SyncEngine.FULL_ENUMERATION_MS_KEY), "the duration of the listing")

            // The next full listing (a reset) has 20 s of data and one item of the three the last one had.
            var during: EnumerationStatus? = null
            provider.duringDelta = { _, _, ctx ->
                now += 20_000
                ctx?.onProgress?.invoke(ScanProgress(items = 1, listing = ScanProgress.LISTING_ACCOUNT))
                during = engine.enumerationStatus()
            }
            engine.enumerateRemoteIntoState(reset = true)

            val s = assertNotNull(during)
            assertEquals(0.05, s.ratePerS)
            assertEquals(40L, s.etaS, "two items left at one item per 20 s")
            assertEquals(EnumerationStatus.EtaKind.ESTIMATE, s.etaKind)
        }

    @Test
    fun `an incremental enumeration neither records totals nor inherits the estimate of a full one`() =
        runTest {
            provider.remote = listOf(item("/a.txt"), item("/dir", isFolder = true), item("/dir/b.txt"))
            engine.enumerateRemoteIntoState(reset = false)
            assertEquals("3", db.getSyncState(SyncEngine.FULL_ENUMERATION_ITEMS_KEY))

            provider.remote = listOf(item("/c.txt"))
            var during: EnumerationStatus? = null
            provider.duringDelta = { _, _, ctx ->
                now += 20_000
                ctx?.onProgress?.invoke(ScanProgress(items = 1, listing = ScanProgress.LISTING_ACCOUNT))
                during = engine.enumerationStatus()
            }
            engine.enumerateRemoteIntoState(reset = false)

            assertEquals("3", db.getSyncState(SyncEngine.FULL_ENUMERATION_ITEMS_KEY), "a delta of one item is no total")
            val s = assertNotNull(during)
            assertFalse(s.first)
            assertNull(s.etaS, "a delta is not measured against the whole drive")
        }

    @Test
    fun `an incomplete full enumeration does not become the previous complete one`() =
        runTest {
            provider.remote = listOf(item("/a.txt"))
            provider.complete = false

            val r = engine.enumerateRemoteIntoState(reset = false)

            assertTrue(r.ok)
            assertFalse(r.complete)
            assertNull(db.getSyncState(SyncEngine.FULL_ENUMERATION_ITEMS_KEY))
            assertEquals(EnumerationStatus.State.IDLE, engine.enumerationStatus().state, "it still ended without a failure")
        }

    // A provider whose delta() lets a test report progress, hold the run, or fail it.
    class ReportingProvider : CloudProvider {
        override val id = "reporting"
        override val displayName = "Reporting"
        override var isAuthenticated = true

        var remote: List<CloudItem> = emptyList()
        var failure: Exception? = null
        var complete = true
        var duringDelta: suspend (cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: ScanContext?) -> Unit =
            { _, _, _ -> }
        private var cursorSeq = 0

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            duringDelta(cursor, onPageProgress, scanContext)
            failure?.let { throw it }
            cursorSeq++
            return DeltaPage(items = remote, cursor = "cursor-$cursorSeq", hasMore = false, complete = complete)
        }

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

        override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)
    }
}
