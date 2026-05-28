package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the remote-shrink trust gate: a FULL enumeration that observed far fewer
 * live remote items than the tracked baseline is treated as a partial listing and
 * the sync is aborted before it can plan spurious mass uploads/deletes. If these
 * tests are removed or loosened, a silently-partial provider enumeration (flaky
 * connection, swallowed transient errors) can drive a catastrophic reconcile —
 * and --force-delete would apply it.
 */
class RemoteShrinkGuardTest {
    // --- Invariant A: a partial full enumeration is rejected. -----------------
    @Test
    fun `guard trips when a full enumeration observes under half the baseline`() {
        assertNotNull(SyncEngine.remoteShrinkWarningOrNull(17, 5917), "0.3% observed must trip")
        assertNotNull(SyncEngine.remoteShrinkWarningOrNull(2801, 5917), "47% observed must trip")
        assertNotNull(SyncEngine.remoteShrinkWarningOrNull(49, 100), "49% observed must trip")
    }

    // --- Invariant B: a sufficiently-complete enumeration is accepted. --------
    @Test
    fun `guard is silent when the observation meets the trust floor`() {
        assertNull(SyncEngine.remoteShrinkWarningOrNull(50, 100), "exactly 50% must pass")
        assertNull(SyncEngine.remoteShrinkWarningOrNull(60, 100), "60% observed must pass")
        assertNull(SyncEngine.remoteShrinkWarningOrNull(5917, 5917), "a complete scan must pass")
    }

    // --- Invariant C: small accounts are exempt (no false positive). ----------
    @Test
    fun `guard exempts accounts below the baseline floor`() {
        assertNull(SyncEngine.remoteShrinkWarningOrNull(1, 49), "baseline below floor is exempt")
        assertNull(SyncEngine.remoteShrinkWarningOrNull(0, 10), "near-empty drive is exempt")
    }

    // --- Invariant D: the guard is wired into the sync path and aborts before
    //     any reconcile/apply, leaving tracked rows intact. ---------------------
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: ShrinkFakeProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-shrink-root")
        dbDir = Files.createTempDirectory("ud-shrink-db")
        cacheRoot = Files.createTempDirectory("ud-shrink-cache")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = ShrinkFakeProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                reporter = ProgressReporter.Silent,
                cacheRoot = cacheRoot,
                cacheKey = "shrink-test",
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `sync aborts on a partial full enumeration without deleting tracked rows`() =
        runTest {
            // Establish a 100-row baseline via the view path (no downloads needed).
            repeat(100) { provider.putRemote("/f$it.txt", "v$it") }
            val seed = engine.enumerateRemoteIntoState(reset = false)
            assertTrue(seed.complete)
            assertEquals(100, db.getEntryCount())

            // The remote now lists only 17 items (silently-partial), and the next
            // pass is a FULL enumeration (delta_cursor cleared).
            for (i in 17 until 100) provider.removeRemote("/f$i.txt")
            db.setSyncState("delta_cursor", "")

            val ex = assertFailsWith<IllegalStateException> { engine.syncOnce(dryRun = false) }
            assertTrue(
                ex.message!!.contains("partial"),
                "guard message should name the partial enumeration: ${ex.message}",
            )

            // The abort happened before reconcile/apply — no row reaped.
            assertEquals(100, db.getEntryCount(), "no tracked row may be deleted by an aborted pass")
            assertNotNull(db.getEntry("/f0.txt"))
        }

    class ShrinkFakeProvider : CloudProvider {
        override val id = "shrink-fake"
        override val displayName = "Shrink Fake"
        override var isAuthenticated = true

        override fun capabilities(): Set<org.krost.unidrive.Capability> =
            setOf(org.krost.unidrive.Capability.Delta)

        private val remote = linkedMapOf<String, CloudItem>()
        private var nextCursorSeq = 0

        fun putRemote(path: String, content: String = "") {
            remote[path] =
                CloudItem(
                    id = "id-$path",
                    name = path.substringAfterLast("/"),
                    path = path,
                    size = content.toByteArray().size.toLong(),
                    isFolder = false,
                    modified = Instant.now(),
                    created = Instant.now(),
                    hash = "h-$content",
                    mimeType = null,
                )
        }

        fun removeRemote(path: String) {
            remote.remove(path)
        }

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String) = remote.getValue(path)

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            nextCursorSeq++
            return DeltaPage(
                items = remote.values.toList(),
                cursor = "cursor-$nextCursorSeq",
                hasMore = false,
                complete = true,
            )
        }

        override suspend fun download(remotePath: String, destination: Path): Long = error("no download")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("no upload")

        override suspend fun delete(remotePath: String) = error("no delete")

        override suspend fun createFolder(path: String) = error("no createFolder")

        override suspend fun move(fromPath: String, toPath: String) = error("no move")

        override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)
    }
}
